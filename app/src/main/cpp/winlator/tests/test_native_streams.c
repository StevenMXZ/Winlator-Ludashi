#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <signal.h>
#include <stdatomic.h>
static atomic_int shortWrites;
static ssize_t countedSend(int fd, const void* data, size_t length, int flags) {
    ssize_t result = send(fd, data, length, flags);
    if (result > 0 && (size_t)result < length) atomic_fetch_add(&shortWrites, 1);
    return result;
}
#define send countedSend
#include "../xinput_stream.c"
#include "../xoutput_stream.c"
#include "../xconnector_epoll.c"
#undef send

static atomic_int refs, arrivals, departures, blocked;
static _Thread_local bool attached;
static XConnectorEpoll* connector;
static JNIEnv environment;
static JavaVM machine;
static char objectIdentity;
static const jmethodID newMethod = (jmethodID)1, existingMethod = (jmethodID)2, shutdownMethod = (jmethodID)3;
typedef struct Tag { uint64_t id; int fd; } Tag;
static jobject JNICALL globalRef(JNIEnv* env, jobject value) { assert(attached); atomic_fetch_add(&refs, 1); return value; }
static void JNICALL deleteGlobal(JNIEnv* env, jobject value) {
    assert(attached); atomic_fetch_sub(&refs, 1); if (value != (jobject)&objectIdentity) free(value);
}
static void JNICALL deleteLocal(JNIEnv* env, jobject value) { assert(attached); }
static jint JNICALL getVm(JNIEnv* env, JavaVM** result) { *result = &machine; return JNI_OK; }
static jclass JNICALL getClass(JNIEnv* env, jobject object) { return (jclass)&objectIdentity; }
static jmethodID JNICALL getMethod(JNIEnv* env, jclass type, const char* name, const char* descriptor) {
    if (!strcmp(name, "handleNewConnection")) return newMethod;
    if (!strcmp(name, "handleExistingConnection")) return existingMethod;
    assert(!strcmp(name, "handleConnectionShutdown")); return shutdownMethod;
}
static const char* JNICALL getString(JNIEnv* env, jstring string, jboolean* copied) { return (const char*)string; }
static void JNICALL releaseString(JNIEnv* env, jstring string, const char* data) {}
static jboolean JNICALL noException(JNIEnv* env) { return JNI_FALSE; }
static jobject JNICALL newClient(JNIEnv* env, jobject object, jmethodID method, ...) {
    assert(attached && method == newMethod);
    Tag* tag = calloc(1, sizeof(*tag)); assert(tag);
    va_list arguments; va_start(arguments, method);
    tag->id = va_arg(arguments, jlong); tag->fd = va_arg(arguments, jint); va_end(arguments);
    atomic_fetch_add(&arrivals, 1); return (jobject)tag;
}
static void JNICALL request(JNIEnv* env, jobject object, jmethodID method, ...) {
    assert(attached); va_list arguments; va_start(arguments, method);
    Tag* tag = (Tag*)va_arg(arguments, jobject); va_end(arguments);
    if (method == shutdownMethod) { atomic_fetch_add(&departures, 1); return; }
    assert(method == existingMethod);
    char byte;
    ssize_t count = read(tag->fd, &byte, 1);
    if (count <= 0 || byte == 'K') {
        Java_com_winlator_cmod_xconnector_XConnectorEpoll_killConnection(env, NULL, (jlong)connector, tag->id);
    } else if (byte == 'D') {
        Java_com_winlator_cmod_xconnector_XConnectorEpoll_destroy(env, NULL, (jlong)connector);
    } else if (byte == 'B') {
        atomic_fetch_add(&blocked, 1);
        assert(read(tag->fd, &byte, 1) <= 0); // destroy must wake blocked callbacks.
    } else assert(send(tag->fd, &byte, 1, MSG_NOSIGNAL) == 1);
}
static jint JNICALL attach(JavaVM* vm, void** output, void* args) { attached = true; *output = &environment; return JNI_OK; }
static jint JNICALL detach(JavaVM* vm) { assert(attached); attached = false; return JNI_OK; }
static const struct JNIInvokeInterface_ machineTable = {.AttachCurrentThread = attach, .DetachCurrentThread = detach};
static const struct JNINativeInterface_ environmentTable = {
    .GetJavaVM = getVm, .NewGlobalRef = globalRef, .DeleteGlobalRef = deleteGlobal,
    .DeleteLocalRef = deleteLocal, .GetObjectClass = getClass, .GetMethodID = getMethod,
    .GetStringUTFChars = getString, .ReleaseStringUTFChars = releaseString,
    .ExceptionCheck = noException, .CallObjectMethod = newClient, .CallVoidMethod = request,
};
static int fdCount(void) {
    DIR* dir = opendir("/proc/self/fd"); assert(dir); int count = 0;
    while (readdir(dir)) ++count; closedir(dir); return count;
}
static void until(atomic_int* counter, int value) {
    for (int i = 0; i < 2000; ++i) {
        if (atomic_load(counter) >= value) return;
        struct timespec delay = {.tv_nsec = 1000000}; nanosleep(&delay, NULL);
    }
    assert(!"callback deadline exceeded");
}
static int connectSocket(const char* path) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); assert(fd >= 0);
    struct timeval timeout = {.tv_sec = 2}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_un address = {.sun_family = AF_UNIX}; strcpy(address.sun_path, path);
    assert(connect(fd, (struct sockaddr*)&address, sizeof(address)) == 0); return fd;
}
static void connectorCycles(void) {
    environment = &environmentTable; machine = &machineTable; attached = true;
    int initialFds = fdCount();
    char directory[] = "/tmp/winlator-epoll-XXXXXX"; assert(mkdtemp(directory));
    char path[108]; snprintf(path, sizeof(path), "%s/socket", directory);
    for (int mode = 0; mode < 2; ++mode) for (int cycle = 0; cycle < 12; ++cycle) {
        int before = atomic_load(&arrivals);
        connector = (XConnectorEpoll*)Java_com_winlator_cmod_xconnector_XConnectorEpoll_nativeAllocate(&environment, (jobject)&objectIdentity, (jstring)path);
        assert(connector);
        Java_com_winlator_cmod_xconnector_XConnectorEpoll_startEpollThread(&environment, NULL, (jlong)connector, mode);
        int peers[4]; for (int i = 0; i < 4; ++i) peers[i] = connectSocket(path);
        until(&arrivals, before + 4);
        for (int i = 0; i < 3; ++i) assert(send(peers[i], "K", 1, MSG_NOSIGNAL) == 1);
        until(&departures, before + 3);
        // A retired client ID must not kill a later connection.
        Java_com_winlator_cmod_xconnector_XConnectorEpoll_killConnection(&environment, NULL, (jlong)connector, 1);
        assert(send(peers[3], "E", 1, MSG_NOSIGNAL) == 1);
        char echo = 0; assert(recv(peers[3], &echo, 1, 0) == 1 && echo == 'E');
        if (cycle % 2) {
            assert(send(peers[3], "D", 1, MSG_NOSIGNAL) == 1);
            until(&departures, before + 4);
            for (int wait = 0; wait < 2000 && atomic_load(&refs); ++wait) {
                struct timespec delay = {.tv_nsec = 1000000}; nanosleep(&delay, NULL);
            }
        } else {
            int waiting = atomic_load(&blocked);
            assert(send(peers[3], "B", 1, MSG_NOSIGNAL) == 1); until(&blocked, waiting + 1);
            Java_com_winlator_cmod_xconnector_XConnectorEpoll_destroy(&environment, NULL, (jlong)connector);
        }
        connector = NULL;
        for (int i = 0; i < 4; ++i) close(peers[i]);
        assert(atomic_load(&refs) == 0); assert(atomic_load(&arrivals) == atomic_load(&departures));
        assert(fdCount() == initialFds);
    }
    unlink(path); rmdir(directory);
}
static void inputFragments(void) {
    int fds[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0);
    XInputStream* in = XInputStream_allocate(fds[1], 3); assert(in);
    assert(write(fds[0], "abc", 3) == 3); assert(XInputStream_readMoreData(in, false) == 3);
    assert(inputValue(in, 1) == 'a');
    assert(write(fds[0], "d", 1) == 1); assert(XInputStream_readMoreData(in, false) == 1);
    assert(in->size == 3 && !memcmp(in->data, "bcd", 3));
    assert(write(fds[0], "efg", 3) == 3); assert(XInputStream_readMoreData(in, false) == 3);
    assert(in->size == 6 && in->capacity == 6);
    assert(inputValue(in, 4) == UINT32_C(0x65646362));
    in->bigEndian = true; assert(inputValue(in, 2) == UINT16_C(0x6667));
    assert(inputValue(in, 8) == 0 && in->position == in->size);
    XInputStream_destroy(in); close(fds[0]); close(fds[1]);
}
typedef struct Reader { int fd; size_t size; } Reader;
static void* drain(void* argument) {
    Reader* reader = argument; unsigned char bytes[3071]; size_t offset = 0;
    struct timespec pause = {.tv_nsec = 50000000}; nanosleep(&pause, NULL);
    while (offset < reader->size) {
        ssize_t n = read(reader->fd, bytes, sizeof(bytes)); assert(n > 0);
        for (int i = 0; i < n; ++i) assert(bytes[i] == (unsigned char)(offset + i));
        offset += n;
    }
    return NULL;
}
static void signalHandler(int signal) {}
static void* interruptWriter(void* argument) {
    struct timespec pause = {.tv_nsec = 10000000}; nanosleep(&pause, NULL);
    assert(pthread_kill(*(pthread_t*)argument, SIGUSR1) == 0); return NULL;
}
static void outputAndFds(void) {
    int baseline = fdCount();
    int fds[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0);
    int size = 4096; setsockopt(fds[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size));
    XOutputStream* out = XOutputStream_allocate(fds[0], 1); assert(out);
    out->bigEndian = true; outputValue(out, 0x12345678, 4); assert(!memcmp(out->data, "\x12\x34\x56\x78", 4));
    out->position = 0; out->bigEndian = false;
    const int length = 8 * 1024 * 1024; assert(ensureSpaceIsAvailable(out, length));
    for (int i = 0; i < length; ++i) out->data[i] = i;
    out->position = length;
    Reader reader = {.fd = fds[1], .size = length}; pthread_t thread;
    struct sigaction action = {.sa_handler = signalHandler}, previous;
    sigemptyset(&action.sa_mask); assert(sigaction(SIGUSR1, &action, &previous) == 0);
    pthread_t writer = pthread_self(), interrupter;
    assert(pthread_create(&thread, NULL, drain, &reader) == 0);
    assert(pthread_create(&interrupter, NULL, interruptWriter, &writer) == 0);
    assert(XOutputStream_send(out)); pthread_join(interrupter, NULL); pthread_join(thread, NULL);
    assert(sigaction(SIGUSR1, &previous, NULL) == 0);
    assert(atomic_load(&shortWrites) > 0 && out->position == 0);
    // Descriptor zero is valid and must be transferred exactly once.
    int saved = dup(0), original = memfd_create("test", MFD_CLOEXEC); assert(saved >= 0 && original >= 0);
    assert(dup2(original, 0) == 0); close(original);
    out->ancillaryFd = 0; outputValue(out, 42, 1); assert(XOutputStream_send(out));
    int received[MAX_FDS], count; char byte;
    assert(recv_fds(fds[1], received, &count, &byte, 1) == 1 && count == 1 && byte == 42); close(received[0]);
    assert(out->ancillaryFd == -1); assert(dup2(saved, 0) == 0); close(saved);
    // Unconsumed incoming ancillary descriptors belong to the input stream.
    XInputStream* in = XInputStream_allocate(fds[1], 32);
    int source = memfd_create("pending", MFD_CLOEXEC); assert(source >= 0);
    assert(send_fds(fds[0], &source, 1, "x", 1) == 1);
    assert(XInputStream_readMoreData(in, true) == 1 && in->fdCount == 1);
    int owned = in->fds[0]; XInputStream_destroy(in); assert(fcntl(owned, F_GETFD) == -1 && errno == EBADF);
    close(source); close(fds[1]); outputValue(out, 1, 1); assert(!XOutputStream_send(out));
    XOutputStream_destroy(out); close(fds[0]); assert(fdCount() == baseline);
}
int main(void) {
    if (fcntl(0, F_GETFD) < 0) assert(open("/dev/null", O_RDONLY) == 0);
    inputFragments(); outputAndFds(); connectorCycles();
    puts("PASS: fragments, compaction/growth, byte orders, 8MiB partial writes interrupted by a signal, ancillary FD zero/cleanup, 24 connector lifecycles, blocked-I/O/callback shutdown and JNI global references");
}
