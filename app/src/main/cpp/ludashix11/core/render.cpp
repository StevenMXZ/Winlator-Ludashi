#include "native_screen.h"
// Wine GDI remains on the core path through ClientSideWithRender=N.
#include "core/client_session.h"
#include "core/display_runtime.h"
#include "core/window_node.h"
#include "core/pointer_sprite.h"
#include "native_drawable.h"
#include "core/wire_extensions.h"
#include <X11/extensions/renderproto.h>

namespace ludashi::x11 {
namespace {
struct RenderPicture final : ProtocolObject {
    RenderPicture(u32 id, sp<NativeDrawable> image, u8 depth)
        : ProtocolObject(ObjectKind::RenderPicture, id), image(std::move(image)), depth(depth) {}
    // Preserve the backing image even when libXcursor frees its pixmap first.
    // Writes after CreatePicture are observed until CreateCursor snapshots it.
    sp<NativeDrawable> image;
    u8 depth;
};
constexpr std::array formats{ServerID::RenderA1, ServerID::RenderRgb, ServerID::RenderArgb};
}
void ClientSession::ProcRender() {
    const auto* extension = &WireExtensions::At(ExtensionOpcode::Render);
    const auto pictureError = [&](u8 offset, u32 id) {
        return XRequestError(extension->error_base + offset, id);
    };
    auto newID = [&](u32 id) { CheckResourceID(id); if (GetResource(id)) throw BadIdChoiceError(id); };
    switch (GetRequestData<xReq>()->data) {
    case X_RenderQueryVersion: {
        RequireExactRequest<xRenderQueryVersionReq>();
        const auto* r = GetRequestData<xRenderQueryVersionReq>();
        xRenderQueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 0; reply.minorVersion = r->majorVersion ? 8 : std::min(8u, r->minorVersion);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_RenderQueryPictFormats: {
        RequireExactRequest<xRenderQueryPictFormatsReq>();
        struct FormatsReply {
            xRenderQueryPictFormatsReply header{};
            std::array<xPictFormInfo, 3> formats{};
            xPictScreen screen{};
            xPictDepth a1{}, rgb{};
            xPictVisual visual{};
            xPictDepth argb{};
            u32 subpixel{};
        } packet;
        static_assert(sizeof(packet) == 32 + 3 * 28 + 8 + 3 * 8 + 8 + 4);
        auto& h = packet.header;
        h.type = X_Reply; h.sequenceNumber = sequence; h.length = (sizeof(packet) - 32) / 4;
        h.numFormats = 3; h.numScreens = 1; h.numDepths = 3; h.numVisuals = 1; h.numSubpixel = 1;
        for (size_t i = 0; i < 3; ++i) {
            auto& f = packet.formats[i]; f.id = formats[i]; f.type = PictTypeDirect;
            f.depth = i == 0 ? 1 : i == 1 ? 24 : 32;
            if (i) { f.direct.red = 16; f.direct.green = 8; f.direct.blue = 0;
                f.direct.redMask = f.direct.greenMask = f.direct.blueMask = 255; }
            if (i != 1) { f.direct.alpha = i == 0 ? 0 : 24; f.direct.alphaMask = i == 0 ? 1 : 255; }
        }
        packet.screen.nDepth = 3; packet.screen.fallback = formats[1];
        packet.a1.depth = 1; packet.rgb.depth = 24; packet.rgb.nPictVisuals = 1;
        packet.visual.visual = ServerID::RootVisual; packet.visual.format = formats[1];
        packet.argb.depth = 32;
        WriteImmediately(&packet, sizeof(packet)); return;
    }
    case X_RenderCreatePicture: {
        RequireRequestHeader<xRenderCreatePictureReq>();
        auto* r = GetRequestData<xRenderCreatePictureReq>(); newID(r->pid);
        if (request_size != sizeof(*r) + std::popcount(r->mask) * 4u) throw BadLengthError();
        auto f = std::ranges::find(formats, r->format);
        if (f == formats.end()) throw pictureError(BadPictFormat, r->format);
        // InputOnly windows have an empty image slot and return BadMatch.
        const auto* storage = FindDrawingStorage(r->drawable);
        if (!storage) throw BadDrawableError(r->drawable);
        sp<NativeDrawable> image = *storage;
        const u8 depth = f == formats.begin() ? 1 : f == formats.begin() + 1 ? 24 : 32;
        if (!image || image->GetDepth() != depth) throw BadMatchError();
        AddResource(std::make_shared<RenderPicture>(r->pid, std::move(image), depth)); return;
    }
    case X_RenderFreePicture: {
        RequireExactRequest<xRenderFreePictureReq>();
        const u32 id = GetRequestData<xRenderFreePictureReq>()->picture;
        auto* r = GetResource(id);
        if (!r || r->GetType() != ObjectKind::RenderPicture) throw pictureError(BadPicture, id);
        RemoveResource(id); return;
    }
    case X_RenderCreateCursor: {
        RequireExactRequest<xRenderCreateCursorReq>();
        auto* r = GetRequestData<xRenderCreateCursorReq>(); newID(r->cid);
        auto* p = GetResource(r->src);
        if (!p || p->GetType() != ObjectKind::RenderPicture) throw pictureError(BadPicture, r->src);
        auto* picture = p->As<RenderPicture>(); auto& image = *picture->image;
        if (picture->depth != 32 || r->x >= image.GetWidth() || r->y >= image.GetHeight()) throw BadMatchError();
        if (size_t(image.GetWidth()) * image.GetHeight() > 512u * 512u) throw BadAllocError();
        AddResource(std::make_shared<PointerSprite>(server.GetScreen(), r->cid, r->x, r->y, image)); return;
    }
    case X_RenderCreateAnimCursor: {
        RequireRequestHeader<xRenderCreateAnimCursorReq>();
        auto* r = GetRequestData<xRenderCreateAnimCursorReq>(); newID(r->cid);
        if (request_size <= sizeof(*r) || (request_size - sizeof(*r)) % sizeof(xAnimCursorElt)) throw BadLengthError();
        const auto* frames = reinterpret_cast<const xAnimCursorElt*>(r + 1);
        const size_t count = (request_size - sizeof(*r)) / sizeof(*frames);
        for (size_t i = 0; i < count; ++i) if (!GetResourceAs<PointerSprite>(frames[i].cursor)) throw BadCursorError(frames[i].cursor);
        auto* first = GetResourceAs<PointerSprite>(frames[0].cursor);
        AddResource(std::make_shared<PointerSprite>(server.GetScreen(), r->cid, first->GetHotspotX(),
            first->GetHotspotY(), *first->Image(), false)); return; // Static first frame.
    }
    default: throw XRequestError(BadImplementation, 0);
    }
}
}
