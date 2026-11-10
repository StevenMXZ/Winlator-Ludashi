#pragma once

#include <array>
#include <thread>
#include "base/types.h"

namespace xorg {

class XServer;
class WindowManager;

class Mouse {
public:
    explicit Mouse(XServer& server);

    ~Mouse();

    void MoveBy(s16 dx, s16 dy);

    void MoveTo(s16 new_x, s16 new_y);

    void SetButton(u16 button, bool down);

    s16 GetX() const { return x; }

    s16 GetY() const { return y; }

    u16 GetButtons() const { return buttons; }

private:
    void FixPosition();

    XServer& server;
    u32 screen_width{};
    u32 screen_height{};
    s16 x{};
    s16 y{};
    u16 buttons{};
    u32 max_distance{};
    float damping{0.25f};
    bool stop_requested{};
    std::thread fix_thread{};
};

}  // namespace xorg