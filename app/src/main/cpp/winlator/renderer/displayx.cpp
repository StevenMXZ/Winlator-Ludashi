#include <sys/socket.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/un.h>
#include <string.h>
#include <thread>
#include <mutex>
#include <cerrno>

#include <android/api-level.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include "displayx.hpp"

#define LOG_TAG "DisplayX"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using PFNASURFACETRANSACTIONSETPOSITION = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t, int32_t);
using PFNASURFACETRANSACTIONSETBUFFER = void (*)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int);
using PFNASURFACETRANSACTIONSETGEOMETRY = void (*)(ASurfaceTransaction*, ASurfaceControl*, const ARect&, const ARect&, int32_t);
using PFNASURFACETRANSACTIONSETZORDER = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
using PFNASURFACETRANSACTIONSETVISIBILITY = void (*)(ASurfaceTransaction*, ASurfaceControl*, enum ASurfaceTransactionVisibility);
using PFNASURFACETRANSACTIONSETBUFFERTRANSPARENCY = void (*)(ASurfaceTransaction*, ASurfaceControl*, enum ASurfaceTransactionTransparency);
using PFNASURFACETRANSACTIONSETBUFFERALPHA = void (*)(ASurfaceTransaction*, ASurfaceControl*, float);
using PFNASURFACETRANSACTIONSTATSGETPRESENTFENCEFD = int (*)(ASurfaceTransactionStats*);
using PFNASURFACETRANSACTIONREPARENT = void (*)(ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl*);
using PFNASURFACETRANSACTIONSETONCOMPLETE = void (*)(ASurfaceTransaction*, void*, ASurfaceTransaction_OnComplete);
using PFNASURFACETRANSACTIONSETENABLEBACKPRESSURE = void (*)(ASurfaceTransaction*, ASurfaceControl*, bool);
using PFNASURFACETRANSACTIONSETFRAMETIMELINE = void (*)(ASurfaceTransaction*, AVsyncId);
using PFNASURFACETRANSACTIONCREATE = ASurfaceTransaction* (*)();
using PFNASURFACETRANSACTIONDELETE = void (*)(ASurfaceTransaction*);
using PFNASURFACETRANSACTIONAPPLY = void (*)(ASurfaceTransaction*);

using PFNASURFACECONTROLACQUIRE = void (*)(ASurfaceControl*);
using PFNASURFACECONTROLRELEASE = void (*)(ASurfaceControl*);
using PFNASURFACECONTROLCREATE = ASurfaceControl* (*)(ASurfaceControl*, const char*);
using PFNASURFACECONTROLCREATEFROMWINDOW = ASurfaceControl* (*)(ANativeWindow*, const char*);

using PFNACHOREOGRAPHERGETINSTANCE = AChoreographer* (*)();
using PFNACHOREOGRAPHERPOSTFRAMECALLBACK64 = void (*)(AChoreographer*, AChoreographer_frameCallback64, void*);
using PFNACHOREOGRAPHERPOSTVSYNCCALLBACK = void (*)(AChoreographer*, AChoreographer_vsyncCallback, void*);
using PFNACHOREOGRAPHERGETFRAMETIMELINEVSYNCID = AVsyncId (*)(const AChoreographerFrameCallbackData*, size_t);
using PFNACHOREOGRAPHERGETPREFERREDTIMELINEINDEX = size_t (*)(const AChoreographerFrameCallbackData*);


using PFNAPERFORMANCEHINTGETMANAGER = APerformanceHintManager* (*)();
using PFNAPERFORMANCEHINTCREATESESSION = APerformanceHintSession* (*)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
using PFNAPERFORMANCEHINTREPORTACTUALWORKDURATION = int (*)(APerformanceHintSession*, int64_t);
using PFNAPERFORMANCEHINTUPDATETARGETWORKDURATION = int (*)(APerformanceHintSession*, int64_t);
using PFNAPERFORMANCEHINTCLOSESESSION = void (*)(APerformanceHintSession*);

static PFNASURFACETRANSACTIONSETPOSITION pfnASurfaceTransactionSetPosition = nullptr;
static PFNASURFACETRANSACTIONSETBUFFER pfnASurfaceTransactionSetBuffer = nullptr;
static PFNASURFACETRANSACTIONSETGEOMETRY pfnASurfaceTransactionSetGeometry = nullptr;
static PFNASURFACETRANSACTIONSETZORDER pfnASurfaceTransactionSetZOrder = nullptr;
static PFNASURFACETRANSACTIONSETVISIBILITY pfnASurfaceTransactionSetVisibility = nullptr;
static PFNASURFACETRANSACTIONSETBUFFERALPHA pfnASurfaceTransactionSetBufferAlpha = nullptr;
static PFNASURFACETRANSACTIONSETBUFFERTRANSPARENCY pfnASurfaceTransactionSetBufferTransparency = nullptr;
static PFNASURFACETRANSACTIONREPARENT pfnASurfaceTransactionReparent = nullptr;
static PFNASURFACETRANSACTIONSETONCOMPLETE pfnASurfaceTransactionSetOnComplete = nullptr;
static PFNASURFACETRANSACTIONSETENABLEBACKPRESSURE pfnASurfaceTransactionSetEnableBackPressure = nullptr;
static PFNASURFACETRANSACTIONSTATSGETPRESENTFENCEFD pfnASurfaceTransactionStatsGetPresentFenceFd = nullptr;
static PFNASURFACETRANSACTIONSETFRAMETIMELINE pfnASurfaceTransactionSetFrameTimeline = nullptr;
static PFNASURFACETRANSACTIONCREATE pfnASurfaceTransactionCreate = nullptr;
static PFNASURFACETRANSACTIONDELETE pfnASurfaceTransactionDelete = nullptr;
static PFNASURFACETRANSACTIONAPPLY pfnASurfaceTransactionApply = nullptr;

static PFNASURFACECONTROLACQUIRE pfnASurfaceControlAcquire = nullptr;
static PFNASURFACECONTROLRELEASE pfnASurfaceControlRelease = nullptr;
static PFNASURFACECONTROLCREATE pfnASurfaceControlCreate = nullptr;
static PFNASURFACECONTROLCREATEFROMWINDOW pfnASurfaceControlCreateFromWindow = nullptr;

static PFNACHOREOGRAPHERGETINSTANCE pfnAChoreographerGetInstance = nullptr;
static PFNACHOREOGRAPHERPOSTFRAMECALLBACK64 pfnAChoreographerPostFrameCallback64 = nullptr;
static PFNACHOREOGRAPHERPOSTVSYNCCALLBACK pfnAChoreographerPostVsyncCallback = nullptr;
static PFNACHOREOGRAPHERGETFRAMETIMELINEVSYNCID pfnAChoreographerGetFrameTimelineVsyncId = nullptr;
static PFNACHOREOGRAPHERGETPREFERREDTIMELINEINDEX pfnAChoreographerGetPreferredTimelineIndex = nullptr;

static PFNAPERFORMANCEHINTGETMANAGER pfnAPerformanceHintGetManager = nullptr;
static PFNAPERFORMANCEHINTCREATESESSION pfnAPerformanceHintCreateSession = nullptr;
static PFNAPERFORMANCEHINTREPORTACTUALWORKDURATION pfnAPerformanceHintReportActualWorkDuration = nullptr;
static PFNAPERFORMANCEHINTUPDATETARGETWORKDURATION pfnAPerformanceHintUpdateTargetWorkDuration = nullptr;
static PFNAPERFORMANCEHINTCLOSESESSION pfnAPerformanceHintCloseSession = nullptr;

void DisplayX::onFrameCallback64(int64_t frameTimeNanos, void* data) {
    auto *self = reinterpret_cast<DisplayX *>(data);
   
    if (self->cursorUpdate && self->cursorManager->control && !self->paused) {
        self->eventLock.notify();
    }
    
    {
        auto lock = self->presentLock.lock();
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->presentLock.notify();
        }
    }
    
    pfnAChoreographerPostFrameCallback64(self->choreographer, DisplayX::onFrameCallback64, self);
}

void DisplayX::onVsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data) {
    auto *self = reinterpret_cast<DisplayX *>(data);
   
    if (self->cursorUpdate && self->cursorManager->control && !self->paused) {
        self->eventLock.notify();
    }
    
    size_t index = pfnAChoreographerGetPreferredTimelineIndex(callbackData);
    AVsyncId id = pfnAChoreographerGetFrameTimelineVsyncId(callbackData, index);
    
    {
        auto lock = self->presentLock.lock();
        self->vsyncId = id;
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->presentLock.notify();
        }
    }
    
    pfnAChoreographerPostVsyncCallback(self->choreographer, DisplayX::onVsyncCallback, self);
}

static void sendFD(int& socket, int fd) {
    std::vector<char> control_buffer(CMSG_SPACE(sizeof(int)));

    char dummy = 0;
    struct iovec iov{};
    iov.iov_len = 1;
    iov.iov_base = &dummy;

    struct msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buffer.data();
    msg.msg_controllen = control_buffer.size();
                                                                                                             struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    
    *reinterpret_cast<int*>(CMSG_DATA(cmsg)) = fd;

    sendmsg(socket, &msg, 0);
}

static int readFD(int& socket) {
    std::vector<char> msg_contents(1);
    struct iovec iov{};
    iov.iov_base = msg_contents.data();
    iov.iov_len = msg_contents.size();
            
    std::vector<char> control_buf(CMSG_SPACE(sizeof(int)));
    struct msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buf.data();
    msg.msg_controllen = control_buf.size();
            
    ssize_t received;
    do { received = recvmsg(socket, &msg, MSG_WAITALL | MSG_CMSG_CLOEXEC); }
    while (received < 0 && errno == EINTR);
    if (received != 1) return -1;
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int))) return -1;
    int fd;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    if (msg.msg_flags & MSG_CTRUNC) { close(fd); return -1; }
    return fd;
}

static bool readDisplayX(int fd, void* destination, size_t count) {
    auto* data = static_cast<uint8_t*>(destination);
    while (count) {
        const ssize_t received = read(fd, data, count);
        if (received < 0 && errno == EINTR) continue;
        if (received <= 0) return false;
        data += received; count -= received;
    }
    return true;
}

void DisplayX::networkThreadLoop() {
    static constexpr int CONNECT = 0, ADD_CLIENT_SWAPCHAIN = 1, PRESENT_IMAGE = 2, DESTROY_CLIENT_SWAPCHAIN = 3;
    const int serverFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (serverFd < 0) return;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, "displayx", 8);
    const socklen_t addressLength = offsetof(sockaddr_un, sun_path) + 1 + 8;
    if (bind(serverFd, reinterpret_cast<sockaddr*>(&address), addressLength) < 0 || listen(serverFd, 8) < 0) {
        close(serverFd); return;
    }
    const int epollFd = epoll_create1(EPOLL_CLOEXEC);
    if (epollFd < 0) { close(serverFd); return; }
    epoll_event listener{}; listener.data.fd = serverFd; listener.events = EPOLLIN;
    epoll_ctl(epollFd, EPOLL_CTL_ADD, serverFd, &listener);

    std::unordered_map<int, std::shared_ptr<DisplayXPeer>> peers;
    std::unordered_map<uint64_t, std::unique_ptr<DisplayXSwapchain>> swapchains;
    auto key = [](int fd, uint8_t id) { return (uint64_t(uint32_t(fd)) << 8) | id; };
    // Called with presentLock held, which also serializes window destruction.
    auto releaseSwapchain = [&](DisplayXSwapchain& swapchain) {
        auto* current = windowManager->getWindow(swapchain.windowId);
        for (const auto& image : swapchain.images) {
            presentRequests.removeDrawable(image.get());
            if (current && current->generation == swapchain.windowGeneration && current->externalContent == image.get())
                current->externalContent = nullptr;
        }
    };
    auto disconnect = [&](int fd) {
        const auto peer = peers.find(fd);
        if (peer == peers.end()) return;
        peer->second->connected = false;
        shutdown(fd, SHUT_RDWR);
        epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, nullptr);
        auto lock = presentLock.lock();
        for (auto entry = swapchains.begin(); entry != swapchains.end();) {
            if (entry->second->clientFd == fd) {
                releaseSwapchain(*entry->second); entry = swapchains.erase(entry);
            } else ++entry;
        }
        // In-flight completion callbacks retain the old FD until they finish.
        peers.erase(peer);
    };

    JNIEnv* networkEnv = cache->getEnv();
    std::array<epoll_event, 2> events{};
    while (!stopped) {
        const int count = epoll_wait(epollFd, events.data(), events.size(), -1);
        if (count < 0) { if (errno == EINTR) continue; break; }
        if (stopped) break;
        for (int i = 0; i < count; ++i) {
            const int fd = events[i].data.fd;
            if (fd == serverFd) {
                const int client = accept4(serverFd, nullptr, nullptr, SOCK_CLOEXEC);
                if (client >= 0) {
                    peers[client] = std::make_shared<DisplayXPeer>(client);
                    epoll_event event{}; event.data.fd = client; event.events = EPOLLIN;
                    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, client, &event) < 0) peers.erase(client);
                }
                continue;
            }
            if (events[i].events & (EPOLLERR | EPOLLHUP)) { disconnect(fd); continue; }
            if (!(events[i].events & EPOLLIN) || !peers.count(fd)) continue;
            int request;
            if (!readDisplayX(fd, &request, sizeof(request))) { disconnect(fd); continue; }
            switch (request) {
            case CONNECT:
                break;
            case ADD_CLIENT_SWAPCHAIN: {
                uint8_t id;
                uint32_t imageCount, windowId, presentMode, format, width, height;
                if (!readDisplayX(fd, &id, 1) || !readDisplayX(fd, &imageCount, 4) ||
                    !readDisplayX(fd, &windowId, 4) || !readDisplayX(fd, &presentMode, 4) ||
                    !readDisplayX(fd, &format, 4) || !readDisplayX(fd, &width, 4) ||
                    !readDisplayX(fd, &height, 4) || imageCount == 0) {
                    disconnect(fd); break;
                }
                auto swapchain = std::make_unique<DisplayXSwapchain>();
                swapchain->id = id; swapchain->clientFd = fd; swapchain->windowId = windowId;
                swapchain->width = width; swapchain->height = height; swapchain->format = format;
                swapchain->imageCount = imageCount; swapchain->presentMode = presentMode;
                bool valid = true;
                for (uint32_t index = 0; index < imageCount; ++index) {
                    auto drawable = std::make_unique<Drawable>();
                    drawable->id = -1; drawable->width = width; drawable->height = height;
                    drawable->isDirectContent = false; drawable->isDisplayX = true;
                    drawable->drawableObj = nullptr; drawable->sync_fence = -1;
                    if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &drawable->ahb) != 0 || !drawable->ahb) {
                        valid = false; break;
                    }
                    AHardwareBuffer_Desc description{}; AHardwareBuffer_describe(drawable->ahb, &description);
                    drawable->stride = description.stride; drawable->format = description.format;
                    swapchain->images.push_back(std::move(drawable));
                }
                if (!valid) { disconnect(fd); break; }
                // Importing handles may block; only lock once every handle has arrived.
                auto lock = presentLock.lock();
                auto* window = windowManager->getWindow(windowId);
                if (!window) { lock.unlock(); disconnect(fd); break; }
                swapchain->window = window;
                swapchain->windowGeneration = window->generation;
                const auto previous = swapchains.find(key(fd, id));
                if (previous != swapchains.end()) releaseSwapchain(*previous->second);
                if (window->parent) window->parent->surface = window;
                swapchains[key(fd, id)] = std::move(swapchain);
                break;
            }
            case PRESENT_IMAGE: {
                uint8_t id; uint32_t index; uint64_t presentId;
                if (!readDisplayX(fd, &id, 1) || !readDisplayX(fd, &index, 4)) { disconnect(fd); break; }
                int socket = fd;
                const int fence = readFD(socket);
                if (fence < 0) { disconnect(fd); break; }
                if (!readDisplayX(fd, &presentId, 8)) {
                    if (fence >= 0) close(fence);
                    disconnect(fd); break;
                }
                auto lock = presentLock.lock();
                const auto found = swapchains.find(key(fd, id));
                if (found == swapchains.end() || index >= found->second->images.size()) {
                    if (fence >= 0) close(fence);
                    lock.unlock(); disconnect(fd); break;
                }
                auto* swapchain = found->second.get();
                auto* window = windowManager->getWindow(swapchain->windowId);
                if (!window || window->generation != swapchain->windowGeneration) {
                    if (fence >= 0) close(fence);
                    lock.unlock(); disconnect(fd); break;
                }
                auto* drawable = swapchain->images[index].get();
                window->externalContent = drawable;
                auto pending = std::make_unique<PresentRequest>();
                pending->drawable = drawable; pending->window = window; pending->sync_fence = fence;
                pending->presentId = presentId; pending->imageIndex = index; pending->swapchainId = id;
                pending->presentMode = swapchain->presentMode; pending->clientFd = fd; pending->peer = peers.at(fd);
                presentRequests.push(std::move(pending), swapchain->presentMode == VK_PRESENT_MODE_MAILBOX_KHR);
                if (xServer->isShowFPS.load(std::memory_order_relaxed))
                    networkEnv->CallVoidMethod(xServer->xserverDisplayActivity, cache->updateFrameRating, window->windowObj);
                if (!presentRR) presentLock.notify();
                break;
            }
            case DESTROY_CLIENT_SWAPCHAIN: {
                uint8_t id;
                if (!readDisplayX(fd, &id, 1)) { disconnect(fd); break; }
                auto lock = presentLock.lock();
                const auto found = swapchains.find(key(fd, id));
                if (found != swapchains.end()) { releaseSwapchain(*found->second); swapchains.erase(found); }
                break;
            }
            default:
                disconnect(fd); break;
            }
        }
    }
    for (auto& entry : peers) { entry.second->connected = false; shutdown(entry.first, SHUT_RDWR); }
    {
        auto lock = presentLock.lock();
        for (auto& entry : swapchains) releaseSwapchain(*entry.second);
        swapchains.clear(); peers.clear();
    }
    close(epollFd); close(serverFd); cache->detachEnv(networkEnv);
}

void DisplayX::eventThreadLoop() {
    bool restoreState = false;
    this->env = cache->getEnv();
    
    while (true) {
        std::function<void()> func = nullptr;
        
        auto lock = eventLock.lock();
        eventLock.wait(lock, [&]{ 
            return stopped || state != State::NONE || !eventQueue.empty() || cursorUpdate;
        });
        
        if (stopped) {
            printf("Stopping eventThread");
            cache->detachEnv(env);
            return;
        }
        
        auto currState = state;
        state = State::NONE;
        
        if (currState == State::PAUSE) {
            printf("Received state PAUSE");
            paused = true;
            eventLock.notify();
        }
            
        if (currState == State::RESUME) {
            printf("Received state RESUME");
            paused = false;
            restoreState = true;
            eventLock.notify();
        }
        
        if (currState == State::CREATE_SURFACE) {
            printf("Received state CREATE_SURFACE");
            createRootWindowControl();
            hasSurface = true;
            eventLock.notify();
        }
            
        if (currState == State::CHANGE_SURFACE) {
            printf("Received state CHANGE_SURFACE");
            resizeRootWindow();
            surfaceChanged = true;
            eventLock.notify();
        }
            
        if (hasSurface && restoreState) {
            printf("Restoring state after resume");
            restoreControlState();
            restoreState = false;
        }
            
        if (currState == State::DESTROY_SURFACE) {
            printf("Received state DESTROY_SURFACE");
            hasSurface = false;
            surfaceChanged = false;
            destroyRootWindowControl();
            eventLock.notify();
        }
        
        if (!eventQueue.empty() && hasSurface && surfaceChanged && !paused) {
            func = eventQueue.front();
            eventQueue.pop();
        }
        
        lock.unlock();
        
        if (func) {
            func();
            {
                auto l = presentLock.lock();
                eventsPending--;
            }
            presentLock.notify();
        }
        
        if (cursorUpdate) {
            updateCursorPosition();
            cursorUpdate = false;
        }
    }
}

int64_t DisplayX::getCurrentTimeNanos() {
    struct timespec ts{};
    
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void DisplayX::onCompleteCallback(void *context, ASurfaceTransactionStats *stats) {
    std::unique_ptr<OnCompleteContext> completeContext(static_cast<OnCompleteContext *>(context));
    
    for (auto &request : completeContext->requests) {
        auto peer = request->peer;
        std::array<uint8_t, 17> packet{};
        if (!peer || !peer->connected || !displayXCompletion(*request, packet)) continue;
        std::lock_guard output(peer->outputLock);
        if (!peer->connected) continue;
        size_t offset = 0;
        while (offset < packet.size()) {
            const ssize_t sent = send(peer->fd, packet.data() + offset, packet.size() - offset, MSG_NOSIGNAL);
            if (sent < 0 && errno == EINTR) continue;
            if (sent <= 0) {
                peer->connected = false;
                shutdown(peer->fd, SHUT_RDWR);
                break;
            }
            offset += sent;
        }
    }
}

void DisplayX::presentThreadLoop() {
    ASurfaceTransaction *presentTransaction = pfnASurfaceTransactionCreate();
    
    int64_t hintTarget = 0;
    while(true) {
        auto lock = presentLock.lock();
        
        presentLock.wait(lock, [&]{ 
            return stopped || (eventsPending == 0 && ((requestUpdate && presentRR) || (!presentRequests.empty() && !presentRR)) && hasSurface && surfaceChanged && !paused);
        });
        
        if (stopped) {
            printf("Stopping presentThread");
            cache->detachEnv(env);
            break;
        }
        
        const int64_t workStart = getCurrentTimeNanos();
        {
            if (perfMode && isPerformanceHintAPIAvailable()) {
                const double hz = std::isfinite(xServer->refreshRate)
                    ? std::clamp(double(xServer->refreshRate), 24.0, 1000.0) : 60.0;
                const int64_t target = static_cast<int64_t>(1000000000.0 / hz);
                if (!performanceHintSession) {
                    performanceHintManager = pfnAPerformanceHintGetManager();
                    const int32_t tid = gettid();
                    if (performanceHintManager)
                        performanceHintSession = pfnAPerformanceHintCreateSession(
                            performanceHintManager, &tid, 1, target);
                } else if (hintTarget != target) {
                    pfnAPerformanceHintUpdateTargetWorkDuration(performanceHintSession, target);
                }
                hintTarget = target;
            } else if (performanceHintSession) {
                pfnAPerformanceHintCloseSession(performanceHintSession);
                performanceHintSession = nullptr;
            }
        }
        auto requests = presentRR ? presentRequests.buildWindowTree() : presentRequests.getLast();
        
        if (vsyncId > -1) pfnASurfaceTransactionSetFrameTimeline(presentTransaction, vsyncId);
        
        if (presentRR) requestUpdate = false;
        auto completeContext = std::make_unique<OnCompleteContext>();
        
        while (!requests.empty()) {
            auto presentRequest = std::move(requests.front());
            requests.pop_front();
            
            auto window = presentRequest->window;
            if (!window || !window->control) {
                if (presentRequest->sync_fence >= 0) close(presentRequest->sync_fence);
                continue;
            }
        
            auto drawable = presentRequest->drawable;
            if (!drawable) {
                if (presentRequest->sync_fence >= 0) close(presentRequest->sync_fence);
                continue;
            }
        
            if (!window->enabled) {
                pfnASurfaceTransactionSetBuffer(presentTransaction, window->control, nullptr, presentRequest->sync_fence);
            }
            else {
                if (effectComposer->isSuitableForColorSwap(drawable)) {
                    pfnASurfaceTransactionSetBuffer(presentTransaction, window->control, drawable->composerTexture->dstBuffer, presentRequest->sync_fence);
                }
                else {
                    pfnASurfaceTransactionSetBuffer(presentTransaction, window->control, drawable->ahb, presentRequest->sync_fence);
                }
                
                if (presentRequest->clientFd >= 0 && pfnASurfaceTransactionSetEnableBackPressure) {
                    const bool fifo = presentRequest->presentMode == VK_PRESENT_MODE_FIFO_KHR;
                    if (window->backPressureEnabled != fifo) {
                        pfnASurfaceTransactionSetEnableBackPressure(presentTransaction, window->control, fifo);
                        window->backPressureEnabled = fifo;
                    }
                }
            }
            if (drawable->isDisplayX && presentRequest->clientFd >= 0)
                completeContext->requests.push_back(std::move(presentRequest));
        }
        
        if (!completeContext->requests.empty()) pfnASurfaceTransactionSetOnComplete(presentTransaction, completeContext.release(), DisplayX::onCompleteCallback);
        pfnASurfaceTransactionApply(presentTransaction);
        const int64_t workDuration = getCurrentTimeNanos() - workStart;
        // Keep controls and drawables alive until SurfaceControl retains the transaction.
        lock.unlock();
        if (performanceHintSession)
            pfnAPerformanceHintReportActualWorkDuration(performanceHintSession,
                std::max<int64_t>(1, workDuration));
    }
    
    pfnASurfaceTransactionDelete(presentTransaction);
    {
        if (isPerformanceHintAPIAvailable() && performanceHintSession)
            pfnAPerformanceHintCloseSession(performanceHintSession);
        performanceHintSession = nullptr;
        performanceHintManager = nullptr;
    }
}

void DisplayX::start() {
    void* handle = dlopen("libandroid.so", RTLD_NOW);
    pfnASurfaceTransactionSetPosition = reinterpret_cast<PFNASURFACETRANSACTIONSETPOSITION>(dlsym(handle,"ASurfaceTransaction_setPosition"));
    pfnASurfaceTransactionSetBuffer = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFER>(dlsym(handle,"ASurfaceTransaction_setBuffer"));
    pfnASurfaceTransactionSetGeometry = reinterpret_cast<PFNASURFACETRANSACTIONSETGEOMETRY>(dlsym(handle,"ASurfaceTransaction_setGeometry"));
    pfnASurfaceTransactionSetZOrder = reinterpret_cast<PFNASURFACETRANSACTIONSETZORDER>(dlsym(handle,"ASurfaceTransaction_setZOrder"));
    pfnASurfaceTransactionSetVisibility = reinterpret_cast<PFNASURFACETRANSACTIONSETVISIBILITY>(dlsym(handle,"ASurfaceTransaction_setVisibility"));
    pfnASurfaceTransactionReparent = reinterpret_cast<PFNASURFACETRANSACTIONREPARENT>(dlsym(handle,"ASurfaceTransaction_reparent"));
    pfnASurfaceTransactionSetOnComplete = reinterpret_cast<PFNASURFACETRANSACTIONSETONCOMPLETE>(dlsym(handle,"ASurfaceTransaction_setOnComplete"));
    pfnASurfaceTransactionSetEnableBackPressure = reinterpret_cast<PFNASURFACETRANSACTIONSETENABLEBACKPRESSURE>(dlsym(handle,"ASurfaceTransaction_setEnableBackPressure"));
    pfnASurfaceTransactionSetBufferAlpha = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFERALPHA>(dlsym(handle, "ASurfaceTransaction_setBufferAlpha"));
    pfnASurfaceTransactionSetBufferTransparency = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFERTRANSPARENCY>(dlsym(handle, "ASurfaceTransaction_setBufferTransparency"));
    pfnASurfaceTransactionStatsGetPresentFenceFd = reinterpret_cast<PFNASURFACETRANSACTIONSTATSGETPRESENTFENCEFD>(dlsym(handle, "ASurfaceTransactionStats_getPresentFenceFd"));
    pfnASurfaceTransactionSetFrameTimeline = reinterpret_cast<PFNASURFACETRANSACTIONSETFRAMETIMELINE>(dlsym(handle, "ASurfaceTransaction_setFrameTimeline"));

    pfnASurfaceTransactionCreate = reinterpret_cast<PFNASURFACETRANSACTIONCREATE>(dlsym(handle,"ASurfaceTransaction_create"));
    pfnASurfaceTransactionDelete = reinterpret_cast<PFNASURFACETRANSACTIONDELETE>(dlsym(handle,"ASurfaceTransaction_delete"));
    pfnASurfaceTransactionApply = reinterpret_cast<PFNASURFACETRANSACTIONAPPLY>(dlsym(handle,"ASurfaceTransaction_apply"));

    pfnASurfaceControlAcquire = reinterpret_cast<PFNASURFACECONTROLACQUIRE>(dlsym(handle,"ASurfaceControl_acquire"));
    pfnASurfaceControlRelease = reinterpret_cast<PFNASURFACECONTROLRELEASE>(dlsym(handle,"ASurfaceControl_release"));
    pfnASurfaceControlCreate = reinterpret_cast<PFNASURFACECONTROLCREATE>(dlsym(handle,"ASurfaceControl_create"));
    pfnASurfaceControlCreateFromWindow = reinterpret_cast<PFNASURFACECONTROLCREATEFROMWINDOW>(dlsym(handle,"ASurfaceControl_createFromWindow"));

    pfnAChoreographerGetInstance = reinterpret_cast<PFNACHOREOGRAPHERGETINSTANCE>(dlsym(handle,"AChoreographer_getInstance"));
    pfnAChoreographerPostFrameCallback64 = reinterpret_cast<PFNACHOREOGRAPHERPOSTFRAMECALLBACK64>(dlsym(handle,"AChoreographer_postFrameCallback64"));
    pfnAChoreographerPostVsyncCallback = reinterpret_cast<PFNACHOREOGRAPHERPOSTVSYNCCALLBACK>(dlsym(handle, "AChoreographer_postVsyncCallback"));
    pfnAChoreographerGetFrameTimelineVsyncId = reinterpret_cast<PFNACHOREOGRAPHERGETFRAMETIMELINEVSYNCID>(dlsym(handle, "AChoreographerFrameCallbackData_getFrameTimelineVsyncId"));
    pfnAChoreographerGetPreferredTimelineIndex = reinterpret_cast<PFNACHOREOGRAPHERGETPREFERREDTIMELINEINDEX>(dlsym(handle, "AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex"));
    
    pfnAPerformanceHintGetManager = reinterpret_cast<PFNAPERFORMANCEHINTGETMANAGER>(dlsym(handle, "APerformanceHint_getManager"));
    pfnAPerformanceHintCreateSession = reinterpret_cast<PFNAPERFORMANCEHINTCREATESESSION>(dlsym(handle, "APerformanceHint_createSession"));
    pfnAPerformanceHintReportActualWorkDuration = reinterpret_cast<PFNAPERFORMANCEHINTREPORTACTUALWORKDURATION>(dlsym(handle, "APerformanceHint_reportActualWorkDuration"));
    pfnAPerformanceHintUpdateTargetWorkDuration = reinterpret_cast<PFNAPERFORMANCEHINTUPDATETARGETWORKDURATION>(dlsym(handle, "APerformanceHint_updateTargetWorkDuration"));
    pfnAPerformanceHintCloseSession = reinterpret_cast<PFNAPERFORMANCEHINTCLOSESESSION>(dlsym(handle, "APerformanceHint_closeSession"));
        
    eventThread = std::thread(&DisplayX::eventThreadLoop, this);
    networkThread = std::thread(&DisplayX::networkThreadLoop, this);
    presentThread = std::thread(&DisplayX::presentThreadLoop, this);
   
    this->choreographer = pfnAChoreographerGetInstance();
    
    if (precisePresentation && pfnAChoreographerPostVsyncCallback)
        pfnAChoreographerPostVsyncCallback(this->choreographer, DisplayX::onVsyncCallback, this);
    else     
        pfnAChoreographerPostFrameCallback64(this->choreographer, DisplayX::onFrameCallback64, this);
}

void DisplayX::stop() {
    stopped = true;
    eventLock.notify();
    presentLock.notify();
}

void DisplayX::pause() {
    auto lock = eventLock.lock();
    state = State::PAUSE;
    eventLock.notify();
    eventLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::resume() {
    auto lock = eventLock.lock();
    state = State::RESUME;
    eventLock.notify();
    eventLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::createSurface(ANativeWindow *window) {
    auto lock = eventLock.lock();
    this->native_window = window;
    state = State::CREATE_SURFACE;
    eventLock.notify();
    eventLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::changeSurface(int width, int height) {
    auto lock = eventLock.lock();
    this->surfaceWidth = width;
    this->surfaceHeight = height;
    state = State::CHANGE_SURFACE;
    eventLock.notify();
    eventLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::destroySurface() {
    auto lock = eventLock.lock();
    this->native_window = nullptr;
    state = State::DESTROY_SURFACE;
    eventLock.notify();
    eventLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::queueEvent(std::function<void()> func) {
    auto lock = eventLock.lock();
    {
        auto l = presentLock.lock();
        eventsPending++;
    }
    eventQueue.push(func);
    eventLock.notify();
}

void DisplayX::requestWindowUpdate(Window *window) {
    auto lock = presentLock.lock();
    
    if (!window || window->isHidden()) return;
    auto drawable = window->drawable.get();
    
    if (effectComposer->isSuitableForColorSwap(drawable))
        effectComposer->apply(drawable);
        
    auto presentRequest = std::make_unique<PresentRequest>();
    presentRequest->drawable = window->hasExternalContents() ? window->externalContent : drawable;
    presentRequest->sync_fence = -1;
    presentRequest->presentId = -1;
    presentRequest->clientFd = -1;
    presentRequest->window = window;
    
    presentRequests.push(std::move(presentRequest));
    if (!presentRR) presentLock.notify();
}

void DisplayX::requestCursorUpdate() {
    if (!cursorVisible) return;
    this->cursorUpdate = true;
}

void DisplayX::createWindowControl(Window *window) {
    if (!window->parent || !window->inputOutput) return;
        
    window->control = pfnASurfaceControlCreate(window->parent->control, "displayx");
    if (pfnASurfaceControlAcquire)    
        pfnASurfaceControlAcquire(window->control);
    
    pfnASurfaceTransactionSetBufferTransparency(windowTransaction, window->control,
        ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    pfnASurfaceTransactionSetZOrder(windowTransaction, window->control, window->z_order);
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }  
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::releaseWindowControls(Window* window) {
    for (auto* child : window->children) releaseWindowControls(child);
    presentRequests.removeWindow(window);
    if (window->drawable && window->drawable->composerTexture)
        effectComposer->destroyComposerTexture(window->drawable.get());
    if (window->control) {
        pfnASurfaceControlRelease(window->control);
        window->control = nullptr;
    }
}

void DisplayX::destroyWindow(int id) {
    auto lock = presentLock.lock();
    auto* window = windowManager->getWindow(id);
    if (!window) return;
    releaseWindowControls(window);
    windowManager->deleteWindow(window);
}

void DisplayX::mapWindow(Window *window) {
    if (!window->control) return;
    
    if (!windowManager->getUnviewableWMClass().empty() && !strcmp(window->className.c_str(), windowManager->getUnviewableWMClass().c_str()))
        window->enabled = false;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::unmapWindow(Window *window) {
    if (!window->control) return;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::changeGeometry(Window *window, bool resized) {
    if (!window->control) return;
    
    if (resized)
        pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, nullptr, -1);
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }
    
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::changeZOrder(Window *window) {
    pfnASurfaceTransactionSetZOrder(windowTransaction, window->control, window->z_order);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::updateCursor(Cursor *cursor) {
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, cursor && (cursor->visible && cursorVisible) ? cursor->image->ahb : nullptr, -1);
    pfnASurfaceTransactionApply(cursorTransaction);
}

void DisplayX::updateCursorPosition() {
    if (!cursorManager->control) return;
    
    jobject pointWindowObj = env->CallObjectMethod(xServer->inputDeviceManager, cache->getPointWindow);
    jint id = env->GetIntField(pointWindowObj, cache->windowID);
    auto pointWindow = windowManager->getWindow(id);
    auto cursor = (pointWindow != nullptr) ? pointWindow->cursor : nullptr;
    auto rootCursor = cursorManager->getRootCursor();
    int x = std::clamp(cursorManager->pointer.posX, 0, windowManager->getRootWindow()->width - 1);
    int y = std::clamp(cursorManager->pointer.posY, 0, windowManager->getRootWindow()->height - 1);
    
    if (cursorVisible || (cursor && cursor->visible)) {
        if (pfnASurfaceTransactionSetPosition) {
            pfnASurfaceTransactionSetPosition(cursorTransaction, cursorManager->control, x, y);
        }
        else {
            auto& cursorImage = (cursor != nullptr) ? cursor->image : rootCursor->image;
            ARect src{};
            ARect dst = {
                .left = x,
                .top = y,
                .right = x + cursorImage->width,
                .bottom = y + cursorImage->height
            };
            pfnASurfaceTransactionSetGeometry(cursorTransaction, cursorManager->control, src, dst, 0);
        }
        
        pfnASurfaceTransactionApply(cursorTransaction);
    }
    else {
        pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        pfnASurfaceTransactionApply(cursorTransaction);
    }
}

void DisplayX::createRootCursorControl() {
    int ret;
    
    auto rootCursor = cursorManager->getRootCursor();
    auto rootWindow = windowManager->getRootWindow();
    if (!rootCursor || !rootWindow) return;
    
    cursorManager->control = pfnASurfaceControlCreate(rootWindow->control, "displayx");
    if (pfnASurfaceControlAcquire)
        pfnASurfaceControlAcquire(cursorManager->control);
    
    cursorTransaction = pfnASurfaceTransactionCreate();
    pfnASurfaceTransactionSetBufferTransparency(cursorTransaction, cursorManager->control,
        ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
}

void DisplayX::showCursor() {
    if (!cursorManager->control) createRootCursorControl();
    
    auto rootCursor = cursorManager->getRootCursor();
    if (!rootCursor) return;
    
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, rootCursor->image->ahb, -1);
    pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, cursorVisible ? ASURFACE_TRANSACTION_VISIBILITY_SHOW : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    pfnASurfaceTransactionSetZOrder(cursorTransaction, cursorManager->control, INT32_MAX);
    pfnASurfaceTransactionApply(cursorTransaction);
}

void DisplayX::reparentWindow(Window *window, Window *parent) {
    if (!window->control) return;
    
    pfnASurfaceTransactionReparent(windowTransaction, window->control, parent->control);
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }
    
    pfnASurfaceTransactionApply(windowTransaction);
}

bool DisplayX::isPerformanceHintAPIAvailable() {
    return pfnAPerformanceHintGetManager &&
           pfnAPerformanceHintCreateSession &&
           pfnAPerformanceHintUpdateTargetWorkDuration &&
           pfnAPerformanceHintReportActualWorkDuration &&
           pfnAPerformanceHintCloseSession;
}

void DisplayX::createRootWindowControl() {
    int ret;
    
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    rootWindow->control = pfnASurfaceControlCreateFromWindow(this->native_window, "displayx");
    if (pfnASurfaceControlAcquire)      
        pfnASurfaceControlAcquire(rootWindow->control);
 
    windowTransaction = pfnASurfaceTransactionCreate();
    pfnASurfaceTransactionSetBufferTransparency(windowTransaction, rootWindow->control,
        ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
}

void DisplayX::destroyRootWindowControl() {
    this->native_window = nullptr;
    pfnASurfaceTransactionDelete(windowTransaction);
    
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    if (!rootWindow->control) return;

    pfnASurfaceControlRelease(rootWindow->control);
    rootWindow->control = nullptr;
}

void DisplayX::destroyRootCursorControl() {
    pfnASurfaceTransactionDelete(cursorTransaction);
    
    auto rootCursor = cursorManager->getRootCursor();
    if (!rootCursor) return;
    
    if (!cursorManager->control) return;

    pfnASurfaceControlRelease(cursorManager->control);
    cursorManager->control = nullptr;
}

void DisplayX::resizeRootWindow() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    viewTransformation.update(surfaceWidth, surfaceHeight, rootWindow->width, rootWindow->height);
    
    ARect src{};
    ARect dst{};
    
    if (fullscreen) {
        src.left = viewTransformation.viewOffsetX;
        src.top = viewTransformation.viewOffsetY;
        src.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        src.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
        
        dst.left = 0;
        dst.top = 0;
        dst.right = surfaceWidth;
        dst.bottom = surfaceHeight;
    }
    else {
        src.left = 0;
        src.top = 0;
        src.right = surfaceWidth;
        src.bottom = surfaceHeight;
        
        dst.left = viewTransformation.viewOffsetX;
        dst.top = viewTransformation.viewOffsetY;
        dst.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        dst.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
    }
    
    pfnASurfaceTransactionSetGeometry(windowTransaction, rootWindow->control, src, dst, 0);
    pfnASurfaceTransactionSetBuffer(windowTransaction, rootWindow->control, rootWindow->drawable->ahb, -1);
    pfnASurfaceTransactionSetZOrder(windowTransaction, rootWindow->control, rootWindow->z_order);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::restoreControlState() {
    const auto& windowTree = windowManager->getWindowTree();
    auto rootWindow = windowManager->getRootWindow();
    
    for (const auto& entry : windowTree) {
        auto window = entry.second.get();
        if (window == rootWindow) continue;
        if (!window->control || !window->parent->control) continue;
        
        pfnASurfaceTransactionReparent(windowTransaction, window->control, window->parent->control);
        pfnASurfaceTransactionApply(windowTransaction);
    }
    
    if (cursorManager->control && rootWindow->control) {
        pfnASurfaceTransactionReparent(cursorTransaction, cursorManager->control, rootWindow->control);
        pfnASurfaceTransactionApply(cursorTransaction);
    }
}

void DisplayX::toggleFullscreen() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    fullscreen = !fullscreen;
    
    ARect src{};
    ARect dst{};
    
    if (fullscreen) {
        src.left = viewTransformation.viewOffsetX;
        src.top = viewTransformation.viewOffsetY;
        src.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        src.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
        
        dst.left = 0;
        dst.top = 0;
        dst.right = surfaceWidth;
        dst.bottom = surfaceHeight;
    }
    else {
        src.left = 0;
        src.top = 0;
        src.right = surfaceWidth;
        src.bottom = surfaceHeight;
        
        dst.left = viewTransformation.viewOffsetX;
        dst.top = viewTransformation.viewOffsetY;
        dst.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        dst.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
    }
    
    pfnASurfaceTransactionSetGeometry(windowTransaction, rootWindow->control, src, dst, 0);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::setPerformanceMode(bool perfMode) {
    this->perfMode = perfMode;
}

void DisplayX::setPresentRR(bool presentRR) {
    this->presentRR = presentRR;
}

void DisplayX::setPrecisePresentation(bool precisePresentation) {
    this->precisePresentation = precisePresentation;
}
