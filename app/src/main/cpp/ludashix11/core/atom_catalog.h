#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace ludashi::x11 {

// Each display owns its atom names. Access is serialized by the server lock.
class AtomCatalog {
public:
    AtomCatalog() = default;
    AtomCatalog(const AtomCatalog&) = delete;
    AtomCatalog& operator=(const AtomCatalog&) = delete;

    std::string_view Name(uint32_t id) const;
    int32_t Find(std::string_view name) const;
    uint32_t Intern(std::string_view name);
    bool Contains(uint32_t id) const;
    size_t Count() const;

private:
    struct NameHash {
        using is_transparent = void;
        size_t operator()(std::string_view name) const { return std::hash<std::string_view>{}(name); }
    };

    // The map owns the strings. Its node addresses survive rehash.
    std::unordered_map<std::string, uint32_t, NameHash, std::equal_to<>> ids;
    std::vector<std::string_view> names;
};

} // namespace ludashi::x11
