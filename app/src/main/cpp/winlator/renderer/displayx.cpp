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
using PFNASURFACETRANSACTIONSETONCOMPLETE = void (*)(ASurfaceTransaction*, void*, ASurfaceTransaction_OnComplete);
using PFNASURFACETRANSACTIONSETONCOMMIT = void (*)(ASurfaceTransaction*, void*, ASurfaceTransaction_OnCommit);
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
static PFNASURFACETRANSACTIONSETONCOMMIT pfnASurfaceTransactionSetOnCommit = nullptr;
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
    auto *self = reinterpret_cast<DisplayX *>(data);
   
    if (self->cursorUpdate && self->cursorManager->control && !self->paused) {
        self->cursorLock.notify();
    }
    
    {
        auto lock = self->windowLock.lock();
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->windowLock.notify();
        }
    }
    
    if (self->isPerformanceHintAPIAvailable() && self->perfMode && !self->performanceHintSession) {
        self->performanceHintManager = pfnAPerformanceHintGetManager();
        float targetFloat = self->xServer->refreshRate * 100.0f;
        int64_t targetWorkDuration = static_cast<int64_t>(1000000000.0f / targetFloat);
    
        int tid = gettid();
        std::vector<int32_t> tids{tid};
        self->performanceHintSession = pfnAPerformanceHintCreateSession(self->performanceHintManager,
            tids.data(), tids.size(), targetWorkDuration);
    }     
    else if (self->isPerformanceHintAPIAvailable() && self->perfMode && self->performanceHintSession && ((self->requestUpdate && self->presentRR) || (!self->presentRequests.empty() && !self->presentRR))) {
        if (self->previousReportedWorkTime == 0) {
            auto currentTime = self->getCurrentTimeNanos();
            self->previousReportedWorkTime = currentTime;
        }     
        else {
            auto currentTime = self->getCurrentTimeNanos();
            auto elapsed = currentTime - self->previousReportedWorkTime;
            pfnAPerformanceHintReportActualWorkDuration(self->performanceHintSession, elapsed);
            self->previousReportedWorkTime = currentTime;
        }
    }
    
    pfnAChoreographerPostFrameCallback64(self->choreographer, DisplayX::onFrameCallback64, self);
}

void DisplayX::onVsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data) {
    auto *self = reinterpret_cast<DisplayX *>(data);
   
    if (self->cursorUpdate && self->cursorManager->control && !self->paused) {
        self->cursorLock.notify();
    }
    
    size_t index = pfnAChoreographerGetPreferredTimelineIndex(callbackData);
    AVsyncId id = pfnAChoreographerGetFrameTimelineVsyncId(callbackData, index);
    pfnASurfaceTransactionSetFrameTimeline(self->windowTransaction, id);
    
    {
        auto lock = self->windowLock.lock();
        if (!self->presentRequests.empty() && self->presentRR) {
            self->requestUpdate = true;
            self->windowLock.notify();
        }
    }
    
    if (self->isPerformanceHintAPIAvailable() && self->perfMode && !self->performanceHintSession) {
        self->performanceHintManager = pfnAPerformanceHintGetManager();
        float targetFloat = self->xServer->refreshRate * 100.0f;
        int64_t targetWorkDuration = static_cast<int64_t>(1000000000.0f / targetFloat);
    
        int tid = gettid();
        std::vector<int32_t> tids{tid};
        self->performanceHintSession = pfnAPerformanceHintCreateSession(self->performanceHintManager,
            tids.data(), tids.size(), targetWorkDuration);
    }     
    else if (self->isPerformanceHintAPIAvailable() && self->perfMode && self->performanceHintSession && ((self->requestUpdate && self->presentRR) || (!self->presentRequests.empty() && !self->presentRR))) {
        if (self->previousReportedWorkTime == 0) {
            auto currentTime = self->getCurrentTimeNanos();
            self->previousReportedWorkTime = currentTime;
        }     
        else {
            auto currentTime = self->getCurrentTimeNanos();
            auto elapsed = currentTime - self->previousReportedWorkTime;
            pfnAPerformanceHintReportActualWorkDuration(self->performanceHintSession, elapsed);
            self->previousReportedWorkTime = currentTime;
        }
    }
    
    pfnAChoreographerPostVsyncCallback(self->choreographer, DisplayX::onVsyncCallback, self);
}

static int readAncillaryMessage(int socket, void *data, size_t len, int *fd) {
    char *buf = static_cast<char *>(data);
    struct iovec iov{};
    iov.iov_base = buf;
    iov.iov_len = len;
            
    std::vector<char> control_buf(CMSG_SPACE(sizeof(int)));
    struct msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buf.data();
    msg.msg_controllen = control_buf.size();
            
    ssize_t size = recvmsg(socket, &msg, 0);
    if (size < 0)
        return -1;
        
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);    
    
    if (static_cast<size_t>(size) < len || (msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC))) {
        if (cmsg != nullptr) {
            *fd = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
            close(*fd);
        }
    
        return -1;    
    }
            
    if (cmsg == nullptr) {
        *fd = -1;
    }     
    else {
        *fd = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
    }
            
    return 0;
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
    std::array<struct epoll_event, 1024> events;
    int n;
    
    JNIEnv *env = cache->getEnv();
       
    while ((n = epoll_wait(epollFd, events.data(), events.size(), -1))) {
        if (stopped) {
            printf("Stopping networkThread");
            return;
        }
        
        for (int i = 0; i < n; i++) {
            if (events[i].data.fd == serverFd) {
                if (events[i].events & EPOLLIN) {
                    printf("Received new client connection");
                    int client_fd = accept(serverFd, nullptr, nullptr);
                    struct epoll_event event{};
                    event.data.fd = client_fd;
                    event.events = EPOLLIN;
                    epoll_ctl(epollFd, EPOLL_CTL_ADD, client_fd, &event);
                }
            } 
            else {
                if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                    printf("Client has disconnected");
                    epoll_ctl(epollFd, EPOLL_CTL_DEL, events[i].data.fd, nullptr);
                    close(events[i].data.fd);
                    continue;
                }
                
                if (events[i].events & EPOLLIN) {
                    uint32_t requestCode;
                    char buf[64];
                    
                    struct iovec iov{};
                    iov.iov_base = buf;
                    iov.iov_len = 64;
            
                    std::vector<char> control_buf(CMSG_SPACE(sizeof(int)));
                    
                    struct msghdr msg{};
                    msg.msg_iov = &iov;
                    msg.msg_iovlen = 1;
                    msg.msg_control = control_buf.data();
                    msg.msg_controllen = control_buf.size();
            
                    ssize_t size = recvmsg(events[i].data.fd, &msg, 0);
                    
                    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); 
                    
                    if (size < 0) {
                        if (errno == EAGAIN || errno == EINTR)
                            continue;

                        epoll_ctl(epollFd, EPOLL_CTL_DEL, events[i].data.fd, nullptr);
                        close(events[i].data.fd);
                        continue;
                    }
                    
                    if (size == 0 || static_cast<size_t>(size) < 4 || (msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC))) {
                        if (cmsg != nullptr) {
                            int fd = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
                            close(fd);
                        }
                        
                        epoll_ctl(epollFd, EPOLL_CTL_DEL, events[i].data.fd, nullptr);
                        close(events[i].data.fd);
                        continue;
                    }
        
                    memcpy(&requestCode, buf, 4);
                    
                    switch (requestCode) {
                        case DisplayXCodes::CREATE_SWAPCHAIN:
                        {
                            uint8_t id;
                            uint32_t imageCount;
                            uint32_t windowId;
                            uint32_t presentMode;
                            uint32_t format;
                            uint32_t colorSpace;
                            
                            if (static_cast<size_t>(size) < 25)
                                continue;
                            
                            memcpy(&id, buf + 4, 1);
                            memcpy(&imageCount, buf + 5, 4);
                            memcpy(&windowId, buf + 9, 4);
                            memcpy(&presentMode, buf + 13, 4);
                            memcpy(&format, buf + 17, 4);
                            memcpy(&colorSpace, buf + 21, 4);
                            
                            auto window = windowManager->getWindow(windowId);
                            if (!window) 
                                continue;
                            
                            if (presentMode == VK_PRESENT_MODE_FIFO_KHR) {  
                                pfnASurfaceTransactionSetEnableBackPressure(windowTransaction, window->control, true);
                                presentRR = false;
                            }
                            else {
                                pfnASurfaceTransactionSetEnableBackPressure(windowTransaction, window->control, false);
                                presentRR.store(defaultRR.load());
                            }
                            
                            printf("Creating client swapchain: id %d, images %d, presentMode %d, format %d, colorSpace %d", id, imageCount, presentMode, format, colorSpace);
                            
                            auto swapchain = std::make_unique<DisplayXSwapchain>();
                            swapchain->id = id;
                            swapchain->window = window;
                            swapchain->width = window->width;
                            swapchain->height = window->height;
                            swapchain->imageCount = imageCount;
                            swapchain->presentMode = presentMode;
                            swapchain->format = format;
                            swapchain->colorSpace = colorSpace;
                            swapchain->fd = events[i].data.fd;
                            
                            swapchain->images.resize(imageCount);
                            for (uint32_t j = 0; j < imageCount; j++) {
                                auto drawable = std::make_unique<Drawable>();
                                drawable->id = -1;
                                drawable->width = window->width;
                                drawable->height = window->height;
                                drawable->data = nullptr;
                                AHardwareBuffer_recvHandleFromUnixSocket(events[i].data.fd, &drawable->ahb);
                                AHardwareBuffer_Desc outDesc{};
                                AHardwareBuffer_describe(drawable->ahb, &outDesc);
                                drawable->stride = outDesc.stride;
                                drawable->format = outDesc.format;
                                drawable->isDirectContent = false;
                                drawable->isDisplayX = true;
                                drawable->sync_fence = -1;
                                
                                swapchain->images[j] = std::move(drawable);
                            }
                            
                            window->parent->surface = window;
                            
                            clientSwapchains[id] = std::move(swapchain);
                            
                            break;
                        }
                        case DisplayXCodes::PRESENT:
                        {
                            uint8_t id;
                            uint32_t index;
                            uint64_t presentId;
                            int sync_fence = -1;
                            
                            if (cmsg != nullptr)
                                sync_fence = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
                            
                            if (static_cast<size_t>(size) < 17) {
                                if (sync_fence > -1)
                                    close(sync_fence);
                                
                                continue;
                            }    
                                
                            memcpy(&id, buf + 4, 1);
                            memcpy(&index, buf + 5, 4);
                            memcpy(&presentId, buf + 9, 8);
                            
                            auto swapchain = clientSwapchains[id].get();
                            if (!swapchain)
                                continue;

                            auto drawable = swapchain->images.at(index).get();
                            if (!drawable)
                                continue;
                            
                            //printf("Presenting image %d, swapchain %d, presentId %d, fence %d", index, id, presentId, sync_fence);         
                            
                            auto lock = windowLock.lock();      
                            
                            swapchain->window->externalContent = drawable;
                            swapchain->fd = events[i].data.fd;
                            
                            auto presentRequest = std::make_unique<PresentRequest>();
                            presentRequest->drawable = drawable;
                            presentRequest->sync_fence = sync_fence;
                            presentRequest->presentId = presentId;
                            presentRequest->index = index;
                            presentRequest->clientFd = events[i].data.fd;
                            presentRequest->window = swapchain->window;
                            presentRequest->swapchainId = swapchain->id;
                            presentRequest->colorSpace = swapchain->colorSpace;
                            presentRequest->presentMode = swapchain->presentMode;
                            
                            presentRequests.push(std::move(presentRequest), swapchain->presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? true : false);
                            if (xServer->isShowFPS) 
                                env->CallVoidMethod(xServer->xserverDisplayActivity, cache->updateFrameRating, swapchain->window->id);
                           
                            if (!presentRR) 
                                windowLock.notify();
                                
                            break;
                        }    
                        case DisplayXCodes::DESTROY_SWAPCHAIN: {
                            uint8_t id;
                            
                            if (static_cast<size_t>(size) < 5)
                                continue;
                                
                            memcpy(&id, buf + 4, 1);
                            
                            auto swapchain = clientSwapchains[id].get();
                            if (!swapchain)
                                continue;
                            
                            printf("Destroying client swapchain: id %d", id);
                            
                            swapchain->images.clear();
                            swapchain->window->externalContent = nullptr;
                            
                            clientSwapchains.erase(id);
                            
                            break;
                        }
                        case DisplayXCodes::HDR_METADATA: {
                            uint8_t id;
                            AHdrMetadata_smpte2086 smpteMetadata{};
                            AHdrMetadata_cta861_3 ctaMetadata{};
                            
                            if (static_cast<size_t>(size) < 53)
                                continue;
                                
                            memcpy(&id, buf + 4, 1);
                            
                            size_t offset = 5;

                            #define UNSERIALIZE_METADATA(param) \
                                memcpy(param, buf + offset, sizeof(float)); \
                                offset += sizeof(float);
                            
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryRed.x);
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryRed.y);
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryGreen.x);
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryGreen.y);
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryBlue.x);
                            UNSERIALIZE_METADATA(&smpteMetadata.displayPrimaryBlue.y);
                            UNSERIALIZE_METADATA(&smpteMetadata.whitePoint.x);
                            UNSERIALIZE_METADATA(&smpteMetadata.whitePoint.y);
                            UNSERIALIZE_METADATA(&smpteMetadata.maxLuminance);
                            UNSERIALIZE_METADATA(&smpteMetadata.minLuminance);
                            UNSERIALIZE_METADATA(&ctaMetadata.maxContentLightLevel);
                            UNSERIALIZE_METADATA(&ctaMetadata.maxFrameAverageLightLevel);
                            
                            auto swapchain = clientSwapchains[id].get();
                            if (!swapchain)
                                continue;
                            
                            printf("Setting HDR metadata for swapchain: id %d", id);
                                     
                            pfnASurfaceTransactionSetHdrMetadataSmpte2086(windowTransaction, swapchain->window->control, &smpteMetadata);
                            pfnASurfaceTransactionSetHdrMetadataCta8613(windowTransaction, swapchain->window->control, &ctaMetadata);
                            
                            break;
                        }
                        default:
                            break;            
                    }
                }
            }
        }
    }
}

void DisplayX::windowThreadLoop() {
    bool restoreState = false;
    
    while (true) {
        std::function<void()> func = nullptr;
        std::deque<std::unique_ptr<PresentRequest>> requests;
        
        auto lock = windowLock.lock();
        windowLock.wait(lock, [&]{ 
            return stopped || state != State::NONE || !windowQueue.empty() || ((requestUpdate && presentRR) || (!presentRequests.empty() && !presentRR));
        });
        
        if (stopped) {
            printf("Stopping windowThread");
            return;
        }
        
        auto currState = state;
        state = State::NONE;
        
        if (currState == State::PAUSE) {
            printf("Received state PAUSE");
            paused = true;
            windowLock.notify();
        }
            
        if (currState == State::RESUME) {
            printf("Received state RESUME");
            paused = false;
            restoreState = true;
            windowLock.notify();
        }
        
        if (currState == State::CREATE_SURFACE) {
            printf("Received state CREATE_SURFACE");
            createRootWindowControl();
            hasSurface = true;
            windowLock.notify();
        }
            
        if (currState == State::CHANGE_SURFACE) {
            printf("Received state CHANGE_SURFACE");
            resizeRootWindow();
            surfaceChanged = true;
            windowLock.notify();
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
            windowLock.notify();
        }
        
        if (!windowQueue.empty() && hasSurface && surfaceChanged && !paused) {
            func = windowQueue.front();
            windowQueue.pop();
        }
        
        if (hasSurface && surfaceChanged && !paused) {
            if (!presentRR) {
                requests = presentRequests.getLast();
            }
            else {
                requests = presentRequests.buildWindowTree();
                requestUpdate = false;
            }
        }
        
        lock.unlock();
        
        if (func)
            func();
        
        auto completeContext = std::make_unique<OnCompleteContext>();
        
        while (!requests.empty()) {
            auto presentRequest = std::move(requests.front());
            requests.pop_front();
            
            auto window = presentRequest->window;
            if (!window || !window->control) 
                continue;
        
            auto drawable = presentRequest->drawable;
            if (!drawable) 
                continue;
        
            if (!window->enabled) {
                pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, nullptr, presentRequest->sync_fence);
            }
            else {
                if (presentRequest->colorSpace != UINT32_MAX)
                    pfnASurfaceTransactionSetBufferDataSpace(windowTransaction, window->control, colorSpaceToADataSpace(presentRequest->colorSpace));
                    
                if (effectComposer->isSuitableForColorSwap(drawable)) {
                    pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, drawable->composerTexture->dstBuffer, presentRequest->sync_fence);
                }
                else {
                    pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, drawable->ahb, presentRequest->sync_fence);
                }
                
                pfnASurfaceTransactionSetBufferTransparency(windowTransaction, window->control, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
                
                if (drawable->isDisplayX) {
                    if (presentRequest->presentId == UINT64_MAX && presentRequest->presentMode != VK_PRESENT_MODE_FIFO_KHR)
                        continue;
                        
                    auto it = completeContext->requests.find(presentRequest->swapchainId);
                    if (it != completeContext->requests.end()) {
                        auto& param = *it->second;
                        
                        if (presentRequest->presentId > param.presentId) {
                            param.presentId = presentRequest->presentId;
                        }
                        param.indexes.push_back(presentRequest->index);
                    }
                    else {
                        completeContext->clientFd = presentRequest->clientFd;
                        
                        auto param = std::make_unique<OnCompleteParams>();
                        param->presentId = presentRequest->presentId;
                        param->indexes.push_back(presentRequest->index);
                        param->clientFd = presentRequest->clientFd;
                        completeContext->requests[presentRequest->swapchainId] = std::move(param);
                    }
                }    
            }
        }
        
        if (!completeContext->isEmpty())
            pfnASurfaceTransactionSetOnComplete(windowTransaction, completeContext.release(), DisplayX::onCompleteCallback);
            
        pfnASurfaceTransactionApply(windowTransaction);
    }
}

void DisplayX::onCompleteCallback(void *context, ASurfaceTransactionStats *stats) {
    std::unique_ptr<OnCompleteContext> completeContext(static_cast<OnCompleteContext *>(context));
    
    uint32_t requestCode = DisplayXCodes::PRESENT_COMPLETE;
    char buf[1024];
    
    memcpy(buf, &requestCode, 4);
    
    uint32_t count = static_cast<uint32_t>(completeContext->requests.size());
    memcpy(buf + 4, &count, 4);
            
    uint32_t start = 8;      
      
    for (auto it = completeContext->requests.begin(); it != completeContext->requests.end(); it++) {
        memcpy(buf + start, &it->first, 1);
        start = start + 1;
        
        memcpy(buf + start, &it->second->presentId, 8);
        start = start + 8;
        
        auto count = static_cast<uint32_t>(it->second->indexes.size());
        memcpy(buf + start, &count, 4);
        start = start + 4;
        
        memcpy(buf + start, it->second->indexes.data(), count * sizeof(uint32_t));
        start = start + count * sizeof(uint32_t);
    }
    
    send(completeContext->clientFd, buf, start, 0);
}

void DisplayX::cursorThreadLoop() {
    this->env = cache->getEnv();
    
    while(true) {
        std::function<void()> func = nullptr;
        bool updateCursor = false;
        
        auto lock = cursorLock.lock();
        
        cursorLock.wait(lock, [&]{ 
            return stopped || ((cursorUpdate || !cursorQueue.empty()) && hasSurface && surfaceChanged && !paused);
        });
        
        if (stopped) {
            printf("Stopping cursorThread");
            return;
        }
        
        if (!cursorManager->control) 
            createRootCursorControl();
        
        if (!cursorQueue.empty()) {
            func = cursorQueue.front();
            cursorQueue.pop();
        }
        
        if (cursorUpdate) {
            updateCursor = true;
            cursorUpdate = false;
        }
        
        lock.unlock();
        
        if (func)
            func();
        
        if (updateCursor) {
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
            }
            else {
                pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            }
        }
        
        pfnASurfaceTransactionApply(cursorTransaction);
    }
}

void DisplayX::start() {
    void* handle = dlopen("libandroid.so", RTLD_NOW);
    int res;
    
    pfnASurfaceTransactionSetPosition = reinterpret_cast<PFNASURFACETRANSACTIONSETPOSITION>(dlsym(handle,"ASurfaceTransaction_setPosition"));
    pfnASurfaceTransactionSetBuffer = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFER>(dlsym(handle,"ASurfaceTransaction_setBuffer"));
    pfnASurfaceTransactionSetGeometry = reinterpret_cast<PFNASURFACETRANSACTIONSETGEOMETRY>(dlsym(handle,"ASurfaceTransaction_setGeometry"));
    pfnASurfaceTransactionSetZOrder = reinterpret_cast<PFNASURFACETRANSACTIONSETZORDER>(dlsym(handle,"ASurfaceTransaction_setZOrder"));
    pfnASurfaceTransactionSetVisibility = reinterpret_cast<PFNASURFACETRANSACTIONSETVISIBILITY>(dlsym(handle,"ASurfaceTransaction_setVisibility"));
    pfnASurfaceTransactionReparent = reinterpret_cast<PFNASURFACETRANSACTIONREPARENT>(dlsym(handle,"ASurfaceTransaction_reparent"));
    pfnASurfaceTransactionSetOnComplete = reinterpret_cast<PFNASURFACETRANSACTIONSETONCOMPLETE>(dlsym(handle,"ASurfaceTransaction_setOnComplete"));
    pfnASurfaceTransactionSetOnCommit = reinterpret_cast<PFNASURFACETRANSACTIONSETONCOMMIT>(dlsym(handle,"ASurfaceTransaction_setOnCommit"));
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
    
    serverFd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (serverFd < 0) {
        printf("Failed to create native rendering socket");
        return;
    }    
                
    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, "displayx", strlen("displayx"));
    addr.sun_path[0] = '\0';
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen("displayx");
    res = bind(serverFd, (struct sockaddr *)&addr, len);
    if (res < 0) {
        printf("Failed to bind native rendering socket");
        close(serverFd);
        return;
    }    
               
    res = listen(serverFd, 1);
    if (res < 0) {
        printf("Failed to listent to native rendering socket");
        close(serverFd);
        return;
    }    
                
    epollFd = epoll_create1(0);
    stopFd = eventfd(0, EFD_NONBLOCK);
    
    struct epoll_event event{};
    
    event.data.fd = serverFd;
    event.events = EPOLLIN;
    epoll_ctl(epollFd, EPOLL_CTL_ADD, serverFd, &event);
     
    event.data.fd = stopFd;
    event.events = EPOLLIN;
    epoll_ctl(epollFd, EPOLL_CTL_ADD, stopFd, &event);
    
    windowTransaction = pfnASurfaceTransactionCreate();
    cursorTransaction = pfnASurfaceTransactionCreate();
        
    windowThread = std::thread(&DisplayX::windowThreadLoop, this);
    networkThread = std::thread(&DisplayX::networkThreadLoop, this);
    cursorThread = std::thread(&DisplayX::cursorThreadLoop, this);
   
    this->choreographer = pfnAChoreographerGetInstance();
    
    if (pfnAChoreographerPostVsyncCallback)
        pfnAChoreographerPostVsyncCallback(this->choreographer, DisplayX::onVsyncCallback, this);
    else     
        pfnAChoreographerPostFrameCallback64(this->choreographer, DisplayX::onFrameCallback64, this);
}

void DisplayX::stop() {
    if (isPerformanceHintAPIAvailable() && performanceHintSession) {
        pfnAPerformanceHintCloseSession(performanceHintSession);
    }
    
    stopped = true;
    int val = 1;
    write(stopFd, &val, 4);
    
    windowLock.notify();
    cursorLock.notify();
    
    close(stopFd);   
    close(epollFd);
    close(serverFd);
    
    pfnASurfaceTransactionDelete(windowTransaction);
    pfnASurfaceTransactionDelete(cursorTransaction);
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
    presentRequest->index = -1;
    
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
    if (!window->parent || !window->inputOutput) 
        return;
   
    window->control = pfnASurfaceControlCreate(window->parent->control, "displayx");
    if (pfnASurfaceControlAcquire)    
        pfnASurfaceControlAcquire(window->control);
    
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

void DisplayX::destroyWindowControl(Window *window) {
    if (!window || !window->control)
        return;
    
    presentRequests.removeWindow(window);
    pfnASurfaceControlRelease(window->control);
    window->control = nullptr;
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
    
    for (const auto& entry : windowTree) {
        auto window = entry.second.get();
        if (window == rootWindow)
            continue;
            
        if (!window->control || !window->parent->control)
            continue;
        
        pfnASurfaceTransactionReparent(windowTransaction, window->control, window->parent->control);
    }
    
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
        send(it->second->fd, buf, 5, 0);
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