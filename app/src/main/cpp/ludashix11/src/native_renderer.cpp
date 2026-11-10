#include "native_screen.h"
#include "VulkanRendererContext.h"



namespace xorg {
class LudashiRenderer final : public NativeRenderer {
public:
    LudashiRenderer(u16 w, u16 h, bool validate) : width(w), height(h), validation(validate) {}
    ~LudashiRenderer() override { if (context) context->detachSurface(); context.reset(); }
    void Surface(ANativeWindow* window) override {
        if (!window) { if (context) context->detachSurface(); return; }
        if (!context) {
            try {
                context = std::make_unique<VulkanRendererContext>(window, width, height, nullptr, validation);
                // This session has its own scene worker. Prevent a second renderer thread
                // from sampling a producer buffer before native Present has retained it.
                context->isRunning.store(false); context->dirtyCV.notify_all();
                if (context->renderThread.joinable()) context->renderThread.join();
                context->setVerboseLog(false);
            } catch (...) { ANativeWindow_release(window); throw; }
        } else {
            if (!context->surfaceDetached.load()) context->detachSurface();
            bool attached = context->reattachSurface(window);
            if (!attached) throw std::runtime_error("Cannot attach native Vulkan surface");
        }
        context->onSurfaceResized(ANativeWindow_getWidth(context->window), ANativeWindow_getHeight(context->window));
        UpdateTransform();
    }
    void Options(bool fs, bool visible, int, float rate) override {
        fullscreen = fs; cursor = visible; refresh = rate;
        if (context) {
            context->setCursorVisible(visible);
            // Do not force a 60 Hz surface frame-rate vote. Android controls
            // the panel mode; this compositor must remain free to present faster.
            UpdateTransform();
        }
    }
    size_t PollCompleted(size_t maxReady) override {
        std::lock_guard lock(pendingMutex);
        return PollLocked(maxReady);
    }
    size_t WaitCompleted(size_t maxReady) override {
        Submission submission;
        {
            std::lock_guard lock(pendingMutex);
            if (!maxReady || pending.empty()) return 0;
            submission = pending.front();
        }
        // ReadyToDraw keeps this slot from being reset until retirement removes
        // it. NativeScreen serializes this wait with Drain/surface destruction.
        // Never hold pendingMutex while waiting: Draw may publish another slot.
        const auto result = submission.wait(submission.device, 1, &submission.fence,
                                            VK_TRUE, 10000000); // bounded shutdown/error latency
        if (result == VK_TIMEOUT) return 0;
        if (result != VK_SUCCESS) throw std::runtime_error("Native Vulkan fence wait failed");
        return PollCompleted(maxReady);
    }
    size_t PollLocked(size_t maxReady) {
        size_t ready = 0;
        while (ready < maxReady && !pending.empty()) {
            const auto& submission = pending.front();
            const VkResult status = submission.getStatus
                ? submission.getStatus(submission.device, submission.fence)
                : submission.wait(submission.device, 1, &submission.fence, VK_TRUE, 0);
            if (status == VK_NOT_READY || status == VK_TIMEOUT) break;
            if (status != VK_SUCCESS) throw std::runtime_error("Native Vulkan fence status failed");
            pending.pop_front();
            ++ready;
        }
        return ready;
    }
    size_t Drain() override {
        std::lock_guard lock(pendingMutex);
        size_t ready = PollLocked(SIZE_MAX);
        while (!pending.empty()) {
            const auto& submission = pending.front();
            if (submission.wait(submission.device, 1, &submission.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS)
                throw std::runtime_error("Native Vulkan surface drain failed");
            pending.pop_front();
            ++ready;
        }
        return ready;
    }
    bool ReadyToDraw() const override {
        if (!context || context->surfaceDetached.load()) return true;
        const uint32_t slot = context->currentFrame;
        std::lock_guard lock(pendingMutex);
        return std::none_of(pending.begin(), pending.end(),
                            [slot](const Submission& item) { return item.slot == slot; });
    }
    NativeAHBMetrics TakeAHBMetrics() override {
        NativeAHBMetrics snapshot = ahbMetrics;
        snapshot.cacheEntries = context ? context->ahbImportCache.size() : 0;
        ahbMetrics = {};
        return snapshot;
    }
    bool Draw(NativeFrame& frame, const NativeFrameLatch& latch) override {
        if (!context || context->surfaceDetached.load()) return false;
        context->setNativeDiagnostics(frame.diagnostics);
        context->nativeCommandCacheEnabled = frame.commandCacheEnabled;
        // Never enter renderFrame's slot wait while the prior submission is still
        // running. Let the producer continue and retain the last valid scene.
        if (!ReadyToDraw()) return false;
        const uint32_t slot = context->currentFrame;
        struct Binding {
            LudashiRenderer* renderer;
            NativeFrame* frame;
            const NativeFrameLatch* latch;
        } binding{this, &frame, &latch};
        context->nativeFrameReadyData = &binding;
        context->nativeFrameReady = [](void* value) {
            auto& b = *static_cast<Binding*>(value);
            if (*b.latch) (*b.latch)(*b.frame);
            b.renderer->UpdateScene(*b.frame);
        };
        struct ClearBinding {
            VulkanRendererContext* context;
            ~ClearBinding() { context->nativeFrameReady = nullptr; context->nativeFrameReadyData = nullptr; }
        } clearBinding{context.get()};
        const u64 serial = context->slotSerial[slot];
        context->renderFrame();
        if (context->slotSerial[slot] == serial) return false;
        // Retain precisely the scene sampled after acquire, through its GPU fence.
        {
            std::lock_guard lock(pendingMutex);
            pending.push_back({slot, context->device, context->inFlightFences[slot],
                               context->vk_.GetFenceStatus, context->vk_.WaitForFences});
        }
        return true;
    }
private:
    void UpdateScene(const NativeFrame& frame) {
        ids.clear(); xs.clear(); ys.clear();
        ids.reserve(frame.entries.size()); xs.reserve(frame.entries.size()); ys.reserve(frame.entries.size());
        for (const auto& entry : frame.entries) {
            // Java-compatible IDLE does not invalidate scene content. Resource
            // references remain retained independently until this GPU work retires.
            auto& d = *entry.drawable;
            u64 id = d.ID(); registry.try_emplace(id, entry.drawable);
            if (AHardwareBuffer* ahb = d.HardwareBuffer()) {
                const auto imported = context->texMap.find(id);
                if (imported == context->texMap.end() || !imported->second.isAHB ||
                    imported->second.ahb != ahb) {
                    // Optional diagnostics: no cache probe or clock read when disabled.
                    const bool trace = frame.diagnostics;
                    const bool cached = trace && context->ahbImportCache.contains(ahb);
                    const auto start = trace && !cached
                        ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
                    context->updateWindowContentAHB(id, ahb, d.GetWidth(), d.GetHeight(), entry.x, entry.y);
                    const auto updated = context->texMap.find(id);
                    if (updated == context->texMap.end() || !updated->second.isAHB ||
                        updated->second.ahb != ahb)
                        throw std::runtime_error("Native AHB import failed");
                    if (trace) {
                        if (cached) {
                            ++ahbMetrics.cacheHits;
                        } else {
                            ++ahbMetrics.vkImports;
                            const auto ns = static_cast<u64>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                std::chrono::steady_clock::now() - start).count());
                            ahbMetrics.importNs += ns;
                            ahbMetrics.maxImportNs = std::max(ahbMetrics.maxImportNs, ns);
#ifdef LUDASHI_NATIVE_X11
                            if (importLogCount++ < 8)
                                __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer",
                                    "updateWindowContentAHB: imported new AHB %p img=%p for id=%llu (%dx%d)",
                                    (void*)ahb, (void*)updated->second.img,
                                    static_cast<unsigned long long>(id), updated->second.w, updated->second.h);
#endif
                        }
                    }
                } else if (frame.diagnostics) {
                    ++ahbMetrics.unchangedDrawables;
                }
            } else if (revisions[id] != entry.revision) {
                const auto& pixels = *entry.pixels;
                // X11's native little-endian 0xAARRGGBB is already BGRA in memory.
                // VulkanRendererContext's CPU texture is VK_FORMAT_B8G8R8A8_UNORM.
                // The upload copies these bytes without modifying the source.
                context->updateWindowContent(id, const_cast<u8*>(pixels.data()),
                                             d.GetWidth(), d.GetHeight(), d.GetWidth(),
                                             entry.x, entry.y);
                revisions[id] = entry.revision;
            }
            ids.push_back(id); xs.push_back(entry.x); ys.push_back(entry.y);
        }
        for (auto i = registry.begin(); i != registry.end();) {
            if (i->second.expired()) {
                context->removeWindow(i->first); revisions.erase(i->first); i = registry.erase(i);
            } else ++i;
        }
        context->setExternalRenderList(ids.data(), xs.data(), ys.data(), ids.size());
        if (frame.cursor && frame.cursorPixels && cursorID != frame.cursor->ID()) {
            const auto& d = *frame.cursor;
            // Cursor pixels share the same BGRA byte order as desktop uploads.
            context->updateCursorImage(const_cast<u8*>(frame.cursorPixels->data()),
                                       d.GetWidth(), d.GetHeight(), d.GetWidth(), 0, 0);
            cursorID = d.ID();
        }
        context->setCursorVisible(cursor && bool(frame.cursor));
        context->updatePointerPosition(frame.cursorX, frame.cursorY);
    }
    void UpdateTransform() {
        if (!context) return;
        float aspect = std::min(float(context->surfaceWidth) / width, float(context->surfaceHeight) / height);
        float sx = fullscreen ? 1.f : width * aspect / context->surfaceWidth;
        float sy = fullscreen ? 1.f : height * aspect / context->surfaceHeight;
        context->setTransform(width * (1.f - sx) * .5f, height * (1.f - sy) * .5f, sx, sy);
    }
    // The completion worker reads only immutable submission handles, never
    // mutable context state. ReadyToDraw prevents resetting a tracked slot;
    // removing its signaled fence under this mutex permits safe reuse.
    struct Submission {
        uint32_t slot;
        VkDevice device;
        VkFence fence;
        PFN_vkGetFenceStatus getStatus;
        PFN_vkWaitForFences wait;
    };
    mutable std::mutex pendingMutex;
    std::deque<Submission> pending;
    int width, height;
    const bool validation;
    bool fullscreen{}, cursor{true};
    float refresh{60};
    u64 cursorID{};
    NativeAHBMetrics ahbMetrics{};
    u32 importLogCount{};
    std::unique_ptr<VulkanRendererContext> context;
    std::unordered_map<u64, u64> revisions;
    std::unordered_map<u64, std::weak_ptr<NativeDrawable>> registry;
    // Stable window counts reuse these buffers across frames and cursor redraws.
    std::vector<int64_t> ids;
    std::vector<int> xs, ys;
};
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 w, u16 h, bool validation) {
    return std::make_unique<LudashiRenderer>(w, h, validation);
}
}


