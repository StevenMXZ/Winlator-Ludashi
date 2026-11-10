#pragma once
#include <algorithm>
#include <cmath>

// Presentation-space cursor-following pan. X11 screen geometry and RandR stay
// untouched. Behavior mirrors Ludashi's magnifier safe-zone model.
struct NativeMagnifierPan {
    float x{}, y{}, previousZoom{1.f};
    bool initialized{};

    void Reset() {
        x = y = 0.f;
        previousZoom = 1.f;
        initialized = false;
    }
    void Update(float cursorX, float cursorY, float width, float height, float zoom) {
        if (zoom <= 1.f || width <= 0.f || height <= 0.f) {
            Reset();
            return;
        }
        if (zoom != previousZoom) initialized = false;
        previousZoom = zoom;
        cursorX = std::clamp(cursorX, 0.f, width);
        cursorY = std::clamp(cursorY, 0.f, height);
        const float maxX = width * (zoom - 1.f);
        const float maxY = height * (zoom - 1.f);
        if (!initialized) {
            x = std::clamp(cursorX * zoom - width * .5f, 0.f, maxX);
            y = std::clamp(cursorY * zoom - height * .5f, 0.f, maxY);
            initialized = true;
        }
        const float visibleW = width / zoom, visibleH = height / zoom;
        const float marginX = visibleW * .2f, marginY = visibleH * .2f;
        const float left = x / zoom, top = y / zoom;
        if (cursorX < left + marginX) x = (cursorX - marginX) * zoom;
        else if (cursorX > left + visibleW - marginX)
            x = (cursorX - visibleW + marginX) * zoom;
        if (cursorY < top + marginY) y = (cursorY - marginY) * zoom;
        else if (cursorY > top + visibleH - marginY)
            y = (cursorY - visibleH + marginY) * zoom;
        x = std::clamp(x, 0.f, maxX);
        y = std::clamp(y, 0.f, maxY);
    }
};
