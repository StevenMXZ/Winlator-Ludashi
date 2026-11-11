#pragma once
#include <cstdint>

// UI percentages are preset IDs, not a percentage of the full-resolution image.
// Preserve upstream Fast (180) and Balanced (252), with one preset on each side.
constexpr uint32_t wfgFlowSide(uint32_t quality) {
    return quality <= 45 ? 128 : quality <= 60 ? 180 : quality <= 85 ? 252 : 360;
}
