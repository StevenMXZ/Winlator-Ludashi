#include "native_screen.h"
#include "base/log.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xserver.h"
#include "core/extension.h"
#include "core/atom.h"
#include <string_view>
#include <X11/extensions/presentproto.h>

namespace xorg {
std::unique_ptr<XScreen> XScreen::Create(XServer& s) { return std::make_unique<NativeScreen>(s); }
XScreen::XScreen(XServer& s) : server(s) {}
XScreen::~XScreen() = default;
NativeScreen::NativeScreen(XServer& s) : XScreen(s), worker([this] { Worker(); }) {}
NativeScreen::~NativeScreen() {
    { std::lock_guard lock(state); stop = true; cv.notify_all(); }
    if (worker.joinable()) worker.join();
    if (surface) ANativeWindow_release(surface);
}
sp<Drawable> NativeScreen::CreateDrawable(u16 w, u16 h, u8 d) {
    return std::make_shared<NativeDrawable>(w, h, d);
}
sp<Drawable> NativeScreen::CreateDrawable(u16 w, u16 h, AHardwareBuffer* b) {
    return std::make_shared<NativeDrawable>(w, h, 32, b);
}
void NativeScreen::UpdateSurface(ANativeWindow* window) {
    std::lock_guard lock(state);
    if (surface) ANativeWindow_release(surface);
    surface = window; // Owns the reference supplied by ANativeWindow_fromSurface.
    surfaceDirty = true; dirty = true; cv.notify_one();
}
void NativeScreen::SetOptions(bool fs, bool visible, int limit, float rate) {
    const int bounded = std::clamp(limit, 0, 1000);
    {
        std::lock_guard lock(state);
        fullscreen = fs; cursorVisible = visible; fps = bounded;
        refresh = std::clamp(rate, 24.f, 240.f);
        producerLimit.store(bounded, std::memory_order_relaxed);
        optionsDirty = true; dirty = true; cv.notify_one();
    }
    // Wake X11 to release deferred IDLEs when the limiter is switched off.
    server.NotifyScreen();
}
void NativeScreen::UpdateCursor(Drawable* d, s16 x, s16 y) {
    // Core calls under the server lock. Snapshot the CPU cursor, never borrow it.
    auto* image = static_cast<NativeDrawable*>(d);
    const bool changed = image ? !cursor || cursorSource != image->ID() ||
        cursorRevision != image->Revision() : bool(cursor);
    if (!changed && cursorX == x && cursorY == y) return;
    if (!image) {
        cursor.reset(); cursorSource = 0; cursorRevision = 0;
    } else if (changed) {
        cursor = std::static_pointer_cast<NativeDrawable>(image->Clone());
        cursorSource = image->ID(); cursorRevision = image->Revision();
    }
    cursorX = x; cursorY = y;
    {
        std::lock_guard lock(state);
        if (sceneCurrent) {
            latest.cursor = cursor;
            latest.cursorPixels = cursor ? cursor->Pixels() : nullptr;
            latest.cursorX = x; latest.cursorY = y;
            dirty = true;
            cv.notify_one();
            if (DiagnosticsEnabled()) metricCursorPatches.fetch_add(1, std::memory_order_relaxed);
            return;
        }
    }
    PostInvalidate();
}
void NativeScreen::SetRandrRates(std::vector<u16> rates) {
    std::erase_if(rates, [](u16 hz) { return hz == 0 || hz > 1000; });
    std::sort(rates.begin(), rates.end(), std::greater<u16>());
    rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
    if (rates.empty() || rates == randrRates) return;
    randrRates = std::move(rates);
    // Java RandrExtension also defaults to the highest advertised mode.
    randrRate = randrRates.front();
}
bool NativeScreen::SelectRandrRate(u16 rate) {
    if (std::find(randrRates.begin(), randrRates.end(), rate) == randrRates.end()) return false;
    randrRate = rate;
    return true;
}
void NativeScreen::SetUnviewableWMClasses(std::vector<std::string> classes) {
    std::erase_if(classes, [](const auto& value) { return value.empty(); });
    unviewableWMClasses = std::move(classes);
    PostInvalidate();
}
void NativeScreen::WindowPropertyChanged(Window* window, u32 atom) {
    if (!unviewableWMClasses.empty() && atom == u32(Atom::GetID("WM_CLASS"))) PostInvalidate();
    if (!hudEnabled || !window) return;
    // Java's HUD binds its FPS source as soon as Wine/Mesa publishes a
    // _MESA_DRV property, not when the compositor happens to draw a frame.
    const std::string name = Atom::GetName(atom);
    if (name != "_MESA_DRV_ENGINE_NAME" && name != "_MESA_DRV_GPU_NAME") return;
    if (!window->GetProperty(atom)) return;
    auto* previous = server.GetResource(hudWindow);
    auto* selected = previous && previous->GetType() == XResourceType::Window
            ? previous->As<Window>() : nullptr;
    if (selected && selected->IsAllAncestorsMapped() && selected != window) {
        const auto engine = Atom::GetID("_MESA_DRV_ENGINE_NAME");
        const auto gpu = Atom::GetID("_MESA_DRV_GPU_NAME");
        if ((engine >= 0 && selected->GetProperty(engine)) ||
            (gpu >= 0 && selected->GetProperty(gpu))) return;
    }
    if (hudWindow != window->id) {
        hudWindow = window->id;
        hudFrames = 0;
    }
}
bool NativeScreen::IsWindowFiltered(Window* w) const {
    if (unviewableWMClasses.empty()) return false;
    auto* property = w->GetProperty(u32(Atom::GetID("WM_CLASS")));
    if (!property || property->format != 8) return false;
    const auto bytes = property->GetData();
    if (bytes.empty()) return false;
    const std::string_view value(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    return std::ranges::any_of(unviewableWMClasses, [&](const auto& name) {
        return value.find(name) != std::string_view::npos;
    });
}
void NativeScreen::Collect(Window* w, Scene& frame, bool ancestorsMapped) {
    bool viewable = ancestorsMapped && w->IsMapped();
    if (!viewable) return;
    if (!w->IsRoot() && !w->IsInputOnly() && w->GetDrawable() &&
        w->GetWidth() > 1 && w->GetHeight() > 1 && !IsWindowFiltered(w)) {
        auto i = direct.find(w->id);
        auto d = i == direct.end() ? std::static_pointer_cast<NativeDrawable>(w->SharedDrawable()) : i->second->drawable;
        const auto& rect = w->GetInRect();
        frame.entries.push_back({d, i == direct.end() ? nullptr : i->second, d->Pixels(), d->Revision(), rect.left, rect.top, w->GetDepth() != 32});
        if (i != direct.end()) {
            frame.entries.back().x += i->second->x;
            frame.entries.back().y += i->second->y;
            frame.tickets[w->id] = i->second;
        }
    }
    // Hiding a window's own content must not hide its mapped children: Wine
    // game windows can be parented beneath the filtered Explorer desktop.
    for (auto* child : w->GetChildren()) Collect(child, frame, viewable);
}
void NativeScreen::PostInvalidate() {
    if (DiagnosticsEnabled()) metricSceneBuilds.fetch_add(1, std::memory_order_relaxed);
    Scene frame;
    Collect(server.GetWindowManager().GetRootWindow(), frame, true);
    // An opaque fullscreen game makes desktop/background draws unnecessary.
    const auto& info = server.GetScreenInfo();
    for (size_t i = frame.entries.size(); i > 0; --i) {
        const auto& e = frame.entries[i - 1];
        if (e.opaque && e.x <= 0 && e.y <= 0 && e.x + e.drawable->GetWidth() >= info.width &&
            e.y + e.drawable->GetHeight() >= info.height) {
            frame.entries.erase(frame.entries.begin(), frame.entries.begin() + i - 1);
            break;
        }
    }
    for (auto i = frame.tickets.begin(); i != frame.tickets.end();) {
        bool visible = std::ranges::any_of(frame.entries, [&](auto& e) { return e.ticket == i->second; });
        if (!visible) i = frame.tickets.erase(i); else ++i;
    }
    frame.cursor = cursor;
    frame.cursorPixels = cursor ? cursor->Pixels() : nullptr;
    frame.cursorX = cursorX; frame.cursorY = cursorY;
    std::lock_guard lock(state);
    // Preserve pending requests still selected in the new snapshot. Protocol
    // acknowledgements have already been sent, just as in Java X11.
    for (const auto& old : latest.requests) {
        const auto next = frame.tickets.find(old->window);
        if (next != frame.tickets.end() && next->second == old)
            frame.requests.push_back(old);
        else {
            if (DiagnosticsEnabled()) metricCoalesced.fetch_add(1, std::memory_order_relaxed);
        }
    }
    frame.requests.insert(frame.requests.end(), requests.begin(), requests.end());
    requests.clear();
    latest = std::move(frame);
    sceneCurrent = true;
    dirty = true;
    cv.notify_one();
}
bool NativeScreen::PatchPresent(const sp<PresentTicket>& ticket) {
    std::lock_guard lock(state);
    if (!sceneCurrent) return false;
    auto previous = latest.tickets.find(ticket->window);
    if (previous == latest.tickets.end()) return false;
    const auto old = previous->second;
    // Size/offset changes can alter fullscreen culling. Rebuild in those cases,
    // and when the window is absent/occluded in the current scene snapshot.
    if (old->x != ticket->x || old->y != ticket->y ||
        old->drawable->GetWidth() != ticket->drawable->GetWidth() ||
        old->drawable->GetHeight() != ticket->drawable->GetHeight()) return false;
    auto entry = std::ranges::find_if(latest.entries, [&](const auto& item) { return item.ticket == old; });
    if (entry == latest.entries.end()) return false;
    entry->drawable = ticket->drawable;
    entry->ticket = ticket;
    entry->pixels = ticket->drawable->Pixels();
    entry->revision = ticket->drawable->Revision();
    previous->second = ticket;
    // A frame already claimed by Draw owns its separate request list. Updating
    // this pending snapshot never drops the resource references of that frame.
    for (auto i = latest.requests.begin(); i != latest.requests.end();) {
        if ((*i)->window == ticket->window) {
            if (DiagnosticsEnabled()) metricCoalesced.fetch_add(1, std::memory_order_relaxed);
            i = latest.requests.erase(i);
        } else ++i;
    }
    latest.requests.push_back(ticket);
    dirty = true;
    cv.notify_one();
    if (DiagnosticsEnabled()) metricScenePatches.fetch_add(1, std::memory_order_relaxed);
    return true;
}
void NativeScreen::HudFrame(u32 id) {
    if (!hudEnabled) return;
    auto* resource = server.GetResource(id);
    if (!resource || resource->GetType() != XResourceType::Window) return;
    auto* window = resource->As<Window>();
    if (window->IsRoot() || window->IsInputOnly() || window->GetWidth() <= 1 ||
        window->GetHeight() <= 1 || IsWindowFiltered(window)) return;
    // A filtered Wine/Explorer desktop may contain the actual game window.
    // Like Collect(), reject only the filtered drawable itself, never a mapped
    // child merely because one of its ancestors has a filtered WM_CLASS.
    const u32 atomCount = Atom::Count();
    if (atomCount != hudAtomCount) {
        hudEngineAtom = Atom::GetID("_MESA_DRV_ENGINE_NAME");
        hudGpuAtom = Atom::GetID("_MESA_DRV_GPU_NAME");
        hudAtomCount = atomCount;
    }
    const auto tagged = [&](Window* w) {
        return (hudEngineAtom >= 0 && w->GetProperty(hudEngineAtom)) ||
               (hudGpuAtom >= 0 && w->GetProperty(hudGpuAtom));
    };
    Window* anchor = window;
    bool candidateTagged = false;
    for (auto* w = window; w; w = w->GetParent()) {
        if (!w->IsMapped()) return;
        if (!candidateTagged && tagged(w)) { anchor = w; candidateTagged = true; }
    }
    auto* previous = server.GetResource(hudWindow);
    auto* active = previous && previous->GetType() == XResourceType::Window
            ? previous->As<Window>() : nullptr;
    if (active && (!active->IsAllAncestorsMapped() || IsWindowFiltered(active)))
        active = nullptr;
    if (active && active != anchor) {
        // Never reset the counter because a background or child window also
        // presents. Keep the first active Mesa renderer, just as the Java HUD
        // retains its selected renderer window until it disappears.
        if (tagged(active)) {
            if (active->IsAncestorOf(window)) ++hudFrames;
            return;
        }
        if (!candidateTagged) {
            if (active->IsAncestorOf(window)) ++hudFrames;
            return;
        }
    }
    if (hudWindow != anchor->id) { hudWindow = anchor->id; hudFrames = 0; }
    ++hudFrames;
}
void NativeScreen::Present(sp<PresentTicket> ticket) {
    if (!FenceTriggered(ticket->waitFence)) { waiting.push_back(std::move(ticket)); return; }
    if (DiagnosticsEnabled()) {
        metricPresents.fetch_add(1, std::memory_order_relaxed);
        ticket->diagnosticQueued = std::chrono::steady_clock::now();
    }
    HudFrame(ticket->window);
    // Limit producer buffer reuse, not the Vulkan compositor. A compositor-only
    // sleep made the game report unbounded FPS while rendering fewer frames.
    const int limit = producerLimit.load(std::memory_order_relaxed);
    if (limit != lastProducerLimit) {
        limiterDeadlines.clear();
        lastProducerLimit = limit;
    }
    if (limit > 0) {
        const auto now = std::chrono::steady_clock::now();
        auto& deadline = limiterDeadlines[ticket->window];
        deadline = std::max(deadline, now) + std::chrono::nanoseconds(1000000000LL / limit);
        ticket->releaseAfter = deadline;
    }
    direct[ticket->window] = ticket;
    if (!PatchPresent(ticket)) {
        requests.push_back(ticket);
        PostInvalidate();
    }
    // Match Java PresentExtension: acknowledge the scene update without waiting
    // for Draw, Vulkan submission, presentation, or GPU completion. Only an
    // explicitly configured producer limiter may defer IDLE.
    if (limit == 0) {
        Complete(ticket, true);
    } else {
        Complete(ticket);
        Idle(ticket);
    }
}
void NativeScreen::Subscribe(sp<Subscription> sub) {
    subscriptions.erase(std::remove_if(subscriptions.begin(), subscriptions.end(),
        [&](auto& s) { return s->id == sub->id && s->client == sub->client; }), subscriptions.end());
    if (sub->mask) subscriptions.push_back(std::move(sub));
}
void NativeScreen::RemoveClient(XClient* client) {
    { std::lock_guard lock(state); sceneCurrent = false; }
    subscriptions.erase(std::remove_if(subscriptions.begin(), subscriptions.end(),
        [client](auto& s) { return s->client == client; }), subscriptions.end());
    for (auto i = direct.begin(); i != direct.end();) {
        auto* w = server.GetResource(i->first);
        if (!w || (w->GetType() == XResourceType::Window && w->As<Window>()->GetClient() == client))
            i = direct.erase(i);
        else ++i;
    }
}
void NativeScreen::WindowDestroyed(u32 id) {
    if (hudWindow == id) { hudWindow = 0; hudFrames = 0; }
    { std::lock_guard lock(state); sceneCurrent = false; }
    direct.erase(id);
    limiterDeadlines.erase(id);
    waiting.erase(std::remove_if(waiting.begin(), waiting.end(), [id](auto& t) { return t->window == id; }), waiting.end());
    subscriptions.erase(std::remove_if(subscriptions.begin(), subscriptions.end(), [id](auto& sub) { return sub->window == id; }), subscriptions.end());
}
bool NativeScreen::FenceTriggered(u32 id) const {
    if (!id) return true;
    auto* r = server.GetResource(id);
    return r && r->GetType() == XResourceType::Fence && r->As<NativeFence>()->triggered;
}
u64 NativeScreen::MSC() const {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return u64(std::chrono::duration_cast<std::chrono::microseconds>(now).count() * double(refresh.load()) / 1000000.0);
}
bool NativeScreen::PrepareIdle(const sp<PresentTicket>& t) {
    if (!t || t->idle) return false;
    if (producerLimit.load(std::memory_order_relaxed) > 0 &&
        std::chrono::steady_clock::now() < t->releaseAfter) {
        if (!t->idleDeferred) {
            t->idleDeferred = true;
            deferredIdles.push_back(t);
        }
        return false;
    }
    t->idleDeferred = false;
    t->idle = true;
    if (DiagnosticsEnabled()) {
        metricIdles.fetch_add(1, std::memory_order_relaxed);
        const auto now = std::chrono::steady_clock::now();
        if (t->diagnosticQueued != decltype(now){})
            metricIdleDelay.Record(std::chrono::duration_cast<std::chrono::nanoseconds>(now - t->diagnosticQueued).count());
    }
    if (auto* f = server.GetResource(t->idleFence); f && f->GetType() == XResourceType::Fence) {
        f->As<NativeFence>()->triggered = true;
        server.NotifyScreen();
    }
    return true;
}
void NativeScreen::Idle(const sp<PresentTicket>& t) {
    if (!PrepareIdle(t)) return;
    for (auto& sub : subscriptions) if (sub->window == t->window && (sub->mask & PresentIdleNotifyMask)) {
        xPresentIdleNotify e{};
        e.type = GenericEvent; e.extension = t->opcode; e.sequenceNumber = sub->client->Sequence();
        e.length = (sizeof(e) - 32) / 4; e.evtype = PresentIdleNotify;
        e.eid = sub->id; e.window = t->window; e.pixmap = t->pixmap;
        e.serial = t->serial; e.idle_fence = t->idleFence;
        sub->client->SendPacket(&e, sizeof(e));
    }
}
void NativeScreen::FlushDeferredIdles() {
    if (deferredIdles.empty()) return;
    auto pending = std::move(deferredIdles);
    deferredIdles.clear();
    for (auto& ticket : pending) {
        ticket->idleDeferred = false;
        Idle(ticket);
    }
}
int NativeScreen::IdleTimeoutMs() const {
    if (deferredIdles.empty()) return -1;
    if (producerLimit.load(std::memory_order_relaxed) == 0) return 0;
    const auto now = std::chrono::steady_clock::now();
    int timeout = 2147483647;
    for (const auto& ticket : deferredIdles) {
        const auto delay = std::chrono::duration_cast<std::chrono::microseconds>(ticket->releaseAfter - now).count();
        if (delay <= 0) return 0;
        timeout = std::min(timeout, int(std::min<int64_t>((delay + 999) / 1000, 2147483647)));
    }
    return timeout;
}
void NativeScreen::Complete(const sp<PresentTicket>& t, bool idleFirst) {
    if (t->complete) return;
    const bool sendIdle = idleFirst && PrepareIdle(t);
    t->complete = true;
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    if (DiagnosticsEnabled() && t->diagnosticQueued != std::chrono::steady_clock::time_point{})
        metricCompleteDelay.Record(std::chrono::duration_cast<std::chrono::nanoseconds>(now - t->diagnosticQueued.time_since_epoch()).count());
    const u64 ust = std::chrono::duration_cast<std::chrono::microseconds>(now).count();
    // Use the same timestamp for UST and MSC instead of another clock read for
    // every subscriber. MSC retains the configured physical refresh rate.
    const u64 msc = u64(ust * double(refresh.load(std::memory_order_relaxed)) / 1000000.0);
    for (auto& sub : subscriptions) if (sub->window == t->window) {
        const bool idleEvent = sendIdle && (sub->mask & PresentIdleNotifyMask);
        const bool completeEvent = sub->mask & PresentCompleteNotifyMask;
        if (!idleEvent && !completeEvent) continue;
        struct {
            xPresentIdleNotify idle{};
            xPresentCompleteNotify complete{};
        } packet;
        static_assert(sizeof(packet) == sizeof(packet.idle) + sizeof(packet.complete));
        auto& e = packet.complete;
        e.type = GenericEvent; e.extension = t->opcode; e.sequenceNumber = sub->client->Sequence();
        e.length = (sizeof(e) - 32) / 4; e.evtype = PresentCompleteNotify;
        e.kind = PresentCompleteKindPixmap; e.mode = PresentCompleteModeCopy;
        e.eid = sub->id; e.window = t->window; e.serial = t->serial;
        e.ust = ust; e.msc = msc;
        auto& idle = packet.idle;
        idle.type = GenericEvent; idle.extension = t->opcode; idle.sequenceNumber = e.sequenceNumber;
        idle.length = (sizeof(idle) - 32) / 4; idle.evtype = PresentIdleNotify;
        idle.eid = sub->id; idle.window = t->window; idle.pixmap = t->pixmap;
        idle.serial = t->serial; idle.idle_fence = t->idleFence;
        // Both events become writable in one syscall, without waiting for the
        // next request or changing Java's IDLE-before-COPY event ordering.
        if (idleEvent && completeEvent) sub->client->SendPacket(&packet, sizeof(packet));
        else if (idleEvent) sub->client->SendPacket(&idle, sizeof(idle));
        else sub->client->SendPacket(&e, sizeof(e));
    }
}
void NativeScreen::DispatchCompletions() {
    for (auto i = waiting.begin(); i != waiting.end();) {
        if (FenceTriggered((*i)->waitFence)) { auto ticket = *i; i = waiting.erase(i); Present(std::move(ticket)); }
        else ++i;
    }
    FlushDeferredIdles();
}
void NativeScreen::WaitIdle() {
    std::unique_lock lock(state); cv.wait(lock, [this] { return !drawing && gpuPending == 0; });
}
std::string NativeScreen::LastError() { std::lock_guard lock(state); return error; }
void NativeScreen::SetDiagnostics(bool enabled) {
    diagnostics.store(enabled, std::memory_order_relaxed);
    if (enabled) LOGI("X11Metrics enabled by WINLATOR_X11=1");
}
void NativeScreen::ProtocolError(int client, u8 opcode, u8 minor, u8 code, u32 resource) {
    if (!DiagnosticsEnabled()) return;
    const u64 count = metricErrors.fetch_add(1, std::memory_order_relaxed);
    // Bound diagnostics if a client repeats an unsupported request every frame.
    if (count < 8) LOGE("X11ProtocolError client={} opcode={} minor={} code={} resource={:#x}",
                        client, opcode, minor, code, resource);
}
void NativeScreen::Worker() {
    const auto info = server.GetScreenInfo();
    auto renderer = CreateNativeRenderer(info.width, info.height);
    // Frames retain the AHBs until the corresponding GPU sampling fences signal.
    // Draw owns context changes. A separate worker retires previously published
    // submissions while Draw may be blocked in acquire/present for another slot.
    std::deque<NativeFrame> inFlight;
    std::mutex completionMutex;
    std::condition_variable retirementCv;
    bool retirementStop{};
    bool hasSurface = false;
    using Clock = std::chrono::steady_clock;
    Clock::time_point metricWindow{};
    u64 metricSubmits{}, metricBusy{}, metricDraws{};
    u64 metricDrawNs{}, metricDrawMaxNs{};
    std::atomic<u64> metricRetired{0};
    Timing metricFence, metricWait;
    u64 metricQueueNs{}, metricQueueMaxNs{}, metricQueued{};
    auto nanoseconds = [](auto start, auto end) { return u64(std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count()); };
    auto retire = [&](size_t ready) {
        while (ready--) {
            NativeFrame retired;
            {
                std::lock_guard lock(state);
                if (inFlight.empty()) break;
                const bool trace = DiagnosticsEnabled();
                const auto now = trace ? Clock::now() : Clock::time_point{};
                if (trace && inFlight.front().diagnosticSubmit != Clock::time_point{}) {
                    metricFence.Record(nanoseconds(inFlight.front().diagnosticSubmit, now));
                    metricRetired.fetch_add(1, std::memory_order_relaxed);
                }
                retired = std::move(inFlight.front());
                inFlight.pop_front();
                gpuPending = inFlight.size();
                cv.notify_all();
            }
            // Destroy retained resources here, outside both the scene state
            // lock and the X11 request lock. No protocol event depends on this.
        }
    };
    std::thread retirement([&] {
        for (;;) {
            {
                std::unique_lock lock(state);
                retirementCv.wait(lock, [&] { return retirementStop || gpuPending != 0; });
                if (retirementStop) break;
            }
            {
                // Serialize the wait+retire pair with rare surface/shutdown
                // drains. Never hold this mutex around Draw or X11 I/O.
                std::lock_guard completionLock(completionMutex);
                size_t registered;
                { std::lock_guard lock(state); registered = inFlight.size(); }
                try {
                    const bool trace = DiagnosticsEnabled();
                    const auto start = trace ? Clock::now() : Clock::time_point{};
                    const size_t ready = renderer->WaitCompleted(registered);
                    if (trace) metricWait.Record(nanoseconds(start, Clock::now()));
                    retire(ready);
                } catch (const std::exception& e) {
                    std::unique_lock lock(state);
                    if (error != e.what()) LOGE("Native GPU fence wait failed: {}", e.what());
                    error = e.what();
                    // Avoid an error spin if the driver cannot wait for a fence.
                    retirementCv.wait_for(lock, std::chrono::milliseconds(10));
                }
            }
        }
    });
    for (;;) {
        {
            std::unique_lock lock(state);
            cv.wait(lock, [&] {
                return stop || surfaceDirty ||
                    (dirty && (!hasSurface || !renderer || renderer->ReadyToDraw()));
            });
            if (stop) break;
        }

        NativeFrame frame; ANativeWindow* nextSurface{};
        bool changeSurface{}, changeOptions{}, fs{}, visible{}, nextHasSurface{};
        int limit{}; float rate{};
        {
            std::unique_lock lock(state);
            if (!dirty) continue;
            if (!surfaceDirty && hasSurface && renderer && !renderer->ReadyToDraw()) {
                if (DiagnosticsEnabled()) ++metricBusy;
                // Do not consume the newest X11 request while the next Vulkan
                // slot is busy. A newer Present can replace it in the meantime.
                continue;
            }
            frame.entries = latest.entries;
            frame.requests.swap(latest.requests);
            frame.cursor = latest.cursor;
            frame.cursorPixels = latest.cursorPixels;
            frame.cursorX = latest.cursorX; frame.cursorY = latest.cursorY;
            dirty = false; drawing = true;
            changeSurface = std::exchange(surfaceDirty, false);
            nextHasSurface = surface != nullptr;
            if (changeSurface && surface) { nextSurface = surface; ANativeWindow_acquire(nextSurface); }
            changeOptions = std::exchange(optionsDirty, false);
            fs = fullscreen; visible = cursorVisible; limit = fps; rate = refresh;
        }
        bool submitted = false;
        const bool trace = DiagnosticsEnabled();
        frame.diagnostics = trace;
        const auto drawStart = trace ? Clock::now() : Clock::time_point{};
        try {
            if (changeSurface) {
                // Resizing/detaching is rare. Finish all previous sampling before
                // replacing Vulkan surface resources and retiring their tickets.
                {
                    std::lock_guard completionLock(completionMutex);
                    retire(renderer->Drain());
                }
                renderer->Surface(nextSurface); nextSurface = nullptr;
                hasSurface = nextHasSurface;
            }
            if (changeOptions) renderer->Options(fs, visible, limit, rate);
            // IDLE is a Java-compatible producer acknowledgement, not an
            // eligibility flag. Already-acknowledged scenes remain drawable.
            if (hasSurface) submitted = renderer->Draw(frame);
        } catch (const std::exception& e) {
            std::lock_guard lock(state);
            error = e.what();
            LOGE("Native presentation failed: {}", error);
        }
        if (nextSurface) ANativeWindow_release(nextSurface);
        if (trace) {
            const auto now = Clock::now();
            const u64 ns = nanoseconds(drawStart, now);
            ++metricDraws;
            metricDrawNs += ns; metricDrawMaxNs = std::max(metricDrawMaxNs, ns);
            if (submitted) {
                ++metricSubmits; frame.diagnosticSubmit = now;
                for (const auto& t : frame.requests) if (t->diagnosticQueued != Clock::time_point{}) {
                    const u64 queued = nanoseconds(t->diagnosticQueued, drawStart);
                    metricQueueNs += queued; metricQueueMaxNs = std::max(metricQueueMaxNs, queued); ++metricQueued;
                }
            }
        }
        if (submitted) {
            if (!frame.entries.empty()) presentedCount.fetch_add(1);
        }
        {
            std::lock_guard lock(state);
            if (submitted) {
                inFlight.push_back(std::move(frame));
            }
            gpuPending = inFlight.size();
            drawing = false;
            cv.notify_all();
        }
        if (submitted) retirementCv.notify_one();
        // Failed/surface-less frames are released by this worker outside locks.
        // GPU retirement wakes only the scene worker, never the X11 socket loop.
        if (trace) {
            const auto now = Clock::now();
            if (metricWindow == Clock::time_point{}) metricWindow = now;
            const double seconds = std::chrono::duration<double>(now - metricWindow).count();
            if (seconds >= 1.0) {
                const auto [fenceAvg, fenceMax] = metricFence.TakeMicroseconds();
                const auto waitAvg = metricWait.TakeMicroseconds().first;
                const auto [completeAvg, completeMax] = metricCompleteDelay.TakeMicroseconds();
                const auto [idleAvg, idleMax] = metricIdleDelay.TakeMicroseconds();
                LOGI("X11Metrics seconds={:.2f} present={} submit={} retired={} idle={} coalesced={} slot_busy={} "
                     "draw_us(avg/max)={:.1f}/{:.1f} fence_retire_us(avg/max)={:.1f}/{:.1f} fence_wait_us_avg={:.1f} "
                     "queue_us(avg/max)={:.1f}/{:.1f} "
                     "present_to_complete_us(avg/max)={:.1f}/{:.1f} present_to_idle_us(avg/max)={:.1f}/{:.1f} "
                     "scene_patch={} scene_build={} cursor_patch={} "
                     "virtual_hz={} panel_hz={:.1f} limiter={} protocol_errors={}",
                     seconds, metricPresents.exchange(0), metricSubmits, metricRetired.exchange(0), metricIdles.exchange(0),
                     metricCoalesced.exchange(0), metricBusy,
                     metricDrawNs / 1000.0 / std::max<u64>(1, metricDraws), metricDrawMaxNs / 1000.0,
                     fenceAvg, fenceMax, waitAvg,
                     metricQueueNs / 1000.0 / std::max<u64>(1, metricQueued), metricQueueMaxNs / 1000.0,
                     completeAvg, completeMax, idleAvg, idleMax,
                     metricScenePatches.exchange(0), metricSceneBuilds.exchange(0), metricCursorPatches.exchange(0),
                     RandrRate(), refresh.load(),
                     producerLimit.load(), metricErrors.exchange(0));
                metricWindow = now;
                metricSubmits = metricBusy = metricDrawNs = metricDrawMaxNs = 0;
                metricDraws = 0;
                metricQueueNs = metricQueueMaxNs = metricQueued = 0;
            }
        }
    }
    { std::lock_guard lock(state); retirementStop = true; }
    retirementCv.notify_one();
    retirement.join();
    // The in-flight frames own AHB references; drain the GPU before destruction.
    if (renderer) {
        try { retire(renderer->Drain()); }
        catch (const std::exception& e) { LOGE("Native GPU shutdown drain failed: {}", e.what()); }
    }
    renderer.reset();
    {
        std::lock_guard lock(state);
        gpuPending = 0;
        cv.notify_all();
    }
}
}

