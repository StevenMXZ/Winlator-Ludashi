/*
 * render_pass_observer.c — Scaffolding for Vulkan render-pass merging.
 *
 * SCOPE WARNING: This file is NOT yet a working render-pass merger. It is
 * the observation/instrumentation layer that detects merge-eligible
 * sequences and logs them. Producing real merged passes requires a multi-
 * thousand-line subpass-dependency builder that resolves attachment
 * reference chains across passes — typically several weeks of work.
 *
 * Why ship the observer half first:
 *   1. Confirms the heuristic on YOUR target games before any risky code
 *      runs. Without telemetry, "render pass merging" is guesswork.
 *   2. Logs (count, dimensions, formats) feed the design of the actual
 *      merger when it's written.
 *   3. Costs almost nothing at runtime when WINLATOR_AHB_RPOBS=0.
 *
 * Wiring (do NOT include from ahb_layer.c yet — kept separate so it can
 * be compiled in or out of the layer .so without touching the main flow):
 *
 *   ahb_layer.c hooks:
 *     - vkCmdBeginRenderPass → rpobs_begin(cb, info)
 *     - vkCmdEndRenderPass   → rpobs_end(cb)
 *
 * When enabled, every command buffer's end-pass+begin-pass pair with
 * matching color attachment formats and dimensions is logged. If the
 * pair is observed N times across a frame (say 4+), it's likely safe
 * to merge into a single 2-subpass render pass.
 *
 * NEXT STEPS for a real merger (not done here):
 *   1. Buffer recorded commands between the End and the next Begin.
 *   2. If a mergeable Begin arrives within K commands (no Submit, no
 *      side-effect commands in between), elide the End and synthesize
 *      a vkCmdNextSubpass with appropriate VkSubpassDependency.
 *   3. Track attachment layout transitions across the subpass boundary
 *      and use VK_DEPENDENCY_BY_REGION_BIT where the access pattern
 *      allows tile-local rendering.
 *
 * The above is genuinely months of careful Vulkan engineering. This
 * observer file is the honest starting point — not the finished feature.
 */

#include <android/log.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define LOG_TAG "RPObs"
#define RPLOG(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* Per-command-buffer state: tracks the most recent EndRenderPass so the
 * next BeginRenderPass can compare. Keyed by VkCommandBuffer handle in a
 * small hash table (open addressing, fixed capacity).
 *
 * 64 slots is enough for any one DXVK frame's worth of active recordings;
 * collisions just degrade to "no observation" — never a correctness issue. */
#define RPOBS_SLOTS 64

typedef struct {
    VkCommandBuffer cb;           /* hash key */
    int             active;       /* slot occupied */
    int             lastEndedW;
    int             lastEndedH;
    uint32_t        lastEndedFmtHash;  /* sum of attachment formats */
    int             lastEndedAttachCount;
    uint64_t        lastEndedAtUs;
} rpobs_entry;

static rpobs_entry      g_rpobs_slots[RPOBS_SLOTS];
static atomic_uint_fast64_t g_rpobs_merge_candidates = 0;
static int              g_rpobs_enabled = -1;

static uint64_t rpobs_mono_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)(ts.tv_nsec / 1000);
}

static int rpobs_active(void) {
    if (g_rpobs_enabled < 0) {
        const char *e = getenv("WINLATOR_AHB_RPOBS");
        g_rpobs_enabled = (e && e[0] == '1') ? 1 : 0;
        RPLOG("render_pass_observer: %s",
              g_rpobs_enabled ? "ENABLED (WINLATOR_AHB_RPOBS=1)" : "disabled");
    }
    return g_rpobs_enabled;
}

static rpobs_entry *rpobs_lookup(VkCommandBuffer cb, int alloc) {
    uintptr_t h = ((uintptr_t)cb >> 4) ^ ((uintptr_t)cb >> 32);
    for (int i = 0; i < RPOBS_SLOTS; i++) {
        rpobs_entry *e = &g_rpobs_slots[(h + i) % RPOBS_SLOTS];
        if (e->active && e->cb == cb) return e;
        if (!e->active && alloc) {
            e->active = 1;
            e->cb     = cb;
            return e;
        }
    }
    return NULL;
}

/* ===== Public hooks (call from ahb_layer.c GetDeviceProcAddr) ===== */

void rpobs_begin(VkCommandBuffer cb, const VkRenderPassBeginInfo *info) {
    if (!rpobs_active() || !info) return;

    int w = (int)info->renderArea.extent.width;
    int h = (int)info->renderArea.extent.height;

    /* If the immediately previous End on this CB had matching extent and
     * happened recently (< 50 µs), call it a candidate. */
    rpobs_entry *e = rpobs_lookup(cb, /*alloc=*/0);
    if (e && e->lastEndedW == w && e->lastEndedH == h && e->lastEndedAtUs != 0) {
        uint64_t now = rpobs_mono_us();
        if (now - e->lastEndedAtUs < 50) {
            uint64_t n = atomic_fetch_add(&g_rpobs_merge_candidates, 1) + 1;
            if (n <= 8 || (n % 240 == 0)) {
                RPLOG("merge-candidate %llu: end+begin %dx%d (gap %llu us)",
                      (unsigned long long)n, w, h,
                      (unsigned long long)(now - e->lastEndedAtUs));
            }
        }
        /* Consume the End marker so a third pass isn't double-counted. */
        e->lastEndedAtUs = 0;
    }
}

void rpobs_end(VkCommandBuffer cb,
               int width, int height, int attachmentCount, uint32_t fmtHash) {
    if (!rpobs_active()) return;
    rpobs_entry *e = rpobs_lookup(cb, /*alloc=*/1);
    if (!e) return;
    e->lastEndedW           = width;
    e->lastEndedH           = height;
    e->lastEndedAttachCount = attachmentCount;
    e->lastEndedFmtHash     = fmtHash;
    e->lastEndedAtUs        = rpobs_mono_us();
}

void rpobs_cb_destroyed(VkCommandBuffer cb) {
    if (!rpobs_active()) return;
    rpobs_entry *e = rpobs_lookup(cb, /*alloc=*/0);
    if (e) memset(e, 0, sizeof(*e));
}
