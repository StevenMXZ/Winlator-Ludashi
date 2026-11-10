#pragma once
// Host model for compiling and exercising the actual native Vulkan adapter.
#include <condition_variable>
inline int ANativeWindow_getWidth(ANativeWindow*) { return 1280; }
inline int ANativeWindow_getHeight(ANativeWindow*) { return 720; }

struct TestFence {
    std::mutex mutex;
    std::condition_variable cv;
    bool signaled{true};
    void Signal() { std::lock_guard lock(mutex); signaled = true; cv.notify_all(); }
};
using VkDevice = int;
using VkFence = TestFence*;
using VkResult = int;
constexpr int VK_SUCCESS = 0, VK_NOT_READY = 1, VK_TIMEOUT = 2, VK_TRUE = 1;
using PFN_vkGetFenceStatus = VkResult (*)(VkDevice, VkFence);
using PFN_vkWaitForFences = VkResult (*)(VkDevice, uint32_t, const VkFence*, int, uint64_t);

class VulkanRendererContext {
public:
    inline static VulkanRendererContext* instance{};
    inline static std::atomic<unsigned> waits{};
    inline static bool holdWait{};
    VulkanRendererContext(ANativeWindow* w, int, int) : window(w) { instance = this; }
    ~VulkanRendererContext() { ANativeWindow_release(window); instance = nullptr; }
    void detachSurface() { surfaceDetached = true; }
    bool reattachSurface(ANativeWindow* w) {
        ANativeWindow_release(window); window = w; surfaceDetached = false; return true;
    }
    void onSurfaceResized(int, int) {}
    void setVerboseLog(bool) {}
    void setNativeDiagnostics(bool) {}
    void setTransform(float, float, float, float) {}
    void setCursorVisible(bool) {}
    void updatePointerPosition(int, int) {}
    void updateCursorImage(void*, int, int, int, int, int) {}
    struct Texture { bool isAHB{}; AHardwareBuffer* ahb{}; };
    std::unordered_map<int64_t, Texture> texMap;
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* b, int, int, int, int) { texMap[id] = {true, b}; }
    void updateWindowContent(int64_t id, void*, int, int, int, int, int) { texMap[id] = {}; }
    void removeWindow(int64_t id) { texMap.erase(id); }
    void setExternalRenderList(const int64_t*, const int*, const int*, int) {}
    void renderFrame() {
        auto& fence = fences[currentFrame];
        { std::lock_guard lock(fence.mutex); fence.signaled = false; }
        slotSerial[currentFrame] = ++serial;
        currentFrame = (currentFrame + 1) % 2;
    }
    static VkResult Status(VkDevice, VkFence fence) {
        std::lock_guard lock(fence->mutex); return fence->signaled ? VK_SUCCESS : VK_NOT_READY;
    }
    static VkResult Wait(VkDevice, uint32_t, const VkFence* fence, int, uint64_t timeout) {
        ++waits;
        std::unique_lock lock((*fence)->mutex);
        if (holdWait || timeout == UINT64_MAX) (*fence)->cv.wait(lock, [&] { return (*fence)->signaled; });
        else if (!(*fence)->cv.wait_for(lock, std::chrono::nanoseconds(timeout),
                                      [&] { return (*fence)->signaled; })) return VK_TIMEOUT;
        return VK_SUCCESS;
    }
    struct { PFN_vkGetFenceStatus GetFenceStatus{Status}; PFN_vkWaitForFences WaitForFences{Wait}; } vk_;
    TestFence fences[2];
    VkFence inFlightFences[2]{&fences[0], &fences[1]};
    uint64_t slotSerial[2]{}, serial{};
    uint32_t currentFrame{};
    VkDevice device{1};
    ANativeWindow* window;
    int surfaceWidth{1280}, surfaceHeight{720};
    std::atomic<bool> isRunning{true}, surfaceDetached{false};
    std::condition_variable dirtyCV;
    std::thread renderThread;
};
