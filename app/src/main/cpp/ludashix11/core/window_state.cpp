#include "core/window_node.h"

#include <algorithm>
#include <bit>
#include <iterator>
#include "core/pointer_sprite.h"
#include "native_drawable.h"
#include "core/request_error.h"
#include "core/window_tree.h"
#include "core/client_session.h"
#include "core/display_runtime.h"
#include "native_screen.h"

namespace ludashi::x11 {

bool WindowNode::ApplyOptions(ClientSession* client, u32 mask, const u32* values) {
    constexpr u32 inputOptions = CWWinGravity | CWEventMask | CWDontPropagate |
                                 CWOverrideRedirect | CWCursor;
    if (IsInputOnly() && (mask & ~inputOptions)) throw BadMatchError();

    // The wire list is dense by bit number. Read each setting by its position
    // in the list so window state can be grouped by behavior.
    const auto value = [mask, values](u32 flag, u32 fallback = 0) -> u32 {
        return (mask & flag) ? values[std::popcount(mask & (flag - 1))] : fallback;
    };

    bool replacePointer = false;
    sp<PointerSprite> nextPointer;
    if (const u32 spriteId = value(CWCursor); spriteId != None) {
        auto object = client->ShareResource(spriteId);
        if (!object || object->GetType() != ObjectKind::PointerSprite) throw BadCursorError(spriteId);
        nextPointer = std::static_pointer_cast<PointerSprite>(std::move(object));
        replacePointer = nextPointer != pointerOverride;
    } else if ((mask & CWCursor) && !IsRoot()) {
        replacePointer = bool(pointerOverride);
    }

    const auto background = value(CWBackPixel, options.background);
    const bool repaint = background != options.background;
    if (repaint)
        drawable->FillRect(0, 0, drawable->GetWidth(), drawable->GetHeight(), background);
    if (mask & CWEventMask) Subscribe(client, value(CWEventMask));

    options.resize = {
        .pixelGravity = value(CWBitGravity, options.resize.pixelGravity),
        .childGravity = value(CWWinGravity, options.resize.childGravity),
    };
    options.backing = {
        .fallbackPixel = value(CWBackingPixel, options.backing.fallbackPixel),
        .planeMask = value(CWBackingPlanes, options.backing.planeMask),
        .policy = value(CWBackingStore, options.backing.policy),
        .saveUnder = value(CWSaveUnder, options.backing.saveUnder) != 0,
    };
    options.propagationStop = static_cast<u16>(value(CWDontPropagate, options.propagationStop));
    options.bypassManager = value(CWOverrideRedirect, options.bypassManager) != 0;
    options.background = background;
    if (replacePointer) pointerOverride = std::move(nextPointer);
    return repaint || replacePointer;
}

WindowNode::WindowNode(WindowTree& tree, u32 id, const Definition& initial)
    : ProtocolObject(ObjectKind::WindowNode, id), window_manager(tree),
      is_input_only(initial.inputOnly),
      placement{initial.x, initial.y, initial.width, initial.height, initial.border},
      parent(initial.parent), owner_client(initial.owner), depth(initial.depth) {
    if (parent) {
        const auto origin = parent->ContentBounds();
        placement.x += origin.left;
        placement.y += origin.top;
    }
    if (!is_input_only) RecreateDrawable();
}

WindowNode::~WindowNode() = default;

bool WindowNode::IsAllAncestorsMapped() const {
    const WindowNode* ancestor = this;
    while (ancestor && ancestor->IsMapped()) ancestor = ancestor->parent;
    return ancestor == nullptr;
}

bool WindowNode::IsAncestorOf(const WindowNode* descendant) const {
    for (auto* ancestor = descendant->parent; ancestor; ancestor = ancestor->parent)
        if (ancestor == this) return true;
    return false;
}

bool WindowNode::IsInferiorOf(const WindowNode* ancestor) const {
    return ancestor->IsAncestorOf(this);
}

const WindowMetadata* WindowNode::GetProperty(u32 atom) const {
    const auto entry = properties.find(atom);
    return entry == properties.end() ? nullptr : &entry->second;
}

void WindowNode::NotifyProperty(u32 atom, u8 state) {
    if (!HasSubscribers(PropertyChangeMask)) return;
    xEvent event{};
    event.u.u.type = PropertyNotify;
    event.u.property.window = id;
    event.u.property.atom = atom;
    event.u.property.time = window_manager.GetServer().GetTimestamp();
    event.u.property.state = state;
    DispatchToSubscribers(PropertyChangeMask, [&](ClientSession* client) { client->DeliverEvent(event); });
}

bool WindowNode::ModifyProperty(u32 atom, u32 type, u8 format, u8 mode, std::span<const u8> bytes) {
    if (mode > PropModeAppend || (format != 8 && format != 16 && format != 32)) return false;
    const auto entry = properties.find(atom);
    if (entry == properties.end()) {
        properties.emplace(atom, WindowMetadata{type, format, {bytes.begin(), bytes.end()}});
    } else {
        auto& property = entry->second;
        if (mode == PropModeReplace) {
            property.bytes.assign(bytes.begin(), bytes.end());
            property.type = type;
            property.format = format;
        } else {
            if (property.type != type || property.format != format) return false;
            const auto position = mode == PropModeAppend ? property.bytes.end() : property.bytes.begin();
            property.bytes.insert(position, bytes.begin(), bytes.end());
        }
    }
    NotifyProperty(atom, PropertyNewValue);
    return true;
}

void WindowNode::DeleteProperty(u32 atom) {
    if (properties.erase(atom)) NotifyProperty(atom, PropertyDelete);
}

void WindowNode::Translate(s32 dx, s32 dy) {
    placement.x += dx;
    placement.y += dy;
    for (auto* child : children) child->Translate(dx, dy);
}

void WindowNode::Move(s32 parentX, s32 parentY, s32 deltaWidth, s32 deltaHeight) {
    s32 dx = parentX, dy = parentY;
    if (deltaWidth || deltaHeight) {
        const u32 gravity = options.resize.childGravity;
        if (gravity == StaticGravity) {
            dx = dy = 0;
        } else if (gravity == UnmapGravity) {
            window_manager.UnmapWindow(this);
        } else if (gravity >= NorthWestGravity && gravity <= SouthEastGravity) {
            const u32 position = gravity - NorthWestGravity;
            dx += deltaWidth * s32(position % 3) / 2;
            dy += deltaHeight * s32(position / 3) / 2;
        }
        if (parent) {
            xEvent event{};
            event.u.u.type = GravityNotify;
            event.u.gravity.window = id;
            event.u.gravity.event = id;
            event.u.gravity.x = placement.x + dx - parent->ContentBounds().left;
            event.u.gravity.y = placement.y + dy - parent->ContentBounds().top;
            DispatchToSubscribers(StructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
            event.u.gravity.event = parent->id;
            parent->DispatchToSubscribers(SubstructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
        }
    }
    if (dx || dy) Translate(dx, dy);
    if (dx != parentX || dy != parentY)
        window_manager.GetScreen().WindowGeometryChanged(this);
}

void WindowNode::Reparent(ClientSession* caller, WindowNode* destination, s32 x, s32 y) {
    if (!parent || destination == this || IsAncestorOf(destination)) throw BadMatchError();
    if (destination->IsInputOnly() && !IsInputOnly()) throw BadMatchError();

    auto* previous = parent;
    const auto origin = destination->ContentBounds();
    const s32 dx = origin.left + x - placement.x;
    const s32 dy = origin.top + y - placement.y;
    const bool moved = previous != destination || dx || dy;
    const bool remap = IsMapped();
    if (remap) window_manager.UnmapWindow(this);
    destination->children.splice(destination->children.end(), previous->children, stackPosition);
    parent = destination;
    if (dx || dy) Translate(dx, dy);

    NotifyRelocation(*previous, static_cast<s16>(x), static_cast<s16>(y));

    if (moved) window_manager.GetScreen().WindowGeometryChanged(this);
    if (remap) window_manager.MapWindow(caller, this);
    window_manager.GetScreen().PostInvalidate();
}

void WindowNode::MakeExposure() {
    if (exposed) return;
    xEvent event{};
    event.u.u.type = Expose;
    event.u.expose.window = id;
    event.u.expose.width = placement.width;
    event.u.expose.height = placement.height;
    DispatchToSubscribers(ExposureMask, [&](ClientSession* client) { client->DeliverEvent(event); });
    exposed = true;
}

bool WindowNode::HasStackOverlap(const WindowNode* upper, const WindowNode* lower) const {
    if ((!upper && !lower) || (upper && !upper->IsMapped()) || (lower && !lower->IsMapped())) return false;
    const auto end = children.cend();
    if (upper && lower) {
        if (!upper->FrameBounds().Overlaps(lower->FrameBounds())) return false;
        const auto firstAbove = std::next(std::list<WindowNode*>::const_iterator(lower->stackPosition));
        return std::find(firstAbove, end, upper) != end;
    }
    const auto bounds = (upper ? upper : lower)->FrameBounds();
    const auto first = upper ? children.cbegin() : std::next(std::list<WindowNode*>::const_iterator(lower->stackPosition));
    const auto last = upper ? std::list<WindowNode*>::const_iterator(upper->stackPosition) : end;
    return std::any_of(first, last, [&](const WindowNode* candidate) {
        return candidate->IsMapped() && candidate->FrameBounds().Overlaps(bounds);
    });
}

u32 WindowNode::CalculateVisibility() const {
    if (IsInputOnly() || !IsAllAncestorsMapped()) return VisibilityNotViewable;
    u32 result = VisibilityUnobscured;
    const auto bounds = FrameBounds();
    for (const auto* ancestor = this; ancestor->parent; ancestor = ancestor->parent) {
        const auto& siblings = ancestor->parent->children;
        for (auto above = std::next(ancestor->stackPosition); above != siblings.end(); ++above) {
            const auto* cover = *above;
            if (!cover->IsMapped() || cover->IsInputOnly()) continue;
            const auto coverBounds = cover->FrameBounds();
            if (coverBounds.Contains(bounds)) return VisibilityFullyObscured;
            if (coverBounds.Overlaps(bounds)) result = VisibilityPartiallyObscured;
        }
    }
    return result;
}

void WindowNode::UpdateVisibility() {
    if (HasSubscribers(VisibilityChangeMask)) {
        const auto next = CalculateVisibility();
        if (next != visibility) {
            visibility = next;
            if (next != VisibilityNotViewable) {
                xEvent event{};
                event.u.u.type = VisibilityNotify;
                event.u.visibility.window = id;
                event.u.visibility.state = next;
                DispatchToSubscribers(VisibilityChangeMask, [&](ClientSession* client) { client->DeliverEvent(event); });
            }
        }
    }
    for (auto* child : children) child->UpdateVisibility();
}

void WindowNode::UpdateAffectedVisibility() {
    if (!parent) {
        UpdateVisibility();
        return;
    }
    for (auto* sibling : parent->children) {
        sibling->UpdateVisibility();
        if (sibling == this) return;
    }
}

WindowNode* WindowNode::WindowAtPoint(s16 x, s16 y) {
    WindowNode* target = this;
    for (;;) {
        const auto child = std::find_if(target->children.rbegin(), target->children.rend(), [&](const WindowNode* candidate) {
            return candidate->IsMapped() && candidate->FrameBounds().Contains(x, y);
        });
        if (child == target->children.rend()) return target;
        target = *child;
    }
}

void WindowNode::RecreateDrawable() {
    drawable = window_manager.GetScreen().CreateDrawable(placement.width, placement.height, depth);
}

void WindowNode::RemoveWithOwner(ClientSession* owner) {
    if (owner != owner_client) throw std::logic_error("Invalid window owner");
    window_manager.DestroyWindow(this, false);
}

} // namespace ludashi::x11
