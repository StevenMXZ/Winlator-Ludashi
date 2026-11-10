#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window_manager.h"
#include "core/window.h"
#include "core/keyboard.h"
#include "core/atom.h"
#include "core/selection_manager.h"
#include "core/graphics_context.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/xscreen.h"
#include "core/cursor.h"
#include "native_drawable.h"
#include "native_screen.h"

namespace xorg {
void XClient::ProcCompat() {
    const auto* req = GetRequestData<xReq>();
    auto send = [this](auto& r) { r.type = X_Reply; r.sequenceNumber = sequence; WriteImmediately(&r, sizeof(r)); };
    switch (req->reqType) {
    case X_ChangeSaveSet: case X_CloseFont:
    case X_GrabButton: case X_UngrabButton: case X_GrabKey: case X_UngrabKey:
    case X_GrabServer: case X_UngrabServer: case X_AllowEvents:
    case X_ChangeKeyboardControl: case X_ChangePointerControl:
    case X_SetCloseDownMode: case X_SetScreenSaver: case X_InstallColormap:
    case X_UninstallColormap: case X_FreeColors:
        return;
    case X_FreeCursor: {
        CHECK_REQUEST_SIZE_STRICT(xResourceReq);
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!GetResourceAs<Cursor>(id)) throw BadCursorError(id);
        // Window attributes and active grabs retain their own references.
        RemoveResource(id); return;
    }
    case X_RecolorCursor: {
        CHECK_REQUEST_SIZE_STRICT(xRecolorCursorReq); const auto* q = GetRequestData<xRecolorCursorReq>();
        auto* cursor = GetResourceAs<Cursor>(q->cursor); if (!cursor) throw BadCursorError(q->cursor);
        auto rgb = [](u16 r, u16 g, u16 b) { return u32(r >> 8) << 16 | u32(g >> 8) << 8 | (b >> 8); };
        cursor->Recolor(rgb(q->foreRed, q->foreGreen, q->foreBlue), rgb(q->backRed, q->backGreen, q->backBlue));
        server.GetWindowManager().UpdatePointer(NotifyNormal); return;
    }
    case X_GrabKeyboard: {
        CHECK_REQUEST_SIZE_STRICT(xGrabKeyboardReq);
        xGrabKeyboardReply r{}; r.status = GrabSuccess; send(r); return;
    }
    case X_UngrabKeyboard: return;
    case X_CopyArea: {
        CHECK_REQUEST_SIZE_STRICT(xCopyAreaReq); const auto* r = GetRequestData<xCopyAreaReq>();
        auto* source = GetDrawable(r->srcDrawable); auto* target = GetDrawable(r->dstDrawable);
        auto* gc = GetResourceAs<GraphicsContext>(r->gc);
        if (!source || !target) throw BadDrawableError(!source ? r->srcDrawable : r->dstDrawable);
        // Public 4.1 uses redirected-window CopyArea as a composition/stacking
        // request. Keep ordinary pixmaps on the real copy path.
        auto* srcWindow = GetResourceAs<Window>(r->srcDrawable);
        if (srcWindow && srcWindow->IsCompositeRedirected()) {
            if (auto* dstWindow = GetResourceAs<Window>(r->dstDrawable))
                server.GetWindowManager().CompositeCopy(srcWindow, dstWindow, r->dstX, r->dstY);
            return;
        }
        if (!gc) throw BadGCError(r->gc);
        static_cast<NativeDrawable*>(target)->CopyAreaFrom(*static_cast<NativeDrawable*>(source),
                r->srcX, r->srcY, r->dstX, r->dstY, r->width, r->height);
        static_cast<NativeScreen&>(server.GetScreen()).DrawableChanged(target);
        if (gc->GetAttribute(GCGraphicsExposures)) {
            xEvent event{}; event.u.u.type = NoExpose; event.u.noExposure.drawable = r->dstDrawable;
            event.u.noExposure.majorEvent = X_CopyArea; SendEvent(event);
        }
        return;
    }
    case X_ClearArea: {
        CHECK_REQUEST_SIZE_STRICT(xClearAreaReq); const auto* r = GetRequestData<xClearAreaReq>();
        auto* w = GetResourceAs<Window>(r->window); if (!w) throw BadWindowError(r->window);
        auto* d = w->GetDrawable(); if (!d) return;
        d->FillRect(r->x, r->y, r->width ? r->width : w->GetWidth() - r->x,
                    r->height ? r->height : w->GetHeight() - r->y, w->GetAttributes().back_pixel);
        if (r->exposures) w->MakeExposure(); static_cast<NativeScreen&>(server.GetScreen()).DrawableChanged(d); return;
    }
    case X_QueryKeymap: {
        xQueryKeymapReply r{}; r.length = 2; send(r); return;
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
        CHECK_REQUEST_SIZE_STRICT(xQueryBestSizeReq); const auto* q = GetRequestData<xQueryBestSizeReq>();
        xQueryBestSizeReply r{}; r.width = q->width; r.height = q->height; send(r); return;
    }
    case X_AllocColor: {
        CHECK_REQUEST_SIZE_STRICT(xAllocColorReq); const auto* q = GetRequestData<xAllocColorReq>();
        xAllocColorReply r{}; r.red = q->red; r.green = q->green; r.blue = q->blue;
        r.pixel = (q->red >> 8) << 16 | (q->green >> 8) << 8 | (q->blue >> 8); send(r); return;
    }
    case X_TranslateCoords: {
        CHECK_REQUEST_SIZE_STRICT(xTranslateCoordsReq); const auto* q = GetRequestData<xTranslateCoordsReq>();
        auto* source = GetResourceAs<Window>(q->srcWid); auto* target = GetResourceAs<Window>(q->dstWid);
        if (!source || !target) throw BadWindowError(!source ? q->srcWid : q->dstWid);
        xTranslateCoordsReply r{}; r.sameScreen = true;
        r.dstX = source->GetInRect().left + q->srcX - target->GetInRect().left;
        r.dstY = source->GetInRect().top + q->srcY - target->GetInRect().top;
        send(r); return;
    }
    case X_GetAtomName: {
        CHECK_REQUEST_SIZE_STRICT(xResourceReq); auto id = GetRequestData<xResourceReq>()->id;
        if (!Atom::IsValid(id)) throw BadAtomError(id);
        auto name = Atom::GetName(id); xGetAtomNameReply r{};
        r.type = X_Reply; r.sequenceNumber = sequence; r.nameLength = name.size(); r.length = (name.size() + 3) / 4;
        Write(&r, sizeof(r)); Write(name.data(), name.size()); WritePad((-name.size()) & 3); Flush(); return;
    }
    case X_ListExtensions: {
        const std::vector<std::string> names = {"BIG-REQUESTS", "MIT-SHM", "DRI3", "Present", "SYNC", "RANDR", "GLX"};
        std::vector<u8> bytes; for (auto& n : names) { bytes.push_back(n.size()); bytes.insert(bytes.end(), n.begin(), n.end()); }
        xListExtensionsReply r{}; r.type = X_Reply; r.sequenceNumber = sequence;
        r.nExtensions = names.size(); r.length = (bytes.size()+3)/4;
        Write(&r, sizeof(r)); Write(bytes.data(), bytes.size()); WritePad((-bytes.size()) & 3); Flush(); return;
    }
    case X_SetSelectionOwner: {
        CHECK_REQUEST_SIZE_STRICT(xSetSelectionOwnerReq); const auto* q = GetRequestData<xSetSelectionOwnerReq>();
        auto* w = q->window ? GetResourceAs<Window>(q->window) : nullptr;
        if (q->window && !w) throw BadWindowError(q->window);
        server.GetSelectionManager().SetSelection(q->selection, w, this, q->time); return;
    }
    case X_ConvertSelection: {
        CHECK_REQUEST_SIZE_STRICT(xConvertSelectionReq); const auto* q = GetRequestData<xConvertSelectionReq>();
        auto* requestor = GetResourceAs<Window>(q->requestor); if (!requestor) throw BadWindowError(q->requestor);
        auto selection = server.GetSelectionManager().GetSelection(q->selection);
        xEvent event{};
        if (selection.owner && selection.client) {
            event.u.u.type = SelectionRequest; event.u.selectionRequest.time = q->time;
            event.u.selectionRequest.owner = selection.owner->id; event.u.selectionRequest.requestor = q->requestor;
            event.u.selectionRequest.selection = q->selection; event.u.selectionRequest.target = q->target;
            event.u.selectionRequest.property = q->property; selection.client->SendEvent(event);
        } else {
            event.u.u.type = SelectionNotify; event.u.selectionNotify.time = q->time;
            event.u.selectionNotify.requestor = q->requestor; event.u.selectionNotify.selection = q->selection;
            event.u.selectionNotify.target = q->target; event.u.selectionNotify.property = None; SendEvent(event);
        }
        return;
    }
    default: throw BadRequestError();
    }
}
}


