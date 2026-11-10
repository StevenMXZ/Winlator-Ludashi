#include <cassert>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

using Clock = std::chrono::steady_clock;
int main(int argc, char** argv) {
    assert(argc == 2);
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { puts(dlerror()); return 1; }
    auto opened = reinterpret_cast<int(*)(const char*, int, ...)>(dlsym(lib, "open"));
    auto closed = reinterpret_cast<int(*)(int)>(dlsym(lib, "close"));
    auto readInput = reinterpret_cast<ssize_t(*)(int,void*,size_t)>(dlsym(lib, "read"));
    auto pollInput = reinterpret_cast<int(*)(pollfd*,nfds_t,int)>(dlsym(lib, "poll"));
    auto ppollInput = reinterpret_cast<int(*)(pollfd*,nfds_t,const timespec*,const sigset_t*)>(dlsym(lib, "ppoll"));
    auto writeInput = reinterpret_cast<ssize_t(*)(int,const void*,size_t)>(dlsym(lib, "write"));
    assert(opened && closed && readInput && pollInput && ppollInput && writeInput);
    std::string path = std::string(getenv("FAKE_EVDEV_DIR")) + "/event0";
    int writer = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600); assert(writer >= 0);
    int fd = opened("/dev/input/event0", O_RDONLY | O_NONBLOCK); assert(fd >= 0);
    input_event event{}, received{}; event.type = EV_KEY; event.code = BTN_A; event.value = 1;
    assert(readInput(fd, &received, sizeof(received)) == -1 && errno == EAGAIN);
    assert(readInput(fd, &received, 0) == 0);
    pollfd pending{fd, POLLIN, 0};
    auto start = Clock::now();
    timespec cpuStart{}, cpuEnd{}; clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpuStart);
    assert(pollInput(&pending, 1, 120) == 0);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpuEnd);
    auto cpuNs = (cpuEnd.tv_sec - cpuStart.tv_sec) * 1000000000LL + cpuEnd.tv_nsec - cpuStart.tv_nsec;
    assert(Clock::now() - start >= std::chrono::milliseconds(100));
    assert(cpuNs < 40000000);
    printf("PASS idle wait: 120 ms wall, %.3f ms thread CPU\n", cpuNs / 1e6);
    std::thread producer([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); assert(write(writer, &event, sizeof(event)) == sizeof(event)); });
    assert(pollInput(&pending, 1, 2000) == 1 && (pending.revents & POLLIN));
    producer.join();
    assert(readInput(fd, &received, sizeof(received)) == sizeof(received));
    assert(received.type == EV_KEY && received.code == BTN_A && received.value == 1);
    assert(pollInput(&pending, 1, 0) == 0);
    std::string secondPath = std::string(getenv("FAKE_EVDEV_DIR")) + "/event1";
    int secondWriter=open(secondPath.c_str(),O_RDWR|O_CREAT|O_TRUNC,0600); assert(secondWriter>=0);
    int secondPad=opened("/dev/input/event1",O_RDONLY|O_NONBLOCK); assert(secondPad>=0);
    pollfd pads[2]{{fd,POLLIN,0},{secondPad,POLLIN,0}};
    std::thread padProducer([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); assert(write(secondWriter,&event,sizeof(event))==sizeof(event)); });
    assert(pollInput(pads,2,2000)==1 && pads[0].revents==0 && pads[1].revents==POLLIN);
    padProducer.join(); assert(readInput(secondPad,&received,sizeof(received))==sizeof(received));
    closed(secondPad); close(secondWriter); unlink(secondPath.c_str());
    fcntl(fd, F_SETFL, 0);
    std::thread second([&] { std::this_thread::sleep_for(std::chrono::milliseconds(120)); assert(write(writer, &event, sizeof(event)) == sizeof(event)); });
    clock_gettime(CLOCK_THREAD_CPUTIME_ID,&cpuStart);
    assert(readInput(fd, &received, sizeof(received)) == sizeof(received)); second.join();
    clock_gettime(CLOCK_THREAD_CPUTIME_ID,&cpuEnd);
    cpuNs=(cpuEnd.tv_sec-cpuStart.tv_sec)*1000000000LL+cpuEnd.tv_nsec-cpuStart.tv_nsec;
    assert(cpuNs<40000000);
    printf("PASS blocking read: 120 ms producer delay, %.3f ms thread CPU\n",cpuNs/1e6);
    // Register-before-check must preserve events arriving on either side of wait setup.
    for (int i = 0; i < 100; ++i) {
        std::thread race([&] { assert(write(writer, &event, sizeof(event)) == sizeof(event)); });
        assert(pollInput(&pending, 1, 2000) == 1);
        assert(readInput(fd, &received, sizeof(received)) == sizeof(received)); race.join();
    }
    int independent = opened("/dev/input/event0", O_RDONLY | O_NONBLOCK); assert(independent >= 0);
    assert(readInput(independent, &received, sizeof(received)) == sizeof(received)); closed(independent);
    int pipes[2]; assert(pipe(pipes) == 0);
    pollfd mixed[2]{{fd,POLLIN,0},{pipes[0],POLLIN,0}};
    std::thread other([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); assert(write(pipes[1], "x", 1) == 1); });
    assert(pollInput(mixed, 2, 2000) == 1 && mixed[0].revents == 0 && mixed[1].revents == POLLIN);
    other.join(); char byte; assert(read(pipes[0], &byte, 1) == 1);
    assert(pollInput(&mixed[1], 1, 0) == 0); close(pipes[0]); close(pipes[1]);
    struct sigaction action{}; action.sa_handler = +[](int) {}; sigemptyset(&action.sa_mask);
    assert(sigaction(SIGUSR1, &action, nullptr) == 0);
    pthread_t reader = pthread_self();
    std::thread signal([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); pthread_kill(reader, SIGUSR1); });
    timespec timeout{2,0}; sigset_t mask; sigemptyset(&mask);
    assert(ppollInput(&pending,1,&timeout,&mask) == -1 && errno == EINTR); signal.join();
    assert(timeout.tv_sec==2 && timeout.tv_nsec==0);
    sigset_t blocked, previousMask, restored; sigemptyset(&blocked); sigaddset(&blocked,SIGUSR1);
    assert(pthread_sigmask(SIG_BLOCK,&blocked,&previousMask)==0);
    std::thread maskedSignal([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); pthread_kill(reader,SIGUSR1); });
    assert(ppollInput(&pending,1,&timeout,&mask)==-1 && errno==EINTR); maskedSignal.join();
    assert(pthread_sigmask(SIG_SETMASK,nullptr,&restored)==0 && sigismember(&restored,SIGUSR1)==1);
    assert(pthread_sigmask(SIG_SETMASK,&previousMask,nullptr)==0);
    std::thread readSignal([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); pthread_kill(reader, SIGUSR1); });
    assert(readInput(fd,&received,sizeof(received)) == -1 && errno == EINTR); readSignal.join();
    timespec invalid{0,1000000000};
    assert(ppollInput(&pending,1,&invalid,nullptr) == -1 && errno == EINVAL);
    // Metadata notifications must not masquerade as input or reset the timeout.
    std::thread metadata([&] { for(int i=0;i<20;++i) { chmod(path.c_str(), i%2 ? 0600 : 0640); std::this_thread::sleep_for(std::chrono::milliseconds(2)); } });
    start=Clock::now(); assert(pollInput(&pending,1,100) == 0); metadata.join();
    assert(Clock::now()-start >= std::chrono::milliseconds(90));
    auto fdCount=[] { size_t count=0; for(const auto& entry:std::filesystem::directory_iterator("/proc/self/fd")) { (void)entry; ++count; } return count; };
    auto before=fdCount(); for(int i=0;i<200;++i) assert(pollInput(&pending,1,0)==0); assert(fdCount()==before);
    input_event rumble{}; rumble.type=EV_FF;
    struct stat fileBefore{}, fileAfter{}; assert(fstat(writer,&fileBefore)==0);
    assert(writeInput(fd,&rumble,sizeof(rumble))==sizeof(rumble)); assert(fstat(writer,&fileAfter)==0);
    assert(fileBefore.st_size==fileAfter.st_size);
    // Closing a waiting reader must release its wait; other handles keep working.
    int waiting=opened("/dev/input/event0",O_RDONLY); assert(waiting>=0);
    assert(lseek(waiting,0,SEEK_END)>=0);
    std::thread closing([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); assert(closed(waiting)==0); });
    start=Clock::now(); assert(readInput(waiting,&received,sizeof(received))==-1 && errno==EBADF);
    closing.join(); assert(Clock::now()-start < std::chrono::milliseconds(750));
    // Removal wakes both poll and blocking read, and a replacement is a new device.
    std::thread removal([&] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); assert(unlink(path.c_str())==0); });
    assert(pollInput(&pending,1,2000)==1 && (pending.revents&POLLHUP)); removal.join();
    assert(readInput(fd,&received,sizeof(received))==-1 && errno==ENODEV);
    int freshWriter=open(path.c_str(),O_RDWR|O_CREAT|O_TRUNC,0600); assert(freshWriter>=0);
    int fresh=opened("/dev/input/event0",O_RDONLY|O_NONBLOCK); assert(fresh>=0);
    assert(write(freshWriter,&event,sizeof(event))==sizeof(event)); assert(readInput(fresh,&received,sizeof(received))==sizeof(received));
    assert(readInput(fd,&received,sizeof(received))==-1 && errno==ENODEV);
    closed(fresh); closed(fd); close(writer); close(freshWriter);
    assert(fdCount()==before-2);
    puts("PASS actual FakeInput shim: nonblocking/EOF, append races, independent readers, mixed sockets, signals, timeout, FD cleanup, rumble, disconnect/reconnect");
}
