#include "core/sysv_segments.h"

#include <atomic>
#include <cassert>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

int main() {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    const int memory = memfd_create("ludashi-shm-lifetime", MFD_CLOEXEC);
    assert(memory >= 0 && ftruncate(memory, 4096) == 0);
    const uint32_t value = 0x12345678;
    assert(pwrite(memory, &value, sizeof(value), 0) == sizeof(value));

    std::atomic<unsigned> requests{};
    std::thread service([&] {
        uint8_t request[5];
        while (recv(sockets[1], request, sizeof(request), MSG_WAITALL) == sizeof(request)) {
            assert(request[0] == 1);
            int id;
            std::memcpy(&id, request + 1, sizeof(id));
            ++requests;
            uint8_t response{};
            iovec payload{&response, 1};
            alignas(cmsghdr) uint8_t control[CMSG_SPACE(sizeof(int))]{};
            msghdr message{};
            message.msg_iov = &payload;
            message.msg_iovlen = 1;
            if (id == 1) {
                message.msg_control = control;
                message.msg_controllen = sizeof(control);
                auto* header = CMSG_FIRSTHDR(&message);
                header->cmsg_level = SOL_SOCKET;
                header->cmsg_type = SCM_RIGHTS;
                header->cmsg_len = CMSG_LEN(sizeof(int));
                std::memcpy(CMSG_DATA(header), &memory, sizeof(memory));
            }
            assert(sendmsg(sockets[1], &message, MSG_NOSIGNAL) == 1);
        }
        close(sockets[1]);
    });

    close(STDIN_FILENO);
    {
        ludashi::x11::SysvSegments manager(sockets[0]);
        assert(manager.Attach(1, 1));
        assert(fcntl(STDIN_FILENO, F_GETFD) == -1);
        auto first = manager.Get(1);
        assert(first && *static_cast<const uint32_t*>(first->addr) == value);
        assert(manager.Attach(2, 1));
        assert(manager.Get(2) == first && requests == 1);
        assert(manager.Detach(1));
        assert(!manager.Attach(2, 99));
        assert(manager.Get(2) == first && requests == 2);
        assert(manager.Detach(2) && !manager.Get(2));
        assert(*static_cast<const uint32_t*>(first->addr) == value);
        assert(manager.Attach(3, 1));
        assert(manager.Get(3) == first && requests == 2);
        assert(manager.Detach(3));
        std::weak_ptr<const ludashi::x11::MappedSegment> lifetime = first;
        first.reset();
        assert(lifetime.expired());
        assert(!manager.Detach(3));
    }
    service.join();
    close(memory);
}
