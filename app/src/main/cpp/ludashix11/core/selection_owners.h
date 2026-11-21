#pragma once

#include <cstddef>
#include <unordered_map>
#include <vector>
#include "base/native_types.h"

namespace ludashi::x11 {

class WindowNode;
class ClientSession;

class SelectionOwners {
public:
    SelectionOwners() = default;
    SelectionOwners(const SelectionOwners&) = delete;
    SelectionOwners& operator=(const SelectionOwners&) = delete;

    struct Owner {
        WindowNode* window{};
        ClientSession* client{};
    };

    Owner Find(u32 atom) const;
    void Claim(u32 atom, WindowNode* window, ClientSession& client, u32 timestamp, u32 now);
    void Release(const ClientSession* client);
    void Release(const WindowNode* window);

private:
    struct Record {
        Owner owner;
        u32 changedAt{};
        std::size_t activeIndex{};
    };

    void Drop(Record& record);

    // Keep timestamps for released atoms. Cleanup visits only active owners.
    std::unordered_map<u32, Record> history;
    std::vector<Record*> active;
};

} // namespace ludashi::x11
