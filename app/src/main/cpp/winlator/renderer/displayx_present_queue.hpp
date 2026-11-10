#pragma once

#include <cstdint>
#include <memory>
#include <queue>
#include <unordered_map>
#include <deque>
#include <list>
#include <algorithm>
#include <array>
#include <atomic>
#include <mutex>
#include <cstring>
#include <unistd.h>

struct Drawable;
struct Window;

struct DisplayXPeer {
    explicit DisplayXPeer(int descriptor) : fd(descriptor) {}
    ~DisplayXPeer() { if (fd >= 0) close(fd); }
    const int fd;
    std::atomic_bool connected{true};
    std::mutex outputLock;
};

struct DisplayXPresentRequest {
    Drawable* drawable{};
    int sync_fence{-1};
    uint64_t presentId{UINT64_MAX};
    uint8_t swapchainId{};
    int clientFd{-1};
    Window* window{};
    uint32_t presentMode{1};
    uint32_t imageIndex{};
    uint32_t colorSpace{UINT32_MAX};
    std::array<float, 12> hdr{};
    bool hasHdr{};
    std::shared_ptr<DisplayXPeer> peer;
};

inline bool displayXCompletion(const DisplayXPresentRequest& request, std::array<uint8_t, 25>& packet) {
    if (request.clientFd < 0 || (request.presentId == UINT64_MAX && request.presentMode != 2)) return false;
    const uint32_t code = 4, count = 1;
    memcpy(packet.data(), &code, 4); memcpy(packet.data() + 4, &count, 4);
    memcpy(packet.data() + 8, &request.swapchainId, 1);
    memcpy(packet.data() + 9, &request.presentId, 8);
    memcpy(packet.data() + 17, &count, 4); memcpy(packet.data() + 21, &request.imageIndex, 4);
    return true;
}

// Access is serialized by DisplayX::presentLock. Dropped requests own their fence.
class DisplayXPresentQueue {
    using Request = std::unique_ptr<DisplayXPresentRequest>;
    using Order = std::list<Window*>;
    struct Pending {
        std::deque<Request> requests;
        Order::iterator position;
    };
    std::unordered_map<Window*, Pending> pending;
    Order order;

    static void discard(std::unique_ptr<DisplayXPresentRequest> request) {
        if (request && request->sync_fence >= 0) close(request->sync_fence);
    }

public:
    ~DisplayXPresentQueue() {
        for (auto& entry : pending)
            for (auto& request : entry.second.requests) discard(std::move(request));
    }

    void push(Request request, bool mailbox = true) {
        if (!request || !request->window) {
            discard(std::move(request));
            return;
        }
        auto [entry, inserted] = pending.try_emplace(request->window);
        auto& queue = entry->second.requests;
        if (inserted) {
            order.push_back(request->window);
            entry->second.position = std::prev(order.end());
        } else {
            order.splice(order.end(), order, entry->second.position);
        }
        if (mailbox) {
            // A normal window refresh must never discard a FIFO producer image.
            if (request->clientFd < 0 && std::any_of(queue.begin(), queue.end(),
                    [](const Request& item) { return item->presentMode == 2; })) {
                discard(std::move(request)); return;
            }
            while (!queue.empty() && queue.back()->presentMode != 2) {
                discard(std::move(queue.back())); queue.pop_back();
            }
        }
        queue.push_back(std::move(request));
    }

    Request pop() { return empty() ? nullptr : take(order.front()); }

    std::deque<Request> getLast() {
        std::deque<Request> result;
        if (!empty()) result.push_back(take(order.back()));
        return result;
    }

    std::deque<Request> buildWindowTree() {
        std::deque<Request> result;
        // Submit at most one FIFO image per window in each transaction.
        for (auto current = order.begin(); current != order.end();) {
            Window* window = *current++;
            result.push_back(take(window));
        }
        return result;
    }

    void removeWindow(Window* window) {
        auto entry = pending.find(window);
        if (!window || entry == pending.end()) return;
        for (auto& request : entry->second.requests) discard(std::move(request));
        order.erase(entry->second.position);
        pending.erase(entry);
    }

    void removeDrawable(Drawable* drawable) {
        for (auto entry = pending.begin(); entry != pending.end();) {
            auto& queue = entry->second.requests;
            for (auto item = queue.begin(); item != queue.end();) {
                if ((*item)->drawable == drawable) {
                    discard(std::move(*item)); item = queue.erase(item);
                } else ++item;
            }
            if (queue.empty()) {
                order.erase(entry->second.position); entry = pending.erase(entry);
            } else ++entry;
        }
    }

    void clear() {
        for (auto& entry : pending)
            for (auto& request : entry.second.requests) discard(std::move(request));
        pending.clear(); order.clear();
    }
    bool hasFifo() const {
        for (const auto& entry : pending)
            for (const auto& item : entry.second.requests) if (item->presentMode == 2) return true;
        return false;
    }
    bool empty() const { return pending.empty(); }

private:
    Request take(Window* window) {
        auto entry = pending.find(window);
        auto& queue = entry->second.requests;
        auto request = std::move(queue.front()); queue.pop_front();
        if (queue.empty()) {
            order.erase(entry->second.position); pending.erase(entry);
        } else {
            order.splice(order.begin(), order, entry->second.position);
        }
        return request;
    }
};
