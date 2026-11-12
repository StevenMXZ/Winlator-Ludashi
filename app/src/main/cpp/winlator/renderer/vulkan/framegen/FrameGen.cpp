#include "FrameGen.h"
#include "VulkanRendererContext.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <stdexcept>
#include <thread>

#include "fg_down_comp.h"
#include "fg_down_r8_comp.h"
#include "fg_me_comp.h"
#include "fg_select_comp.h"
#include "fg_interp_comp.h"
#include "fg_scd_comp.h"
#include "fg_copy_comp.h"

#define FG_TAG "Winlator_FrameGen"
#ifdef WINLATOR_RELEASE
#define FG_LOGI(...) do {} while (0)
#define FG_LOGE(...) do {} while (0)
#else
#define FG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FG_TAG, __VA_ARGS__)
#define FG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FG_TAG, __VA_ARGS__)
#endif

namespace {

using Clock = std::chrono::steady_clock;

// Performance keeps every frame between the content on screen and the newest
// one; with a hold of about three source intervals that is four, plus the
// capture in progress.
constexpr int      kRing        = 6;
constexpr int      kMaxLevels   = 6;
// A 30 fps source on a 120 Hz display needs three; one more absorbs a late frame.
constexpr int      kMaxGen      = 4;
// Frames of the next pair are written while the previous pair still has presents queued.
constexpr int      kGenImages   = 10;
constexpr uint32_t kMinLevelDim = 24;  // coarsest pyramid level is at least this large
constexpr uint32_t kBlock       = 8;   // motion block size in pixels of each level
constexpr uint32_t kStamps      = 5;   // start, pyramid, motion, select, end
// A buffer must be queued this long before a vsync to be shown on it.
constexpr double   kLatchMs     = 2.0;
// Performance: CPU time from planning a frame to having its work submitted.
constexpr double   kQueueMs     = 1.0;
// Performance: every on/off switch jumps the content by the whole hold (about
// 40 ms), so a source hovering near the refresh must not toggle. Generation
// starts below kDynOnRatio of the refresh and stops only above kDynOffRatio.
// Starting is quick because a slowing game needs it now; stopping is slow.
constexpr double   kDynOnRatio  = 0.92;
constexpr double   kDynOffRatio = 0.98;
constexpr int      kDynOnVotes  = 8;
constexpr int      kDynOffVotes = 60;
// Performance: a real frame is shown on a vsync when its timing error there is
// at most this share of a refresh, else that vsync is interpolated. Just under
// half a refresh gives every real frame its nearest vsync, and still only one
// when the playout runs at its slowest speed.
constexpr double   kSnapRefresh = 0.45;
constexpr double   kMaxHoldMs   = 100.0;
// Performance playout: the shown content time follows (vsync - hold) at a speed
// within 1 +- kSpeedRange, correcting its error over about kCatchMs, so a change
// of hold or a late frame reads as a slight change of speed instead of a jump.
// Past kJumpMs behind (a hitch of the game) it jumps.
constexpr double   kSpeedRange  = 0.1;
constexpr double   kCatchMs     = 100.0;
constexpr double   kJumpMs      = 50.0;
// Performance: when the next frame is late the content eases toward the last
// real frame, covering this share of the remaining distance per vsync, instead
// of freezing on it.
constexpr double   kBrake       = 0.6;
// Performance: frames further apart than this are not interpolated.
constexpr double   kMaxMorphMs  = 120.0;
// Performance content clock: share of the phase error corrected per frame. Low
// values keep paired arrivals from bending motion.
constexpr double   kClockPhase  = 0.2;
// The clock interval is the mean over this many arrivals (span / count). Feeding
// it from per-frame errors was biased: a frame pressed against the previous one
// pulled it down, while the late frame after it was a hitch and not learned.
constexpr int      kRateSamples = 24;
// Performance: an update closer than this to the previous frame is that frame again.
constexpr double   kDupMs       = 2.5;
// The delay covers this share of the frames of the last kLateWindowMs; later
// ones are absorbed by the playout brake. A frame later than kHitchIntervals
// clock intervals is a hitch of the game itself: covering it would add its
// whole length as latency to every frame.
constexpr int      kLateSamples = 64;
constexpr double   kLateWindowMs = 2000.0;
constexpr double   kLateShare   = 0.9;
constexpr double   kHitchIntervals = 2.0;

// Minimum share of the screen a window must cover to count as "the game".
constexpr float kContentAreaShare = 0.10f;

std::atomic<int64_t> gVsyncNs{0};
// Power saver X3: a still image (loading screens, menus) runs uncapped, since
// some games load per frame and crawl at a third of the refresh. Residuals are
// the mean motion-compensated luma error of a pair; the gap between the two
// thresholds keeps a near-still scene from toggling.
std::atomic<bool> gSourceUncapped{false};
constexpr double kStillRes    = 0.0005;
constexpr double kMoveRes     = 0.0015;
constexpr int    kStillPairs  = 20;    // about a second at the X3 cap
constexpr double kProbeMs     = 50.0;  // analysis rate while uncapped

// Layout matches the shader push-constant blocks (std140). Images are allocated
// for High; allocSize is that image and the active rectangle is its top-left.
struct DownPC {
    float ratio; int32_t fromColor; int32_t activeDst[2];
    float srcActive[2]; float srcAlloc[2];
};
struct MePC {
    int32_t size[2]; int32_t mode, flags; float coarseScale[2];
    float tempScale, lambda, phase, pad;
    float allocSize[2]; int32_t blockSize[2], coarseBlocks[2], tempBlocks[2];
};
struct SelPC {
    int32_t size[2]; float lambda, zeroBias, coherentTh, phase;
    int32_t mode; float pad; float allocSize[2]; int32_t blockSize[2];
};
struct InterpPC {
    float outSize[2]; float fieldSize[2]; float t, confLo, confHi, staticTh, fieldT;
    int32_t mode;
};
struct ScdPC    { int32_t size[2]; float cutTh, cutRatio; float pad[4]; };
struct CopyPC   { int32_t unused[2]; };
constexpr uint32_t kPushSize = 128;
static_assert(sizeof(DownPC) == 32 && offsetof(DownPC, srcActive) == 16, "DownPC layout");
static_assert(sizeof(MePC) == 72 && offsetof(MePC, allocSize) == 40 &&
              offsetof(MePC, blockSize) == 48, "MePC layout");
static_assert(sizeof(SelPC) == 48 && offsetof(SelPC, allocSize) == 32, "SelPC layout");
static_assert(sizeof(InterpPC) == 40 && offsetof(InterpPC, mode) == 36, "InterpPC layout");
static_assert(sizeof(MePC) <= kPushSize && sizeof(SelPC) <= kPushSize &&
              sizeof(InterpPC) <= kPushSize && sizeof(ScdPC) <= kPushSize &&
              sizeof(DownPC) <= kPushSize, "push constant size");

enum MeFlags : int32_t { kMeCoarse = 1, kMeTemporal = 2, kMeHalfPel = 4, kMeWide = 8 };

// Per quality: shortcuts trade edge precision inside coherent or static regions for GPU time.
constexpr float kCoherentTh[3] = {1.0f, 0.75f, 0.5f};
constexpr float kStaticTh[3]   = {0.5f, 0.25f, 0.25f};
// Short side of the motion pyramid. Resources are allocated for High; Low and
// Medium dispatch a smaller top-left rectangle, so switching does not rebuild.
constexpr float kShortSide[3]  = {192.f, 270.f, 360.f};

double msBetween(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

float halfToFloat(uint16_t h) {
    const int e = (h >> 10) & 0x1f;
    const float m = (float)(h & 0x3ff);
    const float v = e == 0 ? std::ldexp(m, -24)
                  : e == 31 ? INFINITY
                  : std::ldexp(m + 1024.0f, e - 25);
    return (h & 0x8000) ? -v : v;
}

}  // namespace

struct FgImage {
    VkImage        image  = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView    view   = VK_NULL_HANDLE;
};

struct FrameGenState {
    // Configuration, written from any thread.
    std::atomic<bool>     enabled{false};
    std::atomic<int>      mode{0};      // 0 power saver, 1 performance
    std::atomic<int>      quality{1};   // motion estimation detail: 0 low, 1 medium, 2 high
    std::atomic<int>      multiplier{2};  // power saver: output frames per real frame
    std::atomic<float>    targetHz{60.f};  // display refresh rate
    std::atomic<uint64_t> contentSerial{0};
    std::atomic<bool>     reconfigure{false};

    std::mutex    statsMutex;
    FrameGenStats stats;

    // Everything below is touched by the render thread only.
    bool active = false;
    std::atomic<bool> failed{false};  // also cleared by the configuring thread

    struct {
        PFN_vkCreateComputePipelines CreateComputePipelines = nullptr;
        PFN_vkCmdDispatch            CmdDispatch            = nullptr;
        PFN_vkCreateQueryPool        CreateQueryPool        = nullptr;
        PFN_vkDestroyQueryPool       DestroyQueryPool       = nullptr;
        PFN_vkCmdResetQueryPool      CmdResetQueryPool      = nullptr;
        PFN_vkCmdWriteTimestamp      CmdWriteTimestamp      = nullptr;
        PFN_vkGetQueryPoolResults    GetQueryPoolResults    = nullptr;
        PFN_vkCmdClearColorImage     CmdClearColorImage     = nullptr;
        PFN_vkCmdCopyImageToBuffer   CmdCopyImageToBuffer   = nullptr;
    } fvk;
    float tsPeriodNs = 0.f;
    bool  tsValid = false;

    // Geometry. lw/bw are the active rectangle; alw/abw are the allocated images.
    uint32_t W = 0, H = 0;
    int      levels = 0, allocLevels = 0;
    uint32_t lw[kMaxLevels] = {}, lh[kMaxLevels] = {};
    uint32_t bw[kMaxLevels] = {}, bh[kMaxLevels] = {};  // active block field per level
    uint32_t alw[kMaxLevels] = {}, alh[kMaxLevels] = {};
    uint32_t abw[kMaxLevels] = {}, abh[kMaxLevels] = {};
    int      appliedQuality = -1;
    bool     lumaR8 = false;
    VkFormat lumaFormat = VK_FORMAT_R8G8B8A8_UNORM;

    // FIFO keeps every presented frame on screen for whole refreshes, in order.
    // MAILBOX may replace a generated frame with the next real one.
    bool             forcedFifo = false;
    VkPresentModeKHR userPresentMode = VK_PRESENT_MODE_FIFO_KHR;

    // GPU objects
    VkRenderPass          scenePass = VK_NULL_HANDLE;
    VkDescriptorSetLayout compLayout = VK_NULL_HANDLE;
    VkPipelineLayout      compPipeLayout = VK_NULL_HANDLE;
    VkDescriptorPool      pool = VK_NULL_HANDLE;
    VkSampler             linearSampler = VK_NULL_HANDLE;
    VkSampler             nearestSampler = VK_NULL_HANDLE;
    VkPipeline            pipeDown = VK_NULL_HANDLE, pipeMe = VK_NULL_HANDLE,
                          pipeSelect = VK_NULL_HANDLE, pipeInterp = VK_NULL_HANDLE,
                          pipeScd = VK_NULL_HANDLE, pipeCopy = VK_NULL_HANDLE;
    VkQueryPool           queryPool = VK_NULL_HANDLE;
    VkSemaphore           sceneDone = VK_NULL_HANDLE;
    VkSemaphore           computeDone = VK_NULL_HANDLE;

    FgImage      scene[kRing];
    VkFramebuffer sceneFb[kRing] = {};
    FgImage      pyr[kRing][kMaxLevels];  // luma pyramid
    FgImage      fieldA[kMaxLevels];      // block vectors after the search
    FgImage      fieldB[kMaxLevels];      // block vectors after propagation
    FgImage      fieldPrev;               // finest field of the previous pair
    // Per-pixel vectors and scene-cut result of the pair ending at each slot, so
    // a pair can still be interpolated after the next one has been analysed.
    FgImage      vecField[kRing];
    FgImage      scd[kRing];
    FgImage      scdPrev;
    // Host copy of each slot's scene-cut result, for the perf log.
    VkBuffer       scdReadBuf = VK_NULL_HANDLE;
    VkDeviceMemory scdReadMem = VK_NULL_HANDLE;
    const uint16_t* scdRead = nullptr;
    bool           scdPending[kRing] = {};
    FgImage      dummy;
    FgImage      gen[kGenImages];
    int          genHead = 0;  // first image the next generated batch writes
    bool         haveFieldPrev = false;

    VkDescriptorSet dsDown0[kRing] = {};
    VkDescriptorSet dsDownN[kRing][kMaxLevels] = {};
    VkDescriptorSet dsSearch[kRing][kRing][kMaxLevels] = {};
    VkDescriptorSet dsProp[kRing][kRing][kMaxLevels] = {};   // A -> B
    VkDescriptorSet dsSelect[kRing][kRing] = {};
    VkDescriptorSet dsScd[kRing] = {};
    VkDescriptorSet dsKeepField = VK_NULL_HANDLE;
    VkDescriptorSet dsKeepScd[kRing] = {};
    VkDescriptorSet dsInterp[kRing][kRing][kGenImages] = {};
    VkDescriptorSet composeScene[kRing] = {};
    VkDescriptorSet composeGen[kGenImages] = {};

    VkCommandBuffer sceneCb[kRing] = {};
    VkCommandBuffer fgCb[kRing] = {};
    VkCommandBuffer barrierCb = VK_NULL_HANDLE;
    VkFence         sceneFence[kRing] = {};
    VkFence         fgFence[kRing] = {};
    bool            fgUsed[kRing] = {};
    // Performance: one interpolation per vsync, indexed by its gen image.
    VkCommandBuffer interpCb[kGenImages] = {};
    VkFence         interpFence[kGenImages] = {};
    bool            interpUsed[kGenImages] = {};
    uint64_t        slotSerial[kRing] = {};

    // Ring and timing
    int  prevSlot = -1, curSlot = -1;
    uint64_t slotUse[kRing] = {}, slotClock = 0;
    bool pyrValid[kRing] = {};
    uint64_t lastSerial = 0;
    bool haveArrival = false;
    Clock::time_point lastArrival;
    double intervalMs = 0.0, intervalDev = 0.0;
    // Performance: games often deliver frames in uneven bursts while their
    // content advances evenly, so each frame gets a content time on a smoothed
    // clock instead of its arrival time. A frame's lateness is the gap between
    // its arrival and the content time of the frame before it, i.e. how far
    // behind the display has to run for it to be in time; lateMs is the
    // kLateShare quantile of recent ones.
    Clock::time_point contentTime{}, prevContentTime{};
    double clockIvMs = 0.0, lateMs = 0.0;
    bool   haveClock = false, hitchFrame = false;
    Clock::time_point rateAt[kRateSamples] = {};
    int    rateCount = 0, rateHead = 0;
    double lateHist[kLateSamples] = {};
    Clock::time_point lateAt[kLateSamples] = {};
    int    lateCount = 0, lateHead = 0;
    int    intervalSamples = 0;
    int    kApplied = -1, kVotes = 0;
    int    runMode = -1;
    // Performance: the real frames still needed for display, oldest first, on
    // the content clock. Every vsync shows content time dynX, interpolated
    // between the two frames around it; dynV is the last vsync filled.
    // paired: motion against the previous frame is (or will be) estimated;
    // pending: that estimation is not submitted yet.
    struct DynFrame { int slot; Clock::time_point ct; bool paired, cut, pending; };
    DynFrame dyn[kRing] = {};
    int    dynCount = 0;
    Clock::time_point dynX{}, dynV{};
    bool   haveDynX = false, haveDynV = false;
    bool   dynStalled = false;  // stopped at the newest frame waiting for the next one
    VkDescriptorSet dynShown = VK_NULL_HANDLE;
    double holdMs = 0.0;
    int    stillCount = 0;
    Clock::time_point lastProbe{};
    void readScd(int slot);
    void pollScd(VulkanRendererContext* c);
    void updateStill(double res, bool cut);
    void setUncapped(bool on);
    double gpuMsEma = 0.0, interpMsEma = 0.0;
    double stageMs[kStamps - 1] = {};  // pyramid, motion + scene cut, select, interp + copies
    bool   overloaded = false;
    int    overloadVotes = 0;
    int    qualityCap = 2;        // lowered when the GPU cannot keep up
    int    userQuality = -1;      // last requested quality; a change lifts the cap
    int    effectiveQuality() const { return std::min(quality.load(), qualityCap); }
    uint32_t newFrameCount = 0;
    std::vector<VulkanRendererContext::DrawEntry> composeDraws;

    // Statistics window
    Clock::time_point statsStart;
    uint32_t srcCount = 0, outCount = 0;
    double   latencyEma = 0.0;
    int      lastK = 0;

    struct FrameBuild {
        float ox = 0, oy = 0, sx = 1, sy = 1, cw = 1, ch = 1;
        short ptrX = 0, ptrY = 0, curHotX = 0, curHotY = 0, curW = 0, curH = 0;
        bool  curVis = false;
        VkBuffer curUpload = VK_NULL_HANDLE;
        bool  hasCurUpload = false;
        VkRect2D scissor{};
        struct PendingAHB { AHardwareBuffer* ahb; VkImage image; };
        struct PendingTex { int64_t id; VkImage image; };
        std::vector<PendingAHB> pendingAHB;
        std::vector<PendingTex> pendingTex;
    };

    // ---- lifecycle ----
    bool loadFunctions(VulkanRendererContext* c);
    bool activate(VulkanRendererContext* c);
    void deactivate(VulkanRendererContext* c);
    bool createPipelines(VulkanRendererContext* c);
    bool createResources(VulkanRendererContext* c);
    void geometryFor(int quality, int& outLevels, uint32_t* ow, uint32_t* oh,
                     uint32_t* obw, uint32_t* obh) const;
    void setActiveGeometry(int quality);
    void noteQualityChange();
    void destroyResources(VulkanRendererContext* c);
    void destroyPipelines(VulkanRendererContext* c);
    bool makeImage(VulkanRendererContext* c, FgImage& img, uint32_t w, uint32_t h,
                   VkFormat fmt, VkImageUsageFlags usage);
    void destroyImage(VulkanRendererContext* c, FgImage& img);
    VkPipeline makeComputePipeline(VulkanRendererContext* c, const uint32_t* code, size_t size);
    VkDescriptorSet allocSet(VulkanRendererContext* c, VkDescriptorSetLayout layout);
    void writeCompute(VulkanRendererContext* c, VkDescriptorSet ds,
                      VkImageView a, VkImageLayout la, VkImageView b, VkImageLayout lb,
                      VkImageView cc, VkImageLayout lc, VkImageView out,
                      VkImageView extra = VK_NULL_HANDLE);
    void writeCompose(VulkanRendererContext* c, VkDescriptorSet ds, VkImageView view,
                      VkImageLayout layout);

    // ---- per frame ----
    bool renderFrame(VulkanRendererContext* c);
    void prepare(VulkanRendererContext* c, FrameBuild& fb);
    void retireTextures(VulkanRendererContext* c, int slot);
    bool presentCompose(VulkanRendererContext* c, VkDescriptorSet ds, const FrameBuild& fb,
                        bool withCursorUpload, VkSemaphore extraWait = VK_NULL_HANDLE);
    void recordCompute(VulkanRendererContext* c, VkCommandBuffer cb, int slot, int prev, int cur,
                       const float* phases, int k, int genBase, bool needPrevPyr);
    void buildPyramid(VulkanRendererContext* c, VkCommandBuffer cb, int slot);
    void dispatch(VulkanRendererContext* c, VkCommandBuffer cb, VkPipeline pipe, VkDescriptorSet ds,
                  const void* pc, uint32_t pcSize, uint32_t w, uint32_t h);
    void memBarrier(VulkanRendererContext* c, VkCommandBuffer cb, VkPipelineStageFlags src,
                    VkPipelineStageFlags dst, VkAccessFlags srcA, VkAccessFlags dstA);
    void waitFence(VulkanRendererContext* c, VkFence fence);
    void collectGpuTime(VulkanRendererContext* c, int slot);
    void collectInterpTime(VulkanRendererContext* c, int img);
    void submitAnalysis(VulkanRendererContext* c, int slot, int prev);
    void submitInterp(VulkanRendererContext* c, int prev, int cur, float t, int img);
    bool renderDynamic(VulkanRendererContext* c, const FrameBuild& fb, int slot,
                       Clock::time_point arrival, bool newContent, bool dupFrame, bool gap);
    int  planPhases(double intervalNow, Clock::time_point arrival, float* phases);
    void updateContentClock(Clock::time_point arrival);
    double lateQuantile(Clock::time_point now) const;
    double vsyncPeriodMs() const { return 1000.0 / std::max(10.0f, targetHz.load()); }
    // Presents go out half a refresh before their vsync: room for the
    // interpolation and the compose pass to run behind the game's GPU work.
    double presentLeadMs() const { return 0.5 * vsyncPeriodMs() + kLatchMs + 2.0; }
    void resetDyn() {
        dynCount = 0;
        haveDynX = haveDynV = dynStalled = false;
        dynShown = VK_NULL_HANDLE;
    }
    // Keeps only this frame, shown as is.
    void restartDyn(int slot, Clock::time_point ct) {
        dyn[0] = {slot, ct, false, false, false};
        dynCount = 1;
        dynX = ct;
        haveDynX = true;
        haveDynV = dynStalled = false;
        dynShown = composeScene[slot];
    }
    void dynDropOldest() {
        for (int i = 1; i < dynCount; ++i) dyn[i - 1] = dyn[i];
        --dynCount;
        if (dynCount > 0 && dyn[0].pending) dyn[0].pending = dyn[0].paired = false;
    }
    // Frames before the pair on screen are not needed anymore; a spare slot must
    // remain for the next capture even if that skips some content.
    void dynTrim() {
        while (dynCount >= 2 && haveDynX && dyn[1].ct <= dynX) dynDropOldest();
        if (dynCount >= kRing) {
            dynDropOldest();
            if (haveDynX && dynX < dyn[0].ct) dynX = dyn[0].ct;
        }
    }
    bool dynHas(int slot) const {
        for (int i = 0; i < dynCount; ++i) if (dyn[i].slot == slot) return true;
        return false;
    }
    bool vsyncBase(Clock::time_point& base) const {
        const int64_t ns = gVsyncNs.load(std::memory_order_relaxed);
        if (ns <= 0) return false;
        base = Clock::time_point(std::chrono::duration_cast<Clock::duration>(std::chrono::nanoseconds(ns)));
        return msBetween(base, Clock::now()) < 500.0;
    }
    // Vsync of the grid nearest to t.
    Clock::time_point nearestVsync(Clock::time_point base, Clock::time_point t) const {
        const double p = vsyncPeriodMs();
        const double n = std::round(msBetween(base, t) / p);
        return base + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(n * p));
    }
    // First vsync of the grid at or after t.
    Clock::time_point vsyncAtOrAfter(Clock::time_point base, Clock::time_point t) const {
        const double p = vsyncPeriodMs();
        const double n = std::ceil(msBetween(base, t) / p);
        return base + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(n * p));
    }
    // Least recently written slot not in use.
    int  pickSlot() const {
        int best = -1;
        for (int i = 0; i < kRing; ++i) {
            if (i == prevSlot || i == curSlot || dynHas(i)) continue;
            if (best < 0 || slotUse[i] < slotUse[best]) best = i;
        }
        return best >= 0 ? best : 0;
    }
    // Window textures alias the game's buffers, which it reuses soon after the
    // next present; pacing must not hold off sampling a newer frame. Returns false
    // once one has arrived.
    bool paceUntil(Clock::time_point t) {
        const auto limit = Clock::now() + std::chrono::milliseconds(60);
        for (;;) {
            if (contentSerial.load(std::memory_order_relaxed) != lastSerial) return false;
            const auto now = Clock::now();
            if (now >= t || now >= limit) return true;
            std::this_thread::sleep_until(std::min(t, now + std::chrono::milliseconds(1)));
        }
    }
    void publishStats(bool force, int state);
};

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

bool FrameGenState::loadFunctions(VulkanRendererContext* c) {
    if (fvk.CmdDispatch) return true;
    auto get = [&](const char* name) -> PFN_vkVoidFunction {
        return c->vk_.GetDeviceProcAddr ? c->vk_.GetDeviceProcAddr(c->device, name) : nullptr;
    };
#define FG_LOAD(fn) fvk.fn = reinterpret_cast<PFN_vk##fn>(get("vk" #fn))
    FG_LOAD(CreateComputePipelines);
    FG_LOAD(CmdDispatch);
    FG_LOAD(CreateQueryPool);
    FG_LOAD(DestroyQueryPool);
    FG_LOAD(CmdResetQueryPool);
    FG_LOAD(CmdWriteTimestamp);
    FG_LOAD(GetQueryPoolResults);
    FG_LOAD(CmdClearColorImage);
    FG_LOAD(CmdCopyImageToBuffer);
#undef FG_LOAD
    if (!fvk.CreateComputePipelines || !fvk.CmdDispatch) {
        fvk.CmdDispatch = nullptr;
        return false;
    }

    VkPhysicalDeviceProperties props{};
    c->vk_.GetPhysicalDeviceProperties(c->physicalDevice, &props);
    tsPeriodNs = props.limits.timestampPeriod;
    uint32_t qCount = 0;
    c->vk_.GetPhysicalDeviceQueueFamilyProperties(c->physicalDevice, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> qProps(qCount);
    c->vk_.GetPhysicalDeviceQueueFamilyProperties(c->physicalDevice, &qCount, qProps.data());
    tsValid = fvk.CreateQueryPool && fvk.CmdResetQueryPool && fvk.CmdWriteTimestamp &&
              fvk.GetQueryPoolResults && tsPeriodNs > 0.f &&
              c->graphicsQueueFamilyIndex < qCount &&
              qProps[c->graphicsQueueFamilyIndex].timestampValidBits > 0;
    return true;
}

bool FrameGenState::makeImage(VulkanRendererContext* c, FgImage& img, uint32_t w, uint32_t h,
                              VkFormat fmt, VkImageUsageFlags usage) {
    auto& vk = c->vk_;
    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = fmt;
    ii.extent = {w, h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = usage;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk.CreateImage(c->device, &ii, nullptr, &img.image) != VK_SUCCESS) return false;

    VkMemoryRequirements req{};
    vk.GetImageMemoryRequirements(c->device, img.image, &req);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = c->findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk.AllocateMemory(c->device, &ai, nullptr, &img.memory) != VK_SUCCESS) return false;
    vk.BindImageMemory(c->device, img.image, img.memory, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = img.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = fmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return vk.CreateImageView(c->device, &vi, nullptr, &img.view) == VK_SUCCESS;
}

void FrameGenState::destroyImage(VulkanRendererContext* c, FgImage& img) {
    auto& vk = c->vk_;
    if (img.view)   vk.DestroyImageView(c->device, img.view, nullptr);
    if (img.image)  vk.DestroyImage(c->device, img.image, nullptr);
    if (img.memory) vk.FreeMemory(c->device, img.memory, nullptr);
    img = {};
}

VkPipeline FrameGenState::makeComputePipeline(VulkanRendererContext* c, const uint32_t* code,
                                              size_t size) {
    VkShaderModule module = c->makeShader(code, size);
    VkComputePipelineCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = module;
    ci.stage.pName = "main";
    ci.layout = compPipeLayout;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkResult r = fvk.CreateComputePipelines(c->device, VK_NULL_HANDLE, 1, &ci, nullptr, &pipe);
    c->vk_.DestroyShaderModule(c->device, module, nullptr);
    if (r != VK_SUCCESS) throw std::runtime_error("compute pipeline");
    return pipe;
}

bool FrameGenState::createPipelines(VulkanRendererContext* c) {
    auto& vk = c->vk_;

    VkDescriptorSetLayoutBinding b[5]{};
    for (int i = 0; i < 5; ++i) {
        b[i].binding = (uint32_t)i;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].descriptorType = (i == 3) ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                       : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }
    VkDescriptorSetLayoutCreateInfo lci{};
    lci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    lci.bindingCount = 5;
    lci.pBindings = b;
    if (vk.CreateDescriptorSetLayout(c->device, &lci, nullptr, &compLayout) != VK_SUCCESS)
        return false;

    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, kPushSize};
    VkPipelineLayoutCreateInfo pli{};
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &compLayout;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pr;
    if (vk.CreatePipelineLayout(c->device, &pli, nullptr, &compPipeLayout) != VK_SUCCESS)
        return false;

    lumaR8 = false;
    lumaFormat = VK_FORMAT_R8G8B8A8_UNORM;
    // Instance function, resolved here so the shared dispatch table stays untouched.
    auto getFmt = reinterpret_cast<PFN_vkGetPhysicalDeviceFormatProperties>(
        c->gipa ? c->gipa(c->instance, "vkGetPhysicalDeviceFormatProperties") : nullptr);
    if (getFmt) {
        VkFormatProperties lumaProps{};
        getFmt(c->physicalDevice, VK_FORMAT_R8_UNORM, &lumaProps);
        const VkFormatFeatureFlags lumaNeed = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                              VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                              VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        if ((lumaProps.optimalTilingFeatures & lumaNeed) == lumaNeed) {
            lumaR8 = true;
            lumaFormat = VK_FORMAT_R8_UNORM;
        }
    }
    const uint32_t* downCode = lumaR8 ? fg_down_r8_comp_code : fg_down_comp_code;
    const size_t downBytes = lumaR8 ? sizeof(fg_down_r8_comp_code) : sizeof(fg_down_comp_code);
    pipeDown   = makeComputePipeline(c, downCode, downBytes);
    pipeMe     = makeComputePipeline(c, fg_me_comp_code,     sizeof(fg_me_comp_code));
    pipeSelect = makeComputePipeline(c, fg_select_comp_code, sizeof(fg_select_comp_code));
    pipeInterp = makeComputePipeline(c, fg_interp_comp_code, sizeof(fg_interp_comp_code));
    pipeScd    = makeComputePipeline(c, fg_scd_comp_code,    sizeof(fg_scd_comp_code));
    pipeCopy   = makeComputePipeline(c, fg_copy_comp_code,   sizeof(fg_copy_comp_code));

    VkSamplerCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.magFilter = sci.minFilter = VK_FILTER_LINEAR;
    if (vk.CreateSampler(c->device, &sci, nullptr, &linearSampler) != VK_SUCCESS) return false;
    sci.magFilter = sci.minFilter = VK_FILTER_NEAREST;
    if (vk.CreateSampler(c->device, &sci, nullptr, &nearestSampler) != VK_SUCCESS) return false;

    // Same attachment setup as the swapchain pass so the existing pipelines stay
    // compatible; only the final layout differs because the result is sampled.
    VkAttachmentDescription att{};
    att.format = c->swapchainFmt;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;
    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rci{};
    rci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rci.attachmentCount = 1;
    rci.pAttachments = &att;
    rci.subpassCount = 1;
    rci.pSubpasses = &sub;
    rci.dependencyCount = 1;
    rci.pDependencies = &dep;
    if (vk.CreateRenderPass(c->device, &rci, nullptr, &scenePass) != VK_SUCCESS) return false;

    // Pre-recorded full barrier submitted ahead of each frame so scene images
    // are never rewritten while earlier passes still read them.
    VkCommandBufferAllocateInfo cai{};
    cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cai.commandPool = c->cmdPool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (vk.AllocateCommandBuffers(c->device, &cai, &barrierCb) != VK_SUCCESS) return false;
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
    vk.BeginCommandBuffer(barrierCb, &bi);
    memBarrier(c, barrierCb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
               VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    vk.EndCommandBuffer(barrierCb);

    VkCommandBufferAllocateInfo cbs{};
    cbs.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbs.commandPool = c->cmdPool;
    cbs.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbs.commandBufferCount = kRing;
    if (vk.AllocateCommandBuffers(c->device, &cbs, sceneCb) != VK_SUCCESS) return false;
    if (vk.AllocateCommandBuffers(c->device, &cbs, fgCb) != VK_SUCCESS) return false;
    cbs.commandBufferCount = kGenImages;
    if (vk.AllocateCommandBuffers(c->device, &cbs, interpCb) != VK_SUCCESS) return false;

    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (int i = 0; i < kRing; ++i) {
        if (vk.CreateFence(c->device, &fi, nullptr, &sceneFence[i]) != VK_SUCCESS ||
            vk.CreateFence(c->device, &fi, nullptr, &fgFence[i]) != VK_SUCCESS) return false;
    }
    for (int j = 0; j < kGenImages; ++j)
        if (vk.CreateFence(c->device, &fi, nullptr, &interpFence[j]) != VK_SUCCESS) return false;

    if (tsValid) {
        VkQueryPoolCreateInfo qi{};
        qi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qi.queryCount = kRing * kStamps + 2 * kGenImages;
        if (fvk.CreateQueryPool(c->device, &qi, nullptr, &queryPool) != VK_SUCCESS)
            tsValid = false;
    }
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vk.CreateSemaphore(c->device, &semInfo, nullptr, &sceneDone) != VK_SUCCESS ||
        vk.CreateSemaphore(c->device, &semInfo, nullptr, &computeDone) != VK_SUCCESS)
        return false;
    return true;
}

VkDescriptorSet FrameGenState::allocSet(VulkanRendererContext* c, VkDescriptorSetLayout layout) {
    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    VkDescriptorSet ds = VK_NULL_HANDLE;
    if (c->vk_.AllocateDescriptorSets(c->device, &ai, &ds) != VK_SUCCESS)
        throw std::runtime_error("descriptor set");
    return ds;
}

void FrameGenState::writeCompute(VulkanRendererContext* c, VkDescriptorSet ds,
                                 VkImageView a, VkImageLayout la, VkImageView b, VkImageLayout lb,
                                 VkImageView cc, VkImageLayout lc, VkImageView out,
                                 VkImageView extra) {
    if (!extra) extra = dummy.view;
    VkDescriptorImageInfo infos[5]{};
    infos[0] = {linearSampler, a, la};
    infos[1] = {linearSampler, b, lb};
    infos[2] = {nearestSampler, cc, lc};
    infos[3] = {VK_NULL_HANDLE, out, VK_IMAGE_LAYOUT_GENERAL};
    infos[4] = {nearestSampler, extra, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet w[5]{};
    for (int i = 0; i < 5; ++i) {
        w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[i].dstSet = ds;
        w[i].dstBinding = (uint32_t)i;
        w[i].descriptorCount = 1;
        w[i].descriptorType = (i == 3) ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                       : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w[i].pImageInfo = &infos[i];
    }
    c->vk_.UpdateDescriptorSets(c->device, 5, w, 0, nullptr);
}

void FrameGenState::writeCompose(VulkanRendererContext* c, VkDescriptorSet ds, VkImageView view,
                                 VkImageLayout layout) {
    VkDescriptorImageInfo info{nearestSampler, view, layout};
    VkWriteDescriptorSet w{};
    w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w.dstSet = ds;
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &info;
    c->vk_.UpdateDescriptorSets(c->device, 1, &w, 0, nullptr);
}

void FrameGenState::geometryFor(int quality, int& outLevels, uint32_t* ow, uint32_t* oh,
                                 uint32_t* obw, uint32_t* obh) const {
    const int q = std::clamp(quality, 0, 2);
    const float shortSide = (float)std::max(1u, std::min(W, H));
    const float scale = std::min(1.0f, kShortSide[q] / shortSide);
    ow[0] = std::max<uint32_t>(48, (uint32_t)std::lround((float)W * scale));
    oh[0] = std::max<uint32_t>(48, (uint32_t)std::lround((float)H * scale));
    outLevels = 1;
    while (outLevels < kMaxLevels &&
           std::min(ow[outLevels - 1], oh[outLevels - 1]) >= 2 * kMinLevelDim) {
        ow[outLevels] = (ow[outLevels - 1] + 1) / 2;
        oh[outLevels] = (oh[outLevels - 1] + 1) / 2;
        ++outLevels;
    }
    for (int l = 0; l < outLevels; ++l) {
        obw[l] = (ow[l] + kBlock - 1) / kBlock;
        obh[l] = (oh[l] + kBlock - 1) / kBlock;
    }
}

void FrameGenState::setActiveGeometry(int quality) {
    uint32_t tw[kMaxLevels]{}, th[kMaxLevels]{}, tbw[kMaxLevels]{}, tbh[kMaxLevels]{};
    int n = 0;
    geometryFor(quality, n, tw, th, tbw, tbh);
    levels = std::min(n, allocLevels);
    for (int l = 0; l < levels; ++l) {
        lw[l] = std::min(tw[l], alw[l]);
        lh[l] = std::min(th[l], alh[l]);
        bw[l] = (lw[l] + kBlock - 1) / kBlock;
        bh[l] = (lh[l] + kBlock - 1) / kBlock;
        if (bw[l] > abw[l]) bw[l] = abw[l];
        if (bh[l] > abh[l]) bh[l] = abh[l];
    }
    for (int l = levels; l < kMaxLevels; ++l)
        lw[l] = lh[l] = bw[l] = bh[l] = 0;
    appliedQuality = std::clamp(quality, 0, 2);
}

void FrameGenState::noteQualityChange() {
    // The stored field and pyramids were written for the previous rectangle.
    haveFieldPrev = false;
    std::memset(pyrValid, 0, sizeof(pyrValid));
    resetDyn();
}

bool FrameGenState::createResources(VulkanRendererContext* c) {
    auto& vk = c->vk_;
    W = c->swapchainExt.width;
    H = c->swapchainExt.height;
    geometryFor(2, allocLevels, alw, alh, abw, abh);
    setActiveGeometry(effectiveQuality());

    const VkImageUsageFlags sceneUse = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    const VkImageUsageFlags computeUse = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    const VkImageUsageFlags histUse = computeUse | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    for (int s = 0; s < kRing; ++s) {
        if (!makeImage(c, scene[s], W, H, c->swapchainFmt, sceneUse)) return false;
        VkFramebufferCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass = scenePass;
        fi.attachmentCount = 1;
        fi.pAttachments = &scene[s].view;
        fi.width = W;
        fi.height = H;
        fi.layers = 1;
        if (vk.CreateFramebuffer(c->device, &fi, nullptr, &sceneFb[s]) != VK_SUCCESS) return false;
        for (int l = 0; l < allocLevels; ++l)
            if (!makeImage(c, pyr[s][l], alw[l], alh[l], lumaFormat, computeUse))
                return false;
    }
    const VkFormat F16 = VK_FORMAT_R16G16B16A16_SFLOAT;
    for (int l = 0; l < allocLevels; ++l) {
        if (!makeImage(c, fieldA[l], abw[l], abh[l], F16, computeUse)) return false;
        if (!makeImage(c, fieldB[l], abw[l], abh[l], F16, computeUse)) return false;
    }
    if (!makeImage(c, fieldPrev, abw[0], abh[0], F16, histUse)) return false;
    for (int s = 0; s < kRing; ++s) {
        if (!makeImage(c, vecField[s], alw[0], alh[0], F16, computeUse)) return false;
        if (!makeImage(c, scd[s], 1, 1, F16, computeUse | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) return false;
    }
    if (fvk.CmdCopyImageToBuffer) {
        try {
            c->createBuffer(kRing * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            scdReadBuf, scdReadMem);
            void* p = nullptr;
            if (vk.MapMemory(c->device, scdReadMem, 0, kRing * 8, 0, &p) == VK_SUCCESS)
                scdRead = static_cast<const uint16_t*>(p);
        } catch (const std::exception&) {
            scdRead = nullptr;
        }
    }
    if (!makeImage(c, dummy, 1, 1, F16, computeUse)) return false;
    if (!makeImage(c, scdPrev, 1, 1, F16, histUse)) return false;
    for (int j = 0; j < kGenImages; ++j)
        if (!makeImage(c, gen[j], W, H, VK_FORMAT_R8G8B8A8_UNORM, computeUse)) return false;

    // Compute images stay in GENERAL for their whole life; barriers handle visibility.
    VkCommandBuffer cb = c->beginOneTime();
    std::vector<VkImageMemoryBarrier> bars;
    auto toGeneral = [&](FgImage& img) {
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img.image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        bars.push_back(b);
    };
    for (int s = 0; s < kRing; ++s) for (int l = 0; l < allocLevels; ++l) toGeneral(pyr[s][l]);
    for (int l = 0; l < allocLevels; ++l) { toGeneral(fieldA[l]); toGeneral(fieldB[l]); }
    toGeneral(fieldPrev);
    for (int s = 0; s < kRing; ++s) { toGeneral(vecField[s]); toGeneral(scd[s]); }
    toGeneral(dummy);
    toGeneral(scdPrev);
    for (int j = 0; j < kGenImages; ++j) toGeneral(gen[j]);
    vk.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                          0, 0, nullptr, 0, nullptr, (uint32_t)bars.size(), bars.data());
    if (fvk.CmdClearColorImage) {
        FgImage* clr[] = {&fieldPrev, &scdPrev};
        VkImageMemoryBarrier tb[2]{};
        for (int i = 0; i < 2; ++i) {
            tb[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            tb[i].oldLayout = tb[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            tb[i].srcQueueFamilyIndex = tb[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            tb[i].image = clr[i]->image;
            tb[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            tb[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            tb[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        }
        vk.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                              VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, tb);
        VkClearColorValue z{};
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        for (int i = 0; i < 2; ++i)
            fvk.CmdClearColorImage(cb, clr[i]->image, VK_IMAGE_LAYOUT_GENERAL, &z, 1, &range);
        for (int i = 0; i < 2; ++i) {
            tb[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            tb[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        }
        vk.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                              VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 2, tb);
    }
    c->endOneTime(cb);

    // Per slot: pyramid + compose; per ordered pair: search/prop per level, select, interp per gen image.
    constexpr uint32_t kSets = kRing * (kMaxLevels + 3) +
                               kRing * (kRing - 1) * (2 * kMaxLevels + 1 + kGenImages) +
                               kGenImages + 16;
    VkDescriptorPoolSize sizes[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSets * 4},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kSets * 2},
    };
    VkDescriptorPoolCreateInfo pci{};
    pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pci.maxSets = kSets;
    pci.poolSizeCount = 2;
    pci.pPoolSizes = sizes;
    if (vk.CreateDescriptorPool(c->device, &pci, nullptr, &pool) != VK_SUCCESS) return false;

    const VkImageLayout RO = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    const VkImageLayout GEN = VK_IMAGE_LAYOUT_GENERAL;
    VkImageView dv = dummy.view;

    for (int s = 0; s < kRing; ++s) {
        dsDown0[s] = allocSet(c, compLayout);
        writeCompute(c, dsDown0[s], scene[s].view, RO, dv, GEN, dv, GEN, pyr[s][0].view);
        for (int l = 1; l < allocLevels; ++l) {
            dsDownN[s][l] = allocSet(c, compLayout);
            writeCompute(c, dsDownN[s][l], pyr[s][l - 1].view, GEN, dv, GEN, dv, GEN, pyr[s][l].view);
        }
        composeScene[s] = allocSet(c, c->dsLayout);
        writeCompose(c, composeScene[s], scene[s].view, RO);
    }
    for (int p = 0; p < kRing; ++p) {
        for (int q = 0; q < kRing; ++q) {
            if (p == q) continue;
            for (int l = 0; l < allocLevels; ++l) {
                VkImageView coarse = (l < allocLevels - 1) ? fieldB[l + 1].view : dv;
                dsSearch[p][q][l] = allocSet(c, compLayout);
                writeCompute(c, dsSearch[p][q][l], pyr[p][l].view, GEN, pyr[q][l].view, GEN,
                             coarse, GEN, fieldA[l].view, fieldPrev.view);
                dsProp[p][q][l] = allocSet(c, compLayout);
                writeCompute(c, dsProp[p][q][l], pyr[p][l].view, GEN, pyr[q][l].view, GEN,
                             fieldA[l].view, GEN, fieldB[l].view);
            }
            dsSelect[p][q] = allocSet(c, compLayout);
            writeCompute(c, dsSelect[p][q], pyr[p][0].view, GEN, pyr[q][0].view, GEN,
                         fieldB[0].view, GEN, vecField[q].view);
            for (int j = 0; j < kGenImages; ++j) {
                dsInterp[p][q][j] = allocSet(c, compLayout);
                writeCompute(c, dsInterp[p][q][j], scene[p].view, RO, scene[q].view, RO,
                             vecField[q].view, GEN, gen[j].view, scd[q].view);
            }
        }
    }
    for (int s = 0; s < kRing; ++s) {
        dsScd[s] = allocSet(c, compLayout);
        writeCompute(c, dsScd[s], fieldB[0].view, GEN, dv, GEN, scdPrev.view, GEN, scd[s].view);
        dsKeepScd[s] = allocSet(c, compLayout);
        writeCompute(c, dsKeepScd[s], scd[s].view, GEN, dv, GEN, dv, GEN, scdPrev.view);
    }
    dsKeepField = allocSet(c, compLayout);
    writeCompute(c, dsKeepField, fieldB[0].view, GEN, dv, GEN, dv, GEN, fieldPrev.view);
    for (int j = 0; j < kGenImages; ++j) {
        composeGen[j] = allocSet(c, c->dsLayout);
        writeCompose(c, composeGen[j], gen[j].view, GEN);
    }

    haveFieldPrev = false;
    genHead = 0;
    resetDyn();
    FG_LOGI("resources ready: %ux%u me=%ux%u/%ux%u blocks=%ux%u levels=%d/%d quality=%d luma=%s timestamps=%d",
            W, H, lw[0], lh[0], alw[0], alh[0], bw[0], bh[0], levels, allocLevels, appliedQuality,
            lumaR8 ? "r8" : "rgba8", (int)tsValid);
    return true;
}

void FrameGenState::destroyResources(VulkanRendererContext* c) {
    auto& vk = c->vk_;
    if (pool) vk.DestroyDescriptorPool(c->device, pool, nullptr);
    pool = VK_NULL_HANDLE;
    std::memset(dsDown0, 0, sizeof(dsDown0));
    std::memset(dsDownN, 0, sizeof(dsDownN));
    std::memset(dsSearch, 0, sizeof(dsSearch));
    std::memset(dsProp, 0, sizeof(dsProp));
    std::memset(dsSelect, 0, sizeof(dsSelect));
    std::memset(dsInterp, 0, sizeof(dsInterp));
    std::memset(composeScene, 0, sizeof(composeScene));
    std::memset(composeGen, 0, sizeof(composeGen));
    std::memset(dsScd, 0, sizeof(dsScd));
    std::memset(dsKeepScd, 0, sizeof(dsKeepScd));
    dsKeepField = VK_NULL_HANDLE;

    for (int s = 0; s < kRing; ++s) {
        if (sceneFb[s]) vk.DestroyFramebuffer(c->device, sceneFb[s], nullptr);
        sceneFb[s] = VK_NULL_HANDLE;
        destroyImage(c, scene[s]);
        for (int l = 0; l < kMaxLevels; ++l) destroyImage(c, pyr[s][l]);
    }
    for (int l = 0; l < kMaxLevels; ++l) {
        destroyImage(c, fieldA[l]);
        destroyImage(c, fieldB[l]);
    }
    destroyImage(c, fieldPrev);
    for (int s = 0; s < kRing; ++s) {
        destroyImage(c, vecField[s]);
        destroyImage(c, scd[s]);
    }
    destroyImage(c, dummy);
    destroyImage(c, scdPrev);
    if (scdReadBuf) vk.DestroyBuffer(c->device, scdReadBuf, nullptr);
    if (scdReadMem) vk.FreeMemory(c->device, scdReadMem, nullptr);
    scdReadBuf = VK_NULL_HANDLE;
    scdReadMem = VK_NULL_HANDLE;
    scdRead = nullptr;
    std::memset(scdPending, 0, sizeof(scdPending));
    haveFieldPrev = false;
    for (int j = 0; j < kGenImages; ++j) destroyImage(c, gen[j]);
    resetDyn();

    prevSlot = curSlot = -1;
    std::memset(pyrValid, 0, sizeof(pyrValid));
    std::memset(fgUsed, 0, sizeof(fgUsed));
    std::memset(interpUsed, 0, sizeof(interpUsed));
}

void FrameGenState::destroyPipelines(VulkanRendererContext* c) {
    auto& vk = c->vk_;
    for (int i = 0; i < kRing; ++i) {
        if (sceneFence[i]) vk.DestroyFence(c->device, sceneFence[i], nullptr);
        if (fgFence[i])    vk.DestroyFence(c->device, fgFence[i], nullptr);
        sceneFence[i] = fgFence[i] = VK_NULL_HANDLE;
    }
    for (int j = 0; j < kGenImages; ++j) {
        if (interpFence[j]) vk.DestroyFence(c->device, interpFence[j], nullptr);
        interpFence[j] = VK_NULL_HANDLE;
    }
    if (sceneCb[0]) vk.FreeCommandBuffers(c->device, c->cmdPool, kRing, sceneCb);
    if (fgCb[0])    vk.FreeCommandBuffers(c->device, c->cmdPool, kRing, fgCb);
    if (interpCb[0]) vk.FreeCommandBuffers(c->device, c->cmdPool, kGenImages, interpCb);
    if (barrierCb)  vk.FreeCommandBuffers(c->device, c->cmdPool, 1, &barrierCb);
    std::memset(sceneCb, 0, sizeof(sceneCb));
    std::memset(fgCb, 0, sizeof(fgCb));
    std::memset(interpCb, 0, sizeof(interpCb));
    barrierCb = VK_NULL_HANDLE;
    if (queryPool) fvk.DestroyQueryPool(c->device, queryPool, nullptr);
    queryPool = VK_NULL_HANDLE;
    if (sceneDone) vk.DestroySemaphore(c->device, sceneDone, nullptr);
    if (computeDone) vk.DestroySemaphore(c->device, computeDone, nullptr);
    sceneDone = computeDone = VK_NULL_HANDLE;
    if (scenePass) vk.DestroyRenderPass(c->device, scenePass, nullptr);
    scenePass = VK_NULL_HANDLE;
    VkPipeline* pipes[] = {&pipeDown, &pipeMe, &pipeSelect, &pipeInterp, &pipeScd, &pipeCopy};
    for (VkPipeline* p : pipes) {
        if (*p) vk.DestroyPipeline(c->device, *p, nullptr);
        *p = VK_NULL_HANDLE;
    }
    if (compPipeLayout) vk.DestroyPipelineLayout(c->device, compPipeLayout, nullptr);
    if (compLayout) vk.DestroyDescriptorSetLayout(c->device, compLayout, nullptr);
    compPipeLayout = VK_NULL_HANDLE;
    compLayout = VK_NULL_HANDLE;
    if (linearSampler) vk.DestroySampler(c->device, linearSampler, nullptr);
    if (nearestSampler) vk.DestroySampler(c->device, nearestSampler, nullptr);
    linearSampler = nearestSampler = VK_NULL_HANDLE;
}

bool FrameGenState::activate(VulkanRendererContext* c) {
    if (!loadFunctions(c)) {
        FG_LOGE("compute entry points unavailable");
        return false;
    }
    c->vk_.DeviceWaitIdle(c->device);
    {
        std::lock_guard<std::mutex> lk(c->renderMutex);
        c->completedSerial = c->submittedSerial;
    }
    userQuality = quality.load();
    qualityCap = 2;
    overloadVotes = 0;
    try {
        if (!createPipelines(c) || !createResources(c)) throw std::runtime_error("setup");
    } catch (const std::exception& e) {
        FG_LOGE("activation failed: %s", e.what());
        c->vk_.DeviceWaitIdle(c->device);
        destroyResources(c);
        destroyPipelines(c);
        return false;
    }

    std::memset(slotSerial, 0, sizeof(slotSerial));
    lastSerial = contentSerial.load();
    haveArrival = false;
    haveClock = false;
    rateCount = 0;
    intervalMs = 0.0;
    intervalSamples = 0;
    kApplied = -1;
    kVotes = 0;
    intervalDev = 0.0;
    runMode = -1;
    resetDyn();
    gpuMsEma = interpMsEma = 0.0;
    overloaded = false;
    newFrameCount = 0;
    statsStart = Clock::now();
    srcCount = outCount = 0;
    latencyEma = 0.0;
    haveFieldPrev = false;
    active = true;
    FG_LOGI("frame generation active");
    return true;
}

void FrameGenState::deactivate(VulkanRendererContext* c) {
    if (!active && !scenePass) return;
    c->vk_.DeviceWaitIdle(c->device);
    {
        std::lock_guard<std::mutex> lk(c->renderMutex);
        c->completedSerial = std::max(c->completedSerial, c->submittedSerial);
    }
    destroyResources(c);
    destroyPipelines(c);
    active = false;
    setUncapped(false);
    if (forcedFifo) {
        forcedFifo = false;
        if (c->requestedPresentMode == VK_PRESENT_MODE_FIFO_KHR) c->setPresentMode(userPresentMode);
    }
    c->needsRender.store(true);
    c->dirtyCV.notify_one();
    publishStats(true, failed ? 2 : 0);
    FG_LOGI("frame generation stopped");
}

// ---------------------------------------------------------------------------
// Recording helpers
// ---------------------------------------------------------------------------

void FrameGenState::memBarrier(VulkanRendererContext* c, VkCommandBuffer cb,
                               VkPipelineStageFlags src, VkPipelineStageFlags dst,
                               VkAccessFlags srcA, VkAccessFlags dstA) {
    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = srcA;
    mb.dstAccessMask = dstA;
    c->vk_.CmdPipelineBarrier(cb, src, dst, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

void FrameGenState::dispatch(VulkanRendererContext* c, VkCommandBuffer cb, VkPipeline pipe,
                             VkDescriptorSet ds, const void* pc, uint32_t pcSize,
                             uint32_t w, uint32_t h) {
    auto& vk = c->vk_;
    vk.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
    vk.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, compPipeLayout, 0, 1, &ds, 0, nullptr);
    vk.CmdPushConstants(cb, compPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcSize, pc);
    fvk.CmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
}

void FrameGenState::buildPyramid(VulkanRendererContext* c, VkCommandBuffer cb, int slot) {
    const VkPipelineStageFlags cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    DownPC d0{};
    d0.ratio = (float)W / (float)std::max(1u, lw[0]);
    d0.fromColor = 1;
    d0.activeDst[0] = (int32_t)lw[0];
    d0.activeDst[1] = (int32_t)lh[0];
    d0.srcActive[0] = d0.srcAlloc[0] = (float)W;
    d0.srcActive[1] = d0.srcAlloc[1] = (float)H;
    dispatch(c, cb, pipeDown, dsDown0[slot], &d0, sizeof(d0), lw[0], lh[0]);
    memBarrier(c, cb, cs, cs, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    for (int l = 1; l < levels; ++l) {
        DownPC d{};
        d.ratio = (float)lw[l - 1] / (float)std::max(1u, lw[l]);
        d.fromColor = 0;
        d.activeDst[0] = (int32_t)lw[l];
        d.activeDst[1] = (int32_t)lh[l];
        d.srcActive[0] = (float)lw[l - 1];
        d.srcActive[1] = (float)lh[l - 1];
        d.srcAlloc[0] = (float)alw[l - 1];
        d.srcAlloc[1] = (float)alh[l - 1];
        dispatch(c, cb, pipeDown, dsDownN[slot][l], &d, sizeof(d), lw[l], lh[l]);
        memBarrier(c, cb, cs, cs, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    }
}

void FrameGenState::recordCompute(VulkanRendererContext* c, VkCommandBuffer cb, int slot, int prev,
                                  int cur, const float* phases, int k, int genBase,
                                  bool needPrevPyr) {
    auto& vk = c->vk_;
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vk.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) throw std::runtime_error("begin fg cb");

    const VkPipelineStageFlags cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    memBarrier(c, cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, cs, VK_ACCESS_MEMORY_WRITE_BIT,
               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

    auto stamp = [&](uint32_t i, VkPipelineStageFlagBits stage) {
        if (tsValid) fvk.CmdWriteTimestamp(cb, stage, queryPool, (uint32_t)slot * kStamps + i);
    };
    if (tsValid) fvk.CmdResetQueryPool(cb, queryPool, (uint32_t)slot * kStamps, kStamps);
    stamp(0, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    if (needPrevPyr) buildPyramid(c, cb, prev);
    buildPyramid(c, cb, cur);
    stamp(1, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    auto rw = [&]() {
        memBarrier(c, cb, cs, cs, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    };
    const int q = std::clamp(appliedQuality, 0, 2);

    auto estimate = [&](float phase) {
        for (int l = levels - 1; l >= 0; --l) {
            const bool coarsest = (l == levels - 1);
            MePC pc{};
            pc.size[0] = (int32_t)lw[l];
            pc.size[1] = (int32_t)lh[l];
            pc.mode = 0;
            pc.flags = (coarsest ? kMeWide : kMeCoarse) | (haveFieldPrev ? kMeTemporal : 0);
            // Half-pel only on the level the screen samples, and not on Low.
            if (l == 0 && q >= 1) pc.flags |= kMeHalfPel;
            if (!coarsest) {
                pc.coarseScale[0] = (float)lw[l] / (float)std::max(1u, lw[l + 1]);
                pc.coarseScale[1] = (float)lh[l] / (float)std::max(1u, lh[l + 1]);
                pc.coarseBlocks[0] = (int32_t)std::max(1u, bw[l + 1]);
                pc.coarseBlocks[1] = (int32_t)std::max(1u, bh[l + 1]);
            } else {
                pc.coarseScale[0] = pc.coarseScale[1] = 1.0f;
                pc.coarseBlocks[0] = pc.coarseBlocks[1] = 1;
            }
            pc.tempScale = (float)lw[l] / (float)std::max(1u, lw[0]);
            pc.lambda = 0.0015f;
            pc.phase = phase;
            pc.allocSize[0] = (float)alw[l];
            pc.allocSize[1] = (float)alh[l];
            pc.blockSize[0] = (int32_t)std::max(1u, bw[l]);
            pc.blockSize[1] = (int32_t)std::max(1u, bh[l]);
            pc.tempBlocks[0] = (int32_t)std::max(1u, bw[0]);
            pc.tempBlocks[1] = (int32_t)std::max(1u, bh[0]);
            dispatch(c, cb, pipeMe, dsSearch[prev][cur][l], &pc, sizeof(pc), bw[l], bh[l]);
            rw();
            // Neighbor propagation is what the screen samples. Coarser levels
            // only feed the next search, so they copy the field instead.
            pc.mode = (l == 0) ? 1 : 2;
            dispatch(c, cb, pipeMe, dsProp[prev][cur][l], &pc, sizeof(pc), bw[l], bh[l]);
            rw();
        }
    };
    auto select = [&](float phase) {
        SelPC sel{};
        sel.size[0] = (int32_t)lw[0];
        sel.size[1] = (int32_t)lh[0];
        sel.lambda = 0.0015f;
        sel.zeroBias = 0.008f;
        sel.coherentTh = kCoherentTh[q];
        sel.phase = phase;
        sel.mode = q == 0 ? 0 : 1;
        sel.allocSize[0] = (float)alw[0];
        sel.allocSize[1] = (float)alh[0];
        sel.blockSize[0] = (int32_t)std::max(1u, bw[0]);
        sel.blockSize[1] = (int32_t)std::max(1u, bh[0]);
        dispatch(c, cb, pipeSelect, dsSelect[prev][cur], &sel, sizeof(sel), lw[0], lh[0]);
        rw();
    };
    auto interp = [&](int j, float fieldT) {
        InterpPC ipc{};
        ipc.outSize[0] = (float)W;
        ipc.outSize[1] = (float)H;
        ipc.fieldSize[0] = (float)lw[0];
        ipc.fieldSize[1] = (float)lh[0];
        ipc.t = phases[j];
        ipc.confLo = 0.04f;
        ipc.confHi = 0.12f;
        ipc.staticTh = kStaticTh[q];
        ipc.fieldT = fieldT;
        ipc.mode = q;
        dispatch(c, cb, pipeInterp, dsInterp[prev][cur][(genBase + j) % kGenImages], &ipc,
                 sizeof(ipc), W, H);
    };

    // A field anchored at the middle frame serves its own phase directly, but
    // other phases would have to invert it, which has no answer where motion
    // covers or uncovers the background and leaves holes there. High X3 builds
    // one field per phase. Lower levels keep the middle field and let the
    // interpolator fall back where the two warped samples disagree.
    const bool ownFields = q == 2 && (k > 1 || (k == 1 && std::abs(phases[0] - 0.5f) > 0.01f));
    const float firstPhase = ownFields ? phases[0] : 0.5f;
    estimate(firstPhase);

    ScdPC spc{};
    spc.size[0] = (int32_t)bw[0];
    spc.size[1] = (int32_t)bh[0];
    spc.cutTh = 0.14f;
    spc.cutRatio = 3.0f;
    dispatch(c, cb, pipeScd, dsScd[cur], &spc, sizeof(spc), 1, 1);
    if (scdRead) {
        memBarrier(c, cb, cs, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                   VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferImageCopy r{};
        r.bufferOffset = (VkDeviceSize)cur * 8;
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {1, 1, 1};
        fvk.CmdCopyImageToBuffer(cb, scd[cur].image, VK_IMAGE_LAYOUT_GENERAL, scdReadBuf, 1, &r);
        memBarrier(c, cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
        scdPending[cur] = true;
    }
    stamp(2, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    select(firstPhase);
    stamp(3, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    for (int j = 0; j < k; ++j) {
        if (ownFields && j > 0) {
            // The previous interpolation still reads the per-pixel field.
            rw();
            estimate(phases[j]);
            select(phases[j]);
        }
        interp(j, ownFields ? phases[j] : 0.5f);
    }

    // The next pair starts its search from this field.
    rw();
    CopyPC cp{};
    dispatch(c, cb, pipeCopy, dsKeepField, &cp, sizeof(cp), bw[0], bh[0]);
    dispatch(c, cb, pipeCopy, dsKeepScd[cur], &cp, sizeof(cp), 1, 1);
    haveFieldPrev = true;

    memBarrier(c, cb, cs, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
               VK_ACCESS_SHADER_READ_BIT);
    stamp(kStamps - 1, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    if (vk.EndCommandBuffer(cb) != VK_SUCCESS) throw std::runtime_error("end fg cb");
}

void FrameGenState::waitFence(VulkanRendererContext* c, VkFence fence) {
    auto& vk = c->vk_;
    if (vk.GetFenceStatus(c->device, fence) == VK_NOT_READY)
        vk.WaitForFences(c->device, 1, &fence, VK_TRUE, UINT64_MAX);
}

void FrameGenState::readScd(int slot) {
    if (!scdRead || !scdPending[slot]) return;
    scdPending[slot] = false;
    const double res = halfToFloat(scdRead[slot * 4]);
    const bool cut = halfToFloat(scdRead[slot * 4 + 2]) > 0.5f;
    if (runMode == 0) updateStill(res, cut);
}

// Reads finished results without waiting, so the still check reacts within a frame.
void FrameGenState::pollScd(VulkanRendererContext* c) {
    for (int s = 0; s < kRing; ++s)
        if (scdPending[s] && c->vk_.GetFenceStatus(c->device, fgFence[s]) == VK_SUCCESS) readScd(s);
}

void FrameGenState::setUncapped(bool on) {
    stillCount = 0;
    gSourceUncapped.store(on);
}

void FrameGenState::updateStill(double res, bool cut) {
    if (multiplier.load() != 3) { setUncapped(false); return; }
    if (!gSourceUncapped.load()) {
        stillCount = res < kStillRes && !cut ? stillCount + 1 : 0;
        if (stillCount >= kStillPairs) setUncapped(true);
        return;
    }
    if (res < kMoveRes && !cut) return;
    setUncapped(false);
    // Start at the full multiplier at once instead of waiting for the interval
    // average to come down from the uncapped rate.
    const double hz = std::max(10.0f, targetHz.load());
    intervalMs = 1000.0 * multiplier.load() / hz;
    intervalSamples = 3;
    kApplied = multiplier.load() - 1;
    kVotes = 0;
}

void FrameGenState::collectGpuTime(VulkanRendererContext* c, int slot) {
    readScd(slot);
    if (!tsValid || !fgUsed[slot]) return;
    uint64_t t[kStamps] = {};
    VkResult r = fvk.GetQueryPoolResults(c->device, queryPool, (uint32_t)slot * kStamps, kStamps,
                                         sizeof(t), t, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    fgUsed[slot] = false;
    if (r != VK_SUCCESS) return;
    for (uint32_t i = 1; i < kStamps; ++i) if (t[i] < t[i - 1]) return;
    auto toMs = [&](uint64_t d) { return (double)d * (double)tsPeriodNs * 1e-6; };
    double ms = toMs(t[kStamps - 1] - t[0]);
    if (ms > 200.0) return;
    gpuMsEma = gpuMsEma > 0.0 ? gpuMsEma * 0.8 + ms * 0.2 : ms;
    for (uint32_t i = 0; i + 1 < kStamps; ++i) {
        const double s = toMs(t[i + 1] - t[i]);
        stageMs[i] = stageMs[i] > 0.0 ? stageMs[i] * 0.8 + s * 0.2 : s;
    }
}

void FrameGenState::collectInterpTime(VulkanRendererContext* c, int img) {
    if (!tsValid || !interpUsed[img]) return;
    uint64_t t[2] = {};
    VkResult r = fvk.GetQueryPoolResults(c->device, queryPool, kRing * kStamps + 2u * (uint32_t)img, 2,
                                         sizeof(t), t, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    interpUsed[img] = false;
    if (r != VK_SUCCESS || t[1] < t[0]) return;
    const double ms = (double)(t[1] - t[0]) * (double)tsPeriodNs * 1e-6;
    if (ms > 100.0) return;
    interpMsEma = interpMsEma > 0.0 ? interpMsEma * 0.9 + ms * 0.1 : ms;
}

// Performance: motion of the pair prev -> slot, once per real frame. The scene
// passes are earlier in the queue and its opening barrier waits for them; its
// closing barrier orders later work behind it.
void FrameGenState::submitAnalysis(VulkanRendererContext* c, int slot, int prev) {
    auto& vk = c->vk_;
    recordCompute(c, fgCb[slot], slot, prev, slot, nullptr, 0, 0, !pyrValid[prev]);
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &fgCb[slot];
    vk.ResetFences(c->device, 1, &fgFence[slot]);
    if (vk.QueueSubmit(c->graphicsQueue, 1, &si, fgFence[slot]) != VK_SUCCESS)
        throw std::runtime_error("analysis submit");
    fgUsed[slot] = tsValid;
    pyrValid[prev] = true;
    pyrValid[slot] = true;
}

void FrameGenState::submitInterp(VulkanRendererContext* c, int prev, int cur, float t, int img) {
    auto& vk = c->vk_;
    waitFence(c, interpFence[img]);
    collectInterpTime(c, img);
    VkCommandBuffer cb = interpCb[img];
    vk.ResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vk.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) throw std::runtime_error("begin interp cb");
    const VkPipelineStageFlags cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    memBarrier(c, cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, cs, VK_ACCESS_MEMORY_WRITE_BIT,
               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    const uint32_t q0 = kRing * kStamps + 2u * (uint32_t)img;
    if (tsValid) {
        fvk.CmdResetQueryPool(cb, queryPool, q0, 2);
        fvk.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, q0);
    }
    InterpPC ipc{};
    ipc.outSize[0] = (float)W;
    ipc.outSize[1] = (float)H;
    ipc.fieldSize[0] = (float)lw[0];
    ipc.fieldSize[1] = (float)lh[0];
    ipc.t = t;
    ipc.confLo = 0.04f;
    ipc.confHi = 0.12f;
    const int q = std::clamp(appliedQuality, 0, 2);
    ipc.staticTh = kStaticTh[q];
    ipc.fieldT = 0.5f;
    ipc.mode = q;
    dispatch(c, cb, pipeInterp, dsInterp[prev][cur][img], &ipc, sizeof(ipc), W, H);
    memBarrier(c, cb, cs, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
               VK_ACCESS_SHADER_READ_BIT);
    if (tsValid) fvk.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, q0 + 1);
    if (vk.EndCommandBuffer(cb) != VK_SUCCESS) throw std::runtime_error("end interp cb");
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    vk.ResetFences(c->device, 1, &interpFence[img]);
    if (vk.QueueSubmit(c->graphicsQueue, 1, &si, interpFence[img]) != VK_SUCCESS)
        throw std::runtime_error("interp submit");
    interpUsed[img] = tsValid;
}

void FrameGenState::retireTextures(VulkanRendererContext* c, int slot) {
    c->completedSerial = std::max(c->completedSerial, slotSerial[slot]);
    if (!c->retirePending.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lk(c->renderMutex);
    if (!c->deleteQueue.empty()) {
        size_t keep = 0;
        for (auto& retired : c->deleteQueue) {
            if (retired.serial <= c->completedSerial) c->destroyTexNow(retired);
            else c->deleteQueue[keep++] = std::move(retired);
        }
        c->deleteQueue.resize(keep);
    }
    c->retirePending.store(!c->deleteQueue.empty(), std::memory_order_release);
}

// Mirrors the draw-list snapshot taken by VulkanRendererContext::renderFrame.
void FrameGenState::prepare(VulkanRendererContext* c, FrameBuild& fb) {
    std::lock_guard<std::mutex> lk(c->renderMutex);
    auto& vk = c->vk_;

    c->pendingFrameSerial = ++c->submittedSerial;

    fb.ox = c->sceneOffsetX; fb.oy = c->sceneOffsetY;
    fb.sx = c->sceneScaleX;  fb.sy = c->sceneScaleY;
    fb.cw = (float)c->containerWidth; fb.ch = (float)c->containerHeight;
    fb.ptrX = (short)c->pointerX.load(); fb.ptrY = (short)c->pointerY.load();
    fb.curHotX = c->cursorHotX; fb.curHotY = c->cursorHotY;
    fb.curVis = c->cursorVisible.load();
    fb.scissor = {{0, 0}, c->swapchainExt};
    if (c->hasCustomScissor) fb.scissor = c->rotateRectLogicalToIdentity(c->customScissor);

    if (c->treeDirty) { c->rebuildRenderListFromTree(); c->treeDirty = false; }

    c->frameDraws.clear();
    for (auto& re : c->renderList) {
        auto it = c->texMap.find(re.id);
        if (it == c->texMap.end()) continue;
        VulkanRendererContext::WinTex& wt = it->second;
        if (wt.ds == VK_NULL_HANDLE) continue;
        VulkanRendererContext::DrawEntry de{wt.img, wt.ds, VK_NULL_HANDLE, re.x, re.y, wt.w, wt.h};
        de.isAHB = wt.isAHB;
        if (wt.isAHB) {
            auto cit = c->ahbImportCache.find(wt.ahb);
            if (cit != c->ahbImportCache.end() && cit->second.img == wt.img &&
                cit->second.needsTransition &&
                std::none_of(fb.pendingAHB.begin(), fb.pendingAHB.end(),
                             [&](const FrameBuild::PendingAHB& p) { return p.image == wt.img; })) {
                de.needsTransition = true;
                fb.pendingAHB.push_back({wt.ahb, wt.img});
            }
        } else if (wt.needsTransition &&
                   std::none_of(fb.pendingTex.begin(), fb.pendingTex.end(),
                                [&](const FrameBuild::PendingTex& p) { return p.image == wt.img; })) {
            de.needsTransition = true;
            fb.pendingTex.push_back({re.id, wt.img});
        }
        if (wt.dirty && !wt.isAHB && wt.stg != VK_NULL_HANDLE) { de.upload = wt.stg; wt.dirty = false; }
        else if (wt.isAHB) { wt.dirty = false; }
        c->frameDraws.push_back(de);
    }

    if (c->isCursorImageDirty.load() && !c->cursorPixels.empty()) {
        if (c->cursorImg == VK_NULL_HANDLE || c->cursorTexW != c->cursorPendingW ||
            c->cursorTexH != c->cursorPendingH) {
            if (c->cursorImg != VK_NULL_HANDLE)
                for (auto& fence : c->inFlightFences)
                    if (vk.WaitForFences(c->device, 1, &fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS)
                        throw std::runtime_error("cursor resize fence wait");
            c->ensureCursorTex(c->cursorPendingW, c->cursorPendingH);
        }
        VkDeviceSize csz = (VkDeviceSize)c->cursorTexW * c->cursorTexH * 4;
        c->ensureCursorStaging(csz, c->currentFrame);
        memcpy(c->cursorStgP[c->currentFrame], c->cursorPixels.data(), (size_t)csz);
        c->isCursorImageDirty.store(false);
        fb.hasCurUpload = true;
        fb.curUpload = c->cursorStg[c->currentFrame];
    }
    fb.curW = c->cursorTexW;
    fb.curH = c->cursorTexH;
}

bool FrameGenState::presentCompose(VulkanRendererContext* c, VkDescriptorSet ds,
                                   const FrameBuild& fb, bool withCursorUpload,
                                   VkSemaphore extraWait) {
    auto& vk = c->vk_;
    auto fail = [&]() {
        c->fbResized.store(true);
        c->swapchainRetryPending.store(true);
        return false;
    };

    const uint32_t cf = c->currentFrame;
    if (c->swapchain == VK_NULL_HANDLE || cf >= c->cmdBufs.size() ||
        c->cmdBufs[cf] == VK_NULL_HANDLE)
        return fail();

    VkResult fs = vk.GetFenceStatus(c->device, c->inFlightFences[cf]);
    if (fs == VK_NOT_READY) {
        if (vk.WaitForFences(c->device, 1, &c->inFlightFences[cf], VK_TRUE, UINT64_MAX) != VK_SUCCESS)
            return fail();
    } else if (fs != VK_SUCCESS) {
        return fail();
    }

    uint32_t imgIdx = 0;
    VkResult res = vk.AcquireNextImageKHR(c->device, c->swapchain, UINT64_MAX,
                                          c->imgAvailSems[cf], VK_NULL_HANDLE, &imgIdx);
    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR) {
        c->fbResized.store(true);
        return false;
    }
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) return false;
    if (imgIdx >= c->swapchainFBs.size() || imgIdx >= c->swapchainImages.size()) return fail();

    if (c->imgInFlight.size() != c->swapchainImages.size())
        c->imgInFlight.assign(c->swapchainImages.size(), VK_NULL_HANDLE);
    if (c->imgInFlight[imgIdx] != VK_NULL_HANDLE && c->imgInFlight[imgIdx] != c->inFlightFences[cf] &&
        vk.GetFenceStatus(c->device, c->imgInFlight[imgIdx]) == VK_NOT_READY)
        vk.WaitForFences(c->device, 1, &c->imgInFlight[imgIdx], VK_TRUE, UINT64_MAX);
    c->imgInFlight[imgIdx] = c->inFlightFences[cf];

    vk.ResetCommandBuffer(c->cmdBufs[cf], 0);

    composeDraws.resize(1);
    VulkanRendererContext::DrawEntry& de = composeDraws[0];
    de = {};
    de.ds = ds;
    de.w = (int)W;
    de.h = (int)H;

    FrameGenPass pass;
    pass.compose = true;
    try {
        c->recordCmdBuf(c->cmdBufs[cf], imgIdx, composeDraws, c->frameAhbTransitions,
                        c->framePreUpload, c->framePostUpload,
                        fb.curUpload, withCursorUpload && fb.hasCurUpload,
                        fb.ox, fb.oy, fb.sx, fb.sy, fb.cw, fb.ch,
                        fb.ptrX, fb.ptrY, fb.curHotX, fb.curHotY, fb.curW, fb.curH, fb.curVis,
                        VkRect2D{{0, 0}, c->swapchainExt}, 0, false, &pass);
    } catch (const std::exception& e) {
        FG_LOGE("compose record failed: %s", e.what());
        return fail();
    }

    VkSemaphore wSem[2] = {c->imgAvailSems[cf], extraWait};
    VkSemaphore sSem[] = {c->renderDoneSems[imgIdx]};
    VkPipelineStageFlags wStage[2] = {
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT};
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = extraWait ? 2 : 1;
    si.pWaitSemaphores = wSem;
    si.pWaitDstStageMask = wStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &c->cmdBufs[cf];
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = sSem;

    vk.ResetFences(c->device, 1, &c->inFlightFences[cf]);
    if (vk.QueueSubmit(c->graphicsQueue, 1, &si, c->inFlightFences[cf]) != VK_SUCCESS) {
        vk.DestroyFence(c->device, c->inFlightFences[cf], nullptr);
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vk.CreateFence(c->device, &fi, nullptr, &c->inFlightFences[cf]);
        return fail();
    }

    VkSwapchainKHR scs[] = {c->swapchain};
    VkPresentInfoKHR pi{};
    pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = sSem;
    pi.swapchainCount = 1;
    pi.pSwapchains = scs;
    pi.pImageIndices = &imgIdx;
    res = vk.QueuePresentKHR(c->graphicsQueue, &pi);
    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR ||
        res == VK_SUBOPTIMAL_KHR)
        c->fbResized.store(true);

    c->currentFrame = (cf + 1) % MAX_FRAMES_IN_FLIGHT;
    ++outCount;
    return true;
}

// ---------------------------------------------------------------------------
// Pacing policy
// ---------------------------------------------------------------------------

// Fills phases (0,1) of the generated frames to show for this source interval.
// Power saver multiplies (x2 or x3) a source the Java side caps at that share
// of the refresh.
// Performance leaves the game alone and only fills the refreshes that would
// otherwise repeat a frame (see renderDynamic); it only uses the on/off answer.
int FrameGenState::planPhases(double intervalNow, Clock::time_point arrival, float* phases) {
    const double src = 1000.0 / std::max(1.0, intervalNow);
    const double hz = std::max(10.0f, targetHz.load());
    const double ratio = hz / src;
    const int m = mode.load();

    int want = 0;
    if (m == 0) {
        // As many frames as fit in the refresh, up to the chosen multiplier.
        for (int n = multiplier.load(); n >= 2 && want == 0; --n)
            if (ratio >= n / 1.08) want = n - 1;
    }
    else if (kApplied == 1) want = src < kDynOffRatio * hz ? 1 : 0;
    else want = src < kDynOnRatio * hz ? 1 : 0;

    // Switching the multiplier is visible as a hitch, so require a stable vote.
    const int votes = m != 1 ? 8 : (kApplied == 1 ? kDynOffVotes : kDynOnVotes);
    if (kApplied < 0) {
        kApplied = want;
    } else if (want != kApplied) {
        if (++kVotes >= votes) { kApplied = want; kVotes = 0; }
    } else {
        kVotes = 0;
    }

    const int plan = kApplied;
    if (plan <= 0) return 0;

    // Performance interpolates once per vsync, not once per frame.
    const double load = gpuMsEma + (m == 1 ? interpMsEma * intervalNow / vsyncPeriodMs() : 0.0);
    if (overloaded) {
        if (load < 0.45 * intervalNow) overloaded = false;
    } else if (load > 0.6 * intervalNow) {
        if (++overloadVotes >= 8) {
            overloadVotes = 0;
            // Cheaper motion estimation first; pausing generation is the last resort.
            if (appliedQuality > 0) {
                qualityCap = appliedQuality - 1;
                gpuMsEma = 0.0;
            } else {
                overloaded = true;
            }
        }
    } else {
        overloadVotes = 0;
    }
    // While paused, run one frame in a while to learn whether the GPU recovered.
    if (overloaded && (newFrameCount % 24) != 0) return 0;

    for (int j = 0; j < plan; ++j) phases[j] = (float)(j + 1) / (float)(plan + 1);
    return plan;
}

// Phase-locked loop on arrivals: the clock runs at the mean arrival interval and
// its phase is nudged toward each arrival, so bursts average out while a real
// change of frame rate is followed within kRateSamples frames. A hitch
// re-anchors the phase.
void FrameGenState::updateContentClock(Clock::time_point arrival) {
    auto ms = [](double v) {
        return std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(v));
    };
    prevContentTime = contentTime;
    hitchFrame = false;
    if (intervalMs <= 0.0) {
        contentTime = arrival;
        haveClock = false;
        lateCount = 0;
        rateCount = 0;
        return;
    }
    rateAt[rateHead] = arrival;
    rateHead = (rateHead + 1) % kRateSamples;
    rateCount = std::min(rateCount + 1, kRateSamples);
    const int oldest = (rateHead - rateCount + kRateSamples) % kRateSamples;
    double rateIv = rateCount >= 4
        ? msBetween(rateAt[oldest], arrival) / (double)(rateCount - 1)
        : intervalMs;
    if (!(rateIv > 0.5)) rateIv = intervalMs;

    if (!haveClock) {
        contentTime = arrival;
        clockIvMs = rateIv;
        lateMs = rateIv;
        haveClock = true;
        return;
    }
    clockIvMs = rateIv;
    const auto predicted = contentTime + ms(clockIvMs);
    const double err = msBetween(predicted, arrival);
    if (err > (kHitchIntervals - 1.0) * clockIvMs) {
        contentTime = arrival;
        hitchFrame = true;
        lateMs = lateQuantile(arrival);
        return;
    }
    if (err < -2.0 * clockIvMs) {
        contentTime = arrival;
        lateMs = lateQuantile(arrival);
        return;
    }
    contentTime = predicted + ms(kClockPhase * err);
    if (msBetween(prevContentTime, contentTime) < 0.25 * clockIvMs)
        contentTime = prevContentTime + ms(0.25 * clockIvMs);

    lateHist[lateHead] = msBetween(prevContentTime, arrival);
    lateAt[lateHead] = arrival;
    lateHead = (lateHead + 1) % kLateSamples;
    lateCount = std::min(lateCount + 1, kLateSamples);
    lateMs = lateQuantile(arrival);
}

double FrameGenState::lateQuantile(Clock::time_point now) const {
    double v[kLateSamples];
    int n = 0;
    for (int i = 0; i < lateCount; ++i) {
        const int idx = (lateHead - 1 - i + kLateSamples) % kLateSamples;
        if (msBetween(lateAt[idx], now) > kLateWindowMs) break;
        v[n++] = lateHist[idx];
    }
    if (n == 0) return clockIvMs;
    const int k = std::clamp((int)std::ceil(kLateShare * n) - 1, 0, n - 1);
    std::nth_element(v, v + k, v + n);
    return v[k];
}

void FrameGenState::publishStats(bool force, int state) {
    auto now = Clock::now();
    double dt = msBetween(statsStart, now);
    if (!force && dt < 500.0) return;
    std::lock_guard<std::mutex> lk(statsMutex);
    if (dt > 1.0) {
        stats.srcFps = (float)(srcCount * 1000.0 / dt);
        stats.outFps = (float)(outCount * 1000.0 / dt);
    }
    stats.gpuMs = (float)gpuMsEma;
    stats.addedLatencyMs = (float)latencyEma;
    stats.generated = lastK;
    stats.state = state;
    stats.quality = appliedQuality;
    statsStart = now;
    srcCount = outCount = 0;
}

// ---------------------------------------------------------------------------
// Main frame path
// ---------------------------------------------------------------------------

bool FrameGenState::renderFrame(VulkanRendererContext* c) {
    auto& vk = c->vk_;

    if (quality.load() != userQuality) {
        userQuality = quality.load();
        qualityCap = 2;
    }
    if (W != c->swapchainExt.width || H != c->swapchainExt.height) {
        vk.DeviceWaitIdle(c->device);
        {
            std::lock_guard<std::mutex> lk(c->renderMutex);
            c->completedSerial = std::max(c->completedSerial, c->submittedSerial);
        }
        destroyResources(c);
        if (!createResources(c)) {
            FG_LOGE("resource rebuild failed");
            destroyResources(c);
            return false;
        }
        std::memset(slotSerial, 0, sizeof(slotSerial));
        haveArrival = false;
        haveClock = false;
        rateCount = 0;
        intervalSamples = 0;
    }
    if (appliedQuality != effectiveQuality()) {
        setActiveGeometry(effectiveQuality());
        noteQualityChange();
        FG_LOGI("motion quality %d, me %ux%u", appliedQuality, lw[0], lh[0]);
    }

    if (c->requestedPresentMode != VK_PRESENT_MODE_FIFO_KHR) {
        userPresentMode = c->requestedPresentMode;
        forcedFifo = true;
        c->setPresentMode(VK_PRESENT_MODE_FIFO_KHR);
    }

    const int m = mode.load();
    if (m != runMode) {
        runMode = m;
        setUncapped(false);
        resetDyn();
        prevSlot = curSlot = -1;
        haveFieldPrev = false;
        kApplied = -1;
        kVotes = 0;
    }
    if (m == 1) dynTrim();

    const auto arrival = Clock::now();
    // Redraws between real frames (cursor, small windows) would land in the
    // FIFO between a generated and a real frame and break the cadence. The
    // next real frame picks them up; a stalled game still gets redraws.
    if (contentSerial.load() == lastSerial && haveArrival && intervalMs > 0.0 &&
        msBetween(lastArrival, arrival) < intervalMs * 2.5)
        return true;

    const int slot = pickSlot();
    slotUse[slot] = ++slotClock;

    waitFence(c, sceneFence[slot]);
    waitFence(c, fgFence[slot]);
    collectGpuTime(c, slot);
    retireTextures(c, slot);
    // Cursor staging is indexed by currentFrame, so that slot must be idle too.
    waitFence(c, c->inFlightFences[c->currentFrame]);

    FrameBuild fb;
    prepare(c, fb);

    // Scene -> offscreen image (cursor excluded: it is added at present time).
    FrameGenPass scenePassInfo;
    scenePassInfo.renderPass = scenePass;
    scenePassInfo.framebuffer = sceneFb[slot];
    vk.ResetCommandBuffer(sceneCb[slot], 0);
    c->recordCmdBuf(sceneCb[slot], 0, c->frameDraws, c->frameAhbTransitions, c->framePreUpload,
                    c->framePostUpload, VK_NULL_HANDLE, false,
                    fb.ox, fb.oy, fb.sx, fb.sy, fb.cw, fb.ch,
                    fb.ptrX, fb.ptrY, fb.curHotX, fb.curHotY, fb.curW, fb.curH, false,
                    fb.scissor, 0, false, &scenePassInfo);

    VkSubmitInfo bsi{};
    bsi.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    bsi.commandBufferCount = 1;
    bsi.pCommandBuffers = &barrierCb;
    VkSubmitInfo ssi{};
    ssi.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    ssi.commandBufferCount = 1;
    ssi.pCommandBuffers = &sceneCb[slot];
    ssi.signalSemaphoreCount = 1;
    ssi.pSignalSemaphores = &sceneDone;
    vk.ResetFences(c->device, 1, &sceneFence[slot]);
    if (vk.QueueSubmit(c->graphicsQueue, 1, &bsi, VK_NULL_HANDLE) != VK_SUCCESS ||
        vk.QueueSubmit(c->graphicsQueue, 1, &ssi, sceneFence[slot]) != VK_SUCCESS) {
        {
            std::lock_guard<std::mutex> lk(c->renderMutex);
            c->pendingFrameSerial = 0;
            if (fb.hasCurUpload) c->isCursorImageDirty.store(true);
        }
        vk.DestroyFence(c->device, sceneFence[slot], nullptr);
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vk.CreateFence(c->device, &fi, nullptr, &sceneFence[slot]);
        c->fbResized.store(true);
        c->swapchainRetryPending.store(true);
        return true;
    }
    {
        std::lock_guard<std::mutex> lk(c->renderMutex);
        slotSerial[slot] = c->pendingFrameSerial;
        c->pendingFrameSerial = 0;
        for (const auto& p : fb.pendingAHB) {
            auto it = c->ahbImportCache.find(p.ahb);
            if (it != c->ahbImportCache.end() && it->second.img == p.image)
                it->second.needsTransition = false;
        }
        for (const auto& p : fb.pendingTex) {
            auto it = c->texMap.find(p.id);
            if (it != c->texMap.end() && it->second.img == p.image)
                it->second.needsTransition = false;
        }
    }

    // Is this a new game frame or just a redraw (cursor, window changes)?
    const uint64_t serial = contentSerial.load();
    const bool newContent = serial != lastSerial;
    lastSerial = serial;
    // No game draws a new frame this soon: it is the same frame arriving twice
    // or in two parts. Counting it as a frame stops motion for one pair and
    // doubles it in the next, so it only replaces the current image.
    const bool dupFrame = newContent && m == 1 && kApplied == 1 && haveArrival &&
                          dynCount > 0 && msBetween(lastArrival, arrival) < kDupMs;
    if (dupFrame) ++srcCount;

    bool gap = false;
    if (newContent && !dupFrame) {
        if (haveArrival) {
            double iv = msBetween(lastArrival, arrival);
            if (iv > 250.0) {
                gap = true;
                intervalMs = intervalDev = 0.0;
                intervalSamples = 0;
                kApplied = -1;
            } else {
                if (intervalMs > 0.0) {
                    const double dev = std::abs(iv - intervalMs);
                    intervalDev = intervalDev > 0.0 ? intervalDev * 0.9 + dev * 0.1 : dev;
                }
                intervalMs = intervalMs > 0.0 ? intervalMs * 0.85 + iv * 0.15 : iv;
                ++intervalSamples;
            }
        }
        lastArrival = arrival;
        haveArrival = true;
        updateContentClock(arrival);
        ++srcCount;
        ++newFrameCount;
    }

    if (m == 1) return renderDynamic(c, fb, slot, arrival, newContent, dupFrame, gap);

    if (multiplier.load() != 3 && gSourceUncapped.load()) setUncapped(false);
    pollScd(c);
    const bool uncapped = gSourceUncapped.load();

    int k = 0;
    float phases[kMaxGen] = {};
    if (newContent && !gap && curSlot >= 0 && intervalSamples >= 3 && intervalMs > 0.0)
        k = planPhases(intervalMs, arrival, phases);
    // Uncapped on a still image: nothing to generate, but motion is still
    // estimated now and then so the cap returns as soon as the image moves.
    bool probe = false;
    if (uncapped) {
        k = 0;
        probe = newContent && !gap && curSlot >= 0 && msBetween(lastProbe, arrival) >= kProbeMs;
        if (probe) lastProbe = arrival;
    }
    const bool doGen = k > 0 || probe;
    if (newContent) lastK = k;
    // The temporal candidate is only meaningful for the pair right before this one.
    if (newContent && !doGen) haveFieldPrev = false;

    bool cursorUpload = true;
    bool waitCompute = false;
    bool waitScene = false;
    auto present = [&](VkDescriptorSet ds) {
        VkSemaphore extra = VK_NULL_HANDLE;
        if (waitCompute) extra = computeDone;
        else if (waitScene) extra = sceneDone;
        bool ok = presentCompose(c, ds, fb, cursorUpload, extra);
        if (ok) {
            if (extra == computeDone) waitCompute = false;
            if (extra == sceneDone) waitScene = false;
            cursorUpload = false;
        }
        return ok;
    };
    auto drain = [&](VkSemaphore sem) {
        if (!sem) return;
        VkPipelineStageFlags stg = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount = 1;
        si.pWaitSemaphores = &sem;
        si.pWaitDstStageMask = &stg;
        vk.QueueSubmit(c->graphicsQueue, 1, &si, VK_NULL_HANDLE);
    };

    const int genBase = genHead;
    if (doGen) {
        const int prev = curSlot;
        recordCompute(c, fgCb[slot], slot, prev, slot, phases, k, genBase, !pyrValid[prev]);
        genHead = (genHead + k) % kGenImages;
        VkPipelineStageFlags waitScene = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        VkSubmitInfo fsi{};
        fsi.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        fsi.waitSemaphoreCount = 1;
        fsi.pWaitSemaphores = &sceneDone;
        fsi.pWaitDstStageMask = &waitScene;
        fsi.commandBufferCount = 1;
        fsi.pCommandBuffers = &fgCb[slot];
        fsi.signalSemaphoreCount = 1;
        fsi.pSignalSemaphores = &computeDone;
        vk.ResetFences(c->device, 1, &fgFence[slot]);
        if (vk.QueueSubmit(c->graphicsQueue, 1, &fsi, fgFence[slot]) != VK_SUCCESS) {
            vk.DestroyFence(c->device, fgFence[slot], nullptr);
            VkFenceCreateInfo fi{};
            fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            vk.CreateFence(c->device, &fi, nullptr, &fgFence[slot]);
            throw std::runtime_error("fg submit");
        }
        fgUsed[slot] = tsValid;
        pyrValid[prev] = true;
        pyrValid[slot] = true;
    }

    waitCompute = doGen;
    waitScene = !doGen;

    if (!doGen) {
        present(composeScene[slot]);
        if (newContent) latencyEma = latencyEma > 0.0 ? latencyEma * 0.9 : 0.0;
    } else {
        // Even spacing across the source interval. The first generated frame
        // becomes visible once compute finishes, so later presents are timed
        // from that point; FIFO then snaps each one to its own refresh.
        const double slice = intervalMs / (double)(k + 1);
        const double gpuLead = std::min(gpuMsEma, slice * 0.6);
        Clock::time_point anchor{};
        bool anchored = false;
        bool onTime = true;
        for (int j = 0; j < k && onTime; ++j) {
            if (anchored) {
                const auto target = anchor + std::chrono::duration_cast<Clock::duration>(
                    std::chrono::duration<double, std::milli>(j * slice));
                if (!(onTime = paceUntil(target))) break;
                if (msBetween(target, Clock::now()) > std::max(slice * 0.5, 6.0)) continue;
            }
            if (!present(composeGen[(genBase + j) % kGenImages])) continue;
            if (!anchored) {
                anchor = Clock::now() + std::chrono::duration_cast<Clock::duration>(
                    std::chrono::duration<double, std::milli>(gpuLead));
                anchored = true;
            }
        }
        if (anchored && onTime) {
            paceUntil(anchor + std::chrono::duration_cast<Clock::duration>(
                std::chrono::duration<double, std::milli>(k * slice)));
        }
        present(composeScene[slot]);
        double added = msBetween(arrival, Clock::now());
        latencyEma = latencyEma > 0.0 ? latencyEma * 0.85 + added * 0.15 : added;
    }
    if (waitCompute) drain(computeDone);
    if (waitScene) drain(sceneDone);

    if (newContent || curSlot < 0) {
        prevSlot = curSlot;
        curSlot = slot;
        if (!doGen) pyrValid[slot] = false;
    } else {
        curSlot = slot;
        pyrValid[slot] = false;
    }

    publishStats(false, overloaded ? 3 : (appliedQuality < quality.load() ? 4 : 1));
    return true;
}

// Performance: motion is estimated once per real frame; every vsync then gets
// its own image, interpolated right before it is presented for the content
// time dynX it shows. dynX follows (vsync - holdMs) at a speed close to real
// time, so the output advances evenly whatever the cadence of the game.
// Returns when a new frame arrives or there is nothing newer to show.
bool FrameGenState::renderDynamic(VulkanRendererContext* c, const FrameBuild& fb, int slot,
                                  Clock::time_point arrival, bool newContent, bool dupFrame,
                                  bool gap) {
    auto& vk = c->vk_;
    auto ms = [](double v) {
        return std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(v));
    };
    const double p = vsyncPeriodMs();
    const double lead = presentLeadMs();
    const double snap = kSnapRefresh * p;
    pyrValid[slot] = false;

    bool cursorUpload = true;
    bool waitScene = true;  // until something waits for this capture's scene pass
    auto present = [&](VkDescriptorSet ds) {
        const bool ok = presentCompose(c, ds, fb, cursorUpload, waitScene ? sceneDone : VK_NULL_HANDLE);
        if (ok) {
            waitScene = false;
            cursorUpload = false;
            dynShown = ds;
        }
        return ok;
    };
    auto finish = [&]() {
        if (waitScene) {
            VkPipelineStageFlags stg = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.waitSemaphoreCount = 1;
            si.pWaitSemaphores = &sceneDone;
            si.pWaitDstStageMask = &stg;
            vk.QueueSubmit(c->graphicsQueue, 1, &si, VK_NULL_HANDLE);
        }
        publishStats(false, overloaded ? 3 : (appliedQuality < quality.load() ? 4 : 1));
        return true;
    };

    bool gen = false;
    if (newContent && !dupFrame) {
        float phases[kMaxGen];
        if (!gap && dynCount > 0 && intervalSamples >= 3 && intervalMs > 0.0)
            gen = planPhases(intervalMs, arrival, phases) > 0 && haveClock;
        lastK = gen ? 1 : 0;
    }
    Clock::time_point base;
    const bool grid = vsyncBase(base);

    if (!grid || !(dupFrame || (newContent && gen))) {
        // Shown as is: generation off, a redraw, or no vsync timing.
        restartDyn(slot, haveClock ? contentTime : arrival);
        haveFieldPrev = false;
        present(composeScene[slot]);
        if (newContent) latencyEma = latencyEma > 0.0 ? latencyEma * 0.9 : 0.0;
        return finish();
    }

    // Motion estimation of new frames waits until right after the next present:
    // the vsyncs just ahead still show older pairs, and queued in front of
    // their interpolation it would make them miss the latch.
    auto flushAnalysis = [&]() {
        for (int i = 1; i < dynCount; ++i) {
            if (!dyn[i].pending) continue;
            submitAnalysis(c, dyn[i].slot, dyn[i - 1].slot);
            dyn[i].pending = false;
        }
    };

    if (dupFrame) {
        // The same frame again: the newer copy replaces it.
        DynFrame& f = dyn[dynCount - 1];
        f.slot = slot;
        f.paired = f.pending = f.paired && dynCount >= 2;
    } else {
        // A late frame is still on the content clock, so morphing into it keeps
        // the timing; only a freeze too long for motion estimation is a cut.
        const bool cut = msBetween(dyn[dynCount - 1].ct, contentTime) > kMaxMorphMs;
        DynFrame f{slot, contentTime, !cut, cut, !cut};
        if (cut) haveFieldPrev = false;
        dyn[dynCount++] = f;
        // Within `snap` of a frame its vsync shows the frame itself, so the next
        // one is not needed yet: the hold can be that much shorter.
        const double late = std::min(lateMs, kHitchIntervals * clockIvMs);
        holdMs = std::min(std::max(late - snap, 0.0) + lead + kQueueMs, kMaxHoldMs);
    }

    for (;;) {
        if (!vsyncBase(base)) break;
        const auto now = Clock::now();
        Clock::time_point v = haveDynV ? nearestVsync(base, dynV + ms(p))
                                       : vsyncAtOrAfter(base, now + ms(lead));
        // Presents block until about the previous vsync, so being past `due` is
        // normal; a vsync is only lost once the latch plus the GPU work no
        // longer fit before it. The image on screen then stays one more refresh.
        const double minAhead = kLatchMs + 2.0 + interpMsEma;
        if (msBetween(now, v) < minAhead) v = vsyncAtOrAfter(base, now + ms(minAhead));

        // Decide the image before waiting. A real frame that lands while waiting
        // is captured at once: presenting here would block that copy and drop it.
        const DynFrame& last = dyn[dynCount - 1];
        const auto limit = last.ct + ms(snap);
        const auto desired = v - ms(holdMs);
        const double dv = haveDynV && !dynStalled ? std::max(p, msBetween(dynV, v)) : p;
        Clock::time_point x;
        const double err = msBetween(dynX + ms(dv), desired);
        if (!haveDynX || err > kJumpMs) {
            x = std::min(desired, last.ct);
        } else {
            const double s = 1.0 + std::clamp(err / kCatchMs, -kSpeedRange, kSpeedRange);
            x = dynX + ms(dv * s);
            if (x > limit) x = dynX + ms(kBrake * std::max(0.0, msBetween(dynX, limit)));
        }
        if (haveDynX && x < dynX) x = dynX;

        int i = 0;
        while (i < dynCount - 1 && dyn[i].ct < x) ++i;
        VkDescriptorSet ds = composeScene[dyn[i].slot];
        // After a long freeze (a cut) the game resumes as soon as possible.
        // Only the vsyncs that land between two real frames are interpolated;
        // the real frame itself is shown unchanged.
        bool wantInterp = false, needField = false;
        int slotA = -1, slotB = -1, genImg = -1;
        float interpT = 0.f;
        if (i > 0 && x < dyn[i].ct && !dyn[i].cut) {
            const DynFrame& a = dyn[i - 1];
            const DynFrame& b = dyn[i];
            const double fromA = msBetween(a.ct, x), toB = msBetween(x, b.ct);
            if (!b.paired) {
                if (fromA < toB) ds = composeScene[a.slot];
            } else if (fromA <= snap) {
                ds = composeScene[a.slot];
            } else if (toB > snap) {
                wantInterp = true;
                needField = b.pending;
                slotA = a.slot;
                slotB = b.slot;
                interpT = (float)(fromA / (fromA + toB));
            }
        }
        auto copyInterp = [&]() {
            if (!wantInterp || genImg >= 0) return;
            if (needField) flushAnalysis();
            genImg = genHead;
            genHead = (genHead + 1) % kGenImages;
            ds = composeGen[genImg];
            submitInterp(c, slotA, slotB, interpT, genImg);
        };
        // Copy ahead only while the next real frame is not due. Queued in front
        // of its capture, the copy would make that frame miss.
        const double copyMs = (needField ? gpuMsEma : 0.0) + interpMsEma + 3.0;
        const double untilFrame = intervalMs - msBetween(lastArrival, now);
        if (wantInterp && untilFrame > copyMs) copyInterp();

        const auto due = v - ms(lead);
        while (Clock::now() < due)
            if (!paceUntil(due)) return finish();
        copyInterp();

        dynStalled = false;
        dynX = x;
        dynV = v;
        haveDynX = haveDynV = true;
        if (ds != dynShown) present(ds);
        flushAnalysis();
        const double added = std::max(0.0, msBetween(x, v) - 0.5 * p - kLatchMs);
        latencyEma = latencyEma > 0.0 ? latencyEma * 0.95 + added * 0.05 : added;
        if (msBetween(x, last.ct) < 0.5 && ds == composeScene[last.slot]) {
            dynStalled = true;
            break;
        }
    }
    return finish();
}

// ---------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------

static FrameGenState* stateOf(VulkanRendererContext* c) { return c->tuningFrameGen.load(); }

static FrameGenState* ensureState(VulkanRendererContext* c) {
    FrameGenState* st = c->tuningFrameGen.load();
    if (st) return st;
    auto* fresh = new FrameGenState();
    FrameGenState* expected = nullptr;
    if (c->tuningFrameGen.compare_exchange_strong(expected, fresh)) return fresh;
    delete fresh;
    return expected;
}

bool VulkanRendererFrameGenRender(VulkanRendererContext* c) {
    FrameGenState* st = stateOf(c);
    if (!st) return false;

    if (!st->enabled.load()) {
        if (st->active) st->deactivate(c);
        return false;
    }
    if (st->failed) return false;

    if (!st->active && !st->activate(c)) {
        st->failed = true;
        st->publishStats(true, 2);
        return false;
    }

    try {
        if (st->renderFrame(c)) return true;
    } catch (const std::exception& e) {
        FG_LOGE("frame failed, falling back to the normal path: %s", e.what());
        std::lock_guard<std::mutex> lk(c->renderMutex);
        c->pendingFrameSerial = 0;
    }
    st->failed = true;
    st->deactivate(c);
    st->publishStats(true, 2);
    return false;
}

void VulkanRendererFrameGenNoteContent(VulkanRendererContext* c, int w, int h) {
    FrameGenState* st = stateOf(c);
    if (!st || !st->enabled.load(std::memory_order_relaxed)) return;
    const float area = (float)w * (float)h;
    if (area >= kContentAreaShare * (float)c->containerWidth * (float)c->containerHeight)
        st->contentSerial.fetch_add(1, std::memory_order_relaxed);
}

void VulkanRendererFrameGenConfigure(VulkanRendererContext* c, bool enabled, int mode, int quality,
                                     int multiplier, float targetHz) {
    if (!c) return;
    FrameGenState* st = ensureState(c);
    st->mode.store(std::clamp(mode, 0, 1));
    st->quality.store(std::clamp(quality, 0, 2));
    st->multiplier.store(std::clamp(multiplier, 2, 3));
    st->targetHz.store(targetHz > 1.f ? targetHz : 60.f);
    if (enabled && !st->enabled.load()) st->failed = false;
    st->enabled.store(enabled);
    c->needsRender.store(true);
    c->dirtyCV.notify_one();
}

FrameGenStats VulkanRendererFrameGenGetStats(VulkanRendererContext* c) {
    FrameGenStats out;
    FrameGenState* st = c ? stateOf(c) : nullptr;
    if (!st) return out;
    std::lock_guard<std::mutex> lk(st->statsMutex);
    out = st->stats;
    if (!st->enabled.load()) out.state = 0;
    return out;
}

void VulkanRendererFrameGenNoteVsync(int64_t frameTimeNs) {
    gVsyncNs.store(frameTimeNs, std::memory_order_relaxed);
}

bool VulkanRendererFrameGenSourceUncapped() {
    return gSourceUncapped.load(std::memory_order_relaxed);
}

void VulkanRendererFrameGenDestroy(VulkanRendererContext* c) {
    FrameGenState* st = c->tuningFrameGen.exchange(nullptr);
    if (!st) return;
    st->enabled.store(false);
    st->forcedFifo = false;  // the swapchain is going away with the context
    if (st->active || st->scenePass) st->deactivate(c);
    delete st;
}
