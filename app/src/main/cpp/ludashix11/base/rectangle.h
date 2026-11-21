#pragma once

#include <algorithm>
#include <cstdint>

namespace ludashi::x11 {

// Pixel bounds use an inclusive origin and exclusive right/bottom edges.
struct Rect {
    std::int32_t left, top, right, bottom;

    constexpr bool Empty() const {
        return left >= right || top >= bottom;
    }

    constexpr Rect Intersection(const Rect& other) const {
        return {std::max(left, other.left), std::max(top, other.top),
                std::min(right, other.right), std::min(bottom, other.bottom)};
    }

    constexpr bool Overlaps(const Rect& other) const {
        return !Intersection(other).Empty();
    }

    constexpr bool Contains(const Rect& other) const {
        return !Empty() && other.left >= left && other.top >= top &&
               other.right <= right && other.bottom <= bottom;
    }

    constexpr bool Contains(std::int32_t x, std::int32_t y) const {
        return x >= left && x < right && y >= top && y < bottom;
    }
};

} // namespace ludashi::x11
