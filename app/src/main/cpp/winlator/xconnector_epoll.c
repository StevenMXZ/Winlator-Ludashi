#include <stdbool.h>
#include <jni.h>
#include <sys/epoll.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/eventfd.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <malloc.h>
#include <jni.h>
#include <android/log.h>
#include <pthread.h>

#include "jni_utils.h"
#include "winlator.h"

#define MAX_EVENTS 10

typedef struct JMethods {
    JavaVM* jvm;
    JNIEnv* env;
    jobject obj;
    jmethodID handleConnectionShutdown;
    jmethodID handleNewConnection;
    jmethodID handleExistingConnection;
    jmethodID killAllConnections;
} JMethods;

typedef struct XConnectorEpoll {
    pthread_t epollThread;
    bool running;
    int epollFd;
    int serverFd;
    int shutdownFd;
    bool multithreadedClients;
    JMethods jmethods;
} XConnectorEpoll;

typedef struct ConnectedClient {
    pthread_t pollThread;
    int fd;
    int shutdownFd;
    bool running;
    void* tag;
    JMethods jmethods;
} ConnectedClient;

static int createServerSocket(const char* sockPath) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un serverAddr = {0};
    serverAddr.sun_family = AF_LOCAL;

    int addrLength = sizeof(sa_family_t) + strlen(sockPath);
    strncpy(serverAddr.sun_path, sockPath, sizeof(serverAddr.sun_path) - 1);

    unlink(serverAddr.sun_path);
    if (bind(fd, (struct sockaddr*) &serverAddr, addrLength) < 0) goto error;
    if (listen(fd, MAX_EVENTS) < 0) goto error;

    return fd;

error:
    CLOSEFD(fd);
    return -1;
}

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->handleConnectionShutdown = (*env)->GetMethodID(env, cls, "handleConnectionShutdown", "(Ljava/lang/Object;)V");
    jmethods->handleNewConnection = (*env)->GetMethodID(env, cls, "handleNewConnection", "(JI)Ljava/lang/Object;");
    jmethods->handleExistingConnection = (*env)->GetMethodID(env, cls, "handleExistingConnection", "(Ljava/lang/Object;)V");
    jmethods->killAllConnections = (*env)->GetMethodID(env, cls, "killAllConnections", "()V");
}

static bool addFdToEpoll(int epollFd, int fd, void* ptr) {
    struct epoll_event event = {0};
    if (ptr) {
        event.data.ptr = ptr;
    }
    else event.data.fd = fd;
    event.events = EPOLLIN;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) < 0) return false;
    return true;
}

static void removeFdFromEpoll(int epollFd, int fd) {
    epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, NULL);
}

static int waitForSocketRead(jint clientFd, jint shutdownFd) {
    struct pollfd pfds[2] = {0};
    pfds[0].fd = clientFd;
    pfds[0].events = POLLIN;

    pfds[1].fd = shutdownFd;
    pfds[1].events = POLLIN;

    int res = poll(pfds, 2, -1);
    if (res < 0 || (pfds[1].revents & POLLIN)) return -1;
    return (pfds[0].revents & POLLIN) ? 1 : 0;
}

static void requestShutdown(int fd) {
    const uint64_t shutdownValue = 1;
    write(fd, &shutdownValue, sizeof(uint64_t));
}

static void XConnectorEpoll_destroy(JNIEnv* env, XConnectorEpoll* connector) {
    if (connector->serverFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->serverFd);
        CLOSEFD(connector->serverFd);
    }

    if (connector->shutdownFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->shutdownFd);
        CLOSEFD(connector->shutdownFd);
    }

    if (connector->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, connector->jmethods.obj);
        connector->jmethods.obj = NULL;
    }

    CLOSEFD(connector->epollFd);
    free(connector);
}

static XConnectorEpoll* XConnectorEpoll_allocate(JNIEnv* env, jobject obj, const char* sockPath) {
    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));

    connector->epollFd = epoll_create(MAX_EVENTS);
    if (connector->epollFd < 0) goto error;

    connector->serverFd = createServerSocket(sockPath);
    if (connector->serverFd < 0) goto error;

    connector->shutdownFd = eventfd(0, EFD_NONBLOCK);
    if (connector->shutdownFd < 0) goto error;

    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;

    (*env)->GetJavaVM(env, &connector->jmethods.jvm);
    connector->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    return connector;

error:
    XConnectorEpoll_destroy(env, connector);
    return NULL;
}

static void XConnectorEpoll_killConnection(XConnectorEpoll* connector, ConnectedClient* client) {
    JMethods* jmethods = &connector->jmethods;
    client->running = false;

    if (connector->multithreadedClients) {
        if (pthread_self() != client->pollThread) {
            requestShutdown(client->shutdownFd);
            pthread_join(client->pollThread, NULL);
            client->pollThread = 0;
        }
        else jmethods = &client->jmethods;

        CLOSEFD(client->shutdownFd);
    }
    else removeFdFromEpoll(connector->epollFd, client->fd);

    CLOSEFD(client->fd);

    if (client->tag) {
        (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleConnectionShutdown, client->tag);
        client->tag = NULL;
    }
}

static void* pollThread(void* param) {
    ConnectedClient* client = param;
    loadJMethods(&client->jmethods);
    JMethods* jmethods = &client->jmethods;
    client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->handleNewConnection, (jlong)client, client->fd);

    int res;
    do {
        res = waitForSocketRead(client->fd, client->shutdownFd);
        if (res == 1) (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleExistingConnection, client->tag);
    }
    while (client->running && res >= 0);

    if (client->tag) {
        (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleConnectionShutdown, client->tag);
        client->tag = NULL;
    }
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);
    return NULL;
}

static void XConnectorEpoll_handleNewConnection(XConnectorEpoll* connector, int clientFd) {
    JMethods* jmethods = &connector->jmethods;
    ConnectedClient* client = calloc(1, sizeof(ConnectedClient));
    client->fd = clientFd;
    client->running = true;

    if (connector->multithreadedClients) {
        client->jmethods.jvm = connector->jmethods.jvm;
        client->jmethods.obj = connector->jmethods.obj;
        client->shutdownFd = eventfd(0, EFD_NONBLOCK);
        pthread_create(&client->pollThread, NULL, pollThread, client);
    }
    else {
        addFdToEpoll(connector->epollFd, clientFd, client);
        client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->handleNewConnection, (jlong)client, clientFd);
    }
}

static void* epollThread(void* param) {
    XConnectorEpoll* connector = param;
    JMethods* jmethods = &connector->jmethods;
    loadJMethods(jmethods);
    struct epoll_event events[MAX_EVENTS] = {0};

    while (connector->running) {
        int numFds = epoll_wait(connector->epollFd, events, MAX_EVENTS, -1);
        if (numFds < 0) break;
        for (int i = 0; i < numFds; i++) {
            if (events[i].data.ptr == &connector->serverFd) {
                int clientFd = accept(connector->serverFd, NULL, NULL);
                if (clientFd >= 0) XConnectorEpoll_handleNewConnection(connector, clientFd);
            }
            else if (events[i].data.ptr != &connector->shutdownFd &&
                    (events[i].events & EPOLLIN)) {
                ConnectedClient* client = events[i].data.ptr;
                (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleExistingConnection, client->tag);
            }
        }
    }

    (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->killAllConnections);
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);
    return NULL;
}

static void XConnectorEpoll_startEpollThread(XConnectorEpoll* connector, bool multithreadedClients) {
    if (connector->running) return;
    connector->running = true;
    connector->multithreadedClients = multithreadedClients;
    pthread_create(&connector->epollThread, NULL, epollThread, connector);
}

static void XConnectorEpoll_stopEpollThread(XConnectorEpoll* connector) {
    if (!connector->running) return;
    connector->running = false;
    requestShutdown(connector->shutdownFd);

    pthread_join(connector->epollThread, NULL);
    connector->epollThread = 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_closeFd(jint fd) {
    CLOSEFD(fd);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_nativeAllocate(JNIEnv* env, jobject obj,
                                                            jstring sockPath) {
    const char* pathPtr = (*env)->GetStringUTFChars(env, sockPath, 0);
    XConnectorEpoll* connector = XConnectorEpoll_allocate(env, obj, pathPtr);

    (*env)->ReleaseStringUTFChars(env, sockPath, pathPtr);
    return (jlong)connector;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_destroy(JNIEnv *env, jobject obj, jlong nativePtr) {
    XConnectorEpoll_destroy(env, (XConnectorEpoll*)nativePtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_startEpollThread(JNIEnv *env, jobject obj,
                                                              jlong nativePtr, jboolean multithreadedClients) {
    XConnectorEpoll_startEpollThread((XConnectorEpoll*)nativePtr, multithreadedClients);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_stopEpollThread(JNIEnv *env, jobject obj,
                                                             jlong nativePtr) {
    XConnectorEpoll_stopEpollThread((XConnectorEpoll*)nativePtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XConnectorEpoll_killConnection(JNIEnv *env, jclass obj,
                                                            jlong connectorPtr, jlong clientPtr) {
    XConnectorEpoll_killConnection((XConnectorEpoll*)connectorPtr, (ConnectedClient*)clientPtr);
}