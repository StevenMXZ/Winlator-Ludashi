#include "fakeinput_ring.hpp"

#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <climits>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default"))) extern "C"
#define RING_LOG_TAG "FakeInputRing"
#define RING_LOGI(...) __android_log_print(ANDROID_LOG_INFO, RING_LOG_TAG, __VA_ARGS__)
#define RING_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RING_LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint16_t kSnapshotAxisCodes[8] = {
        ABS_X, ABS_Y, ABS_RX, ABS_RY,
        ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y
};

constexpr uint16_t kSnapshotButtons[10] = {
        BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL,
        BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
};

struct KeyframeSpec {
    uint16_t type;
    uint16_t code;
};

constexpr KeyframeSpec kKeyframeEvents[] = {
        {EV_KEY, BTN_A},      {EV_KEY, BTN_B},
        {EV_KEY, BTN_X},      {EV_KEY, BTN_Y},
        {EV_KEY, BTN_TL},     {EV_KEY, BTN_TR},
        {EV_KEY, BTN_SELECT}, {EV_KEY, BTN_START},
        {EV_KEY, BTN_THUMBL}, {EV_KEY, BTN_THUMBR},
        {EV_ABS, ABS_X},      {EV_ABS, ABS_Y},
        {EV_ABS, ABS_RX},     {EV_ABS, ABS_RY},
        {EV_ABS, ABS_GAS},    {EV_ABS, ABS_BRAKE},
        {EV_ABS, ABS_HAT0X},  {EV_ABS, ABS_HAT0Y},
        {EV_SYN, SYN_REPORT},
};

constexpr size_t kKeyframeEventCount =
        sizeof(kKeyframeEvents) / sizeof(kKeyframeEvents[0]);

struct Snapshot {
    uint64_t sequence = 0;
    uint64_t write_seq = 0;
    uint64_t generation = 0;
    uint32_t resync_seq = 0;
    uint32_t buttons = 0;
    int16_t axes[8] = {};
};

struct Reader {
    FakeInputRingHeader* ring = nullptr;
    size_t mapping_size = 0;
    int wake_fd = -1; // Retain a duplicate across guest fd duplication/close.
    int control_fd = -1; // Closing this unregisters the subscriber in the broker.
    uint64_t read_seq = 0;
    uint64_t generation = 0;
    uint32_t resync_seq = 0;
    bool needs_keyframe = true;
    bool closed = false;
    bool logged_first_read = false;

    size_t keyframe_remaining = 0;
    uint32_t keyframe_buttons = 0;
    int16_t keyframe_axes[8] = {};
    std::mutex mutex;

    ~Reader() {
        if (ring) munmap(ring, mapping_size);
        if (wake_fd >= 0) close(wake_fd);
        if (control_fd >= 0) close(control_fd);
    }
};

std::unordered_map<int, std::shared_ptr<Reader>> g_readers;
std::mutex g_readers_mutex;

void wake_waiter(const Reader& reader) {
    if (reader.wake_fd < 0) return;
    const uint64_t one = 1;
    (void)syscall(SYS_write, reader.wake_fd, &one, sizeof(one));
}

// Drain before inspecting the ring. A publication racing the drain either
// appears in the subsequent snapshot or leaves this eventfd readable.
void drain_notification(const Reader& reader) {
    pollfd pfd{reader.wake_fd, POLLIN, 0};
    if (poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLIN)) {
        uint64_t counter;
        (void)syscall(SYS_read, reader.wake_fd, &counter, sizeof(counter));
    }
}

uint64_t ring_write_seq(const FakeInputRingHeader* ring) {
    return __atomic_load_n(&ring->write_seq, __ATOMIC_ACQUIRE);
}

uint64_t ring_generation(const FakeInputRingHeader* ring) {
    return __atomic_load_n(&ring->generation, __ATOMIC_ACQUIRE);
}

uint32_t ring_resync_seq(const FakeInputRingHeader* ring) {
    return __atomic_load_n(&ring->resync_seq, __ATOMIC_ACQUIRE);
}

bool valid_ring(const FakeInputRingHeader* ring) {
    return ring &&
           ring->magic == FAKE_INPUT_RING_MAGIC &&
           ring->version == FAKE_INPUT_RING_VERSION &&
           ring->event_size == FAKE_INPUT_EVENT_SIZE &&
           ring->capacity == FAKE_INPUT_RING_CAPACITY;
}

bool read_snapshot(const FakeInputRingHeader* ring, Snapshot& snapshot) {
    for (int attempt = 0; attempt < 12; ++attempt) {
        const uint64_t sequence =
                __atomic_load_n(&ring->snapshot_seq, __ATOMIC_ACQUIRE);
        if (sequence & 1ULL) continue;

        snapshot.sequence = sequence;
        snapshot.write_seq = ring_write_seq(ring);
        snapshot.generation = ring_generation(ring);
        snapshot.resync_seq = ring_resync_seq(ring);
        snapshot.buttons =
                __atomic_load_n(&ring->snapshot_buttons, __ATOMIC_RELAXED);
        for (int i = 0; i < 8; ++i) {
            snapshot.axes[i] =
                    __atomic_load_n(&ring->snapshot_axes[i], __ATOMIC_RELAXED);
        }

        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (sequence ==
            __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED)) {
            return true;
        }
    }
    return false;
}

struct BrokerFds {
    int memory = -1;
    int notification = -1;
    int control = -1;
};

BrokerFds request_ring_fds(int slot) {
    BrokerFds result;
    const char* fake_dir = getenv("FAKE_EVDEV_DIR");
    if (!fake_dir || !fake_dir[0]) {
        errno = ENOENT;
        return result;
    }
    const std::string path = std::string(fake_dir) + "/" + FAKE_INPUT_SHM_SOCKET_NAME;
    if (path.size() >= sizeof(sockaddr_un{}.sun_path)) {
        errno = ENAMETOOLONG;
        return result;
    }
    const int client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (client < 0) return result;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::snprintf(address.sun_path, sizeof(address.sun_path), "%s", path.c_str());
    const socklen_t address_len = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + path.size() + 1);
    const uint8_t request = static_cast<uint8_t>(slot);
    if (connect(client, reinterpret_cast<sockaddr*>(&address), address_len) != 0 ||
        send(client, &request, sizeof(request), MSG_NOSIGNAL) != sizeof(request)) {
        close(client);
        return result;
    }
    uint8_t status = 0;
    iovec iov{&status, sizeof(status)};
    char control[CMSG_SPACE(2 * sizeof(int))] = {};
    msghdr message{};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    const ssize_t received = recvmsg(client, &message, MSG_CMSG_CLOEXEC);
    if (received != sizeof(status) || status != 1 || (message.msg_flags & MSG_CTRUNC)) {
        close(client);
        errno = EPROTO;
        return result;
    }
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg;
         cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
            cmsg->cmsg_len >= CMSG_LEN(2 * sizeof(int))) {
            int fds[2];
            std::memcpy(fds, CMSG_DATA(cmsg), sizeof(fds));
            result.memory = fds[0];
            result.notification = fds[1];
            result.control = client;
            return result;
        }
    }
    close(client);
    errno = EPROTO;
    return result;
}

std::shared_ptr<Reader> reader_for_fd(int fd) {
    std::lock_guard<std::mutex> guard(g_readers_mutex);
    auto it = g_readers.find(fd);
    return it == g_readers.end() ? nullptr : it->second;
}

void capture_keyframe(Reader& reader, const Snapshot& snapshot) {
    reader.keyframe_buttons = snapshot.buttons;
    std::memcpy(reader.keyframe_axes, snapshot.axes, sizeof(reader.keyframe_axes));
    reader.keyframe_remaining = kKeyframeEventCount;
    reader.read_seq = snapshot.write_seq;
    reader.generation = snapshot.generation;
    reader.resync_seq = snapshot.resync_seq;
    reader.needs_keyframe = false;
}

int32_t keyframe_value(const Reader& reader, uint16_t type, uint16_t code) {
    if (type == EV_KEY) {
        for (int i = 0; i < 10; ++i) {
            if (kSnapshotButtons[i] == code) {
                return (reader.keyframe_buttons & (1u << i)) ? 1 : 0;
            }
        }
        return 0;
    }

    if (type == EV_ABS) {
        for (int i = 0; i < 8; ++i) {
            if (kSnapshotAxisCodes[i] == code) return reader.keyframe_axes[i];
        }
    }
    return 0;
}

void rearm_if_pending(Reader& reader) {
    Snapshot snapshot;
    if (reader.keyframe_remaining || reader.needs_keyframe ||
        (read_snapshot(reader.ring, snapshot) &&
         (snapshot.generation != reader.generation ||
          snapshot.resync_seq != reader.resync_seq ||
          snapshot.write_seq != reader.read_seq))) {
        wake_waiter(reader);
    }
}

} // namespace

extern "C" int fakeinput_ring_open(int slot, int flags) {
    if (slot < 0 || slot >= 4) {
        errno = ENODEV;
        return -1;
    }
    BrokerFds fds = request_ring_fds(slot);
    if (fds.memory < 0 || fds.notification < 0) return -1;

    // Android ashmem may report st_size == 0. Validate the mapped ABI instead.
    void* mapping = mmap(nullptr, FAKE_INPUT_RING_SIZE, PROT_READ,
                         MAP_SHARED, fds.memory, 0);
    const int map_errno = errno;
    close(fds.memory);
    if (mapping == MAP_FAILED) {
        close(fds.notification);
        close(fds.control);
        errno = map_errno;
        return -1;
    }
    auto reader = std::make_shared<Reader>();
    reader->ring = static_cast<FakeInputRingHeader*>(mapping);
    reader->mapping_size = FAKE_INPUT_RING_SIZE;
    reader->control_fd = fds.control;
    if (!valid_ring(reader->ring)) {
        close(fds.notification);
        errno = EPROTO;
        return -1;
    }
    Snapshot snapshot;
    if (!read_snapshot(reader->ring, snapshot)) {
        close(fds.notification);
        errno = EIO;
        return -1;
    }
    reader->generation = snapshot.generation;
    reader->resync_seq = snapshot.resync_seq;
    reader->read_seq = snapshot.write_seq;
    reader->needs_keyframe = true;
    reader->wake_fd = dup(fds.notification);
    if (reader->wake_fd < 0) {
        close(fds.notification);
        return -1;
    }
    // The eventfd itself is the evdev handle: the kernel can poll/epoll it
    // together with Wine's other FDs, without interposing poll or waking at 4 ms.
    if (flags & O_NONBLOCK) {
        int value = fcntl(fds.notification, F_GETFL);
        if (value >= 0) fcntl(fds.notification, F_SETFL, value | O_NONBLOCK);
    }
    if (!(flags & O_CLOEXEC)) {
        int value = fcntl(fds.notification, F_GETFD);
        if (value >= 0) fcntl(fds.notification, F_SETFD, value & ~FD_CLOEXEC);
    }
    {
        std::lock_guard<std::mutex> guard(g_readers_mutex);
        g_readers[fds.notification] = reader;
    }
    RING_LOGI("slot %d: evdev eventfd=%d generation=%llu",
              slot, fds.notification, static_cast<unsigned long long>(reader->generation));
    return fds.notification;
}

extern "C" void fakeinput_ring_unregister_fd(int fd) {
    std::shared_ptr<Reader> reader;
    bool last_reference = true;
    {
        std::lock_guard<std::mutex> guard(g_readers_mutex);
        auto it = g_readers.find(fd);
        if (it == g_readers.end()) return;
        reader = it->second;
        g_readers.erase(it);

        for (const auto& entry : g_readers) {
            if (entry.second.get() == reader.get()) {
                last_reference = false;
                break;
            }
        }
    }

    if (last_reference) {
        {
            std::lock_guard<std::mutex> guard(reader->mutex);
            reader->closed = true;
        }
        wake_waiter(*reader);
    }
}

extern "C" bool fakeinput_ring_clone_fd(int old_fd, int new_fd) {
    if (old_fd < 0 || new_fd < 0) return false;

    std::lock_guard<std::mutex> guard(g_readers_mutex);
    auto old_it = g_readers.find(old_fd);
    if (old_it == g_readers.end()) return false;

    g_readers[new_fd] = old_it->second;
    return true;
}

extern "C" bool fakeinput_ring_has_fd(int fd) {
    std::lock_guard<std::mutex> guard(g_readers_mutex);
    return g_readers.find(fd) != g_readers.end();
}

extern "C" ssize_t fakeinput_ring_read(int fd, void* buf, size_t count) {
    auto reader = reader_for_fd(fd);
    if (!reader) {
        RING_LOGE("read guest_fd=%d has no registered reader", fd);
        errno = EBADF;
        return -1;
    }
    {
        std::lock_guard<std::mutex> guard(reader->mutex);
        if (!reader->logged_first_read) {
            reader->logged_first_read = true;
            RING_LOGI("first read guest_fd=%d count=%zu generation=%llu read_seq=%llu write_seq=%llu",
                      fd, count,
                      static_cast<unsigned long long>(reader->generation),
                      static_cast<unsigned long long>(reader->read_seq),
                      static_cast<unsigned long long>(ring_write_seq(reader->ring)));
        }
    }
    if (count < FAKE_INPUT_EVENT_SIZE) {
        errno = EINVAL;
        return -1;
    }

    const int flags = fcntl(fd, F_GETFL);
    const bool nonblock = flags >= 0 && (flags & O_NONBLOCK);

    for (;;) {
        {
            std::lock_guard<std::mutex> guard(reader->mutex);
            drain_notification(*reader);
            if (reader->closed) {
                errno = EBADF;
                return -1;
            }
            if (ring_generation(reader->ring) != reader->generation) {
                errno = ENODEV;
                return -1;
            }

            const size_t requested = count / FAKE_INPUT_EVENT_SIZE;
            for (int attempt = 0; attempt < 12; ++attempt) {
                Snapshot snapshot;
                if (!read_snapshot(reader->ring, snapshot)) break;
                if (snapshot.generation != reader->generation) {
                    errno = ENODEV;
                    return -1;
                }

                if (reader->keyframe_remaining == 0 &&
                    (reader->needs_keyframe ||
                     snapshot.resync_seq != reader->resync_seq ||
                     snapshot.write_seq < reader->read_seq ||
                     snapshot.write_seq - reader->read_seq >
                             FAKE_INPUT_RING_CAPACITY)) {
                    capture_keyframe(*reader, snapshot);
                }

                if (reader->keyframe_remaining > 0) {
                    const size_t events =
                            std::min(requested, reader->keyframe_remaining);
                    timeval now{};
                    gettimeofday(&now, nullptr);

                    for (size_t i = 0; i < events; ++i) {
                        const size_t index =
                                kKeyframeEventCount - reader->keyframe_remaining;
                        input_event event{};
                        event.time = now;
                        event.type = kKeyframeEvents[index].type;
                        event.code = kKeyframeEvents[index].code;
                        event.value =
                                keyframe_value(*reader, event.type, event.code);
                        std::memcpy(static_cast<uint8_t*>(buf) +
                                            i * FAKE_INPUT_EVENT_SIZE,
                                    &event, FAKE_INPUT_EVENT_SIZE);
                        --reader->keyframe_remaining;
                    }
                    rearm_if_pending(*reader);
                    return static_cast<ssize_t>(
                            events * FAKE_INPUT_EVENT_SIZE);
                }

                const size_t events = static_cast<size_t>(
                        std::min<uint64_t>(
                                requested,
                                snapshot.write_seq - reader->read_seq));
                if (events == 0) break;

                const auto* ring_events =
                        reinterpret_cast<const uint8_t*>(reader->ring) +
                        FAKE_INPUT_RING_HEADER_SIZE;
                for (size_t i = 0; i < events; ++i) {
                    const uint64_t index =
                            (reader->read_seq + i) %
                            FAKE_INPUT_RING_CAPACITY;
                    std::memcpy(static_cast<uint8_t*>(buf) +
                                        i * FAKE_INPUT_EVENT_SIZE,
                                ring_events +
                                        index * FAKE_INPUT_EVENT_SIZE,
                                FAKE_INPUT_EVENT_SIZE);
                }

                __atomic_thread_fence(__ATOMIC_ACQUIRE);
                if (snapshot.sequence !=
                    __atomic_load_n(
                            &reader->ring->snapshot_seq,
                            __ATOMIC_RELAXED)) {
                    continue;
                }

                reader->read_seq += events;
                rearm_if_pending(*reader);
                return static_cast<ssize_t>(
                        events * FAKE_INPUT_EVENT_SIZE);
            }

            if (nonblock) {
                errno = EAGAIN;
                return -1;
            }

        }
        // The guest's eventfd is a real pollable FD. A producer update between
        // our empty-ring check and poll remains readable, avoiding missed wakes.
        pollfd pfd{reader->wake_fd, POLLIN, 0};
        const int result = poll(&pfd, 1, -1);
        if (result < 0 && errno == EINTR) return -1;
        if (result < 0) return -1;
    }
}
