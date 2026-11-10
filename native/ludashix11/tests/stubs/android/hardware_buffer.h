#pragma once
#include <stdint.h>
#include <atomic>
#include <vector>
#include <sys/socket.h>
#include <unistd.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
struct AHardwareBuffer_Desc { uint32_t width{}, height{}, layers{}, format{}; uint64_t usage{}; uint32_t stride{}, rfu0{}; uint64_t rfu1{}; };
struct AHardwareBuffer { AHardwareBuffer_Desc desc; std::atomic<int> refs{1}; std::vector<uint8_t> pixels; };
#define AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN 3
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN 48
inline void AHardwareBuffer_acquire(AHardwareBuffer* b) { ++b->refs; }
inline void AHardwareBuffer_release(AHardwareBuffer* b) {
    if (--b->refs != 0) return;
    if (const char* delay = std::getenv("LUDASHI_HOST_RELEASE_DELAY_MS")) {
        std::fprintf(stderr, "HostBufferRelease begin\n");
        std::fflush(stderr);
        std::this_thread::sleep_for(std::chrono::milliseconds(std::atoi(delay)));
        std::fprintf(stderr, "HostBufferRelease end\n");
    }
    delete b;
}
inline void AHardwareBuffer_describe(AHardwareBuffer* b, AHardwareBuffer_Desc* d) { *d = b->desc; }
inline int AHardwareBuffer_lock(AHardwareBuffer* b, uint64_t, int, void*, void** p) { *p = b->pixels.data(); return 0; }
inline int AHardwareBuffer_unlock(AHardwareBuffer*, int*) { return 0; }
inline int AHardwareBuffer_recvHandleFromUnixSocket(int fd, AHardwareBuffer** p) {
    AHardwareBuffer_Desc d{};
    if (recv(fd, &d, sizeof(d), MSG_WAITALL) != sizeof(d) || !d.width || !d.height || d.width > 8192 || d.height > 8192) return -1;
    auto* b = new AHardwareBuffer; b->desc = d; b->pixels.resize(size_t(d.stride) * d.height * 4); *p = b; return 0;
}

