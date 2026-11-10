#pragma once

#include <condition_variable>
#include <stop_token>
#include <queue>

template <typename T> class LockingQueue {
public:
    void Push(T const& data) {
        {
            std::lock_guard lock(guard);
            queue.push(data);
        }
        cv.notify_one();
    }

    bool Empty() const {
        std::lock_guard lock(guard);
        return queue.empty();
    }

    bool TryPop(T& value) {
        std::lock_guard lock(guard);
        if (queue.empty()) {
            return false;
        }

        value = queue.front();
        queue.pop();
        return true;
    }

    void WaitAndPop(T& value) {
        std::unique_lock lock(guard);
        cv.wait(lock, [&] { return !queue.empty(); });

        value = queue.front();
        queue.pop();
    }

    bool WaitAndPop(T& value, const std::stop_token& stop_token) {
        std::unique_lock lock(guard);

        std::stop_callback callback(stop_token, [&] { cv.notify_all(); });
        cv.wait(lock, stop_token, [&] { return !queue.empty() || stop_token.stop_requested(); });

        if (stop_token.stop_requested()) {
            return false;
        }

        value = queue.front();
        queue.pop();
        return true;
    }

    bool TryWaitAndPop(T& value, uint64_t timeout) {
        std::unique_lock lock(guard);

        if (!cv.wait_for(
                    lock, std::chrono::milliseconds(timeout), [&] { return !queue.empty(); })) {
            return false;
        }

        value = queue.front();
        queue.pop();
        return true;
    }

private:
    std::queue<T> queue;
    mutable std::mutex guard;
    std::condition_variable_any cv;
};