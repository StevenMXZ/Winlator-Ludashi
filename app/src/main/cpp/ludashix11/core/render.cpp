// Wine GDI remains on the core path through ClientSideWithRender=N.
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window.h"
#include "core/pixmap.h"
#include "core/cursor.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/id_generator.h"
#include "core/visual.h"
#include <X11/extensions/renderproto.h>

namespace xorg {
namespace {
struct RenderPicture final : XResource {
    RenderPicture(u32 id, sp<Drawable> image, u8 depth)
        : XResource(XResourceType::RenderPicture, id), image(std::move(image)), depth(depth) {}
    // Preserve the backing image even when libXcursor frees its pixmap first.
    // Writes after CreatePicture are observed until CreateCursor snapshots it.
    sp<Drawable> image;
    u8 depth;
};
const std::array<u32, 3>& CursorFormats() {
    static const std::array<u32, 3> formats{u32(GenerateID()), u32(GenerateID()), u32(GenerateID())};
    return formats;
}
}
void XClient::ProcRender() {
    const auto& formats = CursorFormats();
    const auto* extension = server.GetExtensionManager().FindExtension("RENDER");
    const auto pictureError = [&](u8 offset, u32 id) {
        return XRequestError(extension->error_base + offset, id);
    };
    auto newID = [&](u32 id) { CheckResourceID(id); if (GetResource(id)) throw BadIdChoiceError(id); };
    switch (GetRequestData<xReq>()->data) {
    case X_RenderQueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xRenderQueryVersionReq);
        const auto* r = GetRequestData<xRenderQueryVersionReq>();
        xRenderQueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 0; reply.minorVersion = r->majorVersion ? 8 : std::min(8u, r->minorVersion);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_RenderQueryPictFormats: {
        CHECK_REQUEST_SIZE_STRICT(xRenderQueryPictFormatsReq);
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
        packet.visual.visual = server.GetRootVisual()->id; packet.visual.format = formats[1];
        packet.argb.depth = 32;
        WriteImmediately(&packet, sizeof(packet)); return;
    }
    case X_RenderCreatePicture: {
        CHECK_REQUEST_SIZE(xRenderCreatePictureReq);
        auto* r = GetRequestData<xRenderCreatePictureReq>(); newID(r->pid);
        if (request_size != sizeof(*r) + std::popcount(r->mask) * 4u) throw BadLengthError();
        auto f = std::ranges::find(formats, r->format);
        if (f == formats.end()) throw pictureError(BadPictFormat, r->format);
        auto* resource = GetResource(r->drawable);
        if (!resource || !resource->IsDrawable()) throw BadDrawableError(r->drawable);
        sp<Drawable> image = resource->GetType() == XResourceType::Pixmap
            ? resource->As<Pixmap>()->SharedDrawable() : resource->As<Window>()->SharedDrawable();
        const u8 depth = f == formats.begin() ? 1 : f == formats.begin() + 1 ? 24 : 32;
        if (!image || image->GetDepth() != depth) throw BadMatchError();
        AddResource(std::make_shared<RenderPicture>(r->pid, std::move(image), depth)); return;
    }
    case X_RenderFreePicture: {
        CHECK_REQUEST_SIZE_STRICT(xRenderFreePictureReq);
        const u32 id = GetRequestData<xRenderFreePictureReq>()->picture;
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::RenderPicture) throw pictureError(BadPicture, id);
        RemoveResource(id); return;
    }
    case X_RenderCreateCursor: {
        CHECK_REQUEST_SIZE_STRICT(xRenderCreateCursorReq);
        auto* r = GetRequestData<xRenderCreateCursorReq>(); newID(r->cid);
        auto* p = GetResource(r->src);
        if (!p || p->GetType() != XResourceType::RenderPicture) throw pictureError(BadPicture, r->src);
        auto* picture = p->As<RenderPicture>(); auto& image = *picture->image;
        if (picture->depth != 32 || r->x >= image.GetWidth() || r->y >= image.GetHeight()) throw BadMatchError();
        if (size_t(image.GetWidth()) * image.GetHeight() > 512u * 512u) throw BadAllocError();
        AddResource(std::make_shared<Cursor>(server.GetScreen(), r->cid, r->x, r->y, image)); return;
    }
    case X_RenderCreateAnimCursor: {
        CHECK_REQUEST_SIZE(xRenderCreateAnimCursorReq);
        auto* r = GetRequestData<xRenderCreateAnimCursorReq>(); newID(r->cid);
        if (request_size <= sizeof(*r) || (request_size - sizeof(*r)) % sizeof(xAnimCursorElt)) throw BadLengthError();
        const auto* frames = reinterpret_cast<const xAnimCursorElt*>(r + 1);
        const size_t count = (request_size - sizeof(*r)) / sizeof(*frames);
        for (size_t i = 0; i < count; ++i) if (!GetResourceAs<Cursor>(frames[i].cursor)) throw BadCursorError(frames[i].cursor);
        auto* first = GetResourceAs<Cursor>(frames[0].cursor);
        AddResource(std::make_shared<Cursor>(server.GetScreen(), r->cid, first->GetHotspotX(),
            first->GetHotspotY(), *first->GetDrawable(), false)); return; // Static first frame.
    }
    default: throw XRequestError(BadImplementation, 0);
    }
}
}
