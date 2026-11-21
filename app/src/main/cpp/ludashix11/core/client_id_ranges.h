#pragma once

#include <bit>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace ludashi::x11 {

// The server uses the zero-base range. Client ranges start above these IDs.
namespace ServerID {
inline constexpr uint32_t RootWindow = 1;
inline constexpr uint32_t DefaultCursor = 2;
inline constexpr uint32_t RootVisual = 3;
inline constexpr uint32_t RenderA1 = 4;
inline constexpr uint32_t RenderRgb = 5;
inline constexpr uint32_t RenderArgb = 6;
}

// Each client receives a disjoint range from the 29-bit X resource namespace.
class ClientIdRanges {
public:
    explicit ClientIdRanges(uint32_t maximumClients) {
        if (maximumClients < 2 || maximumClients > (1u << 28))
            throw std::invalid_argument("Invalid X11 client capacity");
        shift = 29 - std::bit_width(maximumClients - 1);
        mask = (1u << shift) - 1;
        bases.resize(maximumClients - 1);
        allocated.resize(maximumClients);
    }

    uint32_t GetMask() const { return mask; }

    uint32_t Acquire() {
        uint32_t slot;
        if (nextUnused < allocated.size()) {
            slot = nextUnused++;
        } else {
            if (available == 0) throw std::runtime_error("X11 client capacity exhausted");
            slot = bases[head];
            ++head;
            if (head == bases.size()) head = 0;
            --available;
        }
        allocated[slot] = true;
        return slot << shift;
    }

    bool IsInInterval(uint32_t id, uint32_t base) const {
        return (id & ~mask) == base;
    }

    void Recycle(uint32_t base) {
        const auto index = base >> shift;
        if ((base & mask) || index >= allocated.size() || !allocated[index])
            throw std::logic_error("Invalid X11 resource range release");
        allocated[index] = false;
        auto tail = head + available;
        if (tail >= bases.size()) tail -= bases.size();
        bases[tail] = index;
        ++available;
    }

private:
    std::vector<uint32_t> bases;
    std::vector<bool> allocated;
    size_t head{};
    size_t available{};
    uint32_t nextUnused{1};
    uint32_t shift{};
    uint32_t mask{};
};

} // namespace ludashi::x11
