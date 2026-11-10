#include "core/selection_manager.h"

#include "window.h"
#include "xclient.h"
#include "xserver.h"

namespace xorg {

SelectionManager::SelectionManager() {}

SelectionManager::~SelectionManager() {}

void SelectionManager::SetSelection(u32 atom, Window* owner, XClient* client, u32 timestamp) {
    auto& selection = selections[atom];
    if (selection.client && (!owner || selection.client != client)) {
        xEvent event{};
        event.u.selectionClear.time = client->GetServer()->GetTimestamp();
        event.u.selectionClear.window = selection.owner->id;
        event.u.selectionClear.atom = atom;
        event.u.u.type = SelectionClear;
        selection.client->SendEvent(event);
    }
    selection.owner = owner;
    selection.client = client;
}

Selection SelectionManager::GetSelection(u32 atom) { return selections[atom]; }
void SelectionManager::RemoveClient(XClient* client) {
    for (auto& [_, s] : selections) if (s.client == client) s = {};
}
void SelectionManager::RemoveWindow(Window* window) {
    for (auto& [_, s] : selections) if (s.owner == window) s = {};
}

}  // namespace xorg
