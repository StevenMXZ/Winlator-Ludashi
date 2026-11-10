#pragma once
#include <condition_variable>
#include <chrono>
#include <deque>
#include <map>
#include "core/xscreen.h"
#include "core/xresource.h"
#include "native_drawable.h"

namespace xorg {
class XClient;
class Window;
struct NativeFence : XResource {
    explicit NativeFence(u32 id, bool value) : XResource(XResourceType::Fence, id), triggered(value) {}
    bool triggered;
};
struct Subscription : XResource {
    Subscription(u32 id, XClient* c, u32 w, u32 m)
        : XResource(XResourceType::PresentSubscription, id), client(c), window(w), mask(m) {}
    XClient* client;
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
struct NativeEntry {
    sp<NativeDrawable> drawable;
    sp<PresentTicket> ticket;
    sp<const std::vector<u8>> pixels;
    u64 revision{};
    int x{}, y{};
    bool opaque{};
};
struct NativeFrame {
    bool diagnostics{};
    std::vector<NativeEntry> entries;
    std::vector<sp<PresentTicket>> requests;
    sp<NativeDrawable> cursor;
    sp<const std::vector<u8>> cursorPixels;
    int cursorX{}, cursorY{};
    std::chrono::steady_clock::time_point diagnosticSubmit{};
};
class NativeRenderer {
public:
    virtual ~NativeRenderer() = default;
    virtual void Surface(ANativeWindow*) = 0;
    virtual bool Draw(const NativeFrame&) = 0;
    virtual bool ReadyToDraw() const = 0;
    // May run alongside Draw. Only consume submissions already registered by
    // NativeScreen; Draw can publish its fence before returning its frame.
    virtual size_t PollCompleted(size_t maxReady) = 0;
    // Retirement thread only. Sleep in the oldest registered GPU fence wait,
    // outside the submission mutex, instead of periodically polling every slot.
    virtual size_t WaitCompleted(size_t maxReady) = 0;
    // Surface changes may block once; never drain the GPU on the Present hot path.
    virtual size_t Drain() = 0;
    virtual void Options(bool fullscreen, bool cursor, int fps, float refresh) = 0;
};
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 width, u16 height);

class NativeScreen final : public XScreen {
public:
    explicit NativeScreen(XServer&);
    ~NativeScreen() override;
    sp<Drawable> CreateDrawable(u16, u16, u8) override;
    sp<Drawable> CreateDrawable(u16, u16, AHardwareBuffer*) override;
    void UpdateSurface(ANativeWindow*) override;
    void UpdateCursor(Drawable*, s16, s16) override;
    void PostInvalidate() override;
    void WaitIdle() override;
    void SetUpScalerEnabled(bool) override {}
    void SetPostProcessingEnabled(bool) override {}
    void Present(sp<PresentTicket>);
    void Subscribe(sp<Subscription>);
    void RemoveClient(XClient*);
    void WindowDestroyed(u32);
    void DispatchCompletions();
    // epoll timeout for delayed producer IDLE notifications (negative = infinite).
    int IdleTimeoutMs() const;
    void ResumeWaitingClients();
    bool FenceTriggered(u32 id) const;
    void SetOptions(bool fullscreen, bool cursor, int fps, float refresh);
    u64 MSC() const;
    float RefreshRate() const { return refresh; }
    // RandR is a virtual display contract, independent of compositor pacing.
    // Configure under XServer::Lock before starting Wine.
    void SetRandrRates(std::vector<u16> rates);
    const std::vector<u16>& RandrRates() const { return randrRates; }
    u16 RandrRate() const { return randrRate.load(std::memory_order_relaxed); }
    bool SelectRandrRate(u16 rate);
    // Called under the XServer lock, as with scene/property changes.
    void SetUnviewableWMClasses(std::vector<std::string> classes);
    void WindowPropertyChanged(Window* window, u32 atom);
    void SetDiagnostics(bool enabled);
    void ProtocolError(int client, u8 opcode, u8 minor, u8 code, u32 resource);
    bool DiagnosticsEnabled() const { return diagnostics.load(std::memory_order_relaxed); }
    std::string LastError();
    std::atomic<u64> presentedCount{0};
    // Accessed only under XServer::Lock; disabled HUD adds one branch per update.
    void SetHudEnabled(bool enabled) { hudEnabled = enabled; hudWindow = 0; hudFrames = 0; }
    void HudFrame(u32 drawable);
    u32 HudWindow() const { return hudWindow; }
    u64 HudFrames() const { return hudFrames; }
private:
    bool hudEnabled{};
    u32 hudWindow{};
    u64 hudFrames{};
    s32 hudEngineAtom{-1}, hudGpuAtom{-1};
    u32 hudAtomCount{};
    // Only the pending scene needs an index for pixmap replacement. GPU frames
    // retain tickets through entries/requests without copying this map.
    struct Scene : NativeFrame {
        std::map<u32, sp<PresentTicket>> tickets;
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
    void Collect(Window*, Scene&, bool ancestorsMapped);
    bool PatchPresent(const sp<PresentTicket>&);
    bool IsWindowFiltered(Window*) const;
    void Worker();
    bool PrepareIdle(const sp<PresentTicket>&);
    void Idle(const sp<PresentTicket>&);
    void FlushDeferredIdles();
    void Complete(const sp<PresentTicket>&, bool idleFirst = false);
    std::mutex state;
    std::condition_variable cv;
    bool stop{}, dirty{}, surfaceDirty{}, optionsDirty{true}, drawing{}, sceneCurrent{};
    size_t gpuPending{};
    bool fullscreen{}, cursorVisible{true};
    int fps{};
    std::atomic<int> producerLimit{0};
    std::atomic<float> refresh{60};
    std::vector<u16> randrRates{60};
    std::atomic<u16> randrRate{60};
    std::vector<std::string> unviewableWMClasses;
    std::atomic<bool> diagnostics{false};
    std::atomic<u64> metricPresents{0}, metricIdles{0}, metricCoalesced{0}, metricErrors{0};
    std::atomic<u64> metricScenePatches{0}, metricSceneBuilds{0}, metricCursorPatches{0};
    Timing metricCompleteDelay, metricIdleDelay;
    ANativeWindow* surface{};
    std::thread worker;
    Scene latest;
    // Producer acknowledgements follow scene updates, independently of GPU retirement.
    std::map<u32, sp<PresentTicket>> direct;
    std::vector<sp<PresentTicket>> requests, waiting;
    // Only touched under the XServer lock (the same thread as Present/Idle).
    std::vector<sp<PresentTicket>> deferredIdles;
    std::map<u32, std::chrono::steady_clock::time_point> limiterDeadlines;
    int lastProducerLimit{};
    std::vector<sp<Subscription>> subscriptions;
    sp<NativeDrawable> cursor;
    int cursorX{}, cursorY{};
    u64 cursorSource{}, cursorRevision{};
    std::string error;
};
}

