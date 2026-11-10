#include <errno.h>
#include <linux/un.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <linux/ashmem.h>
#endif

#include "base/alignment.h"
#include "base/log.h"
#include "core/shm.h"

namespace xorg {

#define REQUEST_CODE_GET_FD 1

#define MIN_REQUEST_LENGTH 5

ShmManager::ShmManager(const std::string& sock_path) : sock_path(sock_path) {}

ShmManager::~ShmManager() {
    for (auto& [_, entry] : shm_entries) {
        if (entry.addr && entry.addr != MAP_FAILED) munmap(entry.addr, entry.size);
        if (entry.fd >= 0) close(entry.fd);
    }
    if (server_fd >= 0) {
        close(server_fd);
    }
}

bool ShmManager::Attach(u32 xid, int shm_id) {
    std::scoped_lock lock(mutex);
    void* addr = ShmAttach(shm_id);
    if (addr) {
        attach_entries[xid] = {.shmid = shm_id, .addr = addr, .size = shm_entries.at(shm_id).size};
    }
    return addr != nullptr;
}

void ShmManager::Detach(u32 xid) {
    std::scoped_lock lock(mutex);
    const auto it = attach_entries.find(xid);
    if (it == attach_entries.end()) {
        return;
    }
    int shmid = it->second.shmid;
    attach_entries.erase(it);
    if (std::ranges::none_of(attach_entries, [shmid](auto& item) { return item.second.shmid == shmid; })) ShmDetach(shmid);
}

const AttachEntry& ShmManager::Get(u32 xid) {
    std::scoped_lock lock(mutex);
    const auto it = attach_entries.find(xid);
    if (it == attach_entries.end()) throw std::runtime_error("Invalid SHM segment");
    return it->second;
}

void* ShmManager::ShmAttach(int shmid) {
    if (!ConnectServer()) return nullptr;
    auto it = shm_entries.find(shmid);
    if (it == shm_entries.end()) {
        // Winlator's SysVSHM service supports GET_FD, not GET_SIZE. Sending
        // opcode 3 waits forever while holding the X11 lock (including input).
        const int fd = IPCGetFD(shmid);
        if (fd < 0) return nullptr;
        struct stat status{};
        size_t size = fstat(fd, &status) == 0 && status.st_size > 0
                ? static_cast<size_t>(status.st_size) : 0;
#ifdef __ANDROID__
        // Older Android devices use /dev/ashmem; its size is not st_size.
        if (!size) {
            int ashmem_size = ioctl(fd, ASHMEM_GET_SIZE, nullptr);
            if (ashmem_size > 0) size = static_cast<size_t>(ashmem_size);
        }
#endif
        if (!size) { close(fd); return nullptr; }
        shm_entries[shmid] = {
                .id = shmid, .addr = nullptr, .fd = fd, .size = size};
        it = shm_entries.find(shmid);
        ASSERT_MSG(it != shm_entries.end(), "Failed to find shm entry");
    }
    auto& entry = it->second;
    if (entry.addr == nullptr) {
        entry.addr = mmap(nullptr,
                          entry.size,
                          PROT_READ | PROT_WRITE,
                          MAP_SHARED,
                          entry.fd,
                          0);
        if (entry.addr == MAP_FAILED) {
            close(entry.fd);
            shm_entries.erase(it);
            return nullptr;
        }
    }
    return entry.addr;
}

int ShmManager::ShmDetach(int shmid) {
    const auto it = shm_entries.find(shmid);
    if (it == shm_entries.end()) {
        return -1;
    }
    auto& entry = it->second;
    if (entry.addr != nullptr) {
        munmap(entry.addr, entry.size);
        entry.addr = nullptr;
    }
    close(entry.fd);
    shm_entries.erase(it);
    return 0;
}

bool ShmManager::ConnectServer() {
    if (server_fd >= 0) {
        return true;
    }
    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    // A failed local service must not hold the scene/input lock indefinitely.
    timeval timeout{1, 0};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0 ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) {
        close(fd); return false;
    }
    sockaddr_un server_addr{};
    server_addr.sun_family = AF_LOCAL;
    strncpy(server_addr.sun_path, sock_path.c_str(), sizeof(server_addr.sun_path) - 1);

    int res;
    do {
        res = 0;
        if (connect(fd, reinterpret_cast<sockaddr*>(&server_addr), sizeof(sockaddr_un)) < 0) {
            res = -errno;
        }
    } while (res == -EINTR);
    if (res != 0) { close(fd); return false; }
    server_fd = fd;
    return true;
}

int ShmManager::IPCGetFD(int shmid) {
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_GET_FD;
    memcpy(request_data + 1, &shmid, 4);
    size_t sent = 0;
    while (sent < sizeof(request_data)) {
        ssize_t n = send(server_fd, request_data + sent, sizeof(request_data) - sent, MSG_NOSIGNAL);
        if (n > 0) { sent += n; continue; }
        if (n < 0 && errno == EINTR) continue;
        close(server_fd); server_fd = -1; return -1;
    }

    char zero = 0;
    iovec iovmsg = {.iov_base = &zero, .iov_len = 1};
    alignas(cmsghdr) char ctrlmsg[CMSG_SPACE(sizeof(int))]{};

    msghdr msg = {
            .msg_name = nullptr,
            .msg_namelen = 0,
            .msg_iov = &iovmsg,
            .msg_iovlen = 1,
            .msg_control = ctrlmsg,
            .msg_controllen = sizeof(ctrlmsg),
            .msg_flags = 0,
    };

    ssize_t res;
    do { res = recvmsg(server_fd, &msg, MSG_CMSG_CLOEXEC); } while (res < 0 && errno == EINTR);
    int result = -1;
    for (auto* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
            cmsg->cmsg_len < CMSG_LEN(sizeof(int))) continue;
        size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        auto* descriptors = reinterpret_cast<int*>(CMSG_DATA(cmsg));
        for (size_t i = 0; i < count; ++i) {
            if (result < 0 && res == 1 && !(msg.msg_flags & MSG_CTRUNC)) result = descriptors[i];
            else close(descriptors[i]);
        }
    }
    if (res != 1) { close(server_fd); server_fd = -1; }
    return result;
}

}  // namespace xorg
