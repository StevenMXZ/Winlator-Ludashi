#pragma once

#include <cstddef>
#include <cstdint>
#include <poll.h>
#include <sys/select.h>
#include <sys/types.h>

static constexpr uint32_t FAKE_INPUT_RING_MAGIC = 0x46494252; // "FIBR"
static constexpr uint32_t FAKE_INPUT_RING_VERSION = 4;
static constexpr uint32_t FAKE_INPUT_RING_CAPACITY = 4096;
static constexpr uint32_t FAKE_INPUT_EVENT_SIZE = 24;
static constexpr size_t FAKE_INPUT_RING_HEADER_SIZE = 64;
static constexpr size_t FAKE_INPUT_RING_SIZE =
        FAKE_INPUT_RING_HEADER_SIZE +
        (FAKE_INPUT_RING_CAPACITY * FAKE_INPUT_EVENT_SIZE);
static constexpr const char* FAKE_INPUT_SHM_SOCKET_NAME = ".fakeinput-shm.sock";

struct FakeInputRingHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t event_size;
    uint32_t capacity;
    uint64_t write_seq;
    uint64_t generation;
    uint64_t snapshot_seq;
    uint32_t snapshot_buttons;
    int16_t snapshot_axes[8];
    uint32_t resync_seq;
};

static_assert(sizeof(FakeInputRingHeader) == FAKE_INPUT_RING_HEADER_SIZE,
              "fake input ring header ABI must remain 64 bytes");

extern "C" int fakeinput_ring_open(int slot, int flags);
extern "C" void fakeinput_ring_unregister_fd(int fd);
extern "C" bool fakeinput_ring_clone_fd(int old_fd, int new_fd);
extern "C" bool fakeinput_ring_has_fd(int fd);
extern "C" ssize_t fakeinput_ring_read(int fd, void* buf, size_t count);
