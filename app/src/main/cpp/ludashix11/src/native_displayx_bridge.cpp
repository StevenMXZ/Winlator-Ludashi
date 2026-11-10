#include "native_displayx_bridge.h"
#include "native_screen.h"
#include "core/xserver.h"
#include "core/window.h"
#include "base/log.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <set>
#include "../../winlator/renderer/displayx_protocol.hpp"

namespace xorg {
namespace {
struct Peer {
    int fd;
    std::atomic<bool> connected{true};
    std::mutex output;
    std::set<u8> ids;
    explicit Peer(int value) : fd(value) {}
    ~Peer() {
        close(fd);
    }
    void Stop() {
        if (connected.exchange(false))
            shutdown(fd, SHUT_RDWR);
    }
    void Complete(u8 id, u32 index, u64 present, u32 mode) {
        if (present == UINT64_MAX && mode != 2)
            return;
        const auto packet = displayx_wire::completion({{id, present, index}});
        std::lock_guard lock(output);
        if (connected && !displayx_wire::sendPacket(fd, packet)) Stop();
    }
};
} // namespace
struct NativeDisplayXBridge::Impl {
    XServer &server;
    int listener{-1};
    std::atomic<bool> stopped{false};
    std::thread acceptThread;
    std::mutex clientsMutex;
    struct Client {
        sp<Peer> peer;
        std::thread worker;
    };
    std::vector<Client> clients;
    struct Swapchain {
        u32 window{}, mode{}, colorSpace{};
        std::array<float, 12> hdr{};
        bool hasHdr{};
        u64 generation{};
        std::vector<sp<NativeDrawable>> images;
        sp<NativeExternalFrame> latest;
    };
    explicit Impl(XServer &value) : server(value) {
        clients.reserve(16);
        listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        if (listener < 0)
            throw std::runtime_error("Cannot create X++ DisplayX+ socket");
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        memcpy(address.sun_path + 1, "displayx", 8);
        if (bind(listener, reinterpret_cast<sockaddr *>(&address), offsetof(sockaddr_un, sun_path) + 9) ||
            listen(listener, 8)) {
            close(listener);
            listener = -1;
            throw std::runtime_error("X++ DisplayX+ socket is already in use");
        }
        try {
            acceptThread = std::thread([this] { Accept(); });
        } catch (...) {
            close(listener);
            throw;
        }
    }
    ~Impl() {
        stopped = true;
        shutdown(listener, SHUT_RDWR);
        if (acceptThread.joinable())
            acceptThread.join();
        close(listener);
        for (auto &client : clients)
            client.peer->Stop();
        for (auto &client : clients)
            if (client.worker.joinable())
                client.worker.join();
    }
    void Accept() {
        while (!stopped) {
            int fd = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
            if (fd < 0) {
                if (errno == EINTR)
                    continue;
                return;
            }
            ucred credentials{};
            socklen_t length = sizeof(credentials);
            if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &length) ||
                credentials.uid != getuid()) {
                close(fd);
                continue;
            }
            timeval timeout{2, 0};
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            auto peer = std::make_shared<Peer>(fd);
            std::lock_guard lock(clientsMutex);
            // Reap disconnected workers; descriptor reuse cannot target a previous peer.
            for (auto i = clients.begin(); i != clients.end();) {
                if (!i->peer->connected) {
                    if (i->worker.joinable())
                        i->worker.join();
                    i = clients.erase(i);
                } else
                    ++i;
            }
            if (clients.size() >= 16) {
                peer->Stop();
                continue;
            }
            try {
                clients.push_back({peer, std::thread([this, peer] { Run(peer); })});
            } catch (const std::system_error &) {
                peer->Stop();
            }
        }
    }
    void Run(const sp<Peer> &peer) {
        std::map<u8, Swapchain> chains;
        auto remove = [&](Swapchain &chain) {
            auto lock = server.Lock();
            static_cast<NativeScreen &>(server.GetScreen())
                .RemoveExternal(chain.window, chain.generation, chain.latest);
            chain.latest.reset();
        };
        try {
            while (!stopped && peer->connected) {
                pollfd input{peer->fd, POLLIN, 0};
                int ready;
                do {
                    ready = poll(&input, 1, 100);
                } while (ready < 0 && errno == EINTR);
                if (ready == 0)
                    continue;
                if (ready < 0 || (input.revents & (POLLERR | POLLHUP | POLLNVAL)))
                    break;
                displayx_wire::Packet packet;
                if (!displayx_wire::receive(peer->fd, packet)) break;
                const u32 opcode = packet.get<u32>(0);
                if (opcode == displayx_wire::Create) {
                    if (packet.size != 25 || packet.fence >= 0) break;
                    const u8 id = packet.get<u8>(4);
                    const u32 count = packet.get<u32>(5), window = packet.get<u32>(9);
                    const u32 mode = packet.get<u32>(13), colorSpace = packet.get<u32>(21);
                    if (!count || count > 16 || mode > 3) break;
                    u32 width, height;
                    u64 generation;
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(window);
                        if (!resource || resource->GetType() != XResourceType::Window) break;
                        auto *w = resource->As<Window>();
                        width = w->GetWidth(); height = w->GetHeight(); generation = w->generation;
                    }
                    if (!width || !height) break;
                    Swapchain chain;
                    chain.window = window;
                    chain.mode = mode;
                    chain.colorSpace = colorSpace;
                    chain.generation = generation;
                    for (u32 i = 0; i < count; ++i) {
                        AHardwareBuffer *buffer{};
                        if (AHardwareBuffer_recvHandleFromUnixSocket(peer->fd, &buffer) || !buffer)
                            throw std::runtime_error("Invalid DisplayX+ buffer");
                        try {
                            chain.images.push_back(
                                std::make_shared<NativeDrawable>(width, height, 32, buffer));
                        } catch (...) {
                            AHardwareBuffer_release(buffer);
                            throw;
                        }
                        AHardwareBuffer_release(buffer);
                    }
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(window);
                        if (!resource || resource->GetType() != XResourceType::Window ||
                            resource->As<Window>()->generation != generation) break;
                    }
                    if (auto found = chains.find(id); found != chains.end())
                        remove(found->second);
                    chains.insert_or_assign(id, std::move(chain));
                    { std::lock_guard output(peer->output); peer->ids.insert(id); }
                } else if (opcode == displayx_wire::Present) {
                    if (packet.size != 17) break;
                    const u8 id = packet.get<u8>(4);
                    const u32 index = packet.get<u32>(5);
                    const u64 present = packet.get<u64>(9);
                    int fence = packet.takeFence();
                    auto found = chains.find(id);
                    if (found == chains.end() || index >= found->second.images.size()) {
                        if (fence >= 0) close(fence);
                        break;
                    }
                    auto &chain = found->second;
                    auto frame = std::make_shared<NativeExternalFrame>();
                    frame->drawable = chain.images[index];
                    frame->fence = fence;
                    frame->presentMode = chain.mode;
                    frame->colorSpace = chain.colorSpace;
                    frame->hasHdr = chain.hasHdr;
                    frame->hdr = chain.hdr;
                    frame->completion = [peer, id, index, present, mode = chain.mode] {
                        peer->Complete(id, index, present, mode);
                    };
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(chain.window);
                        if (!resource || resource->GetType() != XResourceType::Window ||
                            resource->As<Window>()->generation != chain.generation)
                            break;
                        chain.latest = frame;
                        static_cast<NativeScreen &>(server.GetScreen())
                            .UpdateExternal(chain.window, chain.generation, frame);
                    }
                    // FIFO publishes the next image only after Draw has claimed this one.
                    // Several claimed images can be in flight; completion still waits for presentation.
                    // Mailbox may replace an unsubmitted scene; RAII completion releases the dropped image.
                    if (chain.mode == 2) {
                        std::unique_lock lock(frame->completionMutex);
                        while (!frame->claimed && !frame->acknowledged && !stopped && peer->connected) {
                            frame->completionCv.wait_for(lock, std::chrono::milliseconds(100));
                            pollfd input{peer->fd, POLLIN, 0};
                            if (poll(&input, 1, 0) > 0 && (input.revents & (POLLHUP | POLLERR | POLLNVAL)))
                                peer->Stop();
                        }
                    }
                } else if (opcode == displayx_wire::Hdr) {
                    if (packet.size != 53 || packet.fence >= 0) break;
                    auto found = chains.find(packet.get<u8>(4));
                    if (found == chains.end()) break;
                    for (size_t i = 0; i < 12; ++i) {
                        found->second.hdr[i] = packet.get<float>(5 + i * 4);
                        if (!std::isfinite(found->second.hdr[i])) throw std::runtime_error("Invalid DisplayX HDR metadata");
                    }
                    found->second.hasHdr = true;
                } else if (opcode == displayx_wire::Destroy) {
                    if (packet.size != 5 || packet.fence >= 0) break;
                    const u8 id = packet.get<u8>(4);
                    if (auto found = chains.find(id); found != chains.end()) {
                        remove(found->second);
                        chains.erase(found);
                        std::lock_guard output(peer->output); peer->ids.erase(id);
                    }
                } else
                    break;
            }
        } catch (const std::exception &error) {
            LOGE("X++ DisplayX+ peer rejected: {}", error.what());
        }
        peer->Stop();
        for (auto &[id, chain] : chains)
            remove(chain);
    }
};
NativeDisplayXBridge::NativeDisplayXBridge(XServer &server) : impl(std::make_unique<Impl>(server)) {}
NativeDisplayXBridge::~NativeDisplayXBridge() = default;
void NativeDisplayXBridge::ResetState() {
    std::lock_guard clients(impl->clientsMutex);
    for (const auto& client : impl->clients) {
        std::lock_guard output(client.peer->output);
        for (u8 id : client.peer->ids) {
            std::vector<uint8_t> packet;
            displayx_wire::append(packet, uint32_t(displayx_wire::Reset));
            displayx_wire::append(packet, id);
            if (client.peer->connected && !displayx_wire::sendPacket(client.peer->fd, packet)) client.peer->Stop();
        }
    }
}
} // namespace xorg
