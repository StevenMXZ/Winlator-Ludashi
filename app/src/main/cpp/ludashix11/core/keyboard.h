#pragma once

#include <unordered_set>
#include <vector>
#include "base/types.h"
#include "core/xkeycode.h"

namespace xorg {

class XServer;

class Keyboard {
public:
    Keyboard(XServer &server);

    ~Keyboard();

    u32 GetKeycodeMin() const { return keycode_min; }

    u32 GetKeycodeMax() const { return keycode_max; }

    u32 GetKeysymsPerKeycode() const { return keysyms_per_keycode; }

    u32 GetKeycodesCount() const { return keycodes_count; }

    const std::vector<u32>& GetKeysyms() const { return keysyms; }

    void SetKeyState(XKeycode keycode, bool down);

    u32 GetModifiersMask() const { return modifiers_mask; }

private:
    XServer& server;
    u32 keycode_min{};
    u32 keycode_max{};
    u32 keysyms_per_keycode{};
    u32 keycodes_count{};
    std::vector<u32> keysyms;
    std::unordered_set<XKeycode> pressed_keys;
    u32 modifiers_mask{};

    void MapKeycodeToKeysym();
};

}  // namespace xorg