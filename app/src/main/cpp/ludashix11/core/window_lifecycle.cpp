#include "native_screen.h"
#include "core/window_tree.h"

#include <algorithm>
#include <vector>
#include "core/client_session.h"

namespace ludashi::x11 {

bool WindowTree::MapWindow(const ClientSession* caller, WindowNode* window, bool publishScene) {
    auto* parent = window->parent;
    if (!parent || window->IsMapped()) return false;
    bool redirected = false;
    if (!window->options.bypassManager) {
        xEvent request{};
        request.u.u.type = MapRequest;
        request.u.mapRequest.parent = parent->id;
        request.u.mapRequest.window = window->id;
        parent->DispatchToSubscribers(SubstructureRedirectMask, [&](ClientSession* client) {
            if (client == caller) return false;
            client->DeliverEvent(request);
            redirected = true;
            return true;
        });
    }
    if (redirected) return false;
    window->mapped = true;
    if (onWindowMapped) onWindowMapped(window);
    xEvent event{};
    event.u.u.type = MapNotify;
    event.u.mapNotify.window = window->id;
    event.u.mapNotify.override = window->options.bypassManager;
    event.u.mapNotify.event = window->id;
    window->DispatchToSubscribers(StructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
    event.u.mapNotify.event = parent->id;
    parent->DispatchToSubscribers(SubstructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
    window->UpdateAffectedVisibility();
    if (!window->IsInputOnly()) window->MakeExposure();
    UpdatePointer(NotifyNormal);
    has_window_mapped = true;
    if (publishScene) GetScreen().PostInvalidate();
    return true;
}

bool WindowTree::UnmapWindow(WindowNode* window, bool publishScene) {
    if (!window->parent || !window->IsMapped()) return false;
    window->mapped = false;
    xEvent event{};
    event.u.u.type = UnmapNotify;
    event.u.unmapNotify.window = window->id;
    event.u.unmapNotify.event = window->id;
    event.u.unmapNotify.fromConfigure = false;
    window->DispatchToSubscribers(StructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
    event.u.unmapNotify.event = window->parent->id;
    window->parent->DispatchToSubscribers(SubstructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
    const auto contains = [&](const WindowNode* candidate) {
        return candidate && (candidate == window || window->IsAncestorOf(candidate));
    };
    if (contains(focus_window)) RevertFocus(focus_window);
    window->UpdateAffectedVisibility();
    ReleaseWindowGrabs(window);
    UpdatePointer(NotifyNormal);
    if (publishScene) GetScreen().PostInvalidate();
    return true;
}

void WindowTree::DestroyWindow(WindowNode* window, bool releaseResource) {
    if (window->IsRoot()) return;
    // Retain every node before changing the tree. Children may belong to other clients,
    // and removing their IDs must not invalidate the traversal or pending notifications.
    std::vector<sp<WindowNode>> retained;
    std::vector<WindowNode*> pending{window};
    while (!pending.empty()) {
        auto* node = pending.back();
        pending.pop_back();
        retained.push_back(std::static_pointer_cast<WindowNode>(server.ShareResource(node->id)));
        for (auto* child : node->children) pending.push_back(child);
    }
    UnmapWindow(window, false);
    for (auto entry = retained.rbegin(); entry != retained.rend(); ++entry) {
        auto* node = entry->get();
        RemoveCompositeWindow(node);
        UnmapWindow(node, false);
        RevertFocus(node);
        ReleaseWindowGrabs(node);

        xEvent event{};
        event.u.u.type = DestroyNotify;
        event.u.destroyNotify.window = node->id;
        event.u.destroyNotify.event = node->id;
        node->DispatchToSubscribers(StructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
        event.u.destroyNotify.event = node->parent->id;
        node->parent->DispatchToSubscribers(SubstructureNotifyMask, [&](ClientSession* client) { client->DeliverEvent(event); });
        node->DisconnectSubscribers();
        auto& siblings = node->parent->children;
        siblings.erase(node->stackPosition);
        // RemoveWithOwner leaves only the initiating ID for FreeOwnerResources to remove.
        // Inferior IDs always leave BOTH the registry and their actual owner's table.
        if (node != window || releaseResource) {
            node->owner_client->RemoveResource(node->id);
            node->parent = nullptr;
        }
    }
    UpdatePointer(NotifyNormal);
    GetScreen().PostInvalidate();
}

} // namespace ludashi::x11
