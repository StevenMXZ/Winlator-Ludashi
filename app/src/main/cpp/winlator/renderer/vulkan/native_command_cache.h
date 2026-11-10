#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include <type_traits>

// Bounded, allocation-free metadata. Handles belong to their Vulkan frame slot;
// callers must finish that slot's fence before recording/resetting any entry.
inline constexpr size_t NATIVE_COMMAND_MAX_LAYERS = 4;
struct NativeCommandKey {
    std::array<uint64_t, 64> words{};
    size_t size{};
    template<class T> void Add(const T& value) {
        static_assert(std::is_trivially_copyable_v<T> && sizeof(T) <= sizeof(uint64_t));
        uint64_t word{};
        std::memcpy(&word, &value, sizeof(T));
        words.at(size++) = word;
    }
    bool operator==(const NativeCommandKey& other) const {
        return size == other.size && words == other.words;
    }
};
template<class Handle, size_t Slots, size_t Capacity = 12>
class NativeCommandCache {
public:
    struct Entry { NativeCommandKey key; Handle command{}; bool valid{}; };
    Handle Find(size_t slot, const NativeCommandKey& key) const {
        for (const auto& e : entries.at(slot))
            if (e.valid && e.key == key) return e.command;
        return {};
    }
    Entry& Replace(size_t slot) {
        auto& index = next.at(slot);
        auto& entry = entries.at(slot).at(index);
        index = (index + 1) % Capacity;
        entry.valid = false; // A failed recording can never become a cache hit.
        return entry;
    }
    template<class Release> void Clear(Release release) {
        for (auto& slot : entries) for (auto& entry : slot) {
            if (entry.command != Handle{}) release(entry.command);
            entry = {};
        }
        next.fill(0);
    }
private:
    std::array<std::array<Entry, Capacity>, Slots> entries{};
    std::array<size_t, Slots> next{};
};
