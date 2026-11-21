#include "core/window_node.h"

#include <algorithm>
#include <vector>
#include "core/window_tree.h"
#include "core/client_session.h"

namespace ludashi::x11 {
namespace {

u32 MotionMask(u32 state) {
    constexpr u32 buttons = Button1Mask | Button2Mask | Button3Mask | Button4Mask | Button5Mask;
    constexpr u32 motionButtons = Button1MotionMask | Button2MotionMask | Button3MotionMask |
                                  Button4MotionMask | Button5MotionMask;
    u32 result = PointerMotionMask | PointerMotionHintMask;
    if (state & buttons) result |= ButtonMotionMask | (state & motionButtons);
    return result;
}

u8 MotionDetail(u32 mask) {
    return (mask & PointerMotionHintMask) && !(mask & PointerMotionMask) ? NotifyHint : NotifyNormal;
}

void SendFocus(WindowNode& window, u8 type, u32 detail, u32 mode) {
    if (!window.IsMapped() || !window.HasSubscribers(FocusChangeMask)) return;
    xEvent event{};
    event.u.u.type = type;
    event.u.u.detail = detail;
    event.u.focus.window = window.id;
    event.u.focus.mode = mode;
    window.DispatchToSubscribers(FocusChangeMask, [&](ClientSession* client) { client->DeliverEvent(event); });
}

} // namespace

void WindowNode::FocusInNotify(u32 detail, u32 mode) { SendFocus(*this, FocusIn, detail, mode); }
void WindowNode::FocusOutNotify(u32 detail, u32 mode) { SendFocus(*this, FocusOut, detail, mode); }

xEvent WindowNode::InputEvent(u8 type, u8 detail, s32 x, s32 y, u32 state, const WindowNode* child) const {
    xEvent event{};
    event.u.u.type = type;
    event.u.u.detail = detail;
    auto& data = event.u.keyButtonPointer;
    data.time = window_manager.GetInputTime();
    data.root = window_manager.GetRootWindow()->id;
    data.event = id;
    data.child = child ? child->id : None;
    data.rootX = x;
    data.rootY = y;
    data.eventX = x - ContentBounds().left;
    data.eventY = y - ContentBounds().top;
    data.state = state;
    data.sameScreen = true;
    return event;
}

void WindowNode::PointerNotify(u32 type, s32 x, s32 y, u32 detail, WindowNode* source, u32 mode) {
    const u32 mask = type == EnterNotify ? EnterWindowMask : LeaveWindowMask;
    if (!IsMapped() || !HasSubscribers(mask)) return;
    const WindowNode* child = source;
    while (child && child != this && child->parent != this) child = child->parent;
    if (child == this) child = nullptr;
    auto event = InputEvent(type, detail, x, y, window_manager.GetButtonMasks(), child);
    const auto* focus = window_manager.FocusedWindow();
    const bool focused = focus && (focus == this || focus->IsAncestorOf(this));
    event.u.enterLeave.mode = mode;
    event.u.enterLeave.flags = ELFlagSameScreen | (focused ? ELFlagFocus : 0);
    DispatchToSubscribers(mask, [&](ClientSession* client) { client->DeliverEvent(event); });
}

void WindowNode::LeaveEnterNotify(s32 x, s32 y, WindowNode* destination, u32 mode) {
    if (destination == this) return;
    auto depth = [](const WindowNode* window) {
        size_t count = 0;
        for (; window; window = window->parent) ++count;
        return count;
    };
    auto* outgoing = this;
    auto* incoming = destination;
    size_t outDepth = depth(outgoing), inDepth = depth(incoming);
    while (outDepth > inDepth) { outgoing = outgoing->parent; --outDepth; }
    while (inDepth > outDepth) { incoming = incoming->parent; --inDepth; }
    while (outgoing != incoming) { outgoing = outgoing->parent; incoming = incoming->parent; }
    const auto* common = outgoing;

    const u32 leaveDetail = common == destination ? NotifyAncestor :
                            common == this ? NotifyInferior : NotifyNonlinear;
    PointerNotify(LeaveNotify, x, y, leaveDetail, this, mode);
    const u32 intermediate = common == this || common == destination ? NotifyVirtual : NotifyNonlinearVirtual;
    if (common != this) {
        for (auto* ancestor = parent; ancestor != common; ancestor = ancestor->parent)
            ancestor->PointerNotify(LeaveNotify, x, y, intermediate, this, mode);
    }
    // The common sibling-to-sibling path has no intermediate windows and allocates nothing.
    std::vector<WindowNode*> path;
    if (common != destination) {
        for (auto* ancestor = destination->parent; ancestor != common; ancestor = ancestor->parent)
            path.push_back(ancestor);
    }
    for (auto entry = path.rbegin(); entry != path.rend(); ++entry)
        (*entry)->PointerNotify(EnterNotify, x, y, intermediate, destination, mode);
    const u32 enterDetail = common == this ? NotifyAncestor :
                            common == destination ? NotifyInferior : NotifyNonlinear;
    destination->PointerNotify(EnterNotify, x, y, enterDetail, destination, mode);
}

WindowNode* WindowNode::FindEventWindow(u32 mask, WindowNode*& child, const WindowNode* boundary) {
    child = nullptr;
    for (auto* candidate = this; candidate; candidate = candidate->parent) {
        if (candidate->IsMapped() && candidate->HasSubscribers(mask)) return candidate;
        if (candidate == boundary || (candidate->options.propagationStop & mask)) break;
        child = candidate;
    }
    return nullptr;
}

WindowNode* WindowNode::DeliverInput(u8 type, u8 detail, s32 x, s32 y, u32 state, u32 mask,
                             ClientSession* excluded, ClientSession** recipient, const WindowNode* boundary) {
    WindowNode* child = nullptr;
    auto* target = FindEventWindow(mask, child, boundary);
    if (!target) return nullptr;
    if (type == MotionNotify) detail = MotionDetail(target->SelectedEvents());
    auto event = target->InputEvent(type, detail, x, y, state, child);
    bool delivered = false;
    target->DispatchToSubscribers(mask, [&](ClientSession* client) {
        if (client == excluded) return;
        client->DeliverEvent(event);
        delivered = true;
        if (recipient) *recipient = client;
    });
    return delivered ? target : nullptr;
}

void WindowNode::PointerMotionNotify(s32 x, s32 y, u32 state, ClientSession* excluded) {
    DeliverInput(MotionNotify, NotifyNormal, x, y, state, MotionMask(state), excluded);
}

WindowNode* WindowNode::ButtonNotify(bool down, s32 x, s32 y, u32 button, ClientSession* excluded, ClientSession** recipient) {
    return DeliverInput(down ? ButtonPress : ButtonRelease, button, x, y, window_manager.GetInputState(),
                        down ? ButtonPressMask : ButtonReleaseMask, excluded, recipient);
}

bool WindowNode::KeyNotify(bool down, s32 x, s32 y, u32 key, ClientSession* excluded, const WindowNode* boundary) {
    return DeliverInput(down ? KeyPress : KeyRelease, key, x, y, window_manager.GetInputState(),
                        down ? KeyPressMask : KeyReleaseMask, excluded, nullptr, boundary) != nullptr;
}

bool WindowNode::RouteGrabEvent(xEvent& event, u32 mask, ClientSession* client, const WindowNode* boundary) {
    WindowNode* child = nullptr;
    auto* target = FindEventWindow(mask, child, boundary);
    if (!target || !(target->InterestOf(client) & mask)) return false;
    auto& data = event.u.keyButtonPointer;
    data.event = target->id;
    data.child = child ? child->id : None;
    data.eventX = data.rootX - target->ContentBounds().left;
    data.eventY = data.rootY - target->ContentBounds().top;
    client->DeliverEvent(event);
    return true;
}

bool WindowNode::GrabButtonNotify(bool down, s32 x, s32 y, u32 button, ClientSession* client, u32 mask, bool ownerEvents) {
    const u32 selected = down ? ButtonPressMask : ButtonReleaseMask;
    auto event = InputEvent(down ? ButtonPress : ButtonRelease, button, x, y, window_manager.GetInputState());
    if (ownerEvents && window_manager.GetPointerWindow(x, y)->RouteGrabEvent(event, selected, client)) return true;
    if (!(mask & selected)) return false;
    client->DeliverEvent(event);
    return true;
}

void WindowNode::GrabPointerMoveNotify(s32 x, s32 y, u32 state, ClientSession* client, u32 mask, bool ownerEvents) {
    const u32 normal = MotionMask(state), selected = normal & mask;
    auto event = InputEvent(MotionNotify, MotionDetail(selected), x, y, state);
    if (ownerEvents && window_manager.GetPointerWindow(x, y)->RouteGrabEvent(event, normal, client)) return;
    if (selected) client->DeliverEvent(event);
}

void WindowNode::GrabKeyNotify(bool down, s32 x, s32 y, u32 key, ClientSession* client,
                           WindowNode* normalTarget, const WindowNode* focusBoundary) {
    const u32 mask = down ? KeyPressMask : KeyReleaseMask;
    auto event = InputEvent(down ? KeyPress : KeyRelease, key, x, y, window_manager.GetInputState());
    if (normalTarget && normalTarget->RouteGrabEvent(event, mask, client, focusBoundary)) return;
    client->DeliverEvent(event);
}

void WindowNode::NotifyRelocation(WindowNode& previous, s16 x, s16 y) {
    if (!HasSubscribers(StructureNotifyMask) && !previous.HasSubscribers(SubstructureNotifyMask) &&
        !parent->HasSubscribers(SubstructureNotifyMask)) return;

    CorePacket packet{};
    packet.front() = ReparentNotify;
    PutWire32(packet, 8, id);
    PutWire32(packet, 12, parent->id);
    PutWire16(packet, 16, static_cast<u16>(x));
    PutWire16(packet, 18, static_cast<u16>(y));
    packet[20] = options.bypassManager;

    // Each subscription scope receives its own event-window XID, in tree
    // order. The session stamps the recipient's sequence into its copy.
    const std::pair<WindowNode*, u32> scopes[] = {
        {this, StructureNotifyMask}, {&previous, SubstructureNotifyMask}, {parent, SubstructureNotifyMask},
    };
    for (const auto& [window, mask] : scopes) {
        PutWire32(packet, 4, window->id);
        window->DispatchToSubscribers(mask, [&](ClientSession* subscriber) { subscriber->DeliverEvent(packet); });
    }
}

} // namespace ludashi::x11
