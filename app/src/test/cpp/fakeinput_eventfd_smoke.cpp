// Host smoke test for the Android producer/guest reader protocol. CI runs this
// against real Linux Unix sockets, eventfd, SCM_RIGHTS and epoll.
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <initializer_list>
#include <linux/input.h>
#include <poll.h>
#include <sys/epoll.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>
#include "jni.h"
#include "fakeinput_ring.hpp"

extern "C" int __android_log_print(int, const char*, const char*, ...) { return 0; }
extern "C" int ASharedMemory_create(const char* name, size_t size) {
    int fd = static_cast<int>(syscall(SYS_memfd_create, name, 0));
    if (fd < 0 || ftruncate(fd, size)) return -1;
    return fd;
}
extern "C" jboolean Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePrepareSharedMemory(JNIEnv*, jclass, jstring, jint);
extern "C" jboolean Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeActivate(JNIEnv*, jclass, jint);
extern "C" void Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePublishGamepadState(JNIEnv*, jclass, jint, jint, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jint);
extern "C" void Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeDeactivate(JNIEnv*, jclass, jint);
extern "C" void Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeShutdownSharedMemory(JNIEnv*, jclass);

static void publish(JNIEnv& env, int mask) {
    Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePublishGamepadState(
            &env, nullptr, 0, 1, 0, 0, 0, 0, 0, 0, mask);
}
static ssize_t drain(int fd) {
    input_event events[64];
    return fakeinput_ring_read(fd, events, sizeof(events));
}

int main() {
    char path[] = "/tmp/fakeinput-smoke-XXXXXX";
    assert(mkdtemp(path));
    setenv("FAKE_EVDEV_DIR", path, 1);
    JNIEnv env;
    assert(Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativePrepareSharedMemory(&env, nullptr, path, 4));
    assert(Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeActivate(&env, nullptr, 0));
    int first = fakeinput_ring_open(0, O_NONBLOCK);
    int second = fakeinput_ring_open(0, O_NONBLOCK);
    assert(first >= 0 && second >= 0);
    for (int fd : {first, second}) {
        pollfd p{fd, POLLIN, 0};
        assert(poll(&p, 1, 100) == 1); // Initial keyframe.
        assert(drain(fd) == 19 * sizeof(input_event));
        assert(poll(&p, 1, 20) == 0); // Idle never wakes periodically.
    }
    publish(env, 2);
    for (int fd : {first, second}) {
        pollfd p{fd, POLLIN, 0};
        assert(poll(&p, 1, 100) == 1);
        input_event events[64];
        ssize_t count = fakeinput_ring_read(fd, events, sizeof(events));
        bool right = false;
        for (int i = 0; i < count / sizeof(input_event); ++i)
            if (events[i].type == EV_ABS && events[i].code == ABS_HAT0X && events[i].value == 1)
                right = true;
        assert(right); // Reading the first subscriber cannot drain the second.
        assert(poll(&p, 1, 20) == 0);
    }
    publish(env, 2); // The same quantized state must not signal readers.
    pollfd idle{first, POLLIN, 0};
    assert(poll(&idle, 1, 20) == 0);

    int epfd = epoll_create1(EPOLL_CLOEXEC);
    assert(epfd >= 0);
    epoll_event subscribed{};
    subscribed.events = EPOLLIN;
    subscribed.data.fd = first;
    assert(epoll_ctl(epfd, EPOLL_CTL_ADD, first, &subscribed) == 0);
    publish(env, 8);
    epoll_event ready{};
    assert(epoll_wait(epfd, &ready, 1, 100) == 1 && ready.data.fd == first);
    assert(drain(first) > 0 && drain(second) > 0);
    close(epfd);

    int blocking = fakeinput_ring_open(0, 0);
    assert(blocking >= 0 && drain(blocking) == 19 * sizeof(input_event));
    ssize_t delivered = -1;
    std::thread consumer([&] { delivered = drain(blocking); });
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    publish(env, 2);
    consumer.join(); // timeout(1) around this binary catches a missed wake.
    assert(delivered > 0);

    Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeDeactivate(&env, nullptr, 0);
    pollfd disconnected{first, POLLIN, 0};
    assert(poll(&disconnected, 1, 100) == 1);
    input_event events[64];
    assert(fakeinput_ring_read(first, events, sizeof(events)) == -1 && errno == ENODEV);
    for (int fd : {first, second, blocking}) {
        fakeinput_ring_unregister_fd(fd);
        close(fd);
    }
    Java_com_winlator_cmod_inputcontrols_FakeInputWriter_nativeShutdownSharedMemory(&env, nullptr);
    printf("fakeinput eventfd smoke passed\n");
}
