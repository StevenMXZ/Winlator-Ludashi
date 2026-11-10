#include <stack>
#include <X11/X.h>
#include <X11/Xproto.h>
#include "base/log.h"
#include "core/cursor.h"
#include "core/exceptions.h"
#include "core/drawable.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xscreen.h"
#include "core/xserver.h"
#include "native_screen.h"

namespace xorg {

bool WindowAttributes::Update(Window* window, u32 mask, const u32* values, XClient* client) {
    bool redraw = false;
    for (; mask; mask &= mask - 1) {
        const u32 index = 1 << std::countr_zero(mask);
        const u32 value = *values++;
        switch (index) {
            case CWBackPixel: {
                if (back_pixel != value) {
                    back_pixel = value;
                    const auto drawable = window->GetDrawable();
                    drawable->FillRect(0, 0, drawable->GetWidth(), drawable->GetHeight(), value);
                    redraw = true;
                }
                break;
            }
            case CWBackingPixel:
                backing_pixel = value;
                break;
            case CWBackingPlanes:
                backing_planes = value;
                break;
            case CWBitGravity:
                bit_gravity = value;
                break;
            case CWWinGravity:
                win_gravity = value;
                break;
            case CWBackingStore:
                backing_store = value;
                break;
            case CWSaveUnder:
                save_under = value != 0;
                break;
            case CWOverrideRedirect:
                override_redirect = value != 0;
                break;
            case CWEventMask:
                event_mask = value;
                window->PutClientMask(client, event_mask);
                client->PutEventWindow(window, event_mask);
                break;
            case CWDontPropagate:
                dont_propagate_mask = value;
                break;
            case CWCursor: {
                if (!value && window->IsRoot()) break;
                const auto new_cursor = client->GetResourceAs<Cursor>(value);
                if (cursor.get() != new_cursor) {
                    if (value && !new_cursor) throw BadCursorError(value);
                    cursor = new_cursor ? std::static_pointer_cast<Cursor>(client->ShareResource(value)) : nullptr;
                    redraw = true;
                }
                break;
            }
            default:
                break;
        }
    }
    return redraw;
}

Window::Window(WindowManager& window_manager,
               u32 id,
               Window* parent,
               bool is_input_only,
               s32 x,
               s32 y,
               u16 width,
               u16 height,
               u8 depth,
               s32 border_width,
               XClient* client)
        : XResource(XResourceType::Window, id)
        , window_manager(window_manager)
        , is_input_only(is_input_only)
        , border_width(border_width)
        , parent(parent)
        , owner_client(client)
        , width(width)
        , height(height)
        , depth(depth) {
    if (!parent) {
        out_rect = {x, y, width, height};
        in_rect = out_rect;
    } else {
        const s32 left = parent->out_rect.left + parent->border_width + x;
        const s32 top = parent->out_rect.top + parent->border_width + y;
        const s32 border = border_width * 2;
        out_rect = {left, top, left + width + border, top + height + border};
        if (border == 0) {
            in_rect = out_rect;
        } else {
            in_rect = {left + border_width,
                       top + border_width,
                       out_rect.right - border_width,
                       out_rect.bottom - border_width};
        }
    }
    if (!is_input_only) {
        RecreateDrawable();
    }
}

Window::~Window() {}

bool Window::IsAllAncestorsMapped() const {
    for (auto w = this; w; w = w->parent) {
        if (!w->IsMapped()) {
            return false;
        }
    }
    return true;
}

bool Window::IsAncestorOf(const Window* w) const {
    for (auto p = w->parent; p; p = p->parent) {
        if (p == this) {
            return true;
        }
    }
    return false;
}

bool Window::IsInferiorOf(const Window* w) const { return w->IsAncestorOf(this); }

Property* Window::GetProperty(u32 atom) const {
    const auto it = properties.find(atom);
    if (it == properties.end()) {
        return nullptr;
    }
    return it->second.get();
}

Property* Window::AddProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data) {
    const auto property = std::make_shared<Property>(atom, type, format);
    property->ReplaceData(data);
    properties[atom] = property;
    return property.get();
}

bool Window::ModifyProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data) {
    Property* property = GetProperty(atom);
    bool modified = false;
    if (!property) {
        property = AddProperty(atom, type, format, mode, data);
        modified = true;
    } else if (mode == PropModeReplace) {
        if (property->format == format && property->type == type) {
            property->ReplaceData(data);
        } else {
            AddProperty(atom, type, format, mode, data);
        }
        modified = true;
    } else if (property->format == format && property->type == type) {
        if (mode == PropModePrepend) {
            property->PrependData(data);
            modified = true;
        } else if (mode == PropModeAppend) {
            property->AppendData(data);
            modified = true;
        }
    }
    if (modified) {
        xEvent event{};
        event.u.property.window = this->id;
        event.u.property.state = PropertyNewValue;
        event.u.property.atom = atom;
        event.u.property.time = window_manager.GetServer().GetTimestamp();
        event.u.u.type = PropertyNotify;
        ForeachEventListeners(PropertyChangeMask,
                              [&](XClient* client) { client->SendEvent(event); });
    }
    return modified;
}

void Window::DeleteProperty(u32 atom) {
    if (properties.erase(atom) != 0) {
        const auto current_time = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch());

        xEvent event{};
        event.u.property.window = this->id;
        event.u.property.state = PropertyDelete;
        event.u.property.atom = atom;
        event.u.property.time = static_cast<CARD32>(current_time.count());
        event.u.u.type = PropertyNotify;

        ForeachEventListeners(PropertyChangeMask,
                              [&](XClient* client) { client->SendEvent(event); });
    }
}

void Window::Move(s32 dx, s32 dy, s32 dw, s32 dh) {
    const s32 parent_dx = dx, parent_dy = dy;
    if (dw != 0 || dh != 0) {
        switch (attributes.win_gravity) {
            case UnmapGravity:
                window_manager.UnmapWindow(this);
                break;
            case NorthWestGravity:
                break;
            case NorthGravity:
                dx += dw / 2;
                break;
            case NorthEastGravity:
                dx += dw;
                break;
            case WestGravity:
                dy += dh / 2;
                break;
            case CenterGravity:
                dx += dw / 2;
                dy += dh / 2;
                break;
            case EastGravity:
                dx += dw;
                dy += dh / 2;
                break;
            case SouthWestGravity:
                dy += dh;
                break;
            case SouthGravity:
                dx += dw / 2;
                dy += dh;
                break;
            case SouthEastGravity:
                dx += dw;
                dy += dh;
                break;
            case StaticGravity:
                dx = 0;
                dy = 0;
                break;
            default:
                break;
        }
        xEvent event{};
        event.u.gravity.event = id;
        event.u.gravity.window = id;
        event.u.gravity.x = out_rect.left + dx - parent->in_rect.left;
        event.u.gravity.y = out_rect.top + dy - parent->in_rect.top;
        event.u.u.type = GravityNotify;
        ForeachEventListeners(StructureNotifyMask,
                              [&](XClient* client) { client->SendEvent(event); });

        event.u.gravity.event = parent->id;
        parent->ForeachEventListeners(SubstructureNotifyMask,
                                      [&](XClient* client) { client->SendEvent(event); });
    }
    if (dx == 0 && dy == 0) {
        if (parent_dx != 0 || parent_dy != 0)
            static_cast<NativeScreen&>(window_manager.GetScreen()).WindowGeometryChanged(this);
        return;
    }

    in_rect.left += dx;
    in_rect.right += dx;
    in_rect.top += dy;
    in_rect.bottom += dy;
    out_rect.left += dx;
    out_rect.right += dx;
    out_rect.top += dy;
    out_rect.bottom += dy;

    for (const auto child : children) {
        child->Move(dx, dy, 0, 0);
    }
    if (dx != parent_dx || dy != parent_dy)
        static_cast<NativeScreen&>(window_manager.GetScreen()).WindowGeometryChanged(this);
}

void Window::Reparent(XClient* caller_client, Window* new_parent, s32 x, s32 y) {
    const bool geometryChanged = parent != new_parent || x != out_rect.left - parent->in_rect.left ||
                                 y != out_rect.top - parent->in_rect.top;
    const bool mapped = IsMapped();
    if (mapped) {
        window_manager.UnmapWindow(this);
    }
    const s32 dx = new_parent->in_rect.left - out_rect.left + x;
    const s32 dy = new_parent->in_rect.top - out_rect.top + y;
    if (dx != 0 || dy != 0) {
        out_rect.left += dx;
        out_rect.right += dx;
        out_rect.top += dy;
        out_rect.bottom += dy;
        in_rect.left += dx;
        in_rect.right += dx;
        in_rect.top += dy;
        in_rect.bottom += dy;
    }
    parent->children.remove(this);
    new_parent->children.push_back(this);
    if (dx != 0 || dy != 0) {
        for (const auto child : children) {
            child->Move(dx, dy, 0, 0);
        }
    }
    xEvent event{};
    event.u.reparent.event = id;
    event.u.reparent.window = id;
    event.u.reparent.parent = new_parent->id;
    event.u.reparent.x = x;
    event.u.reparent.y = y;
    event.u.reparent.override = attributes.override_redirect;
    event.u.u.type = ReparentNotify;
    ForeachEventListeners(StructureNotifyMask, [&](XClient* client) { client->SendEvent(event); });

    event.u.reparent.event = parent->id;
    parent->ForeachEventListeners(SubstructureNotifyMask,
                                  [&](XClient* client) { client->SendEvent(event); });

    event.u.reparent.event = new_parent->id;
    new_parent->ForeachEventListeners(SubstructureNotifyMask,
                                      [&](XClient* client) { client->SendEvent(event); });

    parent = new_parent;
    if (geometryChanged)
        static_cast<NativeScreen&>(window_manager.GetScreen()).WindowGeometryChanged(this);
    if (mapped) {
        window_manager.MapWindow(caller_client, this);
    }
    window_manager.GetScreen().PostInvalidate(ScreenDirtyFlags::kWindow);
}

void Window::MakeExposure() {
    if (!exposed) {
        xEvent event{};
        event.u.expose.window = id;
        event.u.expose.x = 0;
        event.u.expose.y = 0;
        event.u.expose.width = width, event.u.expose.height = height, event.u.expose.count = 0;
        event.u.u.type = Expose;
        ForeachEventListeners(ExposureMask, [&](XClient* client) { client->SendEvent(event); });
        exposed = true;
    }
}

bool Window::Occuldes(Window* w1, Window* w2) const {
    if (!w1) {
        if (!w2 || !w2->IsMapped()) {
            return false;
        }

        // Does anything occlude w2?
        const Rect r = w2->out_rect;
        bool above = false;

        for (auto& w : children) {
            if (above) {
                if (w->IsMapped() && Rect::Intersects(w->out_rect, r)) {
                    return true;
                }
            } else {
                if (w == w2) {
                    above = true;
                }
            }
        }
    } else {
        if (!w2) {  // Does w1 occlude anything?
            if (!w1->IsMapped()) {
                return false;
            }

            const Rect r = w1->out_rect;

            for (auto& w : children) {
                if (w == w1) {
                    return false;
                }
                if (w->IsMapped() && Rect::Intersects(w->out_rect, r)) {
                    return true;
                }
            }
        } else {  // Does w1 occlude w2?
            if (!w1->IsMapped() || !w2->IsMapped()) {
                return false;
            }
            if (!Rect::Intersects(w1->out_rect, w2->out_rect)) {
                return false;
            }

            return std::distance(children.begin(), std::ranges::find(children, w1)) >
                   std::distance(children.begin(), std::ranges::find(children, w2));
        }
    }
    return false;
}

void Window::UpdateVisibility() {
    if (HasEventListenerFor(VisibilityChangeMask)) {
        if (const u32 new_visibility = CalculateVisibility(); visibility != new_visibility) {
            visibility = new_visibility;
            if (visibility != VisibilityNotViewable) {
                xEvent event{};
                event.u.visibility.window = id;
                event.u.visibility.state = visibility;
                event.u.u.type = VisibilityNotify;
                ForeachEventListeners(VisibilityChangeMask,
                                      [&](XClient* client) { client->SendEvent(event); });
            }
        }
    }
    for (const auto child : children) {
        child->UpdateVisibility();
    }
}

void Window::UpdateAffectedVisibility() {
    if (!parent) {
        UpdateVisibility();
        return;
    }
    for (const auto w : parent->children) {
        w->UpdateVisibility();
        if (w == this) {
            break;
        }
    }
}

void Window::FocusOutNotify(u32 detail, u32 mode) {
    if (!IsMapped()) {
        return;
    }
    xEvent event{};
    event.u.focus.window = id;
    event.u.focus.mode = mode;
    event.u.u.detail = detail;
    event.u.u.type = FocusOut;
    ForeachEventListeners(FocusChangeMask, [&](XClient* client) { client->SendEvent(event); });
}

void Window::FocusInNotify(u32 detail, u32 mode) {
    if (!IsMapped()) {
        return;
    }
    xEvent event{};
    event.u.focus.window = id;
    event.u.focus.mode = mode;
    event.u.u.detail = detail;
    event.u.u.type = FocusIn;
    ForeachEventListeners(FocusChangeMask, [&](XClient* client) { client->SendEvent(event); });
}

void Window::LeaveEnterNotify(s32 x, s32 y, Window* enter_window, u32 mode) {
    if (enter_window->IsAncestorOf(this)) {
        PointerNotify(LeaveNotify, x, y, NotifyAncestor, this, mode);
        for (auto w = parent; w != enter_window; w = w->parent) {
            w->PointerNotify(LeaveNotify, x, y, NotifyVirtual, this, 0);
        }
        enter_window->PointerNotify(EnterNotify, x, y, NotifyInferior, enter_window, mode);
    } else if (this->IsAncestorOf(enter_window)) {
        PointerNotify(LeaveNotify, x, y, NotifyInferior, this, mode);

        std::stack<Window*> stack;
        for (auto w = enter_window->parent; w != this; w = w->parent) {
            stack.push(w);
        }
        while (!stack.empty()) {
            const auto w = stack.top();
            w->PointerNotify(EnterNotify, x, y, NotifyVirtual, enter_window, mode);
            stack.pop();
        }
        enter_window->PointerNotify(EnterNotify, x, y, NotifyAncestor, enter_window, mode);
    } else {
        PointerNotify(LeaveNotify, x, y, NotifyNonlinear, this, 0);
        const Window* lca{};
        std::stack<Window*> stack;
        for (auto w = parent; w != enter_window->parent; w = w->parent) {
            if (w->IsAncestorOf(enter_window)) {
                lca = w;
                break;
            }
            PointerNotify(LeaveNotify, x, y, NotifyNonlinearVirtual, this, mode);
        }
        for (auto w = enter_window->parent; w != lca; w = w->parent) {
            stack.push(w);
        }
        while (!stack.empty()) {
            const auto w = stack.top();
            w->PointerNotify(EnterNotify, x, y, NotifyNonlinearVirtual, enter_window, mode);
            stack.pop();
        }
        enter_window->PointerNotify(EnterNotify, x, y, NotifyNonlinear, enter_window, mode);
    }
}

void Window::PointerNotify(u32 type, s32 x, s32 y, u32 detail, Window* window, u32 mode) {
    if (!IsMapped()) {
        return;
    }
    if (!HasEventListenerFor(EnterWindowMask)) {
        return;
    }
    const Window* child = window->GetParent() == this ? window : nullptr;
    Window* focus_window = window_manager.GetFocuseWindow();
    bool focus{};
    if (focus_window != nullptr) {
        focus = focus_window == this || IsInferiorOf(focus_window);
    }
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    xEvent event{};
    event.u.u.detail = detail;
    event.u.enterLeave.time = timestamp;
    event.u.enterLeave.root = window_manager.GetRootWindow()->id;
    event.u.enterLeave.event = id;
    event.u.enterLeave.child = child ? child->id : 0;
    event.u.enterLeave.rootX = x;
    event.u.enterLeave.rootY = y;
    event.u.enterLeave.eventX = x - out_rect.left;
    event.u.enterLeave.eventY = y - out_rect.top;
    event.u.enterLeave.state = window_manager.GetButtonMasks();
    event.u.enterLeave.mode = mode;
    event.u.enterLeave.flags = focus ? NotifyNonlinear : NotifyInferior;
    event.u.u.type = type;

    ForeachEventListeners(EnterWindowMask, [&](XClient* client) { client->SendEvent(event); });
}

constexpr u32 ButtonEventMask(const u32 button_mask) {
    u32 mask = PointerMotionMask | PointerMotionHintMask;
    if ((button_mask & (Button1Mask | Button2Mask | Button3Mask)) == 0) {
        return mask;
    }
    mask |= ButtonMotionMask;
    if (button_mask & Button1Mask) {
        mask |= Button1MotionMask;
    }
    if (button_mask & Button2Mask) {
        mask |= Button2MotionMask;
    }
    if (button_mask & Button3Mask) {
        mask |= Button3MotionMask;
    }
    return mask;
}

void Window::PointerMotionNotify(s32 x, s32 y, u32 button_mask, XClient* grab_client) {
    auto event_window = this;
    const Window* child{};
    const u32 mask = ButtonEventMask(button_mask);
    while (!event_window->IsMapped() || !event_window->HasEventListenerFor(mask)) {
        if (!event_window->parent) {
            return;
        }
        if (event_window->attributes.dont_propagate_mask & mask) {
            return;
        }
        child = event_window;
        event_window = event_window->parent;
    }
    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = event_window->id;
    event.u.keyButtonPointer.child = child ? child->id : 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - event_window->in_rect.left;
    event.u.keyButtonPointer.eventY = y - event_window->in_rect.top;
    event.u.keyButtonPointer.state = button_mask;
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.type = MotionNotify;
    u32 detail = NotifyNormal;
    if (const auto event_mask = event_window->GetAllEventMasks();
        (event_mask & PointerMotionHintMask) != 0 && (event_mask & PointerMotionMask) == 0) {
        detail = NotifyHint;
    }
    event.u.u.detail = detail;
    event_window->ForeachEventListeners(mask, [&](XClient* client) {
        if (client == grab_client) {
            return;
        }
        client->SendEvent(event);
    });
}

Window* Window::ButtonNotify(bool down, s32 x, s32 y, u32 button, XClient* grab_client, XClient** recipient) {
    u32 mask = down ? ButtonPressMask : ButtonReleaseMask;
    auto event_window = this;
    const Window* child{};
    while (!event_window->IsMapped() || !event_window->HasEventListenerFor(mask)) {
        if (!event_window->parent) {
            return nullptr;
        }
        if (event_window->attributes.dont_propagate_mask & mask) {
            return nullptr;
        }
        child = event_window;
        event_window = event_window->parent;
    }
    Window* sent_window{};
    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = event_window->id;
    event.u.keyButtonPointer.child = child ? child->id : 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - event_window->in_rect.left;
    event.u.keyButtonPointer.eventY = y - event_window->in_rect.top;
    event.u.keyButtonPointer.state = window_manager.GetButtonMasks();
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.detail = button;
    event.u.u.type = down ? ButtonPress : ButtonRelease;
    event_window->ForeachEventListeners(mask, [&](XClient* client) {
        if (client == grab_client) {
            return;
        }
        client->SendEvent(event);
        sent_window = event_window;
        if (recipient) *recipient = client;
    });
    return sent_window;
}

bool Window::KeyNotify(bool down, s32 x, s32 y, u32 keycode, XClient* grab_client) {
    u32 mask = down ? KeyPressMask : KeyReleaseMask;
    auto event_window = this;
    const Window* child{};
    while (!event_window->IsMapped() || !event_window->HasEventListenerFor(mask)) {
        if (!event_window->parent) {
            return false;
        }
        if (event_window->attributes.dont_propagate_mask & mask) {
            return false;
        }
        child = event_window;
        event_window = event_window->parent;
    }
    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.u.detail = keycode;
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = event_window->id;
    event.u.keyButtonPointer.child = child ? child->id : 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - event_window->in_rect.left;
    event.u.keyButtonPointer.eventY = y - event_window->in_rect.top;
    event.u.keyButtonPointer.state = window_manager.GetButtonMasks();
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.type = down ? KeyPress : KeyRelease;
    event_window->ForeachEventListeners(mask, [&](XClient* client) {
        if (client == grab_client) {
            return;
        }
        client->SendEvent(event);
    });
    return true;
}

bool Window::RouteGrabEvent(xEvent& event, u32 mask, XClient* client) {
    Window* child = nullptr;
    auto* target = window_manager.GetPointerWindow(event.u.keyButtonPointer.rootX, event.u.keyButtonPointer.rootY);
    while (target) {
        if (target->IsMapped() && target->HasEventListenerFor(mask)) {
            auto selected = target->client_masks.find(client);
            if (selected == target->client_masks.end() || !(selected->second & mask)) return false;
            event.u.keyButtonPointer.event = target->id;
            event.u.keyButtonPointer.child = child ? child->id : 0;
            event.u.keyButtonPointer.eventX = event.u.keyButtonPointer.rootX - target->in_rect.left;
            event.u.keyButtonPointer.eventY = event.u.keyButtonPointer.rootY - target->in_rect.top;
            client->SendEvent(event);
            return true;
        }
        if (target->attributes.dont_propagate_mask & mask) return false;
        child = target;
        target = target->parent;
    }
    return false;
}

void Window::GrabButtonNotify(bool down,
                              s32 x,
                              s32 y,
                              u32 button,
                              XClient* grab_client,
                              u32 event_mask,
                              bool owner_events) {
    const u32 mask = down ? ButtonPressMask : ButtonReleaseMask;
    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = id;
    event.u.keyButtonPointer.child = 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - in_rect.left;
    event.u.keyButtonPointer.eventY = y - in_rect.top;
    event.u.keyButtonPointer.state = window_manager.GetButtonMasks();
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.detail = button;
    event.u.u.type = down ? ButtonPress : ButtonRelease;
    if (owner_events && RouteGrabEvent(event, mask, grab_client)) return;
    if (event_mask & mask) grab_client->SendEvent(event);
}

void Window::GrabKeyNotify(
        bool down, s32 x, s32 y, u32 keycode, XClient* grab_client, bool owner_events) {
    if (owner_events) {
        Window* w = window_manager.GetPointerWindow(x, y);
        if (w->KeyNotify(down, x, y, keycode, grab_client)) {
            return;
        }
    }
    const u32 mask = down ? KeyPressMask : KeyReleaseMask;
    if ((attributes.event_mask & mask) == 0) {
        return;
    }

    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.u.detail = keycode;
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = id;
    event.u.keyButtonPointer.child = 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - in_rect.left;
    event.u.keyButtonPointer.eventY = y - in_rect.top;
    event.u.keyButtonPointer.state = window_manager.GetButtonMasks();
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.type = down ? KeyPress : KeyRelease;
    grab_client->SendEvent(event);
}

void Window::GrabPointerMoveNotify(
        s32 x, s32 y, u32 button_mask, XClient* grab_client, u32 event_mask, bool owner_events) {
    const u32 normalMask = ButtonEventMask(button_mask);
    const u32 mask = normalMask & event_mask;
    u32 detail = NotifyNormal;
    if ((mask & PointerMotionHintMask) != 0 && (mask & PointerMotionMask) == 0) {
        detail = NotifyHint;
    }
    xEvent event{};
    const auto timestamp = window_manager.GetServer().GetTimestamp();
    event.u.keyButtonPointer.time = timestamp;
    event.u.keyButtonPointer.root = window_manager.GetRootWindow()->id;
    event.u.keyButtonPointer.event = id;
    event.u.keyButtonPointer.child = 0;
    event.u.keyButtonPointer.rootX = x;
    event.u.keyButtonPointer.rootY = y;
    event.u.keyButtonPointer.eventX = x - in_rect.left;
    event.u.keyButtonPointer.eventY = y - in_rect.top;
    event.u.keyButtonPointer.state = button_mask;
    event.u.keyButtonPointer.sameScreen = true;
    event.u.u.detail = detail;
    event.u.u.type = MotionNotify;
    if (owner_events && RouteGrabEvent(event, normalMask, grab_client)) return;
    if (mask) grab_client->SendEvent(event);
}

Window* Window::WindowAtPoint(s16 x, s16 y) {
    for (auto it = children.rbegin(); it != children.rend(); ++it) {
        const auto w = *it;
        if (!w->IsMapped()) {
            continue;
        }
        if (w->out_rect.Contains(x, y)) {
            return w->WindowAtPoint(x, y);
        }
    }
    return this;
}

void Window::RecreateDrawable() {
    drawable = window_manager.GetScreen().CreateDrawable(width, height, depth);
}

void Window::OnOwnerDies(XClient* owner) {
    ASSERT(owner == owner_client);
    window_manager.DestroyWindow(this, false);
}

u32 Window::CalculateVisibility() const {
    if (IsInputOnly()) {
        return VisibilityNotViewable;
    }

    u32 result = VisibilityUnobscured;

    for (auto aw = this; aw->parent; aw = aw->parent) {
        if (!aw->IsMapped()) {
            return VisibilityNotViewable;
        }
        if (result == VisibilityFullyObscured) {
            continue;
        }

        bool above{};
        for (const auto w : aw->parent->children) {
            if (!w->IsMapped()) continue;
            if (above) {
                if (Rect::Intersects(w->out_rect, out_rect)) {
                    if (w->out_rect.Contains(out_rect)) {
                        result = VisibilityFullyObscured;
                        break;
                    }
                    result = VisibilityPartiallyObscured;
                }
            } else if (w == aw) {
                above = true;
            }
        }
    }
    return result;
}

}  // namespace xorg


