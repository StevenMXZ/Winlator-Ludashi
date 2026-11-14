#include <stack>
#include <X11/Xproto.h>

#include "core/cursor.h"
#include "core/drawable.h"
#include "core/exceptions.h"
#include "core/id_generator.h"

#include "core/keyboard.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xscreen.h"
#include "native_screen.h"

namespace xorg {

WindowManager::WindowManager(XServer& server, Mouse& mouse, ScreenInfo screen_info)
        : server(server), mouse(mouse) {
    const auto root_id = GenerateID();
    root_window = std::make_shared<Window>(*this,
                                           root_id,
                                           nullptr,
                                           false,
                                           0,
                                           0,
                                           screen_info.width,
                                           screen_info.height,
                                           24,
                                           0,
                                           nullptr);
    root_window->attributes.mapped = true;
    server.AddResource(root_window);

    const auto cursor =
            std::make_shared<Cursor>(server.GetScreen(), GenerateID(), 0, 1, 0x000000, 0xffffff);
    server.AddResource(cursor);
    root_window->attributes.cursor = cursor;

    focus_window = root_window.get();
    motion_window = root_window.get();
}

Window* WindowManager::GetPointerWindow(s32 x, s32 y) const {
    const auto clamp_x = std::clamp(x, 0, root_window->width - 1);
    const auto clamp_y = std::clamp(y, 0, root_window->height - 1);
    return root_window->WindowAtPoint(clamp_x, clamp_y);
}

sp<Window> WindowManager::CreateWindow(XClient* client,
                                       u32 id,
                                       Window* parent,
                                       s16 x,
                                       s16 y,
                                       u16 width,
                                       u16 height,
                                       u16 border_width,
                                       u16 win_class,
                                       u8 depth,
                                       u32 mask,
                                       const u32* values) {
    bool is_input_only{};
    switch (win_class) {
        case CopyFromParent: {
            depth = (depth != 0 || parent->IsInputOnly()) ? depth
                                                          : parent->GetDrawable()->GetDepth();
            is_input_only = parent->IsInputOnly();
            break;
        }
        case InputOutput: {
            if (parent->IsInputOnly()) {
                throw BadMatchError();
            }
            depth = depth == 0 ? parent->depth : depth;
            is_input_only = false;
            break;
        }
        case InputOnly:
            is_input_only = true;
            break;
        default:
            break;
    }
    const auto window = std::make_shared<Window>(
            *this, id, parent, is_input_only, x, y, width, height, depth, border_width, client);
    if (mask != 0) {
        window->attributes.Update(window.get(), mask, values, client);
    }
    parent->children.push_back(window.get());
    window->parent = parent;
    client->AddResource(window);
    return window;
}

void WindowManager::RedirectComposite(XClient* client, Window* window, u8 update, bool redirect) {
    if (update > 1) throw BadValueError(update);
    if (window->IsRoot() || window->IsInputOnly()) throw BadMatchError();
    auto same = [&](const CompositeRedirect& r) {
        return r.client == client && r.window == window && r.update == update;
    };
    if (redirect) {
        if (std::ranges::any_of(composite_redirects, same)) return;
        if (update == 1 && std::ranges::any_of(composite_redirects, [&](const auto& r) {
            return r.window == window && r.update == 1;
        })) throw BadAccessError();
        composite_redirects.push_back({client, window, update});
    } else if (!std::erase_if(composite_redirects, same)) throw BadValueError(window->id);
    window->composite_redirected = std::ranges::any_of(composite_redirects,
        [&](const auto& r) { return r.window == window; });
}
void WindowManager::RemoveCompositeClient(XClient* client) {
    // Only redirected windows are visited; no resource-tree scan per request.
    for (const auto& r : composite_redirects) if (r.client == client) r.window->composite_redirected = false;
    std::erase_if(composite_redirects, [&](const auto& r) { return r.client == client; });
    for (const auto& r : composite_redirects) r.window->composite_redirected = true;
    if (grab_pointer_client == client) UngrabPointer(client, 0);
}
void WindowManager::RemoveCompositeWindow(Window* window) {
    std::erase_if(composite_redirects, [&](const auto& r) { return r.window == window; });
    window->composite_redirected = false;
}
void WindowManager::CompositeCopy(Window* source, Window* destination, s16 x, s16 y) {
    // Same policy as public 4.1 VulkanXServerView.nativeCompositeRedirect.
    if (destination->IsAncestorOf(source)) return;
    Window* sibling = source;
    while (sibling && sibling->parent != destination->parent) sibling = sibling->parent;
    if (!sibling || sibling == destination || !sibling->parent) return;
    auto& children = sibling->parent->children;
    auto src = std::ranges::find(children, sibling), dst = std::ranges::find(children, destination);
    if (src == children.end() || dst == children.end()) return;
    const s32 newX = destination->out_rect.left - destination->parent->in_rect.left + x;
    const s32 newY = destination->out_rect.top - destination->parent->in_rect.top + y;
    const bool moved = sibling->out_rect.left - sibling->parent->in_rect.left != newX ||
                       sibling->out_rect.top - sibling->parent->in_rect.top != newY;
    bool below = false;
    for (auto it = src; it != children.end(); ++it) if (it == dst) { below = true; break; }
    if (!moved && !below) return; // Repeated copies do no pixel or scene work.
    std::array<u32, 4> values{u32(newX), u32(newY), destination->id, Above};
    ConfigureWindow(nullptr, sibling, CWX | CWY | CWSibling | CWStackMode, values.data());
}

bool WindowManager::ConfigureWindow(XClient* caller_client,
                                    Window* window,
                                    u32 mask,
                                    const u32* values) {
    const s32 old_left = window->in_rect.left;
    const s32 old_top = window->in_rect.top;
    const s32 old_width = window->in_rect.right - window->in_rect.left;
    const s32 old_height = window->in_rect.bottom - window->in_rect.top;
    const s32 old_x = window->out_rect.left - window->parent->in_rect.left;
    const s32 old_y = window->out_rect.top - window->parent->in_rect.top;
    const u32 old_border_width = window->border_width;

    s32 width = old_width;
    s32 height = old_height;
    s32 x = old_x;
    s32 y = old_y;
    u32 border_width = window->border_width;
    u32 stack_mode{};

    bool changed{};
    Window* sibling{};
    for (u32 cur_mask = mask; cur_mask; cur_mask &= cur_mask - 1) {
        const u32 index = 1 << std::countr_zero(cur_mask);
        const u32 value = *values++;
        switch (index) {
            case CWX:
                x = value;
                break;
            case CWY:
                y = value;
                break;
            case CWWidth:
                width = value;
                break;
            case CWHeight:
                height = value;
                break;
            case CWBorderWidth:
                border_width = value;
                break;
            case CWSibling:
                if (auto* resource = server.GetResource(value);
                    resource && resource->GetType() == XResourceType::Window) {
                    sibling = resource->As<Window>();
                } else {
                    throw BadWindowError(value);
                }
                break;
            case CWStackMode:
                stack_mode = value;
                break;
            default:
                break;
        }
    }

    if (sibling && (sibling == window || sibling->parent != window->parent)) throw BadMatchError();
    if ((mask & CWStackMode) && stack_mode > Opposite) throw BadValueError(stack_mode);
    if (width <= 0 || height <= 0 || width > 65535 || height > 65535) throw BadValueError(0);
    if (caller_client && !window->GetAttributes().override_redirect) {
        bool redirected = false;
        xEvent event{};
        event.u.configureRequest.window = window->id;
        event.u.configureRequest.sibling =
                static_cast<CARD32>((mask & CWSibling) ? sibling->id : None);
        event.u.configureRequest.x = static_cast<INT16>(x);
        event.u.configureRequest.y = static_cast<INT16>(y);
        event.u.configureRequest.width = static_cast<CARD16>(width);
        event.u.configureRequest.height = static_cast<CARD16>(height);
        event.u.configureRequest.borderWidth = static_cast<CARD16>(border_width);
        event.u.configureRequest.valueMask = static_cast<CARD16>(mask);
        event.u.configureRequest.parent = window->parent->id;
        event.u.u.type = ConfigureRequest;
        event.u.u.detail = (mask & CWStackMode) ? stack_mode : Above;
        window->parent->ForeachEventListeners(SubstructureRedirectMask, [&](XClient* client) {
            if (client != caller_client) {
                client->SendEvent(event);
                redirected = true;
                return true;
            }
            return false;
        });
        if (redirected) return false;
    }
    if (caller_client && (width != old_width || height != old_height)) {
        window->ForeachEventListeners(ResizeRedirectMask, [&](XClient* client) {
            if (client != caller_client) {
                xEvent event{};
                event.u.resizeRequest.window = window->id;
                event.u.resizeRequest.width = static_cast<CARD16>(width);
                event.u.resizeRequest.height = static_cast<CARD16>(height);
                event.u.u.type = ResizeRequest;
                client->SendEvent(event);
                width = old_width;
                height = old_height;
                return true;
            }
            return false;
        });
    }
    if (x != old_x || y != old_y || width != old_width || height != old_height ||
        border_width != window->border_width) {
        window->width = width;
        window->height = height;
        window->border_width = border_width;
        window->out_rect.left = window->parent->in_rect.left + x;
        window->out_rect.top = window->parent->in_rect.top + y;
        window->out_rect.right = window->out_rect.left + width + 2 * border_width;
        window->out_rect.bottom = window->out_rect.top + height + 2 * border_width;
        window->in_rect.left = window->out_rect.left + border_width;
        window->in_rect.top = window->out_rect.top + border_width;
        window->in_rect.right = window->out_rect.right - border_width;
        window->in_rect.bottom = window->out_rect.bottom - border_width;

        if (width != old_width || height != old_height) {
            window->RecreateDrawable();
            window->exposed = false;
        }
        changed = true;
    }
    if (mask & (CWStackMode | CWSibling)) {
        if (sibling && sibling->parent != window->parent) {
            throw BadMatchError();
        }

        if (!sibling) {
            switch (stack_mode) {
                case Above: {
                    window->parent->children.remove(window);
                    window->parent->children.push_back(window);
                    changed = true;
                    break;
                }
                case Below: {
                    window->parent->children.remove(window);
                    window->parent->children.push_front(window);
                    changed = true;
                    break;
                }
                case TopIf: {
                    if (window->parent->Occuldes(nullptr, window)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_back(window);
                        changed = true;
                    }
                    break;
                }
                case BottomIf: {
                    if (window->parent->Occuldes(window, nullptr)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_front(window);
                        changed = true;
                    }
                    break;
                }
                case Opposite: {
                    if (window->parent->Occuldes(nullptr, window)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_back(window);
                        changed = true;
                    } else if (window->parent->Occuldes(window, nullptr)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_front(window);
                        changed = true;
                    }
                    break;
                }
                default:
                    break;
            }
        } else {
            switch (stack_mode) {
                case Above: {
                    window->parent->children.remove(window);
                    const auto pos = std::ranges::find(window->parent->children, sibling);
                    window->parent->children.insert(std::next(pos), window);
                    changed = true;
                    break;
                }
                case Below: {
                    window->parent->children.remove(window);
                    const auto pos = std::ranges::find(window->parent->children, sibling);
                    window->parent->children.insert(pos, window);
                    changed = true;
                    break;
                }
                case TopIf: {
                    if (window->parent->Occuldes(sibling, window)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_back(window);
                        changed = true;
                    }
                    break;
                }
                case BottomIf: {
                    if (window->parent->Occuldes(window, sibling)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_front(window);
                        changed = true;
                    }
                    break;
                }
                case Opposite: {
                    if (window->parent->Occuldes(sibling, window)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_back(window);
                        changed = true;
                    } else if (window->parent->Occuldes(window, sibling)) {
                        window->parent->children.remove(window);
                        window->parent->children.push_front(window);
                        changed = true;
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
    if (changed) {
        xEvent event{};
        event.u.configureNotify.event = window->id;
        event.u.configureNotify.window = window->id;
        event.u.configureNotify.aboveSibling = sibling ? sibling->id : None;
        event.u.configureNotify.x = x;
        event.u.configureNotify.y = y;
        event.u.configureNotify.width = width;
        event.u.configureNotify.height = height;
        event.u.configureNotify.borderWidth = border_width;
        event.u.configureNotify.override = window->GetAttributes().override_redirect;
        event.u.u.type = ConfigureNotify;
        window->ForeachEventListeners(StructureNotifyMask,
                                      [&](XClient* client) { client->SendEvent(event); });

        event.u.configureNotify.event = window->parent->id;
        window->parent->ForeachEventListeners(SubstructureNotifyMask,
                                              [&](XClient* client) { client->SendEvent(event); });
        if (window->in_rect.left != old_left || window->in_rect.top != old_top ||
            width != old_width || height != old_height) {
            for (const auto child : window->children) {
                child->Move(window->in_rect.left - old_left,
                            window->in_rect.top - old_top,
                            width - old_width,
                            height - old_height);
            }
        }

        window->UpdateAffectedVisibility();
        if (x != old_x || y != old_y || width != old_width || height != old_height || border_width != old_border_width)
            static_cast<NativeScreen&>(server.GetScreen()).WindowGeometryChanged(window);
    }
    window->MakeExposure();
    UpdatePointer(NotifyNormal);
    if (changed) {
        server.GetScreen().PostInvalidate(ScreenDirtyFlags::kWindow);
    }
    return changed;
}

void WindowManager::MapWindow(const XClient* caller_client, Window* window) {
    if (window->IsMapped()) {
        return;
    }
    if (!window->GetParent()) {
        return;
    }
    if (!window->GetAttributes().override_redirect) {
        bool redirected{};

        xEvent event{};
        event.u.mapRequest.parent = window->parent->id;
        event.u.mapRequest.window = window->id;
        event.u.u.type = MapRequest;
        window->GetParent()->ForeachEventListeners(SubstructureRedirectMask, [&](XClient* c) {
            if (c == caller_client) {
                return false;
            }
            c->SendEvent(event);
            redirected = true;
            return true;
        });
        if (redirected) {
            return;
        }
    }
    window->attributes.mapped = true;
    if (onWindowMapped) onWindowMapped(window);
    xEvent event{};
    event.u.mapNotify.event = window->id;
    event.u.mapNotify.window = window->id;
    event.u.mapNotify.override = window->GetAttributes().override_redirect;
    event.u.u.type = MapNotify;
    window->ForeachEventListeners(StructureNotifyMask,
                                  [&](XClient* client) { client->SendEvent(event); });
    event.u.mapNotify.event = window->parent->id;
    window->parent->ForeachEventListeners(SubstructureNotifyMask,
                                          [&](XClient* client) { client->SendEvent(event); });
    window->UpdateAffectedVisibility();
    window->MakeExposure();
    UpdatePointer(NotifyNormal);
    server.GetScreen().PostInvalidate(ScreenDirtyFlags::kWindow);
    if (!has_window_mapped) {
        has_window_mapped = true;
    }
}

void WindowManager::UnmapWindow(Window* window, bool invalidate) {
    if (!window->IsMapped()) {
        return;
    }
    if (!window->GetParent()) {
        return;
    }
    window->attributes.mapped = false;

    xEvent event{};
    event.u.unmapNotify.event = window->id;
    event.u.unmapNotify.window = window->id;
    event.u.unmapNotify.fromConfigure = false;
    event.u.u.type = UnmapNotify;
    window->ForeachEventListeners(StructureNotifyMask,
                                  [&](XClient* client) { client->SendEvent(event); });
    event.u.unmapNotify.event = window->parent->id;
    window->parent->ForeachEventListeners(SubstructureNotifyMask,
                                          [&](XClient* client) { client->SendEvent(event); });
    RevertFocus(window);
    window->UpdateAffectedVisibility();
    if ((grab_pointer_window && (grab_pointer_window == window || window->IsAncestorOf(grab_pointer_window))) ||
        (grab_confine_window && (grab_confine_window == window || window->IsAncestorOf(grab_confine_window))))
        UngrabPointer(grab_pointer_client, 0);
    UpdatePointer(NotifyNormal);
    if (invalidate) server.GetScreen().PostInvalidate(ScreenDirtyFlags::kWindow);
}

void WindowManager::FocusInOutNotify(
        Window* wlose, Window* wgain, Window* wp, Window* wroot, uint32_t mode) const {
    if (wlose == wgain) {
        return;
    }
    if (!wlose) {
        wroot->FocusOutNotify(NotifyDetailNone, mode);

        if (wgain == wroot) {
            wroot->FocusInNotify(NotifyPointerRoot, mode);

            std::stack<Window*> stack;
            for (Window* w = wp; w; w = w->parent) {
                stack.push(w);
            }
            while (!stack.empty()) {
                Window* w = stack.top();
                stack.pop();

                w->FocusInNotify(NotifyPointer, mode);
            }
        } else {
            if (wgain) {
                std::stack<Window*> stack;
                for (Window* w = wgain->parent; w; w = w->parent) {
                    stack.push(w);
                }
                while (!stack.empty()) {
                    Window* w = stack.top();
                    stack.pop();

                    w->FocusInNotify(NotifyNonlinearVirtual, mode);
                }

                wgain->FocusInNotify(NotifyNonlinear, mode);

                if (wgain->IsAncestorOf(wp)) {
                    for (Window* w = wp; w != wgain; w = w->parent) {
                        stack.push(w);
                    }
                    while (!stack.empty()) {
                        Window* w = stack.top();
                        stack.pop();

                        w->FocusInNotify(NotifyPointer, mode);
                    }
                }
            }
        }
    } else if (wlose == wroot) {
        for (Window* w = wp; w; w = w->parent) {
            w->FocusOutNotify(NotifyPointer, mode);
        }
        wroot->FocusOutNotify(NotifyPointerRoot, mode);

        if (!wgain) {
            wroot->FocusInNotify(NotifyDetailNone, mode);
        } else {
            std::stack<Window*> stack;
            for (Window* w = wgain->parent; w; w = w->parent) {
                stack.push(w);
            }
            while (!stack.empty()) {
                Window* w = stack.top();
                stack.pop();

                w->FocusInNotify(NotifyNonlinearVirtual, mode);
            }

            wgain->FocusInNotify(NotifyNonlinear, mode);

            if (wgain->IsAncestorOf(wp)) {
                for (Window* w = wp; w != wgain; w = w->parent) {
                    stack.push(w);
                }
                while (!stack.empty()) {
                    Window* w = stack.top();
                    stack.pop();

                    w->FocusInNotify(NotifyPointer, mode);
                }
            }
        }
    } else if (!wgain) {
        if (wlose->IsAncestorOf(wp))
            for (Window* w = wp; w != wlose; w = w->parent) {
                w->FocusOutNotify(NotifyPointer, mode);
            }

        wlose->FocusOutNotify(NotifyNonlinear, mode);
        for (Window* w = wlose->parent; w; w = w->parent)
            w->FocusOutNotify(NotifyNonlinearVirtual, mode);
        wroot->FocusInNotify(NotifyDetailNone, mode);
    } else if (wgain == wroot) {
        if (wlose->IsAncestorOf(wp))
            for (Window* w = wp; w != wlose; w = w->parent) {
                w->FocusOutNotify(NotifyPointer, mode);
            }

        wlose->FocusOutNotify(NotifyNonlinear, mode);
        for (Window* w = wlose->parent; w; w = w->parent) {
            w->FocusOutNotify(NotifyNonlinearVirtual, mode);
        }
        wroot->FocusInNotify(6, mode);

        std::stack<Window*> stack;
        for (Window* w = wp; w; w = w->parent) {
            stack.push(w);
        }
        while (!stack.empty()) {
            Window* w = stack.top();
            stack.pop();

            w->FocusInNotify(NotifyPointer, mode);
        }
    } else if (wgain->IsAncestorOf(wlose)) {
        wlose->FocusOutNotify(0, mode);

        for (Window* w = wlose->parent; w != wgain; w = w->parent) {
            w->FocusOutNotify(NotifyVirtual, mode);
        }
        wgain->FocusInNotify(NotifyInferior, mode);

        if (wgain->IsAncestorOf(wp) &&
            (wp != wlose && !wp->IsAncestorOf(wlose) && !wp->IsInferiorOf(wlose))) {
            std::stack<Window*> stack;

            for (Window* w = wp; w != wgain; w = w->parent) stack.push(w);

            while (!stack.empty()) {
                Window* w = stack.top();
                stack.pop();

                w->FocusInNotify(NotifyPointer, mode);
            }
        }
    } else if (wlose->IsAncestorOf(wgain)) {
        if (wlose->IsAncestorOf(wp) &&
            (wp != wgain && !wp->IsAncestorOf(wgain) && !wp->IsInferiorOf(wgain))) {
            for (Window* w = wp; w != wlose; w = w->parent) {
                w->FocusOutNotify(NotifyPointer, mode);
            }
        }
        wlose->FocusOutNotify(NotifyInferior, mode);

        std::stack<Window*> stack;

        for (Window* w = wgain->parent; w != wlose; w = w->parent) {
            stack.push(w);
        }
        while (!stack.empty()) {
            Window* w = stack.top();
            stack.pop();

            w->FocusInNotify(NotifyVirtual, mode);
        }

        wgain->FocusInNotify(NotifyAncestor, mode);
    } else {
        if (wlose->IsAncestorOf(wp))
            for (Window* w = wp; w != wlose; w = w->parent) {
                w->FocusOutNotify(NotifyPointer, mode);
            }

        wlose->FocusOutNotify(NotifyNonlinear, 0);

        Window* lca = nullptr;
        std::stack<Window*> stack;

        for (Window* w = wlose->parent; w != wgain; w = w->parent) {
            if (w->IsAncestorOf(wgain)) {
                lca = w;
                break;
            }
            w->FocusOutNotify(NotifyNonlinearVirtual, mode);
        }

        for (Window* w = wgain->parent; w != lca; w = w->parent) {
            stack.push(w);
        }
        while (!stack.empty()) {
            Window* w = stack.top();
            stack.pop();

            w->FocusInNotify(NotifyNonlinearVirtual, mode);
        }

        wgain->FocusInNotify(NotifyNonlinear, mode);

        if (wgain->IsAncestorOf(wp)) {
            for (Window* w = wp; w != wgain; w = w->parent) {
                stack.push(w);
            }
            while (!stack.empty()) {
                Window* w = stack.top();
                stack.pop();

                w->FocusInNotify(NotifyPointer, mode);
            }
        }
    }
}

void WindowManager::RevertFocus(Window* window) {
    if (window == grab_keyboard_window) {
        Window* pw = GetPointerWindow(mouse.GetX(), mouse.GetY());
        FocusInOutNotify(grab_keyboard_window, focus_window, pw, root_window.get(), NotifyUngrab);
        grab_keyboard_window = nullptr;
        grab_keyboard_client = nullptr;
    }
    if (window == focus_window) {
        Window* pw = GetPointerWindow(mouse.GetX(), mouse.GetY());

        if (focus_revert_to == RevertToNone) {
            focus_window = nullptr;
        } else if (focus_revert_to == RevertToPointerRoot) {
            focus_window = root_window.get();
        } else if (focus_revert_to == RevertToParent) {
            focus_window = window->GetParent();
            while (!focus_window->IsAllAncestorsMapped()) {
                focus_window = focus_window->GetParent();
            }
        }
        focus_revert_to = RevertToNone;
        FocusInOutNotify(window,
                         focus_window,
                         pw,
                         root_window.get(),
                         grab_keyboard_window ? NotifyWhileGrabbed : NotifyNormal);
    }
}

void WindowManager::DestroyWindow(Window* window, bool release_resource) {
    std::vector<Window*> windows_to_remove;
    RemoveAllSubWindows(window, windows_to_remove);
    window->parent->children.remove(window);
    if (release_resource) {
        for (const auto w : windows_to_remove) {
            window->owner_client->RemoveResource(w->id);
        }
    }
    server.GetScreen().PostInvalidate(ScreenDirtyFlags::kWindow);
}

void WindowManager::RemoveAllSubWindows(Window* window, std::vector<Window*>& windows_to_remove) {
    if (window->IsRoot()) {
        return;
    }
    RemoveCompositeWindow(window);
    if (window->IsMapped()) {
        UnmapWindow(window, false); // DestroyWindow publishes the complete tree once.
    }
    for (const auto w : window->children) {
        RemoveAllSubWindows(w, windows_to_remove);
    }
    window->children.clear();
    for (auto [client, mask] : window->client_masks) {
        client->RemoveEventWindow(window);
    }
    window->client_masks.clear();
    xEvent event{};
    event.u.destroyNotify.event = window->id;
    event.u.destroyNotify.window = window->id;
    event.u.u.type = DestroyNotify;
    window->ForeachEventListeners(StructureNotifyMask,
                                  [&](XClient* client) { client->SendEvent(event); });

    event.u.destroyNotify.event = window->parent->id;
    window->parent->ForeachEventListeners(SubstructureNotifyMask,
                                          [&](XClient* client) { client->SendEvent(event); });
    if (window == grab_pointer_window || window == grab_confine_window) {
        grab_confine_window = nullptr;
        grab_pointer_window = nullptr;
        grab_pointer_client = nullptr;
        grab_cursor = nullptr;
        UpdatePointer(NotifyUngrab);
    } else {
        UpdatePointer(NotifyNormal);
    }
    RevertFocus(window);
    windows_to_remove.push_back(window);
}

void WindowManager::SetInputFocus(Window* window, u8 revert_to, u32 time) {
    FocusInOutNotify(focus_window,
                     window,
                     GetPointerWindow(mouse.GetX(), mouse.GetY()),
                     root_window.get(),
                     grab_keyboard_window ? NotifyWhileGrabbed : NotifyNormal);
    focus_window = window;
    focus_revert_to = revert_to;
    (void)time;
}

void WindowManager::ChangeWindowAttributes(XClient* client,
                                           Window* window,
                                           u32 mask,
                                           const u32* values) {
    const bool redraw = window->GetAttributes().Update(window, mask, values, client);
    if (redraw && window->GetDrawable()) {
        static_cast<NativeDrawable*>(window->GetDrawable())->PublishContent();
        static_cast<NativeScreen&>(server.GetScreen()).StartupWindowUpdated(window->id);
    }
    UpdatePointer(NotifyNormal);
    if (redraw && window->IsMapped()) {
        server.GetScreen().PostInvalidate();
    }
}

u32 WindowManager::GrabPointer(XClient* client,
                               Window* grab_window,
                               Window* confine_window,
                               Cursor* cursor,
                               u32 time,
                               u32 event_mask,
                               bool owner_events,
                               bool psync,
                               bool ksync) {
    if (grab_pointer_window && grab_pointer_client != client) {
        return AlreadyGrabbed;
    }
    grab_pointer_window = grab_window;
    grab_confine_window = confine_window;
    grab_cursor = cursor ? std::static_pointer_cast<Cursor>(server.ShareResource(cursor->id)) : nullptr;
    grab_pointer_client = client;
    grab_event_mask = event_mask;
    grab_pointer_passive = false;
    grab_pointer_owner_events = owner_events;
    grab_pointer_synchronous = psync;
    grab_keyboard_synchronous = ksync;

    UpdatePointer(NotifyGrab);
    return Success;
}

void WindowManager::UngrabPointer(const XClient* client, u32 time) {
    if (client != grab_pointer_client) {
        return;
    }
    grab_pointer_window = nullptr;
    grab_confine_window = nullptr;
    grab_cursor = nullptr;
    grab_pointer_client = nullptr;
    grab_pointer_passive = false;
    UpdatePointer(NotifyUngrab);
}

void WindowManager::UpdatePointer(u32 mode) {
    UpdatePointerPosition(mouse.GetX(), mouse.GetY(), mode, false);
}

Cursor* WindowManager::UpdatePointerPosition(s32 x, s32 y, u32 mode, bool notify) {
    Cursor* cursor{};
    Window* window{};
    if (grab_confine_window) {
        const auto& in_rect = grab_confine_window->GetInRect();
        if (x < in_rect.left) {
            x = in_rect.left;
        } else if (x >= in_rect.right) {
            x = in_rect.right - 1;
        }
        if (y < in_rect.top) {
            y = in_rect.top;
        } else if (y >= in_rect.bottom) {
            y = in_rect.bottom - 1;
        }
    }
    if (grab_pointer_window) {
        window = grab_pointer_window;
    } else {
        window = GetPointerWindow(x, y);
    }
    if (grab_cursor) {
        cursor = grab_cursor.get();
    } else {
        Window* cursorWindow = GetPointerWindow(x, y);
        if (grab_pointer_window && cursorWindow != grab_pointer_window &&
            !grab_pointer_window->IsAncestorOf(cursorWindow)) cursorWindow = grab_pointer_window;
        cursor = cursorWindow->GetCursor();
    }
    server.GetScreen().UpdateCursor(cursor->GetDrawable(), x - cursor->GetHotspotX(), y - cursor->GetHotspotY());
    if (window != motion_window) {
        motion_window->LeaveEnterNotify(x, y, window, mode);
        motion_window = window;
    } else if (notify) {
        if (!grab_pointer_window) {
            window->PointerMotionNotify(x, y, GetButtonMasks(), nullptr);
        } else {
            ASSERT(!grab_pointer_synchronous);
            grab_pointer_window->GrabPointerMoveNotify(x,
                                                       y,
                                                       GetButtonMasks(),
                                                       grab_pointer_client,
                                                       grab_event_mask,
                                                       grab_pointer_owner_events);
        }
    }
    return cursor;
}

void WindowManager::UpdatePointerButton(u16 button, bool down) {
    const s32 x = mouse.GetX();
    const s32 y = mouse.GetY();
    if (!grab_pointer_window) {
        Window* w = GetPointerWindow(x, y);
        XClient* recipient = nullptr;
        Window* target = w->ButtonNotify(down, x, y, button, nullptr, &recipient);
        // X11 starts an implicit active grab after a delivered ButtonPress.
        // A drag must keep receiving motion/release after leaving the window.
        if (down && target && recipient) {
            const u32 mask = target->GetEventMask(recipient);
            GrabPointer(recipient, target, nullptr, nullptr, 0, mask,
                        bool(mask & OwnerGrabButtonMask), false, false);
            grab_pointer_passive = true;
        }
    } else {
        ASSERT(!grab_pointer_synchronous);
        grab_pointer_window->GrabButtonNotify(down,
                                              x,
                                              y,
                                              button,
                                              grab_pointer_client,
                                              grab_event_mask,
                                              grab_pointer_owner_events);
    }
    if (!down && grab_pointer_passive && mouse.GetButtons() == 0)
        UngrabPointer(grab_pointer_client, 0);
}

void WindowManager::OnPointerMove(s32 x, s32 y) {
    // UpdateCursor already publishes the cursor snapshot (or a full scene when
    // needed). Rebuilding here repeats that work for every mouse movement.
    UpdatePointerPosition(x, y, 0, true);
}

void WindowManager::OnPointerButtonChange(u16 button, bool down) {
    UpdatePointerButton(button, down);
}

void WindowManager::OnKeyboardKeyChange(u32 keycode, bool down) {
    if (!focus_window && !grab_keyboard_window) return;
    const s32 x = mouse.GetX();
    const s32 y = mouse.GetY();
    if (!grab_keyboard_window) {
        Window* w = GetPointerWindow(x, y);
        if (focus_window->IsAncestorOf(w)) {
            w->KeyNotify(down, x, y, keycode, nullptr);
        } else {
            focus_window->KeyNotify(down, x, y, keycode, nullptr);
        }
    } else {
        ASSERT(!grab_keyboard_synchronous);
        grab_keyboard_window->GrabKeyNotify(down, x, y, keycode, grab_keyboard_client, false);
    }
}

u32 WindowManager::GetButtonMasks() const {
    return mouse.GetButtons() | server.GetKeyboard().GetModifiersMask();
}

XScreen& WindowManager::GetScreen() const { return server.GetScreen(); }

}  // namespace xorg

