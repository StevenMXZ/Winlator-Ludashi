#include "core/client_session.h"

#include <X11/extensions/shmproto.h>
#include "native_drawable.h"
#include "core/wire_extensions.h"
#include "core/paint_state.h"
#include "core/sysv_segments.h"
#include "core/display_runtime.h"
#include "native_screen.h"

namespace ludashi::x11 {

void ClientSession::ApplyRasterCommand(u8 opcode) {
    switch (opcode) {
    case X_PolyFillRectangle: {
        const auto& request = *GetRequestData<xPolyFillRectangleReq>();
        const size_t bytes = request_size - sizeof(request);
        if (bytes % sizeof(xRectangle)) throw BadLengthError();
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        const auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        if (context->GetDepth() != image->GetDepth()) throw BadMatchError();
        const auto rectangles = std::span(reinterpret_cast<const xRectangle*>(&request + 1), bytes / sizeof(xRectangle));
        const u32 color = context->GetForeground() | 0xff000000;
        server.GetScreen().MutateDrawable(image, [&] {
            for (const auto& rectangle : rectangles)
                context->PaintRectangle(*image, {rectangle.x, rectangle.y,
                    s32{rectangle.x} + rectangle.width, s32{rectangle.y} + rectangle.height}, color);
        });
        return;
    }
    case X_PolyRectangle: {
        const auto& request = *GetRequestData<xPolyRectangleReq>();
        const size_t bytes = request_size - sizeof(request);
        if (bytes % sizeof(xRectangle)) throw BadLengthError();
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        const auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        if (context->GetDepth() != image->GetDepth()) throw BadMatchError();
        const auto rectangles = std::span(reinterpret_cast<const xRectangle*>(&request + 1), bytes / sizeof(xRectangle));
        const s32 stroke = std::max<u32>(1, context->GetLineWidth() & 0xffff);
        const s32 half = stroke / 2;
        const u32 color = context->GetForeground() | 0xff000000;
        server.GetScreen().MutateDrawable(image, [&] {
            for (const auto& r : rectangles) {
                const Rect outer{r.x - half, r.y - half, s32{r.x} + r.width + stroke - half,
                                  s32{r.y} + r.height + stroke - half};
                auto paint = [&](const Rect& area) { context->PaintRectangle(*image, area, color); };
                if (r.width <= stroke || r.height <= stroke) { paint(outer); continue; }
                paint({outer.left, outer.top, outer.right, outer.top + stroke});
                paint({outer.left, outer.bottom - stroke, outer.right, outer.bottom});
                paint({outer.left, outer.top + stroke, outer.left + stroke, outer.bottom - stroke});
                paint({outer.right - stroke, outer.top + stroke, outer.right, outer.bottom - stroke});
            }
        });
        return;
    }
    case X_PolyLine: {
        const auto& request = *GetRequestData<xPolyLineReq>();
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        const auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        const size_t bytes = request_size - sizeof(request);
        if (bytes % sizeof(xPoint)) throw BadLengthError();
        if (request.coordMode > CoordModePrevious) throw BadValueError(request.coordMode);
        auto points = std::span(reinterpret_cast<const xPoint*>(&request + 1), bytes / sizeof(xPoint));
        std::vector<xPoint> absolute;
        if (request.coordMode == CoordModePrevious && !points.empty()) {
            absolute.assign(points.begin(), points.end());
            for (size_t index = 1; index < absolute.size(); ++index) {
                absolute[index].x += absolute[index - 1].x;
                absolute[index].y += absolute[index - 1].y;
            }
            points = absolute;
        }
        server.GetScreen().MutateDrawable(image, [&] {
            image->DrawLines(points.data(), points.size(), context->GetForeground() | 0xff000000, context->GetLineWidth());
        });
        return;
    }
    case X_PolySegment: {
        const auto& request = *GetRequestData<xPolySegmentReq>();
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        const auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        const size_t bytes = request_size - sizeof(request);
        if (bytes % sizeof(xSegment)) throw BadLengthError();
        const auto segments = std::span(reinterpret_cast<const xSegment*>(&request + 1), bytes / sizeof(xSegment));
        server.GetScreen().MutateDrawable(image, [&] {
            image->DrawPolySegments(segments.data(), segments.size(),
                                   context->GetForeground() | 0xff000000, context->GetLineWidth());
        });
        return;
    }
    default:
        throw BadRequestError();
    }
}

void ClientSession::UploadImage(u8) {
    const auto& request = *GetRequestData<xPutImageReq>();
    auto* image = GetDrawable(request.drawable);
    if (!image) throw BadDrawableError(request.drawable);
    const auto* context = GetResourceAs<PaintState>(request.gc);
    if (!context) throw BadGCError(request.gc);
    if (request.format > ZPixmap) throw BadValueError(request.format);
    if (request.format == XYBitmap && request.depth != 1) throw BadMatchError();
    if (request.depth != image->GetDepth() || (request.format == ZPixmap && request.leftPad))
        throw BadMatchError();
    const bool binary = image->GetDepth() == 1;
    if (!binary && image->GetDepth() != 24 && image->GetDepth() != 32) throw BadMatchError();
    const uint64_t rowBytes = binary ? (u32{request.width} + 31) / 32 * 4 : u32{request.width} * 4;
    if (rowBytes * request.height > request_size - sizeof(request)) throw BadLengthError();
    const auto* pixels = request_buffer.data() + sizeof(request);
    auto& screen = server.GetScreen();
    screen.MutateDrawable(image, [&] {
        if (binary) {
            image->WriteBinaryPixels(pixels, request.width, request.height,
                request.format == XYBitmap ? context->GetForeground() : 1,
                request.format == XYBitmap ? context->GetBackground() : 0);
        } else {
            image->WritePixels(pixels, 0, 0, request.dstX, request.dstY,
                               request.width, request.height, request.width);
        }
    });
    screen.HudFrame(request.drawable);
}

void ClientSession::DownloadImage(u8) {
    const auto& request = *GetRequestData<xGetImageReq>();
    auto* image = GetDrawable(request.drawable);
    if (!image) throw BadDrawableError(request.drawable);
    if (request.format != ZPixmap) throw BadValueError(request.format);
    const size_t bytes = size_t{request.width} * request.height * 4;
    // Match the transport output bound before allocating the readback.
    if (bytes > 16 * 1024 * 1024 - sizeof(xGetImageReply)) throw BadAllocError();
    std::vector<u8> pixels(bytes);
    image->ReadPixels(pixels.data(), request.x, request.y, request.width, request.height, request.width);
    xGetImageReply reply{};
    reply.type = X_Reply;
    reply.sequenceNumber = sequence;
    reply.depth = image->GetDepth();
    reply.visual = ServerID::RootVisual;
    reply.length = pixels.size() / 4;
    Write(&reply, sizeof(reply));
    Write(pixels.data(), pixels.size());
    Flush();
}

void ClientSession::ProcShmRequest() {
    const auto minor = GetRequestData<xReq>()->data;
    switch (minor) {
    case X_ShmQueryVersion: {
        xShmQueryVersionReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.majorVersion = 1;
        reply.minorVersion = 2;
        reply.pixmapFormat = ZPixmap;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_ShmAttach: {
        const auto& request = *GetRequestData<xShmAttachReq>();
        CheckResourceID(request.shmseg);
        if (GetResource(request.shmseg)) throw BadIdChoiceError(request.shmseg);
        const auto [entry, inserted] = shmSegments.insert(request.shmseg);
        if (!server.Segments().Attach(request.shmseg, request.shmid)) {
            if (inserted) shmSegments.erase(entry);
            throw BadAccessError();
        }
        return;
    }
    case X_ShmDetach: {
        const auto id = GetRequestData<xShmDetachReq>()->shmseg;
        if (!server.Segments().Detach(id))
            throw XRequestError(WireExtensions::At(ExtensionOpcode::SharedMemory).error_base, id);
        shmSegments.erase(id);
        return;
    }
    case X_ShmPutImage: {
        const auto& request = *GetRequestData<xShmPutImageReq>();
        const auto region = server.Segments().Get(request.shmseg);
        if (!region) throw XRequestError(WireExtensions::At(ExtensionOpcode::SharedMemory).error_base, request.shmseg);
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        if (!GetResourceAs<PaintState>(request.gc)) throw BadGCError(request.gc);
        if ((request.depth != 24 && request.depth != 32) || request.format != ZPixmap)
            throw BadMatchError();
        const uint64_t bytes = uint64_t{request.totalWidth} * request.totalHeight * 4;
        if (uint64_t{request.offset} + bytes > region->size ||
            u32{request.srcX} + request.srcWidth > request.totalWidth ||
            u32{request.srcY} + request.srcHeight > request.totalHeight)
            throw BadMatchError();
        const auto* pixels = static_cast<const u8*>(region->addr) + request.offset;
        auto& screen = server.GetScreen();
        screen.MutateDrawable(image, [&] {
            image->WritePixels(pixels, request.srcX, request.srcY, request.dstX, request.dstY,
                               request.srcWidth, request.srcHeight, request.totalWidth);
        });
        screen.HudFrame(request.drawable);
        if (request.sendEvent) {
            xShmCompletionEvent event{};
            event.type = WireExtensions::At(ExtensionOpcode::SharedMemory).event_base;
            event.sequenceNumber = sequence;
            event.drawable = request.drawable;
            event.majorEvent = request_buffer[0];
            event.minorEvent = X_ShmPutImage;
            event.shmseg = request.shmseg;
            event.offset = request.offset;
            WriteImmediately(&event, sizeof(event));
        }
        return;
    }
    case X_ShmGetImage: {
        const auto& request = *GetRequestData<xShmGetImageReq>();
        const auto region = server.Segments().Get(request.shmseg);
        if (!region) throw XRequestError(WireExtensions::At(ExtensionOpcode::SharedMemory).error_base, request.shmseg);
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        const uint64_t bytes = uint64_t{request.width} * request.height * 4;
        if (request.format != ZPixmap || uint64_t{request.offset} + bytes > region->size)
            throw BadMatchError();
        image->ReadPixels(static_cast<u8*>(region->addr) + request.offset,
                          request.x, request.y, request.width, request.height, request.width);
        xShmGetImageReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.depth = image->GetDepth();
        reply.visual = ServerID::RootVisual;
        reply.size = bytes;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
