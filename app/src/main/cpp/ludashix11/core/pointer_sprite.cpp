#include "core/pointer_sprite.h"

#include <algorithm>
#include "core/cursor/cursor_glyph.h"
#include "native_drawable.h"
#include "native_screen.h"
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {

uint32_t StraightAlpha(uint32_t pixel) {
    const uint32_t alpha = pixel >> 24;
    if (alpha == 0) return 0;
    if (alpha == 255) return pixel;
    const auto channel = [alpha](uint32_t value) {
        return std::min(255u, (value * 255 + alpha / 2) / alpha);
    };
    return (alpha << 24) | (channel((pixel >> 16) & 255) << 16) |
           (channel((pixel >> 8) & 255) << 8) | channel(pixel & 255);
}

} // namespace

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, int32_t hotX, int32_t hotY,
               NativeDrawable& source, NativeDrawable* mask, uint32_t foreground, uint32_t background)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display), hotX(hotX), hotY(hotY),
      width(source.GetWidth()), height(source.GetHeight()) {
    if (source.GetDepth() != 1 || hotX < 0 || hotY < 0 || hotX >= width || hotY >= height)
        throw BadMatchError();
    if (mask && (mask->GetDepth() != 1 || mask->GetWidth() != width || mask->GetHeight() != height))
        throw BadMatchError();

    const auto* sourceBits = static_cast<const uint32_t*>(source.Map());
    const auto* maskBits = mask ? static_cast<const uint32_t*>(mask->Map()) : nullptr;
    const size_t sourceStride = source.GetStride() / sizeof(uint32_t);
    const size_t maskStride = mask ? mask->GetStride() / sizeof(uint32_t) : 0;
    shape.resize(size_t(width) * height);
    for (size_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width; ++x) {
            auto& role = shape[y * width + x];
            if (maskBits && !(maskBits[y * maskStride + x] & 1)) {
                role = PixelRole::Transparent;
            } else {
                role = (sourceBits[y * sourceStride + x] & 1) ? PixelRole::Foreground : PixelRole::Background;
            }
        }
    }
    Recolor(foreground, background);
}

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, uint32_t sourceGlyph, uint32_t maskGlyph,
               uint32_t foreground, uint32_t background, bool hasMask)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display) {
    const auto& source = GetCursorGlyph(sourceGlyph);
    const auto* mask = hasMask ? &GetCursorGlyph(maskGlyph) : nullptr;
    int32_t left = std::min<int32_t>(source.left, 0);
    int32_t top = std::min<int32_t>(source.top, 0);
    int32_t right = std::max<int32_t>(source.left + source.width, 1);
    int32_t bottom = std::max<int32_t>(source.top + source.height, 1);
    if (mask) {
        left = std::min<int32_t>(left, mask->left);
        top = std::min<int32_t>(top, mask->top);
        right = std::max<int32_t>(right, mask->left + mask->width);
        bottom = std::max<int32_t>(bottom, mask->top + mask->height);
    }
    hotX = -left;
    hotY = -top;
    width = right - left;
    height = bottom - top;
    shape.resize(size_t(width) * height);
    for (int32_t y = 0; y < height; ++y) {
        for (int32_t x = 0; x < width; ++x) {
            auto& role = shape[size_t(y) * width + x];
            if (mask && !mask->Pixel(left + x - mask->left, top + y - mask->top)) {
                role = PixelRole::Transparent;
            } else {
                role = source.Pixel(left + x - source.left, top + y - source.top)
                    ? PixelRole::Foreground : PixelRole::Background;
            }
        }
    }
    Recolor(foreground, background);
}

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, uint16_t hotX, uint16_t hotY,
               NativeDrawable& source, bool premultiplied)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display), hotX(hotX), hotY(hotY),
      width(source.GetWidth()), height(source.GetHeight()) {
    if (!width || !height || hotX >= width || hotY >= height) throw BadMatchError();
    auto converted = display.CreateDrawable(width, height, 32);
    auto* pixels = static_cast<uint32_t*>(converted->Map());
    const auto stride = converted->GetStride() / sizeof(uint32_t);
    source.ReadPixels(reinterpret_cast<uint8_t*>(pixels), 0, 0, width, height, stride);
    if (premultiplied) {
        for (size_t y = 0; y < height; ++y)
            for (size_t x = 0; x < width; ++x)
                pixels[y * stride + x] = StraightAlpha(pixels[y * stride + x]);
    }
    image = std::move(converted);
}

void PointerSprite::Recolor(uint32_t nextForeground, uint32_t nextBackground) {
    if (shape.empty()) return;
    nextForeground |= 0xff000000;
    nextBackground |= 0xff000000;
    if (image && foreground == nextForeground && background == nextBackground) return;

    // Publish a fresh drawable after conversion succeeds. Render snapshots
    // can keep reading the old colors while this cursor is being recolored.
    auto colored = display.CreateDrawable(width, height, 32);
    auto* pixels = static_cast<uint32_t*>(colored->Map());
    const size_t stride = colored->GetStride() / sizeof(uint32_t);
    const uint32_t colors[] = {0, nextBackground, nextForeground};
    for (size_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width; ++x) {
            pixels[y * stride + x] = colors[static_cast<uint8_t>(shape[y * width + x])];
        }
    }
    image = std::move(colored);
    foreground = nextForeground;
    background = nextBackground;
}

} // namespace ludashi::x11
