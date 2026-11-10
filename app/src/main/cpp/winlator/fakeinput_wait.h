#pragma once
#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <new>
#include <poll.h>
#include <signal.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace FakeInputWait {
struct Watch {
    int fd = -1;
    ~Watch() { if (fd >= 0) { int saved = errno; syscall(SYS_close, fd); errno = saved; } }
    bool add(int source) {
        if (fd < 0) fd = syscall(SYS_inotify_init1, IN_NONBLOCK | IN_CLOEXEC);
        if (fd < 0) return false;
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", source);
        return syscall(SYS_inotify_add_watch, fd, path,
            IN_MODIFY | IN_ATTRIB | IN_DELETE_SELF | IN_MOVE_SELF | IN_CLOSE_WRITE | IN_CLOSE_NOWRITE) >= 0;
    }
    void drain() {
        alignas(inotify_event) char events[4096];
        while (syscall(SYS_read, fd, events, sizeof(events)) > 0) {}
    }
};
inline int kernelPoll(pollfd* fds, nfds_t count, const timespec* timeout, const sigset_t* mask) {
    // Linux's kernel signal set is 64 bits, including when libc sigset_t is larger.
    timespec copy{};
    if (timeout) copy = *timeout;
    return syscall(SYS_ppoll, fds, count, timeout ? &copy : nullptr, mask, sizeof(uint64_t));
}
inline int64_t now() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec) * 1000000000 + t.tv_nsec;
}
inline short readiness(int fd, short requested, int watchFd = -1) {
    // A concurrent close can free the input FD just before inotify_init1 reuses it.
    // Never treat our own notification descriptor as the original input device.
    if (fd == watchFd) return POLLNVAL;
    struct stat st{};
    if (syscall(SYS_fstat, fd, &st) < 0) return POLLNVAL;
    if (!st.st_nlink) return POLLHUP | POLLERR;
    off_t offset = syscall(SYS_lseek, fd, 0, SEEK_CUR);
    if (offset < 0) return errno == EBADF ? POLLNVAL : POLLERR;
    short ready = requested & (POLLOUT | POLLWRNORM);
    if (st.st_size > offset) ready |= requested & (POLLIN | POLLRDNORM);
    return ready;
}
template<class Tracked>
int poll(pollfd* fds, nfds_t count, const timespec* timeout, const sigset_t* mask, Tracked tracked) try {
    if (timeout && (timeout->tv_sec < 0 || timeout->tv_nsec < 0 || timeout->tv_nsec >= 1000000000)) {
        errno = EINVAL; return -1;
    }
    bool any = false;
    for (nfds_t i = 0; i < count; ++i) if (fds[i].fd >= 0 && tracked(fds[i].fd)) { any = true; break; }
    if (!any) return kernelPoll(fds, count, timeout, mask);
    std::vector<pollfd> real(fds, fds + count);
    std::vector<bool> fake(count, false);
    size_t realCount = count;
    for (nfds_t i = 0; i < count; ++i) if (fds[i].fd >= 0 && tracked(fds[i].fd)) {
        fake[i] = true; real[i].fd = -1; --realCount;
    }
    const int64_t start = now();
    const int64_t duration = timeout
        ? (timeout->tv_sec > (INT64_MAX / 1000000000 - 1) ? INT64_MAX
            : int64_t(timeout->tv_sec) * 1000000000 + timeout->tv_nsec)
        : INT64_MAX;
    // Data already pending and nonblocking polls need no watch or wake descriptor.
    int immediate = 0;
    for (nfds_t i = 0; i < count; ++i) {
        fds[i].revents = fake[i] ? readiness(fds[i].fd, fds[i].events) : 0;
        if (fds[i].revents) ++immediate;
    }
    timespec zero{};
    if (realCount || mask) {
        if (kernelPoll(real.data(), real.size(), &zero, mask) < 0) return -1;
        for (nfds_t i = 0; i < count; ++i) if (!fake[i]) {
            fds[i].revents = real[i].revents;
            if (fds[i].revents) ++immediate;
        }
    }
    if (immediate || duration == 0) return immediate;
    Watch watch;
    bool watched = true;
    for (nfds_t i = 0; i < count; ++i) if (fake[i] && !watch.add(fds[i].fd)) watched = false;
    if (watch.fd >= 0) real.push_back({watch.fd, POLLIN, 0});
    for (;;) {
        if (watch.fd >= 0) watch.drain();
        int ready = 0;
        for (nfds_t i = 0; i < count; ++i) {
            fds[i].revents = fake[i] ? readiness(fds[i].fd, fds[i].events, watch.fd) : 0;
            if (fds[i].revents) ++ready;
        }
        const int64_t elapsed = now() - start;
        const int64_t remaining = duration > elapsed ? duration - elapsed : 0;
        // Notifications wake immediately. The watchdog also observes a concurrent
        // close; a bounded fallback handles systems that refuse inotify watches.
        int64_t delay = ready ? 0 : std::min(remaining, watched ? int64_t(1000000000) : int64_t(1000000));
        timespec wait{time_t(delay / 1000000000), long(delay % 1000000000)};
        int result = kernelPoll(real.data(), real.size(), &wait, mask);
        if (result < 0) return -1;
        for (nfds_t i = 0; i < count; ++i) if (!fake[i]) {
            fds[i].revents = real[i].revents;
            if (fds[i].revents) ++ready;
        }
        if (ready) return ready;
        // Recheck data after every wake, including the deadline, before timing out.
        for (nfds_t i = 0; i < count; ++i) if (fake[i]) {
            fds[i].revents = readiness(fds[i].fd, fds[i].events, watch.fd);
            if (fds[i].revents) ++ready;
        }
        if (ready) return ready;
        if (now() - start >= duration) return 0;
    }
} catch (const std::bad_alloc&) { errno = ENOMEM; return -1; }
template<class Tracked>
ssize_t read(int fd, void* buffer, size_t count, Tracked tracked) {
    if (!count) return 0;
    for (;;) {
        struct stat st{};
        if (syscall(SYS_fstat, fd, &st) < 0) return -1;
        if (!st.st_nlink) { errno = tracked(fd) ? ENODEV : EBADF; return -1; }
        ssize_t result = syscall(SYS_read, fd, buffer, count);
        if (result != 0) return result;
        int flags = fcntl(fd, F_GETFL);
        if (flags < 0) return -1;
        if (flags & O_NONBLOCK) { errno = EAGAIN; return -1; }
        pollfd pending{fd, POLLIN, 0};
        if (poll(&pending, 1, nullptr, nullptr, tracked) < 0) return -1;
        if (pending.revents & POLLNVAL) { errno = EBADF; return -1; }
        if (pending.revents & (POLLHUP | POLLERR)) { errno = tracked(fd) ? ENODEV : EBADF; return -1; }
    }
}
}
