#include "core/window_tree.h"

#include "core/pointer_sprite.h"
#include "core/client_session.h"

namespace ludashi::x11 {
namespace {

bool ValidGrabTime(u32 time, u32 last, u32 now) {
    return (!last || static_cast<s32>(time - last) >= 0) && static_cast<s32>(now - time) >= 0;
}

} // namespace

u32 WindowTree::GrabPointer(ClientSession* client, WindowNode* window, WindowNode* confined, PointerSprite* cursor,
                               u32 time, u32 mask, bool ownerEvents, u8 pointerMode, u8 keyboardMode) {
    if (grab_pointer_client && grab_pointer_client != client) return AlreadyGrabbed;
    if (grab_keyboard_client != client && grabFreeze[Keys].device[Pointer] == Freeze::Frozen)
        return GrabFrozen;
    if (!window->IsAllAncestorsMapped() || (confined && !confined->IsAllAncestorsMapped()))
        return GrabNotViewable;
    const u32 now = server.GetTimestamp(), requested = time ? time : now;
    if (time && !ValidGrabTime(requested, last_pointer_grab_time, now)) return GrabInvalidTime;
    auto selected = cursor ? std::static_pointer_cast<PointerSprite>(server.ShareResource(cursor->id)) : nullptr;
    const bool replacing = grab_pointer_client;
    grab_pointer_client = client;
    grab_pointer_window = window;
    grab_confine_window = confined;
    grab_cursor = std::move(selected);
    grab_event_mask = mask;
    grab_pointer_owner_events = ownerEvents;
    grab_pointer_passive = false;
    last_pointer_grab_time = requested;
    if (stepBothOwner == client) stepBothOwner = nullptr;
    SetGrabFreeze(Pointer, pointerMode, keyboardMode);
    UpdatePointer(NotifyGrab);
    if (replacing) DrainPendingInput();
    return GrabSuccess;
}

void WindowTree::UngrabPointer(const ClientSession* client, u32 time) {
    if (!grab_pointer_client || client != grab_pointer_client) return;
    const u32 now = server.GetTimestamp(), requested = time ? time : now;
    if (time && !ValidGrabTime(requested, last_pointer_grab_time, now)) return;
    RemovePointerGrab();
    DrainPendingInput();
}

void WindowTree::RemovePointerGrab() {
    SetGrabFreeze(Pointer, GrabModeAsync, GrabModeAsync);
    if (stepBothOwner == grab_pointer_client && stepBothOwner != grab_keyboard_client) stepBothOwner = nullptr;
    grab_pointer_client = nullptr;
    grab_pointer_window = nullptr;
    grab_confine_window = nullptr;
    grab_cursor.reset();
    grab_pointer_passive = false;
    UpdatePointer(NotifyUngrab);
}

u32 WindowTree::GrabKeyboard(ClientSession* client, WindowNode* window, u32 time, bool ownerEvents,
                                u8 pointerMode, u8 keyboardMode) {
    if (grab_keyboard_client && grab_keyboard_client != client) return AlreadyGrabbed;
    if (grab_pointer_client != client && grabFreeze[Pointer].device[Keys] == Freeze::Frozen)
        return GrabFrozen;
    if (!window->IsAllAncestorsMapped()) return GrabNotViewable;
    const u32 now = server.GetTimestamp(), requested = time ? time : now;
    if (time && !ValidGrabTime(requested, last_keyboard_grab_time, now)) return GrabInvalidTime;
    auto* previous = grab_keyboard_window ? grab_keyboard_window : focus_window;
    const bool replacing = grab_keyboard_window;
    grab_keyboard_client = client;
    grab_keyboard_window = window;
    grab_keyboard_owner_events = ownerEvents;
    last_keyboard_grab_time = requested;
    if (stepBothOwner == client) stepBothOwner = nullptr;
    SetGrabFreeze(Keys, pointerMode, keyboardMode);
    const auto [x, y] = GetPointerPosition();
    FocusInOutNotify(previous, window, GetPointerWindow(x, y), root_window.get(), NotifyGrab,
                     !replacing && previous == root_window.get(), false);
    if (replacing) DrainPendingInput();
    return GrabSuccess;
}

void WindowTree::UngrabKeyboard(const ClientSession* client, u32 time) {
    if (!grab_keyboard_client || client != grab_keyboard_client) return;
    const u32 now = server.GetTimestamp();
    if (time && !ValidGrabTime(time, last_keyboard_grab_time, now)) return;
    RemoveKeyboardGrab();
    DrainPendingInput();
}

void WindowTree::RemoveKeyboardGrab() {
    SetGrabFreeze(Keys, GrabModeAsync, GrabModeAsync);
    auto* previous = std::exchange(grab_keyboard_window, nullptr);
    if (stepBothOwner == grab_keyboard_client && stepBothOwner != grab_pointer_client) stepBothOwner = nullptr;
    grab_keyboard_client = nullptr;
    grab_keyboard_owner_events = false;
    const auto [x, y] = GetPointerPosition();
    FocusInOutNotify(previous, focus_window, GetPointerWindow(x, y), root_window.get(), NotifyUngrab,
                     false, focus_window == root_window.get());
}

void WindowTree::ReleaseClientGrabs(const ClientSession* client) {
    if (grab_keyboard_client == client) RemoveKeyboardGrab();
    if (grab_pointer_client == client) RemovePointerGrab();
    DrainPendingInput();
}

void WindowTree::AllowEvents(const ClientSession* client, u8 mode, u32 time) {
    const bool ownsPointer = grab_pointer_client == client;
    const bool ownsKeyboard = grab_keyboard_client == client;
    if (!ownsPointer && !ownsKeyboard) return;
    u32 last = ownsPointer ? last_pointer_grab_time : last_keyboard_grab_time;
    if (ownsKeyboard && static_cast<s32>(last_keyboard_grab_time - last) > 0)
        last = last_keyboard_grab_time;
    if (time && !ValidGrabTime(time, last, server.GetTimestamp())) return;

    const auto thaw = [&](Device device, Freeze next) {
        for (const Device grab : {Pointer, Keys}) {
            if (GrabOwner(grab) == client) grabFreeze[grab].device[device] = next;
        }
        if (GrabOwner(device) == client) replayEvents[device].reset();
    };
    if (mode == AsyncBoth || mode == SyncBoth) {
        if (!FrozenBy(Pointer, client) || !FrozenBy(Keys, client)) return;
        thaw(Pointer, Freeze::Running);
        thaw(Keys, Freeze::Running);
        stepBothOwner = mode == SyncBoth ? client : nullptr;
    } else {
        const Device device = mode <= ReplayPointer ? Pointer : Keys;
        if (!FrozenBy(device, client)) return;
        if (mode == ReplayPointer || mode == ReplayKeyboard) {
            if (GrabOwner(device) != client || !replayEvents[device]) return;
            const auto saved = *replayEvents[device];
            if (device == Pointer) RemovePointerGrab();
            else RemoveKeyboardGrab();
            // Reuse the original event coordinates, timestamp and pre-event state.
            // Passive grabs are not installed by this server.
            auto& queue = pendingInput[device];
            if (!queue) queue.emplace();
            queue->push_front(saved);
        } else {
            const bool step = mode == SyncPointer || mode == SyncKeyboard;
            if (step && GrabOwner(device) != client) return;
            if (stepBothOwner == client) stepBothOwner = nullptr;
            thaw(device, step ? Freeze::Step : Freeze::Running);
        }
    }
    DrainPendingInput();
}

void WindowTree::ReleaseWindowGrabs(const WindowNode* window) {
    const auto contains = [&](const WindowNode* target) {
        return target && (target == window || window->IsAncestorOf(target));
    };
    if (contains(grab_keyboard_window)) RemoveKeyboardGrab();
    if (contains(grab_pointer_window) || contains(grab_confine_window)) RemovePointerGrab();
    DrainPendingInput();
}

} // namespace ludashi::x11
