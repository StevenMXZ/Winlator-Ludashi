#include <cassert>
#include <cstdlib>
#include <fcntl.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <unistd.h>

static int closingFd = -1;
static int recycledFd = -1;
static int closeBeforeSeek = -1;

template<class... Args>
long raceSyscall(long operation, Args... args) {
    if (operation == SYS_lseek && closeBeforeSeek >= 0) {
        assert(syscall(SYS_close, closeBeforeSeek) == 0);
        closeBeforeSeek = -1;
    }
    int closed = -1;
    if (operation == SYS_inotify_init1 && closingFd >= 0) {
        closed = closingFd;
        closingFd = -1;
        assert(syscall(SYS_close, closed) == 0);
    }
    long result = syscall(operation, args...);
    if (closed >= 0) {
        assert(result == closed);
        recycledFd = static_cast<int>(result);
    }
    return result;
}

// Force the close precisely between the initial readiness check and watch creation.
#define syscall raceSyscall
#include "fakeinput_wait.h"
#undef syscall

int main() {
    char path[] = "/tmp/fakeinput-reuse-XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    closingFd = fd;
    pollfd pending{fd, POLLIN, 0};
    timespec timeout{1, 0};
    assert(FakeInputWait::poll(&pending, 1, &timeout, nullptr,
        [fd](int source) { return source == fd; }) == 1);
    assert(pending.revents == POLLNVAL && recycledFd == fd);
    assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);

    fd = open(path, O_RDONLY);
    assert(fd >= 0);
    closingFd = fd;
    char byte;
    assert(FakeInputWait::read(fd, &byte, 1,
        [fd](int source) { return source == fd; }) == -1 && errno == EBADF);
    assert(recycledFd == fd);
    assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    fd = open(path, O_RDONLY);
    assert(fd >= 0);
    closeBeforeSeek = fd;
    assert(FakeInputWait::readiness(fd, POLLIN) == POLLNVAL);
    assert(unlink(path) == 0);
    puts("PASS: a closed input FD recycled by inotify returns POLLNVAL/EBADF without hanging");
}
