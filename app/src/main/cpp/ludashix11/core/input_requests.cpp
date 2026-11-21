#include "core/client_session.h"

#include <algorithm>
#include "core/pointer_sprite.h"
#include "core/key_state.h"
#include "core/pointer_input.h"
#include "core/window_node.h"
#include "core/window_tree.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

void CheckGrabModes(u8 ownerEvents, u8 pointerMode, u8 keyboardMode) {
    if (ownerEvents > 1) throw BadValueError(ownerEvents);
    if (pointerMode > GrabModeAsync) throw BadValueError(pointerMode);
    if (keyboardMode > GrabModeAsync) throw BadValueError(keyboardMode);
}

} // namespace

void ClientSession::ApplyKeyboardCommand(u8 opcode) {
    switch (opcode) {
    case X_GetKeyboardMapping: {
        const auto& request = *GetRequestData<xGetKeyboardMappingReq>();
        const auto& keyboard = server.Keys();
        if (request.firstKeyCode < keyboard.GetKeycodeMin()) throw BadValueError(request.firstKeyCode);
        if (u32{request.firstKeyCode} + request.count > keyboard.GetKeycodeMax() + 1)
            throw BadValueError(request.count);
        const size_t perKey = keyboard.GetKeysymsPerKeycode();
        const size_t offset = (request.firstKeyCode - keyboard.GetKeycodeMin()) * perKey;
        xGetKeyboardMappingReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.keySymsPerKeyCode = perKey;
        reply.length = request.count * perKey;
        Write(&reply, sizeof(reply));
        Write(keyboard.GetKeysyms().data() + offset, reply.length * sizeof(u32));
        Flush();
        return;
    }
    case X_GetModifierMapping: {
        constexpr u8 keys[8][2] = {{50, 62}, {66, 0}, {37, 105}, {64, 108},
                                  {77, 0}, {0, 0}, {133, 134}, {0, 0}};
        xGetModifierMappingReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.numKeyPerModifier = 2;
        reply.length = sizeof(keys) / 4;
        Write(&reply, sizeof(reply));
        Write(keys, sizeof(keys));
        Flush();
        return;
    }
    case X_GetInputFocus: {
        auto& manager = server.Windows();
        const auto* window = manager.FocusedWindow();
        xGetInputFocusReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.revertTo = manager.GetFocusRevertTo();
        reply.focus = !window ? None : window->IsRoot() ? PointerRoot : window->id;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_SetInputFocus: {
        const auto& request = *GetRequestData<xSetInputFocusReq>();
        auto& manager = server.Windows();
        auto* window = request.focus == PointerRoot ? manager.GetRootWindow() : GetResourceAs<WindowNode>(request.focus);
        if (request.focus && !window) throw BadWindowError(request.focus);
        manager.SetInputFocus(window, request.revertTo, request.time);
        return;
    }
    case X_GrabKeyboard: {
        if (request_size != sizeof(xGrabKeyboardReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xGrabKeyboardReq>();
        CheckGrabModes(request.ownerEvents, request.pointerMode, request.keyboardMode);
        auto* window = GetResourceAs<WindowNode>(request.grabWindow);
        if (!window) throw BadWindowError(request.grabWindow);
        xGrabKeyboardReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.status = server.Windows().GrabKeyboard(this, window, request.time, request.ownerEvents,
                                                            request.pointerMode, request.keyboardMode);
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_UngrabKeyboard: {
        if (request_size != sizeof(xResourceReq)) throw BadLengthError();
        server.Windows().UngrabKeyboard(this, GetRequestData<xResourceReq>()->id);
        return;
    }
    default:
        throw BadRequestError();
    }
}

void ClientSession::ApplyPointerCommand(u8 opcode) {
    switch (opcode) {
    case X_QueryPointer: {
        const auto id = GetRequestData<xResourceReq>()->id;
        auto* window = GetResourceAs<WindowNode>(id);
        if (!window) throw BadWindowError(id);
        auto& manager = server.Windows();
        auto* root = manager.GetRootWindow();
        const auto [pointerX, pointerY] = manager.GetPointerPosition();
        const auto x = std::clamp<s32>(pointerX, 0, root->GetWidth() - 1);
        const auto y = std::clamp<s32>(pointerY, 0, root->GetHeight() - 1);
        const auto* child = root->WindowAtPoint(x, y);
        while (child && child->GetParent() != window) child = child->GetParent();
        xQueryPointerReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.sameScreen = xTrue;
        reply.root = root->id;
        reply.child = child ? child->id : None;
        reply.rootX = x;
        reply.rootY = y;
        reply.winX = x - window->ContentBounds().left;
        reply.winY = y - window->ContentBounds().top;
        reply.mask = manager.GetButtonMasks();
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_GrabPointer: {
        if (request_size != sizeof(xGrabPointerReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xGrabPointerReq>();
        constexpr u32 pointerEvents = ButtonPressMask | ButtonReleaseMask | EnterWindowMask | LeaveWindowMask |
            PointerMotionMask | PointerMotionHintMask | ButtonMotionMask | Button1MotionMask | Button2MotionMask |
            Button3MotionMask | Button4MotionMask | Button5MotionMask | KeymapStateMask;
        CheckGrabModes(request.ownerEvents, request.pointerMode, request.keyboardMode);
        if (request.eventMask & ~pointerEvents) throw BadValueError(request.eventMask);
        auto* window = GetResourceAs<WindowNode>(request.grabWindow);
        if (!window) throw BadWindowError(request.grabWindow);
        auto* confined = request.confineTo ? GetResourceAs<WindowNode>(request.confineTo) : nullptr;
        if (request.confineTo && !confined) throw BadWindowError(request.confineTo);
        auto* cursor = request.cursor ? GetResourceAs<PointerSprite>(request.cursor) : nullptr;
        if (request.cursor && !cursor) throw BadCursorError(request.cursor);
        xGrabPointerReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.status = server.Windows().GrabPointer(
            this, window, confined, cursor, request.time, request.eventMask, request.ownerEvents,
            request.pointerMode, request.keyboardMode);
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_UngrabPointer: {
        if (request_size != sizeof(xResourceReq)) throw BadLengthError();
        server.Windows().UngrabPointer(this, GetRequestData<xResourceReq>()->id);
        return;
    }
    case X_AllowEvents: {
        if (request_size != sizeof(xAllowEventsReq)) throw BadLengthError();
        const auto& request = *GetRequestData<xAllowEventsReq>();
        if (request.mode > SyncBoth) throw BadValueError(request.mode);
        server.Windows().AllowEvents(this, request.mode, request.time);
        return;
    }
    case X_WarpPointer: {
        const auto& request = *GetRequestData<xWarpPointerReq>();
        const auto* source = request.srcWid ? GetResourceAs<WindowNode>(request.srcWid) : nullptr;
        const auto* destination = request.dstWid ? GetResourceAs<WindowNode>(request.dstWid) : nullptr;
        if (request.srcWid && !source) throw BadWindowError(request.srcWid);
        if (request.dstWid && !destination) throw BadWindowError(request.dstWid);
        auto& mouse = server.PointerDevice();
        const auto [pointerX, pointerY] = server.Windows().GetPointerPosition();
        if (source) {
            const auto area = source->ContentBounds();
            const s32 left = area.left + request.srcX;
            const s32 top = area.top + request.srcY;
            const s32 right = request.srcWidth ? left + request.srcWidth : area.right;
            const s32 bottom = request.srcHeight ? top + request.srcHeight : area.bottom;
            if (pointerX < left || pointerX >= right || pointerY < top || pointerY >= bottom)
                return;
        }
        const s32 originX = destination ? destination->ContentBounds().left : pointerX;
        const s32 originY = destination ? destination->ContentBounds().top : pointerY;
        mouse.WarpTo(originX + request.dstX, originY + request.dstY);
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
