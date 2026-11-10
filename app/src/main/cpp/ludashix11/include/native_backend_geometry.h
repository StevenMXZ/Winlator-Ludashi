#pragma once
#include "native_magnifier.h"

namespace xorg {
struct NativeBackendGeometry {
    float sx{}, sy{}, ox{}, oy{};
    int left{}, top{}, right{}, bottom{};
    void Update(int outerW, int outerH, int innerW, int innerH, bool stretch, float zoom, int cursorX,
                int cursorY, NativeMagnifierPan &pan) {
        const float fit = std::min(float(outerW) / innerW, float(outerH) / innerH);
        const float baseX = stretch ? float(outerW) / innerW : fit;
        const float baseY = stretch ? float(outerH) / innerH : fit;
        left = stretch ? 0 : int((outerW - innerW * fit) * .5f);
        top = stretch ? 0 : int((outerH - innerH * fit) * .5f);
        right = stretch ? outerW : outerW - left;
        bottom = stretch ? outerH : outerH - top;
        pan.Update(cursorX, cursorY, innerW, innerH, zoom);
        sx = baseX * zoom;
        sy = baseY * zoom;
        ox = left - pan.x * baseX;
        oy = top - pan.y * baseY;
    }
};
} // namespace xorg
