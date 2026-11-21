#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include "base/owned_descriptor.h"

namespace ludashi::x11 {

struct MappedSegment final {
    MappedSegment(int id, void* address, size_t bytes) : id(id), addr(address), size(bytes) {}
    ~MappedSegment();
    MappedSegment(const MappedSegment&) = delete;
    MappedSegment& operator=(const MappedSegment&) = delete;

    const int id;
    void* const addr;
    const size_t size;
};

// Uses the five-byte GET_FD message implemented by SysVSHMRequestHandler.java.
// Aliases share one mapping, whose lifetime extends through in-flight accesses.
class SysvSegments {
public:
    explicit SysvSegments(std::string path) : path(std::move(path)) {}
#ifdef LUDASHI_HOST_TEST
    explicit SysvSegments(int fd) : channel(fd) {}
#endif

    bool Attach(uint32_t xid, int systemId);
    bool Detach(uint32_t xid);
    std::shared_ptr<const MappedSegment> Get(uint32_t xid) const;

private:
    bool Connect();
    OwnedDescriptor RequestDescriptor(int systemId);
    std::shared_ptr<const MappedSegment> Map(int systemId);
    void RemoveUnused(int systemId);

    const std::string path;
    OwnedDescriptor channel;
    mutable std::mutex mutex;
    std::unordered_map<uint32_t, std::shared_ptr<const MappedSegment>> segments;
    std::unordered_map<int, std::weak_ptr<const MappedSegment>> mappings;
};

} // namespace ludashi::x11
