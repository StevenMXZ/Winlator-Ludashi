#include "core/client_session.h"

#include <array>
#include <initializer_list>
#include <stdexcept>
#include <string_view>
#include "core/wire_extensions.h"
#include "core/request_error.h"
#include "core/key_state.h"
#include "core/window_node.h"
#include "core/window_tree.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

constexpr std::string_view vendor = "LudashiX11";
constexpr u8 rootDepth = 24;
constexpr xVisualType rootVisual{
    .visualID = ServerID::RootVisual,
    .c_class = TrueColor,
    .bitsPerRGB = 8,
    .colormapEntries = 256,
    .redMask = 0xff0000,
    .greenMask = 0x00ff00,
    .blueMask = 0x0000ff,
};

struct ConnectionReply {
    xConnSetupPrefix prefix;
    xConnSetup setup;
    std::array<char, 12> vendorName;
    xPixmapFormat formats[3];
    xWindowRoot root;
    xDepth depth;
    xVisualType visual;
};
static_assert(sizeof(ConnectionReply) == 148);

} // namespace

void ClientSession::SendSetupReply() {
    const auto& request = *GetRequestData<xConnClientPrefix>();
    if (request.majorVersion != X_PROTOCOL || request.minorVersion != X_PROTOCOL_REVISION)
        throw std::runtime_error("Unsupported X11 version");

    const auto& display = server.Dimensions();
    const auto& root = *server.Windows().GetRootWindow();
    const auto& keyboard = server.Keys();

    ConnectionReply reply{};
    reply.prefix.success = xTrue;
    reply.prefix.majorVersion = X_PROTOCOL;
    reply.prefix.minorVersion = X_PROTOCOL_REVISION;
    reply.prefix.length = (sizeof(reply) - sizeof(reply.prefix)) / 4;

    reply.setup.release = 1;
    reply.setup.ridBase = rid_base;
    reply.setup.ridMask = server.GetResourceIDs().GetMask();
    reply.setup.motionBufferSize = 256;
    reply.setup.nbytesVendor = vendor.size();
    reply.setup.maxRequestSize = UINT16_MAX;
    reply.setup.numRoots = 1;
    reply.setup.numFormats = std::size(reply.formats);
    reply.setup.bitmapScanlineUnit = 32;
    reply.setup.bitmapScanlinePad = 32;
    reply.setup.minKeyCode = keyboard.GetKeycodeMin();
    reply.setup.maxKeyCode = keyboard.GetKeycodeMax();

    vendor.copy(reply.vendorName.data(), reply.vendorName.size());
    reply.formats[0] = xPixmapFormat{.depth = 1, .bitsPerPixel = 1, .scanLinePad = 32};
    reply.formats[1] = xPixmapFormat{.depth = 24, .bitsPerPixel = 32, .scanLinePad = 32};
    reply.formats[2] = xPixmapFormat{.depth = 32, .bitsPerPixel = 32, .scanLinePad = 32};

    reply.root.windowId = root.id;
    reply.root.whitePixel = 0xffffff;
    reply.root.currentInputMask = root.SelectedEvents();
    reply.root.pixWidth = display.width;
    reply.root.pixHeight = display.height;
    reply.root.mmWidth = display.widthMillimetres;
    reply.root.mmHeight = display.heightMillimetres;
    reply.root.minInstalledMaps = 1;
    reply.root.maxInstalledMaps = 1;
    reply.root.rootVisualID = rootVisual.visualID;
    reply.root.rootDepth = rootDepth;
    reply.root.nDepths = 1;
    reply.depth.depth = rootDepth;
    reply.depth.nVisuals = 1;

    reply.visual = rootVisual;
    WriteImmediately(&reply, sizeof(reply));
}

void ClientSession::RouteCommand(u8 opcode) {
    using Handler = void (ClientSession::*)(u8);
    static constexpr auto core = [] {
        std::array<Handler, kExtensionBase> handlers{};
        const auto bind = [&](Handler handler, std::initializer_list<u8> opcodes) {
            for (const auto opcode : opcodes) handlers[opcode] = handler;
        };
        bind(&ClientSession::ApplyPaintCommand,
             {X_CreateGC, X_ChangeGC, X_CopyGC, X_FreeGC, X_SetClipRectangles});
        bind(&ClientSession::ApplyImageResourceCommand,
             {X_CreatePixmap, X_FreePixmap, X_CreateGlyphCursor, X_CreateCursor});
        bind(&ClientSession::ApplyWindowCommand,
             {X_CreateWindow, X_ChangeWindowAttributes, X_GetWindowAttributes, X_QueryTree,
              X_ConfigureWindow, X_GetGeometry, X_ReparentWindow,
              X_MapWindow, X_UnmapWindow, X_DestroyWindow,
              X_DestroySubwindows, X_MapSubwindows, X_UnmapSubwindows,
              X_SendEvent});
        bind(&ClientSession::ApplyDisplayPolicyCommand,
             {X_OpenFont, X_ListFonts, X_CreateColormap, X_FreeColormap, X_SetScreenSaver,
              X_GetScreenSaver, X_ForceScreenSaver});
        bind(&ClientSession::ApplyKeyboardCommand,
             {X_GetKeyboardMapping, X_GetModifierMapping, X_GetInputFocus, X_SetInputFocus,
              X_GrabKeyboard, X_UngrabKeyboard});
        bind(&ClientSession::ApplyPointerCommand,
             {X_QueryPointer, X_GrabPointer, X_UngrabPointer, X_WarpPointer, X_AllowEvents});
        bind(&ClientSession::ApplyRasterCommand,
             {X_PolyFillRectangle, X_PolyRectangle, X_PolyLine, X_PolySegment});
        bind(&ClientSession::ApplyPropertyCommand,
             {X_GetProperty, X_ChangeProperty, X_DeleteProperty});
        bind(&ClientSession::ApplySelectionCommand,
             {X_SetSelectionOwner, X_GetSelectionOwner, X_ConvertSelection});
        bind(&ClientSession::ApplyCatalogCommand,
             {X_QueryExtension, X_InternAtom, X_GetAtomName});
        handlers[X_PutImage] = &ClientSession::UploadImage;
        handlers[X_GetImage] = &ClientSession::DownloadImage;
        return handlers;
    }();

    if (opcode == X_NoOperation || opcode == X_Bell) return;
    if (opcode < core.size()) {
        const auto handler = core[opcode] ? core[opcode] : &ClientSession::ApplyCompatibilityCommand;
        (this->*handler)(opcode);
        return;
    }

    const auto* extension = WireExtensions::ForOpcode(opcode);
    if (!extension) throw BadRequestError();
    (this->*extension->handler)();
}

void ClientSession::ApplyCatalogCommand(u8 opcode) {
    switch (opcode) {
    case X_QueryExtension: {
        if (request_size < sizeof(xQueryExtensionReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xQueryExtensionReq>();
        if (request.nbytes > request_size - sizeof(request)) throw BadLengthError();

        const auto* name = reinterpret_cast<const char*>(request_buffer.data() + sizeof(request));
        const auto* extension = WireExtensions::Find({name, request.nbytes});

        xQueryExtensionReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        if (extension) {
            reply.present = xTrue;
            reply.major_opcode = extension->base;
            reply.first_event = extension->event_base;
            reply.first_error = extension->error_base;
        }
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_InternAtom: {
        const auto& request = *GetRequestData<xInternAtomReq>();
        if (request.nbytes > request_size - sizeof(request)) throw BadLengthError();

        const auto* text = reinterpret_cast<const char*>(request_buffer.data() + sizeof(request));
        const std::string_view name(text, request.nbytes);
        auto& atoms = server.Atoms();
        const auto id = request.onlyIfExists ? std::max(0, atoms.Find(name)) : atoms.Intern(name);
        xInternAtomReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.atom = id;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_GetAtomName: {
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!server.Atoms().Contains(id)) throw BadAtomError(id);

        const auto name = server.Atoms().Name(id);
        xGetAtomNameReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.nameLength = name.size();
        reply.length = (name.size() + 3) / 4;
        Write(&reply, sizeof(reply));
        Write(name.data(), name.size());
        WritePad(-name.size() & 3);
        Flush();
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
