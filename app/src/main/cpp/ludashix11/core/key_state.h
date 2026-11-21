#pragma once

#include <array>
#include <cstdint>

namespace ludashi::x11 {

class DisplayRuntime;
class WindowTree;

class KeyState final {
public:
    explicit KeyState(DisplayRuntime& display);
    static constexpr uint32_t GetKeycodeMin() { return 8; }
    static constexpr uint32_t GetKeycodeMax() { return 255; }
    static constexpr uint32_t GetKeysymsPerKeycode() { return 2; }
    static constexpr uint32_t GetKeycodesCount() { return 248; }

    const std::array<uint32_t, 496>& GetKeysyms() const;
    std::array<uint8_t, 32> GetKeymap() const;
    uint32_t GetModifiersMask() const { return modifiers; }
    void SetKeyState(uint32_t code, bool down);

private:
    friend class WindowTree;
    void ApplyKeyState(uint32_t code, bool down);
    DisplayRuntime& display;
    std::array<uint64_t, 4> physicalPressed{};
    std::array<uint64_t, 4> pressed{};
    uint32_t modifiers{};
};

} // namespace ludashi::x11
