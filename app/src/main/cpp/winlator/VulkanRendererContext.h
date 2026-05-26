#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <utility>
#include <vulkan/vulkan_android.h>
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkGetPastPresentationTimingGOOGLE GetPastPresentationTimingGOOGLE;
    PFN_vkGetRefreshCycleDurationGOOGLE GetRefreshCycleDurationGOOGLE;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <unordered_map>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;

struct WindowPushConstants {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
};

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight, void* adrenotoolsHandle = nullptr);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    /* bgraBytes: 1 = source AHB has BGRA byte order, receiver swaps via
     * format-aware vkCmdBlitImage in the local compositor blit. 0 = AHB
     * already has RGBA bytes; plain CmdCopyImage. Set by the layer based
     * on direct-render vs trojan-blit mode. */
    void scanoutSetBuffer(AHardwareBuffer* ahb, int acquireFenceFd, int slotIndex, int x, int y, int w, int h, int bgraBytes = 0);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    std::pair<int,int> pollReleaseFence();
    std::atomic<bool> scanoutActive{false};
    std::atomic<bool> gameFrameDelivered{false};
    std::atomic<bool> surfaceDetached{false};
    std::atomic<int>  scanoutSocketFd{-1};
    std::atomic<uint64_t> directFrameCount{0};

    /* === Compositor-latency instrumentation (DAC modes only) ===
     *
     * T1 = recv thread reads MSG_PRESENT  →  stored in latencyArriveUs[slot]
     * T2 = SurfaceFlinger setOnCommit callback fires for that slot
     * latency = T2 - T1, exposed as an EMA via the HUD.
     *
     * Lock-free: T1 is written by the recv thread; T2/EMA is updated inside
     * the onCommit callback (single writer). HUD reads the EMA via a JNI
     * getter on a Choreographer tick (single reader, value may be a few
     * frames stale — fine for a 60-sample-per-second display).
     *
     * Both clocks come from CLOCK_MONOTONIC which is kernel-wide on Linux,
     * so the subtraction is meaningful even though the timestamps are
     * captured on different threads. */
    static constexpr int LATENCY_SLOT_MAX = 4;  /* matches AHB pool size */
    std::atomic<uint64_t> latencyArriveUs[LATENCY_SLOT_MAX] = {};
    std::atomic<uint64_t> latencyEmaUs{0};      /* 0 = no data yet */

    /* Native (X11) mode latency. T1 stamped from Java's onUpdateWindowContent
     * via the nativeSetX11FrameT1 JNI bridge. T2 used to be captured right
     * after vkQueuePresentKHR returned, but that fires before SurfaceFlinger
     * has actually displayed the frame — under-reporting the true compositor
     * latency by ~1 vsync. The new path uses VK_GOOGLE_display_timing to ask
     * the driver "what time was this presentId actually displayed?", giving
     * an apples-to-apples comparison with DAC's setOnCommit timestamp.
     *
     * Compare-exchange-from-0 on the Java side: first update of a render
     * cycle wins, subsequent updates (e.g. for additional windows in the
     * same frame) are ignored. The QueuePresent path swaps it back to 0 so
     * the next cycle can stamp fresh.
     *
     * Both X11 and DAC paths write to the SHARED latencyEmaUs above, since
     * only one pipeline is active per session and the HUD reads a single
     * number regardless of source. */
    std::atomic<uint64_t> latencyX11ArriveUs{0};

    /* === VK_GOOGLE_display_timing ring buffer ===
     * For each Native present we attach a monotonic presentId via
     * VkPresentTimesInfoGOOGLE and remember (presentId, T1) here. Later
     * vkGetPastPresentationTimingGOOGLE returns the actualPresentTime
     * (CLOCK_MONOTONIC ns) for past presents; we match presentId →
     * arriveT1Us, compute the delta, and feed the EMA.
     *
     * Owned exclusively by the render thread (single-writer). The query
     * pulls results into a transient local array each frame — no atomics
     * needed beyond the EMA itself. */
    bool displayTimingSupported = false;
    uint64_t nextPresentId = 1;  /* 0 means "no timing requested" per spec */
    static constexpr int PRESENT_RING_SIZE = 32;
    struct PresentEntry {
        uint64_t presentId;
        uint64_t arriveT1Us;
    };
    PresentEntry presentRing[PRESENT_RING_SIZE] = {};
    int presentRingHead = 0;

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setSwapRB(bool enabled);
    void setEffect(int effectId, float sharpness);
    void setPresentMode(VkPresentModeKHR mode);

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        VkSubresourceLayout  imgLayout      = {};
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        bool                 useLinear      = false;
        bool                 stgCached      = false;
        AHardwareBuffer*     ahb            = nullptr;
    };
    struct AHBCached {
        VkImage         img  = VK_NULL_HANDLE;
        VkDeviceMemory  mem  = VK_NULL_HANDLE;
        VkImageView     view = VK_NULL_HANDLE;
        VkDescriptorSet ds   = VK_NULL_HANDLE;
        int             w    = 0;
        int             h    = 0;
    };
    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
        bool            dirtyAHB       = false;
        bool            useLinear      = false;
        bool            dirtyLinear    = false;
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode = 0;
    bool swapRB = false;
    int activeEffectId = 0;
    float activeSharpness = 1.0f;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;
    std::unordered_map<AHardwareBuffer*, AHBCached> ahbTexCache;
    std::list<AHardwareBuffer*>                  ahbCacheLRU;
    static constexpr size_t AHB_CACHE_MAX_NORMAL  =  6;
    static constexpr size_t AHB_CACHE_MAX_SCANOUT =  2;
    std::vector<WinTex>    deleteQueue;
    std::vector<RenderEntry> renderList;

    void*  scanoutGameSC      = nullptr;
    void*  scanoutCursorSC    = nullptr;
    void*  scanoutCursorBuf   = nullptr;
    int32_t scanoutCursorBufW = 0;
    int32_t scanoutCursorBufH = 0;
    AHardwareBuffer*  scanoutLocalAhb     = nullptr;
    VkImage           scanoutLocalImg     = VK_NULL_HANDLE;
    VkDeviceMemory    scanoutLocalMem     = VK_NULL_HANDLE;
    int               scanoutLocalW       = 0;
    int               scanoutLocalH       = 0;
    uint32_t          scanoutLocalAhbFormat = 0;  /* HAL pixel format the local AHB was allocated with;
                                                  * reallocate if the requested format changes (e.g.
                                                  * switching between trojan-blit RGBA and direct-render BGRA modes). */
    bool              scanoutNeedsGpuBlit = false;
    bool   scanoutApiLoaded   = false;
    bool   scanoutEnvGpuBlit  = false;
    bool   scanoutAlwaysGpuBlit = false;
    void*  fnSCCreateFromWin  = nullptr;
    void*  fnSCRelease        = nullptr;
    void*  fnSTCreate         = nullptr;
    void*  fnSTDelete         = nullptr;
    void*  fnSTApply          = nullptr;
    void*  fnSTSetBuffer      = nullptr;
    void*  fnSTSetZOrder      = nullptr;
    void*  fnSTSetVisibility  = nullptr;
    void*  fnSTSetGeometry    = nullptr;
    void*  fnSTSetBackPressure = nullptr;
    void*  fnSTSetOnComplete   = nullptr;
    void*  fnSTSetOnCommit     = nullptr;  /* ASurfaceTransaction_setOnCommit, API 31+ */
    void*  fnSTSetFrameRate    = nullptr;  /* ASurfaceTransaction_setFrameRate, API 30+ */
    void*  fnSTSetBufferTransparency = nullptr;  /* ASurfaceTransaction_setBufferTransparency, API 29+ */
    bool   loadScanoutApi();

    int32_t scanoutDstX=0, scanoutDstY=0, scanoutDstW=0, scanoutDstH=0;

    int32_t lastDstX=0, lastDstY=0, lastDstW=0, lastDstH=0;
    bool    gameScVisible      = false;

    struct ScanoutPending { AHardwareBuffer* ahb=nullptr; int acquireFenceFd=-1; int slotIndex=-1; int x=0,y=0,w=0,h=0; int bgraBytes=0; };
    std::mutex        scanoutMutex;
    ScanoutPending    scanoutPending{};
    std::atomic<bool> scanoutPendingDirty{false};

    struct ReleasePending { int slotIndex; int releaseFd; };
    std::mutex                   releaseMutex;
    std::vector<ReleasePending>  releaseQueue;
    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorPool cursorPool = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkPipeline       cursorPipe = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;
    VkFence                      oneTimeFence = VK_NULL_HANDLE;
    VkCommandBuffer              scanoutBlitCb = VK_NULL_HANDLE;
    VkFence                      scanoutBlitFence = VK_NULL_HANDLE;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    std::shared_mutex frameMutex;

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    /* overrideFormat: when not VK_FORMAT_UNDEFINED, forces the imported
     * VkImage's format regardless of the swapRB heuristic. Used by the
     * direct-render scanout path to import the source AHB as B8G8R8A8 so
     * a subsequent vkCmdBlitImage performs an R↔B channel swap. */
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb, VkFormat overrideFormat = VK_FORMAT_UNDEFINED);
    bool  ensureScanoutLocalAhb(int w, int h, uint32_t ahbFormat);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
