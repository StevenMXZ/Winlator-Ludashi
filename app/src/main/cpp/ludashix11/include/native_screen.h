#pragma once
#include "native_renderer_config.h"
#include <condition_variable>
#include <chrono>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <thread>
#include <android/native_window.h>
#include "core/protocol_object.h"
#include "native_drawable.h"

namespace ludashi::x11 {
class ClientSession;
class DisplayRuntime;
class WindowNode;
struct NativeFence : ProtocolObject {
    explicit NativeFence(u32 id, bool value) : ProtocolObject(ObjectKind::Fence, id), triggered(value) {}
    bool triggered;
};
struct Subscription : ProtocolObject {
    Subscription(u32 id, ClientSession* c, u32 w, u32 m)
        : ProtocolObject(ObjectKind::PresentSubscription, id), client(c), window(w), mask(m) {}
    ClientSession* client;
    u32 window, mask;
};
struct PresentTicket {
    u32 window{}, pixmap{}, serial{}, idleFence{}, waitFence{};
    u8 opcode{};
    sp<NativeDrawable> drawable;
    s16 x{}, y{};
    bool complete{};
    bool idleDeferred{};
    std::chrono::steady_clock::time_point releaseAfter{};
    std::chrono::steady_clock::time_point diagnosticQueued{};
    std::atomic<bool> idle{false};
};
struct NativeExternalFrame {
    sp<NativeDrawable> drawable;
    int fence{-1};
    u32 presentMode{1}, colorSpace{UINT32_MAX};
    std::array<float, 12> hdr{};
    bool hasHdr{};
    std::function<void()> completion;
    std::atomic<bool> acknowledged{false}, claimed{false}, discarded{false};
    std::mutex completionMutex;
    std::condition_variable completionCv;
    bool TryClaim() {
        { std::lock_guard lock(completionMutex); if (discarded) return false; claimed = true; }
        completionCv.notify_all(); return true;
    }
    void Discard() {
        { std::lock_guard lock(completionMutex); if (claimed || acknowledged.exchange(true)) return; discarded = true; }
        if (completion) completion();
        completionCv.notify_all();
    }
    void Abandon() {
        { std::lock_guard lock(completionMutex); discarded = true; }
        Complete();
    }
    void Complete() {
        bool first;
        { std::lock_guard lock(completionMutex); first = !acknowledged.exchange(true); }
        if (first && completion) completion();
        completionCv.notify_all();
    }
    ~NativeExternalFrame() { if (fence >= 0) close(fence); Complete(); }
};
struct NativeEntry {
    sp<NativeDrawable> drawable;
    sp<PresentTicket> ticket;
    sp<const std::vector<u8>> pixels;
    u64 revision{};
    int x{}, y{};
    bool opaque{};
    u32 window{};
    int windowWidth{}, windowHeight{};
    sp<NativeExternalFrame> external;
};
struct NativeFrame {
    int64_t frameTimeline{-1};
    bool diagnostics{};
    bool commandCacheEnabled{true};
    u64 frameGenSourceFrames{};
    std::vector<NativeEntry> entries;
    std::vector<sp<PresentTicket>> requests;
    sp<NativeDrawable> cursor;
    sp<const std::vector<u8>> cursorPixels;
    int cursorX{}, cursorY{};
    std::chrono::steady_clock::time_point diagnosticSubmit{};
    std::chrono::steady_clock::time_point diagnosticSelected{};
};
struct NativeAHBMetrics {
    u64 vkImports{}, cacheHits{}, unchangedDrawables{}, cacheEntries{};
    u64 importNs{}, maxImportNs{};
};
struct NativeVisualOptions {
    int filter{}, postFX{}, stretch{};
    float sharpness{.5f};
    bool frameGen{};
    std::string frameGenCache;
    int multiplier{2}, targetRate{-1}, flowScale{70};
    float refresh{60.f};
    int frameGenEngine{};
    bool operator==(const NativeVisualOptions&) const = default;
};
struct NativeVisualState {
    bool ready{}, supported{}, enabled{};
    u64 generatedFrames{};
};
using NativeFrameLatch = std::function<void(NativeFrame&)>;
class NativeRenderer {
public:
    virtual ~NativeRenderer() = default;
    // Direct scanout must clear queued layer buffers before a surface-loss drain.
    virtual void PrepareSurfaceChange() {}
    virtual void Surface(ANativeWindow*) = 0;
    // Refresh the retained scene only after the swapchain acquire has finished.
    virtual bool Draw(NativeFrame&, const NativeFrameLatch& latch = {}) = 0;
    virtual bool ReadyToDraw() const = 0;
    // May run alongside Draw. Only consume submissions already registered by
    // NativeScreen; Draw can publish its fence before returning its frame.
    virtual size_t PollCompleted(size_t maxReady) = 0;
    // Retirement thread only. Sleep in the oldest registered GPU fence wait,
    // outside the submission mutex, instead of periodically polling every slot.
    virtual size_t WaitCompleted(size_t maxReady) = 0;
    // Surface changes may block once; never drain the GPU on the Present hot path.
    virtual size_t Drain() = 0;
    virtual NativeAHBMetrics TakeAHBMetrics() { return {}; }
    virtual void Options(bool fullscreen, bool cursor, int fps, float refresh, float zoom) = 0;
    virtual void VisualOptions(const NativeVisualOptions&) {}
    virtual NativeVisualState VisualState() const { return {}; }
};
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 width, u16 height, bool validation = false, const NativeRendererConfig& config = {});

class NativeScreen final {
public:
    explicit NativeScreen(DisplayRuntime&);
    ~NativeScreen();
    sp<NativeDrawable> CreateDrawable(u16, u16, u8);
    sp<NativeDrawable> CreateDrawable(u16, u16, AHardwareBuffer*);
    void UpdateSurface(ANativeWindow*);
    void SetFrameTimeline(int64_t id);
    void UpdateCursor(const sp<NativeDrawable>&, s16, s16);
    void PostInvalidate(sp<PresentTicket> present = {});
    // Serialize CPU writes with snapshot selection. Pending scenes do not need
    // an immutable copy; frames already selected for the GPU still do.
    template<class Write> void MutateDrawable(NativeDrawable* drawable, const Write& write) {
        // Borrow the callback for this synchronous call instead of allocating
        // a std::function for drawing requests with several captured arguments.
        MutateDrawable(drawable, &write, [](const void* callback) {
            (*static_cast<const Write*>(callback))();
        });
    }
    void WaitIdle();
    void Present(sp<PresentTicket>);
    void Subscribe(sp<Subscription>);
    void RemoveClient(ClientSession*);
    void WindowDestroyed(WindowNode& window);
    void WindowGeometryChanged(WindowNode*, u32 pixmapFlags = 0);
    void DispatchCompletions();
    // epoll timeout for delayed producer IDLE notifications (negative = infinite).
    int IdleTimeoutMs() const;
    bool HasWaitingPresents() const { return !waiting.empty(); }
    void ResumeWaitingClients();
    bool FenceTriggered(u32 id) const;
    void FenceSignaled();
    void SetOptions(bool fullscreen, bool cursor, int fps, float refresh);
    void SetMagnifierZoom(float zoom);
    u64 SetVisualOptions(NativeVisualOptions options);
    std::array<u64, 3> VisualState();
    u64 MSC() const;
    float RefreshRate() const { return refresh; }
    // RandR is a virtual display contract, independent of compositor pacing.
    // Configure under DisplayRuntime::Lock before starting Wine.
    void SetRandrRates(std::vector<u16> rates);
    const std::vector<u16>& RandrRates() const { return randrRates; }
    u16 RandrRate() const { return randrRate.load(std::memory_order_relaxed); }
    bool SelectRandrRate(u16 rate);
    // Called under the DisplayRuntime lock, as with scene/property changes.
    void SetUnviewableWMClasses(std::vector<std::string> classes);
    void UpdateExternal(u32 window, u64 generation, sp<NativeExternalFrame> frame);
    void RemoveExternal(u32 window, u64 generation, const sp<NativeExternalFrame>& frame);
    void WindowPropertyChanged(WindowNode* window, u32 atom);
    void SetDiagnostics(bool enabled);
    void RecordDri3Import() { metricDri3Imports.fetch_add(1, std::memory_order_relaxed); }
    void SetCommandCacheEnabled(bool enabled) { commandCacheEnabled.store(enabled, std::memory_order_relaxed); }
    void RecordInputRead(u64 ns) { metricInputRead.Record(ns); }
    void RecordRequest(u64 ns, size_t bytes) {
        metricDispatch.Record(ns);
        metricRequests.fetch_add(1, std::memory_order_relaxed);
        metricRequestBytes.fetch_add(bytes, std::memory_order_relaxed);
    }
    void RecordServerLock(u64 ns) { metricServerLock.Record(ns); }
    void RecordOutputBackpressure(size_t bytes) {
        metricOutputBlocked.fetch_add(1, std::memory_order_relaxed);
        u64 old = metricOutputPeak.load(std::memory_order_relaxed);
        while (old < bytes && !metricOutputPeak.compare_exchange_weak(old, bytes, std::memory_order_relaxed)) {}
    }
    void ProtocolError(int client, u8 opcode, u8 minor, u8 code, u32 resource);
    bool DiagnosticsEnabled() const { return diagnostics.load(std::memory_order_relaxed); }
    std::string LastError();
    std::atomic<u64> presentedCount{0};
    // Accessed only under DisplayRuntime::Lock; disabled HUD adds one branch per update.
    void SetHudEnabled(bool enabled) { hudEnabled = enabled; hudWindow = 0; hudFrames = 0; }
    void HudFrame(u32 drawable);
    void StartupWindowUpdated(u32 window);
    void StartupDrawableUpdated(NativeDrawable* drawable);
    u32 HudWindow() const { return hudWindow; }
    u64 HudFrames() const { return hudFrames; }
    bool HasStartupContent() const { return startupContent.load(std::memory_order_relaxed); }
private:
    DisplayRuntime& server;
    std::atomic<bool> startupContent{false};
public:
private:
    void MutateDrawable(NativeDrawable*, const void*, void (*)(const void*));
    std::atomic<int64_t> displayXFrameTimeline{-1};
    bool hudEnabled{};
    u32 hudWindow{};
    u64 hudFrames{};
    s32 hudEngineAtom{-1}, hudGpuAtom{-1};
    u32 hudAtomCount{};
    // Keep the index with the pending scene. In-flight frames retain their images
    // through their entries and do not copy this index.
    struct Scene : NativeFrame {
        void Reindex();
        NativeEntry* FindEntry(u32 window);
        std::vector<std::pair<u32, size_t>> entriesByWindow;
    };
    struct Timing {
        std::atomic<u64> count{0}, total{0}, maximum{0};
        void Record(u64 ns) {
            total.fetch_add(ns, std::memory_order_relaxed);
            count.fetch_add(1, std::memory_order_relaxed);
            u64 previous = maximum.load(std::memory_order_relaxed);
            while (previous < ns && !maximum.compare_exchange_weak(previous, ns, std::memory_order_relaxed)) {}
        }
        std::pair<double, double> TakeMicroseconds() {
            const auto n = count.exchange(0);
            return {total.exchange(0) / 1000.0 / std::max<u64>(1, n), maximum.exchange(0) / 1000.0};
        }
    };
    void Collect(WindowNode*, Scene&, bool ancestorsMapped);
    bool PatchPresent(const sp<PresentTicket>&, const sp<PresentTicket>& previousDirect);
    bool IsWindowFiltered(WindowNode*) const;
    void Worker();
    bool PrepareIdle(const sp<PresentTicket>&);
    void Idle(const sp<PresentTicket>&);
    void FinishIdle(const sp<PresentTicket>&);
    void SendIdleEvent(const sp<PresentTicket>&);
    void FlushDeferredIdles();
    void Complete(const sp<PresentTicket>&, bool idleFirst = false);
    std::mutex state;
    std::condition_variable cv;
    bool stop{}, dirty{}, surfaceDirty{}, optionsDirty{true}, drawing{}, sceneCurrent{};
    bool visualDirty{};
    NativeVisualOptions visualOptions;
    NativeVisualState visualState;
    u64 visualRevision{}, appliedVisualRevision{};
    std::atomic<bool> frameGenRequested{false};
    std::atomic<u64> frameGenSources{0};
    size_t gpuPending{};
    bool fullscreen{}, cursorVisible{true};
    int fps{};
    float magnifierZoom{1.f};
    std::atomic<int> producerLimit{0};
    std::atomic<float> refresh{60};
    std::vector<u16> randrRates{60};
    std::atomic<u16> randrRate{60};
    std::vector<std::string> unviewableWMClasses;
    std::atomic<bool> diagnostics{false};
    std::atomic<bool> commandCacheEnabled{true};
    std::atomic<u64> metricPresents{0}, metricIdles{0}, metricCoalesced{0}, metricErrors{0};
    std::atomic<u64> metricDri3Imports{0};
    std::atomic<u64> metricScenePatches{0}, metricSceneBuilds{0}, metricCursorPatches{0}, metricDrawablePatches{0}, metricOffscreenWrites{0};
    std::atomic<u64> metricOffscreenPresents{0};
    Timing metricCompleteDelay, metricIdleDelay;
    Timing metricInputRead, metricDispatch, metricServerLock;
    std::atomic<u64> metricRequests{0}, metricRequestBytes{0}, metricOutputBlocked{0}, metricOutputPeak{0};
    ANativeWindow* surface{};
    std::thread worker;
    Scene latest;
    // Producer acknowledgements follow scene updates, independently of GPU retirement.
    std::map<u32, sp<PresentTicket>> direct;
    struct External { u64 generation; sp<NativeExternalFrame> frame; };
    std::map<u32, External> externalFrames;
    std::vector<sp<PresentTicket>> waiting;
    // Only touched under the DisplayRuntime lock (the same thread as Present/Idle).
    std::vector<sp<PresentTicket>> deferredIdles;
    std::chrono::steady_clock::time_point nextIdleDeadline{std::chrono::steady_clock::time_point::max()};
    std::map<u32, std::chrono::steady_clock::time_point> limiterDeadlines;
    int lastProducerLimit{};
    std::unordered_map<u32, std::vector<sp<Subscription>>> subscriptions;
    sp<NativeDrawable> cursor;
    int cursorX{}, cursorY{};
    std::string error;
};
}


