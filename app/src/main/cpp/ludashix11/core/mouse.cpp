#include "core/mouse.h"
#include "core/window_manager.h"
#include "core/xserver.h"

namespace xorg {

Mouse::Mouse(XServer& server)
        : server(server)
        , screen_width(server.GetScreenInfo().width)
        , screen_height(server.GetScreenInfo().height) {
    max_distance = screen_width * 0.05f;

}

Mouse::~Mouse() = default;

void Mouse::MoveBy(s16 dx, s16 dy) {
    auto lock = server.Lock();
    if (rawEnabled) server.SendRawMotion(dx, dy);
    absoluteAnchored = false;
    WarpTo(x + dx, y + dy);
}

void Mouse::MoveTo(s16 new_x, s16 new_y) {
    auto lock = server.Lock();
    if (absoluteAnchored && rawEnabled) server.SendRawMotion(new_x - absoluteX, new_y - absoluteY);
    AnchorTo(new_x, new_y);
}

void Mouse::AnchorTo(s16 new_x, s16 new_y) {
    auto lock = server.Lock();
    absoluteAnchored = true; absoluteX = new_x; absoluteY = new_y;
    WarpTo(new_x, new_y);
}

void Mouse::WarpTo(s16 new_x, s16 new_y) {
    auto lock = server.Lock();
    if (x != new_x || y != new_y) {
        x = new_x;
        y = new_y;
        auto lock = server.Lock();
        server.GetWindowManager().OnPointerMove(x, y);
    }
}

void Mouse::SetButton(u16 button, bool down) {
    if (button < 1 || button > 7) return;
    auto guard = server.Lock();
    const u32 mask = 0x80 << button;
    if (down) {
        if (buttons & mask) {
            return;
        }
        buttons |= mask;
    } else {
        if (!(buttons & mask)) {
            return;
        }
        buttons &= ~mask;
    }
    auto lock = server.Lock();
    if (rawEnabled) server.SendRawButton(button, down);
    server.GetWindowManager().OnPointerButtonChange(button, down);
}

void Mouse::FixPosition() {
    const s16 fix_x = std::clamp<s16>(
            x, -static_cast<s16>(max_distance), static_cast<s16>(screen_width + max_distance));
    const s16 fix_y = std::clamp<s16>(
            y, -static_cast<s16>(max_distance), static_cast<s16>(screen_height + max_distance));
    s16 new_x = x, new_y = y;
    if (fix_x < 0) {
        new_x = static_cast<s16>(std::ceil(fix_x * damping));
    } else if (fix_x >= static_cast<s32>(screen_width)) {
        new_x = static_cast<s16>(std::floor(screen_width + (fix_x - screen_width) * damping));
    }
    if (fix_y < 0) {
        new_y = static_cast<s16>(std::ceil(fix_y * damping));
    } else if (fix_y >= static_cast<s32>(screen_height)) {
        new_y = static_cast<s16>(std::floor(screen_height + (fix_y - screen_height) * damping));
    }
    if (new_x != x || new_y != y) {
        WarpTo(new_x, new_y);
    }
}

}  // namespace xorg
