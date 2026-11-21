#pragma once

#include <cstdint>

namespace ludashi::x11 {

// X11 setup and RandR report the same virtual monitor at four pixels per millimetre.
struct DisplayDimensions {
    std::uint16_t width;
    std::uint16_t height;
    std::uint16_t widthMillimetres;
    std::uint16_t heightMillimetres;

    constexpr DisplayDimensions(std::uint16_t width, std::uint16_t height)
        : width(width), height(height),
          widthMillimetres(width / 4), heightMillimetres(height / 4) {}
};

} // namespace ludashi::x11
