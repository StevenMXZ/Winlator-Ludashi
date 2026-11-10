#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <cerrno>
#include "base/log.h"
#include "core/extension.h"
#include "core/id_generator.h"
#include "core/keyboard.h"
#include "core/mouse.h"
#include "core/selection_manager.h"
#include "core/shm.h"
#include "core/visual.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xserver.h"
#include "native_screen.h"

namespace xorg {
XServer::XServer(jobject instance, ScreenInfo info, bool validation)
    : java_instance(instance), validationEnabled(validation), screen_info(info), resource_ids(128) {
    start_time = GetSystemTime();
    // NativeScreen starts workers during construction. Their notification fd
    // must already exist and remain immutable until those workers are joined.
    completion_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (!completion_fd.IsValid()) throw std::runtime_error("Cannot create native X11 completion source");
    screen = XScreen::Create(*this);
    keyboard = std::make_unique<Keyboard>(*this);
    mouse = std::make_unique<Mouse>(*this);
    window_manager = std::make_unique<WindowManager>(*this, *mouse, info);
    extension_manager = std::make_unique<ExtensionManager>();
    selection_manager = std::make_unique<SelectionManager>();
    root_visual = std::make_shared<Visual>(GenerateID(), 24, 8, 0x0000ff, 0x00ff00, 0xff0000);
}
XServer::~XServer() {
    Shutdown();
    // Join presentation before any scene/resource objects are destroyed.
    screen.reset();
}
void XServer::UpdateSurface(ANativeWindow* window) const { screen->UpdateSurface(window); }
void XServer::SetBoolean(u32 item, bool value) { (void)item; (void)value; }
void XServer::SetInteger(u32 item, u32 value) { (void)item; (void)value; }
void XServer::Start(const std::string& path, const std::string& shm_path) {
    if (server_thread.joinable()) return;
    if (path.empty() || path.size() >= sizeof(sockaddr_un::sun_path))
        throw std::runtime_error("X11 socket path is too long");
    shm_manager = std::make_unique<ShmManager>(shm_path);
    server_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (!server_fd.IsValid()) throw std::runtime_error("Cannot create native X11 socket");
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path.c_str(), path.size() + 1);
    // Never unlink an occupied display; Android's Java and native backends are exclusive.
    if (bind(server_fd.Handle(), reinterpret_cast<sockaddr*>(&address), sizeof(address)) < 0)
        throw std::runtime_error("Cannot bind native X11 socket (display already occupied?)");
    bound_path = path;
    if (listen(server_fd.Handle(), 128) < 0) throw std::runtime_error("Cannot listen on native X11 socket");
    epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    shutdown_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (!epoll_fd.IsValid() || !shutdown_fd.IsValid() || !completion_fd.IsValid())
        throw std::runtime_error("Cannot create native X11 event loop");
    for (int fd : {server_fd.Handle(), shutdown_fd.Handle(), completion_fd.Handle()}) {
        epoll_event event{}; event.events = EPOLLIN; event.data.fd = fd;
        if (epoll_ctl(epoll_fd.Handle(), EPOLL_CTL_ADD, fd, &event) < 0)
            throw std::runtime_error("Cannot register native X11 event source");
    }
    server_thread = std::thread([this] { RunLoop(); });
}
#ifdef LUDASHI_HOST_TEST
void XServer::StartTest(int channel, int shm_channel) {
    if (shm_channel >= 0) shm_manager = std::make_unique<ShmManager>(shm_channel);
    server_fd = channel;
    epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    shutdown_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    for (int fd : {server_fd.Handle(), shutdown_fd.Handle(), completion_fd.Handle()}) {
        epoll_event e{}; e.events = EPOLLIN; e.data.fd = fd;
        if (epoll_ctl(epoll_fd.Handle(), EPOLL_CTL_ADD, fd, &e) < 0) throw std::runtime_error("test epoll failure");
    }
    server_thread = std::thread([this] { RunLoop(); });
}
#endif
void XServer::Shutdown() {
    if (server_thread.joinable()) {
        uint64_t one = 1; (void)write(shutdown_fd.Handle(), &one, sizeof(one));
        server_thread.join();
    }
    if (!bound_path.empty()) { unlink(bound_path.c_str()); bound_path.clear(); }
}
void XServer::NotifyScreen() {
    if (completion_fd.IsValid()) { uint64_t one = 1; (void)write(completion_fd.Handle(), &one, sizeof(one)); }
}
void XServer::SetWritable(int fd, bool enabled) {
    if (!epoll_fd.IsValid()) return;
    epoll_event e{}; e.data.fd = fd;
    const auto client = clients.find(fd);
    const bool suspended = client != clients.end() && client->second->IsInputSuspended();
    e.events = (suspended ? 0 : EPOLLIN) | EPOLLRDHUP | (enabled ? EPOLLOUT : 0);
    int result;
    do { result = epoll_ctl(epoll_fd.Handle(), EPOLL_CTL_MOD, fd, &e); }
    while (result < 0 && errno == EINTR);
}
void XServer::RunLoop() {
    for (;;) {
        int timeout;
        {
            auto lock = Lock();
            timeout = static_cast<NativeScreen&>(*screen).IdleTimeoutMs();
            // A bounded client turn may leave requests in userspace after the
            // socket was drained. Poll epoll without sleeping in that case.
            for (const auto& [fd, client] : clients)
                if (client->HasPendingRequests()) { timeout = 0; break; }
        }
        int count = epoll_wait(epoll_fd.Handle(), events.data(), events.size(), timeout);
        if (count < 0) { if (errno == EINTR) continue; break; }
        // Prioritize producer buffer returns over another batch of requests.
        // This also services completions when a client keeps epoll continuously busy.
        {
            auto lock = Lock();
            try { static_cast<NativeScreen&>(*screen).DispatchCompletions(); }
            catch (const std::exception& e) { LOGW("X11 completion: {}", e.what()); }
        }
        for (auto j = clients.begin(); j != clients.end();) {
            auto lock = Lock();
            try {
                if (j->second->HasPendingRequests() || j->second->IsWaiting()) j->second->HandleRequest();
                ++j;
            } catch (const std::exception& e) {
                LOGW("X11 buffered client disconnected: {}", e.what()); j = clients.erase(j);
            }
        }
        for (int i = 0; i < count; ++i) {
            auto& nativeScreen = static_cast<NativeScreen&>(*screen);
            const bool trace = nativeScreen.DiagnosticsEnabled();
            const auto lockStart = trace ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            auto lock = Lock();
            if (trace) nativeScreen.RecordServerLock(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - lockStart).count());
            int fd = events[i].data.fd;
            if (fd == shutdown_fd.Handle()) { clients.clear(); return; }
            if (fd == server_fd.Handle()) {
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
                    if (client >= 0) base::SetNonblockingFD(client);
#else
                    int client = accept4(fd, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
#endif
                    if (client < 0) { if (errno == EINTR) continue; break; }
                    if (clients.size() >= 127) { close(client); continue; }
                    epoll_event e{}; e.data.fd = client; e.events = EPOLLIN | EPOLLRDHUP;
                    if (epoll_ctl(epoll_fd.Handle(), EPOLL_CTL_ADD, client, &e) < 0) { close(client); continue; }
                    try { OnClientConnection(client); }
                    catch (const std::exception& e) { close(client); LOGW("X11 accept: {}", e.what()); }
                }
                continue;
            }
            if (fd == completion_fd.Handle()) {
                uint64_t value; while (read(fd, &value, sizeof(value)) > 0) {}
                try { static_cast<NativeScreen&>(*screen).DispatchCompletions(); }
                catch (const std::exception& e) { LOGW("X11 completion: {}", e.what()); }
                // Resume only the clients whose SYNC fence is signaled; never spin in AwaitFence.
                for (auto j = clients.begin(); j != clients.end();) {
                    try { if (j->second->IsWaiting()) j->second->HandleRequest(); ++j; }
                    catch (const std::exception&) { j = clients.erase(j); }
                }
                continue;
            }
            auto client = clients.find(fd);
            if (client == clients.end()) continue;
            try {
                if (events[i].events & EPOLLIN) client->second->HandleRequest();
                if (events[i].events & EPOLLOUT) client->second->FlushOutput();
                if (events[i].events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) clients.erase(client);
            } catch (const std::exception& e) {
                LOGW("X11 client {} disconnected: {}", fd, e.what()); clients.erase(fd);
            }
        }
    }
}
void XServer::OnClientConnection(int fd) { clients[fd] = std::make_unique<XClient>(*this, fd); }
void XServer::AddResource(const sp<XResource>& r) {
    if (resources.contains(r->id)) throw std::runtime_error("Duplicate X11 resource");
    resources[r->id] = r;
}
XResource* XServer::GetResource(u32 id) const {
    auto it = resources.find(id); return it == resources.end() ? nullptr : it->second.get();
}
sp<XResource> XServer::ShareResource(u32 id) const {
    auto it = resources.find(id); return it == resources.end() ? nullptr : it->second;
}
void XServer::RemoveResource(u32 id) {
    auto* resource = GetResource(id);
    if (resource && resource->GetType() == XResourceType::Window) {
        static_cast<NativeScreen&>(*screen).WindowDestroyed(id);
        selection_manager->RemoveWindow(resource->As<Window>());
    }
    resources.erase(id);
}
u64 XServer::GetSystemTime() const {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
u32 XServer::GetTimestamp() const { return std::max<u32>(1, GetSystemTime() - start_time); }
}


