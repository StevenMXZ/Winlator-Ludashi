#include "native_screen.h"
#include "native_displayx_visibility.h"
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
void NativeScreen::SetFrameTimeline(int64_t id) {
    std::lock_guard lock(state);
    displayXFrameTimeline.store(id, std::memory_order_relaxed);
    if (dirty) cv.notify_one();
}
void NativeScreen::SetOptions(bool fs, bool visible, int limit, float rate) {
    const int bounded = std::clamp(limit, 0, 1000);
    {
        std::lock_guard lock(state);
        fullscreen = fs; cursorVisible = visible; fps = bounded;
        refresh = std::isfinite(rate) ? std::clamp(rate, 24.f, 1000.f) : 60.f;
        producerLimit.store(bounded, std::memory_order_relaxed);
        optionsDirty = true; dirty = true; cv.notify_one();
    }
    // Wake X11 to release deferred IDLEs when the limiter is switched off.
    server.NotifyScreen();
}
void NativeScreen::SetMagnifierZoom(float value) {
    if (!std::isfinite(value)) value = 1.f;
    value = std::clamp(value, 1.f, 3.f);
    std::lock_guard lock(state);
    if (magnifierZoom == value) return;
    magnifierZoom = value;
    optionsDirty = true;
    dirty = true;
    cv.notify_one();
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
            // Keep input and the next visible snapshot current, without waking
            // the compositor solely for an invisible pointer.
            if (!cursorVisible) return;
            // One pending scene needs one wake, even during a burst of input.
            // Retirement separately wakes the worker when a GPU slot becomes free.
            if (!std::exchange(dirty, true)) cv.notify_one();
            if (DiagnosticsEnabled()) metricCursorPatches.fetch_add(1, std::memory_order_relaxed);
            return;
        }
    }
    PostInvalidate();
}
u64 NativeScreen::SetVisualOptions(NativeVisualOptions options) {
    options.filter = std::clamp(options.filter, 0, 5);
    options.postFX = std::clamp(options.postFX, 0, 20);
    options.stretch = options.stretch == 1 ? 1 : 0;
    options.sharpness = std::isfinite(options.sharpness) ? std::clamp(options.sharpness, 0.f, 1.f) : .5f;
    options.multiplier = std::clamp(options.multiplier, 2, 4);
    options.targetRate = std::clamp(options.targetRate, -1, 1000);
    options.flowScale = std::clamp(options.flowScale, 25, 100);
    options.refresh = std::isfinite(options.refresh) ? std::clamp(options.refresh, 24.f, 240.f) : 60.f;
    options.frameGen = options.frameGen && (options.frameGenEngine == 1 || !options.frameGenCache.empty());
    std::lock_guard lock(state);
    if (options == visualOptions) return visualRevision;
    visualOptions = std::move(options);
    frameGenRequested.store(visualOptions.frameGen, std::memory_order_relaxed);
    ++visualRevision;
    visualDirty = true; dirty = true; cv.notify_one();
    return visualRevision;
}
std::array<u64, 3> NativeScreen::VisualState() {
    std::lock_guard lock(state);
    return {appliedVisualRevision,
        u64(visualState.ready) | (u64(visualState.supported) << 1) | (u64(visualState.enabled) << 2),
        visualState.generatedFrames};
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
void NativeScreen::UpdateExternal(u32 id, u64 generation, sp<NativeExternalFrame> frame) {
    // Called under XServer::Lock, just like PresentPixmap.
    auto* resource = server.GetResource(id);
    if (!resource || resource->GetType() != XResourceType::Window ||
            resource->As<Window>()->generation != generation) return;
    externalFrames.insert_or_assign(id, External{generation, frame});
    HudFrame(id);
    {
        std::lock_guard lock(state);
        if (sceneCurrent) {
            auto entry = std::ranges::find_if(latest.entries, [&](const auto& value) { return value.window == id; });
            if (entry != latest.entries.end() && entry->drawable->GetWidth() == frame->drawable->GetWidth() &&
                    entry->drawable->GetHeight() == frame->drawable->GetHeight()) {
                entry->drawable = frame->drawable; entry->pixels.reset(); entry->revision = frame->drawable->Revision();
                entry->ticket.reset(); entry->external = frame; latest.tickets.erase(id);
                if (!std::exchange(dirty, true)) cv.notify_one();
                return;
            }
        }
    }
    PostInvalidate();
    // Keep an unsubmitted image while its window is hidden. FIFO can resume on
    // MapWindow without losing the last frame; mailbox replacements release it.
}
void NativeScreen::RemoveExternal(u32 id, u64 generation, const sp<NativeExternalFrame>& frame) {
    auto found = externalFrames.find(id);
    if (found != externalFrames.end() && found->second.generation == generation && found->second.frame == frame) {
        externalFrames.erase(found); PostInvalidate();
    }
}
void NativeScreen::Collect(Window* w, Scene& frame, bool ancestorsMapped) {
    bool viewable = ancestorsMapped && w->IsMapped();
    if (!viewable) return;
    if (!w->IsRoot() && !w->IsInputOnly() && w->GetDrawable() &&
        w->GetWidth() > 1 && w->GetHeight() > 1 && !IsWindowFiltered(w)) {
        auto i = direct.find(w->id);
        auto d = i == direct.end() ? std::static_pointer_cast<NativeDrawable>(w->SharedDrawable()) : i->second->drawable;
        auto external = externalFrames.find(w->id);
        if (external != externalFrames.end() && external->second.generation != w->generation) {
            externalFrames.erase(external); external = externalFrames.end();
        }
        if (external != externalFrames.end()) d = external->second.frame->drawable;
        const auto& rect = w->GetInRect();
        // Vulkan window quads force useTexAlpha=0, including depth-32 images.
        // Match that opacity when discarding fully covered layers.
        frame.entries.push_back({d, i == direct.end() ? nullptr : i->second, d->Pixels(), d->Revision(),
                rect.left, rect.top, true, w->id, int(w->GetWidth()), int(w->GetHeight()), {}});
        if (external != externalFrames.end()) {
            frame.entries.back().ticket.reset();
            frame.entries.back().external = external->second.frame;
        }
        if (i != direct.end() && external == externalFrames.end()) {
            frame.entries.back().x += i->second->x;
            frame.entries.back().y += i->second->y;
            frame.tickets[w->id] = i->second;
        }
    }
    // Hiding a window's own content must not hide its mapped children: Wine
    // game windows can be parented beneath the filtered Explorer desktop.
    for (auto* child : w->GetChildren()) Collect(child, frame, viewable);
}
void NativeScreen::MutateDrawable(Drawable* drawable, const void* callback, void (*write)(const void*)) {
    auto* image = static_cast<NativeDrawable*>(drawable);
    const u64 before = image->Revision();
    {
        std::unique_lock lock(state);
        for (auto& entry : latest.entries)
            if (entry.drawable.get() == image) entry.pixels.reset();
        // Restore a valid snapshot even if validation/allocation throws.
        auto restore = [&] {
            for (auto& entry : latest.entries) if (entry.drawable.get() == image) {
                entry.pixels = image->Pixels();
                entry.revision = image->Revision();
            }
        };
        try { write(callback); }
        catch (...) { restore(); throw; }
        restore();
    }
    if (image->Revision() != before) DrawableChanged(drawable);
}
void NativeScreen::DrawableChanged(Drawable* drawable) {
    // Called under XServer::Lock: pixels changed, but geometry/visibility did not.
    auto* image = static_cast<NativeDrawable*>(drawable);
    {
        std::lock_guard lock(state);
        if (sceneCurrent) {
            bool visible = false;
            for (auto& entry : latest.entries) if (entry.drawable.get() == image) {
                entry.pixels = image->Pixels();
                entry.revision = image->Revision();
                visible = true;
            }
            if (visible) {
                if (!std::exchange(dirty, true)) cv.notify_one();
                if (DiagnosticsEnabled()) metricDrawablePatches.fetch_add(1, std::memory_order_relaxed);
            } else if (DiagnosticsEnabled()) metricOffscreenWrites.fetch_add(1, std::memory_order_relaxed);
            // Offscreen/occluded content will be collected if later made visible.
            return;
        }
    }
    PostInvalidate();
}
void NativeScreen::PostInvalidate() {
    for (auto it = externalFrames.begin(); it != externalFrames.end();) {
        auto* resource = server.GetResource(it->first);
        if (!resource || resource->GetType() != XResourceType::Window ||
                resource->As<Window>()->generation != it->second.generation) {
            it->second.frame->Discard(); it = externalFrames.erase(it);
        }
        else ++it;
    }
    if (DiagnosticsEnabled()) metricSceneBuilds.fetch_add(1, std::memory_order_relaxed);
    Scene frame;
    Collect(server.GetWindowManager().GetRootWindow(), frame, true);
    // An opaque fullscreen game makes desktop/background draws unnecessary.
    const auto& info = server.GetScreenInfo();
    const bool displayX = server.RendererConfig().backend == NativeRendererConfig::Backend::DisplayX;
    if (displayX) CullCoveredDisplayXLayers(frame.entries);
    for (size_t i = frame.entries.size(); i > 0; --i) {
        const auto& e = frame.entries[i - 1];
        const int width = displayX && e.windowWidth > 0 ? e.windowWidth : e.drawable->GetWidth();
        const int height = displayX && e.windowHeight > 0 ? e.windowHeight : e.drawable->GetHeight();
        if (e.opaque && e.x <= 0 && e.y <= 0 && e.x + width >= info.width &&
            e.y + height >= info.height) {
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
    if (frameGenRequested.load(std::memory_order_relaxed) && visualOptions.frameGenEngine == 1)
        frame.frameGenSourceFrames = frameGenSources.load(std::memory_order_relaxed);
    latest = std::move(frame);
    sceneCurrent = true;
    // A pending scene is replaced in place; it needs no additional wake.
    if (!std::exchange(dirty, true)) cv.notify_one();
}
bool NativeScreen::PatchPresent(const sp<PresentTicket>& ticket, const sp<PresentTicket>& previousDirect) {
    std::lock_guard lock(state);
    if (!sceneCurrent) return false;
    auto previous = latest.tickets.find(ticket->window);
    if (previous == latest.tickets.end()) {
        // A hidden/filtered/occluded window has no selected entry. Content-only
        // Presents cannot change visibility when extent and offsets are stable.
        // Retain its newest buffer in direct, acknowledge normally, and avoid a
        // full tree walk/composite that would repaint an unrelated foreground.
        if (!previousDirect || previousDirect->x != ticket->x || previousDirect->y != ticket->y ||
            previousDirect->drawable->GetWidth() != ticket->drawable->GetWidth() ||
            previousDirect->drawable->GetHeight() != ticket->drawable->GetHeight()) return false;
        if (DiagnosticsEnabled()) metricOffscreenPresents.fetch_add(1, std::memory_order_relaxed);
        return true;
    }
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
    if (frameGenRequested.load(std::memory_order_relaxed) && visualOptions.frameGenEngine == 1)
        latest.frameGenSourceFrames = frameGenSources.load(std::memory_order_relaxed);
    // Do not wake a slot-blocked worker for every producer Present. It will
    // consume the newest snapshot after retirement notifies cv, or after Draw.
    if (!std::exchange(dirty, true)) cv.notify_one();
    if (DiagnosticsEnabled()) metricScenePatches.fetch_add(1, std::memory_order_relaxed);
    return true;
}
void NativeScreen::HudFrame(u32 id) {
    if (!hudEnabled && HasApplicationContent()) return;
    auto* resource = server.GetResource(id);
    if (!resource || resource->GetType() != XResourceType::Window) return;
    auto* window = resource->As<Window>();
    if (window->IsRoot() || window->IsInputOnly() || window->GetWidth() <= 1 ||
        window->GetHeight() <= 1 || IsWindowFiltered(window)) return;
    if (!HasApplicationContent() && window->IsAllAncestorsMapped()) {
        auto* hints = window->GetProperty(Atom::GetID("WM_HINTS"));
        if (hints && hints->format == 32 && hints->GetData().size() >= 9 * sizeof(u32)) {
            u32 group{};
            memcpy(&group, hints->GetData().data() + 8 * sizeof(u32), sizeof(group));
            if (group == window->id) applicationContent.store(true, std::memory_order_release);
        }
    }
    if (!hudEnabled) return;
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
    if (frameGenRequested.load(std::memory_order_relaxed))
        frameGenSources.fetch_add(1, std::memory_order_relaxed);
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
    auto& selected = direct[ticket->window];
    auto previousDirect = std::exchange(selected, ticket);
    if (!PatchPresent(ticket, previousDirect)) {
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
    auto i = subscriptions.find(sub->window);
    if (i != subscriptions.end()) {
        std::erase_if(i->second, [&](const auto& s) { return s->id == sub->id && s->client == sub->client; });
        if (i->second.empty() && !sub->mask) subscriptions.erase(i);
    }
    if (sub->mask) subscriptions[sub->window].push_back(std::move(sub));
}
void NativeScreen::RemoveClient(XClient* client) {
    { std::lock_guard lock(state); sceneCurrent = false; }
    for (auto i = subscriptions.begin(); i != subscriptions.end();) {
        std::erase_if(i->second, [client](const auto& s) { return s->client == client; });
        if (i->second.empty()) i = subscriptions.erase(i); else ++i;
    }
    for (auto i = direct.begin(); i != direct.end();) {
        auto* w = server.GetResource(i->first);
        if (!w || (w->GetType() == XResourceType::Window && w->As<Window>()->GetClient() == client))
            i = direct.erase(i);
        else ++i;
    }
}
void NativeScreen::WindowGeometryChanged(Window* window, u32 pixmapFlags) {
    // Match Java 40cfb9c8: Mesa DRI3/Freedreno listens to Present's GenericEvent,
    // independently of core StructureNotifyMask/ConfigureNotify subscriptions.
    auto selected = subscriptions.find(window->id);
    if (selected == subscriptions.end()) return;
    const auto* extension = server.GetExtensionManager().FindExtension("Present");
    const auto& rect = window->GetOutRect();
    const auto* parent = window->GetParent();
    for (const auto& sub : selected->second) if (sub->mask & PresentConfigureNotifyMask) {
        xPresentConfigureNotify event{};
        static_assert(sizeof(event) == 40);
        event.type = GenericEvent; event.extension = extension->base;
        event.sequenceNumber = sub->client->Sequence();
        event.length = (sizeof(event) - 32) / 4; event.evtype = PresentConfigureNotify;
        event.eid = sub->id; event.window = window->id;
        event.x = rect.left - (parent ? parent->GetInRect().left : 0);
        event.y = rect.top - (parent ? parent->GetInRect().top : 0);
        event.width = event.pixmap_width = window->GetWidth();
        event.height = event.pixmap_height = window->GetHeight();
        event.pixmap_flags = pixmapFlags;
        sub->client->SendPacket(&event, sizeof(event));
    }
}
void NativeScreen::WindowDestroyed(u32 id) {
    if (auto* resource = server.GetResource(id); resource && resource->GetType() == XResourceType::Window)
        WindowGeometryChanged(resource->As<Window>(), 1); // Java PIXMAP_FLAG_WINDOW_DESTROYED.
    if (hudWindow == id) { hudWindow = 0; hudFrames = 0; }
    { std::lock_guard lock(state); sceneCurrent = false; }
    direct.erase(id);
    limiterDeadlines.erase(id);
    waiting.erase(std::remove_if(waiting.begin(), waiting.end(), [id](auto& t) { return t->window == id; }), waiting.end());
    subscriptions.erase(id);
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
        if (!std::exchange(f->As<NativeFence>()->triggered, true)) FenceSignaled();
    }
    return true;
}
void NativeScreen::FenceSignaled() {
    if (server.HasSyncWaiters() || !waiting.empty()) server.NotifyScreen();
}
void NativeScreen::Idle(const sp<PresentTicket>& t) {
    if (!PrepareIdle(t)) return;
    auto selected = subscriptions.find(t->window);
    if (selected == subscriptions.end()) return;
    for (auto& sub : selected->second) if (sub->mask & PresentIdleNotifyMask) {
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
    auto selected = subscriptions.find(t->window);
    if (selected == subscriptions.end() && !DiagnosticsEnabled()) return;
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    if (DiagnosticsEnabled() && t->diagnosticQueued != std::chrono::steady_clock::time_point{})
        metricCompleteDelay.Record(std::chrono::duration_cast<std::chrono::nanoseconds>(now - t->diagnosticQueued.time_since_epoch()).count());
    const u64 ust = std::chrono::duration_cast<std::chrono::microseconds>(now).count();
    // Use the same timestamp for UST and MSC instead of another clock read for
    // every subscriber. MSC retains the configured physical refresh rate.
    const u64 msc = u64(ust * double(refresh.load(std::memory_order_relaxed)) / 1000000.0);
    if (selected == subscriptions.end()) return;
    for (auto& sub : selected->second) {
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
    auto renderer = CreateNativeRenderer(info.width, info.height, server.ValidationEnabled(), server.RendererConfig());
    // Frames retain the AHBs until the corresponding GPU sampling fences signal.
    // Draw owns context changes. A separate worker retires previously published
    // submissions while Draw may be blocked in acquire/present for another slot.
    std::deque<NativeFrame> inFlight;
    std::vector<NativeFrame> recycled;
    recycled.reserve(4);
    auto clearFrame = [](NativeFrame& frame) {
        frame.entries.clear(); frame.requests.clear();
        frame.cursor.reset(); frame.cursorPixels.reset();
        frame.diagnosticSubmit = {}; frame.diagnosticSelected = {};
    };
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
            clearFrame(retired);
            std::lock_guard lock(state);
            if (recycled.size() < 4) recycled.push_back(std::move(retired));
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
    // Reuse the temporary vectors. Never release stale drawable/AHB references
    // while holding state: resource destruction must not block X11 requests.
    NativeFrame refreshed;
    const NativeFrameLatch latch = [&](NativeFrame& frame) {
        // Recover the reusable temporary after an allocation failure as well.
        refreshed.entries.clear(); refreshed.requests.clear();
        refreshed.cursor.reset(); refreshed.cursorPixels.reset();
        bool changed = false;
        {
            std::lock_guard lock(state);
            if (dirty && sceneCurrent && !surfaceDirty && !optionsDirty && !stop) {
                refreshed.frameGenSourceFrames = latest.frameGenSourceFrames;
                refreshed.entries = latest.entries;
                refreshed.requests.swap(latest.requests);
                for (const auto& request : frame.requests) {
                    const bool selected = std::ranges::any_of(refreshed.entries,
                        [&](const auto& entry) { return entry.ticket == request; });
                    if (selected) {
                        if (std::ranges::find(refreshed.requests, request) == refreshed.requests.end())
                            refreshed.requests.push_back(request);
                    } else if (frame.diagnostics) metricCoalesced.fetch_add(1, std::memory_order_relaxed);
                }
                refreshed.cursor = latest.cursor;
                refreshed.cursorPixels = latest.cursorPixels;
                refreshed.cursorX = latest.cursorX; refreshed.cursorY = latest.cursorY;
                dirty = false;
                changed = true;
            }
        }
        if (changed) {
            frame.frameGenSourceFrames = refreshed.frameGenSourceFrames;
            frame.entries.swap(refreshed.entries);
            frame.requests.swap(refreshed.requests);
            frame.cursor.swap(refreshed.cursor);
            frame.cursorPixels.swap(refreshed.cursorPixels);
            frame.cursorX = refreshed.cursorX; frame.cursorY = refreshed.cursorY;
            refreshed.entries.clear(); refreshed.requests.clear();
            refreshed.cursor.reset(); refreshed.cursorPixels.reset();
        }
        if (frame.diagnostics) frame.diagnosticSelected = Clock::now();
    };
    // DisplayX does not block acquiring a Vulkan/EGL swapchain. Its selected
    // snapshot can go straight to SurfaceControl without a second scene copy
    // and another state-lock round trip inside Draw.
    int64_t lastDisplayXTimeline = -1;
    const bool paceDisplayX = server.RendererConfig().backend == NativeRendererConfig::Backend::DisplayX &&
        server.RendererConfig().presentAtRefreshRate;
    const NativeFrameLatch noLatch;
    const NativeFrameLatch& drawLatch = server.RendererConfig().backend ==
            NativeRendererConfig::Backend::DisplayX ? noLatch : latch;
    for (;;) {
        {
            std::unique_lock lock(state);
            cv.wait(lock, [&] {
                if (stop || surfaceDirty) return true;
                if (!dirty || (hasSurface && renderer && !renderer->ReadyToDraw())) return false;
                const auto timeline = displayXFrameTimeline.load(std::memory_order_relaxed);
                // FIFO delegates pacing to per-layer backpressure, as in Java.
                const bool fifo = std::ranges::any_of(latest.entries, [](const auto& e) {
                    return e.external && e.external->presentMode == 2;
                });
                return !hasSurface || !paceDisplayX || fifo || timeline < 0 || timeline != lastDisplayXTimeline;
            });
            if (stop) break;
        }

        NativeFrame frame; ANativeWindow* nextSurface{};
        bool changeSurface{}, changeOptions{}, changeVisual{}, fs{}, visible{}, nextHasSurface{};
        NativeVisualOptions nextVisual;
        u64 nextVisualRevision{};
        int limit{}, selectedFrameGenEngine{}; float rate{}, zoom{};
        {
            std::unique_lock lock(state);
            if (!dirty) continue;
            if (!surfaceDirty && hasSurface && renderer && !renderer->ReadyToDraw()) {
                if (DiagnosticsEnabled()) ++metricBusy;
                // Do not consume the newest X11 request while the next presentation
                // slot is busy. A newer Present can replace it in the meantime.
                continue;
            }
            if (!recycled.empty()) { frame = std::move(recycled.back()); recycled.pop_back(); }
            selectedFrameGenEngine = visualOptions.frameGenEngine;
            frame.frameGenSourceFrames = latest.frameGenSourceFrames;
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
            changeVisual = std::exchange(visualDirty, false) || changeSurface;
            if (changeVisual) { nextVisual = visualOptions; nextVisualRevision = visualRevision; }
            fs = fullscreen; visible = cursorVisible; limit = fps; rate = refresh;
            zoom = magnifierZoom;
        }
        bool submitted = false;
        const bool trace = DiagnosticsEnabled();
        frame.diagnostics = trace;
        frame.commandCacheEnabled = commandCacheEnabled.load(std::memory_order_relaxed);
        const auto drawStart = trace ? Clock::now() : Clock::time_point{};
        frame.diagnosticSelected = drawStart;
        try {
            if (changeSurface) {
                renderer->PrepareSurfaceChange();
                // Resizing/detaching is rare. Finish all previous sampling before
                // replacing presentation surface resources and retiring their tickets.
                {
                    std::lock_guard completionLock(completionMutex);
                    retire(renderer->Drain());
                }
                renderer->Surface(std::exchange(nextSurface, nullptr));
                hasSurface = nextHasSurface;
            }
            if (changeOptions) renderer->Options(fs, visible, limit, rate, zoom);
            if (changeVisual) {
                // Settings can replace samplers, swapchain images and fences.
                // Retire registered frames before invalidating those handles.
                std::lock_guard completionLock(completionMutex);
                retire(renderer->Drain());
                renderer->VisualOptions(nextVisual);
                std::lock_guard lock(state);
                visualState = renderer->VisualState();
                appliedVisualRevision = nextVisualRevision;
            }
            frame.frameTimeline = displayXFrameTimeline.load(std::memory_order_relaxed);
            if (selectedFrameGenEngine != 1)
                frame.frameGenSourceFrames = frameGenSources.load(std::memory_order_relaxed);
            // IDLE is a Java-compatible producer acknowledgement, not an
            // eligibility flag. Already-acknowledged scenes remain drawable.
            if (hasSurface) submitted = renderer->Draw(frame, drawLatch);
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
                    const u64 queued = nanoseconds(t->diagnosticQueued, frame.diagnosticSelected);
                    metricQueueNs += queued; metricQueueMaxNs = std::max(metricQueueMaxNs, queued); ++metricQueued;
                }
            }
        }
        if (submitted) {
            lastDisplayXTimeline = frame.frameTimeline;
            if (!frame.entries.empty()) presentedCount.fetch_add(1);
        }
        {
            std::lock_guard lock(state);
            if (submitted) {
                inFlight.push_back(std::move(frame));
            }
            if (frameGenRequested.load(std::memory_order_relaxed)) visualState = renderer->VisualState();
            gpuPending = inFlight.size();
            drawing = false;
            cv.notify_all();
        }
        if (submitted) retirementCv.notify_one();
        if (!submitted) {
            clearFrame(frame);
            std::lock_guard lock(state);
            if (recycled.size() < 4) recycled.push_back(std::move(frame));
        }
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
                     "scene_patch={} scene_build={} cursor_patch={} drawable_patch={} offscreen_write={} offscreen_present={} "
                     "virtual_hz={} panel_hz={:.1f} limiter={} protocol_errors={}",
                     seconds, metricPresents.exchange(0), metricSubmits, metricRetired.exchange(0), metricIdles.exchange(0),
                     metricCoalesced.exchange(0), metricBusy,
                     metricDrawNs / 1000.0 / std::max<u64>(1, metricDraws), metricDrawMaxNs / 1000.0,
                     fenceAvg, fenceMax, waitAvg,
                     metricQueueNs / 1000.0 / std::max<u64>(1, metricQueued), metricQueueMaxNs / 1000.0,
                     completeAvg, completeMax, idleAvg, idleMax,
                     metricScenePatches.exchange(0), metricSceneBuilds.exchange(0), metricCursorPatches.exchange(0),
                     metricDrawablePatches.exchange(0), metricOffscreenWrites.exchange(0),
                     metricOffscreenPresents.exchange(0),
                     RandrRate(), refresh.load(),
                     producerLimit.load(), metricErrors.exchange(0));
                const auto [readAvg, readMax] = metricInputRead.TakeMicroseconds();
                const auto [dispatchAvg, dispatchMax] = metricDispatch.TakeMicroseconds();
                const auto [lockAvg, lockMax] = metricServerLock.TakeMicroseconds();
                LOGI("X11RequestMetrics seconds={:.2f} requests={} bytes={} "
                     "input_read_us(avg/max)={:.1f}/{:.1f} dispatch_us(avg/max)={:.1f}/{:.1f} "
                     "event_lock_us(avg/max)={:.1f}/{:.1f} output_blocked={} output_peak_bytes={}",
                     seconds, metricRequests.exchange(0), metricRequestBytes.exchange(0),
                     readAvg, readMax, dispatchAvg, dispatchMax, lockAvg, lockMax,
                     metricOutputBlocked.exchange(0), metricOutputPeak.exchange(0));
                const auto ahb = renderer->TakeAHBMetrics();
                LOGI("X11AHBMetrics seconds={:.2f} dri3_received={} vk_import_new={} vk_cache_hit={} "
                     "scene_unchanged_ahb={} cache_entries={} vk_import_us(avg/max)={:.1f}/{:.1f}",
                     seconds, metricDri3Imports.exchange(0), ahb.vkImports, ahb.cacheHits,
                     ahb.unchangedDrawables, ahb.cacheEntries,
                     ahb.importNs / 1000.0 / std::max<u64>(1, ahb.vkImports), ahb.maxImportNs / 1000.0);
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
        try { renderer->PrepareSurfaceChange(); retire(renderer->Drain()); }
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


