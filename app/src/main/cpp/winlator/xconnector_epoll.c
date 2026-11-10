#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stddef.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <pthread.h>
#include <unistd.h>

#define MAX_EVENTS 16

typedef struct XConnectorEpoll XConnectorEpoll;
typedef struct ConnectedClient {
    struct ConnectedClient* next;
    XConnectorEpoll* connector;
    uint64_t id;
    int fd;
    pthread_t thread;
    bool threaded;
    atomic_bool closing, finished;
    jobject tag; // Global reference: callbacks and shutdown may use different threads.
} ConnectedClient;
struct XConnectorEpoll {
    pthread_t thread;
    bool started, multithreaded;
    atomic_bool running, selfDestroy;
    int epollFd, serverFd, wakeFd;
    JavaVM* vm;
    jobject object;
    jmethodID newConnection, existingConnection, shutdownConnection;
    pthread_mutex_t clientsLock;
    ConnectedClient* clients;
    uint64_t nextId;
};
static _Thread_local XConnectorEpoll* activeConnector;
static void freeConnector(JNIEnv* env, XConnectorEpoll* c) {
    if (c->serverFd >= 0) close(c->serverFd);
    if (c->wakeFd >= 0) close(c->wakeFd);
    if (c->epollFd >= 0) close(c->epollFd);
    if (c->object) (*env)->DeleteGlobalRef(env, c->object);
    pthread_mutex_destroy(&c->clientsLock); free(c);
}
static void wakeConnector(XConnectorEpoll* c) {
    uint64_t value = 1;
    ssize_t result;
    do { result = write(c->wakeFd, &value, sizeof(value)); } while (result < 0 && errno == EINTR);
}
static bool addFd(XConnectorEpoll* c, int fd, void* pointer) {
    struct epoll_event event = {0}; event.data.ptr = pointer; event.events = EPOLLIN | EPOLLRDHUP;
    return epoll_ctl(c->epollFd, EPOLL_CTL_ADD, fd, &event) == 0;
}
static void markClosing(ConnectedClient* client) {
    if (!atomic_exchange(&client->closing, true)) shutdown(client->fd, SHUT_RDWR);
}
static bool clearCallbackException(JNIEnv* env) {
    if (!(*env)->ExceptionCheck(env)) return false;
    (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); return true;
}
static void beginClient(JNIEnv* env, ConnectedClient* client) {
    XConnectorEpoll* c = client->connector;
    jobject tag = (*env)->CallObjectMethod(env, c->object, c->newConnection, (jlong)client->id, client->fd);
    if (clearCallbackException(env) || !tag) { markClosing(client); return; }
    client->tag = (*env)->NewGlobalRef(env, tag);
    if (!client->tag) {
        clearCallbackException(env);
        (*env)->CallVoidMethod(env, c->object, c->shutdownConnection, tag);
        clearCallbackException(env); markClosing(client);
    }
    (*env)->DeleteLocalRef(env, tag);
}
static void endClient(JNIEnv* env, ConnectedClient* client) {
    if (!client->tag) return;
    XConnectorEpoll* c = client->connector;
    (*env)->CallVoidMethod(env, c->object, c->shutdownConnection, client->tag);
    clearCallbackException(env);
    (*env)->DeleteGlobalRef(env, client->tag); client->tag = NULL;
}
static void handleClient(JNIEnv* env, ConnectedClient* client) {
    if (!client->tag || atomic_load(&client->closing)) return;
    XConnectorEpoll* c = client->connector;
    (*env)->CallVoidMethod(env, c->object, c->existingConnection, client->tag);
    if (clearCallbackException(env)) markClosing(client);
}
static jint attachEnv(JavaVM* vm, JNIEnv** env) {
#ifdef __ANDROID__
    return (*vm)->AttachCurrentThread(vm, env, NULL);
#else
    return (*vm)->AttachCurrentThread(vm, (void**)env, NULL);
#endif
}
static void* clientThread(void* argument) {
    ConnectedClient* client = argument; XConnectorEpoll* c = client->connector;
    activeConnector = c;
    JNIEnv* env = NULL;
    if (attachEnv(c->vm, &env) == JNI_OK) {
        beginClient(env, client);
        while (!atomic_load(&client->closing) && atomic_load(&c->running)) {
            struct pollfd pfd = {.fd = client->fd, .events = POLLIN};
            int result;
            do { result = poll(&pfd, 1, -1); } while (result < 0 && errno == EINTR);
            if (result <= 0) break;
            if (pfd.revents & POLLIN) handleClient(env, client);
            else if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        }
        markClosing(client); endClient(env, client);
        (*c->vm)->DetachCurrentThread(c->vm);
    } else markClosing(client);
    atomic_store(&client->finished, true); wakeConnector(c); return NULL;
}
// Only the epoll thread retires clients, after processing the whole event batch.
static void reapClients(JNIEnv* env, XConnectorEpoll* c, bool all) {
    while (true) {
        pthread_mutex_lock(&c->clientsLock);
        ConnectedClient** link = &c->clients;
        while (*link && !(all || ((*link)->threaded ? atomic_load(&(*link)->finished) : atomic_load(&(*link)->closing))))
            link = &(*link)->next;
        ConnectedClient* client = *link;
        if (client) { *link = client->next; markClosing(client); }
        pthread_mutex_unlock(&c->clientsLock);
        if (!client) break;
        epoll_ctl(c->epollFd, EPOLL_CTL_DEL, client->fd, NULL);
        if (client->threaded) pthread_join(client->thread, NULL);
        else endClient(env, client);
        close(client->fd); free(client);
    }
}
static void acceptClients(JNIEnv* env, XConnectorEpoll* c) {
    while (atomic_load(&c->running)) {
        int fd = accept4(c->serverFd, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) { if (errno == EINTR) continue; break; }
        ConnectedClient* client = calloc(1, sizeof(*client));
        if (!client) { close(fd); continue; }
        client->fd = fd; client->connector = c; client->id = ++c->nextId;
        atomic_init(&client->closing, false); atomic_init(&client->finished, false);
        pthread_mutex_lock(&c->clientsLock);
        client->next = c->clients; c->clients = client;
        pthread_mutex_unlock(&c->clientsLock);
        if (c->multithreaded) {
            // Set this before creation; the epoll owner reaps only after this returns.
            client->threaded = true;
            if (pthread_create(&client->thread, NULL, clientThread, client) != 0) {
                client->threaded = false; markClosing(client);
            }
        } else if (addFd(c, fd, client)) beginClient(env, client);
        else markClosing(client);
    }
}
static void* epollThread(void* argument) {
    XConnectorEpoll* c = argument; JNIEnv* env = NULL;
    activeConnector = c;
    if (attachEnv(c->vm, &env) != JNI_OK) {
        atomic_store(&c->running, false); return NULL;
    }
    struct epoll_event events[MAX_EVENTS];
    while (atomic_load(&c->running)) {
        int count = epoll_wait(c->epollFd, events, MAX_EVENTS, -1);
        if (count < 0) { if (errno == EINTR) continue; break; }
        for (int i = 0; i < count; ++i) {
            if (events[i].data.ptr == &c->serverFd) acceptClients(env, c);
            else if (events[i].data.ptr == &c->wakeFd) {
                uint64_t value; while (read(c->wakeFd, &value, sizeof(value)) > 0) {}
            } else {
                ConnectedClient* client = events[i].data.ptr;
                if (events[i].events & EPOLLIN) handleClient(env, client);
                else if (events[i].events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) markClosing(client);
            }
        }
        reapClients(env, c, false);
    }
    atomic_store(&c->running, false);
    pthread_mutex_lock(&c->clientsLock);
    for (ConnectedClient* client = c->clients; client; client = client->next) markClosing(client);
    pthread_mutex_unlock(&c->clientsLock);
    reapClients(env, c, true);
    JavaVM* vm = c->vm;
    if (atomic_load(&c->selfDestroy)) { pthread_detach(pthread_self()); freeConnector(env, c); }
    activeConnector = NULL;
    (*vm)->DetachCurrentThread(vm); return NULL;
}
static void destroyConnector(JNIEnv* env, XConnectorEpoll* c) {
    if (!c) return;
    atomic_store(&c->running, false);
    // Wake any callback blocked on socket I/O before joining its owner.
    pthread_mutex_lock(&c->clientsLock);
    for (ConnectedClient* client = c->clients; client; client = client->next) markClosing(client);
    pthread_mutex_unlock(&c->clientsLock);
    if (c->started && activeConnector == c) {
        // A callback cannot join its own owner. The epoll owner frees it on exit.
        atomic_store(&c->selfDestroy, true); wakeConnector(c); return;
    }
    if (c->started) { wakeConnector(c); pthread_join(c->thread, NULL); }
    freeConnector(env, c);
}
JNIEXPORT jlong JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_nativeAllocate(JNIEnv* env, jobject object, jstring path) {
    XConnectorEpoll* c = calloc(1, sizeof(*c)); if (!c) return 0;
    atomic_init(&c->running, false); atomic_init(&c->selfDestroy, false);
    c->serverFd = c->wakeFd = c->epollFd = -1; pthread_mutex_init(&c->clientsLock, NULL);
    (*env)->GetJavaVM(env, &c->vm); c->object = (*env)->NewGlobalRef(env, object);
    jclass type = (*env)->GetObjectClass(env, object);
    c->newConnection = (*env)->GetMethodID(env, type, "handleNewConnection", "(JI)Ljava/lang/Object;");
    c->existingConnection = (*env)->GetMethodID(env, type, "handleExistingConnection", "(Ljava/lang/Object;)V");
    c->shutdownConnection = (*env)->GetMethodID(env, type, "handleConnectionShutdown", "(Ljava/lang/Object;)V");
    (*env)->DeleteLocalRef(env, type);
    if (!c->object || !c->newConnection || !c->existingConnection || !c->shutdownConnection) goto error;
    const char* name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) goto error;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (strlen(name) >= sizeof(address.sun_path)) { (*env)->ReleaseStringUTFChars(env, path, name); goto error; }
    strcpy(address.sun_path, name); (*env)->ReleaseStringUTFChars(env, path, name);
    c->serverFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    c->epollFd = epoll_create1(EPOLL_CLOEXEC); c->wakeFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (c->serverFd < 0 || c->epollFd < 0 || c->wakeFd < 0) goto error;
    unlink(address.sun_path);
    if (bind(c->serverFd, (struct sockaddr*)&address, offsetof(struct sockaddr_un, sun_path) + strlen(address.sun_path) + 1) < 0 ||
        listen(c->serverFd, MAX_EVENTS) < 0 || !addFd(c, c->serverFd, &c->serverFd) || !addFd(c, c->wakeFd, &c->wakeFd)) goto error;
    return (jlong)c;
error:
    destroyConnector(env, c); return 0;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_startEpollThread(JNIEnv* env, jclass type, jlong pointer, jboolean multithreaded) {
    XConnectorEpoll* c = (XConnectorEpoll*)pointer;
    if (!c || c->started) return;
    c->multithreaded = multithreaded; atomic_store(&c->running, true);
    c->started = pthread_create(&c->thread, NULL, epollThread, c) == 0;
    if (!c->started) {
        atomic_store(&c->running, false);
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) (*env)->ThrowNew(env, error, "Unable to start XConnectorEpoll");
    }
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_destroy(JNIEnv* env, jclass type, jlong pointer) { destroyConnector(env, (XConnectorEpoll*)pointer); }
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_killConnection(JNIEnv* env, jclass type, jlong pointer, jlong id) {
    XConnectorEpoll* c = (XConnectorEpoll*)pointer; if (!c || !id) return;
    pthread_mutex_lock(&c->clientsLock);
    for (ConnectedClient* client = c->clients; client; client = client->next) {
        if (client->id == (uint64_t)id) { markClosing(client); break; }
    }
    pthread_mutex_unlock(&c->clientsLock); wakeConnector(c);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_closeFd(jint fd) { if (fd >= 0) close(fd); }
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XConnectorEpoll_registerNatives(JNIEnv* env, jclass type) {
    const JNINativeMethod methods[] = {
        {"closeFd", "(I)V", (void*)Java_com_winlator_cmod_xconnector_XConnectorEpoll_closeFd},
    };
    (*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0]));
}
