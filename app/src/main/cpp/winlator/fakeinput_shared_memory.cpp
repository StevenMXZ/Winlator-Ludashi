#include "fakeinput_ring.hpp"

#include <android/log.h>
#include <android/sharedmem.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <climits>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <arpa/inet.h>
#include <fcntl.h>
#include <linux/input.h>
#include <pthread.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "FakeInputShm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxSlots = 4;

constexpr uint16_t kButtonCodes[10] = {
        BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL,
        BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
};

constexpr uint16_t kAxisCodes[8] = {
        ABS_X, ABS_Y, ABS_RX, ABS_RY,
        ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y
};

struct CompactState {
    uint32_t buttons = 0;
    int16_t axes[8] = {};
};

struct Subscriber {
    int client_fd;
    int notify_fd;
};

struct ProducerSlot {
    int fd = -1;
    FakeInputRingHeader* ring = nullptr;
    uint64_t generation = 1;
    bool active = false;
    bool ever_activated = false;
    bool logged_first_publish = false;
    CompactState current{};
    std::vector<Subscriber> subscribers;
    std::mutex mutex;
};

std::array<ProducerSlot, kMaxSlots> g_slots;
std::mutex g_prepare_mutex;

std::atomic<bool> g_broker_running{false};
int g_broker_fd = -1;
int g_broker_stop_fd = -1;
pthread_t g_broker_thread{};
bool g_broker_thread_valid = false;
std::string g_broker_path;

static_assert(sizeof(struct input_event) == FAKE_INPUT_EVENT_SIZE,
              "input_event ABI does not match fake-input ring");

void notify_readers_locked(const ProducerSlot& producer) {
    const uint64_t one = 1;
    for (const Subscriber& subscriber : producer.subscribers) {
        // Every open has its own eventfd; readers cannot consume each other's wakeups.
        // A full counter is already readable and does not need another notification.
        (void)syscall(SYS_write, subscriber.notify_fd, &one, sizeof(one));
    }
}

uint64_t begin_publication(FakeInputRingHeader* ring) {
    uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED);
    if (sequence & 1ULL) ++sequence;
    __atomic_store_n(&ring->snapshot_seq, sequence + 1, __ATOMIC_RELEASE);
    return sequence;
}

void end_publication(FakeInputRingHeader* ring, uint64_t sequence) {
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(&ring->snapshot_seq, sequence + 2, __ATOMIC_RELEASE);
}

void write_snapshot(FakeInputRingHeader* ring, const CompactState& state) {
    __atomic_store_n(&ring->snapshot_buttons, state.buttons, __ATOMIC_RELAXED);
    for (int i = 0; i < 8; ++i) {
        __atomic_store_n(&ring->snapshot_axes[i], state.axes[i], __ATOMIC_RELAXED);
    }
}

bool ensure_slot_locked(int slot) {
    if (slot < 0 || slot >= kMaxSlots) return false;

    ProducerSlot& producer = g_slots[slot];
    if (producer.fd >= 0 && producer.ring) return true;

    char name[48];
    snprintf(name, sizeof(name), "winlator_fakeinput_%d", slot);
    const int fd = ASharedMemory_create(name, FAKE_INPUT_RING_SIZE);
    if (fd < 0) {
        LOGE("ASharedMemory_create failed for slot %d", slot);
        return false;
    }

    void* mapping = mmap(nullptr, FAKE_INPUT_RING_SIZE,
                         PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        LOGE("mmap failed for slot %d", slot);
        close(fd);
        return false;
    }

    producer.fd = fd;
    producer.ring = static_cast<FakeInputRingHeader*>(mapping);
    producer.generation = 1;
    producer.active = false;
    producer.ever_activated = false;
    producer.logged_first_publish = false;
    producer.current = {};

    std::memset(mapping, 0, FAKE_INPUT_RING_SIZE);
    producer.ring->magic = FAKE_INPUT_RING_MAGIC;
    producer.ring->version = FAKE_INPUT_RING_VERSION;
    producer.ring->event_size = FAKE_INPUT_EVENT_SIZE;
    producer.ring->capacity = FAKE_INPUT_RING_CAPACITY;
    producer.ring->generation = producer.generation;
    write_snapshot(producer.ring, producer.current);

    LOGI("Created ASharedMemory ring for slot %d (%zu bytes)",
         slot, FAKE_INPUT_RING_SIZE);
    return true;
}

bool ensure_slot(int slot) {
    if (slot < 0 || slot >= kMaxSlots) return false;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    return ensure_slot_locked(slot);
}

int16_t clamp_axis(float value) {
    value = std::max(-1.0f, std::min(1.0f, value));
    int scaled = static_cast<int>(value * 32767.0f);
    scaled = std::max<int>(INT16_MIN, std::min<int>(INT16_MAX, scaled));
    return static_cast<int16_t>(scaled);
}

int16_t clamp_trigger(float value) {
    value = std::max(0.0f, std::min(1.0f, value));
    return static_cast<int16_t>(value * 255.0f);
}

CompactState make_state(jint buttons,
                        jfloat lx, jfloat ly, jfloat rx, jfloat ry,
                        jfloat trigger_l, jfloat trigger_r,
                        jint dpad_mask) {
    CompactState state{};
    state.buttons = static_cast<uint32_t>(buttons) & 0x3ffu;
    state.axes[0] = clamp_axis(lx);
    state.axes[1] = clamp_axis(ly);
    state.axes[2] = clamp_axis(rx);
    state.axes[3] = clamp_axis(ry);
    state.axes[4] = clamp_trigger(trigger_r); // ABS_GAS
    state.axes[5] = clamp_trigger(trigger_l); // ABS_BRAKE

    const bool up = (dpad_mask & 0x1) != 0;
    const bool right = (dpad_mask & 0x2) != 0;
    const bool down = (dpad_mask & 0x4) != 0;
    const bool left = (dpad_mask & 0x8) != 0;
    state.axes[6] = left ? -1 : (right ? 1 : 0);
    state.axes[7] = up ? -1 : (down ? 1 : 0);
    return state;
}

void append_event(std::array<input_event, 32>& events, size_t& count,
                  const timeval& now, uint16_t type, uint16_t code, int32_t value) {
    if (count >= events.size()) return;
    input_event& event = events[count++];
    std::memset(&event, 0, sizeof(event));
    event.time = now;
    event.type = type;
    event.code = code;
    event.value = value;
}

bool publish_state_locked(ProducerSlot& producer,
                          const CompactState& next,
                          bool force_resend) {
    FakeInputRingHeader* ring = producer.ring;
    if (!ring) return false;

    std::array<input_event, 32> events{};
    size_t event_count = 0;
    timeval now{};
    gettimeofday(&now, nullptr);

    for (int i = 0; i < 10; ++i) {
        const bool previous = (producer.current.buttons & (1u << i)) != 0;
        const bool current = (next.buttons & (1u << i)) != 0;
        if (force_resend || previous != current) {
            append_event(events, event_count, now, EV_MSC, MSC_SCAN, kButtonCodes[i]);
            append_event(events, event_count, now, EV_KEY, kButtonCodes[i], current ? 1 : 0);
        }
    }

    for (int i = 0; i < 8; ++i) {
        if (force_resend || producer.current.axes[i] != next.axes[i]) {
            append_event(events, event_count, now, EV_ABS, kAxisCodes[i], next.axes[i]);
        }
    }

    if (event_count == 0 && !force_resend) {
        return false;
    }

    append_event(events, event_count, now, EV_SYN, SYN_REPORT, 0);

    const uint64_t sequence = begin_publication(ring);
    uint64_t write_seq = __atomic_load_n(&ring->write_seq, __ATOMIC_RELAXED);
    auto* ring_events = reinterpret_cast<uint8_t*>(ring) + FAKE_INPUT_RING_HEADER_SIZE;

    for (size_t i = 0; i < event_count; ++i) {
        const uint64_t index = write_seq % FAKE_INPUT_RING_CAPACITY;
        std::memcpy(ring_events + index * FAKE_INPUT_EVENT_SIZE,
                    &events[i], FAKE_INPUT_EVENT_SIZE);
        ++write_seq;
    }

    producer.current = next;
    write_snapshot(ring, producer.current);
    __atomic_store_n(&ring->write_seq, write_seq, __ATOMIC_RELEASE);

    if (force_resend) {
        const uint32_t resync = __atomic_load_n(&ring->resync_seq, __ATOMIC_RELAXED);
        __atomic_store_n(&ring->resync_seq, resync + 1, __ATOMIC_RELAXED);
    }

    end_publication(ring, sequence);
    notify_readers_locked(producer);
    if (!producer.logged_first_publish) {
        producer.logged_first_publish = true;
        LOGI("First ring publish generation=%llu events=%zu write_seq=%llu buttons=0x%x force=%d",
             static_cast<unsigned long long>(producer.generation),
             event_count,
             static_cast<unsigned long long>(write_seq),
             producer.current.buttons,
             force_resend ? 1 : 0);
    }
    return true;
}

void mark_generation_locked(ProducerSlot& producer, const CompactState& state) {
    FakeInputRingHeader* ring = producer.ring;
    if (!ring) return;

    const uint64_t sequence = begin_publication(ring);
    producer.current = state;
    write_snapshot(ring, producer.current);
    __atomic_store_n(&ring->write_seq, 0, __ATOMIC_RELEASE);
    ++producer.generation;
    __atomic_store_n(&ring->generation, producer.generation, __ATOMIC_RELEASE);
    const uint32_t resync = __atomic_load_n(&ring->resync_seq, __ATOMIC_RELAXED);
    __atomic_store_n(&ring->resync_seq, resync + 1, __ATOMIC_RELAXED);
    end_publication(ring, sequence);
    notify_readers_locked(producer);
}

bool send_slot_fd(int client, int slot) {
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!ensure_slot_locked(slot)) return false;

    const int notification = eventfd(1, EFD_CLOEXEC);
    if (notification < 0) return false;
    const int memory = dup(producer.fd);
    if (memory < 0) {
        close(notification);
        return false;
    }

    uint8_t status = 1;
    iovec iov{};
    iov.iov_base = &status;
    iov.iov_len = sizeof(status);

    int descriptors[2] = {memory, notification};
    char control[CMSG_SPACE(sizeof(descriptors))] = {};
    msghdr message{};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    cmsghdr* cmsg = CMSG_FIRSTHDR(&message);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(descriptors));
    std::memcpy(CMSG_DATA(cmsg), descriptors, sizeof(descriptors));

    producer.subscribers.push_back({client, notification});
    const ssize_t sent = sendmsg(client, &message, MSG_NOSIGNAL);
    close(memory);
    if (sent != static_cast<ssize_t>(sizeof(status))) {
        producer.subscribers.pop_back();
        close(notification);
        return false;
    }
    // The broker keeps both handles until the reader closes its control socket.
    return true;
}

void drop_subscriber(int client) {
    for (ProducerSlot& producer : g_slots) {
        std::lock_guard<std::mutex> guard(producer.mutex);
        auto& subscribers = producer.subscribers;
        auto it = std::find_if(subscribers.begin(), subscribers.end(),
                               [client](const Subscriber& s) { return s.client_fd == client; });
        if (it == subscribers.end()) continue;
        close(it->notify_fd);
        close(it->client_fd);
        subscribers.erase(it);
        return;
    }
}

void* broker_main(void*) {
    while (g_broker_running.load(std::memory_order_acquire)) {
        std::vector<pollfd> fds = {
                {g_broker_fd, POLLIN, 0}, {g_broker_stop_fd, POLLIN, 0}};
        for (ProducerSlot& producer : g_slots) {
            std::lock_guard<std::mutex> guard(producer.mutex);
            for (const Subscriber& subscriber : producer.subscribers)
                fds.push_back({subscriber.client_fd,
                               static_cast<short>(POLLIN | POLLHUP | POLLERR), 0});
        }

        if (poll(fds.data(), fds.size(), -1) <= 0) continue;
        if (fds[1].revents || !g_broker_running.load(std::memory_order_acquire)) break;

        for (size_t i = 2; i < fds.size(); ++i) {
            if (!fds[i].revents) continue;
            // A client never sends data after the slot request. Its connection is
            // retained solely to reclaim its eventfd on process exit or close().
            drop_subscriber(fds[i].fd);
        }
        if (!(fds[0].revents & POLLIN)) continue;

        int client = accept(g_broker_fd, nullptr, nullptr);
        if (client < 0) continue;
        timeval timeout{1, 0};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        uint8_t slot = 0xff;
        const ssize_t received = recv(client, &slot, sizeof(slot), MSG_WAITALL);
        if (received != 1 || slot >= kMaxSlots || !send_slot_fd(client, slot)) {
            uint8_t status = 0;
            send(client, &status, sizeof(status), MSG_NOSIGNAL);
            close(client);
        }
    }
    for (ProducerSlot& producer : g_slots) {
        std::lock_guard<std::mutex> guard(producer.mutex);
        for (const Subscriber& subscriber : producer.subscribers) {
            close(subscriber.client_fd);
            close(subscriber.notify_fd);
        }
        producer.subscribers.clear();
    }
    return nullptr;
}

bool start_broker_locked(const char* fake_input_path) {
    if (g_broker_running.load(std::memory_order_acquire)) return true;
    if (!fake_input_path || !fake_input_path[0]) return false;

    g_broker_path = std::string(fake_input_path) + "/" + FAKE_INPUT_SHM_SOCKET_NAME;
    if (g_broker_path.size() >= sizeof(sockaddr_un{}.sun_path)) {
        LOGE("Fake-input broker path too long: %s", g_broker_path.c_str());
        g_broker_path.clear();
        return false;
    }

    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) {
        LOGE("Unable to create shared-memory broker socket");
        g_broker_path.clear();
        return false;
    }

    unlink(g_broker_path.c_str());

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::snprintf(address.sun_path, sizeof(address.sun_path), "%s", g_broker_path.c_str());
    const socklen_t address_len = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + std::strlen(address.sun_path) + 1);

    if (bind(server, reinterpret_cast<sockaddr*>(&address), address_len) != 0) {
        LOGE("Unable to bind shared-memory broker socket %s: %s",
             g_broker_path.c_str(), strerror(errno));
        close(server);
        unlink(g_broker_path.c_str());
        g_broker_path.clear();
        return false;
    }

    if (listen(server, 8) != 0) {
        LOGE("Unable to listen on shared-memory broker socket");
        close(server);
        return false;
    }

    g_broker_stop_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (g_broker_stop_fd < 0) {
        close(server);
        unlink(g_broker_path.c_str());
        return false;
    }
    g_broker_fd = server;
    g_broker_running.store(true, std::memory_order_release);
    if (pthread_create(&g_broker_thread, nullptr, broker_main, nullptr) != 0) {
        g_broker_running.store(false, std::memory_order_release);
        close(server);
        close(g_broker_stop_fd);
        g_broker_stop_fd = -1;
        g_broker_fd = -1;
        LOGE("Unable to create shared-memory broker thread");
        return false;
    }

    g_broker_thread_valid = true;
    LOGI("Fake-input ASharedMemory broker started at %s", g_broker_path.c_str());
    return true;
}

void stop_broker_locked() {
    if (!g_broker_running.exchange(false, std::memory_order_acq_rel)) return;

    const uint64_t one = 1;
    if (g_broker_stop_fd >= 0)
        (void)syscall(SYS_write, g_broker_stop_fd, &one, sizeof(one));

    if (g_broker_thread_valid) {
        pthread_join(g_broker_thread, nullptr);
        g_broker_thread_valid = false;
    }
    if (g_broker_fd >= 0) close(g_broker_fd);
    if (g_broker_stop_fd >= 0) close(g_broker_stop_fd);
    g_broker_fd = -1;
    g_broker_stop_fd = -1;
    if (!g_broker_path.empty()) {
        unlink(g_broker_path.c_str());
        g_broker_path.clear();
    }
    LOGI("Fake-input ASharedMemory broker stopped");
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePrepareSharedMemory(
        JNIEnv* env, jclass, jstring fake_input_path, jint slot_count) {
    std::lock_guard<std::mutex> guard(g_prepare_mutex);
    const int count = std::max(0, std::min(kMaxSlots, static_cast<int>(slot_count)));

    for (int slot = 0; slot < count; ++slot) {
        if (!ensure_slot(slot)) return JNI_FALSE;
    }

    if (!fake_input_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(fake_input_path, nullptr);
    if (!path) return JNI_FALSE;
    const bool started = start_broker_locked(path);
    env->ReleaseStringUTFChars(fake_input_path, path);
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeActivate(
        JNIEnv*, jclass, jint slot) {
    if (slot < 0 || slot >= kMaxSlots) return JNI_FALSE;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!ensure_slot_locked(slot)) return JNI_FALSE;

    if (!producer.active) {
        producer.active = true;
        producer.ever_activated = true;
        publish_state_locked(producer, CompactState{}, true);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeDeactivate(
        JNIEnv*, jclass, jint slot) {
    if (slot < 0 || slot >= kMaxSlots) return;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!producer.ring || !producer.active) return;

    mark_generation_locked(producer, CompactState{});
    producer.active = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeReset(
        JNIEnv*, jclass, jint slot) {
    if (slot < 0 || slot >= kMaxSlots) return;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!producer.ring || !producer.active) return;
    publish_state_locked(producer, CompactState{}, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeRequestFullResend(
        JNIEnv*, jclass, jint slot) {
    if (slot < 0 || slot >= kMaxSlots) return;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!producer.ring || !producer.active) return;
    publish_state_locked(producer, producer.current, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePublishGamepadState(
        JNIEnv*, jclass, jint slot, jint buttons,
        jfloat lx, jfloat ly, jfloat rx, jfloat ry,
        jfloat trigger_l, jfloat trigger_r, jint dpad_mask) {
    if (slot < 0 || slot >= kMaxSlots) return;
    ProducerSlot& producer = g_slots[slot];
    std::lock_guard<std::mutex> guard(producer.mutex);
    if (!producer.ring || !producer.active) return;

    const CompactState next =
            make_state(buttons, lx, ly, rx, ry, trigger_l, trigger_r, dpad_mask);
    publish_state_locked(producer, next, false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeShutdownSharedMemory(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_prepare_mutex);
    // Notify blocked readers of the generation change before the broker drops
    // their eventfds. Otherwise a blocking read may never observe shutdown.
    for (int slot = 0; slot < kMaxSlots; ++slot) {
        ProducerSlot& producer = g_slots[slot];
        std::lock_guard<std::mutex> slot_guard(producer.mutex);
        if (producer.ring) mark_generation_locked(producer, CompactState{});
    }
    stop_broker_locked();

    for (int slot = 0; slot < kMaxSlots; ++slot) {
        ProducerSlot& producer = g_slots[slot];
        std::lock_guard<std::mutex> slot_guard(producer.mutex);
        if (!producer.ring) continue;
        munmap(producer.ring, FAKE_INPUT_RING_SIZE);
        producer.ring = nullptr;
        if (producer.fd >= 0) close(producer.fd);
        producer.fd = -1;
        producer.active = false;
        producer.ever_activated = false;
        producer.current = {};
    }
}
