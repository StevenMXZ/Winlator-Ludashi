#pragma once

#include <utility>

namespace ludashi::x11 {

class OwnedDescriptor {
public:
    OwnedDescriptor() = default;
    explicit OwnedDescriptor(int fd) noexcept : fd(fd) {}
    ~OwnedDescriptor();
    OwnedDescriptor(const OwnedDescriptor&) = delete;
    OwnedDescriptor& operator=(const OwnedDescriptor&) = delete;

    OwnedDescriptor(OwnedDescriptor&& other) noexcept : fd(other.Take()) {}
    OwnedDescriptor& operator=(OwnedDescriptor&& other) noexcept {
        if (this != &other) Reset(other.Take());
        return *this;
    }

    int Get() const noexcept { return fd; }
    explicit operator bool() const noexcept { return fd >= 0; }
    int Take() noexcept { return std::exchange(fd, -1); }
    void Reset(int replacement = -1) noexcept;

private:
    int fd{-1};
};

} // namespace ludashi::x11
