#include "native_drawable.h"
#include "core/request_error.h"

namespace ludashi::x11 {
static std::atomic<u64> next_drawable{1};
NativeDrawable::NativeDrawable(u16 w, u16 h, u8 d, AHardwareBuffer* ahb)
    : width(w), height(h), depth(d), stride(u32(w) * 4), id(next_drawable.fetch_add(1, std::memory_order_relaxed)) {
    if (!w || !h || w > 16384 || h > 16384 || u64(w) * h > 64 * 1024 * 1024)
        throw BadAllocError();
    if (ahb) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        // Android gralloc may pad an AHB allocation beyond the X11 pixmap extent.
        // Keep the logical X11 size and sample only that region, without CPU copies.
        if (desc.width < w || desc.height < h || desc.stride < w || desc.layers != 1 ||
            desc.stride > UINT32_MAX / 4u)
            throw BadMatchError();
        stride = desc.stride * 4;
        AHardwareBuffer_acquire(ahb); buffer = ahb;
    } else pixels = std::make_shared<std::vector<u8>>(size_t(stride) * h);
}
NativeDrawable::~NativeDrawable() {
    if (!buffer) return;
    if (locked) AHardwareBuffer_unlock(buffer, nullptr);
    AHardwareBuffer_release(buffer);
}
void NativeDrawable::Writable() {
    if (buffer) { Map(); ++revision; return; }
    if (pixels.use_count() != 1) pixels = std::make_shared<std::vector<u8>>(*pixels);
    ++revision;
}
void* NativeDrawable::Map() {
    if (buffer && !locked) {
        if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &mapped) != 0)
            throw BadAccessError();
        locked = true;
    }
    return buffer ? mapped : pixels->data();
}
void NativeDrawable::ReadPixels(u8* dst, s16 x, s16 y, u16 w, u16 h, u16 dst_stride) {
    if (x < 0 || y < 0 || u32(x) + w > width || u32(y) + h > height || dst_stride < w)
        throw BadMatchError();
    const auto* src = static_cast<const u8*>(Map());
    for (u32 row = 0; row < h; ++row)
        memcpy(dst + size_t(row) * dst_stride * 4, src + size_t(y + row) * stride + x * 4, size_t(w) * 4);
}
void NativeDrawable::WritePixels(const u8* src, s16 sx, s16 sy, s16 dx, s16 dy,
                                  u16 w, u16 h, u16 src_stride) {
    if (!src || sx < 0 || sy < 0 || u32(sx) + w > src_stride) throw BadMatchError();
    int left = std::max(0, int(dx)), top = std::max(0, int(dy));
    int right = std::min(int(width), int(dx) + w), bottom = std::min(int(height), int(dy) + h);
    if (right <= left || bottom <= top) return;
    // CopyArea can read from the same drawable. Preserve source on overlaps.
    std::vector<u8> copy;
    auto* old = static_cast<u8*>(Map());
    if (src == old) { copy.assign(src, src + size_t(stride) * height); src = copy.data(); }
    Writable(); auto* dst = static_cast<u8*>(Map());
    for (int y = top; y < bottom; ++y)
        memcpy(dst + size_t(y) * stride + left * 4,
               src + (size_t(sy + y - dy) * src_stride + sx + left - dx) * 4,
               size_t(right - left) * 4);
}
void NativeDrawable::CopyAreaFrom(NativeDrawable& source, s16 sx, s16 sy, s16 dx, s16 dy, u16 w, u16 h) {
    // Keep the source validation and destination clipping of ReadPixels + WritePixels.
    if (sx < 0 || sy < 0 || u32(sx) + w > source.width || u32(sy) + h > source.height)
        throw BadMatchError();
    if (!w || !h) throw BadMatchError(); // Preserve the previous zero-size request result.
    if (buffer || source.buffer) {
        // Preserve the existing AHB mapping/synchronization path. The fast path
        // is strictly for CPU storage; no new mapping or GPU wait is introduced.
        std::vector<u8> copy(size_t(w) * h * 4);
        source.ReadPixels(copy.data(), sx, sy, w, h, w);
        WritePixels(copy.data(), 0, 0, dx, dy, w, h, w);
        return;
    }
    const int left = std::max(0, int(dx)), top = std::max(0, int(dy));
    const int right = std::min(int(width), int(dx) + w), bottom = std::min(int(height), int(dy) + h);
    if (right <= left || bottom <= top) return;
    Writable(); // Fork once if a scene/GPU frame still owns the old snapshot.
    const int sourceX = int(sx) + left - dx, sourceY = int(sy) + top - dy;
    const int rows = bottom - top;
    const bool backwards = &source == this && top > sourceY && top < sourceY + rows;
    const size_t bytes = size_t(right - left) * 4;
    for (int n = 0; n < rows; ++n) {
        const int row = backwards ? rows - n - 1 : n;
        std::memmove(pixels->data() + size_t(top + row) * stride + size_t(left) * 4,
                     source.pixels->data() + size_t(sourceY + row) * source.stride + size_t(sourceX) * 4, bytes);
    }
}
void NativeDrawable::FillRect(s32 x, s32 y, u32 w, u32 h, u32 color) {
    const auto left = std::max(0, x), top = std::max(0, y);
    const auto right = std::min<int64_t>(width, int64_t{x} + w);
    const auto bottom = std::min<int64_t>(height, int64_t{y} + h);
    // Clip wide intermediate coordinates before COW, mapping or revision changes.
    if (left >= right || top >= bottom) return;
    u8* destination = nullptr;
    FillClipped(left, top, right, bottom, color, destination);
}

void NativeDrawable::FillClipped(s32 left, s32 top, s32 right, s32 bottom, u32 color, u8*& destination) {
    if (!destination) {
        Writable();
        destination = static_cast<u8*>(Map());
    }
    for (auto row = top; row < bottom; ++row) {
        auto* line = reinterpret_cast<u32*>(destination + size_t(row) * stride);
        std::fill(line + left, line + right, color);
    }
}
void NativeDrawable::WriteBinaryPixels(const u8* src, u16 w, u16 h, u32 fg, u32 bg) {
    Writable(); auto* dst = static_cast<u8*>(Map());
    // X11 bitmaps use a 32-bit scanline pad.
    size_t row_bytes = ((size_t(w) + 31) / 32) * 4;
    for (u32 y = 0; y < std::min(h, height); ++y)
        for (u32 x = 0; x < std::min(w, width); ++x)
            reinterpret_cast<u32*>(dst + size_t(y) * stride)[x] =
                (src[y * row_bytes + x / 8] & (1u << (x % 8))) ? fg : bg;
}
void NativeDrawable::Line(xPoint a, xPoint b, u32 color, u32 line_width, u8*& destination) {
    const int radius = std::clamp<int>(line_width, 1, 64) / 2;
    const int left = std::max(0, std::min(a.x, b.x) - radius);
    const int top = std::max(0, std::min(a.y, b.y) - radius);
    const int right = std::min(int(width), std::max(a.x, b.x) + radius + 1);
    const int bottom = std::min(int(height), std::max(a.y, b.y) + radius + 1);
    if (left >= right || top >= bottom) return;
    if (a.x == b.x || a.y == b.y) {
        FillClipped(left, top, right, bottom, color, destination);
        return;
    }

    int x = a.x, y = a.y, dx = std::abs(int(b.x) - x), sx = x < b.x ? 1 : -1;
    int dy = -std::abs(int(b.y) - y), sy = y < b.y ? 1 : -1, error = dx + dy;
    for (;;) {
        const int stampLeft = std::max(left, x - radius), stampTop = std::max(top, y - radius);
        const int stampRight = std::min(right, x + radius + 1), stampBottom = std::min(bottom, y + radius + 1);
        if (stampLeft < stampRight && stampTop < stampBottom)
            FillClipped(stampLeft, stampTop, stampRight, stampBottom, color, destination);
        if (x == b.x && y == b.y) break;
        int twice = 2 * error;
        if (twice >= dy) { error += dy; x += sx; }
        if (twice <= dx) { error += dx; y += sy; }
    }
}
void NativeDrawable::DrawLines(const xPoint* p, u32 n, u32 color, u32 width) {
    u8* destination = nullptr;
    for (u32 i = 1; i < n; ++i) Line(p[i - 1], p[i], color, width, destination);
}
void NativeDrawable::DrawPolySegments(const xSegment* p, u32 n, u32 color, u32 width) {
    u8* destination = nullptr;
    for (u32 i = 0; i < n; ++i) Line({p[i].x1, p[i].y1}, {p[i].x2, p[i].y2}, color, width, destination);
}
}

