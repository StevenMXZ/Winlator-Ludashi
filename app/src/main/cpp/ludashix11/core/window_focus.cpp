#include "core/window_tree.h"

#include <array>
#include <vector>
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {

void FocusUp(WindowNode* first, const WindowNode* stop, u32 detail, u32 mode) {
    for (auto* window = first; window && window != stop; window = window->GetParent())
        window->FocusOutNotify(detail, mode);
}

void FocusDown(WindowNode* last, const WindowNode* stop, u32 detail, u32 mode) {
    // Most Wine hierarchies fit here without an allocation. Deep trees retain
    // the same root-to-leaf event order and do not consume the C++ call stack.
    std::array<WindowNode*, 32> local;
    size_t count = 0;
    std::vector<WindowNode*> overflow;
    for (auto* window = last; window && window != stop; window = window->GetParent()) {
        if (count < local.size()) local[count++] = window;
        else overflow.push_back(window);
    }
    for (auto entry = overflow.rbegin(); entry != overflow.rend(); ++entry)
        (*entry)->FocusInNotify(detail, mode);
    while (count) local[--count]->FocusInNotify(detail, mode);
}

WindowNode* CommonAncestor(WindowNode* left, WindowNode* right) {
    auto depth = [](const WindowNode* window) {
        size_t result = 0;
        for (; window; window = window->GetParent()) ++result;
        return result;
    };
    size_t leftDepth = depth(left), rightDepth = depth(right);
    while (leftDepth > rightDepth) { left = left->GetParent(); --leftDepth; }
    while (rightDepth > leftDepth) { right = right->GetParent(); --rightDepth; }
    while (left != right) { left = left->GetParent(); right = right->GetParent(); }
    return left;
}

bool DifferentBranch(const WindowNode* pointer, const WindowNode* window) {
    return pointer != window && !pointer->IsAncestorOf(window) && !window->IsAncestorOf(pointer);
}

bool Earlier(u32 time, u32 reference) { return static_cast<s32>(time - reference) < 0; }

} // namespace

void WindowTree::FocusInOutNotify(WindowNode* lost, WindowNode* gained, WindowNode* pointer,
                                    WindowNode* root, u32 mode, bool lostCanBePointerRoot,
                                    bool gainedCanBePointerRoot) const {
    const bool lostPointerRoot = lost == root && lostCanBePointerRoot;
    const bool gainedPointerRoot = gained == root && gainedCanBePointerRoot;
    if (lost == gained && lostPointerRoot == gainedPointerRoot) return;
    if (!lost || !gained || lostPointerRoot || gainedPointerRoot) {
        if (!lost || lostPointerRoot) {
            if (lostPointerRoot) FocusUp(pointer, nullptr, NotifyPointer, mode);
            root->FocusOutNotify(lost ? NotifyPointerRoot : NotifyDetailNone, mode);
        } else {
            if (lost->IsAncestorOf(pointer)) FocusUp(pointer, lost, NotifyPointer, mode);
            lost->FocusOutNotify(NotifyNonlinear, mode);
            FocusUp(lost->GetParent(), nullptr, NotifyNonlinearVirtual, mode);
        }
        if (!gained || gainedPointerRoot) {
            root->FocusInNotify(gained ? NotifyPointerRoot : NotifyDetailNone, mode);
            if (gainedPointerRoot) FocusDown(pointer, nullptr, NotifyPointer, mode);
        } else {
            FocusDown(gained->GetParent(), nullptr, NotifyNonlinearVirtual, mode);
            gained->FocusInNotify(NotifyNonlinear, mode);
            if (gained->IsAncestorOf(pointer)) FocusDown(pointer, gained, NotifyPointer, mode);
        }
        return;
    }

    const auto* common = CommonAncestor(lost, gained);
    if (common == lost) {
        if (lost->IsAncestorOf(pointer) && DifferentBranch(pointer, gained))
            FocusUp(pointer, lost, NotifyPointer, mode);
        lost->FocusOutNotify(NotifyInferior, mode);
        FocusDown(gained->GetParent(), lost, NotifyVirtual, mode);
        gained->FocusInNotify(NotifyAncestor, mode);
    } else if (common == gained) {
        lost->FocusOutNotify(NotifyAncestor, mode);
        FocusUp(lost->GetParent(), gained, NotifyVirtual, mode);
        gained->FocusInNotify(NotifyInferior, mode);
        if (gained->IsAncestorOf(pointer) && DifferentBranch(pointer, lost))
            FocusDown(pointer, gained, NotifyPointer, mode);
    } else {
        if (lost->IsAncestorOf(pointer)) FocusUp(pointer, lost, NotifyPointer, mode);
        lost->FocusOutNotify(NotifyNonlinear, mode);
        FocusUp(lost->GetParent(), common, NotifyNonlinearVirtual, mode);
        FocusDown(gained->GetParent(), common, NotifyNonlinearVirtual, mode);
        gained->FocusInNotify(NotifyNonlinear, mode);
        if (gained->IsAncestorOf(pointer)) FocusDown(pointer, gained, NotifyPointer, mode);
    }
}

void WindowTree::SetInputFocus(WindowNode* window, u8 revert, u32 time) {
    if (revert > RevertToParent) throw BadValueError(revert);
    if (window && !window->IsAllAncestorsMapped()) throw BadMatchError();
    const u32 now = server.GetTimestamp(), requested = time ? time : now;
    if (time && ((last_focus_time && Earlier(requested, last_focus_time)) || Earlier(now, requested))) return;
    const auto [x, y] = GetPointerPosition();
    FocusInOutNotify(focus_window, window, GetPointerWindow(x, y),
                     root_window.get(), grab_keyboard_window ? NotifyWhileGrabbed : NotifyNormal);
    focus_window = window;
    focus_revert_to = revert;
    last_focus_time = requested;
}

void WindowTree::RevertFocus(WindowNode* window) {
    if (!window || window != focus_window) return;
    const auto [x, y] = GetPointerPosition();
    auto* pointer = GetPointerWindow(x, y);
    WindowNode* next = nullptr;
    if (focus_revert_to == RevertToPointerRoot) next = root_window.get();
    else if (focus_revert_to == RevertToParent) {
        next = window->GetParent();
        while (next && !next->IsAllAncestorsMapped()) next = next->GetParent();
    }
    FocusInOutNotify(window, next, pointer, root_window.get(),
                     grab_keyboard_window ? NotifyWhileGrabbed : NotifyNormal);
    focus_window = next;
    focus_revert_to = RevertToNone;
}

} // namespace ludashi::x11
