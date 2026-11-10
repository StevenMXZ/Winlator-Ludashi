#pragma once

#include "base/types.h"

struct Point {
    s16 x;
    s16 y;
};

struct PolySegment {
    Point p1;
    Point p2;
};

struct Rect {
    s32 left;
    s32 top;
    s32 right;
    s32 bottom;

    static constexpr bool Intersects(const Rect& a, const Rect& b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    bool Contains(const Rect& r) const {
        return left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right &&
               bottom >= r.bottom;
    }

    bool Contains(s32 x, s32 y) const {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }
};