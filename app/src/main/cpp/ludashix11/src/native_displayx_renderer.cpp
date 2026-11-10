#include "native_screen.h"
#include "native_backend_geometry.h"
#include <android/surface_control.h>
#include <android/performance_hint.h>
#include <sys/syscall.h>
#include <dlfcn.h>
#include <unordered_set>

namespace xorg {
namespace {
struct SurfaceAPI {
    struct Library {
        void *handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        ~Library() {
            if (handle)
                dlclose(handle);
        }
        operator void *() const {
            return handle;
        }
    } library;
    template <class T> T Load(const char *name) {
        auto function = reinterpret_cast<T>(library ? dlsym(library, name) : nullptr);
        if (!function)
            throw std::runtime_error("X++ DisplayX requires Android 10 SurfaceControl support");
        return function;
    }
    ASurfaceControl *(*root)(ANativeWindow *,
                             const char *) = Load<decltype(root)>("ASurfaceControl_createFromWindow");
    ASurfaceControl *(*child)(ASurfaceControl *,
                              const char *) = Load<decltype(child)>("ASurfaceControl_create");
    void (*release)(ASurfaceControl *) = Load<decltype(release)>("ASurfaceControl_release");
    ASurfaceTransaction *(*transaction)() = Load<decltype(transaction)>("ASurfaceTransaction_create");
    void (*destroy)(ASurfaceTransaction *) = Load<decltype(destroy)>("ASurfaceTransaction_delete");
    void (*apply)(ASurfaceTransaction *) = Load<decltype(apply)>("ASurfaceTransaction_apply");
    void (*buffer)(ASurfaceTransaction *, ASurfaceControl *, AHardwareBuffer *,
                   int) = Load<decltype(buffer)>("ASurfaceTransaction_setBuffer");
    void (*geometry)(ASurfaceTransaction *, ASurfaceControl *, const ARect &, const ARect &,
                     int32_t) = Load<decltype(geometry)>("ASurfaceTransaction_setGeometry");
    void (*order)(ASurfaceTransaction *, ASurfaceControl *,
                  int32_t) = Load<decltype(order)>("ASurfaceTransaction_setZOrder");
    void (*visibility)(ASurfaceTransaction *, ASurfaceControl *, ASurfaceTransactionVisibility) =
        Load<decltype(visibility)>("ASurfaceTransaction_setVisibility");
    void (*transparency)(ASurfaceTransaction *, ASurfaceControl *, ASurfaceTransactionTransparency) =
        Load<decltype(transparency)>("ASurfaceTransaction_setBufferTransparency");
    void (*complete)(ASurfaceTransaction *, void *, ASurfaceTransaction_OnComplete) =
        Load<decltype(complete)>("ASurfaceTransaction_setOnComplete");
    void (*reparent)(ASurfaceTransaction *, ASurfaceControl *,
                     ASurfaceControl *) = Load<decltype(reparent)>("ASurfaceTransaction_reparent");
    using BackPressure = void (*)(ASurfaceTransaction *, ASurfaceControl *, bool);
    BackPressure backPressure =
        reinterpret_cast<BackPressure>(dlsym(library, "ASurfaceTransaction_setEnableBackPressure"));
    using GetHintManager = APerformanceHintManager *(*)();
    using CreateHintSession = APerformanceHintSession *(*)(APerformanceHintManager *, const int32_t *, size_t,
                                                           int64_t);
    using UpdateHint = int (*)(APerformanceHintSession *, int64_t);
    using CloseHint = void (*)(APerformanceHintSession *);
    GetHintManager hintManager =
        reinterpret_cast<GetHintManager>(dlsym(library, "APerformanceHint_getManager"));
    CreateHintSession createHint =
        reinterpret_cast<CreateHintSession>(dlsym(library, "APerformanceHint_createSession"));
    UpdateHint hintTarget =
        reinterpret_cast<UpdateHint>(dlsym(library, "APerformanceHint_updateTargetWorkDuration"));
    UpdateHint hintWork =
        reinterpret_cast<UpdateHint>(dlsym(library, "APerformanceHint_reportActualWorkDuration"));
    CloseHint closeHint = reinterpret_cast<CloseHint>(dlsym(library, "APerformanceHint_closeSession"));
    void (*presentTime)(ASurfaceTransaction *, int64_t) =
        reinterpret_cast<decltype(presentTime)>(dlsym(library, "ASurfaceTransaction_setDesiredPresentTime"));
};
struct HardwareBuffer {
    AHardwareBuffer *value{};
    explicit HardwareBuffer(AHardwareBuffer *buffer) : value(buffer) {
        if (value)
            AHardwareBuffer_acquire(value);
    }
    ~HardwareBuffer() {
        if (value)
            AHardwareBuffer_release(value);
    }
};
class DisplayXNativeRenderer final : public NativeRenderer {
    struct Layer {
        ASurfaceControl *control{};
        sp<HardwareBuffer> image;
        u64 drawable{}, revision{};
        std::weak_ptr<NativeExternalFrame> presentation;
        bool attached{};
    };
    struct Submission {
        bool completed{};
        std::vector<sp<NativeExternalFrame>> external;
    };
    struct Queue {
        std::mutex mutex;
        std::condition_variable cv;
        std::deque<sp<Submission>> pending;
    };
    struct Callback {
        sp<Queue> queue;
        sp<Submission> submission;
    };
    std::unique_ptr<SurfaceAPI> apiOwner;
    SurfaceAPI *api{};
    NativeRendererConfig config;
    int width, height;
    ANativeWindow *window{};
    ASurfaceControl *root{};
    APerformanceHintSession *hint{};
    std::unordered_map<u64, Layer> layers;
    sp<Queue> queue = std::make_shared<Queue>();
    bool fullscreen{}, cursorVisible{true};
    float zoom{1}, refresh{60};
    NativeMagnifierPan pan;
    NativeBackendGeometry geometry;
    std::chrono::steady_clock::time_point target{};
    static void OnComplete(void *value, ASurfaceTransactionStats *) {
        std::unique_ptr<Callback> callback(static_cast<Callback *>(value));
        for (const auto &frame : callback->submission->external)
            frame->Complete();
        {
            std::lock_guard lock(callback->queue->mutex);
            callback->submission->completed = true;
        }
        callback->queue->cv.notify_all();
    }
    size_t PollLocked(size_t limit) {
        size_t count{};
        while (count < limit && !queue->pending.empty() && queue->pending.front()->completed) {
            queue->pending.pop_front();
            ++count;
        }
        return count;
    }
    sp<HardwareBuffer> Image(const sp<NativeDrawable> &drawable, const sp<const std::vector<u8>> &pixels) {
        if (auto *buffer = drawable->HardwareBuffer())
            return std::make_shared<HardwareBuffer>(buffer);
        if (!pixels)
            throw std::runtime_error("Missing X++ DisplayX pixel snapshot");
        AHardwareBuffer_Desc description{};
        description.width = drawable->GetWidth();
        description.height = drawable->GetHeight();
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        description.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        AHardwareBuffer *buffer{};
        if (AHardwareBuffer_allocate(&description, &buffer))
            throw std::runtime_error("Cannot allocate X++ DisplayX backing buffer");
        auto result = std::make_shared<HardwareBuffer>(buffer);
        AHardwareBuffer_release(buffer);
        AHardwareBuffer_describe(buffer, &description);
        void *address{};
        if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &address))
            throw std::runtime_error("Cannot update X++ DisplayX backing buffer");
        for (u32 y = 0; y < drawable->GetHeight(); ++y) {
            const auto *source =
                reinterpret_cast<const u32 *>(pixels->data() + size_t(y) * drawable->GetStride());
            auto *destination = static_cast<u32 *>(address) + size_t(y) * description.stride;
            for (u32 x = 0; x < drawable->GetWidth(); ++x) {
                const u32 pixel = source[x];
                destination[x] = (pixel & 0xff00ff00u) | ((pixel & 0xffu) << 16) | ((pixel >> 16) & 0xffu);
            }
        }
        AHardwareBuffer_unlock(buffer, nullptr);
        return result;
    }
    void SetLayer(ASurfaceTransaction *transaction, u64 key, const sp<NativeDrawable> &drawable,
                  const sp<const std::vector<u8>> &pixels, u64 revision, int x, int y, int w, int h,
                  int order, bool cursor, const sp<NativeExternalFrame> &external = {}) {
        auto &layer = layers[key];
        if (!layer.control) {
            layer.control = api->child(root, cursor ? "X++ cursor" : "X++ window");
            if (!layer.control)
                throw std::runtime_error("Cannot create X++ DisplayX layer");
        }
        const bool changed = layer.drawable != drawable->ID() || layer.revision != revision;
        if (changed) {
            layer.image = Image(drawable, pixels);
            layer.drawable = drawable->ID();
            layer.revision = revision;
        }
        const float left = geometry.ox + x * geometry.sx, top = geometry.oy + y * geometry.sy;
        const float right = left + w * geometry.sx, bottom = top + h * geometry.sy;
        ARect destination{std::max(geometry.left, int(std::floor(left))),
                          std::max(geometry.top, int(std::floor(top))),
                          std::min(geometry.right, int(std::ceil(right))),
                          std::min(geometry.bottom, int(std::ceil(bottom)))};
        const bool visible = destination.left < destination.right && destination.top < destination.bottom;
        api->visibility(transaction, layer.control,
                        visible ? ASURFACE_TRANSACTION_VISIBILITY_SHOW
                                : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        if (!visible)
            return;
        ARect source{
            std::clamp(int(std::floor((destination.left - left) * drawable->GetWidth() / (right - left))), 0,
                       int(drawable->GetWidth())),
            std::clamp(int(std::floor((destination.top - top) * drawable->GetHeight() / (bottom - top))), 0,
                       int(drawable->GetHeight())),
            std::clamp(int(std::ceil((destination.right - left) * drawable->GetWidth() / (right - left))), 0,
                       int(drawable->GetWidth())),
            std::clamp(int(std::ceil((destination.bottom - top) * drawable->GetHeight() / (bottom - top))), 0,
                       int(drawable->GetHeight()))};
        if (changed || !layer.attached || (external && layer.presentation.lock() != external)) {
            int fence = external && external->fence >= 0 ? dup(external->fence) : -1;
            api->buffer(transaction, layer.control, layer.image->value, fence);
            layer.presentation = external;
            layer.attached = true;
        }
        api->geometry(transaction, layer.control, source, destination, 0);
        api->order(transaction, layer.control, order);
        api->transparency(transaction, layer.control,
                          cursor ? ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT
                                 : ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        if (api->backPressure)
            api->backPressure(transaction, layer.control, config.presentMode == 2);
    }

  public:
    DisplayXNativeRenderer(int w, int h, const NativeRendererConfig &options)
        : config(options), width(w), height(h) {}
    ~DisplayXNativeRenderer() override {
        try {
            PrepareSurfaceChange();
            Drain();
            Surface(nullptr);
        } catch (...) {
        }
    }
    void PrepareSurfaceChange() override {
        if (!root)
            return;
        auto *transaction = api->transaction();
        for (auto &[key, layer] : layers) {
            api->buffer(transaction, layer.control, nullptr, -1);
            layer.attached = false;
            api->visibility(transaction, layer.control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        }
        api->visibility(transaction, root, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        api->apply(transaction);
        api->destroy(transaction);
    }
    void Surface(ANativeWindow *next) override {
        if (root) {
            auto *transaction = api->transaction();
            for (auto &[key, layer] : layers) {
                api->buffer(transaction, layer.control, nullptr, -1);
                api->reparent(transaction, layer.control, nullptr);
                api->release(layer.control);
            }
            layers.clear();
            api->visibility(transaction, root, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            api->reparent(transaction, root, nullptr);
            api->apply(transaction);
            api->destroy(transaction);
            api->release(root);
            root = nullptr;
        }
        if (hint) {
            api->closeHint(hint);
            hint = nullptr;
        }
        if (window)
            ANativeWindow_release(window);
        window = next;
        target = {};
        if (window) {
            if (!api) {
                apiOwner = std::make_unique<SurfaceAPI>();
                api = apiOwner.get();
            }
            ANativeWindow_setBuffersGeometry(window, 0, 0, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            ANativeWindow_Buffer backing{};
            if (ANativeWindow_lock(window, &backing, nullptr) == 0) {
                for (int row = 0; row < backing.height; ++row)
                    std::fill_n(static_cast<u32 *>(backing.bits) + size_t(row) * backing.stride,
                                backing.width, 0xff000000u);
                ANativeWindow_unlockAndPost(window);
            }
            if (config.performanceMode && api->hintManager && api->createHint && api->hintWork &&
                api->closeHint) {
                const int32_t tid = syscall(SYS_gettid);
                if (auto *manager = api->hintManager())
                    hint = api->createHint(manager, &tid, 1, int64_t(1'000'000'000. / refresh));
            }
            root = api->root(window, "X++ DisplayX");
            if (!root)
                throw std::runtime_error("Cannot attach X++ DisplayX surface");
            auto *transaction = api->transaction();
            api->visibility(transaction, root, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
            api->apply(transaction);
            api->destroy(transaction);
        }
    }
    bool Draw(NativeFrame &frame, const NativeFrameLatch &latch) override {
        if (!root)
            return false;
        const auto workStart = std::chrono::steady_clock::now();
        if (latch)
            latch(frame);
        const int outerW = ANativeWindow_getWidth(window), outerH = ANativeWindow_getHeight(window);
        if (outerW <= 0 || outerH <= 0)
            return false;
        std::erase_if(frame.entries,
                      [](const auto &entry) { return entry.external && !entry.external->TryClaim(); });
        bool published = false;
        struct FailedFrame {
            NativeFrame &frame;
            bool &published;
            ~FailedFrame() {
                if (!published)
                    for (const auto &entry : frame.entries)
                        if (entry.external)
                            entry.external->Abandon();
            }
        } failed{frame, published};
        geometry.Update(outerW, outerH, width, height, fullscreen, zoom, frame.cursorX, frame.cursorY, pan);
        auto *transaction = api->transaction();
        struct Guard {
            SurfaceAPI *api;
            ASurfaceTransaction *value;
            ~Guard() {
                api->destroy(value);
            }
        } guard{api, transaction};
        auto submission = std::make_shared<Submission>();
        std::unordered_set<u64> visible;
        int order{};
        for (const auto &entry : frame.entries) {
            const u64 key = entry.window ? entry.window : entry.drawable->ID();
            visible.insert(key);
            SetLayer(transaction, key, entry.drawable, entry.pixels, entry.revision, entry.x, entry.y,
                     entry.windowWidth ? entry.windowWidth : entry.drawable->GetWidth(),
                     entry.windowHeight ? entry.windowHeight : entry.drawable->GetHeight(), ++order, false,
                     entry.external);
            if (entry.external)
                submission->external.push_back(entry.external);
        }
        constexpr u64 cursorKey = UINT64_MAX;
        if (cursorVisible && frame.cursor && frame.cursorPixels) {
            visible.insert(cursorKey);
            SetLayer(transaction, cursorKey, frame.cursor, frame.cursorPixels, frame.cursor->Revision(),
                     frame.cursorX, frame.cursorY, frame.cursor->GetWidth(), frame.cursor->GetHeight(),
                     INT32_MAX, true);
        }
        for (auto i = layers.begin(); i != layers.end();) {
            if (visible.contains(i->first)) {
                ++i;
                continue;
            }
            api->buffer(transaction, i->second.control, nullptr, -1);
            api->reparent(transaction, i->second.control, nullptr);
            api->release(i->second.control);
            i = layers.erase(i);
        }
        if (api->presentTime && config.presentAtRefreshRate) {
            const auto now = std::chrono::steady_clock::now();
            target = std::max(target, now);
            if (config.precisePresentation && target == now) {
                const int64_t period = int64_t(1'000'000'000. / refresh);
                const auto ns =
                    std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch()).count();
                target = std::chrono::steady_clock::time_point(
                    std::chrono::nanoseconds(((ns + period - 1) / period) * period));
            }
            const auto ns =
                std::chrono::duration_cast<std::chrono::nanoseconds>(target.time_since_epoch()).count();
            api->presentTime(transaction, ns);
            target += std::chrono::nanoseconds(int64_t(1'000'000'000. / refresh));
        }
        auto callback = std::make_unique<Callback>(Callback{queue, submission});
        api->complete(transaction, callback.get(), OnComplete);
        {
            std::lock_guard lock(queue->mutex);
            queue->pending.push_back(submission);
        }
        callback.release();
        api->apply(transaction);
        published = true;
        if (hint)
            api->hintWork(hint, std::max<int64_t>(1, std::chrono::duration_cast<std::chrono::nanoseconds>(
                                                         std::chrono::steady_clock::now() - workStart)
                                                         .count()));
        return true;
    }
    bool ReadyToDraw() const override {
        std::lock_guard lock(queue->mutex);
        return queue->pending.size() < (config.performanceMode ? 3u : 1u);
    }
    size_t PollCompleted(size_t limit) override {
        std::lock_guard lock(queue->mutex);
        return PollLocked(limit);
    }
    size_t WaitCompleted(size_t limit) override {
        std::unique_lock lock(queue->mutex);
        if (!limit || queue->pending.empty())
            return 0;
        queue->cv.wait_for(lock, std::chrono::milliseconds(10),
                           [&] { return queue->pending.front()->completed; });
        return PollLocked(limit);
    }
    size_t Drain() override {
        size_t count{};
        for (;;) {
            {
                std::lock_guard lock(queue->mutex);
                if (queue->pending.empty())
                    return count;
            }
            count += WaitCompleted(1);
        }
    }
    void Options(bool fs, bool visible, int, float hz, float value) override {
        fullscreen = fs;
        cursorVisible = visible;
        refresh = std::isfinite(hz) ? std::clamp(hz, 24.f, 1000.f) : 60.f;
        if (hint && api->hintTarget)
            api->hintTarget(hint, int64_t(1'000'000'000. / refresh));
        if (zoom != value)
            pan.Reset();
        zoom = value;
    }
    NativeVisualState VisualState() const override {
        return {root != nullptr, false, false, 0};
    }
};
} // namespace
std::unique_ptr<NativeRenderer> CreateNativeDisplayXRenderer(u16 w, u16 h,
                                                             const NativeRendererConfig &config) {
    return std::make_unique<DisplayXNativeRenderer>(w, h, config);
}
} // namespace xorg
