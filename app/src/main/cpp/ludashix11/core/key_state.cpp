#include "core/key_state.h"

#include <bit>
#include <iterator>
#include <linux/input-event-codes.h>
#include <string_view>
#include "core/window_tree.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

constexpr uint32_t WireCode(uint32_t evdev) { return evdev + 8; }

constexpr auto keymap = [] {
    std::array<uint32_t, 248 * 2> table{};
    const auto key = [&](uint32_t evdev, uint32_t first, uint32_t second = 0) {
        table[2 * evdev] = first;
        table[2 * evdev + 1] = second;
    };
    const auto letters = [&](uint32_t start, std::string_view row) {
        for (size_t index = 0; index < row.size(); ++index)
            key(start + index, row[index], row[index] - 'a' + 'A');
    };
    letters(KEY_Q, "qwertyuiop");
    letters(KEY_A, "asdfghjkl");
    letters(KEY_Z, "zxcvbnm");
    constexpr std::string_view numbers = "1234567890";
    constexpr std::string_view shifted = "!@#$%^&*()";
    for (size_t index = 0; index < numbers.size(); ++index)
        key(KEY_1 + index, numbers[index], shifted[index]);

    key(KEY_SPACE, ' ', ' ');
    key(KEY_COMMA, ',', '<');
    key(KEY_DOT, '.', '>');
    key(KEY_SEMICOLON, ';', ':');
    key(KEY_APOSTROPHE, '\'', '"');
    key(KEY_LEFTBRACE, '[', '{');
    key(KEY_RIGHTBRACE, ']', '}');
    key(KEY_GRAVE, '`', '~');
    key(KEY_MINUS, '-', '_');
    key(KEY_EQUAL, '=', '+');
    key(KEY_SLASH, '/', '?');
    key(KEY_BACKSLASH, '\\', '|');

    // Non-printable symbols use the X.Org keysym values.
    key(KEY_ESC, 0xff1b);
    key(KEY_ENTER, 0xff0d);
    key(KEY_BACKSPACE, 0xff08);
    key(KEY_TAB, 0xff09);
    key(KEY_HOME, 0xff50);
    key(KEY_LEFT, 0xff51);
    key(KEY_UP, 0xff52);
    key(KEY_RIGHT, 0xff53);
    key(KEY_DOWN, 0xff54);
    key(KEY_PAGEUP, 0xff55);
    key(KEY_PAGEDOWN, 0xff56);
    key(KEY_END, 0xff57);
    key(KEY_SYSRQ, 0xff61);
    key(KEY_INSERT, 0xff63);
    key(KEY_DELETE, 0xffff);
    key(KEY_LEFTSHIFT, 0xffe1);
    key(KEY_RIGHTSHIFT, 0xffe2);
    key(KEY_LEFTCTRL, 0xffe3);
    key(KEY_RIGHTCTRL, 0xffe4);
    key(KEY_CAPSLOCK, 0xffe5);
    key(KEY_LEFTALT, 0xffe9);
    key(KEY_RIGHTALT, 0xffea);
    key(KEY_NUMLOCK, 0xff7f);

    key(KEY_KPENTER, 0xff8d);
    key(KEY_KPDOT, 0xff9f);
    key(KEY_KPASTERISK, 0xffaa, 0xffaa);
    key(KEY_KPPLUS, 0xffab, 0xffab);
    key(KEY_KPMINUS, 0xffad, 0xffad);
    key(KEY_KPSLASH, 0xffaf, 0xffaf);
    constexpr uint16_t keypad[] = {KEY_KP0, KEY_KP1, KEY_KP2, KEY_KP3, KEY_KP4,
                                   KEY_KP5, KEY_KP6, KEY_KP7, KEY_KP8, KEY_KP9};
    constexpr uint16_t keypadShift[] = {0xff9e, 0xff9c, 0xff99, 0xffb3, 0xff96,
                                        0xffb5, 0xff98, 0xff95, 0xff97, 0xffb9};
    for (size_t index = 0; index < std::size(keypad); ++index)
        key(keypad[index], 0xffb0 + index, keypadShift[index]);

    for (uint32_t index = 0; index < 10; ++index) key(KEY_F1 + index, 0xffbe + index);
    key(KEY_F11, 0xffc8);
    key(KEY_F12, 0xffc9);
    return table;
}();

constexpr uint16_t modifierKeys[5][2] = {
    {WireCode(KEY_LEFTSHIFT), WireCode(KEY_RIGHTSHIFT)},
    {WireCode(KEY_CAPSLOCK), WireCode(KEY_CAPSLOCK)},
    {WireCode(KEY_LEFTCTRL), WireCode(KEY_RIGHTCTRL)},
    {WireCode(KEY_LEFTALT), WireCode(KEY_RIGHTALT)},
    {WireCode(KEY_NUMLOCK), WireCode(KEY_NUMLOCK)},
};

constexpr auto modifierMasks = [] {
    std::array<uint8_t, 256> masks{};
    for (size_t index = 0; index < std::size(modifierKeys); ++index)
        for (auto code : modifierKeys[index]) masks[code] = 1u << index;
    return masks;
}();

constexpr uint64_t KeyBit(uint32_t code) { return uint64_t{1} << (code % 64); }

bool Pressed(const std::array<uint64_t, 4>& keys, uint32_t code) {
    return (keys[code / 64] & KeyBit(code)) != 0;
}

void UpdatePressed(std::array<uint64_t, 4>& keys, uint32_t code, bool down) {
    const auto bit = KeyBit(code);
    auto& word = keys[code / 64];
    word = down ? word | bit : word & ~bit;
}

} // namespace

KeyState::KeyState(DisplayRuntime& display) : display(display) {}

const std::array<uint32_t, 496>& KeyState::GetKeysyms() const {
    return keymap;
}

std::array<uint8_t, 32> KeyState::GetKeymap() const {
    if constexpr (std::endian::native == std::endian::little)
        return std::bit_cast<std::array<uint8_t, 32>>(pressed);

    std::array<uint8_t, 32> wire{};
    for (size_t byte = 0; byte < wire.size(); ++byte)
        wire[byte] = pressed[byte / 8] >> (8 * (byte % 8));
    return wire;
}

void KeyState::SetKeyState(uint32_t index, bool down) {
    if (index < GetKeycodeMin() || index > GetKeycodeMax()) return;
    auto guard = display.Lock();
    auto& physical = physicalPressed[index / 64];
    const auto bit = KeyBit(index);
    if (((physical & bit) != 0) == down) return;
    physical ^= bit;
    // Java resolves sticky modifiers before injecting logical key states.
    auto& windows = display.Windows();
    if (windows.KeyboardInputScheduled() || modifierMasks[index]) {
        windows.OnKeyboardKeyChange(index, down);
    } else {
        // Ordinary keys do not change the event's modifier state.
        UpdatePressed(pressed, index, down);
        windows.DeliverCurrentKey(index, down);
    }
}

void KeyState::ApplyKeyState(uint32_t code, bool down) {
    UpdatePressed(pressed, code, down);
    const auto flag = modifierMasks[code];
    if (flag) {
        const auto& pair = modifierKeys[std::countr_zero(flag)];
        const bool active = Pressed(pressed, pair[0]) || Pressed(pressed, pair[1]);
        modifiers = (modifiers & ~flag) | (active ? flag : 0);
    }
}

} // namespace ludashi::x11
