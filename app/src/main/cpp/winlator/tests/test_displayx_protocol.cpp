#include "../renderer/displayx_protocol.hpp"
#include <cassert>
#include <fcntl.h>
#include <sys/socket.h>

static void sendFence(int fd, const std::vector<uint8_t>& bytes, const std::vector<int>& fences) {
    iovec data{const_cast<uint8_t*>(bytes.data()), bytes.size()};
    alignas(cmsghdr) char control[CMSG_SPACE(8 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &data; message.msg_iovlen = 1;
    if (!fences.empty()) {
        message.msg_control = control; message.msg_controllen = CMSG_SPACE(fences.size() * sizeof(int));
        auto *h = CMSG_FIRSTHDR(&message);
        h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS;
        h->cmsg_len = CMSG_LEN(fences.size() * sizeof(int));
        memcpy(CMSG_DATA(h), fences.data(), fences.size() * sizeof(int));
    }
    assert(sendmsg(fd, &message, 0) == ssize_t(bytes.size()));
}

int main() {
    using namespace displayx_wire;
    int pair[2], pipefd[2];
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair) == 0);
    assert(pipe(pipefd) == 0);
    std::vector<uint8_t> present;
    append(present, uint32_t(Present)); append(present, uint8_t(7));
    append(present, uint32_t(2)); append(present, uint64_t(123));
    sendFence(pair[0], present, {pipefd[0]});
    {
        Packet packet;
        assert(receive(pair[1], packet) && packet.size == 17);
        assert(packet.get<uint32_t>(0) == Present && packet.get<uint8_t>(4) == 7);
        assert(packet.get<uint32_t>(5) == 2 && packet.get<uint64_t>(9) == 123);
        assert(packet.fence >= 0 && (fcntl(packet.fence, F_GETFD) & FD_CLOEXEC));
        int fence = packet.takeFence(); assert(packet.fence == -1); close(fence);
    }
    sendFence(pair[0], present, {});
    { Packet packet; assert(receive(pair[1], packet) && packet.fence == -1); }
    sendFence(pair[0], present, {pipefd[0], pipefd[1]});
    { Packet packet; assert(!receive(pair[1], packet) && packet.fence == -1); }
    sendFence(pair[0], std::vector<uint8_t>(100, 0), {pipefd[0]});
    { Packet packet; assert(!receive(pair[1], packet) && packet.fence == -1); }
    auto batch = completion({{3, 100, 0}, {3, 101, 1}, {4, UINT64_MAX, 2}});
    assert(batch.size() == 46);
    uint32_t groups{}, count{}; uint64_t id{};
    memcpy(&groups, batch.data() + 4, 4); assert(groups == 2);
    assert(batch[8] == 3); memcpy(&id, batch.data() + 9, 8); assert(id == 101);
    memcpy(&count, batch.data() + 17, 4); assert(count == 2);
    assert(batch[29] == 4);
    assert(sendPacket(pair[0], batch));
    std::vector<uint8_t> received(100);
    auto size = recv(pair[1], received.data(), received.size(), 0);
    received.resize(size); assert(received == batch);
    close(pair[0]); close(pair[1]); close(pipefd[0]); close(pipefd[1]);
}
