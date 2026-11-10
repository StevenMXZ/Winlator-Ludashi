#include <sys/socket.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/un.h>
#include <string.h>
#include <thread>
#include <mutex>

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
using PFNASURFACETRANSACTIONSETBUFFERDATASPACE = void (*)(ASurfaceTransaction*, ASurfaceControl*, enum ADataSpace data_space);
using PFNASURFACETRANSACTIONSETHDRMETADATA_SMPTE2086 = void (*)(ASurfaceTransaction*, ASurfaceControl*, struct AHdrMetadata_smpte2086*);
using PFNASURFACETRANSACTIONSETHDRMETADATA_CTA861_3 = void (*)(ASurfaceTransaction*, ASurfaceControl*, struct AHdrMetadata_cta861_3*);
using PFNASURFACETRANSACTIONSTATSGETPRESENTFENCEFD = int (*)(ASurfaceTransactionStats*);
using PFNASURFACETRANSACTIONREPARENT = void (*)(ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl*);
using PFNASURFACETRANSACTIONSETONCOMPLETE = void (*)(ASurfaceTransaction*, void*, void (*)(void*, ASurfaceTransactionStats*));
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
static PFNASURFACETRANSACTIONSETBUFFERDATASPACE pfnASurfaceTransactionSetBufferDataSpace = nullptr;
static PFNASURFACETRANSACTIONSETHDRMETADATA_SMPTE2086 pfnASurfaceTransactionSetHdrMetadataSmpte2086 = nullptr;
static PFNASURFACETRANSACTIONSETHDRMETADATA_CTA861_3 pfnASurfaceTransactionSetHdrMetadataCta8613 = nullptr;
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

int64_t DisplayX::getCurrentTimeNanos() {
    struct timespec ts{};

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void DisplayX::onFrameCallback64(int64_t frameTimeNanos, void* data) {
    std::unique_ptr<FrameCallback> callback(static_cast<FrameCallback*>(data));
    auto* self = callback->self;
    if (self->stopped || callback->generation != self->frameGeneration.load()) return;
    if (self->stopped) return;
   
    if (self->cursorUpdate && !self->paused) {
        self->cursorLock.notify();
    }
    
    {
        auto lock = self->windowLock.lock();
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->windowLock.notify();
        }
    }
    
    pfnAChoreographerPostFrameCallback64(self->choreographer, DisplayX::onFrameCallback64, callback.release());
}

void DisplayX::onVsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data) {
    std::unique_ptr<FrameCallback> callback(static_cast<FrameCallback*>(data));
    auto* self = callback->self;
    if (self->stopped || callback->generation != self->frameGeneration.load()) return;
   
    if (self->cursorUpdate && !self->paused) {
        self->cursorLock.notify();
    }
    
    if (self->stopped) return;
    size_t index = pfnAChoreographerGetPreferredTimelineIndex(callbackData);
    AVsyncId id = pfnAChoreographerGetFrameTimelineVsyncId(callbackData, index);
    

    {
        auto lock = self->windowLock.lock();
        self->vsyncId = id;
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->windowLock.notify();
        }
    }
    
    pfnAChoreographerPostVsyncCallback(self->choreographer, DisplayX::onVsyncCallback, callback.release());
}

static ADataSpace colorSpaceToADataSpace(uint32_t& colorSpace) {
    switch (colorSpace) {
        case VK_COLOR_SPACE_SRGB_NONLINEAR_KHR:
            return ADATASPACE_SRGB;
        case VK_COLOR_SPACE_HDR10_ST2084_EXT:
            return ADATASPACE_BT2020_PQ;
        case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT:
            return ADATASPACE_SCRGB_LINEAR;
        default:
            return ADATASPACE_SRGB;
    }
}

void DisplayX::networkThreadLoop() {
    serverFd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (serverFd < 0) return;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, "displayx", 8);
    const socklen_t addressLength = offsetof(sockaddr_un, sun_path) + 1 + 8;
    if (bind(serverFd, reinterpret_cast<sockaddr*>(&address), addressLength) < 0 || listen(serverFd, 8) < 0) {
        close(serverFd); return;
    }
    epollFd = epoll_create1(EPOLL_CLOEXEC);
    if (epollFd < 0) { close(serverFd); return; }
    epoll_event listener{}; listener.data.fd = serverFd; listener.events = EPOLLIN;
    epoll_ctl(epollFd, EPOLL_CTL_ADD, serverFd, &listener);

    std::unordered_map<int, std::shared_ptr<DisplayXPeer>> peers;
    auto& swapchains = clientSwapchains;
    auto key = [](int fd, uint8_t id) { return (uint64_t(uint32_t(fd)) << 8) | id; };
    // Called with windowLock held, which also serializes window destruction.
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
        auto lock = windowLock.lock();
        for (auto entry = swapchains.begin(); entry != swapchains.end();) {
            if (entry->second->fd == fd) {
                releaseSwapchain(*entry->second); entry = swapchains.erase(entry);
            } else ++entry;
        }
        // In-flight completion callbacks retain the old FD until they finish.
        peers.erase(peer);
    };

    JNIEnv* networkEnv = cache->getEnv();
    epoll_event stopEvent{}; stopEvent.data.fd = stopFd; stopEvent.events = EPOLLIN;
    epoll_ctl(epollFd, EPOLL_CTL_ADD, stopFd, &stopEvent);
    std::array<epoll_event, 64> events{};
    while (!stopped) {
        const int count = epoll_wait(epollFd, events.data(), events.size(), -1);
        if (count < 0) { if (errno == EINTR) continue; break; }
        if (stopped) break;
        for (int i = 0; i < count; ++i) {
            const int fd = events[i].data.fd;
            if (fd == stopFd) { uint64_t value; read(stopFd, &value, sizeof(value)); continue; }
            if (fd == serverFd) {
                const int client = accept4(serverFd, nullptr, nullptr, SOCK_CLOEXEC);
                if (client >= 0) {
                    timeval timeout{2, 0};
                    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
                    peers[client] = std::make_shared<DisplayXPeer>(client);
                    epoll_event event{}; event.data.fd = client; event.events = EPOLLIN;
                    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, client, &event) < 0) peers.erase(client);
                }
                continue;
            }
            if (events[i].events & (EPOLLERR | EPOLLHUP)) { disconnect(fd); continue; }
            if (!(events[i].events & EPOLLIN) || !peers.count(fd)) continue;
            displayx_wire::Packet packet;
            if (!displayx_wire::receive(fd, packet)) { disconnect(fd); continue; }
            const auto request = packet.get<uint32_t>(0);
            switch (request) {
            case displayx_wire::Create: {
                if (packet.size != 25 || packet.fence >= 0) { disconnect(fd); break; }
                const uint8_t id = packet.get<uint8_t>(4);
                const uint32_t imageCount = packet.get<uint32_t>(5), windowId = packet.get<uint32_t>(9);
                const uint32_t presentMode = packet.get<uint32_t>(13), format = packet.get<uint32_t>(17);
                const uint32_t colorSpace = packet.get<uint32_t>(21);
                uint32_t width, height; uint64_t generation;
                {
                    auto lock = windowLock.lock();
                    auto* window = windowManager->getWindow(windowId);
                    if (!window) { lock.unlock(); disconnect(fd); break; }
                    width = window->width; height = window->height; generation = window->generation;
                }
                if (!imageCount || imageCount > 16 || !width || !height || width > 16384 || height > 16384 ||
                    presentMode > 3) { disconnect(fd); break; }
                auto swapchain = std::make_unique<DisplayXSwapchain>();
                swapchain->id = id; swapchain->fd = fd; swapchain->windowId = windowId;
                swapchain->colorSpace = colorSpace; swapchain->width = width; swapchain->height = height; swapchain->format = format;
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
                auto lock = windowLock.lock();
                auto* window = windowManager->getWindow(windowId);
                if (!window || window->generation != generation) { lock.unlock(); disconnect(fd); break; }
                swapchain->window = window;
                swapchain->windowGeneration = window->generation;
                const auto previous = swapchains.find(key(fd, id));
                if (previous != swapchains.end()) releaseSwapchain(*previous->second);
                if (window->parent) window->parent->surface = window;
                swapchains[key(fd, id)] = std::move(swapchain);
                break;
            }
            case displayx_wire::Present: {
                if (packet.size != 17) { disconnect(fd); break; }
                const uint8_t id = packet.get<uint8_t>(4);
                const uint32_t index = packet.get<uint32_t>(5);
                const uint64_t presentId = packet.get<uint64_t>(9);
                const int fence = packet.takeFence();
                auto lock = windowLock.lock();
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
                pending->colorSpace = swapchain->colorSpace; pending->presentMode = swapchain->presentMode; pending->clientFd = fd; pending->peer = peers.at(fd);
                pending->hdr = swapchain->hdr; pending->hasHdr = swapchain->hasHdr;
                presentRequests.push(std::move(pending), swapchain->presentMode != VK_PRESENT_MODE_FIFO_KHR);
                if (xServer->isShowFPS.load(std::memory_order_relaxed))
                    networkEnv->CallVoidMethod(xServer->xserverDisplayActivity, cache->updateFrameRating, window->windowObj);
                if (!presentRR || swapchain->presentMode == 2) windowLock.notify();
                break;
            }
            case displayx_wire::Hdr: {
                if (packet.size != 53 || packet.fence >= 0) { disconnect(fd); break; }
                const uint8_t id = packet.get<uint8_t>(4);
                std::array<float, 12> metadata{};
                bool valid = true;
                for (size_t i = 0; i < metadata.size(); ++i) {
                    metadata[i] = packet.get<float>(5 + i * 4);
                    valid &= std::isfinite(metadata[i]);
                }
                if (!valid) { disconnect(fd); break; }
                auto lock = windowLock.lock();
                const auto found = swapchains.find(key(fd, id));
                if (found != swapchains.end()) {
                    found->second->hdr = metadata; found->second->hasHdr = true;
                }
                break;
            }
            case displayx_wire::Destroy: {
                if (packet.size != 5 || packet.fence >= 0) { disconnect(fd); break; }
                const uint8_t id = packet.get<uint8_t>(4);
                auto lock = windowLock.lock();
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
        auto lock = windowLock.lock();
        for (auto& entry : swapchains) releaseSwapchain(*entry.second);
        swapchains.clear(); peers.clear();
    }
    close(epollFd); close(serverFd); epollFd = serverFd = -1; cache->detachEnv(networkEnv);
}



void DisplayX::windowThreadLoop() {
    bool restoreState = false;
    int64_t hintTarget = 0;
    while (true) {
        auto lock = windowLock.lock();
        windowLock.wait(lock, [&] {
            return stopped || state != State::NONE ||
                (hasSurface && surfaceChanged && !paused &&
                 (!windowQueue.empty() || (presentRR && !presentRequests.hasFifo() ? requestUpdate && !presentRequests.empty() : !presentRequests.empty())));
        });
        if (stopped) break;
        const int64_t workStart = getCurrentTimeNanos();
        auto currState = state;
        state = State::NONE;
        if (currState == State::PAUSE) paused = true;
        if (currState == State::RESUME) { paused = false; restoreState = true; }
        if (currState == State::CREATE_SURFACE) { createRootWindowControl(); hasSurface = true; restoreState = true; }
        if (currState == State::CHANGE_SURFACE) { resizeRootWindow(); surfaceChanged = true; }
        if (currState == State::DESTROY_SURFACE) {
            hasSurface = false; surfaceChanged = false; destroyRootWindowControl();
        }
        if (hasSurface && restoreState) { restoreControlState(); restoreState = false; }
        windowLock.notify();
        bool changed = currState != State::NONE;
        if (hasSurface && surfaceChanged && !paused && !windowQueue.empty()) {
            auto func = std::move(windowQueue.front()); windowQueue.pop();
            func(); changed = true;
        }
        const bool paced = presentRR && !presentRequests.hasFifo();
        auto requests = hasSurface && surfaceChanged && !paused && (!paced || requestUpdate)
            ? (paced ? presentRequests.buildWindowTree() : presentRequests.getLast())
            : std::deque<std::unique_ptr<PresentRequest>>{};
        if (paced) requestUpdate = false;
        if (!changed && requests.empty()) continue;
        if (vsyncId > -1 && pfnASurfaceTransactionSetFrameTimeline)
            pfnASurfaceTransactionSetFrameTimeline(windowTransaction, vsyncId);
        if (perfMode && isPerformanceHintAPIAvailable()) {
            const double hz = std::isfinite(xServer->refreshRate) ? std::clamp(double(xServer->refreshRate), 24., 1000.) : 60.;
            const int64_t target = int64_t(1000000000. / hz);
            if (!performanceHintSession) {
                performanceHintManager = pfnAPerformanceHintGetManager();
                const int32_t tid = gettid();
                if (performanceHintManager) performanceHintSession = pfnAPerformanceHintCreateSession(performanceHintManager, &tid, 1, target);
            } else if (target != hintTarget) pfnAPerformanceHintUpdateTargetWorkDuration(performanceHintSession, target);
            hintTarget = target;
        } else if (performanceHintSession) {
            pfnAPerformanceHintCloseSession(performanceHintSession); performanceHintSession = nullptr;
        }
        auto complete = std::make_unique<OnCompleteContext>();
        while (!requests.empty()) {
            auto request = std::move(requests.front()); requests.pop_front();
            auto* window = request->window;
            auto* drawable = request->drawable;
            if (!window || !window->control || !drawable) {
                if (request->sync_fence >= 0) close(request->sync_fence);
                continue;
            }
            if (!window->enabled) {
                pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, nullptr, request->sync_fence);
            } else {
                const uint32_t colorSpace = request->colorSpace;
                if (pfnASurfaceTransactionSetBufferDataSpace && window->displayXColorSpace != colorSpace) {
                    const auto space = colorSpace == UINT32_MAX ? ADATASPACE_UNKNOWN : colorSpaceToADataSpace(request->colorSpace);
                    pfnASurfaceTransactionSetBufferDataSpace(windowTransaction, window->control, space);
                    window->displayXColorSpace = colorSpace;
                }
                if (request->hasHdr && (!window->displayXHasHdr || window->displayXHdr != request->hdr)) {
                    AHdrMetadata_smpte2086 smpte{}; AHdrMetadata_cta861_3 cta{};
                    static_assert(sizeof(smpte) == 10 * sizeof(float) && sizeof(cta) == 2 * sizeof(float));
                    memcpy(&smpte, request->hdr.data(), sizeof(smpte)); memcpy(&cta, request->hdr.data() + 10, sizeof(cta));
                    if (pfnASurfaceTransactionSetHdrMetadataSmpte2086) pfnASurfaceTransactionSetHdrMetadataSmpte2086(windowTransaction, window->control, &smpte);
                    if (pfnASurfaceTransactionSetHdrMetadataCta8613) pfnASurfaceTransactionSetHdrMetadataCta8613(windowTransaction, window->control, &cta);
                    window->displayXHdr = request->hdr; window->displayXHasHdr = true;
                }
                auto* buffer = effectComposer->isSuitableForColorSwap(drawable) ? drawable->composerTexture->dstBuffer : drawable->ahb;
                pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, buffer, request->sync_fence);
                if (request->clientFd >= 0 && pfnASurfaceTransactionSetEnableBackPressure) {
                    const bool fifo = request->presentMode == VK_PRESENT_MODE_FIFO_KHR;
                    if (window->backPressureEnabled != fifo) {
                        pfnASurfaceTransactionSetEnableBackPressure(windowTransaction, window->control, fifo);
                        window->backPressureEnabled = fifo;
                    }
                }
            }
            if (drawable->isDisplayX && request->clientFd >= 0) complete->requests.push_back(std::move(request));
        }
        if (!complete->requests.empty()) pfnASurfaceTransactionSetOnComplete(windowTransaction, complete.release(), DisplayX::onCompleteCallback);
        pfnASurfaceTransactionApply(windowTransaction);
        const int64_t work = getCurrentTimeNanos() - workStart;
        lock.unlock();
        if (performanceHintSession) pfnAPerformanceHintReportActualWorkDuration(performanceHintSession, std::max<int64_t>(1, work));
    }
    if (performanceHintSession) pfnAPerformanceHintCloseSession(performanceHintSession);
    performanceHintSession = nullptr; performanceHintManager = nullptr;
}

void DisplayX::onCompleteCallback(void *context, ASurfaceTransactionStats *) {
    std::unique_ptr<OnCompleteContext> complete(static_cast<OnCompleteContext*>(context));
    struct Group { std::shared_ptr<DisplayXPeer> peer; std::vector<displayx_wire::Completion> values; };
    std::unordered_map<DisplayXPeer*, Group> groups;
    for (const auto& request : complete->requests) {
        if (!request->peer || (request->presentId == UINT64_MAX && request->presentMode != 2)) continue;
        auto& group = groups[request->peer.get()];
        group.peer = request->peer;
        group.values.push_back({request->swapchainId, request->presentId, request->imageIndex});
    }
    for (const auto& [key, group] : groups) {
        auto packet = displayx_wire::completion(group.values);
        std::lock_guard lock(group.peer->outputLock);
        if (group.peer->connected && !displayx_wire::sendPacket(group.peer->fd, packet)) {
            group.peer->connected = false;
            shutdown(group.peer->fd, SHUT_RDWR);
        }
    }
}

void DisplayX::cursorThreadLoop() {
    JNIEnv* env = cache->getEnv();
    
    while(true) {
        std::function<void()> func = nullptr;
        bool updateCursor = false;

        auto lock = cursorLock.lock();

        cursorLock.wait(lock, [&]{
            return stopped || ((cursorUpdate || !cursorQueue.empty()) && hasSurface && surfaceChanged && !paused);
        });
        
        if (stopped) {
            printf("Stopping cursorThread");
            cache->detachEnv(env);
            break;
        }
        

        if (!cursorQueue.empty()) {
            func = cursorQueue.front();
            cursorQueue.pop();
        }

        if (cursorUpdate) {
            updateCursor = true;
            cursorUpdate = false;
        }

        lock.unlock();
        auto windows = windowLock.lock();
        if (!hasSurface || !surfaceChanged || paused) {
            auto pending = cursorLock.lock();
            if (func) cursorQueue.push(std::move(func));
            cursorUpdate = cursorUpdate || updateCursor;
            continue;
        }
        if (!cursorManager->control) createRootCursorControl();
        if (!cursorManager->control) continue;
        if (func) func();
        if (updateCursor) {
            jobject pointWindowObj = env->CallObjectMethod(xServer->inputDeviceManager, cache->getPointWindow);
            jint id = env->GetIntField(pointWindowObj, cache->windowID);
            env->DeleteLocalRef(pointWindowObj);
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
            }
            else {
                pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            }
        }
        
        pfnASurfaceTransactionApply(cursorTransaction);
    }
}

void DisplayX::start() {
    stopped = false; paused = false; hasSurface = false; surfaceChanged = false;
    state = State::NONE;
    void* handle = dlopen("libandroid.so", RTLD_NOW);
    int res;

    pfnASurfaceTransactionSetPosition = reinterpret_cast<PFNASURFACETRANSACTIONSETPOSITION>(dlsym(handle,"ASurfaceTransaction_setPosition"));
    pfnASurfaceTransactionSetBuffer = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFER>(dlsym(handle,"ASurfaceTransaction_setBuffer"));
    pfnASurfaceTransactionSetGeometry = reinterpret_cast<PFNASURFACETRANSACTIONSETGEOMETRY>(dlsym(handle,"ASurfaceTransaction_setGeometry"));
    pfnASurfaceTransactionSetZOrder = reinterpret_cast<PFNASURFACETRANSACTIONSETZORDER>(dlsym(handle,"ASurfaceTransaction_setZOrder"));
    pfnASurfaceTransactionSetVisibility = reinterpret_cast<PFNASURFACETRANSACTIONSETVISIBILITY>(dlsym(handle,"ASurfaceTransaction_setVisibility"));
    pfnASurfaceTransactionReparent = reinterpret_cast<PFNASURFACETRANSACTIONREPARENT>(dlsym(handle,"ASurfaceTransaction_reparent"));
    pfnASurfaceTransactionSetOnComplete = reinterpret_cast<PFNASURFACETRANSACTIONSETONCOMPLETE>(dlsym(handle,"ASurfaceTransaction_setOnComplete"));
    pfnASurfaceTransactionSetEnableBackPressure = reinterpret_cast<PFNASURFACETRANSACTIONSETENABLEBACKPRESSURE>(dlsym(handle,"ASurfaceTransaction_setEnableBackPressure"));
    pfnASurfaceTransactionSetBufferAlpha = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFERALPHA>(dlsym(handle, "ASurfaceTransaction_setBufferAlpha"));
    pfnASurfaceTransactionSetBufferDataSpace = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFERDATASPACE>(dlsym(handle, "ASurfaceTransaction_setBufferDataSpace"));
    pfnASurfaceTransactionSetHdrMetadataSmpte2086 = reinterpret_cast<PFNASURFACETRANSACTIONSETHDRMETADATA_SMPTE2086>(dlsym(handle, "ASurfaceTransaction_setHdrMetadata_smpte2086"));
    pfnASurfaceTransactionSetHdrMetadataCta8613 = reinterpret_cast<PFNASURFACETRANSACTIONSETHDRMETADATA_CTA861_3>(dlsym(handle, "ASurfaceTransaction_setHdrMetadata_cta861_3"));
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
        
    windowTransaction = pfnASurfaceTransactionCreate();
    cursorTransaction = pfnASurfaceTransactionCreate();

    stopFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    windowThread = std::thread(&DisplayX::windowThreadLoop, this);
    networkThread = std::thread(&DisplayX::networkThreadLoop, this);
    cursorThread = std::thread(&DisplayX::cursorThreadLoop, this);
   
    this->choreographer = pfnAChoreographerGetInstance();
    
    auto* callback = new FrameCallback{this, ++frameGeneration};
    if (pfnAChoreographerPostVsyncCallback && pfnAChoreographerGetPreferredTimelineIndex &&
        pfnAChoreographerGetFrameTimelineVsyncId && pfnASurfaceTransactionSetFrameTimeline)
        pfnAChoreographerPostVsyncCallback(this->choreographer, DisplayX::onVsyncCallback, callback);
    else     
        pfnAChoreographerPostFrameCallback64(this->choreographer, DisplayX::onFrameCallback64, callback);
}

void DisplayX::stop() {
    stopped = true;
    ++frameGeneration;
    const uint64_t value = 1;
    if (stopFd >= 0) write(stopFd, &value, sizeof(value));
    windowLock.notify(); cursorLock.notify();
    if (windowThread.joinable()) windowThread.join();
    if (cursorThread.joinable()) cursorThread.join();
    if (networkThread.joinable()) networkThread.join();
    if (stopFd >= 0) close(stopFd);
    stopFd = -1;
    presentRequests.clear();
    while (!windowQueue.empty()) windowQueue.pop();
    while (!cursorQueue.empty()) cursorQueue.pop();
    pfnASurfaceTransactionDelete(windowTransaction); pfnASurfaceTransactionDelete(cursorTransaction);
}

void DisplayX::pause() {
    auto lock = windowLock.lock();
    state = State::PAUSE;
    windowLock.notify();
    windowLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::resume() {
    auto lock = windowLock.lock();
    state = State::RESUME;
    windowLock.notify();
    windowLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::createSurface(ANativeWindow *window) {
    auto lock = windowLock.lock();
    this->native_window = window;
    state = State::CREATE_SURFACE;
    windowLock.notify();
    windowLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::changeSurface(int width, int height) {
    auto lock = windowLock.lock();
    this->surfaceWidth = width;
    this->surfaceHeight = height;
    state = State::CHANGE_SURFACE;
    windowLock.notify();
    windowLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::destroySurface() {
    auto lock = windowLock.lock();
    this->native_window = nullptr;
    state = State::DESTROY_SURFACE;
    windowLock.notify();
    windowLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::queueWindowEvent(std::function<void()> func) {
    auto lock = windowLock.lock();
    windowQueue.push(func);
    windowLock.notify();
}

void DisplayX::requestWindowUpdate(Window *window) {
    auto lock = windowLock.lock();
    
    if (!window || window->isHidden())
        return;

    auto drawable = window->drawable.get();
    
    if (effectComposer->isSuitableForColorSwap(drawable))
        effectComposer->apply(drawable);
        
    auto presentRequest = std::make_unique<PresentRequest>();
    presentRequest->drawable = window->hasExternalContents() ? window->externalContent : drawable;
    presentRequest->sync_fence = -1;
    presentRequest->presentId = UINT64_MAX;
    presentRequest->clientFd = -1;
    presentRequest->window = window;
    presentRequest->swapchainId = -1;
    presentRequest->presentMode = -1;
    presentRequest->colorSpace = UINT32_MAX;
    presentRequest->imageIndex = UINT32_MAX;
    
    presentRequests.push(std::move(presentRequest), true);
    if (!presentRR)
        windowLock.notify();
}

void DisplayX::queueCursorEvent(std::function<void()> func) {
    auto lock = cursorLock.lock();
    cursorQueue.push(func);
    cursorLock.notify();
}

void DisplayX::requestCursorUpdate() {
    if (!cursorVisible)
        return;

    this->cursorUpdate = true;
}

void DisplayX::createWindowControl(Window *window) {
    auto lock = windowLock.lock();
    createWindowControlLocked(window);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::createWindowControlLocked(Window *window) {
    if (!window->parent || !window->inputOutput || !window->parent->control || window->control)
        return;
        
    window->control = pfnASurfaceControlCreate(window->parent->control, "displayx");
    if (!window->control) return;
    window->displayXColorSpace = UINT32_MAX; window->displayXHasHdr = false; window->backPressureEnabled = false;
    if (pfnASurfaceControlAcquire)    
        pfnASurfaceControlAcquire(window->control);
    
    pfnASurfaceTransactionSetBufferTransparency(windowTransaction, window->control, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
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

}

void DisplayX::releaseWindowControls(Window* window) {
    for (auto* child : window->children) releaseWindowControls(child);
    presentRequests.removeWindow(window);
    if (window->drawable && window->drawable->composerTexture)
        effectComposer->destroyComposerTexture(window->drawable.get());
    if (window->control) {
        pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, nullptr, -1);
        pfnASurfaceTransactionReparent(windowTransaction, window->control, nullptr);
        pfnASurfaceControlRelease(window->control);
        window->control = nullptr;
    }
}

void DisplayX::destroyWindow(int id) {
    auto* window = windowManager->getWindow(id);
    if (!window) return;
    releaseWindowControls(window);
    windowManager->deleteWindow(window);
}

void DisplayX::mapWindow(Window *window) {
    if (!window->control)
        return;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
}

void DisplayX::unmapWindow(Window *window) {
    if (!window->control)
        return;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
}

void DisplayX::changeGeometry(Window *window, bool resized) {
    if (!window->control)
        return;
    
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
}

void DisplayX::changeZOrder(Window *window) {
    pfnASurfaceTransactionSetZOrder(windowTransaction, window->control, window->z_order);
}

void DisplayX::updateCursor(Cursor *cursor) {
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, cursor && (cursor->visible && cursorVisible) ? cursor->image->ahb : nullptr, -1);
}

void DisplayX::createRootCursorControl() {
    int ret;
    
    auto rootCursor = cursorManager->getRootCursor();
    auto rootWindow = windowManager->getRootWindow();
    if (!rootCursor || !rootWindow)
        return;
    
    cursorManager->control = pfnASurfaceControlCreate(rootWindow->control, "displayx");
    if (!cursorManager->control) return;
    pfnASurfaceTransactionSetBufferTransparency(cursorTransaction, cursorManager->control, ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
    if (pfnASurfaceControlAcquire)
        pfnASurfaceControlAcquire(cursorManager->control);
}

void DisplayX::showCursor() {
    auto rootCursor = cursorManager->getRootCursor();
    if (!rootCursor)
        return;
    
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, rootCursor->image->ahb, -1);
    pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, cursorVisible ? ASURFACE_TRANSACTION_VISIBILITY_SHOW : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    pfnASurfaceTransactionSetZOrder(cursorTransaction, cursorManager->control, INT32_MAX);
}

void DisplayX::reparentWindow(Window *window, Window *parent) {
    if (!window->control)
        return;
    
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
    if (!rootWindow)
        return;
    
    rootWindow->control = pfnASurfaceControlCreateFromWindow(this->native_window, "displayx");
    if (!rootWindow->control) return;
    pfnASurfaceTransactionSetBufferTransparency(windowTransaction, rootWindow->control, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    if (pfnASurfaceControlAcquire)      
        pfnASurfaceControlAcquire(rootWindow->control);
}

void DisplayX::destroyRootWindowControl() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow || !rootWindow->control)
        return;

    pfnASurfaceControlRelease(rootWindow->control);
    rootWindow->control = nullptr;
    this->native_window = nullptr;
}

void DisplayX::destroyRootCursorControl() {
    auto rootCursor = cursorManager->getRootCursor();
    if (!rootCursor || !cursorManager->control)
        return;
    

    pfnASurfaceControlRelease(cursorManager->control);
    cursorManager->control = nullptr;
}

void DisplayX::resizeRootWindow() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow)
        return;
    
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
}

void DisplayX::restoreControlState() {
    const auto& windowTree = windowManager->getWindowTree();
    auto rootWindow = windowManager->getRootWindow();
    
    std::function<void(Window*)> restore = [&](Window* parent) {
        for (auto* window : parent->children) {
            createWindowControlLocked(window);
            if (window->control && parent->control) {
                pfnASurfaceTransactionReparent(windowTransaction, window->control, parent->control);
                pfnASurfaceTransactionSetVisibility(windowTransaction, window->control,
                    window->mapped ? ASURFACE_TRANSACTION_VISIBILITY_SHOW : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            }
            restore(window);
        }
    };
    restore(rootWindow);
        
    if (cursorManager->control && rootWindow->control) {
        auto lock = cursorLock.lock();
        pfnASurfaceTransactionReparent(cursorTransaction, cursorManager->control, rootWindow->control);
        cursorUpdate = true;
        cursorLock.notify();
    }

    for (auto it = clientSwapchains.begin(); it != clientSwapchains.end(); it++) {
        char buf[32];

        uint32_t requestCode = DisplayXCodes::RESET_STATE;
        memcpy(buf, &requestCode, 4);

        memcpy(buf + 4, &it->second->id, 1);
        send(it->second->fd, buf, 5, MSG_NOSIGNAL | MSG_DONTWAIT);
    }
}

void DisplayX::toggleFullscreen() {
    auto lock = windowLock.lock();

    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow)
        return;
    
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

    windowLock.notify();
}

void DisplayX::setPerformanceMode(bool perfMode) {
    this->perfMode = perfMode;
}

void DisplayX::setPresentRR(bool presentRR) {
    this->defaultRR = presentRR;
    this->presentRR.store(this->defaultRR.load());
}
