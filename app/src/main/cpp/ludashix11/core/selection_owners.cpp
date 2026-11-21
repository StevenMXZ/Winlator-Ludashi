#include "core/selection_owners.h"

#include <bit>
#include "core/window_node.h"
#include "core/client_session.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {
namespace {

bool Earlier(u32 first, u32 second) {
    // X11 timestamps compare modulo the 32-bit wire clock.
    return std::bit_cast<s32>(first - second) < 0;
}

} // namespace

SelectionOwners::Owner SelectionOwners::Find(u32 atom) const {
    const auto entry = history.find(atom);
    return entry == history.end() ? Owner{} : entry->second.owner;
}

void SelectionOwners::Claim(u32 atom, WindowNode* window, ClientSession& client, u32 timestamp, u32 now) {
    const u32 when = timestamp == CurrentTime ? now : timestamp;
    if (Earlier(now, when)) return;
    auto& record = history.try_emplace(atom).first->second;
    if (record.changedAt && Earlier(when, record.changedAt)) return;

    const auto previous = record.owner;
    if (window) {
        if (!previous.client) {
            active.push_back(&record);
            record.activeIndex = active.size() - 1;
        }
        record.owner = {window, &client};
    } else if (previous.client) {
        Drop(record);
    }
    record.changedAt = when;

    if (previous.client && (previous.client != &client || !window)) {
        xEvent event{};
        event.u.u.type = SelectionClear;
        event.u.selectionClear.time = when;
        event.u.selectionClear.window = previous.window->id;
        event.u.selectionClear.atom = atom;
        previous.client->DeliverEvent(event);
    }
}

void SelectionOwners::Drop(Record& record) {
    auto* tail = active.back();
    active[record.activeIndex] = tail;
    tail->activeIndex = record.activeIndex;
    active.pop_back();
    record.owner = {};
}

void SelectionOwners::Release(const ClientSession* client) {
    for (size_t index = 0; index < active.size();) {
        auto& record = *active[index];
        if (record.owner.client == client) Drop(record);
        else ++index;
    }
}

void SelectionOwners::Release(const WindowNode* window) {
    for (size_t index = 0; index < active.size();) {
        auto& record = *active[index];
        if (record.owner.window == window) Drop(record);
        else ++index;
    }
}

void ClientSession::ApplySelectionCommand(u8 opcode) {
    switch (opcode) {
    case X_SetSelectionOwner: {
        const auto& request = *GetRequestData<xSetSelectionOwnerReq>();
        if (!server.Atoms().Contains(request.selection)) throw BadAtomError(request.selection);
        auto* window = request.window ? GetResourceAs<WindowNode>(request.window) : nullptr;
        if (request.window && !window) throw BadWindowError(request.window);
        server.Selections().Claim(request.selection, window, *this, request.time, server.GetTimestamp());
        return;
    }
    case X_GetSelectionOwner: {
        const u32 atom = GetRequestData<xResourceReq>()->id;
        if (!server.Atoms().Contains(atom)) throw BadAtomError(atom);
        const auto selection = server.Selections().Find(atom);
        xGetSelectionOwnerReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        reply.owner = selection.window ? selection.window->id : None;
        WriteImmediately(&reply, sizeof(reply));
        return;
    }
    case X_ConvertSelection: {
        const auto& request = *GetRequestData<xConvertSelectionReq>();
        if (!GetResourceAs<WindowNode>(request.requestor)) throw BadWindowError(request.requestor);
        for (const auto atom : {request.selection, request.target}) {
            if (!server.Atoms().Contains(atom)) throw BadAtomError(atom);
        }
        if (request.property && !server.Atoms().Contains(request.property)) throw BadAtomError(request.property);

        const auto selection = server.Selections().Find(request.selection);
        xEvent event{};
        if (selection.client) {
            event.u.u.type = SelectionRequest;
            event.u.selectionRequest.time = request.time;
            event.u.selectionRequest.owner = selection.window->id;
            event.u.selectionRequest.requestor = request.requestor;
            event.u.selectionRequest.selection = request.selection;
            event.u.selectionRequest.target = request.target;
            event.u.selectionRequest.property = request.property;
            selection.client->DeliverEvent(event);
            return;
        }

        event.u.u.type = SelectionNotify;
        event.u.selectionNotify.time = request.time;
        event.u.selectionNotify.requestor = request.requestor;
        event.u.selectionNotify.selection = request.selection;
        event.u.selectionNotify.target = request.target;
        event.u.selectionNotify.property = None;
        DeliverEvent(event);
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
