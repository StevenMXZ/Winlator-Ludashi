#pragma once

namespace base {

int SetNonblockingFD(int fd);

class ScopedFD {
public:
    ScopedFD(ScopedFD const&) = delete;

    ScopedFD& operator=(ScopedFD const&) = delete;

    ScopedFD() = default;

    explicit ScopedFD(int fd) : fd(fd) {}

    ~ScopedFD();

    ScopedFD& operator=(int new_fd);

    int Handle() const { return fd; }
    int Release() { int result = fd; fd = -1; return result; }

    bool IsValid() { return fd >= 0; }

    void Reset(int new_fd = -1);

    void SetNonblocking();

private:
    int fd{-1};
};

}  // namespace base
