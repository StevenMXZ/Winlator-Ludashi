#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <span>
#include <vector>

namespace ludashi::x11 {

class RequestStream {
public:
    explicit RequestStream(int socket);
    ~RequestStream();

    RequestStream(const RequestStream&) = delete;
    RequestStream& operator=(const RequestStream&) = delete;

    // A complete frame is valid through dispatch, until Consume().
    std::span<uint8_t> ReadSetup();
    std::span<uint8_t> ReadCommand(bool bigRequests);
    void Consume();
    bool HasFollowingBytes() const { return tail > frameEnd; }
    int TakeDescriptor();

private:
    bool EnsureAvailable(size_t required);

    const int socket;
    std::vector<uint8_t> incoming;
    std::deque<int> descriptors;
    size_t head{};
    size_t tail{};
    size_t frameEnd{};
};

} // namespace ludashi::x11
