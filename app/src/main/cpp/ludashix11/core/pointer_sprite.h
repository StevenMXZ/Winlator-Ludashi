#pragma once

#include <cstdint>
#include <vector>
#include "core/protocol_object.h"

namespace ludashi::x11 {

class NativeDrawable;
class NativeScreen;

class PointerSprite final : public ProtocolObject {
public:
    PointerSprite(NativeScreen& display, uint32_t id, int32_t hotX, int32_t hotY,
           NativeDrawable& source, NativeDrawable* mask, uint32_t foreground, uint32_t background);
    PointerSprite(NativeScreen& display, uint32_t id, uint32_t sourceGlyph, uint32_t maskGlyph,
           uint32_t foreground, uint32_t background, bool hasMask = true);
    PointerSprite(NativeScreen& display, uint32_t id, uint16_t hotX, uint16_t hotY,
           NativeDrawable& source, bool premultiplied = true);

    void Recolor(uint32_t foreground, uint32_t background);
    const sp<NativeDrawable>& Image() const { return image; }
    int32_t GetHotspotX() const { return hotX; }
    int32_t GetHotspotY() const { return hotY; }

private:
    enum class PixelRole : uint8_t { Transparent, Background, Foreground };

    NativeScreen& display;
    sp<NativeDrawable> image;
    std::vector<PixelRole> shape;
    int32_t hotX{};
    int32_t hotY{};
    uint16_t width{};
    uint16_t height{};
    uint32_t foreground{};
    uint32_t background{};
};

} // namespace ludashi::x11
