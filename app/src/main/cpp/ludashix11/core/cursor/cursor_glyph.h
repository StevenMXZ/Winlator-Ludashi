#pragma once

#include <cstdint>
#include <span>

namespace ludashi::x11 {

struct CursorGlyph {
    std::span<const uint8_t> bits;
    uint16_t width;
    uint16_t height;
    int16_t left;
    int16_t top;

    constexpr bool Pixel(int32_t x, int32_t y) const {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        const std::size_t rowBytes = (width + 7) / 8;
        return (bits[std::size_t(y) * rowBytes + x / 8] & (0x80u >> (x % 8))) != 0;
    }
};

const CursorGlyph& GetCursorGlyph(uint32_t code);

} // namespace ludashi::x11
