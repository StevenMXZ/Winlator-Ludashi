#include <sys/uio.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cerrno>
#include <stdexcept>

#include "base/log.h"
#include "base/types.h"
#include "core/io/bufferd_io.h"

namespace xorg {

BufferedInputStream::BufferedInputStream(int sock_fd, size_t buffer_size) : sock_fd(sock_fd) {
    buffer.resize(buffer_size);
}

BufferedInputStream::~BufferedInputStream() { for (int fd : fds) close(fd); }

ssize_t BufferedInputStream::Read(void* data, size_t size) {
    const size_t bytes_pre_copy = std::min(GetCachedSize(), size);
    Consume(data, bytes_pre_copy);
    size -= bytes_pre_copy;
    if (size == 0) {
        return bytes_pre_copy;
    }
    Rewind();
    data = static_cast<uint8_t*>(data) + bytes_pre_copy;
    size_t bytes_copies = bytes_pre_copy;
    if (buffer.size() < size) {
        const ssize_t bytes_read = ReadRaw(data, size);
        if (bytes_read > 0) {
            bytes_copies += bytes_read;
        }
        return bytes_copies;
    }
    const ssize_t bytes_read = ReadRaw(buffer.data() + end, buffer.size());
    if (bytes_read > 0) {
        end += bytes_read;
    }
    const size_t remaining_bytes = std::min(size, GetCachedSize());
    Consume(data, remaining_bytes);
    bytes_copies += remaining_bytes;
    return bytes_copies;
}

ssize_t BufferedInputStream::ReadRaw(void* data, size_t size) {
    constexpr u32 max_fds = 0x10;
    iovec iovmsg = {.iov_base = data, .iov_len = size};
    alignas(cmsghdr) char ctrlmsg[CMSG_SPACE(sizeof(int) * max_fds)]{};

    msghdr msg = {.msg_name = nullptr,
                  .msg_namelen = 0,
                  .msg_iov = &iovmsg,
                  .msg_iovlen = 1,
                  .msg_control = ctrlmsg,
                  .msg_controllen = sizeof(ctrlmsg)};
    ssize_t bytes_read;
    do { bytes_read = recvmsg(sock_fd, &msg, MSG_CMSG_CLOEXEC); } while (bytes_read < 0 && errno == EINTR);
    if (bytes_read == 0) throw std::runtime_error("X11 EOF");
    if (bytes_read < 0) {
        return bytes_read;
    }
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
            const u32 num_fds = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            if (num_fds > 0) {
                for (int i = 0; i < num_fds; i++) {
                    const int fd = reinterpret_cast<int*>(CMSG_DATA(cmsg))[i];
                    fds.push_back(fd);
                }
            }
        }
    }
    return bytes_read;
}

int BufferedInputStream::ConsumeFd() {
    if (fds.empty()) throw std::runtime_error("Missing X11 file descriptor");
    const auto fd = fds.front();
    fds.pop_front();
    return fd;
}

}  // namespace xorg
