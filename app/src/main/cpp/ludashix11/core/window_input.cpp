#include "native_screen.h"
#include "core/window_tree.h"

#include <algorithm>
#include "core/pointer_sprite.h"
#include "core/key_state.h"
#include "core/client_session.h"

namespace ludashi::x11 {

std::pair<s32, s32> WindowTree::GetPointerPosition() const {
    s32 x = pointerX, y = pointerY;
    if (grab_confine_window) {
        const auto& area = grab_confine_window->ContentBounds();
        x = std::clamp(x, area.left, area.right - 1);
        y = std::clamp(y, area.top, area.bottom - 1);
    }
    return {x, y};
}

WindowNode* WindowTree::GetPointerWindow(s32 x, s32 y) const {
    if (root_window->GetChildren().empty()) return root_window.get();
    return root_window->WindowAtPoint(std::clamp<s32>(x, 0, root_window->GetWidth() - 1),
                                      std::clamp<s32>(y, 0, root_window->GetHeight() - 1));
}

void WindowTree::UpdatePointer(u32 mode) {
    const auto [x, y] = GetPointerPosition();
    UpdatePointerPosition(x, y, mode, false);
}

PointerSprite* WindowTree::UpdatePointerPosition(s32 x, s32 y, u32 mode, bool notify) {
    if (grab_confine_window) {
        const auto& area = grab_confine_window->ContentBounds();
        x = std::clamp(x, area.left, area.right - 1);
        y = std::clamp(y, area.top, area.bottom - 1);
    }
    // Reuse a single tree lookup for event targeting and inherited cursor selection.
    auto* hit = (!grab_pointer_window || !grab_cursor) ? GetPointerWindow(x, y) : nullptr;
    auto* target = grab_pointer_window ? grab_pointer_window : hit;
    PointerSprite* cursor = grab_cursor.get();
    if (!cursor) {
        auto* owner = hit;
        if (grab_pointer_window && hit != grab_pointer_window && !grab_pointer_window->IsAncestorOf(hit))
            owner = grab_pointer_window;
        cursor = owner->GetCursor();
    }
    GetScreen().UpdateCursor(cursor->Image(), x - cursor->GetHotspotX(), y - cursor->GetHotspotY());
    if (target != motion_window) {
        motion_window->LeaveEnterNotify(x, y, target, mode);
        motion_window = target;
    } else if (notify && grab_pointer_window) {
        target->GrabPointerMoveNotify(x, y, GetButtonMasks(), grab_pointer_client,
                                      grab_event_mask, grab_pointer_owner_events);
    } else if (notify) target->PointerMotionNotify(x, y, GetButtonMasks(), nullptr);
    return cursor;
}

ClientSession* WindowTree::DeliverPointerButton(u16 button, bool down, s32 x, s32 y) {
    ClientSession* reported = nullptr;
    if (grab_pointer_window) {
        if (grab_pointer_window->GrabButtonNotify(down, x, y, button, grab_pointer_client,
                                                 grab_event_mask, grab_pointer_owner_events))
            reported = grab_pointer_client;
    } else {
        ClientSession* recipient = nullptr;
        auto* target = GetPointerWindow(x, y)->ButtonNotify(down, x, y, button, nullptr, &recipient);
        if (down && target && recipient) {
            const u32 mask = target->InterestOf(recipient);
            if (GrabPointer(recipient, target, nullptr, nullptr, CurrentTime, mask,
                             bool(mask & OwnerGrabButtonMask)) == GrabSuccess)
                grab_pointer_passive = true;
        }
    }
    if (!down && grab_pointer_passive && !pointerButtons) RemovePointerGrab();
    return reported;
}

void WindowTree::OnPointerMove(s32 x, s32 y) {
    SubmitInput({.kind = InputKind::Move, .x = x, .y = y});
}
void WindowTree::OnPointerButtonChange(u16 button, bool down) {
    SubmitInput({.kind = InputKind::Button, .code = button, .down = down});
}

void WindowTree::OnKeyboardKeyChange(u32 keycode, bool down) {
    SubmitInput({.kind = InputKind::Key, .code = static_cast<u16>(keycode), .down = down});
}

void WindowTree::DeliverCurrentKey(u16 keycode, bool down) {
    const auto [x, y] = GetPointerPosition();
    DeliverKey(keycode, down, x, y);
}

ClientSession* WindowTree::DeliverKey(u16 keycode, bool down, s32 x, s32 y) {
    if (!focus_window && !grab_keyboard_window) return nullptr;
    WindowNode* start = nullptr;
    if (focus_window && (!grab_keyboard_window || grab_keyboard_owner_events)) {
        auto* hit = GetPointerWindow(x, y);
        start = (hit == focus_window || focus_window->IsAncestorOf(hit)) ? hit : focus_window;
    }
    if (grab_keyboard_window) {
        grab_keyboard_window->GrabKeyNotify(down, x, y, keycode, grab_keyboard_client, start, focus_window);
        return grab_keyboard_client;
    } else if (start) start->KeyNotify(down, x, y, keycode, nullptr, focus_window);
    return nullptr;
}

u32 WindowTree::GetButtonMasks() const { return pointerButtons | server.Keys().GetModifiersMask(); }
u32 WindowTree::GetInputTime() const { return eventTime ? eventTime : server.GetTimestamp(); }

} // namespace ludashi::x11
