#include <fmt/core.h>
// clang-format off
#include <X11/X.h>
#include <X11/Xproto.h>
#include <X11/extensions/bigreqsproto.h>
#include <X11/extensions/shmproto.h>
// clang-format on
#include "base/alignment.h"
#include "base/log.h"
#include "core/atom.h"
#include "core/cursor.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/graphics_context.h"
#include "core/keyboard.h"
#include "core/pixmap.h"
#include "core/pixmap_format.h"
#include "core/selection_manager.h"
#include "core/shm.h"
#include "core/visual.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xscreen.h"
#include "core/xserver.h"
#include "native_screen.h"

namespace xorg {

void XClient::ProcAuthenticateRequest() {
    CHECK_REQUEST_SIZE(xConnClientPrefix);
    const auto req = GetRequestData<xConnClientPrefix>();
    CHECK_MSG(req->byteOrder == 'l', "Unsupported byte order");
    CHECK_MSG(req->majorVersion == X_PROTOCOL, "Unsupported major version {}", req->majorVersion);
    CHECK_MSG(req->minorVersion == X_PROTOCOL_REVISION,
              "Unsupported minor version {}",
              req->minorVersion);

    constexpr auto vendor_name = "LudashiX11";

    const std::vector<PixmapFormat> supported_formats = {
            {1, 1, 32},
            {24, 32, 32},
            {32, 32, 32},
    };
    const auto root_visual = server.GetRootVisual();

    const xConnSetup setup{
            .release = 1,
            .ridBase = rid_base,
            .ridMask = server.GetResourceIDs().GetMask(),
            .motionBufferSize = 256,
            .nbytesVendor = static_cast<CARD16>(strlen(vendor_name)),
            .maxRequestSize = 65535,
            .numRoots = 1,
            .numFormats = static_cast<CARD8>(supported_formats.size()),
            .imageByteOrder = 0,
            .bitmapBitOrder = 0,
            .bitmapScanlineUnit = 32,
            .bitmapScanlinePad = 32,
            .minKeyCode = static_cast<CARD8>(server.GetKeyboard().GetKeycodeMin()),
            .maxKeyCode = static_cast<CARD8>(server.GetKeyboard().GetKeycodeMax()),
    };

    const auto root_window = server.GetWindowManager().GetRootWindow();

    const xWindowRoot root{
            .windowId = root_window->id,
            .defaultColormap = 0,
            .whitePixel = 0xffffff,
            .blackPixel = 0,
            .currentInputMask = root_window->GetAllEventMasks(),
            .pixWidth = server.GetScreenInfo().width,
            .pixHeight = server.GetScreenInfo().height,
            .mmWidth = server.GetScreenInfo().GetWidthInMillimeters(),
            .mmHeight = server.GetScreenInfo().GetHeightInMillimeters(),
            .minInstalledMaps = 1,
            .maxInstalledMaps = 1,
            .rootVisualID = root_visual->id,
            .backingStore = 0,
            .saveUnders = 0,
            .rootDepth = root_visual->depth,
            .nDepths = 1,
    };
    const size_t length = sizeof(xConnSetup) + base::AlignUp(setup.nbytesVendor, 4) +
                          setup.numFormats * sizeof(xPixmapFormat) +
                          setup.numRoots * sizeof(xWindowRoot) +
                          root.nDepths * (sizeof(xDepth) + sizeof(xVisualType));

    const xConnSetupPrefix prefix{
            .success = xTrue,
            .majorVersion = X_PROTOCOL,
            .minorVersion = X_PROTOCOL_REVISION,
            .length = static_cast<CARD16>(length / 4),
    };

    Write(&prefix, sizeof(prefix));
    Write(&setup, sizeof(setup));
    Write(vendor_name, setup.nbytesVendor);
    WritePad(base::AlignDiff(setup.nbytesVendor, 4));

    for (const auto& format : supported_formats) {
        xPixmapFormat fmt{
                .depth = format.depth,
                .bitsPerPixel = format.bpp,
                .scanLinePad = format.scanline_pad,
        };
        Write(&fmt, sizeof(fmt));
    }
    Write(&root, sizeof(root));
    const xDepth depth{
            .depth = root_visual->depth,
            .nVisuals = 1,
    };
    Write(&depth, sizeof(depth));
    const xVisualType vis{
            .visualID = root_visual->id,
            .c_class = root_visual->visual_class,
            .bitsPerRGB = root_visual->bpp,
            .colormapEntries = root_visual->colormap_entries,
            .redMask = static_cast<CARD32>(root_visual->red_mask),
            .greenMask = static_cast<CARD32>(root_visual->green_mask),
            .blueMask = static_cast<CARD32>(root_visual->blue_mask),
    };
    Write(&vis, sizeof(vis));
    Flush();
    authenticated = true;
}

void XClient::ProcNormalRequest() {
    auto lock = server.Lock();
    sequence++;
    const xReq* req = GetRequestData<xReq>();
    const auto opcode = req->reqType;
#if 0
    TRACE("[{}] Request: {} ({}) ", socket_fd, opcode, sequence);
#endif
    if (opcode >= kExtensionBase) {
        if (const auto extension = server.GetExtensionManager().GetExtension(opcode)) {
            extension->handler(this);
        } else {
            throw BadRequestError();
        }
    } else {
        switch (opcode) {
            case X_CreateWindow:
                ProcCreateWindow();
                break;
            case X_ConfigureWindow:
                ProcConfigureWindow();
                break;
            case X_GetGeometry:
                ProcGetGeometry();
                break;
            case X_GetWindowAttributes:
                ProcGetWindowAttributes();
                break;
            case X_ChangeWindowAttributes:
                ProcChangeWindowAttributes();
                break;
            case X_ReparentWindow:
                ProcReparentWindow();
                break;
            case X_MapWindow:
                ProcMapWindow();
                break;
            case X_UnmapWindow:
                ProcUnmapWindow();
                break;
            case X_DestroyWindow:
                ProcDestroyWindow();
                break;
            case X_SetInputFocus:
                ProcSetInputFocus();
                break;
            case X_SendEvent:
                ProcSendEvent();
                break;
            case X_QueryTree:
                ProcQueryTree();
                break;
            case X_InternAtom:
                ProcInternAtom();
                break;
            case X_GetProperty:
                ProcGetProperty();
                break;
            case X_ChangeProperty:
                ProcChangeProperty();
                break;
            case X_DeleteProperty:
                ProcDeleteProperty();
                break;
            case X_GetSelectionOwner:
                ProcGetSelectionOwner();
                break;
            case X_GetInputFocus:
                ProcGetInputFocus();
                break;
            case X_QueryPointer:
                ProcQueryPointer();
                break;
            case X_WarpPointer:
                ProcWarpPointer();
                break;
            case X_GrabPointer:
                ProcGrabPointer();
                break;
            case X_UngrabPointer:
                ProcUngrabPointer();
                break;
            case X_CreatePixmap:
                ProcCreatePixmap();
                break;
            case X_FreePixmap:
                ProcFreePixmap();
                break;
            case X_OpenFont:
                ProcOpenFont();
                break;
            case X_ListFonts:
                ProcListFonts();
                break;
            case X_CreateGC:
                ProcCreateGC();
                break;
            case X_ChangeGC:
                ProcChangeGC();
                break;
            case X_PolyLine:
                ProcPolyLine();
                break;
            case X_PolySegment:
                ProcPolySegment();
                break;
            case X_SetClipRectangles:
                ProcSetClipRectangles();
                break;
            case X_FreeGC:
                ProcFreeGC();
                break;
            case X_CreateColormap:
                ProcCreateColormap();
                break;
            case X_FreeColormap:
                ProcFreeColormap();
                break;
            case X_QueryExtension:
                ProcQueryExtension();
                break;
            case X_GetKeyboardMapping:
                ProcGetKeyboardMapping();
                break;
            case X_GetModifierMapping:
                ProcGetModifierMapping();
                break;
            case X_CreateGlyphCursor:
                ProcCreateGlyphCursor();
                break;
            case X_PolyFillRectangle:
                ProcPolyFillRectangle();
                break;
            case X_PutImage:
                ProcPutImage();
                break;
            case X_GetImage:
                ProcGetImage();
                break;
            case X_CreateCursor:
                ProcCreateCursor();
                break;
            case X_ForceScreenSaver:
                ProcForceScreenSaver();
                break;
            case X_GetScreenSaver:
                ProcGetScreenSaver();
                break;
            case X_SetScreenSaver:
                ProcSetScreenSaver();
                break;
            case X_Bell:
            case X_NoOperation:
                break;
            default:
                ProcCompat();
        }
    }
}

void XClient::ProcQueryExtension() {
    CHECK_REQUEST_SIZE(xQueryExtensionReq);
    const auto req = GetRequestData<xQueryExtensionReq>();
    const std::string name(reinterpret_cast<const char*>(req) + sizeof(xQueryExtensionReq),
                           req->nbytes);
    TRACE("QueryExtension: {}", name);

    xQueryExtensionReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
    };
    if (const auto extension = server.GetExtensionManager().FindExtension(name)) {
        reply.present = xTrue;
        reply.major_opcode = extension->base;
        reply.first_event = extension->event_base;
        reply.first_error = extension->error_base;
    }
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcBigRequest() {
    const auto req = GetRequestData<xBigReqEnableReq>();
    if (req->brReqType != X_BigReqEnable) {
        throw BadRequestError();
    }
    big_request = true;
    const xBigReqEnableReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = 0,
            .max_request_size = 4194303,
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcShmRequest() {
    const auto* req = GetRequestData<xReq>();
    switch (req->data) {
    case X_ShmQueryVersion: {
        xShmQueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 1; reply.minorVersion = 2; reply.pixmapFormat = ZPixmap;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_ShmAttach: {
        CHECK_REQUEST_SIZE_STRICT(xShmAttachReq); auto* r = GetRequestData<xShmAttachReq>();
        if (!server.GetShmManager().Attach(r->shmseg, r->shmid)) throw BadAccessError(); return;
    }
    case X_ShmDetach: {
        CHECK_REQUEST_SIZE_STRICT(xShmDetachReq);
        server.GetShmManager().Detach(GetRequestData<xShmDetachReq>()->shmseg); return;
    }
    case X_ShmPutImage: {
        CHECK_REQUEST_SIZE_STRICT(xShmPutImageReq); auto* r = GetRequestData<xShmPutImageReq>();
        auto& entry = server.GetShmManager().Get(r->shmseg);
        auto* d = GetDrawable(r->drawable); if (!d) throw BadDrawableError(r->drawable);
        if (!GetResourceAs<GraphicsContext>(r->gc)) throw BadGCError(r->gc);
        if ((r->depth != 24 && r->depth != 32) || r->format != ZPixmap ||
            u64(r->offset) + u64(r->totalWidth) * r->totalHeight * 4 > entry.size ||
            u32(r->srcX) + r->srcWidth > r->totalWidth || u32(r->srcY) + r->srcHeight > r->totalHeight)
            throw BadMatchError();
        static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(d, [&] {
            d->WritePixels(static_cast<const u8*>(entry.addr) + r->offset, r->srcX, r->srcY,
                           r->dstX, r->dstY, r->srcWidth, r->srcHeight, r->totalWidth);
        });
        static_cast<NativeScreen&>(server.GetScreen()).HudFrame(r->drawable);
        if (r->sendEvent) {
            xShmCompletionEvent event{};
            event.type = server.GetExtensionManager().FindExtension("MIT-SHM")->event_base;
            event.sequenceNumber = sequence; event.drawable = r->drawable;
            event.minorEvent = X_ShmPutImage; event.majorEvent = req->reqType;
            event.shmseg = r->shmseg; event.offset = r->offset;
            WriteImmediately(&event, sizeof(event));
        }
        return;
    }
    case X_ShmGetImage: {
        CHECK_REQUEST_SIZE_STRICT(xShmGetImageReq); auto* r = GetRequestData<xShmGetImageReq>();
        auto& entry = server.GetShmManager().Get(r->shmseg);
        auto* d = GetDrawable(r->drawable); if (!d) throw BadDrawableError(r->drawable);
        if (r->format != ZPixmap || u64(r->offset) + u64(r->width) * r->height * 4 > entry.size) throw BadMatchError();
        d->ReadPixels(static_cast<u8*>(entry.addr) + r->offset, r->x, r->y, r->width, r->height, r->width);
        xShmGetImageReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.depth = d->GetDepth(); reply.visual = server.GetRootVisual()->id;
        reply.size = u32(r->width) * r->height * 4; WriteImmediately(&reply, sizeof(reply)); return;
    }
    default: throw BadRequestError();
    }
}

void XClient::ProcGetProperty() {
    CHECK_REQUEST_SIZE(xGetPropertyReq);
    const auto req = GetRequestData<xGetPropertyReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    if (!Atom::IsValid(req->property)) {
        throw BadAtomError(req->property);
    }
    const auto property = window->GetProperty(req->property);
    xGetPropertyReply reply{.type = X_Reply,
                            .format = None,
                            .sequenceNumber = sequence,
                            .length = 0,
                            .propertyType = 0,
                            .bytesAfter = 0,
                            .nItems = 0};

    if (!property) {
        Write(&reply, sizeof(reply));
        Flush();
        return;
    }

    reply.format = property->format;
    reply.propertyType = property->type;

    if (property->type == req->type || req->type == 0) {
        const auto& data = property->GetData();
        const u64 offset = u64(req->longOffset) * 4;
        if (offset > data.size()) throw BadValueError(req->longOffset);
        const u32 length = static_cast<u32>(std::min<uint64_t>(uint64_t(req->longLength) * 4, data.size() - offset));
        const u32 bytes_after = data.size() - offset - length;
        reply.length = base::AlignUp(length, 4) >> 2;
        reply.bytesAfter = bytes_after;
        reply.nItems = length / (property->format / 8);
        Write(&reply, sizeof(reply));
        Write(data.data() + offset, length);
        WritePad(base::AlignDiff(length, 4));
        Flush();
    } else {
        reply.bytesAfter = property->GetData().size();
        Write(&reply, sizeof(reply));
        Flush();
    }
    if (req->c_delete && reply.bytesAfter == 0 &&
        (property->type == req->type || req->type == 0)) {
        window->DeleteProperty(req->property);
        static_cast<NativeScreen&>(server.GetScreen()).WindowPropertyChanged(window, req->property);
    }
}

void XClient::ProcChangeProperty() {
    CHECK_REQUEST_SIZE(xChangePropertyReq);
    const auto req = GetRequestData<xChangePropertyReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    if (!Atom::IsValid(req->property)) {
        throw BadAtomError(req->property);
    }
    const u32 remaining = request_size - sizeof(xChangePropertyReq);
    if (req->format != 8 && req->format != 16 && req->format != 32) throw BadValueError(req->format);
    if (req->mode > PropModeAppend) throw BadValueError(req->mode);
    const u64 total_size = u64(req->nUnits) * (req->format / 8);
    if (remaining < total_size) throw BadLengthError();
    std::vector<u8> data(total_size);
    memcpy(data.data(), req + 1, total_size);

    if (!window->ModifyProperty(req->property, req->type, req->format, req->mode, data)) {
        throw BadMatchError();
    }
    static_cast<NativeScreen&>(server.GetScreen()).WindowPropertyChanged(window, req->property);
}

void XClient::ProcDeleteProperty() {
    CHECK_REQUEST_SIZE(xDeletePropertyReq);
    const auto req = GetRequestData<xDeletePropertyReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    if (!Atom::IsValid(req->property)) {
        throw BadAtomError(req->property);
    }
    window->DeleteProperty(req->property);
    static_cast<NativeScreen&>(server.GetScreen()).WindowPropertyChanged(window, req->property);
}

void XClient::ProcCreateGC() {
    CHECK_REQUEST_SIZE(xCreateGCReq);
    const auto req = GetRequestData<xCreateGCReq>();
    const auto gc = std::make_shared<GraphicsContext>(req->gc);
    if (req->mask != 0) {
        gc->UpdateValues(req->mask, reinterpret_cast<const u32*>(req + 1));
    }
    AddResource(gc);
}

void XClient::ProcChangeGC() {
    CHECK_REQUEST_SIZE(xChangeGCReq);
    const auto req = GetRequestData<xChangeGCReq>();
    const auto res = GetResource(req->gc);
    if (!res) {
        throw BadIdChoiceError(req->gc);
    }
    const auto gc = res->As<GraphicsContext>();
    gc->UpdateValues(req->mask, reinterpret_cast<const u32*>(req + 1));
    server.GetScreen().PostInvalidate();
}

void XClient::ProcSetClipRectangles() {
    CHECK_REQUEST_SIZE(xSetClipRectanglesReq);
    const auto req = GetRequestData<xSetClipRectanglesReq>();
    const auto res = GetResource(req->gc);
    if (!res) {
        throw BadIdChoiceError(req->gc);
    }
    const auto gc = res->As<GraphicsContext>();
    const xRectangle* rectangles = reinterpret_cast<xRectangle*>(req + 1);
    const u32 size = request_size - sizeof(xSetClipRectanglesReq);
    if (size & 4) {
        throw BadLengthError();
    }
    const auto count = size / sizeof(xRectangle);
    gc->SetClipRectangles(req->xOrigin, req->yOrigin, rectangles, count);
}

void XClient::ProcFreeGC() {
    const auto req = GetRequestData<xResourceReq>();
    const auto res = GetResource(req->id);
    if (!res) {
        throw BadIdChoiceError(req->id);
    }
    const auto gc = res->As<GraphicsContext>();
    RemoveResource(gc->id);
}

void XClient::ProcCreateColormap() {
    const auto req = GetRequestData<xCreateColormapReq>();
    (void)req;
}

void XClient::ProcFreeColormap() {}

void XClient::ProcInternAtom() {
    const auto req = GetRequestData<xInternAtomReq>();
    const std::string name(reinterpret_cast<const char*>(req + 1), req->nbytes);

    const s32 id = req->onlyIfExists ? Atom::GetID(name) : Atom::Intern(name);
    if (id < 0) {
        throw BadAllocError();
    }
    const xInternAtomReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = 0,
            .atom = static_cast<CARD32>(id),
    };
    Write(&reply, sizeof(reply));
    Flush();
}

void XClient::ProcQueryTree() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    const xQueryTreeReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = static_cast<CARD32>(window->GetChildren().size()),
            .root = server.GetWindowManager().GetRootWindow()->id,
            .parent = static_cast<CARD32>(window->GetParent() ? window->GetParent()->id : None),
            .nChildren = static_cast<CARD16>(window->GetChildren().size()),
    };
    Write(&reply, sizeof(reply));
    for (const auto& child : window->GetChildren()) {
        const auto id = child->id;
        Write(&id, sizeof(id));
    }
    Flush();
}

void XClient::ProcGetKeyboardMapping() {
    CHECK_REQUEST_SIZE(xGetKeyboardMappingReq);
    const auto req = GetRequestData<xGetKeyboardMappingReq>();
    const auto& keyboard = server.GetKeyboard();
    if (req->firstKeyCode < keyboard.GetKeycodeMin() ||
        req->firstKeyCode > keyboard.GetKeycodeMax()) {
        throw BadValueError(req->firstKeyCode);
    }
    if (req->firstKeyCode + req->count > keyboard.GetKeycodeMax() + 1) {
        throw BadValueError(req->count);
    }
    const xGetKeyboardMappingReply reply{
            .type = X_Reply,
            .keySymsPerKeyCode = static_cast<CARD8>(keyboard.GetKeysymsPerKeycode()),
            .sequenceNumber = sequence,
            .length = keyboard.GetKeysymsPerKeycode() * req->count,
    };
    Write(&reply, sizeof(reply));
    const auto& keysyms = keyboard.GetKeysyms();
    const auto index = req->firstKeyCode - keyboard.GetKeycodeMin();
    Write(keysyms.data() + index * keyboard.GetKeysymsPerKeycode(),
          req->count * keyboard.GetKeysymsPerKeycode() * 4);
    Flush();
}

void XClient::ProcGetModifierMapping() {
    xGetModifierMappingReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
    reply.numKeyPerModifier = 2; reply.length = 4;
    const u8 keys[] = {50,62, 66,0, 37,105, 64,108, 77,0, 0,0, 133,134, 0,0};
    Write(&reply, sizeof(reply)); Write(keys, sizeof(keys)); Flush();
}

void XClient::ProcQueryPointer() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    const auto& wm = server.GetWindowManager();
    const auto& mouse = server.GetMouse();
    const auto root = wm.GetRootWindow();
    const s16 rx = std::clamp<s16>(mouse.GetX(), 0, static_cast<s16>(root->GetWidth() - 1));
    const s16 ry = std::clamp<s16>(mouse.GetY(), 0, static_cast<s16>(root->GetHeight() - 1));
    const s16 wx = rx - window->GetInRect().left;
    const s16 wy = ry - window->GetInRect().top;
    const auto point_window = root->WindowAtPoint(rx, ry);
    const auto child = point_window->GetParent() == window ? point_window : nullptr;

    const xQueryPointerReply reply{
            .type = X_Reply,
            .sameScreen = true,
            .sequenceNumber = sequence,
            .length = 0,
            .root = root->id,
            .child = static_cast<CARD32>(child ? child->id : None),
            .rootX = rx,
            .rootY = ry,
            .winX = wx,
            .winY = wy,
            .mask = static_cast<CARD16>(wm.GetButtonMasks()),
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcGrabPointer() {
    CHECK_REQUEST_SIZE(xGrabPointerReq);
    const auto req = GetRequestData<xGrabPointerReq>();
    const auto window = GetResourceAs<Window>(req->grabWindow);
    if (!window) {
        throw BadWindowError(req->grabWindow);
    }
    Window* confine_window{};
    if (req->confineTo != None) {
        confine_window = GetResourceAs<Window>(req->confineTo);
        if (!confine_window) {
            throw BadWindowError(req->confineTo);
        }
    }
    Cursor* cursor{};
    if (req->cursor != None) {
        cursor = GetResourceAs<Cursor>(req->cursor);
        if (!cursor) {
            throw BadCursorError(req->cursor);
        }
    }
    auto& wm = server.GetWindowManager();
    const u32 status = wm.GrabPointer(this,
                                      window,
                                      confine_window,
                                      cursor,
                                      req->time,
                                      req->eventMask,
                                      req->ownerEvents,
                                      req->pointerMode == GrabModeSync,
                                      req->keyboardMode == GrabModeSync);
    const xGrabPointerReply reply{
            .type = X_Reply,
            .status = static_cast<CARD8>(status),
            .sequenceNumber = sequence,
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcUngrabPointer() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    auto& wm = server.GetWindowManager();
    wm.UngrabPointer(this, req->id);
}

void XClient::ProcCreatePixmap() {
    CHECK_REQUEST_SIZE(xCreatePixmapReq);
    const auto req = GetRequestData<xCreatePixmapReq>();
    CheckResourceID(req->pid);
    const auto drawable = GetResource(req->drawable);
    if (!drawable || !drawable->IsDrawable()) {
        throw BadDrawableError(req->drawable);
    }
    const auto pixmap = std::make_shared<Pixmap>(
            server.GetScreen(), req->pid, req->width, req->height, req->depth);
    AddResource(pixmap);
}

void XClient::ProcFreePixmap() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto pixmap = GetResource(req->id);
    if (!pixmap) {
        throw BadIdChoiceError(req->id);
    }
    RemoveResource(req->id);
}

void XClient::ProcGetInputFocus() {
    const auto& wm = server.GetWindowManager();
    const Window* focuse_window = wm.GetFocuseWindow();
    u32 wid = None;
    if (focuse_window) {
        wid = focuse_window->IsRoot() ? PointerRoot : focuse_window->id;
    }
    const xGetInputFocusReply reply{
            .type = X_Reply,
            .revertTo = wm.GetFocusRevertTo(),
            .sequenceNumber = sequence,
            .length = 0,
            .focus = wid,
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcOpenFont() {
    CHECK_REQUEST_SIZE(xOpenFontReq);
    const auto req = GetRequestData<xOpenFontReq>();
    const std::string name(reinterpret_cast<const char*>(req + 1), req->nbytes);
    TRACE("OpenFont: {}", name);
}

void XClient::ProcListFonts() {
    CHECK_REQUEST_SIZE(xListFontsReq);
    const auto req = GetRequestData<xListFontsReq>();
    const std::string pattern(reinterpret_cast<const char*>(req + 1), req->nbytes);

    const xListFontsReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = 0,
            .nFonts = 0,
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcCreateWindow() {
    CHECK_REQUEST_SIZE(xCreateWindowReq);
    const auto req = GetRequestData<xCreateWindowReq>();
    CheckResourceID(req->wid);
    auto& wm = server.GetWindowManager();
    Window* parent = GetResourceAs<Window>(req->parent);
    if (!parent) {
        throw BadWindowError(req->parent);
    }
    const auto window = wm.CreateWindow(this,
                                        req->wid,
                                        parent,
                                        req->x,
                                        req->y,
                                        req->width,
                                        req->height,
                                        req->borderWidth,
                                        req->c_class,
                                        req->depth,
                                        req->mask,
                                        reinterpret_cast<const u32*>(req + 1));
    if (!window) {
        throw BadAllocError();
    }
}

void XClient::ProcChangeWindowAttributes() {
    CHECK_REQUEST_SIZE(xChangeWindowAttributesReq);
    const auto req = GetRequestData<xChangeWindowAttributesReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    if (req->valueMask != 0) {
        server.GetWindowManager().ChangeWindowAttributes(
                this, window, req->valueMask, reinterpret_cast<const u32*>(req + 1));
    }
}

void XClient::ProcGetWindowAttributes() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    const auto& attributes = window->GetAttributes();
    u32 your_event_mask = 0;
    if (const auto it = event_windows.find(window); it != event_windows.end()) {
        your_event_mask = it->second;
    }

    const xGetWindowAttributesReply reply{
            .type = X_Reply,
            .backingStore = static_cast<CARD8>(attributes.backing_store),
            .sequenceNumber = sequence,
            .length = (sizeof(xGetWindowAttributesReply) - sizeof(xGenericReply)) / 4,
            .visualID = server.GetRootVisual()->id,
            .c_class = static_cast<CARD16>(window->IsInputOnly() ? InputOnly : InputOutput),
            .bitGravity = static_cast<CARD8>(attributes.bit_gravity),
            .winGravity = static_cast<CARD8>(attributes.win_gravity),
            .backingBitPlanes = attributes.backing_planes,
            .backingPixel = attributes.backing_pixel,
            .saveUnder = attributes.save_under,
            .mapInstalled = 1,
            .mapState = static_cast<CARD8>(window->IsMapped() ? IsViewable : IsUnviewable),
            .override = attributes.override_redirect,
            .colormap = 0,
            .allEventMasks = window->GetAllEventMasks(),
            .yourEventMask = your_event_mask,
            .doNotPropagateMask = static_cast<CARD16>(attributes.dont_propagate_mask),
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcConfigureWindow() {
    CHECK_REQUEST_SIZE(xConfigureWindowReq);
    const auto req = GetRequestData<xConfigureWindowReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    if (req->mask & ~u16(CWX | CWY | CWWidth | CWHeight | CWBorderWidth | CWSibling | CWStackMode))
        throw BadValueError(req->mask);
    if (request_size != sizeof(*req) + std::popcount(req->mask) * 4u) throw BadLengthError();
    if (window->IsRoot()) return;
    server.GetWindowManager().ConfigureWindow(
            this, window, req->mask, reinterpret_cast<const u32*>(req + 1));
}

void XClient::ProcGetGeometry() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }

    const auto in_rect = window->GetInRect();
    const auto out_rect = window->GetOutRect();
    const auto width = in_rect.right - in_rect.left;
    const auto height = in_rect.bottom - in_rect.top;

    s16 x, y;
    if (const auto parent = window->GetParent(); parent) {
        x = out_rect.left - parent->GetInRect().left;
        y = out_rect.top - parent->GetInRect().top;
    } else {
        x = out_rect.left;
        y = out_rect.top;
    }

    const xGetGeometryReply reply{
            .type = X_Reply,
            .depth = window->GetDepth(),
            .sequenceNumber = sequence,
            .length = 0,
            .root = server.GetWindowManager().GetRootWindow()->id,
            .x = x,
            .y = y,
            .width = static_cast<u16>(width),
            .height = static_cast<u16>(height),
            .borderWidth = window->GetBorderWidth(),
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcReparentWindow() {
    CHECK_REQUEST_SIZE(xReparentWindowReq);
    const auto req = GetRequestData<xReparentWindowReq>();
    const auto window = GetResourceAs<Window>(req->window);
    if (!window) {
        throw BadWindowError(req->window);
    }
    const auto parent = GetResourceAs<Window>(req->parent);
    if (!parent) {
        throw BadWindowError(req->parent);
    }
    window->Reparent(this, parent, req->x, req->y);
}

void XClient::ProcMapWindow() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    server.GetWindowManager().MapWindow(this, window);
}

void XClient::ProcUnmapWindow() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    server.GetWindowManager().UnmapWindow(window);
}

void XClient::ProcDestroyWindow() {
    CHECK_REQUEST_SIZE(xResourceReq);
    const auto req = GetRequestData<xResourceReq>();
    const auto window = GetResourceAs<Window>(req->id);
    if (!window) {
        throw BadWindowError(req->id);
    }
    server.GetWindowManager().DestroyWindow(window, true);
}

void XClient::ProcSendEvent() {
    CHECK_REQUEST_SIZE(xSendEventReq);
    const auto req = GetRequestData<xSendEventReq>();
    const auto destination = GetResourceAs<Window>(req->destination);
    if (!destination) {
        throw BadWindowError(req->destination);
    }
    if (req->eventMask == 0) {
        if (auto* client = destination->GetClient()) client->SendEvent(req->event);
    } else {
        destination->ForeachEventListeners(req->eventMask,
                                           [&](XClient* client) { client->SendEvent(req->event); });
    }
}

void XClient::ProcGetSelectionOwner() {
    const auto req = GetRequestData<xResourceReq>();
    const auto selection = server.GetSelectionManager().GetSelection(req->id);
    const xGetSelectionOwnerReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = 0,
            .owner = static_cast<CARD32>(selection.owner ? selection.owner->id : None),
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcCreateGlyphCursor() {
    CHECK_REQUEST_SIZE(xCreateGlyphCursorReq);
    const auto req = GetRequestData<xCreateGlyphCursorReq>();

    const auto encode_rgb = [](const u8 r, const u8 g, const u8 b) -> u32 {
        return (r << 16) | (g << 8) | b;
    };
    const u32 fg_color = encode_rgb(req->foreRed, req->foreGreen, req->foreBlue);
    const u32 bg_color = encode_rgb(req->backRed, req->backGreen, req->backBlue);

    const auto cursor = std::make_shared<Cursor>(
            server.GetScreen(), req->cid, req->sourceChar, req->maskChar, fg_color, bg_color);
    AddResource(cursor);
}

void XClient::ProcSetInputFocus() {
    CHECK_REQUEST_SIZE_STRICT(xSetInputFocusReq);
    const auto* req = GetRequestData<xSetInputFocusReq>();
    auto& wm = server.GetWindowManager();
    auto* window = req->focus == PointerRoot ? wm.GetRootWindow() : GetResourceAs<Window>(req->focus);
    if (req->focus != None && !window) throw BadWindowError(req->focus);
    wm.SetInputFocus(window, req->revertTo, req->time);
}

void XClient::ProcPolyFillRectangle() {
    CHECK_REQUEST_SIZE(xPolyFillRectangleReq);
    const auto req = GetRequestData<xPolyFillRectangleReq>();
    const auto drawable = GetDrawable(req->drawable);
    if (!drawable) {
        throw BadDrawableError(req->drawable);
    }
    const auto gc = GetResourceAs<GraphicsContext>(req->gc);
    if (!gc) {
        throw BadIdChoiceError(req->gc);
    }
    const u32 count = (request_size - sizeof(xPolyFillRectangleReq)) / sizeof(xRectangle);
    const xRectangle* rectangles = reinterpret_cast<xRectangle*>(req + 1);
    static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(drawable, [&] {
        for (u32 i = 0; i < count; i++) {
            const u32 background = gc->GetForeground() | 0xff000000;
            drawable->FillRect(rectangles[i].x,
                               rectangles[i].y,
                               rectangles[i].width,
                               rectangles[i].height,
                               background);
        }
    });
}

void XClient::ProcPolyLine() {
    CHECK_REQUEST_SIZE(xPolyLineReq);
    const auto req = GetRequestData<xPolyLineReq>();
    const auto drawable = GetDrawable(req->drawable);
    if (!drawable) {
        throw BadDrawableError(req->drawable);
    }
    const auto gc = GetResourceAs<GraphicsContext>(req->gc);
    if (!gc) {
        throw BadIdChoiceError(req->gc);
    }
    const auto points = reinterpret_cast<const Point*>(req + 1);
    const u32 count = (request_size - sizeof(xPolyLineReq)) / sizeof(xPoint);
    static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(drawable, [&] {
        drawable->DrawLines(points, count, gc->GetForeground() | 0xff000000, gc->GetLineWidth());
    });
}

void XClient::ProcPolySegment() {
    CHECK_REQUEST_SIZE(xPolySegmentReq);
    const auto req = GetRequestData<xPolySegmentReq>();
    const auto drawable = GetDrawable(req->drawable);
    if (!drawable) {
        throw BadDrawableError(req->drawable);
    }
    const auto gc = GetResourceAs<GraphicsContext>(req->gc);
    if (!gc) {
        throw BadIdChoiceError(req->gc);
    }
    const auto segments = reinterpret_cast<const PolySegment*>(req + 1);
    const u32 count = (request_size - sizeof(xPolySegmentReq)) / sizeof(xSegment);
    static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(drawable, [&] {
        drawable->DrawPolySegments(
                segments, count, gc->GetForeground() | 0xff000000, gc->GetLineWidth());
    });
}

void XClient::ProcPutImage() {
    CHECK_REQUEST_SIZE(xPutImageReq);
    const auto req = GetRequestData<xPutImageReq>();
    const auto drawable = GetDrawable(req->drawable);
    if (!drawable) {
        throw BadDrawableError(req->drawable);
    }
    const auto gc = GetResourceAs<GraphicsContext>(req->gc);
    if (!gc) {
        throw BadIdChoiceError(req->gc);
    }
    const u8* data = reinterpret_cast<u8*>(req + 1);
    const auto data_len = request_size - sizeof(xPutImageReq);
    if (req->format == XYBitmap) {
        if (drawable->GetDepth() != 1) {
            throw BadMatchError();
        }
    } else if (req->format == XYPixmap) {
        if (drawable->GetDepth() != req->depth) {
            throw BadMatchError();
        }
    } else if (req->format == ZPixmap) {
        if (drawable->GetDepth() != req->depth || req->leftPad != 0) {
            throw BadMatchError();
        }
    }

    u32 depth = req->depth;
    if (req->format == XYBitmap || req->format == XYPixmap) {
        depth = drawable->GetDepth();
    }
    static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(drawable, [&] {
        if (req->format == ZPixmap || req->format == XYBitmap || req->format == XYPixmap) {
            if (depth == 32 || depth == 24) {
                ASSERT(req->width * req->height * 4 <= data_len);
                drawable->WritePixels(
                        data, 0, 0, req->dstX, req->dstY, req->width, req->height, req->width);
            } else if (depth == 1) {
                if (size_t((req->width + 31) / 32) * 4 * req->height > data_len) throw BadLengthError();
                drawable->WriteBinaryPixels(data, req->width, req->height,
                        req->format == XYBitmap ? gc->GetForeground() : 1,
                        req->format == XYBitmap ? gc->GetBackground() : 0);
            } else {
                throw BadMatchError();
            }
        } else {
            throw BadMatchError();
        }
    });
    static_cast<NativeScreen&>(server.GetScreen()).HudFrame(req->drawable);
}

void XClient::ProcGetImage() {
    CHECK_REQUEST_SIZE(xGetImageReq);
    const auto req = GetRequestData<xGetImageReq>();
    const auto drawable = GetDrawable(req->drawable);
    if (!drawable) {
        throw BadDrawableError(req->drawable);
    }
    CHECK_MSG(req->format == ZPixmap, "Unsupported image format: {}", req->format);

    const size_t data_size = req->width * req->height * 4;
    std::vector<u8> image(data_size);
    drawable->ReadPixels(image.data(), req->x, req->y, req->width, req->height, req->width);

    const xGetImageReply reply{
            .type = X_Reply,
            .depth = drawable->GetDepth(),
            .sequenceNumber = sequence,
            .length = static_cast<CARD32>(image.size() / 4),
            .visual = server.GetRootVisual()->id,
    };
    Write(&reply, sizeof(reply));
    Write(image.data(), image.size());
    Flush();
}

void XClient::ProcCreateCursor() {
    CHECK_REQUEST_SIZE(xCreateCursorReq);
    const auto req = GetRequestData<xCreateCursorReq>();
    const auto source = GetResourceAs<Pixmap>(req->source);
    if (!source) {
        throw BadPixmapError(req->source);
    }
    Pixmap* mask{};
    if (req->mask != None) {
        mask = GetResourceAs<Pixmap>(req->mask);
        if (!mask) {
            throw BadPixmapError(req->mask);
        }
    }
    CheckResourceID(req->cid);
    if (GetResource(req->cid)) throw BadIdChoiceError(req->cid);
    if (source->GetDrawable()->GetDepth() != 1 ||
        (mask && (mask->GetDrawable()->GetDepth() != 1 || mask->width != source->width || mask->height != source->height)) ||
        req->x >= source->width || req->y >= source->height) throw BadMatchError();
    const auto encode_rgb = [](u16 r, u16 g, u16 b) -> u32 {
        return 0xff000000u | (u32(r >> 8) << 16) | (u32(g >> 8) << 8) | (b >> 8);
    };
    const u32 fg_color = encode_rgb(req->foreRed, req->foreGreen, req->foreBlue);
    const u32 bg_color = encode_rgb(req->backRed, req->backGreen, req->backBlue);

    const auto cursor = std::make_shared<Cursor>(
            server.GetScreen(), req->cid, req->x, req->y, source, mask, fg_color, bg_color);
    AddResource(cursor);
}

void XClient::ProcForceScreenSaver() {}

void XClient::ProcGetScreenSaver() {
    const xGetScreenSaverReply reply{
            .type = X_Reply,
            .sequenceNumber = sequence,
            .length = 0,
            .timeout = 600,
            .interval = 600,
            .preferBlanking = xTrue,
            .allowExposures = xTrue,
    };
    WriteImmediately(&reply, sizeof(reply));
}

void XClient::ProcSetScreenSaver() {}

void XClient::ProcWarpPointer() {
    CHECK_REQUEST_SIZE(xWarpPointerReq);
    const auto req = GetRequestData<xWarpPointerReq>();
    auto& wm = server.GetWindowManager();
    const auto src_window = GetResourceAs<Window>(req->srcWid);
    const auto dst_window = GetResourceAs<Window>(req->dstWid);
    if (req->srcWid != None && !src_window) {
        throw BadWindowError(req->srcWid);
    }
    if (req->dstWid != None && !dst_window) {
        throw BadWindowError(req->dstWid);
    }
    auto& mouse = server.GetMouse();
    s32 x, y;
    if (dst_window) {
        x = dst_window->GetInRect().left + req->dstX;
        y = dst_window->GetInRect().top + req->dstY;
    } else {
        x = mouse.GetX() + req->dstX;
        y = mouse.GetY() + req->dstY;
    }
    if (src_window) {
        s32 sx = req->srcX, sy = req->srcY;
        u32 width = req->srcWidth, height = req->srcHeight;
        sx += src_window->GetInRect().left;
        sy += src_window->GetInRect().top;
        if (req->srcWidth == 0) {
            width = src_window->GetInRect().right - sx;
        }
        if (req->srcHeight == 0) {
            height = src_window->GetInRect().bottom - sy;
        }
        if (x < sx || x >= sx + width || y < sy || y >= sy + height) {
            LOGE("WarpPointer: Destination is outside of source window");
        }
    }

    mouse.WarpTo(x, y);
}

}  // namespace xorg

