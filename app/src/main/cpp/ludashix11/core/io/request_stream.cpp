#include "request_stream.h"

#include <algorithm>
#include <bit>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <sys/socket.h>
#include <unistd.h>
#include <X11/Xproto.h>

namespace ludashi::x11 {
namespace {

constexpr size_t maximumFrameSize = 16 * 1024 * 1024;
constexpr size_t readSize = 16 * 1024;
constexpr size_t descriptorCapacity = 16;
static_assert(std::endian::native == std::endian::little);

template<class Value>
Value ReadInteger(const uint8_t* data) {
    Value value;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

size_t PaddedSize(size_t size) {
    return (size + 3) & ~size_t{3};
}

} // namespace

RequestStream::RequestStream(int socket) : socket(socket), incoming(readSize) {}

RequestStream::~RequestStream() {
    for (int fd : descriptors) close(fd);
}

std::span<uint8_t> RequestStream::ReadSetup() {
    if (!EnsureAvailable(sz_xConnClientPrefix)) return {};
    const auto* prefix = incoming.data() + head;
    if (prefix[0] != 'l') throw std::runtime_error("Unsupported X11 byte order");
    const size_t authName = ReadInteger<uint16_t>(prefix + 6);
    const size_t authData = ReadInteger<uint16_t>(prefix + 8);
    const size_t bytes = sz_xConnClientPrefix + PaddedSize(authName) + PaddedSize(authData);
    if (!EnsureAvailable(bytes)) return {};
    frameEnd = head + bytes;
    return {incoming.data() + head, bytes};
}

std::span<uint8_t> RequestStream::ReadCommand(bool bigRequests) {
    if (!EnsureAvailable(sz_xReq)) return {};
    size_t words = ReadInteger<uint16_t>(incoming.data() + head + 2);
    const bool extended = words == 0;
    if (extended) {
        if (!bigRequests) throw std::runtime_error("BIG-REQUESTS was not enabled");
        if (!EnsureAvailable(8)) return {};
        words = ReadInteger<uint32_t>(incoming.data() + head + 4);
        if (words < 2 || words > maximumFrameSize / 4)
            throw std::runtime_error("Invalid BIG-REQUESTS length");
    }

    const size_t bytes = words * 4;
    if (!EnsureAvailable(bytes)) return {};
    frameEnd = head + bytes;
    auto* frame = incoming.data() + head;
    if (extended) {
        // Move the four-byte header over the wide length field. The payload
        // stays in place, including for large image uploads.
        std::memcpy(frame + 4, frame, 4);
        frame += 4;
        const auto length = static_cast<uint16_t>(std::min<size_t>(UINT16_MAX, words - 1));
        std::memcpy(frame + 2, &length, sizeof(length));
    }
    return {frame, bytes - (extended ? 4 : 0)};
}

void RequestStream::Consume() {
    head = frameEnd;
    if (head == tail) head = tail = 0;
}

bool RequestStream::EnsureAvailable(size_t required) {
    while (tail - head < required) {
        if (incoming.size() - head < required) {
            std::memmove(incoming.data(), incoming.data() + head, tail - head);
            tail -= head;
            head = 0;
            if (incoming.size() < required) {
                incoming.resize(std::min(maximumFrameSize, std::max(required, incoming.size() * 2)));
            }
        }

        alignas(cmsghdr) unsigned char control[CMSG_SPACE(descriptorCapacity * sizeof(int))];
        iovec data{incoming.data() + tail, incoming.size() - tail};
        msghdr message{};
        message.msg_iov = &data;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);

        const ssize_t bytes = recvmsg(socket, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return false;
        if (bytes <= 0) throw std::runtime_error("X11 client socket closed");

        for (auto* header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS) continue;
            const auto* files = reinterpret_cast<const int*>(CMSG_DATA(header));
            const size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(*files);
            for (size_t i = 0; i < count; ++i) descriptors.push_back(files[i]);
        }
        if (message.msg_flags & MSG_CTRUNC)
            throw std::runtime_error("Truncated X11 descriptor message");
        tail += static_cast<size_t>(bytes);
    }
    return true;
}

int RequestStream::TakeDescriptor() {
    if (descriptors.empty()) throw std::runtime_error("Missing X11 file descriptor");
    const int fd = descriptors.front();
    descriptors.pop_front();
    return fd;
}

} // namespace ludashi::x11
