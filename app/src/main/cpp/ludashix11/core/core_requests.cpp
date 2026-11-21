#include "native_screen.h"
#include "core/client_session.h"

#include <bit>
#include <X11/extensions/bigreqsproto.h>
#include "core/pointer_sprite.h"
#include "native_drawable.h"
#include "core/paint_state.h"
#include "core/key_state.h"
#include "core/offscreen_image.h"
#include "core/window_node.h"
#include "core/window_tree.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

void CheckValues(size_t bytes, size_t header, u32 mask, u32 allowed) {
    if (mask & ~allowed) throw BadValueError(mask);
    if (bytes != header + sizeof(u32) * std::popcount(mask)) throw BadLengthError();
}

u32 Color(u16 red, u16 green, u16 blue) {
    return 0xff000000u | u32(red >> 8) << 16 | u32(green >> 8) << 8 | (blue >> 8);
}

} // namespace

void ClientSession::ProcBigRequest() {
    const auto& request = *GetRequestData<xBigReqEnableReq>();
    if (request.brReqType != X_BigReqEnable) throw BadRequestError();
    big_request = true;
    xBigReqEnableReply reply{};
    reply.type = X_Reply;
    reply.sequenceNumber = sequence;
    reply.max_request_size = 4194303;
    WriteImmediately(&reply, sizeof(reply));
}

void ClientSession::ApplyPaintCommand(u8 opcode) {
    switch (opcode) {
    case X_CreateGC: {
        const auto& request = *GetRequestData<xCreateGCReq>();
        CheckValues(request_size, sizeof(request), request.mask, (1u << (GCLastBit + 1)) - 1);
        auto* image = GetDrawable(request.drawable);
        if (!image) throw BadDrawableError(request.drawable);
        auto context = std::make_shared<PaintState>(request.gc, image->GetDepth());
        context->UpdateValues(request.mask, reinterpret_cast<const u32*>(&request + 1));
        AddResource(context);
        return;
    }
    case X_ChangeGC: {
        const auto& request = *GetRequestData<xChangeGCReq>();
        CheckValues(request_size, sizeof(request), request.mask, (1u << (GCLastBit + 1)) - 1);
        auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        context->UpdateValues(request.mask, reinterpret_cast<const u32*>(&request + 1));
        return;
    }
    case X_CopyGC: {
        if (request_size != sizeof(xCopyGCReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xCopyGCReq>();
        auto* source = GetResourceAs<PaintState>(request.srcGC);
        auto* target = GetResourceAs<PaintState>(request.dstGC);
        if (!source || !target) throw BadGCError(!source ? request.srcGC : request.dstGC);
        target->CopyValues(*source, request.mask);
        return;
    }
    case X_FreeGC: {
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!GetResourceAs<PaintState>(id)) throw BadGCError(id);
        RemoveResource(id);
        return;
    }
    case X_SetClipRectangles: {
        const auto& request = *GetRequestData<xSetClipRectanglesReq>();
        if (request.ordering > YXBanded) throw BadValueError(request.ordering);
        const size_t bytes = request_size - sizeof(request);
        if (bytes % sizeof(xRectangle)) throw BadLengthError();
        auto* context = GetResourceAs<PaintState>(request.gc);
        if (!context) throw BadGCError(request.gc);
        context->SetClipRectangles(request.xOrigin, request.yOrigin,
                                  reinterpret_cast<const xRectangle*>(&request + 1), bytes / sizeof(xRectangle));
        return;
    }
    default:
        throw BadRequestError();
    }
}

void ClientSession::ApplyDisplayPolicyCommand(u8 opcode) {
    switch (opcode) {
    case X_CreateColormap: {
        (void)GetRequestData<xCreateColormapReq>();
        return;
    }
    case X_FreeColormap: {
        (void)GetRequestData<xResourceReq>();
        return;
    }
    case X_OpenFont: {
        const auto& request = *GetRequestData<xOpenFontReq>();
        if (request.nbytes > request_size - sizeof(request)) throw BadLengthError();
        return;
    }
    case X_ListFonts: {
        const auto& request = *GetRequestData<xListFontsReq>();
        if (request.nbytes > request_size - sizeof(request)) throw BadLengthError();
        xListFontsReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_SetScreenSaver: {
        (void)GetRequestData<xSetScreenSaverReq>();
        return;
    }
    case X_ForceScreenSaver:
        return;
    case X_GetScreenSaver: {
        xGetScreenSaverReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.timeout = 600;
        reply.interval = 600;
        reply.preferBlanking = xTrue;
        reply.allowExposures = xTrue;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    default:
        throw BadRequestError();
    }
}

void ClientSession::ApplyImageResourceCommand(u8 opcode) {
    switch (opcode) {
    case X_CreatePixmap: {
        const auto& request = *GetRequestData<xCreatePixmapReq>();
        CheckResourceID(request.pid);
        if (!GetDrawable(request.drawable)) throw BadDrawableError(request.drawable);
        if (!request.width || !request.height) throw BadValueError(0);
        // libXrender probes depth 4 and 8 pixmaps before enabling RENDER.
        // Rejecting either depth disables ARGB cursors in libXcursor.
        switch (request.depth) {
        case 1: case 4: case 8: case 24: case 32: break;
        default: throw BadValueError(request.depth);
        }
        auto image = server.GetScreen().CreateDrawable(request.width, request.height, request.depth);
        AddResource(std::make_shared<OffscreenImage>(request.pid, std::move(image)));
        return;
    }
    case X_FreePixmap: {
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!GetResourceAs<OffscreenImage>(id)) throw BadPixmapError(id);
        RemoveResource(id);
        return;
    }
    case X_CreateGlyphCursor: {
        const auto& request = *GetRequestData<xCreateGlyphCursorReq>();
        AddResource(std::make_shared<PointerSprite>(
            server.GetScreen(), request.cid, request.sourceChar, request.maskChar,
            Color(request.foreRed, request.foreGreen, request.foreBlue),
            Color(request.backRed, request.backGreen, request.backBlue), request.mask != None));
        return;
    }
    case X_CreateCursor: {
        const auto& request = *GetRequestData<xCreateCursorReq>();
        auto* source = GetResourceAs<OffscreenImage>(request.source);
        if (!source) throw BadPixmapError(request.source);
        auto* mask = request.mask ? GetResourceAs<OffscreenImage>(request.mask) : nullptr;
        if (request.mask && !mask) throw BadPixmapError(request.mask);
        AddResource(std::make_shared<PointerSprite>(
            server.GetScreen(), request.cid, request.x, request.y, *source->Image(), mask ? mask->Image().get() : nullptr,
            Color(request.foreRed, request.foreGreen, request.foreBlue),
            Color(request.backRed, request.backGreen, request.backBlue)));
        return;
    }
    default:
        throw BadRequestError();
    }
}

void ClientSession::ApplyWindowCommand(u8 opcode) {
    switch (opcode) {
    case X_CreateWindow: {
        const auto& request = *GetRequestData<xCreateWindowReq>();
        CheckValues(request_size, sizeof(request), request.mask, (CWCursor << 1) - 1);
        CheckResourceID(request.wid);
        if (GetResource(request.wid)) throw BadIdChoiceError(request.wid);
        auto* parent = GetResourceAs<WindowNode>(request.parent);
        if (!parent) throw BadWindowError(request.parent);
        if (!request.width || !request.height) throw BadValueError(0);
        auto window = server.Windows().CreateWindow(
            this, request.wid, parent, request.x, request.y, request.width, request.height,
            request.borderWidth, request.c_class, request.depth, request.mask,
            reinterpret_cast<const u32*>(&request + 1));
        if (!window) throw BadAllocError();
        return;
    }
    case X_ChangeWindowAttributes: {
        const auto& request = *GetRequestData<xChangeWindowAttributesReq>();
        CheckValues(request_size, sizeof(request), request.valueMask, (CWCursor << 1) - 1);
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        server.Windows().ChangeWindowAttributes(
            this, window, request.valueMask, reinterpret_cast<const u32*>(&request + 1));
        return;
    }
    case X_GetWindowAttributes: {
        const auto id = GetRequestData<xResourceReq>()->id;
        auto* window = GetResourceAs<WindowNode>(id);
        if (!window) throw BadWindowError(id);
        const auto& options = window->Options();
        xGetWindowAttributesReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.length = (sizeof(reply) - 32) / 4;
        reply.backingStore = options.backing.policy;
        reply.visualID = ServerID::RootVisual;
        reply.c_class = window->IsInputOnly() ? InputOnly : InputOutput;
        reply.bitGravity = options.resize.pixelGravity;
        reply.winGravity = options.resize.childGravity;
        reply.backingBitPlanes = options.backing.planeMask;
        reply.backingPixel = options.backing.fallbackPixel;
        reply.saveUnder = options.backing.saveUnder;
        reply.mapInstalled = 1;
        reply.mapState = !window->IsMapped() ? IsUnmapped
                          : window->IsAllAncestorsMapped() ? IsViewable : IsUnviewable;
        reply.override = options.bypassManager;
        reply.allEventMasks = window->SelectedEvents();
        reply.yourEventMask = window->InterestOf(this);
        reply.doNotPropagateMask = options.propagationStop;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_QueryTree: {
        const auto id = GetRequestData<xResourceReq>()->id;
        auto* window = GetResourceAs<WindowNode>(id);
        if (!window) throw BadWindowError(id);
        const auto& children = window->GetChildren();
        xQueryTreeReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.root = server.Windows().GetRootWindow()->id;
        reply.parent = window->GetParent() ? window->GetParent()->id : None;
        reply.nChildren = children.size();
        reply.length = children.size();
        Write(&reply, sizeof(reply));
        for (auto* child : children) Write(&child->id, sizeof(child->id));
        Flush();
        return;
    }
    case X_ConfigureWindow: {
        const auto& request = *GetRequestData<xConfigureWindowReq>();
        CheckValues(request_size, sizeof(request), request.mask,
                    CWX | CWY | CWWidth | CWHeight | CWBorderWidth | CWSibling | CWStackMode);
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        if (!window->IsRoot()) {
            server.Windows().ConfigureWindow(
                this, window, request.mask, reinterpret_cast<const u32*>(&request + 1));
        }
        return;
    }
    case X_GetGeometry: {
        const auto id = GetRequestData<xResourceReq>()->id;
        const auto* window = GetResourceAs<WindowNode>(id);
        const auto* drawable = GetDrawable(id);
        if (!window && !drawable) throw BadDrawableError(id);

        xGetGeometryReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.root = server.Windows().GetRootWindow()->id;
        if (window) {
            reply.depth = window->GetDepth();
            reply.width = window->GetWidth();
            reply.height = window->GetHeight();
            const auto bounds = window->FrameBounds();
            const auto* parent = window->GetParent();
            reply.x = bounds.left - (parent ? parent->ContentBounds().left : 0);
            reply.y = bounds.top - (parent ? parent->ContentBounds().top : 0);
            reply.borderWidth = window->GetBorderWidth();
        } else {
            reply.depth = drawable->GetDepth();
            reply.width = drawable->GetWidth();
            reply.height = drawable->GetHeight();
        }
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_ReparentWindow: {
        const auto& request = *GetRequestData<xReparentWindowReq>();
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        auto* parent = GetResourceAs<WindowNode>(request.parent);
        if (!parent) throw BadWindowError(request.parent);
        window->Reparent(this, parent, request.x, request.y);
        return;
    }
    case X_MapWindow:
    case X_UnmapWindow:
    case X_DestroyWindow: {
        const auto id = GetRequestData<xResourceReq>()->id;
        auto* window = GetResourceAs<WindowNode>(id);
        if (!window) throw BadWindowError(id);
        auto& tree = server.Windows();
        if (opcode == X_MapWindow) tree.MapWindow(this, window);
        else if (opcode == X_UnmapWindow) tree.UnmapWindow(window);
        else tree.DestroyWindow(window, true);
        return;
    }
    case X_DestroySubwindows:
    case X_MapSubwindows:
    case X_UnmapSubwindows: {
        if (request_size != sizeof(xResourceReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xResourceReq>();
        auto* parent = GetResourceAs<WindowNode>(request.id);
        if (!parent) throw BadWindowError(request.id);
        auto& manager = server.Windows();
        const auto& children = parent->GetChildren();
        if (opcode == X_DestroySubwindows) {
            // Each child owns an independent subtree; the parent remains valid.
            while (!children.empty()) manager.DestroyWindow(children.front(), true);
            return;
        }

        // Keep per-window X events, but publish the completed scene once.
        bool changed = false;
        if (opcode == X_MapSubwindows) {
            for (auto entry = children.rbegin(); entry != children.rend(); ++entry)
                changed |= manager.MapWindow(this, *entry, false);
        } else {
            for (auto* child : children) changed |= manager.UnmapWindow(child, false);
        }
        if (changed) server.GetScreen().PostInvalidate();
        return;
    }
    case X_SendEvent: {
        const auto& request = *GetRequestData<xSendEventReq>();
        auto* destination = GetResourceAs<WindowNode>(request.destination);
        if (!destination) throw BadWindowError(request.destination);
        auto event = request.event;
        if (!request.eventMask) {
            if (auto* recipient = destination->GetClient()) recipient->DeliverEvent(event);
            return;
        }
        destination->DispatchToSubscribers(request.eventMask, [&](ClientSession* recipient) { recipient->DeliverEvent(event); });
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
