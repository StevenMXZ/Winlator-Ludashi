#include <sys/fcntl.h>
#include <unistd.h>
#include "base/fd.h"

namespace base {

int SetNonblockingFD(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) {
        return -1;
    }
    return 0;
}

ScopedFD::~ScopedFD() { Reset(); }

ScopedFD& ScopedFD::operator=(int new_fd) {
    Reset(new_fd);
    return *this;
}

void ScopedFD::Reset(int new_fd) {
    if (fd > 0) {
        close(fd);
    }
    fd = new_fd;
}

void ScopedFD::SetNonblocking() { SetNonblockingFD(fd); }

}  // namespace base