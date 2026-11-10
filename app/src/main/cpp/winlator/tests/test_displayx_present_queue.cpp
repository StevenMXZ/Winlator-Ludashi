#include "../renderer/displayx_present_queue.hpp"
#include <cassert>
#include <cerrno>
#include <fcntl.h>

static int fence() {
    int fds[2];
    assert(pipe(fds) == 0);
    close(fds[1]);
    return fds[0];
}

static bool closed(int fd) {
    errno = 0;
    return fcntl(fd, F_GETFD) == -1 && errno == EBADF;
}

static std::unique_ptr<DisplayXPresentRequest> request(Window* window, int fd) {
    auto result = std::make_unique<DisplayXPresentRequest>();
    result->window = window;
    result->sync_fence = fd;
    return result;
}

int main() {
    int storage[3]{};
    auto* a = reinterpret_cast<Window*>(&storage[0]);
    auto* b = reinterpret_cast<Window*>(&storage[1]);
    auto* c = reinterpret_cast<Window*>(&storage[2]);
    const int first = fence(), second = fence(), duplicate = fence();
    int remaining;
    {
        DisplayXPresentQueue queue;
        queue.push(request(a, first));
        queue.push(request(b, second));
        queue.push(request(a, duplicate));
        assert(closed(first) && !closed(duplicate));
        queue.removeWindow(nullptr);
        queue.removeWindow(c);
        assert(!closed(duplicate) && !closed(second));
        queue.removeWindow(a);
        assert(closed(duplicate) && !closed(second));
        auto retained = queue.pop();
        assert(retained && retained->window == b && queue.empty());
        close(retained->sync_fence);
        remaining = fence();
        queue.push(request(a, remaining));
        assert(!queue.empty());
    }
    assert(closed(remaining));
    {
        DisplayXPresentQueue queue;
        queue.push(request(a, -1));
        queue.push(request(b, -1));
        queue.push(request(c, -1));
        queue.removeWindow(b);
        assert(queue.pop()->window == a);
        assert(queue.pop()->window == c);
        assert(queue.empty() && !queue.pop());
        int invalid = fence();
        queue.push(request(nullptr, invalid));
        queue.push(nullptr);
        assert(queue.empty() && closed(invalid));
    }
    {
        DisplayXPresentQueue queue;
        auto first = request(a, -1); first->presentMode = 2; first->imageIndex = 1;
        auto second = request(a, -1); second->presentMode = 2; second->imageIndex = 2;
        queue.push(std::move(first), false);
        queue.push(std::move(second), false);
        queue.push(request(b, -1));
        queue.push(request(a, -1)); // A generic refresh cannot discard the FIFO images.
        auto batch = queue.buildWindowTree();
        assert(batch.size() == 2 && !queue.empty());
        assert(batch[0]->window != batch[1]->window);
        for (auto& item : batch) if (item->window == a) assert(item->imageIndex == 1);
        batch = queue.buildWindowTree();
        assert(batch.size() == 1 && batch[0]->imageIndex == 2 && queue.empty());
    }
    {
        DisplayXPresentRequest request;
        std::array<uint8_t, 17> packet{};
        request.clientFd = 9; request.swapchainId = 3; request.imageIndex = 7;
        assert(!displayXCompletion(request, packet)); // Mailbox without a present-wait ID.
        request.presentMode = 2;
        assert(displayXCompletion(request, packet)); // FIFO still needs an image-reuse acknowledgement.
        uint32_t code{}, index{}; uint64_t present{};
        memcpy(&code, packet.data(), 4); memcpy(&present, packet.data() + 5, 8);
        memcpy(&index, packet.data() + 13, 4);
        assert(code == 4 && packet[4] == 3 && present == UINT64_MAX && index == 7);
        request.presentMode = 1; request.presentId = 123;
        assert(displayXCompletion(request, packet));
        request.clientFd = -1; assert(!displayXCompletion(request, packet));
    }
    {
        const int descriptor = fence();
        auto peer = std::make_shared<DisplayXPeer>(descriptor);
        auto pending = request(a, -1); pending->peer = peer;
        peer->connected = false; peer.reset();
        assert(!closed(descriptor)); // An old callback must retain its own FD, never a reused one.
        pending.reset();
        assert(closed(descriptor));
    }
    {
        int images[2]{};
        auto* oldImage = reinterpret_cast<Drawable*>(&images[0]);
        auto* newImage = reinterpret_cast<Drawable*>(&images[1]);
        const int oldFence = fence(), newFence = fence();
        DisplayXPresentQueue queue;
        auto old = request(a, oldFence); old->drawable = oldImage; old->presentMode = 2;
        auto fresh = request(a, newFence); fresh->drawable = newImage; fresh->presentMode = 2;
        queue.push(std::move(old), false); queue.push(std::move(fresh), false);
        queue.removeDrawable(oldImage);
        assert(closed(oldFence) && !closed(newFence));
        auto retained = queue.pop(); assert(retained->drawable == newImage);
        close(retained->sync_fence);
    }
}
