#include <unistd.h>
#include "core/keyboard.h"
#include "core/window_manager.h"
#include "core/xkeycode.h"
#include "core/xserver.h"

namespace xorg {

Keyboard::Keyboard(XServer& server) : server(server) {
    keycode_min = 8;
    keycode_max = 255;
    keysyms_per_keycode = 2;
    keycodes_count = keycode_max - keycode_min + 1;
    keysyms.resize(keycodes_count * keysyms_per_keycode);
    MapKeycodeToKeysym();
}

Keyboard::~Keyboard() {}

void Keyboard::SetKeyState(XKeycode keycode, bool down) {
    auto lock = server.Lock();
    // Android's Keyboard adapter already resolves sticky keys to logical states.
    if (down) {
        if (pressed_keys.insert(keycode).second) {
            if (IsModifier(keycode)) modifiers_mask |= GetModifierFlag(keycode);
            server.GetWindowManager().OnKeyboardKeyChange(static_cast<u32>(keycode), true);
        }
    } else if (pressed_keys.erase(keycode)) {
        if (IsModifier(keycode)) modifiers_mask &= ~GetModifierFlag(keycode);
        server.GetWindowManager().OnKeyboardKeyChange(static_cast<u32>(keycode), false);
    }
}

void Keyboard::MapKeycodeToKeysym() {
    const auto map = [&](int keycode, int keysym1, int keysym2) {
        const auto index = keycode - keycode_min;
        keysyms[index * keysyms_per_keycode] = keysym1;
        keysyms[index * keysyms_per_keycode + 1] = keysym2;
    };
    map(+XKeycode::KEY_ESC, 65307, 0);
    map(+XKeycode::KEY_ENTER, 65293, 0);
    map(+XKeycode::KEY_RIGHT, 65363, 0);
    map(+XKeycode::KEY_UP, 65362, 0);
    map(+XKeycode::KEY_LEFT, 65361, 0);
    map(+XKeycode::KEY_DOWN, 65364, 0);
    map(+XKeycode::KEY_DEL, 65535, 0);
    map(+XKeycode::KEY_BKSP, 65288, 0);
    map(+XKeycode::KEY_INSERT, 65379, 0);
    map(+XKeycode::KEY_PRIOR, 65365, 0);
    map(+XKeycode::KEY_NEXT, 65366, 0);
    map(+XKeycode::KEY_HOME, 65360, 0);
    map(+XKeycode::KEY_END, 65367, 0);
    map(+XKeycode::KEY_SHIFT_L, 65505, 0);
    map(+XKeycode::KEY_SHIFT_R, 65506, 0);
    map(+XKeycode::KEY_CTRL_L, 65507, 0);
    map(+XKeycode::KEY_CTRL_R, 65508, 0);
    map(+XKeycode::KEY_ALT_L, 65513, 0);
    map(+XKeycode::KEY_ALT_R, 65514, 0);
    map(+XKeycode::KEY_TAB, 65289, 0);
    map(+XKeycode::KEY_SPACE, 32, 32);
    map(+XKeycode::KEY_A, 97, 65);
    map(+XKeycode::KEY_B, 98, 66);
    map(+XKeycode::KEY_C, 99, 67);
    map(+XKeycode::KEY_D, 100, 68);
    map(+XKeycode::KEY_E, 101, 69);
    map(+XKeycode::KEY_F, 102, 70);
    map(+XKeycode::KEY_G, 103, 71);
    map(+XKeycode::KEY_H, 104, 72);
    map(+XKeycode::KEY_I, 105, 73);
    map(+XKeycode::KEY_J, 106, 74);
    map(+XKeycode::KEY_K, 107, 75);
    map(+XKeycode::KEY_L, 108, 76);
    map(+XKeycode::KEY_M, 109, 77);
    map(+XKeycode::KEY_N, 110, 78);
    map(+XKeycode::KEY_O, 111, 79);
    map(+XKeycode::KEY_P, 112, 80);
    map(+XKeycode::KEY_Q, 113, 81);
    map(+XKeycode::KEY_R, 114, 82);
    map(+XKeycode::KEY_S, 115, 83);
    map(+XKeycode::KEY_T, 116, 84);
    map(+XKeycode::KEY_U, 117, 85);
    map(+XKeycode::KEY_V, 118, 86);
    map(+XKeycode::KEY_W, 119, 87);
    map(+XKeycode::KEY_X, 120, 88);
    map(+XKeycode::KEY_Y, 121, 89);
    map(+XKeycode::KEY_Z, 122, 90);
    map(+XKeycode::KEY_1, 49, 33);
    map(+XKeycode::KEY_2, 50, 64);
    map(+XKeycode::KEY_3, 51, 35);
    map(+XKeycode::KEY_4, 52, 36);
    map(+XKeycode::KEY_5, 53, 37);
    map(+XKeycode::KEY_6, 54, 94);
    map(+XKeycode::KEY_7, 55, 38);
    map(+XKeycode::KEY_8, 56, 42);
    map(+XKeycode::KEY_9, 57, 40);
    map(+XKeycode::KEY_0, 48, 41);
    map(+XKeycode::KEY_COMMA, 44, 60);
    map(+XKeycode::KEY_PERIOD, 46, 62);
    map(+XKeycode::KEY_SEMICOLON, 59, 58);
    map(+XKeycode::KEY_APOSTROPHE, 39, 34);
    map(+XKeycode::KEY_BRACKET_LEFT, 91, 123);
    map(+XKeycode::KEY_BRACKET_RIGHT, 93, 125);
    map(+XKeycode::KEY_GRAVE, 96, 126);
    map(+XKeycode::KEY_MINUS, 45, 95);
    map(+XKeycode::KEY_EQUAL, 61, 43);
    map(+XKeycode::KEY_SLASH, 47, 63);
    map(+XKeycode::KEY_BACKSLASH, 92, 124);
    map(+XKeycode::KEY_KP_DIVIDE, 65455, 65455);
    map(+XKeycode::KEY_KP_MULTIPLY, 65450, 65450);
    map(+XKeycode::KEY_KP_SUBTRACT, 65453, 65453);
    map(+XKeycode::KEY_KP_ADD, 65451, 65451);
    map(+XKeycode::KEY_KP_0, 65456, 65438);
    map(+XKeycode::KEY_KP_1, 65457, 65436);
    map(+XKeycode::KEY_KP_2, 65458, 65433);
    map(+XKeycode::KEY_KP_3, 65459, 65459);
    map(+XKeycode::KEY_KP_4, 65460, 65430);
    map(+XKeycode::KEY_KP_5, 65461, 65461);
    map(+XKeycode::KEY_KP_6, 65462, 65432);
    map(+XKeycode::KEY_KP_7, 65463, 65429);
    map(+XKeycode::KEY_KP_8, 65464, 65431);
    map(+XKeycode::KEY_KP_9, 65465, 65465);
    map(+XKeycode::KEY_KP_DEL, 65439, 0);
    map(+XKeycode::KEY_F1, 65470, 0);
    map(+XKeycode::KEY_F2, 65471, 0);
    map(+XKeycode::KEY_F3, 65472, 0);
    map(+XKeycode::KEY_F4, 65473, 0);
    map(+XKeycode::KEY_F5, 65474, 0);
    map(+XKeycode::KEY_F6, 65475, 0);
    map(+XKeycode::KEY_F7, 65476, 0);
    map(+XKeycode::KEY_F8, 65477, 0);
    map(+XKeycode::KEY_F9, 65478, 0);
    map(+XKeycode::KEY_F10, 65479, 0);
    map(+XKeycode::KEY_F11, 65480, 0);
    map(+XKeycode::KEY_F12, 65481, 0);
}

}  // namespace xorg
