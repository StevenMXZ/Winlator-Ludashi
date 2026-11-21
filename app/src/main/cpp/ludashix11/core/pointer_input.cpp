#include "core/pointer_input.h"

#include <algorithm>
#include "core/window_tree.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

// Preserve the native adapter's edge recovery while using integer arithmetic.
// C++ integer division truncates toward zero, matching ceil for negatives.
int32_t RecoverCoordinate(int32_t position, int32_t extent, int32_t overscroll) {
    if (position < 0) return std::max(position, -overscroll) / 4;
    if (position >= extent) return extent + std::min(position - extent, overscroll) / 4;
    return position;
}

} // namespace

PointerInput::PointerInput(DisplayRuntime& display)
    : display(display), width(display.Dimensions().width), height(display.Dimensions().height),
      overscroll(width / 20) {}

void PointerInput::UpdatePosition(int16_t nextX, int16_t nextY) {
    if (nextX == position.x && nextY == position.y) return;
    position = {nextX, nextY};
    display.Windows().OnPointerMove(nextX, nextY);
}

void PointerInput::SetAnchor(int16_t nextX, int16_t nextY) {
    rawAnchor = Point{nextX, nextY};
    UpdatePosition(nextX, nextY);
}

void PointerInput::SetRawInputEnabled(bool enabled) {
    auto guard = display.Lock();
    rawInput = enabled;
    rawAnchor.reset();
}

void PointerInput::WarpTo(int16_t nextX, int16_t nextY) {
    auto guard = display.Lock();
    UpdatePosition(nextX, nextY);
}

void PointerInput::AnchorTo(int16_t nextX, int16_t nextY) {
    auto guard = display.Lock();
    SetAnchor(nextX, nextY);
}

void PointerInput::MoveTo(int16_t nextX, int16_t nextY) {
    auto guard = display.Lock();
    if (rawInput && rawAnchor)
        display.SendRawMotion(int32_t{nextX} - rawAnchor->x, int32_t{nextY} - rawAnchor->y);
    SetAnchor(nextX, nextY);
}

void PointerInput::MoveBy(int16_t dx, int16_t dy) {
    auto guard = display.Lock();
    if (rawInput) display.SendRawMotion(dx, dy);
    rawAnchor.reset();
    const int32_t nextX = RecoverCoordinate(position.x, width, overscroll) + dx;
    const int32_t nextY = RecoverCoordinate(position.y, height, overscroll) + dy;
    UpdatePosition(static_cast<int16_t>(nextX), static_cast<int16_t>(nextY));
}

void PointerInput::SetButton(uint16_t button, bool down) {
    if (button == 0 || button > 7) return;
    auto guard = display.Lock();
    const uint16_t flag = uint16_t{1} << (button + 7);
    if (bool(buttons & flag) == down) return;
    buttons ^= flag;
    if (rawInput) display.SendRawButton(button, down);
    display.Windows().OnPointerButtonChange(button, down);
}

} // namespace ludashi::x11
