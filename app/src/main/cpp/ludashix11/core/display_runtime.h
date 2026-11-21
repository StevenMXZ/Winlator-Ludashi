#pragma once
#include "native_renderer_config.h"

#include <array>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <android/native_window.h>
#include <sys/epoll.h>
#include "base/owned_descriptor.h"
#include "core/atom_catalog.h"
#include "core/client_id_ranges.h"
#include "core/display_dimensions.h"
#include "core/selection_owners.h"

namespace ludashi::x11 {

class ClientSession;
class WindowTree;
class KeyState;
class PointerInput;
class ProtocolObject;
class NativeScreen;
class SysvSegments;

class NativeDisplayXBridge;

class DisplayRuntime {
public:
    explicit DisplayRuntime(DisplayDimensions dimensions, bool validation = false, NativeRendererConfig renderer = {});

    ~DisplayRuntime();

    std::unique_lock<std::recursive_mutex> Lock() { return std::unique_lock(mutex); }

    const NativeRendererConfig& RendererConfig() const { return rendererConfig; }

    bool ValidationEnabled() const { return validationEnabled; }


    const DisplayDimensions& Dimensions() const { return dimensions; }

    SysvSegments& Segments() const { return *shm_manager; }

    NativeScreen& GetScreen() const { return *screen; }

    WindowTree& Windows() const { return *window_manager; }

    KeyState& Keys() const { return *keyboard; }

    PointerInput& PointerDevice() const { return *mouse; }

    AtomCatalog& Atoms() { return atoms; }
    const AtomCatalog& Atoms() const { return atoms; }

    ClientIdRanges& GetResourceIDs() { return resource_ids; }

    SelectionOwners& Selections() { return selections; }

    void UpdateSurface(ANativeWindow* window) const;

    void Start(const std::string& path, const std::string& shm_path);
    void NotifyScreen();
    // All accessed under DisplayRuntime::Lock. Producer fence signaling needs an
    // eventfd wake only when a protocol client or queued Present is waiting.
    void AddSyncWaiter() { ++syncWaiters; }
    void RemoveSyncWaiter() { --syncWaiters; }
    bool HasSyncWaiters() const { return syncWaiters != 0; }
#ifdef LUDASHI_HOST_TEST
    void StartTest(int channel, int shm_channel = -1);
    u64 TakeTestNotifications() {
        u64 value{};
        return read(completion_fd.Get(), &value, sizeof(value)) == sizeof(value) ? value : 0;
    }
#endif
    void SetWritable(int fd, bool enabled);

    void AddResource(const sp<ProtocolObject>& resource);

    ProtocolObject* GetResource(u32 id) const;
    sp<ProtocolObject> ShareResource(u32 id) const;

    void RemoveResource(u32 id);

    u32 GetTimestamp() const;
    void SelectRawInput(ClientSession* client, bool enabled);
    void SendRawMotion(s32 dx, s32 dy);
    void SendRawButton(u16 button, bool down);

private:
    void Shutdown();

    void RunLoop();

    void OnClientConnection(OwnedDescriptor socket);

    u64 GetSystemTime() const;

    const bool validationEnabled;
    const NativeRendererConfig rendererConfig;
    std::unique_ptr<NativeDisplayXBridge> displayXBridge;
    u64 start_time{};
    size_t syncWaiters{};
    std::recursive_mutex mutex;
    OwnedDescriptor server_fd{};
    OwnedDescriptor epoll_fd{};
    OwnedDescriptor shutdown_fd{};
    OwnedDescriptor completion_fd{};
    std::string bound_path;
    const DisplayDimensions dimensions;
    AtomCatalog atoms;
    ClientIdRanges resource_ids;
    std::array<epoll_event, 10> events{};
    std::unordered_map<int, std::unique_ptr<ClientSession>> clients;
    std::unordered_set<ClientSession*> rawInputClients;
    std::thread server_thread;
    std::unique_ptr<SysvSegments> shm_manager;
    std::unique_ptr<NativeScreen> screen;
    std::unique_ptr<WindowTree> window_manager;
    std::unique_ptr<KeyState> keyboard;
    std::unique_ptr<PointerInput> mouse;
    SelectionOwners selections;
    std::unordered_map<u32, sp<ProtocolObject>> resources;
};

}  // namespace ludashi::x11

