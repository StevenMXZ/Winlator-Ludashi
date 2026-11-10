#include <vector>

#include "base/log.h"
#include "core/cursor.h"
#include "core/cursor/cursor_font.h"
#include "core/drawable.h"
#include "core/pixmap.h"
#include "xscreen.h"

namespace xorg {

Cursor::Cursor(XScreen& screen,
               u32 id,
               s32 hotspot_x,
               s32 hotspot_y,
               Pixmap* source_pixmap,
               Pixmap* mask_pixmap,
               u32 fg_color,
               u32 bg_color)
        : XResource(XResourceType::Cursor, id)
        , screen(screen)
        , hotspot_x(hotspot_x)
        , hotspot_y(hotspot_y) {
    const auto source_drawable = source_pixmap->GetDrawable();
    const auto mask_drawable = mask_pixmap ? mask_pixmap->GetDrawable() : nullptr;
    const u32 source_stride = source_drawable->GetStride() / 4;
    const u32 mask_stride = mask_drawable ? mask_drawable->GetStride() / 4 : 0;
    const auto source = static_cast<u32*>(source_drawable->Map());
    const auto mask = mask_drawable ? static_cast<u32*>(mask_drawable->Map()) : nullptr;

    // Depth-one drawables store pixel values 0/1, unlike built-in font RGBA.
    // Normalize both inputs before using the common cursor conversion.
    std::vector<u32> source_bits(size_t(source_stride) * source_pixmap->height);
    std::vector<u32> mask_bits(mask ? size_t(mask_stride) * mask_pixmap->height : 0);
    std::transform(source, source + source_bits.size(), source_bits.begin(),
                   [](u32 bit) { return (bit & 1) ? 0xff000000u : 0x00ffffffu; });
    if (mask) std::transform(mask, mask + mask_bits.size(), mask_bits.begin(),
                            [](u32 bit) { return (bit & 1) ? 0xff000000u : 0x00ffffffu; });
    UpdateCursorDrawable(source_pixmap->width,
                         source_pixmap->height,
                         source_stride,
                         source_bits.data(),
                         mask_stride,
                         mask ? mask_bits.data() : nullptr,
                         fg_color,
                         bg_color);
}

Cursor::Cursor(XScreen& screen, u32 id, u32 source_char, u32 mask_char, u32 fg_color, u32 bg_color)
        : XResource(XResourceType::Cursor, id), screen(screen) {
    const auto& source_font = GetXCursorFont(source_char);
    ASSERT_MSG(source_font.data, "Unsupported source char {}", source_char);
    const auto& mask_font = GetXCursorFont(mask_char);
    ASSERT_MSG(mask_font.data, "Unsupported mask char {}", mask_char);

    hotspot_x = source_font.xhot;
    hotspot_y = source_font.yhot;

    std::vector<u8> source_data = source_font.ToRGBA();
    std::vector<u8> mask_data = mask_font.ToRGBA();

    UpdateCursorDrawable(source_font.width,
                         source_font.height,
                         source_font.width,
                         reinterpret_cast<const u32*>(source_data.data()),
                         mask_font.width,
                         reinterpret_cast<const u32*>(mask_data.data()),
                         fg_color,
                         bg_color);
}

Cursor::~Cursor() {}

Cursor::Cursor(XScreen& screen, u32 id, u16 hotX, u16 hotY, Drawable& image, bool premultiplied)
    : XResource(XResourceType::Cursor, id), screen(screen), hotspot_x(hotX), hotspot_y(hotY) {
    cursorWidth = image.GetWidth(); cursorHeight = image.GetHeight();
    drawable = screen.CreateDrawable(cursorWidth, cursorHeight, 32);
    std::vector<u32> pixels(size_t(cursorWidth) * cursorHeight);
    image.ReadPixels(reinterpret_cast<u8*>(pixels.data()), 0, 0, cursorWidth, cursorHeight, cursorWidth);
    // RENDER ARGB32 is premultiplied. The existing cursor Vulkan pipeline uses
    // SRC_ALPHA, so normalize once here, without changing the Java pipeline.
    if (premultiplied) for (auto& pixel : pixels) {
        const u32 alpha = pixel >> 24;
        if (!alpha) { pixel = 0; continue; }
        if (alpha == 255) continue;
        const auto channel = [alpha](u32 c) { return std::min(255u, (c * 255 + alpha / 2) / alpha); };
        pixel = (alpha << 24) | (channel((pixel >> 16) & 255) << 16) |
                (channel((pixel >> 8) & 255) << 8) | channel(pixel & 255);
    }
    drawable->WritePixels(reinterpret_cast<const u8*>(pixels.data()), 0, 0, 0, 0,
                          cursorWidth, cursorHeight, cursorWidth);
}

void Cursor::UpdateCursorDrawable(u32 width,
                                  u32 height,
                                  u32 source_stride,
                                  const u32* source,
                                  u32 mask_stride,
                                  const u32* mask,
                                  u32 fg_color,
                                  u32 bg_color) {
    cursorWidth = width; cursorHeight = height;
    shape.resize(size_t(width) * height);
    for (u32 y = 0; y < height; ++y) for (u32 x = 0; x < width; ++x) {
        shape[size_t(y) * width + x] = mask && mask[y * mask_stride + x] != 0xff000000 ? 0 :
            source[y * source_stride + x] == 0xff000000 ? 1 : 2;
    }
    Recolor(fg_color, bg_color);
}
void Cursor::Recolor(u32 fg, u32 bg) {
    if (shape.empty()) return; // Core RecolorCursor does not recolor ARGB cursors.
    // A new drawable also invalidates the cached native cursor snapshot.
    drawable = screen.CreateDrawable(cursorWidth, cursorHeight, 32);
    auto* bitmap = static_cast<u32*>(drawable->Map());
    const u32 stride = drawable->GetStride() / 4;
    for (u32 y = 0; y < cursorHeight; ++y) for (u32 x = 0; x < cursorWidth; ++x) {
        const u8 bit = shape[size_t(y) * cursorWidth + x];
        bitmap[y * stride + x] = bit == 0 ? 0 : (bit == 1 ? fg : bg) | 0xff000000;
    }
}

}  // namespace xorg

