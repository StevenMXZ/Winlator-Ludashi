#include "core/sysv_segments.h"

#include <array>
#include <cerrno>
#include <cstring>
#include <limits>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <linux/ashmem.h>
#include <sys/ioctl.h>
#endif

namespace ludashi::x11 {

MappedSegment::~MappedSegment() {
    munmap(addr, size);
}

std::shared_ptr<const MappedSegment> SysvSegments::Get(uint32_t xid) const {
    std::lock_guard guard(mutex);
    const auto segment = segments.find(xid);
    return segment == segments.end() ? nullptr : segment->second;
}

bool SysvSegments::Attach(uint32_t xid, int systemId) {
    std::lock_guard guard(mutex);
    const auto cached = mappings.find(systemId);
    auto mapping = cached == mappings.end() ? nullptr : cached->second.lock();
    if (!mapping) {
        // Detached mappings can remain alive in readers. Retain a weak entry
        // for reuse and occasionally remove the records that finally expired.
        if (mappings.size() > 32 && mappings.size() / 2 > segments.size())
            std::erase_if(mappings, [](const auto& item) { return item.second.expired(); });
        mapping = Map(systemId);
    }
    if (!mapping) return false;

    const auto previous = segments.find(xid);
    const int previousId = previous == segments.end() ? systemId : previous->second->id;
    mappings.insert_or_assign(systemId, mapping);
    segments.insert_or_assign(xid, std::move(mapping));
    if (previousId != systemId) RemoveUnused(previousId);
    return true;
}

bool SysvSegments::Detach(uint32_t xid) {
    std::lock_guard guard(mutex);
    const auto segment = segments.find(xid);
    if (segment == segments.end()) return false;
    const int systemId = segment->second->id;
    segments.erase(segment);
    RemoveUnused(systemId);
    return true;
}

void SysvSegments::RemoveUnused(int systemId) {
    const auto entry = mappings.find(systemId);
    if (entry != mappings.end() && entry->second.expired()) mappings.erase(entry);
}

std::shared_ptr<const MappedSegment> SysvSegments::Map(int systemId) {
    OwnedDescriptor descriptor(RequestDescriptor(systemId));
    if (!descriptor) return {};

    struct stat attributes{};
    size_t bytes = 0;
    if (fstat(descriptor.Get(), &attributes) == 0 && attributes.st_size > 0 &&
        uint64_t(attributes.st_size) <= std::numeric_limits<size_t>::max())
        bytes = static_cast<size_t>(attributes.st_size);
#ifdef __ANDROID__
    if (bytes == 0) {
        const int ashmemSize = ioctl(descriptor.Get(), ASHMEM_GET_SIZE, nullptr);
        if (ashmemSize > 0) bytes = static_cast<size_t>(ashmemSize);
    }
#endif
    if (bytes == 0) return {};
    void* address = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor.Get(), 0);
    if (address == MAP_FAILED) return {};
    try {
        return std::make_shared<MappedSegment>(systemId, address, bytes);
    } catch (...) {
        munmap(address, bytes);
        throw;
    }
}

bool SysvSegments::Connect() {
    if (channel) return true;
    sockaddr_un address{};
    if (path.empty() || path.size() >= sizeof(address.sun_path)) return false;
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);
    OwnedDescriptor connection(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
    if (!connection) return false;
    // Bound a stalled local service while the X11 thread owns its display lock.
    const timeval timeout{1, 0};
    for (int option : {SO_SNDTIMEO, SO_RCVTIMEO}) {
        if (setsockopt(connection.Get(), SOL_SOCKET, option, &timeout, sizeof(timeout)) < 0)
            return false;
    }
    int result;
    do {
        result = connect(connection.Get(), reinterpret_cast<sockaddr*>(&address), sizeof(address));
    } while (result < 0 && errno == EINTR);
    if (result < 0) return false;
    channel = std::move(connection);
    return true;
}

OwnedDescriptor SysvSegments::RequestDescriptor(int systemId) {
    if (!Connect()) return {};
    std::array<uint8_t, 5> request{1};
    static_assert(sizeof(systemId) == 4);
    std::memcpy(request.data() + 1, &systemId, sizeof(systemId));
    size_t offset = 0;
    while (offset < request.size()) {
        const ssize_t bytes = send(channel.Get(), request.data() + offset, request.size() - offset, MSG_NOSIGNAL);
        if (bytes > 0) {
            offset += bytes;
        } else if (bytes < 0 && errno == EINTR) {
            continue;
        } else {
            channel.Reset();
            return {};
        }
    }

    uint8_t response{};
    iovec payload{&response, sizeof(response)};
    alignas(cmsghdr) uint8_t control[CMSG_SPACE(16 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &payload;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t bytes;
    do {
        bytes = recvmsg(channel.Get(), &message, MSG_CMSG_CLOEXEC);
    } while (bytes < 0 && errno == EINTR);

    OwnedDescriptor received;
    size_t descriptorCount = 0;
    for (auto* header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS) continue;
        const auto* descriptors = reinterpret_cast<const int*>(CMSG_DATA(header));
        const size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t index = 0; index < count; ++index) {
            if (descriptorCount++ == 0) received.Reset(descriptors[index]);
            else close(descriptors[index]);
        }
    }
    if (bytes != sizeof(response) || descriptorCount > 1 || (message.msg_flags & MSG_CTRUNC)) {
        channel.Reset();
        return {};
    }
    return received;
}

} // namespace ludashi::x11
