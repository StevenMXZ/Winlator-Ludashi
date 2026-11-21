#include "core/window_node.h"

#include <algorithm>
#include "core/client_session.h"

namespace ludashi::x11 {

u32 WindowNode::InterestOf(const ClientSession* client) const {
    const auto entry = std::ranges::find(subscriptions, client, &Subscription::client);
    return entry == subscriptions.end() ? 0 : entry->mask;
}

void WindowNode::Subscribe(ClientSession* client, u32 mask) {
    if (!mask) {
        Unsubscribe(client);
        client->RemoveEventWindow(this);
        return;
    }

    const auto entry = std::ranges::find(subscriptions, client, &Subscription::client);
    if (entry != subscriptions.end()) {
        if (entry->mask != mask) {
            entry->mask = mask;
            RefreshSelectedEvents();
        }
        return;
    }

    subscriptions.push_back({client, mask});
    try {
        client->TrackEventWindow(this);
    } catch (...) {
        subscriptions.pop_back();
        throw;
    }
    selectedEvents |= mask;
}

void WindowNode::Unsubscribe(const ClientSession* client) {
    const auto entry = std::ranges::find(subscriptions, client, &Subscription::client);
    if (entry == subscriptions.end()) return;
    subscriptions.erase(entry);
    RefreshSelectedEvents();
}

void WindowNode::RefreshSelectedEvents() {
    selectedEvents = 0;
    for (const auto& entry : subscriptions) selectedEvents |= entry.mask;
}

void WindowNode::DisconnectSubscribers() {
    for (const auto& entry : subscriptions) entry.client->RemoveEventWindow(this);
    subscriptions.clear();
    selectedEvents = 0;
}

} // namespace ludashi::x11
