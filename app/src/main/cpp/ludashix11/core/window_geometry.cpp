#include "core/window_tree.h"

#include <algorithm>
#include <iterator>
#include "native_drawable.h"
#include "core/request_error.h"
#include "core/client_session.h"
#include "native_screen.h"

namespace ludashi::x11 {

bool WindowTree::ConfigureWindow(ClientSession* caller, WindowNode* window, u32 mask, const u32* values) {
    if (window->IsRoot()) return false;
    auto* parent = window->parent;
    const auto oldInner = window->ContentBounds();
    const auto parentContent = parent->ContentBounds();
    const s32 oldX = window->placement.x - parentContent.left;
    const s32 oldY = window->placement.y - parentContent.top;
    const u32 oldWidth = window->placement.width, oldHeight = window->placement.height;
    const u32 oldBorder = window->placement.border;
    s32 x = oldX, y = oldY;
    u32 width = oldWidth, height = oldHeight, border = oldBorder;
    u32 stackMode = Above;
    WindowNode* sibling = nullptr;

    constexpr u32 allowed = CWX | CWY | CWWidth | CWHeight | CWBorderWidth | CWSibling | CWStackMode;
    if (mask & ~allowed) throw BadValueError(mask);
    if ((mask & CWSibling) && !(mask & CWStackMode)) throw BadMatchError();
    if (window->IsInputOnly() && (mask & CWBorderWidth)) throw BadMatchError();
    for (u32 remaining = mask; remaining;) {
        const u32 attribute = remaining & (0u - remaining);
        remaining ^= attribute;
        const u32 value = *values++;
        switch (attribute) {
        case CWX: x = static_cast<s16>(value); break;
        case CWY: y = static_cast<s16>(value); break;
        case CWWidth: width = value; break;
        case CWHeight: height = value; break;
        case CWBorderWidth: border = value; break;
        case CWStackMode: stackMode = value; break;
        case CWSibling: {
            auto* resource = server.GetResource(value);
            if (!resource || resource->GetType() != ObjectKind::WindowNode) throw BadWindowError(value);
            sibling = resource->As<WindowNode>();
            break;
        }
        }
    }
    if (!width || !height || width > 65535 || height > 65535) throw BadValueError(0);
    if (border > 65535) throw BadValueError(border);
    if (stackMode > Opposite) throw BadValueError(stackMode);
    if (sibling && (sibling == window || sibling->parent != parent)) throw BadMatchError();

    bool redirected = false;
    if (caller && !window->options.bypassManager) {
        xEvent request{};
        request.u.u.type = ConfigureRequest;
        request.u.u.detail = stackMode;
        auto& data = request.u.configureRequest;
        data.parent = parent->id;
        data.window = window->id;
        data.sibling = sibling ? sibling->id : None;
        data.x = x;
        data.y = y;
        data.width = width;
        data.height = height;
        data.borderWidth = border;
        data.valueMask = mask;
        parent->DispatchToSubscribers(SubstructureRedirectMask, [&](ClientSession* listener) {
            if (listener == caller) return false;
            listener->DeliverEvent(request);
            redirected = true;
            return true;
        });
    }
    if (redirected) return false;
    if (caller && (width != oldWidth || height != oldHeight)) {
        window->DispatchToSubscribers(ResizeRedirectMask, [&](ClientSession* listener) {
            if (listener == caller) return false;
            xEvent request{};
            request.u.u.type = ResizeRequest;
            request.u.resizeRequest.window = window->id;
            request.u.resizeRequest.width = width;
            request.u.resizeRequest.height = height;
            listener->DeliverEvent(request);
            width = oldWidth;
            height = oldHeight;
            return true;
        });
    }

    const bool resized = width != oldWidth || height != oldHeight;
    const bool geometryChanged = resized || x != oldX || y != oldY || border != oldBorder;
    // Allocate before publishing geometry. InputOnly windows never acquire pixel storage.
    auto image = resized && !window->IsInputOnly()
        ? GetScreen().CreateDrawable(width, height, window->depth) : window->drawable;
    auto& siblings = parent->children;
    const auto node = window->stackPosition;
    auto position = node;
    if (mask & CWStackMode) {
        switch (stackMode) {
        case Above:
            position = sibling ? std::next(sibling->stackPosition) : siblings.end();
            break;
        case Below:
            position = sibling ? sibling->stackPosition : siblings.begin();
            break;
        case TopIf:
            if (parent->HasStackOverlap(sibling, window)) position = siblings.end();
            break;
        case BottomIf:
            if (parent->HasStackOverlap(window, sibling)) position = siblings.begin();
            break;
        case Opposite:
            if (parent->HasStackOverlap(sibling, window)) position = siblings.end();
            else if (parent->HasStackOverlap(window, sibling)) position = siblings.begin();
            break;
        }
    }
    const bool stackChanged = position != node && position != std::next(node);
    if (stackChanged) siblings.splice(position, siblings, node);
    if (geometryChanged) {
        window->placement = {parentContent.left + x, parentContent.top + y,
                             static_cast<u16>(width), static_cast<u16>(height), static_cast<u16>(border)};
        if (resized && !window->IsInputOnly()) {
            window->drawable = std::move(image);
            window->exposed = false;
        }
    }

    const bool changed = geometryChanged || stackChanged;
    if (changed) {
        xEvent event{};
        event.u.u.type = ConfigureNotify;
        auto& data = event.u.configureNotify;
        data.event = window->id;
        data.window = window->id;
        data.aboveSibling = node == siblings.begin() ? None : (*std::prev(node))->id;
        data.x = x;
        data.y = y;
        data.width = width;
        data.height = height;
        data.borderWidth = border;
        data.override = window->options.bypassManager;
        window->DispatchToSubscribers(StructureNotifyMask, [&](ClientSession* listener) { listener->DeliverEvent(event); });
        data.event = parent->id;
        parent->DispatchToSubscribers(SubstructureNotifyMask, [&](ClientSession* listener) { listener->DeliverEvent(event); });

        const auto inner = window->ContentBounds();
        const s32 dx = inner.left - oldInner.left, dy = inner.top - oldInner.top;
        if (dx || dy || resized) {
            for (auto* child : window->children)
                child->Move(dx, dy, s32(width) - s32(oldWidth), s32(height) - s32(oldHeight));
        }
        // Lowering a window also reveals siblings ABOVE its new position.
        // Recompute the parent subtree so these listeners do not retain stale visibility.
        if (stackChanged) parent->UpdateVisibility();
        else window->UpdateAffectedVisibility();
        if (geometryChanged) GetScreen().WindowGeometryChanged(window);
    }
    if (!window->IsInputOnly()) window->MakeExposure();
    UpdatePointer(NotifyNormal);
    if (changed) GetScreen().PostInvalidate();
    return changed;
}

} // namespace ludashi::x11
