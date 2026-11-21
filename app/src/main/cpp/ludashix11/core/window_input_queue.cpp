#include "core/window_tree.h"

#include "core/key_state.h"

namespace ludashi::x11 {

void WindowTree::SetGrabFreeze(Device grab, u8 pointerMode, u8 keyboardMode) {
    grabFreeze[grab].device = {pointerMode == GrabModeSync ? Freeze::Frozen : Freeze::Running,
                               keyboardMode == GrabModeSync ? Freeze::Frozen : Freeze::Running};
    replayEvents[grab].reset();
}

bool WindowTree::InputFrozen(Device device) const {
    return grabFreeze[Pointer].device[device] == Freeze::Frozen ||
           grabFreeze[Keys].device[device] == Freeze::Frozen;
}

bool WindowTree::FrozenBy(Device device, const ClientSession* client) const {
    return (GrabOwner(Pointer) == client && grabFreeze[Pointer].device[device] == Freeze::Frozen) ||
           (GrabOwner(Keys) == client && grabFreeze[Keys].device[device] == Freeze::Frozen);
}

void WindowTree::SubmitInput(InputChange event) {
    const auto device = InputDevice(event.kind);
    if (!InputFrozen(device)) {
        DispatchInput(event);
        return;
    }

    // Keep the normal input path allocation-free. Bound the backlog and thaw
    // the saturated device on overflow so input cannot remain stuck indefinitely.
    constexpr size_t pendingLimit = 4096;
    auto& queue = pendingInput[device];
    if (!queue) queue.emplace();
    event.time = server.GetTimestamp();
    event.order = ++inputOrder;
    if (event.kind == InputKind::Move && !queue->empty() && queue->back().kind == InputKind::Move &&
        queue->back().order + 1 == event.order) {
        queue->back() = event;
        return;
    }
    if (queue->size() >= pendingLimit) {
        for (auto& freeze : grabFreeze) freeze.device[device] = Freeze::Running;
        stepBothOwner = nullptr;
        replayEvents[device].reset();
        DrainPendingInput();
        SubmitInput(event);
        return;
    }
    queue->push_back(event);
}

void WindowTree::DispatchInput(const InputChange& event) {
    const auto savedTime = std::exchange(eventTime, event.time);
    if (event.kind == InputKind::Move) {
        pointerX = event.x;
        pointerY = event.y;
        UpdatePointerPosition(event.x, event.y, NotifyNormal, true);
    } else {
        const auto [x, y] = event.replay ? std::pair{event.x, event.y} : GetPointerPosition();
        const auto savedState = std::exchange(eventState, event.replay ? event.state : GetButtonMasks());
        const auto device = InputDevice(event.kind);
        ClientSession* recipient;
        if (grabFreeze[Pointer].device[device] == Freeze::Step ||
            grabFreeze[Keys].device[device] == Freeze::Step || stepBothOwner)
            eventTime = GetInputTime();
        if (device == Keys) {
            if (!event.replay) server.Keys().ApplyKeyState(event.code, event.down);
            recipient = DeliverKey(event.code, event.down, x, y);
        } else {
            const u16 bit = u16{1} << (event.code + 7);
            if (!event.replay) pointerButtons = event.down ? pointerButtons | bit : pointerButtons & ~bit;
            recipient = DeliverPointerButton(event.code, event.down, x, y);
        }
        FinishInputStep(device, event, recipient);
        eventState = savedState;
    }
    eventTime = savedTime;
}

void WindowTree::DrainPendingInput() {
    if (drainingInput || (!pendingInput[Pointer] && !pendingInput[Keys])) return;
    drainingInput = true;
    for (;;) {
        Device next = Pointer;
        const auto ready = [&](Device device) {
            return pendingInput[device] && !pendingInput[device]->empty() && !InputFrozen(device);
        };
        if (ready(Keys) && (!ready(Pointer) ||
            pendingInput[Keys]->front().order < pendingInput[Pointer]->front().order))
            next = Keys;
        else if (!ready(Pointer)) break;

        auto& queue = pendingInput[next];
        const auto event = queue->front();
        queue->pop_front();
        DispatchInput(event);
    }
    for (auto& queue : pendingInput)
        if (queue && queue->empty()) queue.reset();
    drainingInput = false;
}

void WindowTree::FinishInputStep(Device device, const InputChange& event, ClientSession* recipient) {
    if (!recipient || GrabOwner(device) != recipient) return;
    const bool both = stepBothOwner == recipient;
    bool step = false;
    for (const Device grab : {Pointer, Keys}) {
        if (GrabOwner(grab) != recipient) continue;
        auto& mode = grabFreeze[grab].device[device];
        if (mode == Freeze::Step) {
            mode = Freeze::Running;
            step = true;
        }
    }
    if (!both && !step) return;
    auto& freeze = grabFreeze[device].device;
    freeze[device] = Freeze::Frozen;
    if (both) {
        freeze[Pointer] = freeze[Keys] = Freeze::Frozen;
        stepBothOwner = nullptr;
    }
    auto replay = event;
    replay.replay = true;
    replay.time = GetInputTime();
    replay.state = GetInputState();
    const auto [x, y] = GetPointerPosition();
    replay.x = x;
    replay.y = y;
    replayEvents[device] = replay;
}

} // namespace ludashi::x11
