#pragma once
#include "native_renderer_config.h"

#include <array>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <android/native_window.h>
#include <jni.h>
#include <sys/epoll.h>
#include "base/fd.h"
#include "core/resource.h"
#include "core/screen_info.h"

namespace xorg {

class XClient;
class WindowManager;
class Keyboard;
class Mouse;
class ExtensionManager;
class SelectionManager;
class XResource;
class Visual;
class XScreen;
class ShmManager;

class NativeDisplayXBridge;

class XServer {
public:
    explicit XServer(jobject java_instance, ScreenInfo screen_info, bool validation = false, NativeRendererConfig renderer = {});

    ~XServer();

    std::unique_lock<std::recursive_mutex> Lock() { return std::unique_lock(mutex); }

    jobject GetJavaInstance() const { return java_instance; }

    const NativeRendererConfig& RendererConfig() const { return rendererConfig; }

    bool ValidationEnabled() const { return validationEnabled; }

    Visual* GetRootVisual() const { return root_visual.get(); }

    const ScreenInfo& GetScreenInfo() const { return screen_info; }

    ShmManager& GetShmManager() const { return *shm_manager; }

    XScreen& GetScreen() const { return *screen; }

    WindowManager& GetWindowManager() const { return *window_manager; }

    Keyboard& GetKeyboard() const { return *keyboard; }

    Mouse& GetMouse() const { return *mouse; }

    ResourceIDs& GetResourceIDs() { return resource_ids; }

    ExtensionManager& GetExtensionManager() const { return *extension_manager; }

    SelectionManager& GetSelectionManager() const { return *selection_manager; }

    void UpdateSurface(ANativeWindow* window) const;

    void SetBoolean(u32 item, bool value);

    void SetInteger(u32 item, u32 value);

    void Start(const std::string& path, const std::string& shm_path);
    void NotifyScreen();
    // All accessed under XServer::Lock. Producer fence signaling needs an
    // eventfd wake only when a protocol client or queued Present is waiting.
    void AddSyncWaiter() { ++syncWaiters; }
    void RemoveSyncWaiter() { --syncWaiters; }
    bool HasSyncWaiters() const { return syncWaiters != 0; }
#ifdef LUDASHI_HOST_TEST
    void StartTest(int channel, int shm_channel = -1);
    u64 TakeTestNotifications() {
        u64 value{};
        return read(completion_fd.Handle(), &value, sizeof(value)) == sizeof(value) ? value : 0;
    }
#endif
    void SetWritable(int fd, bool enabled);

    void AddResource(const sp<XResource>& resource);

    XResource* GetResource(u32 id) const;
    sp<XResource> ShareResource(u32 id) const;

    void RemoveResource(u32 id);

    u32 GetTimestamp() const;
    void SelectRawInput(XClient* client, bool enabled);
    void SendRawMotion(s32 dx, s32 dy);
    void SendRawButton(u16 button, bool down);

private:
    void Shutdown();

    void RunLoop();

    void OnClientConnection(int fd);

    u64 GetSystemTime() const;

    jobject java_instance;
    const bool validationEnabled;
    const NativeRendererConfig rendererConfig;
    std::unique_ptr<NativeDisplayXBridge> displayXBridge;
    u64 start_time{};
    size_t syncWaiters{};
    std::recursive_mutex mutex;
    base::ScopedFD server_fd{};
    base::ScopedFD epoll_fd{};
    base::ScopedFD shutdown_fd{};
    base::ScopedFD completion_fd{};
    std::string bound_path;
    ScreenInfo screen_info;
    ResourceIDs resource_ids;
    std::array<epoll_event, 10> events{};
    std::unordered_map<int, std::unique_ptr<XClient>> clients;
    std::unordered_set<XClient*> rawInputClients;
    std::thread server_thread;
    std::unique_ptr<ShmManager> shm_manager;
    std::unique_ptr<XScreen> screen;
    std::unique_ptr<WindowManager> window_manager;
    std::unique_ptr<Keyboard> keyboard;
    std::unique_ptr<Mouse> mouse;
    std::unique_ptr<ExtensionManager> extension_manager;
    std::unique_ptr<SelectionManager> selection_manager;
    std::unordered_map<u32, sp<XResource>> resources;
    sp<Visual> root_visual;
};

}  // namespace xorg

