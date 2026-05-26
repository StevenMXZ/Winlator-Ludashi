/*
 * AHB Direct Compositing - Vulkan Implicit Layer
 *
 * This is a proper Vulkan layer that intercepts swapchain operations to
 * redirect rendering through AHardwareBuffers for direct compositing.
 *
 * Unlike the ICD wrapper approach, a layer is guaranteed to be invoked
 * by the Vulkan loader regardless of Box64 thunk routing.
 *
 * Copyright 2024 Winlator contributors
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include "../wineandroid.drv/vulkan_ahb.h"

#define LOG_TAG "AHB_LAYER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ========================================================================
 * Per-instance and per-device dispatch tables
 * ======================================================================== */

typedef struct {
    PFN_vkGetInstanceProcAddr         GetInstanceProcAddr;
    PFN_vkDestroyInstance             DestroyInstance;
    PFN_vkEnumeratePhysicalDevices    EnumeratePhysicalDevices;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkCreateDevice                CreateDevice;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR      GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
} InstanceDispatch;

typedef struct {
    PFN_vkGetDeviceProcAddr       GetDeviceProcAddr;
    PFN_vkDestroyDevice           DestroyDevice;
    PFN_vkCreateSwapchainKHR      CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR     DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR   GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR     AcquireNextImageKHR;
    PFN_vkQueuePresentKHR         QueuePresentKHR;
    PFN_vkQueueSubmit             QueueSubmit;
    PFN_vkCreateCommandPool       CreateCommandPool;
    PFN_vkAllocateCommandBuffers  AllocateCommandBuffers;
    PFN_vkBeginCommandBuffer      BeginCommandBuffer;
    PFN_vkEndCommandBuffer        EndCommandBuffer;
    PFN_vkCmdCopyImage            CmdCopyImage;
    PFN_vkCmdBlitImage            CmdBlitImage;
    PFN_vkCmdPipelineBarrier      CmdPipelineBarrier;
    PFN_vkCreateImage             CreateImage;  /* For transient-attachment promotion. */
    PFN_vkCreateFence             CreateFence;
    PFN_vkDestroyFence            DestroyFence;
    PFN_vkWaitForFences           WaitForFences;
    PFN_vkResetFences             ResetFences;
    PFN_vkResetCommandBuffer      ResetCommandBuffer;
    PFN_vkDestroyCommandPool      DestroyCommandPool;
    PFN_vkQueueWaitIdle           QueueWaitIdle;
    /* VK_KHR_present_wait — DXVK uses this for FIFO frame pacing. We must
     * intercept it because we bypass the real vkQueuePresentKHR; without
     * interception, DXVK would wait forever for a present_id that the trojan
     * swapchain never signals. Loaded from vkGetDeviceProcAddr (may be NULL
     * on devices without the extension). */
    PFN_vkWaitForPresentKHR       WaitForPresentKHR;
} DeviceDispatch;

/* Simple single-instance/device support (sufficient for Wine/Box64) */
static InstanceDispatch g_inst_dispatch;
static DeviceDispatch   g_dev_dispatch;
static VkInstance       g_instance    = VK_NULL_HANDLE;
static VkDevice         g_device      = VK_NULL_HANDLE;
static VkPhysicalDevice g_phys_device = VK_NULL_HANDLE;

/* AHB state */
static int              g_ahb_socket_fd    = -1;
static int              g_ahb_active       = 0;
static int              g_ahb_connected    = 0;
static AHardwareBuffer *g_ahb_buffers[AHB_MAX_IMAGES] = {NULL, NULL, NULL, NULL};
static int              g_ahb_buffer_count = 0;
static struct wine_vk_swapchain *g_ahb_swapchain = NULL;
static VkSwapchainKHR            g_real_swapchain_handle = VK_NULL_HANDLE;
static VkImage                   g_trojan_images[AHB_MAX_IMAGES] = {0};
static uint32_t                  g_trojan_image_count = 0;
static VkCommandPool             g_copy_cmd_pool = VK_NULL_HANDLE;
static VkCommandBuffer           g_copy_cmd_bufs[AHB_MAX_IMAGES] = {VK_NULL_HANDLE};
static VkFence                   g_copy_fences[AHB_MAX_IMAGES] = {VK_NULL_HANDLE};
static VkQueue                   g_saved_queue = VK_NULL_HANDLE;

/* === DIRECT-RENDER MODE (Path 3) ===
 *
 * When enabled (env var WINLATOR_AHB_DIRECT_RENDER=1), DXVK renders DIRECTLY
 * into the AHB-backed VkImages. The trojan swapchain + per-slot blit are
 * skipped entirely. QueuePresentKHR submits a "fence-only signal" job (zero
 * commands; waits on DXVK's render-complete semaphores, signals the per-slot
 * fence) so we still get a SYNC_FD to ship to SurfaceFlinger as the acquire
 * fence.
 *
 * Saved per frame: vkCmdBlitImage + 4 image-memory barriers + one
 * BeginCommandBuffer/EndCommandBuffer + the trojan image allocation. The
 * AHB write happens during DXVK's own draws (zero-copy from there to
 * SurfaceFlinger).
 *
 * Defaults OFF — the trojan-blit path is the safe known-good. Toggle on
 * after verifying. Set once at layer init by reading the env var. */
static bool g_direct_render_mode = false;

/* Per-slot mailbox state — true when the slot is available for DXVK to render.
 * Slots transition free→busy on acquire and busy→free on MSG_RELEASE recv. */
static bool g_slot_free[AHB_MAX_IMAGES] = { true, true, true, true };
/* DXVK's requested present mode, captured at swapchain creation. FIFO games
 * (Unity, most non-action titles) need per-frame release waits to mimic the
 * vsync-blocking acquire contract DXVK depends on. Without it the layer's
 * return-immediately acquire breaks DXVK's render pacing model and games hang
 * after a few frames (Vampire Survivors, Hollow Knight, GTA4). */
static VkPresentModeKHR g_present_mode_requested = VK_PRESENT_MODE_FIFO_KHR;
/* Recently-presented slot history (2-deep) to bias acquire away from buffers
 * SurfaceFlinger may still be scanning out. */
static int  g_presented_ring[2] = { -1, -1 };
static int  g_presented_idx = 0;

/* === REAL vkWaitForPresentKHR support ===
 *
 * DXVK passes a VkPresentIdKHR in pNext on vkQueuePresentKHR. Each present_id
 * is monotonically increasing. DXVK then calls vkWaitForPresentKHR(N) to block
 * until present-N is "observable on display."
 *
 * Our pipeline: present_id N is sent to Android in slot S. SurfaceFlinger's
 * onComplete eventually fires for that buffer; Android sends MSG_RELEASE for
 * slot S. That release is our "present N is done on display" signal.
 *
 * Tracking: when QueuePresentKHR records (present_id, slot), we add an entry
 * to g_present_records. A dedicated release-reader thread consumes MSG_RELEASE
 * from the socket and marks the matching entry complete, then signals the cv.
 *
 * Splitting the socket reader into its own thread (instead of having
 * AcquireNextImageKHR read it inline) means both AcquireNextImageKHR and
 * WaitForPresentKHR can wait on the same cv without racing on socket reads. */

#include <pthread.h>
#include <time.h>
#include <errno.h>

struct present_record {
    uint64_t present_id;
    uint32_t slot;
    bool     completed;
};

#define PRESENT_RECORD_RING 128
static struct present_record g_present_records[PRESENT_RECORD_RING] = {{0}};
static uint32_t g_present_record_head = 0;  /* next write index */
/* highest present_id known to be completed (release received) */
static uint64_t g_max_completed_present_id = 0;
/* === Display-tick counter for vkWaitForPresentKHR pacing ===
 * Incremented ONLY when an onComplete-driven MSG_RELEASE arrives
 * (displayed=1). Mailbox-drain releases (displayed=0) don't advance it.
 * Each call to vkWaitForPresentKHR returns when display_count moves at
 * least 1 tick past the value it captured on entry — i.e., exactly one
 * real vsync. */
static uint64_t g_display_count = 0;

/* === PHASE-ANCHORED VSYNC TIMESTAMP ===
 * Updated on every MSG_VSYNC from the Android receiver — i.e. once per
 * AChoreographer callback, i.e. once per real panel vsync independent of
 * our present cadence. WFP uses this as a PHASE REFERENCE for
 * clock_nanosleep TIMER_ABSTIME: the wake target is computed as
 *   target = g_last_hardware_vsync_us + N * 16667us  (smallest N s.t. > now)
 * and the kernel sleeps until that absolute monotonic time. This decouples:
 *   - the IPC delivery path (which has thread-hop variance) from
 *   - the actual wake (which is kernel-scheduler precise).
 * The IPC just keeps the clock synchronized; nanosleep does the wake.
 *
 * The counter (g_vsync_count) is kept for diagnostics — it's never used
 * to gate WFP because cond-var-on-MSG_VSYNC adds the same multi-hop
 * variance that we are trying to escape.
 *
 * Both fields read/written under g_release_mtx (cheap; uncontended). */
static uint64_t g_vsync_count = 0;
static uint64_t g_last_hardware_vsync_us = 0;
/* Measured panel vsync period in µs. Updated each MSG_VSYNC from the
 * arrival-delta EMA. Replaces the previous hardcoded 16667 µs (60 Hz)
 * which was capping DAC at 60 FPS on 90/120/144 Hz panels. 0 until the
 * second MSG_VSYNC arrives — callers fall back to the 60 Hz default. */
/* Exposed via extern in VulkanRendererContext.cpp for adaptive setFrameRate. */
uint64_t g_vsync_period_us_measured = 0;

static pthread_mutex_t g_release_mtx = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_release_cv  = PTHREAD_COND_INITIALIZER;
static pthread_t g_release_thread;
static bool g_release_thread_running = false;

/* === FRAME PACING (Pillar 2 — Swappy-style predictor) ===
 *
 * Problem: DXVK render time is bimodal — most frames finish in time for the
 * next vsync, some don't. Without pacing, the user sees motion alternating
 * between 60 FPS smooth and 30 FPS chop.
 *
 * Solution: track recent render times in a rolling window. On each
 * vkWaitForPresentKHR, predict the next render time (p90 — robust to
 * outliers), divide by the self-discovered vsync period, and wait that
 * many MSG_TICK events before returning. Result: every frame interval is
 * exactly a multiple of vsync. No bimodality.
 *
 * Hysteresis: only allow stepping DOWN to a lower interval count if the
 * predicted render time is comfortably below the lower bucket (85% of
 * one bucket). Prevents single-frame oscillation. All state guarded by
 * g_release_mtx so reads in WaitForPresent are consistent. */
#define FP_WINDOW 16
/* Skip the first N samples — startup is dominated by shader compile / asset
 * load hitches that don't represent steady-state render time and poison
 * any percentile prediction. */
#define FP_WARMUP_FRAMES 5

/* Rolling window of CPU render times (acquire → present, microseconds). */
static uint64_t g_fp_render_times[FP_WINDOW] = {0};
static uint32_t g_fp_render_head  = 0;
static uint32_t g_fp_render_count = 0;
/* Counts samples we've SEEN (including warmup-skipped ones) so we can skip
 * the first N without affecting the recorded window. */
static uint32_t g_fp_render_seen  = 0;
/* Set in acquire; consumed (and reset to 0) in present. */
static uint64_t g_fp_last_acquire_us = 0;

/* Rolling window of MSG_TICK inter-arrival deltas → self-discovered vsync
 * period. Defaults to 60 Hz (16.67 ms) until we have enough samples. */
static uint64_t g_fp_tick_deltas[FP_WINDOW] = {0};
static uint32_t g_fp_tick_head    = 0;
static uint32_t g_fp_tick_count   = 0;
static uint64_t g_fp_last_tick_for_period_us = 0;
static uint64_t g_fp_vsync_period_us = 16667;

/* For time-based pacing (separate from tick-based natural pacing) */
static uint64_t g_fp_last_wait_return_us = 0;

/* Helpers — small enough for bubble sort to dominate cache lines.
 *
 * Note on choice of statistic: Swappy uses p90 (pessimistic). That's
 * theoretically right because UNDER-estimating render time causes vsync
 * misses, which manifest as jitter. The catch in our setting is that
 * startup hitches (shader compile, asset load) poison the window with
 * 50–200ms samples that take >FP_WINDOW frames to age out. p90 captures
 * exactly those poisoning values and locks us into clamp-4 pacing.
 *
 * We use p50 (median) instead — robust to single-sample outliers — and
 * combine it with a small upward bias (15%) for safety margin. With the
 * warmup-skip + outlier rejection at record time, the window contains
 * only steady-state render times. Median over that is the right answer. */
static uint64_t fp_percentile_render_locked(uint32_t pct) {
    (void)pct;  /* kept in signature for API stability — we now always use p50 */
    uint32_t n = g_fp_render_count < FP_WINDOW ? g_fp_render_count : FP_WINDOW;
    if (n < 4) return 16667;  /* insufficient data: assume 60 Hz */
    uint64_t copy[FP_WINDOW];
    for (uint32_t i = 0; i < n; i++) copy[i] = g_fp_render_times[i];
    for (uint32_t i = 0; i + 1 < n; i++) {
        for (uint32_t j = i + 1; j < n; j++) {
            if (copy[i] > copy[j]) { uint64_t t = copy[i]; copy[i] = copy[j]; copy[j] = t; }
        }
    }
    /* p50 with 15% upward bias for safety margin. */
    uint64_t median = copy[n / 2];
    return (median * 115) / 100;
}
static uint64_t fp_median_tick_locked(void) {
    uint32_t n = g_fp_tick_count < FP_WINDOW ? g_fp_tick_count : FP_WINDOW;
    if (n < 4) return 16667;
    uint64_t copy[FP_WINDOW];
    for (uint32_t i = 0; i < n; i++) copy[i] = g_fp_tick_deltas[i];
    for (uint32_t i = 0; i + 1 < n; i++) {
        for (uint32_t j = i + 1; j < n; j++) {
            if (copy[i] > copy[j]) { uint64_t t = copy[i]; copy[i] = copy[j]; copy[j] = t; }
        }
    }
    return copy[n / 2];
}

/* === JITTER TRACE ===
 * Per-stage inter-event delta logging. Each tracer keeps a previous-timestamp
 * and logs the delta in microseconds when an event fires. Use:
 *     adb logcat -d | grep JITTER_TRACE > trace.txt
 * Then awk or load into a spreadsheet to compare variance across stages.
 *
 * Stages on the layer side:
 *   stage=present     — DXVK called vkQueuePresentKHR (one per game frame)
 *   stage=wait_return — vkWaitForPresentKHR unblocked
 *   stage=wait_block  — vkWaitForPresentKHR was called; reports how long
 *                       it blocked before returning (separately from delta)
 *   stage=tick        — release-reader thread observed MSG_TICK
 * The Android side adds: recv, apply, onCommit. */
static inline uint64_t mono_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)ts.tv_nsec / 1000ULL;
}
#define JTRACE(stage_str, prev_var_ptr, fmt, ...) do {                       \
    uint64_t _now = mono_us();                                               \
    uint64_t _prev = *(prev_var_ptr);                                        \
    *(prev_var_ptr) = _now;                                                  \
    if (_prev) {                                                             \
        uint64_t _d = _now - _prev;                                          \
        LOGI("JITTER_TRACE: stage=%s delta_us=%llu " fmt,                    \
             (stage_str), (unsigned long long)_d, ##__VA_ARGS__);            \
    }                                                                        \
} while (0)

/* ========================================================================
 * Layer chaining helper
 * ======================================================================== */

static inline VkLayerInstanceCreateInfo *get_instance_chain_info(
    const VkInstanceCreateInfo *pCreateInfo, VkLayerFunction func)
{
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)pCreateInfo->pNext;
    while (chain) {
        if (chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
            chain->function == func)
            return chain;
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    }
    return NULL;
}

static inline VkLayerDeviceCreateInfo *get_device_chain_info(
    const VkDeviceCreateInfo *pCreateInfo, VkLayerFunction func)
{
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)pCreateInfo->pNext;
    while (chain) {
        if (chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
            chain->function == func)
            return chain;
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    }
    return NULL;
}

/* ========================================================================
 * AHB socket connection
 * ======================================================================== */

/* === Release-reader thread ===
 *
 * Single owner of recv() on the AHB socket. Reads MSG_RELEASE messages and:
 *   1. Marks the released slot free (for layer_AcquireNextImageKHR).
 *   2. Marks any pending present_record for that slot completed and bumps
 *      g_max_completed_present_id (for layer_WaitForPresentKHR).
 *   3. Signals g_release_cv so any waiting thread wakes up.
 *
 * Replaces the previous "AcquireNextImageKHR calls recv() inline" model,
 * which fought with WaitForPresentKHR for the socket. */
static void *release_reader_thread(void *arg) {
    (void)arg;
    LOGI("release_reader_thread: started");
    while (g_release_thread_running) {
        struct release_msg rel;
        ssize_t n = recv(g_ahb_socket_fd, &rel, sizeof(rel), 0);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            LOGW("release_reader_thread: recv ended (n=%zd: %s); exiting", n, strerror(errno));
            break;
        }
        if (n != (ssize_t)sizeof(rel)) continue;

        /* MSG_VSYNC: from Android-side AChoreographer_postFrameCallback,
         * sent once per real panel vsync independent of our present cadence.
         * Stamps g_last_hardware_vsync_us so WFP's clock_nanosleep TIMER_ABSTIME
         * has a phase reference.
         *
         * Anchor choice: we use recv-time (mono_us() at the moment we process
         * the message). The IPC adds ~500 µs of mostly-stable latency, and
         * EMPIRICALLY that gives the smoothest pacing on the Odin 2 Portal —
         * waking exactly at the panel vsync (via the shipped frameTimeNanos)
         * landed our subsequent ASurfaceTransaction apply too close to
         * SurfaceFlinger's swap deadline, causing some frames to slip a
         * vsync. The IPC delay was an accidental but useful headroom. The
         * MSG_VSYNC payload still ships vsync_time_ns for future use but
         * we don't read it for pacing. */
        if (rel.type == MSG_VSYNC) {
            uint64_t recv_us = mono_us();
            pthread_mutex_lock(&g_release_mtx);
            uint64_t prev = g_last_hardware_vsync_us;
            g_vsync_count++;
            g_last_hardware_vsync_us = recv_us;
            /* Measure the panel's actual vsync period from MSG_VSYNC arrival
             * deltas. We used to hardcode 16667 µs (60 Hz), which on 90/120/144 Hz
             * panels caused WaitForPresent's clock_nanosleep target to land at
             * 60 Hz boundaries — effectively capping DAC at 60 FPS even on
             * faster displays. The IPC delivery is jittery enough that a
             * single delta is noisy; smooth with a heavy EMA so transient
             * scheduler hiccups don't push the period around. Clamp to
             * sane bounds (4-25 ms = 240 Hz down to 40 Hz) to ignore the
             * occasional dropped-vsync outlier. */
            if (prev != 0) {
                uint64_t delta = recv_us - prev;
                if (delta >= 4000ULL && delta <= 25000ULL) {
                    uint64_t cur = g_vsync_period_us_measured;
                    g_vsync_period_us_measured = (cur == 0)
                        ? delta
                        : (cur * 15 + delta) / 16;  /* 1/16 weight ≈ ~1 sec smoothing */
                }
            }
            pthread_mutex_unlock(&g_release_mtx);
            static uint64_t s_vsync_prev = 0;
            JTRACE("vsync", &s_vsync_prev, "count=%llu",
                   (unsigned long long)g_vsync_count);
            continue;
        }

        /* MSG_TICK: from Android-side setOnCommit, sent once per real vsync.
         * Drives g_display_count (the pacing signal for vkWaitForPresentKHR).
         * Slot-freeing happens separately via MSG_RELEASE. */
        if (rel.type == MSG_TICK) {
            static uint64_t s_tick_prev = 0;
            JTRACE("tick", &s_tick_prev, "count=%llu",
                   (unsigned long long)(g_display_count + 1));
            uint64_t now = mono_us();
            pthread_mutex_lock(&g_release_mtx);
            /* Track inter-tick interval for self-discovered vsync period.
             * Filter outliers (<2ms or >50ms) to keep the median stable. */
            if (g_fp_last_tick_for_period_us != 0) {
                uint64_t d = now - g_fp_last_tick_for_period_us;
                if (d >= 2000 && d <= 50000) {
                    g_fp_tick_deltas[g_fp_tick_head] = d;
                    g_fp_tick_head = (g_fp_tick_head + 1) % FP_WINDOW;
                    if (g_fp_tick_count < FP_WINDOW) g_fp_tick_count++;
                    g_fp_vsync_period_us = fp_median_tick_locked();
                }
            }
            g_fp_last_tick_for_period_us = now;
            g_display_count++;
            pthread_cond_broadcast(&g_release_cv);
            pthread_mutex_unlock(&g_release_mtx);
            continue;
        }

        if (rel.type != MSG_RELEASE) continue;
        if (rel.slot_index >= AHB_MAX_IMAGES) continue;

        pthread_mutex_lock(&g_release_mtx);
        g_slot_free[rel.slot_index] = true;

        /* Fallback path: if Android-side has no setOnCommit (Android < 12),
         * onComplete-driven MSG_RELEASE arrives with displayed=1 to act as
         * the tick. With setOnCommit available, MSG_RELEASE always uses
         * displayed=0 (slot freeing only) so we don't double-count. */
        if (rel.displayed) {
            g_display_count++;
        }

        pthread_cond_broadcast(&g_release_cv);
        pthread_mutex_unlock(&g_release_mtx);
    }
    LOGI("release_reader_thread: exiting");
    return NULL;
}

static void connect_ahb(void)
{
    if (g_ahb_connected) return;
    g_ahb_connected = 1;

    const char *server_path = getenv("ANDROID_AHB_SERVER");
    if (!server_path || server_path[0] == '\0') {
        LOGI("connect_ahb: ANDROID_AHB_SERVER not set, passthrough mode");
        return;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { LOGE("connect_ahb: socket() failed: %s", strerror(errno)); return; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, server_path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("connect_ahb: connect(%s) failed: %s", server_path, strerror(errno));
        close(fd);
        return;
    }

    g_ahb_socket_fd = fd;
    LOGI("connect_ahb: connected to %s (fd=%d)", server_path, fd);

    for (int i = 0; i < AHB_MAX_IMAGES; i++) {
        AHardwareBuffer *ahb = NULL;
        if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb) != 0 || !ahb) {
            LOGE("connect_ahb: failed to receive AHB %d", i);
            close(fd);
            g_ahb_socket_fd = -1;
            return;
        }
        g_ahb_buffers[i] = ahb;
        g_ahb_buffer_count++;
        LOGI("connect_ahb: received AHB handle %d", i);
    }

    g_ahb_active = 1;
    LOGI("connect_ahb: DIRECT PATH ACTIVE — %d buffers ready", g_ahb_buffer_count);

    /* Start the release-reader thread now that the socket is set up and the
     * initial AHB handshake (buffer handles) has been consumed. From this
     * point on, only this thread reads MSG_RELEASE from the socket. */
    g_release_thread_running = true;
    if (pthread_create(&g_release_thread, NULL, release_reader_thread, NULL) != 0) {
        LOGE("connect_ahb: failed to start release_reader_thread");
        g_release_thread_running = false;
    }
}

/* ========================================================================
 * Intercepted instance-level functions (surface queries)
 * ======================================================================== */

static VKAPI_ATTR VkResult VKAPI_CALL layer_GetPhysicalDeviceSurfaceCapabilitiesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
    VkSurfaceCapabilitiesKHR *pSurfaceCapabilities)
{
    if (!g_ahb_active) {
        return g_inst_dispatch.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, pSurfaceCapabilities);
    }

    /* Return capabilities that exactly match our AHB swapchain setup */
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(g_ahb_buffers[0], &desc);

    pSurfaceCapabilities->minImageCount = g_ahb_buffer_count;
    pSurfaceCapabilities->maxImageCount = g_ahb_buffer_count;
    pSurfaceCapabilities->currentExtent.width  = desc.width;
    pSurfaceCapabilities->currentExtent.height = desc.height;
    pSurfaceCapabilities->minImageExtent.width  = desc.width;
    pSurfaceCapabilities->minImageExtent.height = desc.height;
    pSurfaceCapabilities->maxImageExtent.width  = desc.width;
    pSurfaceCapabilities->maxImageExtent.height = desc.height;
    pSurfaceCapabilities->maxImageArrayLayers = 1;
    pSurfaceCapabilities->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    pSurfaceCapabilities->currentTransform    = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    pSurfaceCapabilities->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    pSurfaceCapabilities->supportedUsageFlags =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT;

    LOGI("layer_GetPhysicalDeviceSurfaceCapabilitiesKHR: returning %ux%u, count=%u",
         desc.width, desc.height, g_ahb_buffer_count);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_GetPhysicalDeviceSurfaceFormatsKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
    uint32_t *pSurfaceFormatCount, VkSurfaceFormatKHR *pSurfaceFormats)
{
    if (!g_ahb_active) {
        return g_inst_dispatch.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pSurfaceFormatCount, pSurfaceFormats);
    }

    /* Advertise BGRA first in both modes. DXVK's preferred swapchain
     * format is B8G8R8A8_UNORM (matches DXGI_FORMAT_B8G8R8A8_UNORM, the
     * standard DirectX swapchain format) and DXVK's own caching often
     * bypasses our format restriction anyway. By aligning the AHB pool's
     * HAL format to BGRA (P2 change), DXVK's preferred format also
     * matches the AHB byte layout — no channel swap needed end-to-end. */
    static const VkSurfaceFormatKHR formats[] = {
        { VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
        { VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
        { VK_FORMAT_B8G8R8A8_SRGB,  VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
        { VK_FORMAT_R8G8B8A8_SRGB,  VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
    };
    uint32_t count = (uint32_t)(sizeof(formats) / sizeof(formats[0]));

    if (!pSurfaceFormats) {
        *pSurfaceFormatCount = count;
        return VK_SUCCESS;
    }

    uint32_t to_copy = *pSurfaceFormatCount < count ? *pSurfaceFormatCount : count;
    memcpy(pSurfaceFormats, formats, to_copy * sizeof(VkSurfaceFormatKHR));
    *pSurfaceFormatCount = to_copy;

    LOGI("layer_GetPhysicalDeviceSurfaceFormatsKHR: returning %u formats", to_copy);
    return (to_copy < count) ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_GetPhysicalDeviceSurfacePresentModesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
    uint32_t *pPresentModeCount, VkPresentModeKHR *pPresentModes)
{
    if (!g_ahb_active) {
        return g_inst_dispatch.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, pPresentModes);
    }

    /* Support FIFO (always available) and MAILBOX (for low-latency) */
    static const VkPresentModeKHR modes[] = {
        VK_PRESENT_MODE_FIFO_KHR,
        VK_PRESENT_MODE_MAILBOX_KHR,
        VK_PRESENT_MODE_IMMEDIATE_KHR,
    };
    uint32_t count = sizeof(modes) / sizeof(modes[0]);

    if (!pPresentModes) {
        *pPresentModeCount = count;
        return VK_SUCCESS;
    }

    uint32_t to_copy = *pPresentModeCount < count ? *pPresentModeCount : count;
    memcpy(pPresentModes, modes, to_copy * sizeof(VkPresentModeKHR));
    *pPresentModeCount = to_copy;

    LOGI("layer_GetPhysicalDeviceSurfacePresentModesKHR: returning %u modes", to_copy);
    return (to_copy < count) ? VK_INCOMPLETE : VK_SUCCESS;
}

/* ========================================================================
 * Intercepted device-level functions
 * ======================================================================== */

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateSwapchainKHR(
    VkDevice device,
    const VkSwapchainCreateInfoKHR *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkSwapchainKHR *pSwapchain)
{
    if (!g_ahb_active || g_ahb_buffer_count == 0) {
        return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
    }

    LOGI("layer_CreateSwapchainKHR: request %dx%d fmt=%d mode=%d minCount=%u oldSwapchain=%p",
         pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height,
         pCreateInfo->imageFormat, pCreateInfo->presentMode,
         pCreateInfo->minImageCount, (void*)(uintptr_t)pCreateInfo->oldSwapchain);

    /* Handle oldSwapchain: if it's our AHB swapchain, destroy it first */
    if (pCreateInfo->oldSwapchain != VK_NULL_HANDLE &&
        g_ahb_swapchain &&
        g_real_swapchain_handle == pCreateInfo->oldSwapchain) {
        LOGI("layer_CreateSwapchainKHR: retiring old AHB swapchain");
        wine_ahb_destroy_swapchain(g_ahb_swapchain);
        g_ahb_swapchain = NULL;
        /* Don't destroy the real swapchain here; pass it as oldSwapchain below */
    }

    /* === THE SNIPER HOOK ===
     * Only intercept swapchains that EXACTLY match the AHB pool dimensions.
     * All other swapchains (DXVK probing, UI overlays, different resolutions)
     * pass through to the real Turnip driver. This lets DXVK's format/mode
     * probing phase succeed normally against the real driver, and we only
     * hijack the final gameplay swapchain. */
    AHardwareBuffer_Desc ahb_desc;
    AHardwareBuffer_describe(g_ahb_buffers[0], &ahb_desc);
    if (pCreateInfo->imageExtent.width != ahb_desc.width ||
        pCreateInfo->imageExtent.height != ahb_desc.height) {
        LOGI("layer_CreateSwapchainKHR: PASSTHROUGH (%dx%d != AHB %dx%d)",
             pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height,
             ahb_desc.width, ahb_desc.height);
        return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
    }

    /* === DIAGNOSTIC TOGGLE ===
     * WINLATOR_AHB_FIFO_PASSTHROUGH=1 makes us decline to intercept FIFO-mode
     * swapchains. FIFO has a stricter "acquire blocks until vsync" contract
     * that our return-immediately + mailbox-style acquire doesn't honor. If
     * this toggle unsticks games (Vampire Survivors, Hollow Knight, GTA4),
     * the FIFO-vs-mailbox semantic mismatch is the root cause.
     * Set WINLATOR_AHB_NO_INTERCEPT=1 to disable interception entirely (pure
     * X11 passthrough — equivalent to running with no layer). */
    {
        const char *env_no_intercept = getenv("WINLATOR_AHB_NO_INTERCEPT");
        if (env_no_intercept && env_no_intercept[0] == '1') {
            LOGI("layer_CreateSwapchainKHR: WINLATOR_AHB_NO_INTERCEPT=1 — declining to intercept (X11 passthrough)");
            return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
        }
        const char *env_fifo_pt = getenv("WINLATOR_AHB_FIFO_PASSTHROUGH");
        if (env_fifo_pt && env_fifo_pt[0] == '1'
            && pCreateInfo->presentMode == VK_PRESENT_MODE_FIFO_KHR) {
            LOGI("layer_CreateSwapchainKHR: FIFO mode + FIFO_PASSTHROUGH=1 — declining to intercept");
            return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
        }
    }

    LOGI("layer_CreateSwapchainKHR: SNIPER HOOK! (%dx%d matches AHB pool, mode=%d) — intercepting",
         pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height,
         (int)pCreateInfo->presentMode);

    struct wine_vk_swapchain *sc = calloc(1, sizeof(*sc));
    if (!sc) return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);

    static struct wine_vk_surface fake_surface;
    fake_surface.socket_fd    = g_ahb_socket_fd;
    fake_surface.width        = (int)pCreateInfo->imageExtent.width;
    fake_surface.height       = (int)pCreateInfo->imageExtent.height;
    fake_surface.ahb_supported = true;

    sc->surface      = &fake_surface;
    sc->device       = device;
    sc->image_count  = (g_ahb_buffer_count < AHB_MAX_IMAGES) ? g_ahb_buffer_count : AHB_MAX_IMAGES;
    sc->current_image = 0;

    /* Resolve device function pointers from the next layer/driver */
    PFN_vkGetDeviceProcAddr gdpa = g_dev_dispatch.GetDeviceProcAddr;
    sc->pfn_vkGetFenceFdKHR    = (PFN_vkGetFenceFdKHR)   gdpa(device, "vkGetFenceFdKHR");
    sc->pfn_vkImportFenceFdKHR = (PFN_vkImportFenceFdKHR)gdpa(device, "vkImportFenceFdKHR");
    sc->pfn_vkCreateFence      = (PFN_vkCreateFence)      gdpa(device, "vkCreateFence");
    sc->pfn_vkDestroyFence     = (PFN_vkDestroyFence)     gdpa(device, "vkDestroyFence");
    sc->pfn_vkWaitForFences    = (PFN_vkWaitForFences)    gdpa(device, "vkWaitForFences");
    sc->pfn_vkResetFences      = (PFN_vkResetFences)      gdpa(device, "vkResetFences");
    sc->pfn_vkAllocateMemory   = (PFN_vkAllocateMemory)   gdpa(device, "vkAllocateMemory");
    sc->pfn_vkFreeMemory       = (PFN_vkFreeMemory)       gdpa(device, "vkFreeMemory");
    sc->pfn_vkCreateImage      = (PFN_vkCreateImage)      gdpa(device, "vkCreateImage");
    sc->pfn_vkDestroyImage     = (PFN_vkDestroyImage)     gdpa(device, "vkDestroyImage");
    sc->pfn_vkBindImageMemory2 = (PFN_vkBindImageMemory2) gdpa(device, "vkBindImageMemory2");
    sc->pfn_vkGetDeviceProcAddr = gdpa;

    /* Create reuse fences */
    VkFenceCreateInfo fi = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                             .flags = VK_FENCE_CREATE_SIGNALED_BIT };
    for (uint32_t i = 0; i < sc->image_count; i++) {
        if (sc->pfn_vkCreateFence(device, &fi, NULL, &sc->images[i].reuse_fence) != VK_SUCCESS) {
            LOGE("layer_CreateSwapchainKHR: vkCreateFence failed slot %u", i);
            free(sc);
            return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
        }
        sc->images[i].release_fence = -1;
        sc->images[i].in_use = false;
    }

    /* Import the AHB into Vulkan as B8G8R8A8_UNORM — matches the pool's
     * HAL_PIXEL_FORMAT_BGRA_8888 backing (see AHardwareBufferPool.java).
     *
     * Both modes use the same import format:
     *   - Direct-render: DXVK writes BGRA bytes directly into the AHB.
     *     Memory bytes are BGRA, SurfaceFlinger reads HAL_BGRA → correct.
     *   - Trojan-blit fallback: DXVK writes BGRA into the trojan (also
     *     B8G8R8A8), then vkCmdBlitImage copies trojan → AHB. With both
     *     ends labeled the same format, the blit degenerates to a byte
     *     copy (no channel reinterpretation). AHB memory stays BGRA.
     *
     * The AHB's HAL format and the Vulkan import format MUST agree — if
     * they disagree (e.g. HAL=BGRA + import=RGBA), pixels are read at
     * wrong byte offsets, swapping R and B on display. */
    PFN_vkGetPhysicalDeviceMemoryProperties getMemProps = g_inst_dispatch.GetPhysicalDeviceMemoryProperties;
    for (uint32_t i = 0; i < sc->image_count; i++) {
        VkResult ires = import_ahb_to_vk_image(sc, i, g_ahb_buffers[i], g_phys_device, getMemProps, VK_FORMAT_B8G8R8A8_UNORM);
        if (ires != VK_SUCCESS) {
            LOGE("layer_CreateSwapchainKHR: import failed slot %u: %d", i, ires);
            for (uint32_t j = 0; j < i; j++) {
                sc->pfn_vkDestroyImage(device, sc->images[j].vk_image, NULL);
                sc->pfn_vkFreeMemory(device, sc->images[j].vk_memory, NULL);
            }
            for (uint32_t j = 0; j < sc->image_count; j++)
                sc->pfn_vkDestroyFence(device, sc->images[j].reuse_fence, NULL);
            free(sc);
            LOGW("layer_CreateSwapchainKHR: AHB import failed, falling back");
            return g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
        }
        LOGI("layer_CreateSwapchainKHR: slot %u imported (image=%p)", i, (void*)sc->images[i].vk_image);
    }

    g_ahb_swapchain = sc;

    if (g_direct_render_mode) {
        /* === DIRECT-RENDER MODE (Path 3) ===
         *
         * No trojan swapchain. The AHB-backed VkImages are exposed to DXVK
         * directly via vkGetSwapchainImagesKHR; DXVK renders straight into
         * them. We still need per-slot fences (export-capable for SYNC_FD)
         * because QueuePresentKHR submits a fence-only signal to convert
         * DXVK's render-complete semaphores into a SYNC_FD for SurfaceFlinger.
         *
         * The "real" swapchain handle is the synthetic pointer to our
         * wine_vk_swapchain struct — downstream code compares against
         * g_real_swapchain_handle so any swapchain handle works as long as
         * it's unique. */
        *pSwapchain = (VkSwapchainKHR)(uintptr_t)sc;
        g_real_swapchain_handle = *pSwapchain;
        g_trojan_image_count = 0;  /* signals "no trojan blit infrastructure" */

        /* Create per-slot export-capable fences. Same layout as the trojan
         * path's g_copy_fences but no command-buffer/pool overhead. */
        VkExportFenceCreateInfo export_ci = {
            .sType = VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO,
            .pNext = NULL,
            .handleTypes = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        VkFenceCreateInfo fence_ci = {
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .pNext = &export_ci,
            .flags = 0,
        };
        int created = 0;
        for (uint32_t i = 0; i < sc->image_count; i++) {
            VkResult fres = g_dev_dispatch.CreateFence(device, &fence_ci, NULL, &g_copy_fences[i]);
            if (fres != VK_SUCCESS) {
                LOGW("layer_CreateSwapchainKHR: direct-render fence %u failed (%d), retrying plain", i, fres);
                VkFenceCreateInfo plain_ci = {
                    .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                    .flags = 0,
                };
                g_dev_dispatch.CreateFence(device, &plain_ci, NULL, &g_copy_fences[i]);
            } else {
                created++;
            }
        }
        LOGI("layer_CreateSwapchainKHR: DIRECT-RENDER mode — synthetic swapchain handle=%p, %u/%u export fences",
             (void*)(uintptr_t)*pSwapchain, created, sc->image_count);
    } else {
        /* === TROJAN HORSE: create a REAL swapchain for handle validity ===
         *
         * Pass DXVK's requested present mode through to the real swapchain UNCHANGED.
         *
         * Previously we forced MAILBOX here, on the theory that the trojan needed
         * to "never block on acquire." But we never call vkAcquireNextImageKHR on
         * the trojan — we have our own slot-picking logic — so the trojan's mode
         * never affects our pacing. The override only caused DXVK's internal state
         * (which believes the swapchain is FIFO when DXVK requested FIFO) to diverge
         * from the real driver state (which thinks the swapchain is MAILBOX).
         *
         * That divergence appears to be why FIFO games (Vampire Survivors, Hollow
         * Knight, GTA4) freeze at frame 3 — DXVK's render commands or fence-wait
         * logic interrogates the real swapchain in some way that fails because
         * MAILBOX semantics differ from the FIFO behavior DXVK expects. */
        VkResult real_res = g_dev_dispatch.CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
        LOGI("layer_CreateSwapchainKHR: trojan created with DXVK's requested mode=%d, result=%d",
             (int)pCreateInfo->presentMode, real_res);
        if (real_res != VK_SUCCESS) {
            LOGE("layer_CreateSwapchainKHR: real swapchain creation failed (%d), using AHB-only", real_res);
            /* Fallback: return fake handle (may still fail with Wine thunks) */
            *pSwapchain = (VkSwapchainKHR)(uintptr_t)sc;
        } else {
            g_real_swapchain_handle = *pSwapchain;
            LOGI("layer_CreateSwapchainKHR: real swapchain handle=%p stored as trojan",
                 (void*)(uintptr_t)*pSwapchain);

            /* Get trojan swapchain images — these are regular device-local images
             * (no AHB backing) that DXVK will render into safely. */
            g_trojan_image_count = AHB_MAX_IMAGES;
            VkResult img_res = g_dev_dispatch.GetSwapchainImagesKHR(
                device, g_real_swapchain_handle, &g_trojan_image_count, g_trojan_images);
            if (img_res != VK_SUCCESS && img_res != VK_INCOMPLETE) {
                LOGE("layer_CreateSwapchainKHR: failed to get trojan images (%d)", img_res);
                g_trojan_image_count = 0;
            } else {
                LOGI("layer_CreateSwapchainKHR: got %u trojan images for blit indirection",
                     g_trojan_image_count);
                /* === FIX: cap AHB swapchain image_count to trojan count ===
                 *
                 * The AHB pool has up to AHB_MAX_IMAGES slots, but the real
                 * driver may return fewer trojan images — FIFO swapchains
                 * commonly get 2-3, MAILBOX/IMMEDIATE 3-4. If sc->image_count
                 * exceeds g_trojan_image_count, layer_AcquireNextImageKHR can
                 * return ahb_slot ≥ g_trojan_image_count where the per-slot
                 * cmd_buf/fence is VK_NULL_HANDLE. The QueuePresent blit
                 * branch is skipped, falls through to the fallback that
                 * forwards the AHB WITHOUT doing trojan→AHB copy → receiver
                 * displays whatever stale bytes the AHB happened to hold,
                 * game appears frozen on the first frame that lands in an
                 * orphan slot.
                 *
                 * This is why FIFO games (Vampire Survivors with FIFO + 2-3
                 * trojan images) freeze in trojan-blit mode while MAILBOX
                 * games (Broforce) work — MAILBOX gets enough trojan images
                 * to cover all 4 AHB slots.
                 *
                 * Cap image_count so acquire only ever returns a slot that
                 * has a valid trojan/cmd_buf/fence triple. Unused AHB slots
                 * remain imported (memory waste only) — destroy walks them
                 * via the AHB_MAX_IMAGES-bounded loop in vulkan_ahb.c with
                 * NULL checks already in place. */
                if (g_trojan_image_count > 0 && sc->image_count > g_trojan_image_count) {
                    LOGW("layer_CreateSwapchainKHR: capping image_count %u -> %u (trojan limit, FIFO-fix)",
                         sc->image_count, g_trojan_image_count);
                    sc->image_count = g_trojan_image_count;
                }
            }

            /* Create command pool + per-slot command buffers + per-slot fences for blit.
             * Per-slot resources eliminate the cross-frame fence reuse that was causing
             * Adreno driver timeouts. Each ahb_slot owns its own (cmd_buf, fence) pair. */
            if (g_trojan_image_count > 0 && !g_copy_cmd_pool) {
                VkCommandPoolCreateInfo pool_ci = {
                    .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                    .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                    .queueFamilyIndex = 0, /* graphics queue family */
                };
                g_dev_dispatch.CreateCommandPool(device, &pool_ci, NULL, &g_copy_cmd_pool);

                VkCommandBufferAllocateInfo alloc_ci = {
                    .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                    .commandPool = g_copy_cmd_pool,
                    .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                    .commandBufferCount = g_trojan_image_count,
                };
                g_dev_dispatch.AllocateCommandBuffers(device, &alloc_ci, g_copy_cmd_bufs);

                /* Create fences with SYNC_FD export capability so wine_ahb_queue_present
                 * can call vkGetFenceFdKHR(SYNC_FD_BIT) on them. */
                VkExportFenceCreateInfo export_ci = {
                    .sType = VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO,
                    .pNext = NULL,
                    .handleTypes = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
                };
                VkFenceCreateInfo fence_ci = {
                    .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                    .pNext = &export_ci,
                    /* Not signaled: vkGetFenceFdKHR(SYNC_FD) requires the fence to
                     * have a pending signal operation OR be unsignaled. Initial
                     * signaled state would force a wait+reset on first use. */
                    .flags = 0,
                };
                int created = 0;
                for (uint32_t i = 0; i < g_trojan_image_count; i++) {
                    VkResult fres = g_dev_dispatch.CreateFence(device, &fence_ci, NULL, &g_copy_fences[i]);
                    if (fres != VK_SUCCESS) {
                        LOGW("layer_CreateSwapchainKHR: exportable fence %u failed (%d), retrying plain", i, fres);
                        VkFenceCreateInfo plain_ci = {
                            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                            .flags = 0,
                        };
                        g_dev_dispatch.CreateFence(device, &plain_ci, NULL, &g_copy_fences[i]);
                    } else {
                        created++;
                    }
                }
                LOGI("layer_CreateSwapchainKHR: %u/%u exportable fences created",
                     created, g_trojan_image_count);
                LOGI("layer_CreateSwapchainKHR: per-slot blit resources created (pool=%p, %u cmd_bufs, %u fences)",
                     (void*)g_copy_cmd_pool, g_trojan_image_count, g_trojan_image_count);
            }
        }
    }

    /* Reset mailbox slot state + present-id tracking — all slots start free at
     * swapchain creation, and any old present_records from a previous swapchain
     * are discarded so stale entries can't accidentally complete a new presentId. */
    pthread_mutex_lock(&g_release_mtx);
    for (uint32_t i = 0; i < AHB_MAX_IMAGES; i++) g_slot_free[i] = true;
    for (uint32_t i = 0; i < PRESENT_RECORD_RING; i++) {
        g_present_records[i].present_id = 0;
        g_present_records[i].slot = 0;
        g_present_records[i].completed = false;
    }
    g_present_record_head = 0;
    g_max_completed_present_id = 0;
    g_display_count = 0;
    /* Reset frame-pacing windows so a swapchain recreation starts learning
     * fresh — the new swapchain may have different content / rate. */
    for (uint32_t i = 0; i < FP_WINDOW; i++) {
        g_fp_render_times[i] = 0;
        g_fp_tick_deltas[i]  = 0;
    }
    g_fp_render_head  = g_fp_render_count = 0;
    g_fp_render_seen  = 0;
    g_fp_tick_head    = g_fp_tick_count   = 0;
    g_fp_last_acquire_us         = 0;
    g_fp_last_tick_for_period_us = 0;
    g_fp_vsync_period_us         = 16667;
    g_fp_last_wait_return_us     = 0;
    /* Phase-anchor state: don't reset g_vsync_count itself (it's a monotonic
     * absolute counter), but DO clear g_last_hardware_vsync_us so WFP's
     * "is the Choreographer alive?" check starts from a known state. */
    g_last_hardware_vsync_us     = 0;
    g_presented_ring[0] = g_presented_ring[1] = -1;
    g_presented_idx = 0;
    pthread_cond_broadcast(&g_release_cv);
    pthread_mutex_unlock(&g_release_mtx);
    /* Remember DXVK's requested present mode so acquire can enforce the right pacing. */
    g_present_mode_requested = pCreateInfo->presentMode;
    LOGI("layer_CreateSwapchainKHR: DXVK requested presentMode=%d (FIFO=%d MAILBOX=%d IMMEDIATE=%d) — acquire pacing tuned",
         (int)g_present_mode_requested, (int)VK_PRESENT_MODE_FIFO_KHR,
         (int)VK_PRESENT_MODE_MAILBOX_KHR, (int)VK_PRESENT_MODE_IMMEDIATE_KHR);

    LOGI("layer_CreateSwapchainKHR: AHB SWAPCHAIN CREATED (%dx%d, %u images) — mode=%s",
         pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height, sc->image_count,
         g_direct_render_mode ? "DIRECT-RENDER"
                              : (g_trojan_image_count > 0 ? "TROJAN-BLIT" : "TROJAN-FALLBACK"));
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL layer_DestroySwapchainKHR(
    VkDevice device, VkSwapchainKHR swapchain,
    const VkAllocationCallbacks *pAllocator)
{
    if (g_ahb_swapchain && g_real_swapchain_handle == swapchain) {
        LOGI("layer_DestroySwapchainKHR: destroying AHB swapchain (mode=%s)",
             g_direct_render_mode ? "direct-render" : "trojan-blit");

        /* Drain any in-flight submits before tearing down per-slot resources.
         * QueueWaitIdle is heavy but correct here — destruction is rare. */
        if (g_saved_queue && g_dev_dispatch.QueueWaitIdle)
            g_dev_dispatch.QueueWaitIdle(g_saved_queue);

        if (g_direct_render_mode) {
            /* Direct-render mode: only per-slot fences; no command pool, no
             * trojan images, no real swapchain to destroy. */
            for (uint32_t i = 0; i < AHB_MAX_IMAGES; i++) {
                if (g_copy_fences[i] != VK_NULL_HANDLE) {
                    g_dev_dispatch.DestroyFence(device, g_copy_fences[i], NULL);
                    g_copy_fences[i] = VK_NULL_HANDLE;
                }
            }
            wine_ahb_destroy_swapchain(g_ahb_swapchain);
            g_ahb_swapchain = NULL;
            g_real_swapchain_handle = VK_NULL_HANDLE;
            /* Synthetic handle is just a pointer to the wine_vk_swapchain
             * struct (just freed); nothing for the underlying driver to do. */
            return;
        }

        /* Trojan-blit mode: tear down per-slot fences + command pool + real swapchain. */
        for (uint32_t i = 0; i < g_trojan_image_count; i++) {
            if (g_copy_fences[i] != VK_NULL_HANDLE) {
                g_dev_dispatch.DestroyFence(device, g_copy_fences[i], NULL);
                g_copy_fences[i] = VK_NULL_HANDLE;
            }
            g_copy_cmd_bufs[i] = VK_NULL_HANDLE; /* freed with pool */
        }
        if (g_copy_cmd_pool != VK_NULL_HANDLE) {
            g_dev_dispatch.DestroyCommandPool(device, g_copy_cmd_pool, NULL);
            g_copy_cmd_pool = VK_NULL_HANDLE;
        }
        g_trojan_image_count = 0;

        wine_ahb_destroy_swapchain(g_ahb_swapchain);
        g_ahb_swapchain = NULL;
        /* Also destroy the real swapchain handle */
        g_dev_dispatch.DestroySwapchainKHR(device, swapchain, pAllocator);
        g_real_swapchain_handle = VK_NULL_HANDLE;
        return;
    }
    g_dev_dispatch.DestroySwapchainKHR(device, swapchain, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_GetSwapchainImagesKHR(
    VkDevice device, VkSwapchainKHR swapchain,
    uint32_t *pCount, VkImage *pImages)
{
    if (g_ahb_swapchain && g_real_swapchain_handle == swapchain) {
        /* Direct-render mode: return AHB-backed VkImages directly. DXVK
         * renders into them with no intermediate buffer. */
        if (g_direct_render_mode) {
            VkResult r = wine_ahb_get_swapchain_images(g_ahb_swapchain, pCount, pImages);
            LOGI("layer_GetSwapchainImagesKHR: returning %u DIRECT-RENDER (AHB-backed) images, result=%d",
                 pCount ? *pCount : 0, r);
            return r;
        }
        /* Trojan-blit mode: return trojan images (device-local, no AHB) so
         * DXVK renders into them without gralloc lock conflicts. We blit
         * trojan → AHB in QueuePresentKHR. */
        if (g_trojan_image_count > 0) {
            if (!pImages) {
                *pCount = g_trojan_image_count;
                return VK_SUCCESS;
            }
            uint32_t to_copy = *pCount < g_trojan_image_count ? *pCount : g_trojan_image_count;
            for (uint32_t i = 0; i < to_copy; i++)
                pImages[i] = g_trojan_images[i];
            *pCount = to_copy;
            LOGI("layer_GetSwapchainImagesKHR: returning %u TROJAN images (blit indirection)", to_copy);
            return (to_copy < g_trojan_image_count) ? VK_INCOMPLETE : VK_SUCCESS;
        }
        /* Fallback path: also return AHB images directly. */
        VkResult r = wine_ahb_get_swapchain_images(g_ahb_swapchain, pCount, pImages);
        LOGI("layer_GetSwapchainImagesKHR: count=%u images=%p result=%d (fallback)",
             pCount ? *pCount : 0, (void*)pImages, r);
        return r;
    }
    return g_dev_dispatch.GetSwapchainImagesKHR(device, swapchain, pCount, pImages);
}

/* Track the last real_idx from AcquireNextImageKHR for presenting back to trojan */
static uint32_t g_last_real_idx = 0;
/* g_saved_queue, g_slot_free, g_presented_ring, g_presented_idx all declared at
 * the top of the file (forward-required by layer_CreateSwapchainKHR). */

/* Helper: count free slots. Must be called with g_release_mtx held. */
static int count_free_slots_locked(uint32_t image_count) {
    int n = 0;
    for (uint32_t i = 0; i < image_count; i++) if (g_slot_free[i]) n++;
    return n;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_AcquireNextImageKHR(
    VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
    VkSemaphore semaphore, VkFence fence, uint32_t *pImageIndex)
{
    (void)timeout;
    if (g_ahb_swapchain && g_real_swapchain_handle == swapchain) {
        uint32_t image_count = g_ahb_swapchain->image_count;

        /* === ACQUIRE PACING ===
         * All slot-free state and release tracking now lives behind g_release_mtx.
         * The release_reader_thread is the sole consumer of MSG_RELEASE on the
         * socket; it flips g_slot_free entries and signals g_release_cv.
         *
         * FIFO contract: DXVK expects acquire to block when the pipeline is full.
         * With 4 buffers and FIFO, allow up to image_count-1 frames in flight,
         * then block until a release arrives. MAILBOX/IMMEDIATE: block only on
         * full saturation. */
        pthread_mutex_lock(&g_release_mtx);

        static uint64_t s_acquire_counter = 0;
        s_acquire_counter++;
        int free_count = count_free_slots_locked(image_count);

        /* WINLATOR_AHB_NO_PACING=1 also disables the FIFO back-pressure
         * here. The WaitForPresent vsync sleep is one cap; this is the
         * other — when image_count-1 frames are already queued, FIFO
         * normally blocks the next acquire until a release arrives, which
         * caps DXVK at the slot-release rate (= panel vsync rate). With
         * the bypass set, acquire only blocks on TRUE saturation
         * (free_count == 0), letting DXVK produce at GPU speed and rely
         * on mailbox-drain in the receiver to discard the overflow. */
        static int s_no_pacing_acquire = -1;
        if (s_no_pacing_acquire < 0) {
            const char *env = getenv("WINLATOR_AHB_NO_PACING");
            s_no_pacing_acquire = (env && env[0] == '1') ? 1 : 0;
        }
        for (;;) {
            int frames_in_flight = (int)image_count - free_count;
            bool need_block = (free_count == 0);
            if (!s_no_pacing_acquire
                && g_present_mode_requested == VK_PRESENT_MODE_FIFO_KHR
                && s_acquire_counter > image_count
                && frames_in_flight >= (int)image_count - 1) {
                need_block = true;
            }
            if (!need_block) break;
            pthread_cond_wait(&g_release_cv, &g_release_mtx);
            free_count = count_free_slots_locked(image_count);
        }

        /* Pick first free slot. Prefer slots NOT in the recently-presented ring
         * so SurfaceFlinger isn't still scanning it out. */
        int chosen = -1;
        for (uint32_t i = 0; i < image_count; i++) {
            uint32_t idx = (g_ahb_swapchain->current_image + i) % image_count;
            if (g_slot_free[idx] &&
                (int)idx != g_presented_ring[0] && (int)idx != g_presented_ring[1]) {
                chosen = (int)idx;
                break;
            }
        }
        if (chosen < 0) {
            for (uint32_t i = 0; i < image_count; i++) {
                if (g_slot_free[i]) { chosen = (int)i; break; }
            }
        }
        if (chosen < 0) chosen = 0;
        g_slot_free[chosen] = false;
        pthread_mutex_unlock(&g_release_mtx);

        uint32_t ahb_idx = (uint32_t)chosen;
        /* g_slot_free[ahb_idx] was already set false inside the locked section. */
        g_ahb_swapchain->current_image = (ahb_idx + 1) % image_count;

        /* Frame pacing: stamp the moment DXVK starts working on this frame.
         * Paired in QueuePresent to compute render time. */
        pthread_mutex_lock(&g_release_mtx);
        g_fp_last_acquire_us = mono_us();
        pthread_mutex_unlock(&g_release_mtx);

        static int _acq_cnt = 0;
        ++_acq_cnt;
        /* Log every acquire for the first 30 frames so first-launch shader-compile
         * stalls show up clearly in the log (acquire-but-no-present gaps indicate
         * DXVK is stuck compiling, not a DAC bug). After 30, sample every 240th. */
        if (_acq_cnt <= 30 || (_acq_cnt % 240 == 0)) {
            LOGI("layer_AcquireNextImageKHR: frame %d ahb_idx=%u free=%d/%u",
                 _acq_cnt, ahb_idx, free_count, image_count);
        }

        *pImageIndex = ahb_idx;

        /* Signal the semaphore/fence directly via an empty queue submit */
        if (g_saved_queue && (semaphore || fence)) {
            VkSubmitInfo submit = {
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = NULL,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = NULL,
                .pWaitDstStageMask = NULL,
                .commandBufferCount = 0,
                .pCommandBuffers = NULL,
                .signalSemaphoreCount = semaphore ? 1u : 0u,
                .pSignalSemaphores = semaphore ? &semaphore : NULL,
            };
            g_dev_dispatch.QueueSubmit(g_saved_queue, 1, &submit, fence);
        }

        return VK_SUCCESS;
    }
    return g_dev_dispatch.AcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, pImageIndex);
}

/* === THE FIX FOR FIFO GAMES (Vampire Survivors / Hollow Knight / GTA4) ===
 *
 * DXVK enables VK_KHR_present_wait on FIFO swapchains and uses
 * vkWaitForPresentKHR(swap, presentId, timeout) for vsync frame pacing.
 * After every vkQueuePresentKHR with a VkPresentIdKHR={presentId=N}, DXVK
 * blocks on vkWaitForPresentKHR(N) until that present is observable on
 * the display, then submits the next frame.
 *
 * Our DAC pipeline BYPASSES the real vkQueuePresentKHR (we blit trojan→AHB
 * and ship the buffer to SurfaceFlinger via socket). So the trojan
 * swapchain's presentId is never signaled. DXVK waits forever on
 * vkWaitForPresentKHR and the game freezes — typically at frame 3 because
 * present-id tracking ramps up after the first few frames.
 *
 * Confirmed via DXVK log: "VK_KHR_present_wait: presentWait : 1".
 *
 * The fix: return VK_SUCCESS immediately for the trojan swapchain. The
 * present really did happen — it just went through the DAC path instead
 * of the real swapchain. From DXVK's pacing perspective, this is correct. */
static VKAPI_ATTR VkResult VKAPI_CALL layer_WaitForPresentKHR(
    VkDevice device, VkSwapchainKHR swapchain,
    uint64_t presentId, uint64_t timeout)
{
    if (g_ahb_swapchain && g_real_swapchain_handle == swapchain) {
        /* === Pacing bypass ===
         * WINLATOR_AHB_NO_PACING=1 disables the vsync sleep entirely.
         * DXVK still has its swapchain — the underlying mailbox/drop
         * semantics in the receiver still cap displayed FPS to the panel
         * rate — but DXVK is no longer artificially held back. Use this to
         * confirm DAC's architectural throughput matches Native (i.e. the
         * 60-FPS reading is the pacing, not a ceiling).
         *
         * Probed once per process via a static. Set once at the first call
         * to keep the hot path branch-free after that. */
        static int s_no_pacing = -1;
        if (s_no_pacing < 0) {
            const char *env = getenv("WINLATOR_AHB_NO_PACING");
            s_no_pacing = (env && env[0] == '1') ? 1 : 0;
            if (s_no_pacing) {
                LOGI("layer_WaitForPresentKHR: WINLATOR_AHB_NO_PACING=1 — vsync sleep DISABLED, returning immediately");
            }
        }
        if (s_no_pacing) {
            /* Bookkeep enough state that fallback paths still work if the
             * env var is unset on a future swapchain. */
            g_fp_last_wait_return_us = mono_us();
            return VK_SUCCESS;
        }
        /* === Pillar 2 — phase-anchored absolute sleep ===
         *
         * Each call wakes at the NEXT panel-vsync-aligned boundary, using:
         *   - Phase reference: g_last_hardware_vsync_us, updated by the
         *     release-reader thread on every MSG_VSYNC from the Android-side
         *     AChoreographer callback. The IPC keeps the clock synchronized;
         *     it does NOT gate the wait, so its delivery variance can't
         *     affect wake precision.
         *   - Wake mechanism: clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME),
         *     a single syscall returning at the kernel scheduler's precision
         *     with zero thread-hop variance.
         *
         * Design decisions:
         *
         *   1. NO render-time prediction. Earlier iterations used predicted
         *      render time to scale intervals_needed (1×/2×/4× vsync). That
         *      created the feedback loop: predicted climbed during startup
         *      shader compiles, pacing slowed to 15 FPS, render times were
         *      measured AGAINST the slowed pacing, predicted stayed high,
         *      and recovery took ~40 seconds. We now ALWAYS target the next
         *      vsync (or the one after, if we're already past it because
         *      render ran long). Slow renders auto-pace via natural slot
         *      back-pressure (Acquire blocks) and via missed-vsync drift.
         *      No artificial cap, no recursive feedback.
         *
         *   2. Phase-anchored, not relative. target = g_last_hardware_vsync_us
         *      + N × 16667 (smallest N s.t. target > now). Each call
         *      recomputes the anchor fresh — drift can't accumulate.
         *
         *   3. Fallback to relative pacing when the vsync stream is silent
         *      (very first present, scanout torn down). Same clock_nanosleep
         *      primitive — just a different reference. */
        uint64_t wait_entry_us = mono_us();

        /* Prediction is kept for diagnostics only — does not influence pacing. */
        pthread_mutex_lock(&g_release_mtx);
        uint64_t predicted = fp_percentile_render_locked(50);  /* median + 15% bias */
        uint64_t vsync_anchor = g_last_hardware_vsync_us;
        uint64_t now_us = mono_us();
        bool vsync_stream_live = (vsync_anchor != 0) &&
                                 (now_us - vsync_anchor < 3 * 16667);
        pthread_mutex_unlock(&g_release_mtx);

        /* Use the measured panel period from MSG_VSYNC deltas — falls back
         * to 16667 µs (60 Hz) if we haven't seen two vsyncs yet. */
        pthread_mutex_lock(&g_release_mtx);
        uint64_t measured = g_vsync_period_us_measured;
        pthread_mutex_unlock(&g_release_mtx);
        const uint64_t vsync_period_us = (measured != 0) ? measured : 16667ULL;
        VkResult result = VK_SUCCESS;

        /* Compute absolute wake target. */
        uint64_t target_us;
        if (vsync_stream_live) {
            /* Phase-anchored: target is the next panel-vsync boundary in the
             * future, measured from the most recent hardware vsync we know
             * about. The anchor is recv-time of MSG_VSYNC (NOT the shipped
             * frameTimeNanos), which gives us implicit ~500 µs of SurfaceFlinger
             * deadline margin — empirically the smoothest config on this SoC.
             * See the MSG_VSYNC handler for rationale.
             *
             * The (target_us <= now_us) loop handles two cases:
             *   - Normal: the next vsync is one period after the anchor.
             *   - Catch-up: render took longer than one period, so we skip
             *     ahead by additional periods until the target is genuinely
             *     in the future. */
            target_us = vsync_anchor + vsync_period_us;
            while (target_us <= now_us) target_us += vsync_period_us;
        } else if (g_fp_last_wait_return_us != 0) {
            /* Fallback: relative pacing from the previous return. */
            target_us = g_fp_last_wait_return_us + vsync_period_us;
            if (target_us <= now_us) target_us = now_us;  /* don't drift backward */
        } else {
            /* Very first call. Return immediately so we don't stall on
             * absent state; subsequent calls will be properly paced. */
            target_us = now_us;
        }

        /* Honor caller's timeout. */
        if (timeout == 0) {
            /* DXVK occasionally polls with timeout=0; semantics is "return
             * VK_TIMEOUT if not yet ready". With phase-anchored pacing we
             * have nothing to "be ready for", so just return success. */
        } else {
            uint64_t deadline_us = (timeout == UINT64_MAX)
                                   ? UINT64_MAX
                                   : now_us + (timeout / 1000ULL);
            if (target_us > deadline_us) {
                target_us = deadline_us;
                result = VK_TIMEOUT;
            }
        }

        /* Absolute sleep. CLOCK_MONOTONIC matches mono_us(). */
        if (target_us > now_us) {
            struct timespec target_ts;
            target_ts.tv_sec  = (time_t)(target_us / 1000000ULL);
            target_ts.tv_nsec = (long)((target_us % 1000000ULL) * 1000ULL);
            int rc;
            while ((rc = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME,
                                         &target_ts, NULL)) == EINTR) {
                /* clock_nanosleep with ABSTIME re-uses the same target on
                 * restart — no remainder tracking needed. */
            }
            (void)rc;  /* ignore other errors; we still mark return time */
        }

        uint64_t wait_return_us = mono_us();
        g_fp_last_wait_return_us = wait_return_us;

        /* Periodic visibility into pacing decisions. */
        static int s_pace_log_cnt = 0;
        if (++s_pace_log_cnt <= 5 || s_pace_log_cnt % 240 == 0) {
            LOGI("frame_pacing: predicted_us=%llu vsync_us=%llu path=%s vsync_count=%llu (render_n=%u)",
                 (unsigned long long)predicted, (unsigned long long)vsync_period_us,
                 vsync_stream_live ? "phase-anchored" : "wall-clock-fallback",
                 (unsigned long long)g_vsync_count,
                 g_fp_render_count);
        }

        /* JITTER TRACE: how long this call blocked + delta-since-last-return. */
        uint64_t wait_us = wait_return_us - wait_entry_us;
        static uint64_t s_wait_prev = 0;
        JTRACE("wait_return", &s_wait_prev, "wait_us=%llu presentId=%llu",
               (unsigned long long)wait_us, (unsigned long long)presentId);

        static int s_wait_cnt = 0;
        if (++s_wait_cnt <= 10 || s_wait_cnt % 240 == 0) {
            LOGI("layer_WaitForPresentKHR: presentId=%llu result=%d wait_us=%llu",
                 (unsigned long long)presentId, (int)result,
                 (unsigned long long)wait_us);
        }
        return result;
    }
    if (g_dev_dispatch.WaitForPresentKHR)
        return g_dev_dispatch.WaitForPresentKHR(device, swapchain, presentId, timeout);
    return VK_ERROR_EXTENSION_NOT_PRESENT;
}

/* ========================================================================
 * Transient attachment promotion
 *
 * On Adreno (TBDR), an image declared with VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT
 * and bound to LAZILY_ALLOCATED memory stays entirely in GMEM (the tile cache)
 * and never gets written to main DDR. For intermediate render targets that
 * are written then read within the same render pass, this eliminates the
 * GMEM→DDR resolve and DDR→GMEM reload — a significant bandwidth saving.
 *
 * DXVK doesn't set TRANSIENT_ATTACHMENT because on desktop GPUs it provides
 * no benefit. We promote candidates at the layer level using a conservative
 * heuristic:
 *
 *   PROMOTE if and only if ALL of the following hold:
 *     1. usage includes COLOR_ATTACHMENT or DEPTH_STENCIL_ATTACHMENT
 *     2. usage includes INPUT_ATTACHMENT (signals "read within render pass")
 *     3. usage does NOT include SAMPLED   (signals "read in a later pass")
 *     4. usage does NOT include STORAGE   (compute would break tiled memory)
 *     5. usage does NOT include TRANSFER_SRC/DST (signals "copied elsewhere")
 *     6. tiling is OPTIMAL (TILING_LINEAR can't be transient)
 *     7. arrayLayers == 1, mipLevels == 1 (multi-resource transient is risky)
 *
 * INPUT_ATTACHMENT specifically marks an attachment that's read by a later
 * subpass in the SAME render pass — exactly the pattern that benefits.
 *
 * This is a conservative filter — it will NOT trigger on plain framebuffer
 * targets (those are SAMPLED for the post-process chain) or on swapchain
 * images. It only catches G-buffers and depth pre-pass results in games
 * using multi-subpass render passes (Unreal Engine, idTech, etc).
 *
 * If the driver later refuses the LAZILY_ALLOCATED memory binding (no such
 * memory type available), DXVK falls back to standard memory; no breakage.
 * ======================================================================== */
static bool ahb_should_promote_transient(const VkImageCreateInfo *ci)
{
    if (!ci) return false;
    const VkImageUsageFlags u = ci->usage;

    bool isAttachment =
        (u & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
              VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0;
    bool isInputAttachment = (u & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) != 0;
    bool isSampled         = (u & VK_IMAGE_USAGE_SAMPLED_BIT) != 0;
    bool isStorage         = (u & VK_IMAGE_USAGE_STORAGE_BIT) != 0;
    bool isTransfer        = (u & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_DST_BIT)) != 0;

    if (!isAttachment)       return false;
    if (!isInputAttachment)  return false;  /* must be read within the pass */
    if (isSampled)           return false;  /* read in a later pass — keep DDR-backed */
    if (isStorage)           return false;
    if (isTransfer)          return false;
    if (ci->tiling != VK_IMAGE_TILING_OPTIMAL) return false;
    if (ci->arrayLayers != 1 || ci->mipLevels != 1) return false;
    return true;
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateImage(
    VkDevice device,
    const VkImageCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkImage *pImage)
{
    if (ahb_should_promote_transient(pCreateInfo)) {
        VkImageCreateInfo ci = *pCreateInfo;
        ci.usage |= VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
        static int promoteCnt = 0;
        if (++promoteCnt <= 8 || (promoteCnt % 64 == 0)) {
            LOGI("layer_CreateImage: promoting to TRANSIENT_ATTACHMENT "
                 "(%ux%u fmt=%d usage=0x%x → 0x%x) [count=%d]",
                 ci.extent.width, ci.extent.height, ci.format,
                 pCreateInfo->usage, ci.usage, promoteCnt);
        }
        return g_dev_dispatch.CreateImage
             ? g_dev_dispatch.CreateImage(device, &ci, pAllocator, pImage)
             : VK_ERROR_INITIALIZATION_FAILED;
    }
    /* No promotion — forward unchanged. */
    return g_dev_dispatch.CreateImage
         ? g_dev_dispatch.CreateImage(device, pCreateInfo, pAllocator, pImage)
         : VK_ERROR_INITIALIZATION_FAILED;
}

/* === DIAGNOSTIC vkQueueSubmit intercept ===
 * Logs every submission so we can see what DXVK does between acquire-N and
 * present-N. If freezes happen after acquire-3, we want to know: does DXVK
 * still submit render-3 commands (and they just don't return), or is DXVK
 * stuck CPU-side before submission? */
static VKAPI_ATTR VkResult VKAPI_CALL layer_QueueSubmit(
    VkQueue queue, uint32_t submitCount,
    const VkSubmitInfo *pSubmits, VkFence fence)
{
    if (g_ahb_swapchain) {
        static int sub_cnt = 0;
        ++sub_cnt;
        if (sub_cnt <= 60 || sub_cnt % 240 == 0) {
            uint32_t waits = 0, signals = 0, cbs = 0;
            for (uint32_t i = 0; i < submitCount; i++) {
                waits  += pSubmits[i].waitSemaphoreCount;
                signals+= pSubmits[i].signalSemaphoreCount;
                cbs    += pSubmits[i].commandBufferCount;
            }
            LOGI("layer_QueueSubmit #%d: submitCount=%u cbs=%u waits=%u signals=%u fence=%p",
                 sub_cnt, submitCount, cbs, waits, signals, (void*)fence);
        }
    }
    return g_dev_dispatch.QueueSubmit(queue, submitCount, pSubmits, fence);
}

static VKAPI_ATTR VkResult VKAPI_CALL layer_QueuePresentKHR(
    VkQueue queue, const VkPresentInfoKHR *pPresentInfo)
{
    if (g_ahb_swapchain) {
        for (uint32_t i = 0; i < pPresentInfo->swapchainCount; i++) {
            if (g_real_swapchain_handle == pPresentInfo->pSwapchains[i]) {
                /* Save queue for semaphore signaling in AcquireNextImageKHR */
                if (!g_saved_queue) g_saved_queue = queue;

                uint32_t ahb_slot = pPresentInfo->pImageIndices[i];

                /* JITTER TRACE: time between successive QueuePresent calls.
                 * This is DXVK's effective render cadence as seen by us. */
                {
                    static uint64_t s_present_prev = 0;
                    JTRACE("present", &s_present_prev, "slot=%u", ahb_slot);
                }

                /* Frame pacing: record render time (acquire → present).
                 *
                 * Two guards against poisoning the prediction window:
                 *   1. Skip the first FP_WARMUP_FRAMES samples — startup
                 *      hitches (shader compile, first-asset load) don't
                 *      represent steady-state render time.
                 *   2. Discard outliers above 6×vsync (~100ms). Real games
                 *      never sustain <10 FPS render times; anything beyond
                 *      that is a hitch we don't want to feed back into pacing.
                 */
                {
                    uint64_t now = mono_us();
                    pthread_mutex_lock(&g_release_mtx);
                    if (g_fp_last_acquire_us != 0) {
                        uint64_t rt = now - g_fp_last_acquire_us;
                        uint64_t vsync_us = g_fp_vsync_period_us;
                        if (vsync_us < 4000)  vsync_us = 16667;
                        if (vsync_us > 33334) vsync_us = 33334;
                        uint64_t outlier_cap = vsync_us * 6;  /* ~100ms @ 60Hz */
                        g_fp_render_seen++;
                        if (g_fp_render_seen > FP_WARMUP_FRAMES
                            && rt > 0 && rt < outlier_cap) {
                            g_fp_render_times[g_fp_render_head] = rt;
                            g_fp_render_head = (g_fp_render_head + 1) % FP_WINDOW;
                            if (g_fp_render_count < FP_WINDOW) g_fp_render_count++;
                        }
                    }
                    g_fp_last_acquire_us = 0;
                    pthread_mutex_unlock(&g_release_mtx);
                }

                /* Extract DXVK's VkPresentIdKHR (pNext chain). It carries one
                 * presentId per swapchain in pPresentIds[]. Record (id, slot)
                 * so the release-reader thread can map MSG_RELEASE → "this
                 * presentId is now displayed" and wake waiters. */
                uint64_t present_id_this = 0;
                {
                    const VkBaseInStructure *p = (const VkBaseInStructure*)pPresentInfo->pNext;
                    while (p) {
                        if (p->sType == VK_STRUCTURE_TYPE_PRESENT_ID_KHR) {
                            const VkPresentIdKHR *pid = (const VkPresentIdKHR*)p;
                            if (i < pid->swapchainCount && pid->pPresentIds) {
                                present_id_this = pid->pPresentIds[i];
                            }
                            break;
                        }
                        p = p->pNext;
                    }
                }
                if (present_id_this != 0) {
                    pthread_mutex_lock(&g_release_mtx);
                    struct present_record *r = &g_present_records[g_present_record_head];
                    r->present_id = present_id_this;
                    r->slot       = ahb_slot;
                    r->completed  = false;
                    g_present_record_head = (g_present_record_head + 1) % PRESENT_RECORD_RING;
                    pthread_mutex_unlock(&g_release_mtx);
                }

                /* Direct-render mode: DXVK already wrote the final image into
                 * the AHB. No blit needed — we just need to convert DXVK's
                 * render-complete wait semaphores into a SYNC_FD that
                 * SurfaceFlinger can wait on. Standard Vulkan idiom: submit
                 * zero command buffers, wait on the semaphores, signal a
                 * fence. wine_ahb_queue_present exports the fence as SYNC_FD.
                 *
                 * Saved vs trojan-blit: ~1ms GPU blit, 4 image barriers, the
                 * CB Begin/Record/End, and the entire trojan-image allocation
                 * at swapchain creation. */
                if (g_direct_render_mode
                    && g_copy_fences[ahb_slot] != VK_NULL_HANDLE) {

                    VkFence fence = g_copy_fences[ahb_slot];
                    g_dev_dispatch.ResetFences(g_device, 1, &fence);

                    VkPipelineStageFlags wait_stages[8];
                    uint32_t wait_count = pPresentInfo->waitSemaphoreCount;
                    if (wait_count > 8) wait_count = 8;
                    for (uint32_t w_i = 0; w_i < wait_count; w_i++) {
                        /* BOTTOM_OF_PIPE because there's no GPU work after
                         * the wait — the fence signals as soon as the
                         * semaphores resolve. */
                        wait_stages[w_i] = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
                    }

                    VkSubmitInfo si = {
                        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                        .waitSemaphoreCount = wait_count,
                        .pWaitSemaphores = wait_count ? pPresentInfo->pWaitSemaphores : NULL,
                        .pWaitDstStageMask = wait_count ? wait_stages : NULL,
                        .commandBufferCount = 0,
                        .pCommandBuffers = NULL,
                        .signalSemaphoreCount = 0,
                        .pSignalSemaphores = NULL,
                    };
                    g_dev_dispatch.QueueSubmit(queue, 1, &si, fence);

                    g_presented_ring[g_presented_idx] = (int)ahb_slot;
                    g_presented_idx = (g_presented_idx + 1) % 2;

                    /* DXVK wrote BGRA bytes into the AHB (Wine's winevulkan
                     * thunk picks BGRA regardless of our surface-format
                     * advertisement). Tell the receiver to do an R↔B swap
                     * via its local format-aware blit. */
                    wine_ahb_queue_present(g_ahb_swapchain, queue, ahb_slot, fence, present_id_this, /*bgra_bytes=*/1);
                }
                /* === BLIT INDIRECTION: copy trojan image → AHB image ===
                 * Per-slot resources prevent cross-frame fence reuse (Adreno timeout).
                 * DXVK's wait semaphores are forwarded to the blit submit so the copy
                 * doesn't start until DXVK finishes drawing the trojan image. The
                 * resulting fence is exported as a SYNC_FD by wine_ahb_queue_present
                 * and handed to SurfaceFlinger via the present_msg — no CPU wait. */
                else if (g_trojan_image_count > 0 && g_copy_cmd_bufs[ahb_slot] != VK_NULL_HANDLE
                    && g_copy_fences[ahb_slot] != VK_NULL_HANDLE) {

                    VkCommandBuffer cb = g_copy_cmd_bufs[ahb_slot];
                    VkFence fence = g_copy_fences[ahb_slot];

                    /* Reset per-slot resources.
                     * Safety: by the time slot N is revisited, the release chain
                     * (Wine blocks on MSG_RELEASE → SurfaceFlinger onComplete → sync_fd
                     * has signaled → GPU blit done) guarantees the previous use's
                     * GPU work has completed, so vkResetCommandBuffer is safe.
                     * vkResetFences is idempotent on an already-unsignaled fence
                     * (which is the post-SYNC_FD-export state). */
                    g_dev_dispatch.ResetFences(g_device, 1, &fence);
                    g_dev_dispatch.ResetCommandBuffer(cb, 0);

                    VkCommandBufferBeginInfo bi = {
                        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                    };
                    g_dev_dispatch.BeginCommandBuffer(cb, &bi);

                    VkImage trojan = g_trojan_images[ahb_slot];
                    VkImage ahb_img = g_ahb_swapchain->images[ahb_slot].vk_image;
                    uint32_t w = (uint32_t)g_ahb_swapchain->surface->width;
                    uint32_t h = (uint32_t)g_ahb_swapchain->surface->height;

                    VkImageSubresourceRange full = {
                        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                        .baseMipLevel = 0, .levelCount = 1,
                        .baseArrayLayer = 0, .layerCount = 1,
                    };

                    /* Barrier 1: trojan PRESENT_SRC_KHR → TRANSFER_SRC_OPTIMAL.
                     * DXVK leaves the rendered image in PRESENT_SRC_KHR. */
                    VkImageMemoryBarrier b_trojan_to_src = {
                        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                        .srcAccessMask = 0,
                        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                        .oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .image = trojan,
                        .subresourceRange = full,
                    };
                    /* Barrier 2: AHB UNDEFINED → TRANSFER_DST_OPTIMAL.
                     * Previous content is discarded — SurfaceFlinger has already
                     * consumed it (or this is the first use of this slot). */
                    VkImageMemoryBarrier b_ahb_to_dst = {
                        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                        .srcAccessMask = 0,
                        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .image = ahb_img,
                        .subresourceRange = full,
                    };
                    VkImageMemoryBarrier pre_barriers[2] = { b_trojan_to_src, b_ahb_to_dst };
                    g_dev_dispatch.CmdPipelineBarrier(cb,
                        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, 0, NULL, 0, NULL, 2, pre_barriers);

                    /* Use vkCmdBlitImage (not CmdCopyImage) because trojan format is
                     * B8G8R8A8 (DXVK's choice) and AHB-VkImage format is R8G8B8A8
                     * (matches the AHB's native HAL_PIXEL_FORMAT_RGBA_8888 allocation).
                     * Blit reads/writes pixels by FORMAT SEMANTICS, so reading a BGRA
                     * pixel and writing as RGBA effectively swaps R↔B bytes in memory.
                     * CmdCopyImage would byte-copy, leaving SurfaceFlinger to display
                     * red as blue. Filter is NEAREST since dimensions match exactly. */
                    VkImageBlit blit_region = {
                        .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .srcOffsets = { {0, 0, 0}, {(int32_t)w, (int32_t)h, 1} },
                        .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .dstOffsets = { {0, 0, 0}, {(int32_t)w, (int32_t)h, 1} },
                    };
                    g_dev_dispatch.CmdBlitImage(cb,
                        trojan,  VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        ahb_img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        1, &blit_region, VK_FILTER_NEAREST);

                    /* Barrier 3: trojan TRANSFER_SRC_OPTIMAL → PRESENT_SRC_KHR.
                     * Restore the layout DXVK expects for the next render cycle. */
                    VkImageMemoryBarrier b_trojan_back = {
                        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                        .srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                        .dstAccessMask = 0,
                        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .image = trojan,
                        .subresourceRange = full,
                    };
                    /* Barrier 4: AHB TRANSFER_DST_OPTIMAL → GENERAL.
                     * SurfaceFlinger reads the AHB as an external sampler; GENERAL
                     * is the safe layout for handoff outside Vulkan's tracking. */
                    VkImageMemoryBarrier b_ahb_to_general = {
                        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                        .dstAccessMask = 0,
                        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .image = ahb_img,
                        .subresourceRange = full,
                    };
                    VkImageMemoryBarrier post_barriers[2] = { b_trojan_back, b_ahb_to_general };
                    g_dev_dispatch.CmdPipelineBarrier(cb,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        0, 0, NULL, 0, NULL, 2, post_barriers);

                    g_dev_dispatch.EndCommandBuffer(cb);

                    /* Forward DXVK's render-complete wait semaphores to the blit
                     * submit so the copy waits for DXVK's writes to finish on the
                     * GPU side. No CPU-side drain — pure GPU pipelining. */
                    VkPipelineStageFlags wait_stages[8];
                    uint32_t wait_count = pPresentInfo->waitSemaphoreCount;
                    if (wait_count > 8) wait_count = 8;
                    for (uint32_t w_i = 0; w_i < wait_count; w_i++)
                        wait_stages[w_i] = VK_PIPELINE_STAGE_TRANSFER_BIT;

                    VkSubmitInfo si = {
                        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                        .waitSemaphoreCount = wait_count,
                        .pWaitSemaphores = wait_count ? pPresentInfo->pWaitSemaphores : NULL,
                        .pWaitDstStageMask = wait_count ? wait_stages : NULL,
                        .commandBufferCount = 1,
                        .pCommandBuffers = &cb,
                        .signalSemaphoreCount = 0,
                        .pSignalSemaphores = NULL,
                    };
                    g_dev_dispatch.QueueSubmit(queue, 1, &si, fence);

                    /* Track this slot as recently presented */
                    g_presented_ring[g_presented_idx] = (int)ahb_slot;
                    g_presented_idx = (g_presented_idx + 1) % 2;

                    /* Send present_msg with the fence — wine_ahb_queue_present
                     * exports it as SYNC_FD (VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT)
                     * and ships the fd to Android via SCM_RIGHTS. SurfaceFlinger
                     * waits on the sync_fd before scanout. */
                    /* Trojan-blit path: the layer's vkCmdBlitImage copies
                     * trojan (B8G8R8A8) → AHB (B8G8R8A8). With the pool
                     * now allocating HAL_BGRA AHBs (matching DXVK's
                     * preferred swapchain format), both ends of the blit
                     * are the same format and the operation degenerates
                     * to a byte copy. AHB memory holds BGRA bytes →
                     * receiver should treat it as BGRA. */
                    wine_ahb_queue_present(g_ahb_swapchain, queue, ahb_slot, fence, present_id_this, /*bgra_bytes=*/1);
                } else {
                    /* Fallback path: no blit infrastructure — preserve pre-fix
                     * behavior of just draining wait semaphores and forwarding. */
                    if (pPresentInfo->waitSemaphoreCount > 0) {
                        VkPipelineStageFlags wait_stages[8];
                        for (uint32_t w = 0; w < pPresentInfo->waitSemaphoreCount && w < 8; w++)
                            wait_stages[w] = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
                        VkSubmitInfo drain = {
                            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                            .waitSemaphoreCount = pPresentInfo->waitSemaphoreCount,
                            .pWaitSemaphores = pPresentInfo->pWaitSemaphores,
                            .pWaitDstStageMask = wait_stages,
                            .commandBufferCount = 0,
                            .signalSemaphoreCount = 0,
                        };
                        g_dev_dispatch.QueueSubmit(queue, 1, &drain, VK_NULL_HANDLE);
                    }
                    g_presented_ring[g_presented_idx] = (int)ahb_slot;
                    g_presented_idx = (g_presented_idx + 1) % 2;
                    /* Fallback: AHB content is whatever DXVK happened to
                     * write with no layer-side translation. AHB pool is
                     * HAL_BGRA so DXVK's BGRA writes match the byte layout —
                     * flag as BGRA. */
                    wine_ahb_queue_present(g_ahb_swapchain, VK_NULL_HANDLE, ahb_slot, VK_NULL_HANDLE, present_id_this, /*bgra_bytes=*/1);
                }

                static int _cnt = 0;
                ++_cnt;
                /* Log first 30 presents to make first-launch behavior visible
                 * (including any gaps caused by DXVK shader compile). */
                if (_cnt <= 30 || (_cnt % 60 == 0))
                    LOGI("layer_QueuePresentKHR: frame %d slot=%u mode=%s",
                         _cnt, ahb_slot,
                         g_direct_render_mode ? "direct"
                                              : (g_trojan_image_count > 0 ? "trojan-blit" : "trojan-fallback"));
                if (pPresentInfo->pResults) pPresentInfo->pResults[i] = VK_SUCCESS;
                return VK_SUCCESS;
            }
        }
    }
    return g_dev_dispatch.QueuePresentKHR(queue, pPresentInfo);
}

/* ========================================================================
 * Layer core: vkCreateInstance
 * ======================================================================== */

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance)
{
    VkLayerInstanceCreateInfo *layerInfo = get_instance_chain_info(pCreateInfo, VK_LAYER_LINK_INFO);
    if (!layerInfo) {
        LOGE("layer_CreateInstance: no layer link info");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    PFN_vkGetInstanceProcAddr fpGetInstanceProcAddr = layerInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    /* Advance the chain for the next layer */
    layerInfo->u.pLayerInfo = layerInfo->u.pLayerInfo->pNext;

    PFN_vkCreateInstance fpCreateInstance = (PFN_vkCreateInstance)
        fpGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    if (!fpCreateInstance) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = fpCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (result != VK_SUCCESS) return result;

    g_instance = *pInstance;

    /* Populate instance dispatch */
    g_inst_dispatch.GetInstanceProcAddr = fpGetInstanceProcAddr;
    g_inst_dispatch.DestroyInstance = (PFN_vkDestroyInstance)
        fpGetInstanceProcAddr(*pInstance, "vkDestroyInstance");
    g_inst_dispatch.EnumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)
        fpGetInstanceProcAddr(*pInstance, "vkEnumeratePhysicalDevices");
    g_inst_dispatch.EnumerateDeviceExtensionProperties = (PFN_vkEnumerateDeviceExtensionProperties)
        fpGetInstanceProcAddr(*pInstance, "vkEnumerateDeviceExtensionProperties");
    g_inst_dispatch.GetPhysicalDeviceMemoryProperties = (PFN_vkGetPhysicalDeviceMemoryProperties)
        fpGetInstanceProcAddr(*pInstance, "vkGetPhysicalDeviceMemoryProperties");
    g_inst_dispatch.CreateDevice = (PFN_vkCreateDevice)
        fpGetInstanceProcAddr(*pInstance, "vkCreateDevice");
    g_inst_dispatch.GetPhysicalDeviceSurfaceCapabilitiesKHR = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)
        fpGetInstanceProcAddr(*pInstance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    g_inst_dispatch.GetPhysicalDeviceSurfaceFormatsKHR = (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)
        fpGetInstanceProcAddr(*pInstance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    g_inst_dispatch.GetPhysicalDeviceSurfacePresentModesKHR = (PFN_vkGetPhysicalDeviceSurfacePresentModesKHR)
        fpGetInstanceProcAddr(*pInstance, "vkGetPhysicalDeviceSurfacePresentModesKHR");

    LOGI("layer_CreateInstance: instance=%p chain OK", (void*)*pInstance);
    return VK_SUCCESS;
}

/* ========================================================================
 * Layer core: vkCreateDevice
 * ======================================================================== */

static VKAPI_ATTR VkResult VKAPI_CALL layer_CreateDevice(
    VkPhysicalDevice physicalDevice,
    const VkDeviceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkDevice *pDevice)
{
    VkLayerDeviceCreateInfo *layerInfo = get_device_chain_info(pCreateInfo, VK_LAYER_LINK_INFO);
    if (!layerInfo) {
        LOGE("layer_CreateDevice: no layer link info");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    PFN_vkGetInstanceProcAddr fpGetInstanceProcAddr = layerInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr fpGetDeviceProcAddr = layerInfo->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    /* Advance the chain */
    layerInfo->u.pLayerInfo = layerInfo->u.pLayerInfo->pNext;

    PFN_vkCreateDevice fpCreateDevice = (PFN_vkCreateDevice)
        fpGetInstanceProcAddr(g_instance, "vkCreateDevice");
    if (!fpCreateDevice) return VK_ERROR_INITIALIZATION_FAILED;

    /* Inject VK_KHR_external_fence_fd into the enabled device extensions if the
     * app (DXVK) hasn't requested it. vkGetFenceFdKHR (used to export the blit
     * fence as a SYNC_FD for SurfaceFlinger) only works if this extension is
     * enabled. Without it, present_msg goes out with acquireFd=-1 and the
     * release chain has no GPU-completion guarantee — leading to cmd_buf
     * reuse-while-pending and Adreno hangs.
     *
     * Also inject VK_EXT_global_priority so we can request HIGH priority on
     * DXVK's render queue (see queue-priority block below). Turnip on SM8550
     * supports this; without it, DXVK competes equally with SurfaceFlinger's
     * compositor queue in the kernel GPU scheduler. */
    static const char *kExtFenceFd        = "VK_KHR_external_fence_fd";
    static const char *kExtGlobalPriority = "VK_EXT_global_priority";
    VkDeviceCreateInfo modified_ci = *pCreateInfo;
    const char **injected_exts = NULL;
    bool has_fence_fd = false;
    bool has_global_priority = false;
    for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
        const char *name = pCreateInfo->ppEnabledExtensionNames[i];
        if (strcmp(name, kExtFenceFd)        == 0) has_fence_fd        = true;
        if (strcmp(name, kExtGlobalPriority) == 0) has_global_priority = true;
    }
    int extras = (has_fence_fd ? 0 : 1) + (has_global_priority ? 0 : 1);
    if (extras > 0) {
        uint32_t n = pCreateInfo->enabledExtensionCount;
        injected_exts = (const char **)malloc(sizeof(char*) * (n + extras));
        if (injected_exts) {
            uint32_t k = 0;
            for (uint32_t i = 0; i < n; i++) injected_exts[k++] = pCreateInfo->ppEnabledExtensionNames[i];
            if (!has_fence_fd)        { injected_exts[k++] = kExtFenceFd;        LOGI("layer_CreateDevice: injected %s", kExtFenceFd); }
            if (!has_global_priority) { injected_exts[k++] = kExtGlobalPriority; LOGI("layer_CreateDevice: injected %s", kExtGlobalPriority); }
            modified_ci.enabledExtensionCount = k;
            modified_ci.ppEnabledExtensionNames = injected_exts;
        }
    }

    /* === Queue priority injection ===
     *
     * Rewrite the DeviceQueueCreateInfo chain so each requested queue carries
     * VkDeviceQueueGlobalPriorityCreateInfoEXT{ globalPriority = HIGH }.
     *
     * Turnip's queue priority is a kernel-level hint (KGSL adjusts the GPU
     * scheduler weight). With HIGH, DXVK's render submits take precedence
     * over the compositor queue when both are runnable — a measurable win
     * during GPU-bound scenes where SurfaceFlinger and DXVK would otherwise
     * round-robin.
     *
     * We keep the originals around in case CreateDevice rejects the priority
     * struct (older drivers return VK_ERROR_NOT_PERMITTED_EXT when HIGH is
     * requested by a non-system process — we then retry with MEDIUM). */
    VkDeviceQueueCreateInfo *qinfo_copies = NULL;
    VkDeviceQueueGlobalPriorityCreateInfoEXT *qprio_copies = NULL;
    if (has_global_priority || extras > 0) {
        uint32_t qc = pCreateInfo->queueCreateInfoCount;
        qinfo_copies = (VkDeviceQueueCreateInfo*)malloc(sizeof(VkDeviceQueueCreateInfo) * qc);
        qprio_copies = (VkDeviceQueueGlobalPriorityCreateInfoEXT*)
                        malloc(sizeof(VkDeviceQueueGlobalPriorityCreateInfoEXT) * qc);
        if (qinfo_copies && qprio_copies) {
            for (uint32_t i = 0; i < qc; i++) {
                qinfo_copies[i] = pCreateInfo->pQueueCreateInfos[i];
                qprio_copies[i] = (VkDeviceQueueGlobalPriorityCreateInfoEXT){
                    .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT,
                    .pNext = qinfo_copies[i].pNext,  /* chain through existing pNext */
                    .globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_EXT,
                };
                qinfo_copies[i].pNext = &qprio_copies[i];
            }
            modified_ci.pQueueCreateInfos = qinfo_copies;
            LOGI("layer_CreateDevice: requesting HIGH global priority on %u queues", qc);
        }
    }

    VkResult result = fpCreateDevice(physicalDevice, &modified_ci, pAllocator, pDevice);

    /* If HIGH priority was rejected (typical: VK_ERROR_NOT_PERMITTED_EXT for
     * non-system processes), retry with MEDIUM. Still better than default. */
    if (result == VK_ERROR_NOT_PERMITTED_EXT && qprio_copies) {
        for (uint32_t i = 0; i < pCreateInfo->queueCreateInfoCount; i++)
            qprio_copies[i].globalPriority = VK_QUEUE_GLOBAL_PRIORITY_MEDIUM_EXT;
        LOGW("layer_CreateDevice: HIGH priority rejected, retrying with MEDIUM");
        result = fpCreateDevice(physicalDevice, &modified_ci, pAllocator, pDevice);
    }

    if (injected_exts) free(injected_exts);
    if (qinfo_copies)  free(qinfo_copies);
    if (qprio_copies)  free(qprio_copies);

    if (result != VK_SUCCESS) {
        /* Final fallback: try with the original pCreateInfo entirely unchanged. */
        LOGW("layer_CreateDevice: CreateDevice failed (%d), retrying with original CreateInfo", result);
        result = fpCreateDevice(physicalDevice, pCreateInfo, pAllocator, pDevice);
        if (result != VK_SUCCESS) return result;
    }

    g_device = *pDevice;
    g_phys_device = physicalDevice;

    /* Populate device dispatch */
    g_dev_dispatch.GetDeviceProcAddr = fpGetDeviceProcAddr;
    g_dev_dispatch.DestroyDevice = (PFN_vkDestroyDevice)
        fpGetDeviceProcAddr(*pDevice, "vkDestroyDevice");
    g_dev_dispatch.CreateSwapchainKHR = (PFN_vkCreateSwapchainKHR)
        fpGetDeviceProcAddr(*pDevice, "vkCreateSwapchainKHR");
    g_dev_dispatch.DestroySwapchainKHR = (PFN_vkDestroySwapchainKHR)
        fpGetDeviceProcAddr(*pDevice, "vkDestroySwapchainKHR");
    g_dev_dispatch.GetSwapchainImagesKHR = (PFN_vkGetSwapchainImagesKHR)
        fpGetDeviceProcAddr(*pDevice, "vkGetSwapchainImagesKHR");
    g_dev_dispatch.AcquireNextImageKHR = (PFN_vkAcquireNextImageKHR)
        fpGetDeviceProcAddr(*pDevice, "vkAcquireNextImageKHR");
    g_dev_dispatch.QueuePresentKHR = (PFN_vkQueuePresentKHR)
        fpGetDeviceProcAddr(*pDevice, "vkQueuePresentKHR");
    g_dev_dispatch.QueueSubmit = (PFN_vkQueueSubmit)
        fpGetDeviceProcAddr(*pDevice, "vkQueueSubmit");
    g_dev_dispatch.CreateCommandPool = (PFN_vkCreateCommandPool)
        fpGetDeviceProcAddr(*pDevice, "vkCreateCommandPool");
    g_dev_dispatch.AllocateCommandBuffers = (PFN_vkAllocateCommandBuffers)
        fpGetDeviceProcAddr(*pDevice, "vkAllocateCommandBuffers");
    g_dev_dispatch.BeginCommandBuffer = (PFN_vkBeginCommandBuffer)
        fpGetDeviceProcAddr(*pDevice, "vkBeginCommandBuffer");
    g_dev_dispatch.EndCommandBuffer = (PFN_vkEndCommandBuffer)
        fpGetDeviceProcAddr(*pDevice, "vkEndCommandBuffer");
    g_dev_dispatch.CmdCopyImage = (PFN_vkCmdCopyImage)
        fpGetDeviceProcAddr(*pDevice, "vkCmdCopyImage");
    g_dev_dispatch.CmdBlitImage = (PFN_vkCmdBlitImage)
        fpGetDeviceProcAddr(*pDevice, "vkCmdBlitImage");
    g_dev_dispatch.CmdPipelineBarrier = (PFN_vkCmdPipelineBarrier)
        fpGetDeviceProcAddr(*pDevice, "vkCmdPipelineBarrier");
    g_dev_dispatch.CreateImage = (PFN_vkCreateImage)
        fpGetDeviceProcAddr(*pDevice, "vkCreateImage");
    g_dev_dispatch.CreateFence = (PFN_vkCreateFence)
        fpGetDeviceProcAddr(*pDevice, "vkCreateFence");
    g_dev_dispatch.DestroyFence = (PFN_vkDestroyFence)
        fpGetDeviceProcAddr(*pDevice, "vkDestroyFence");
    g_dev_dispatch.WaitForFences = (PFN_vkWaitForFences)
        fpGetDeviceProcAddr(*pDevice, "vkWaitForFences");
    g_dev_dispatch.ResetFences = (PFN_vkResetFences)
        fpGetDeviceProcAddr(*pDevice, "vkResetFences");
    g_dev_dispatch.ResetCommandBuffer = (PFN_vkResetCommandBuffer)
        fpGetDeviceProcAddr(*pDevice, "vkResetCommandBuffer");
    g_dev_dispatch.DestroyCommandPool = (PFN_vkDestroyCommandPool)
        fpGetDeviceProcAddr(*pDevice, "vkDestroyCommandPool");
    g_dev_dispatch.QueueWaitIdle = (PFN_vkQueueWaitIdle)
        fpGetDeviceProcAddr(*pDevice, "vkQueueWaitIdle");
    g_dev_dispatch.WaitForPresentKHR = (PFN_vkWaitForPresentKHR)
        fpGetDeviceProcAddr(*pDevice, "vkWaitForPresentKHR");
    LOGI("layer_CreateDevice: vkWaitForPresentKHR=%s",
         g_dev_dispatch.WaitForPresentKHR ? "loaded (will intercept)" : "not loaded (extension absent)");

    /* Get queue 0 for semaphore signaling in AcquireNextImageKHR */
    {
        PFN_vkGetDeviceQueue getQueue = (PFN_vkGetDeviceQueue)
            fpGetDeviceProcAddr(*pDevice, "vkGetDeviceQueue");
        if (getQueue) {
            getQueue(*pDevice, 0, 0, &g_saved_queue);
        }
    }

    /* Connect to AHB server */
    connect_ahb();

    /* Patch the device dispatch table to ensure vkQueuePresentKHR + vkQueueSubmit
     * route through our layer even when Wine/winevulkan caches function pointers
     * from the device's internal dispatch table (bypassing GetDeviceProcAddr). */
    {
        void **dispatch = *(void***)(*pDevice);
        if (dispatch) {
            void *real_present = (void*)g_dev_dispatch.QueuePresentKHR;
            void *real_submit  = (void*)g_dev_dispatch.QueueSubmit;
            int patched_present = 0;
            int patched_submit  = 0;
            for (int i = 0; i < 512 && !(patched_present && patched_submit); i++) {
                if (!patched_present && dispatch[i] == real_present) {
                    dispatch[i] = (void*)layer_QueuePresentKHR;
                    patched_present = 1;
                    LOGI("layer_CreateDevice: patched dispatch[%d] vkQueuePresentKHR → layer", i);
                }
                if (!patched_submit && dispatch[i] == real_submit) {
                    dispatch[i] = (void*)layer_QueueSubmit;
                    patched_submit = 1;
                    LOGI("layer_CreateDevice: patched dispatch[%d] vkQueueSubmit → layer", i);
                }
            }
            if (!patched_present)
                LOGW("layer_CreateDevice: could not find vkQueuePresentKHR in device dispatch");
            if (!patched_submit)
                LOGW("layer_CreateDevice: could not find vkQueueSubmit in device dispatch");
        }
    }

    LOGI("layer_CreateDevice: device=%p AHB active=%d", (void*)*pDevice, g_ahb_active);
    return VK_SUCCESS;
}

/* ========================================================================
 * GetInstanceProcAddr / GetDeviceProcAddr
 * ======================================================================== */

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetDeviceProcAddr(VkDevice device, const char *pName);

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetInstanceProcAddr(VkInstance instance, const char *pName);

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetDeviceProcAddr(VkDevice device, const char *pName)
{
    if (!pName) return NULL;

    if (strcmp(pName, "vkGetDeviceProcAddr") == 0)      return (PFN_vkVoidFunction)AHBLayer_GetDeviceProcAddr;
    if (strcmp(pName, "vkCreateSwapchainKHR") == 0)     return (PFN_vkVoidFunction)layer_CreateSwapchainKHR;
    if (strcmp(pName, "vkDestroySwapchainKHR") == 0)    return (PFN_vkVoidFunction)layer_DestroySwapchainKHR;
    if (strcmp(pName, "vkGetSwapchainImagesKHR") == 0)  return (PFN_vkVoidFunction)layer_GetSwapchainImagesKHR;
    if (strcmp(pName, "vkAcquireNextImageKHR") == 0)    return (PFN_vkVoidFunction)layer_AcquireNextImageKHR;
    if (strcmp(pName, "vkQueuePresentKHR") == 0)        return (PFN_vkVoidFunction)layer_QueuePresentKHR;
    if (strcmp(pName, "vkQueueSubmit") == 0)            return (PFN_vkVoidFunction)layer_QueueSubmit;
    if (strcmp(pName, "vkWaitForPresentKHR") == 0)      return (PFN_vkVoidFunction)layer_WaitForPresentKHR;

    /* TRANSIENT_ATTACHMENT promotion. Hidden behind an env var so users can
     * disable it if a game misbehaves (set WINLATOR_AHB_NO_TRANSIENT=1).
     * Defaults ON because the heuristic in ahb_should_promote_transient is
     * strict enough that false positives are extremely unlikely. */
    if (strcmp(pName, "vkCreateImage") == 0) {
        static int s_no_transient = -1;
        if (s_no_transient < 0) {
            const char *env = getenv("WINLATOR_AHB_NO_TRANSIENT");
            s_no_transient = (env && env[0] == '1') ? 1 : 0;
            LOGI("AHBLayer: vkCreateImage interception %s",
                 s_no_transient ? "DISABLED (WINLATOR_AHB_NO_TRANSIENT=1)" : "enabled");
        }
        if (!s_no_transient) return (PFN_vkVoidFunction)layer_CreateImage;
    }

    if (g_dev_dispatch.GetDeviceProcAddr)
        return g_dev_dispatch.GetDeviceProcAddr(device, pName);
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetInstanceProcAddr(VkInstance instance, const char *pName)
{
    if (!pName) return NULL;

    if (strcmp(pName, "vkCreateInstance") == 0)          return (PFN_vkVoidFunction)layer_CreateInstance;
    if (strcmp(pName, "vkCreateDevice") == 0)            return (PFN_vkVoidFunction)layer_CreateDevice;
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0)     return (PFN_vkVoidFunction)AHBLayer_GetInstanceProcAddr;
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0)       return (PFN_vkVoidFunction)AHBLayer_GetDeviceProcAddr;

    /* Surface query intercepts DISABLED — let Turnip handle all surface queries
     * so DXVK gets real driver capabilities. We only intercept the final gameplay
     * swapchain creation (the "sniper hook" in layer_CreateSwapchainKHR). */
    /* if (strcmp(pName, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0)  return (PFN_vkVoidFunction)layer_GetPhysicalDeviceSurfaceCapabilitiesKHR; */
    /* if (strcmp(pName, "vkGetPhysicalDeviceSurfaceFormatsKHR") == 0)       return (PFN_vkVoidFunction)layer_GetPhysicalDeviceSurfaceFormatsKHR; */
    /* if (strcmp(pName, "vkGetPhysicalDeviceSurfacePresentModesKHR") == 0)  return (PFN_vkVoidFunction)layer_GetPhysicalDeviceSurfacePresentModesKHR; */

    /* Device-level intercepts also need to be returned from GIPA */
    if (strcmp(pName, "vkCreateSwapchainKHR") == 0)     return (PFN_vkVoidFunction)layer_CreateSwapchainKHR;
    if (strcmp(pName, "vkDestroySwapchainKHR") == 0)    return (PFN_vkVoidFunction)layer_DestroySwapchainKHR;
    if (strcmp(pName, "vkGetSwapchainImagesKHR") == 0)  return (PFN_vkVoidFunction)layer_GetSwapchainImagesKHR;
    if (strcmp(pName, "vkAcquireNextImageKHR") == 0)    return (PFN_vkVoidFunction)layer_AcquireNextImageKHR;
    if (strcmp(pName, "vkQueuePresentKHR") == 0)        return (PFN_vkVoidFunction)layer_QueuePresentKHR;
    if (strcmp(pName, "vkQueueSubmit") == 0)            return (PFN_vkVoidFunction)layer_QueueSubmit;
    if (strcmp(pName, "vkWaitForPresentKHR") == 0)      return (PFN_vkVoidFunction)layer_WaitForPresentKHR;

    if (g_inst_dispatch.GetInstanceProcAddr)
        return g_inst_dispatch.GetInstanceProcAddr(instance, pName);
    return NULL;
}

/* ========================================================================
 * Mandatory layer exports
 * ======================================================================== */

__attribute__((visibility("default")))
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(
    uint32_t *pPropertyCount, VkLayerProperties *pProperties)
{
    if (!pProperties) {
        *pPropertyCount = 1;
        return VK_SUCCESS;
    }
    if (*pPropertyCount < 1) {
        *pPropertyCount = 1;
        return VK_INCOMPLETE;
    }
    *pPropertyCount = 1;
    memset(pProperties, 0, sizeof(*pProperties));
    strncpy(pProperties->layerName, "VK_LAYER_WINLATOR_ahb_direct", VK_MAX_EXTENSION_NAME_SIZE);
    strncpy(pProperties->description, "Winlator AHB Direct Compositing layer", VK_MAX_DESCRIPTION_SIZE);
    pProperties->specVersion = VK_MAKE_VERSION(1, 3, 0);
    pProperties->implementationVersion = 1;
    return VK_SUCCESS;
}

__attribute__((visibility("default")))
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(
    const char *pLayerName, uint32_t *pPropertyCount, VkExtensionProperties *pProperties)
{
    if (pLayerName && strcmp(pLayerName, "VK_LAYER_WINLATOR_ahb_direct") == 0) {
        *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    /* Not our layer, return nothing */
    *pPropertyCount = 0;
    return VK_SUCCESS;
}

/* Named entry points referenced in the JSON manifest */
__attribute__((visibility("default")))
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetInstanceProcAddr_Export(VkInstance instance, const char *pName)
{
    return AHBLayer_GetInstanceProcAddr(instance, pName);
}

__attribute__((visibility("default")))
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
AHBLayer_GetDeviceProcAddr_Export(VkDevice device, const char *pName)
{
    return AHBLayer_GetDeviceProcAddr(device, pName);
}

/* Constructor for diagnostics + one-time env-var reads */
__attribute__((constructor))
static void ahb_layer_ctor(void)
{
    /* Direct-render mode: skip the trojan-blit step, render DXVK output
     * directly into AHB-backed VkImages. See g_direct_render_mode docs. */
    const char *direct_env = getenv("WINLATOR_AHB_DIRECT_RENDER");
    g_direct_render_mode = (direct_env && direct_env[0] == '1');

    LOGI("[CTOR] libahb_layer.so loaded in PID=%d UID=%d direct_render=%s",
         getpid(), getuid(), g_direct_render_mode ? "ON" : "OFF");
}
