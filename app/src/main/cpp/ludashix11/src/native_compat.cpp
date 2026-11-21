#include "core/client_session.h"
#include "core/display_runtime.h"
#include "core/window_tree.h"
#include "core/window_node.h"
#include "core/key_state.h"
#include "core/atom_catalog.h"
#include "core/paint_state.h"
#include "native_drawable.h"
#include "core/wire_extensions.h"
#include "core/pointer_sprite.h"
#include "native_screen.h"

namespace ludashi::x11 {
void ClientSession::ApplyCompatibilityCommand(u8 opcode) {
    auto send = [this](auto& r) { r.type = X_Reply; r.sequenceNumber = sequence; WriteImmediately(&r, sizeof(r)); };
    switch (opcode) {
    case X_ChangeSaveSet: case X_CloseFont:
    case X_GrabButton: case X_UngrabButton: case X_GrabKey: case X_UngrabKey:
    case X_GrabServer: case X_UngrabServer:
    case X_ChangeKeyboardControl: case X_ChangePointerControl:
    case X_SetCloseDownMode: case X_SetScreenSaver: case X_InstallColormap:
    case X_UninstallColormap: case X_FreeColors:
        return;
    case X_FreeCursor: {
        RequireExactRequest<xResourceReq>();
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!GetResourceAs<PointerSprite>(id)) throw BadCursorError(id);
        // WindowNode attributes and active grabs retain their own references.
        RemoveResource(id); return;
    }
    case X_RecolorCursor: {
        RequireExactRequest<xRecolorCursorReq>(); const auto* q = GetRequestData<xRecolorCursorReq>();
        auto* cursor = GetResourceAs<PointerSprite>(q->cursor); if (!cursor) throw BadCursorError(q->cursor);
        auto rgb = [](u16 r, u16 g, u16 b) { return u32(r >> 8) << 16 | u32(g >> 8) << 8 | (b >> 8); };
        cursor->Recolor(rgb(q->foreRed, q->foreGreen, q->foreBlue), rgb(q->backRed, q->backGreen, q->backBlue));
        server.Windows().UpdatePointer(NotifyNormal); return;
    }
    case X_CopyArea: {
        RequireExactRequest<xCopyAreaReq>(); const auto* r = GetRequestData<xCopyAreaReq>();
        auto* source = GetDrawable(r->srcDrawable); auto* target = GetDrawable(r->dstDrawable);
        auto* gc = GetResourceAs<PaintState>(r->gc);
        if (!source || !target) throw BadDrawableError(!source ? r->srcDrawable : r->dstDrawable);
        // Public 4.1 uses redirected-window CopyArea as a composition/stacking
        // request. Keep ordinary pixmaps on the real copy path.
        auto* srcWindow = GetResourceAs<WindowNode>(r->srcDrawable);
        if (srcWindow && srcWindow->IsCompositeRedirected()) {
            if (auto* dstWindow = GetResourceAs<WindowNode>(r->dstDrawable))
                server.Windows().CompositeCopy(srcWindow, dstWindow, r->dstX, r->dstY);
            return;
        }
        if (!gc) throw BadGCError(r->gc);
        server.GetScreen().MutateDrawable(target, [&] {
            target->CopyAreaFrom(*source,
                    r->srcX, r->srcY, r->dstX, r->dstY, r->width, r->height);
        });
        if (gc->ReportsExposures()) {
            xEvent event{}; event.u.u.type = NoExpose; event.u.noExposure.drawable = r->dstDrawable;
            event.u.noExposure.majorEvent = X_CopyArea; DeliverEvent(event);
        }
        return;
    }
    case X_ClearArea: {
        RequireExactRequest<xClearAreaReq>(); const auto* r = GetRequestData<xClearAreaReq>();
        auto* w = GetResourceAs<WindowNode>(r->window); if (!w) throw BadWindowError(r->window);
        auto* d = w->GetDrawable(); if (!d) return;
        server.GetScreen().MutateDrawable(d, [&] {
            d->FillRect(r->x, r->y, r->width ? r->width : w->GetWidth() - r->x,
                    r->height ? r->height : w->GetHeight() - r->y, w->Options().background);
        });
        if (r->exposures) w->MakeExposure(); return;
    }
    case X_QueryKeymap: {
        xQueryKeymapReply r{};
        r.length = 2;
        const auto keys = server.Keys().GetKeymap();
        static_assert(sizeof(r.map) == keys.size());
        std::memcpy(r.map, keys.data(), keys.size());
        send(r);
        return;
    }
    case X_GetKeyboardControl: {
        xGetKeyboardControlReply r{}; r.length = 5; r.globalAutoRepeat = 1;
        r.keyClickPercent = 0; r.bellPercent = 0; send(r); return;
    }
    case X_GetPointerControl: {
        xGetPointerControlReply r{}; r.accelNumerator = r.accelDenominator = 1; r.threshold = 0; send(r); return;
    }
    case X_GetPointerMapping: {
        xGetPointerMappingReply r{}; r.type = X_Reply; r.sequenceNumber = sequence; r.nElts = 7; r.length = 2;
        const u8 buttons[] = {1,2,3,4,5,6,7,0}; Write(&r, sizeof(r)); Write(buttons, 8); Flush(); return;
    }
    case X_SetPointerMapping: case X_SetModifierMapping: {
        xSetPointerMappingReply r{}; r.success = MappingSuccess; send(r); return;
    }
    case X_QueryBestSize: {
        RequireExactRequest<xQueryBestSizeReq>(); const auto* q = GetRequestData<xQueryBestSizeReq>();
        xQueryBestSizeReply r{}; r.width = q->width; r.height = q->height; send(r); return;
    }
    case X_AllocColor: {
        RequireExactRequest<xAllocColorReq>(); const auto* q = GetRequestData<xAllocColorReq>();
        xAllocColorReply r{}; r.red = q->red; r.green = q->green; r.blue = q->blue;
        r.pixel = (q->red >> 8) << 16 | (q->green >> 8) << 8 | (q->blue >> 8); send(r); return;
    }
    case X_TranslateCoords: {
        RequireExactRequest<xTranslateCoordsReq>(); const auto* q = GetRequestData<xTranslateCoordsReq>();
        auto* source = GetResourceAs<WindowNode>(q->srcWid); auto* target = GetResourceAs<WindowNode>(q->dstWid);
        if (!source || !target) throw BadWindowError(!source ? q->srcWid : q->dstWid);
        xTranslateCoordsReply r{}; r.sameScreen = true;
        r.dstX = source->ContentBounds().left + q->srcX - target->ContentBounds().left;
        r.dstY = source->ContentBounds().top + q->srcY - target->ContentBounds().top;
        send(r); return;
    }
    case X_ListExtensions: {
        static const auto names = [] {
            std::vector<u8> data;
            for (const auto& extension : WireExtensions::All()) {
                data.push_back(extension.name.size());
                data.insert(data.end(), extension.name.begin(), extension.name.end());
            }
            return data;
        }();
        xListExtensionsReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.nExtensions = WireExtensions::All().size();
        reply.length = (names.size() + 3) / 4;
        Write(&reply, sizeof(reply));
        Write(names.data(), names.size());
        WritePad(-names.size() & 3);
        Flush();
        return;
    }
    default: throw BadRequestError();
    }
}
}


