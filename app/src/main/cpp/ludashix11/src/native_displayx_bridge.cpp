#include "native_displayx_bridge.h"
#include "native_screen.h"
#include "core/xserver.h"
#include "core/window.h"
#include "base/log.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>

namespace xorg {
namespace {
struct Peer {
    int fd;
    std::atomic<bool> connected{true};
    std::mutex output;
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
        std::array<u8, 17> packet{};
        const u32 opcode = 4;
        memcpy(packet.data(), &opcode, 4);
        memcpy(packet.data() + 4, &id, 1);
        memcpy(packet.data() + 5, &present, 8);
        memcpy(packet.data() + 13, &index, 4);
        std::lock_guard lock(output);
        size_t offset{};
        while (connected && offset < packet.size()) {
            const auto sent =
                send(fd, packet.data() + offset, packet.size() - offset, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (sent < 0 && errno == EINTR)
                continue;
            if (sent <= 0) {
                Stop();
                break;
            }
            offset += sent;
        }
    }
};
bool Read(int fd, void *address, size_t size) {
    auto *next = static_cast<u8 *>(address);
    while (size) {
        const auto count = recv(fd, next, size, 0);
        if (count < 0 && errno == EINTR)
            continue;
        if (count <= 0)
            return false;
        next += count;
        size -= count;
    }
    return true;
}
int ReceiveFence(int fd) {
    char byte{};
    iovec data{&byte, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * 8)]{};
    msghdr message{};
    message.msg_iov = &data;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t count;
    do {
        count = recvmsg(fd, &message, MSG_CMSG_CLOEXEC);
    } while (count < 0 && errno == EINTR);
    std::vector<int> received;
    for (auto *header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
            header->cmsg_len < CMSG_LEN(0))
            continue;
        const size_t descriptors = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t i = 0; i < descriptors; ++i) {
            int value;
            memcpy(&value, CMSG_DATA(header) + i * sizeof(int), sizeof(value));
            received.push_back(value);
        }
    }
    if (count != 1 || (message.msg_flags & MSG_CTRUNC) || received.size() != 1) {
        for (int value : received)
            close(value);
        return -1;
    }
    return received.front();
}
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
        u32 window{}, mode{};
        u64 generation{};
        std::vector<sp<NativeDrawable>> images;
        sp<NativeExternalFrame> latest;
    };
    explicit Impl(XServer &value) : server(value) {
        clients.reserve(16);
        listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
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
                u32 opcode{};
                if (!Read(peer->fd, &opcode, 4))
                    break;
                if (opcode == 0)
                    continue;
                if (opcode == 1) {
                    u8 id;
                    u32 count, window, mode, format, width, height;
                    if (!Read(peer->fd, &id, 1) || !Read(peer->fd, &count, 4) ||
                        !Read(peer->fd, &window, 4) || !Read(peer->fd, &mode, 4) ||
                        !Read(peer->fd, &format, 4) || !Read(peer->fd, &width, 4) ||
                        !Read(peer->fd, &height, 4) || !count || count > 16 || !width || !height ||
                        width > 16384 || height > 16384)
                        break;
                    Swapchain chain;
                    chain.window = window;
                    chain.mode = mode == 2 ? 2 : 1;
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
                        if (!resource || resource->GetType() != XResourceType::Window)
                            break;
                        chain.generation = resource->As<Window>()->generation;
                    }
                    if (auto found = chains.find(id); found != chains.end())
                        remove(found->second);
                    chains.insert_or_assign(id, std::move(chain));
                } else if (opcode == 2) {
                    u8 id;
                    u32 index;
                    u64 present;
                    if (!Read(peer->fd, &id, 1) || !Read(peer->fd, &index, 4))
                        break;
                    int fence = ReceiveFence(peer->fd);
                    if (fence < 0)
                        break;
                    if (!Read(peer->fd, &present, 8)) {
                        close(fence);
                        break;
                    }
                    auto found = chains.find(id);
                    if (found == chains.end() || index >= found->second.images.size()) {
                        close(fence);
                        break;
                    }
                    auto &chain = found->second;
                    auto frame = std::make_shared<NativeExternalFrame>();
                    frame->drawable = chain.images[index];
                    frame->fence = fence;
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
                } else if (opcode == 3) {
                    u8 id;
                    if (!Read(peer->fd, &id, 1))
                        break;
                    if (auto found = chains.find(id); found != chains.end()) {
                        remove(found->second);
                        chains.erase(found);
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
} // namespace xorg
