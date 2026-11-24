#include "base/diagnostics.h"
#include <sys/eventfd.h>
#ifdef LUDASHI_HOST_TEST
#include <fcntl.h>
#endif
#include <sys/socket.h>
#include <sys/un.h>
#include <cerrno>
#include "core/key_state.h"
#include "core/pointer_input.h"
#include "core/sysv_segments.h"
#include "core/window_tree.h"
#include "core/client_session.h"
#include "core/display_runtime.h"
#include "native_screen.h"
#include "native_displayx_bridge.h"

namespace ludashi::x11 {
DisplayRuntime::DisplayRuntime(DisplayDimensions info, bool validation, NativeRendererConfig renderer)
    : validationEnabled(validation), rendererConfig(std::move(renderer)), dimensions(info), resource_ids(128) {
    start_time = GetSystemTime();
    // NativeScreen starts workers during construction. Their notification fd
    // must already exist and remain immutable until those workers are joined.
    completion_fd.Reset(eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC));
    if (!completion_fd) throw std::runtime_error("Cannot create native X11 completion source");
    screen = std::make_unique<NativeScreen>(*this);
    keyboard = std::make_unique<KeyState>(*this);
    mouse = std::make_unique<PointerInput>(*this);
    window_manager = std::make_unique<WindowTree>(*this, info);
}
DisplayRuntime::~DisplayRuntime() {
    Shutdown();
    displayXBridge.reset();
    // Join presentation before any scene/resource objects are destroyed.
    screen.reset();
}
void DisplayRuntime::UpdateSurface(ANativeWindow* window) const {
    screen->UpdateSurface(window);
    if (window && displayXBridge) displayXBridge->ResetState();
}
void DisplayRuntime::Start(const std::string& path, const std::string& shm_path) {
    if (server_thread.joinable()) return;
    if (path.empty() || path.size() >= sizeof(sockaddr_un::sun_path))
        throw std::runtime_error("X11 socket path is too long");
    if (rendererConfig.backend == NativeRendererConfig::Backend::DisplayX && rendererConfig.trueDisplayX)
        displayXBridge = std::make_unique<NativeDisplayXBridge>(*this);
    shm_manager = std::make_unique<SysvSegments>(shm_path);
    server_fd.Reset(socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0));
    if (!server_fd) throw std::runtime_error("Cannot create native X11 socket");
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path.c_str(), path.size() + 1);
    // Never unlink an occupied display; Android's Java and native backends are exclusive.
    if (bind(server_fd.Get(), reinterpret_cast<sockaddr*>(&address), sizeof(address)) < 0)
        throw std::runtime_error("Cannot bind native X11 socket (display already occupied?)");
    bound_path = path;
    if (listen(server_fd.Get(), 128) < 0) throw std::runtime_error("Cannot listen on native X11 socket");
    epoll_fd.Reset(epoll_create1(EPOLL_CLOEXEC));
    shutdown_fd.Reset(eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC));
    if (!epoll_fd || !shutdown_fd || !completion_fd)
        throw std::runtime_error("Cannot create native X11 event loop");
    for (int fd : {server_fd.Get(), shutdown_fd.Get(), completion_fd.Get()}) {
        epoll_event event{}; event.events = EPOLLIN; event.data.fd = fd;
        if (epoll_ctl(epoll_fd.Get(), EPOLL_CTL_ADD, fd, &event) < 0)
            throw std::runtime_error("Cannot register native X11 event source");
    }
    server_thread = std::thread([this] { RunLoop(); });
}
#ifdef LUDASHI_HOST_TEST
void DisplayRuntime::StartTest(int channel, int shm_channel) {
    if (shm_channel >= 0) shm_manager = std::make_unique<SysvSegments>(shm_channel);
    server_fd.Reset(channel);
    epoll_fd.Reset(epoll_create1(EPOLL_CLOEXEC));
    shutdown_fd.Reset(eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC));
    for (int fd : {server_fd.Get(), shutdown_fd.Get(), completion_fd.Get()}) {
        epoll_event e{}; e.events = EPOLLIN; e.data.fd = fd;
        if (epoll_ctl(epoll_fd.Get(), EPOLL_CTL_ADD, fd, &e) < 0) throw std::runtime_error("test epoll failure");
    }
    server_thread = std::thread([this] { RunLoop(); });
}
#endif
void DisplayRuntime::Shutdown() {
    if (server_thread.joinable()) {
        uint64_t one = 1; (void)write(shutdown_fd.Get(), &one, sizeof(one));
        server_thread.join();
    }
    if (!bound_path.empty()) { unlink(bound_path.c_str()); bound_path.clear(); }
}
void DisplayRuntime::NotifyScreen() {
    if (completion_fd) { uint64_t one = 1; (void)write(completion_fd.Get(), &one, sizeof(one)); }
}
void DisplayRuntime::SetWritable(int fd, bool enabled) {
    if (!epoll_fd) return;
    epoll_event e{}; e.data.fd = fd;
    const auto client = clients.find(fd);
    const bool suspended = client != clients.end() && client->second->IsInputSuspended();
    e.events = (suspended ? 0 : EPOLLIN) | EPOLLRDHUP | (enabled ? EPOLLOUT : 0);
    int result;
    do { result = epoll_ctl(epoll_fd.Get(), EPOLL_CTL_MOD, fd, &e); }
    while (result < 0 && errno == EINTR);
}
void DisplayRuntime::RunLoop() {
    for (;;) {
        int timeout;
        bool pendingPresents;
        {
            auto lock = Lock();
            timeout = screen->IdleTimeoutMs();
            pendingPresents = timeout >= 0 || screen->HasWaitingPresents();
            // A bounded client turn may leave requests in userspace after the
            // socket was drained. Poll epoll without sleeping in that case.
            for (const auto& [fd, client] : clients)
                if (client->HasPendingRequests()) { timeout = 0; break; }
        }
        int count = epoll_wait(epoll_fd.Get(), events.data(), events.size(), timeout);
        if (count < 0) { if (errno == EINTR) continue; break; }
        // Prioritize producer buffer returns over another batch of requests.
        // This also services completions when a client keeps epoll continuously busy.
        if (pendingPresents) {
            auto lock = Lock();
            try { screen->DispatchCompletions(); }
            catch (const std::exception& e) { diagnostics::Write(diagnostics::Severity::Warning, "X11 completion: {}", e.what()); }
        }
        for (auto j = clients.begin(); j != clients.end();) {
            // The event loop owns the client map and these scheduling flags.
            // Only the DRI3 worker's completion bit is written across threads.
            if (!j->second->HasPendingRequests() && !j->second->IsWaiting()) {
                ++j;
                continue;
            }
            auto lock = Lock();
            try {
                j->second->ServiceInput();
                ++j;
            } catch (const std::exception& e) {
                diagnostics::Write(diagnostics::Severity::Warning, "X11 buffered client disconnected: {}", e.what()); j = clients.erase(j);
            }
        }
        for (int i = 0; i < count; ++i) {
            auto& nativeScreen = *screen;
            const bool trace = nativeScreen.DiagnosticsEnabled();
            const auto lockStart = trace ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            auto lock = Lock();
            if (trace) nativeScreen.RecordServerLock(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - lockStart).count());
            int fd = events[i].data.fd;
            if (fd == shutdown_fd.Get()) { clients.clear(); return; }
            if (fd == server_fd.Get()) {
                for (;;) {
#ifdef LUDASHI_HOST_TEST
                    char byte; alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
                    iovec iov{&byte, 1}; msghdr msg{}; msg.msg_iov = &iov; msg.msg_iovlen = 1;
                    msg.msg_control = control; msg.msg_controllen = sizeof(control);
                    int client = -1;
                    if (recvmsg(fd, &msg, MSG_DONTWAIT | MSG_CMSG_CLOEXEC) == 1) {
                        auto* c = CMSG_FIRSTHDR(&msg);
                        if (c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) memcpy(&client, CMSG_DATA(c), sizeof(client));
                    }
                    if (client >= 0) {
                        int flags;
                        do { flags = fcntl(client, F_GETFL); } while (flags < 0 && errno == EINTR);
                        int result = -1;
                        if (flags >= 0) {
                            do { result = fcntl(client, F_SETFL, flags | O_NONBLOCK); }
                            while (result < 0 && errno == EINTR);
                        }
                        if (result < 0) { close(client); continue; }
                    }
#else
                    int client = accept4(fd, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
#endif
                    if (client < 0) { if (errno == EINTR) continue; break; }
                    OwnedDescriptor accepted(client);
                    if (clients.size() >= 127) continue;
                    epoll_event e{}; e.data.fd = client; e.events = EPOLLIN | EPOLLRDHUP;
                    if (epoll_ctl(epoll_fd.Get(), EPOLL_CTL_ADD, client, &e) < 0) continue;
                    try { OnClientConnection(std::move(accepted)); }
                    catch (const std::exception& e) { diagnostics::Write(diagnostics::Severity::Warning, "X11 accept: {}", e.what()); }
                }
                continue;
            }
            if (fd == completion_fd.Get()) {
                uint64_t value; while (read(fd, &value, sizeof(value)) > 0) {}
                try { screen->DispatchCompletions(); }
                catch (const std::exception& e) { diagnostics::Write(diagnostics::Severity::Warning, "X11 completion: {}", e.what()); }
                // Resume only the clients whose SYNC fence is signaled; never spin in AwaitFence.
                for (auto j = clients.begin(); j != clients.end();) {
                    try { if (j->second->IsWaiting()) j->second->ServiceInput(); ++j; }
                    catch (const std::exception&) { j = clients.erase(j); }
                }
                continue;
            }
            auto client = clients.find(fd);
            if (client == clients.end()) continue;
            try {
                if (events[i].events & EPOLLIN) client->second->ServiceInput();
                if (events[i].events & EPOLLOUT) client->second->FlushOutput();
                if (events[i].events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) clients.erase(client);
            } catch (const std::exception& e) {
                diagnostics::Write(diagnostics::Severity::Warning, "X11 client {} disconnected: {}", fd, e.what()); clients.erase(fd);
            }
        }
    }
}
void DisplayRuntime::OnClientConnection(OwnedDescriptor socket) {
    const int fd = socket.Get();
    auto session = std::make_unique<ClientSession>(*this, std::move(socket));
    clients.emplace(fd, std::move(session));
}
void DisplayRuntime::AddResource(const sp<ProtocolObject>& r) {
    if (!resources.try_emplace(r->id, r).second) throw std::runtime_error("Duplicate X11 resource");
}
ProtocolObject* DisplayRuntime::GetResource(u32 id) const {
    auto it = resources.find(id); return it == resources.end() ? nullptr : it->second.get();
}
sp<ProtocolObject> DisplayRuntime::ShareResource(u32 id) const {
    auto it = resources.find(id); return it == resources.end() ? nullptr : it->second;
}
void DisplayRuntime::RemoveResource(u32 id) {
    const auto entry = resources.find(id);
    if (entry == resources.end()) return;
    if (entry->second->GetType() == ObjectKind::WindowNode) {
        auto* window = entry->second->As<WindowNode>();
        screen->WindowDestroyed(*window);
        selections.Release(window);
    }
    resources.erase(entry);
}
u64 DisplayRuntime::GetSystemTime() const {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
u32 DisplayRuntime::GetTimestamp() const { return std::max<u32>(1, GetSystemTime() - start_time); }
}


