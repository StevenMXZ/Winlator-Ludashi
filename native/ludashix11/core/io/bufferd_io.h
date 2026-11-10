#pragma once

#include <list>
#include <vector>

namespace xorg {

class BufferedInputStream {
public:
    explicit BufferedInputStream(int sock_fd, size_t buffer_size = 10240);

    ~BufferedInputStream();
    ssize_t Read(void* data, size_t size);

    int ConsumeFd();

private:
    size_t GetCachedSize() const { return end - start; }

    size_t GetFreeSize() const { return buffer.size() - end; }

    void Rewind() {
        start = 0;
        end = 0;
    }

    void Consume(void* data, size_t size) {
        memcpy(data, buffer.data() + start, size);
        start += size;
    }

    ssize_t ReadRaw(void* data, size_t size);

    int sock_fd;
    std::vector<uint8_t> buffer;
    size_t start{};
    size_t end{};
    std::list<int> fds;
};

}  // namespace xorg