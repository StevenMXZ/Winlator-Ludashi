/*
 * frame_synthesis.c — Scaffolding for speculative frame synthesis.
 *
 * SCOPE WARNING: This file is NOT a working frame synthesizer. It is the
 * framework — slot tracking, deadline detection, decision logic — with
 * the actual motion estimation and synthesis compute shader replaced by
 * a TODO. Wiring this to a real motion-estimation pipeline is a
 * multi-week project that requires:
 *
 *   1. A 2-pass compute shader: downscale + block-match (8x8 blocks
 *      with SAD scoring) to produce a coarse motion field.
 *   2. A 1-pass warp shader using the motion field + frames N-1, N-2
 *      to produce frame N via bilinear extrapolation.
 *   3. A heuristic that disables synthesis on "wrong" content: HUD,
 *      menus, paused scenes (no motion = no benefit, just artifacts).
 *
 * Why ship the framework half first:
 *   1. Detects deadline misses correctly without changing behavior.
 *   2. Logs how OFTEN deadlines are missed on real games — without
 *      this telemetry we don't know if synthesis would even help.
 *   3. The shader work happens in a separate compute submit; the
 *      framework here just decides "would synthesize here".
 *
 * When enabled via WINLATOR_AHB_SYN_OBSERVE=1, it counts and logs
 * frames where the deadline was missed by more than 1 vsync — exactly
 * the frames where synthesis would help. The count tells us the
 * potential upside before investing in the shaders.
 */

#include <android/log.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>

#define LOG_TAG "FrameSyn"
#define FSLOG(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int                 g_syn_enabled = -1;
static atomic_uint_fast64_t g_total_frames        = 0;
static atomic_uint_fast64_t g_late_frames         = 0;
static atomic_uint_fast64_t g_very_late_frames    = 0;  /* > 1.5 vsync over budget */

static int frame_syn_active(void) {
    if (g_syn_enabled < 0) {
        const char *e = getenv("WINLATOR_AHB_SYN_OBSERVE");
        g_syn_enabled = (e && e[0] == '1') ? 1 : 0;
        FSLOG("frame_synthesis: %s",
              g_syn_enabled ? "OBSERVE mode (no synthesis yet)" : "disabled");
    }
    return g_syn_enabled;
}

/*
 * frame_syn_record — called from layer_QueuePresentKHR after computing
 * how long this frame took relative to the panel vsync period.
 *
 * actualRenderUs: T1 (DXVK acquire) → T2 (just before vkQueuePresentKHR)
 * vsyncPeriodUs:  the EMA-smoothed value from MSG_VSYNC deltas
 */
void frame_syn_record(uint64_t actualRenderUs, uint64_t vsyncPeriodUs) {
    if (!frame_syn_active() || vsyncPeriodUs == 0) return;

    atomic_fetch_add(&g_total_frames, 1);

    if (actualRenderUs > vsyncPeriodUs) {
        atomic_fetch_add(&g_late_frames, 1);
    }
    if (actualRenderUs > (vsyncPeriodUs * 3) / 2) {
        uint64_t n = atomic_fetch_add(&g_very_late_frames, 1) + 1;
        if (n <= 4 || (n % 60 == 0)) {
            FSLOG("synthesis-candidate %llu: render=%llu us vs vsync=%llu us "
                  "(%.1fx budget)", (unsigned long long)n,
                  (unsigned long long)actualRenderUs,
                  (unsigned long long)vsyncPeriodUs,
                  (double)actualRenderUs / (double)vsyncPeriodUs);
        }
    }

    /* TODO: when synthesis is implemented, decision logic goes here:
     *
     *   if (very_late) {
     *       enqueue compute-shader synthesis using frames N-1, N-2;
     *       present synthesized frame at the missed-vsync boundary;
     *       present real frame N at the next vsync.
     *   }
     */
}
