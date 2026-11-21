#pragma once

#include <cstdint>
#include <optional>

namespace ludashi::x11 {

class DisplayRuntime;

class PointerInput final {
public:
    explicit PointerInput(DisplayRuntime& display);
    void MoveBy(int16_t dx, int16_t dy);
    void MoveTo(int16_t x, int16_t y);
    void AnchorTo(int16_t x, int16_t y);
    void WarpTo(int16_t x, int16_t y);
    void SetRawInputEnabled(bool enabled);
    void SetButton(uint16_t button, bool down);

private:
    struct Point { int16_t x, y; };

    void UpdatePosition(int16_t nextX, int16_t nextY);
    void SetAnchor(int16_t nextX, int16_t nextY);

    DisplayRuntime& display;
    const int32_t width, height, overscroll;
    Point position{};
    std::optional<Point> rawAnchor;
    uint16_t buttons{};
    bool rawInput{true};
};

} // namespace ludashi::x11
