#include <X11/Xproto.h>

#include "core/pointer_sprite.h"
#include "native_drawable.h"
#include "core/request_error.h"

#include "core/key_state.h"
#include "core/window_node.h"
#include "core/window_tree.h"
#include "core/client_session.h"
#include "native_screen.h"

namespace ludashi::x11 {

WindowTree::WindowTree(DisplayRuntime& server, DisplayDimensions screen_info)
        : server(server) {
    const WindowNode::Definition root{
        .width = screen_info.width, .height = screen_info.height, .depth = 24,
    };
    root_window = std::make_shared<WindowNode>(*this, ServerID::RootWindow, root);
    root_window->mapped = true;
    server.AddResource(root_window);

    const auto cursor =
            std::make_shared<PointerSprite>(server.GetScreen(), ServerID::DefaultCursor, 0, 1, 0x000000, 0xffffff);
    server.AddResource(cursor);
    root_window->pointerOverride = cursor;

    focus_window = root_window.get();
    motion_window = root_window.get();
}

sp<WindowNode> WindowTree::CreateWindow(ClientSession* client, u32 id, WindowNode* parent,
                                     s16 x, s16 y, u16 width, u16 height,
                                     u16 border_width, u16 win_class, u8 depth,
                                     u32 mask, const u32* values) {
    if (win_class > InputOnly) throw BadValueError(win_class);
    if (!width || !height) throw BadValueError(0);

    const bool inputOnly = win_class == InputOnly ||
                           (win_class == CopyFromParent && parent->IsInputOnly());
    if (inputOnly) {
        constexpr u32 allowed = CWWinGravity | CWEventMask | CWDontPropagate |
                                CWOverrideRedirect | CWCursor;
        if (depth || border_width || (mask & ~allowed)) throw BadMatchError();
    } else {
        if (parent->IsInputOnly()) throw BadMatchError();
        if (depth == 0) depth = parent->GetDepth();
    }

    const WindowNode::Definition initial{
        .parent = parent, .owner = client, .x = x, .y = y,
        .width = width, .height = height, .border = border_width, .depth = depth, .inputOnly = inputOnly,
    };
    auto window = std::make_shared<WindowNode>(*this, id, initial);
    // Reserve the ID before attaching listeners or publishing a tree node.
    // A rejected ID therefore cannot leave pointers to a destroyed window.
    client->AddResource(window);
    try {
        if (mask) window->ApplyOptions(client, mask, values);
        window->stackPosition = parent->children.insert(parent->children.end(), window.get());
    } catch (...) {
        window->DisconnectSubscribers();
        client->RemoveResource(id);
        throw;
    }
    return window;
}

void WindowTree::RedirectComposite(ClientSession* client, WindowNode* window, u8 update, bool redirect) {
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
void WindowTree::RemoveCompositeClient(ClientSession* client) {
    // Only redirected windows are visited; no resource-tree scan per request.
    for (const auto& r : composite_redirects) if (r.client == client) r.window->composite_redirected = false;
    std::erase_if(composite_redirects, [&](const auto& r) { return r.client == client; });
    for (const auto& r : composite_redirects) r.window->composite_redirected = true;
}
void WindowTree::RemoveCompositeWindow(WindowNode* window) {
    std::erase_if(composite_redirects, [&](const auto& r) { return r.window == window; });
    window->composite_redirected = false;
}
void WindowTree::CompositeCopy(WindowNode* source, WindowNode* destination, s16 x, s16 y) {
    // Same policy as public 4.1 VulkanXServerView.nativeCompositeRedirect.
    if (destination->IsAncestorOf(source)) return;
    WindowNode* sibling = source;
    while (sibling && sibling->parent != destination->parent) sibling = sibling->parent;
    if (!sibling || sibling == destination || !sibling->parent) return;
    auto& children = sibling->parent->children;
    const auto origin = sibling->parent->ContentBounds();
    const s32 newX = destination->placement.x - origin.left + x;
    const s32 newY = destination->placement.y - origin.top + y;
    const bool moved = sibling->placement.x - origin.left != newX ||
                       sibling->placement.y - origin.top != newY;
    const bool below = std::find(std::next(sibling->stackPosition), children.end(), destination) != children.end();
    if (!moved && !below) return; // Repeated copies do no pixel or scene work.
    std::array<u32, 4> values{u32(newX), u32(newY), destination->id, Above};
    ConfigureWindow(nullptr, sibling, CWX | CWY | CWSibling | CWStackMode, values.data());
}

void WindowTree::ChangeWindowAttributes(ClientSession* client,
                                           WindowNode* window,
                                           u32 mask,
                                           const u32* values) {
    const bool redraw = window->ApplyOptions(client, mask, values);
    if (redraw && window->GetDrawable()) {
        window->GetDrawable()->PublishContent();
        server.GetScreen().StartupWindowUpdated(window->id);
    }
    UpdatePointer(NotifyNormal);
    if (redraw && window->IsMapped()) {
        server.GetScreen().PostInvalidate();
    }
}

NativeScreen& WindowTree::GetScreen() const { return server.GetScreen(); }

} // namespace ludashi::x11
