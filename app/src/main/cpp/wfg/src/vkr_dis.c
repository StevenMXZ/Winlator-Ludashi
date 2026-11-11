// SPDX-FileCopyrightText: Copyright 2026 qwertypower (DEVAR Entertainment LLC)
// SPDX-License-Identifier: MIT
//
// WFG frame generation: a Vulkan compute realisation of Dense Inverse Search
// optical flow. The algorithm and its reference implementation come from
// OpenCV's DISOpticalFlow, which adopted Till Kroeger's original OF_DIS.
// See THIRD_PARTY_NOTICES.txt for attribution.

// vk_dispatch.h first: it declares Vulkan without prototypes, every call goes through wfg_vkd.
#include "vk_dispatch.h"
#include "vkr_dis.h"

#include "shaders/dis_luma_r16_comp.spv.h"
#include "shaders/dis_luma_r32_comp.spv.h"
#include "shaders/dis_gradient_comp.spv.h"
#include "shaders/dis_inverse_search_comp.spv.h"
#include "shaders/dis_propagate_comp.spv.h"
#include "shaders/dis_densify_comp.spv.h"
#include "shaders/dis_interpolate_comp.spv.h"
#include "shaders/dis_hist_comp.spv.h"
#include "shaders/dis_side_comp.spv.h"
#include "shaders/dis_flow_pack_comp.spv.h"
#include "shaders/dis_splat_comp.spv.h"
#include "shaders/dis_fill_comp.spv.h"
#include "shaders/dis_vr_prep_comp.spv.h"
#include "shaders/dis_vr_d1_comp.spv.h"
#include "shaders/dis_vr_d2_comp.spv.h"
#include "shaders/dis_vr_w_comp.spv.h"
#include "shaders/dis_vr_coef_comp.spv.h"
#include "shaders/dis_vr_sor_comp.spv.h"
#include "shaders/dis_vr_add_comp.spv.h"
#include "shaders/dis_fb_poly_comp.spv.h"
#include "shaders/dis_fb_hsum_comp.spv.h"
#include "shaders/dis_fb_vsolve_comp.spv.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <android/log.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

#define DIS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WFG", __VA_ARGS__)
#define DIS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "WFG", __VA_ARGS__)

#define DIS_LOCAL_SIZE 8u
#define DIS_PATCH_STRIDE 3u
#define DIS_MIN_EXTENT 16u

#define DIS_DEFAULT_FLOW_MIN_SIDE 180u

// Flow estimators. DIS: sparse inverse search, densification and variational refinement.
// Farneback: polynomial expansion and a windowed least-squares solve per level (dis_fb_*).
#define DIS_ENGINE_DIS 0
#define DIS_ENGINE_FARNEBACK 1
// Farneback down to level 2, then the DIS patch search (with its prior and static tests) on
// levels 1 and 0 and a shorter variational refinement. Farneback still solves level 1, into
// fb_l1, as the other start of level 0's search: DIS on level 1 catches small fast objects that
// Farneback's windows average away, Farneback follows smooth and fast motion better, and each
// patch of level 0 starts from whichever of the two explains it.
#define DIS_ENGINE_HYBRID 2
// Farneback, tuned on the bench (window in flow texels): 3 iterations a level, a Gaussian
// window of radius 12 and sigma 8, a Cauchy reweighting at mismatch 5, and the 7-tap
// polynomial applicability of OpenCV's default sigma 1.1.
#define DIS_FB_ITERATIONS 3
#define DIS_FB_RADIUS 12
#define DIS_FB_WINDOW_SIGMA 8.0f
#define DIS_FB_POLY_SIGMA 1.1f
#define DIS_FB_POLY_N 3
#define DIS_FB_ROBUST 5.0f
// The hybrid's refinement, on level 0 only (fb_hybrid_vr_upper): at most 2 fixed-point passes
// of 2 SOR sweeps (the x4 path of DIS runs 4 of 7), which pays for the level-1 search. Against
// Farneback to level 1 with DIS on level 0 and a 2x4 refinement: 7% less flow time on Adreno 750
// (2.36 against 2.54 ms a pair) and 22.98 dB against 22.90 on the bench, small fast objects 29.1
// against 27.3 (DIS alone: 22.87 and 29.9 at 5.5 ms). Refining level 1 too, or a third sweep,
// measured within 0.01 dB of this.
#define DIS_HYB_FIXED_POINT 2u
#define DIS_HYB_SOR 2u
#define DIS_FLOW_MIN_SIDE_FLOOR 64u
#define DIS_FLOW_MIN_SIDE_CEIL 1080u
#define DIS_MAX_LEVELS 8u
#define DIS_MAX_DESCRIPTOR_WRITES 256u

#define DIS_SLOTS 3u

#define DIS_PROP_STEPS_MAX 4u

#define DIS_SRC_SMOOTHING 0.08f
#define DIS_SRC_STALE_NS 500000000ull
#define DIS_MIN_RATE_SAMPLES 12u

#define DIS_PLAN_LOG_NS 5000000000ull

#define DIS_RATIO_SLACK 0.12f

#define DIS_MIN_GEN_RATIO 1.45f

#define DIS_RATIO_HYST 0.10f

// Source rate below which frame generation stops, and above which it starts again.
#define DIS_MIN_SOURCE_FPS_OFF 20.0f
#define DIS_MIN_SOURCE_FPS_ON 24.0f

#define DIS_VR_ALPHA 20.0f
#define DIS_VR_DELTA 5.0f
#define DIS_VR_GAMMA 10.0f
#define DIS_VR_OMEGA 1.6f
#define DIS_VR_ZETA 0.1f
#define DIS_VR_EPS 0.001f

// Fewest SOR sweeps a level that still runs the solver gets.
#define DIS_VR_SOR_FLOOR 2u

// The variational refinement runs on the finest levels only. Above the third,
// the refined flow is upsampled into the next search anyway, so refining the
// coarse levels bought very little while their prep/add dispatches and solver
// sweeps dominated the pass count. Levels without refinement skip the VR stage
// entirely and the search reads the densified flow instead.
//
// Restored to every level: fast motion showed the coarse levels were carrying
// more of the flow than the dispatch saving was worth, and the refinement has
// to be there for large displacements to come out of the pyramid cleanly.
#define DIS_VR_LEVELS 8u


#define DIS_SET_SAMPLERS 7u
#define DIS_SET_STORAGE 1u
#define DIS_SHARED_SETS_PER_LEVEL 6u
#define DIS_VR_SHARED_SETS 7u
#define DIS_VR_SAMPLER_BINDINGS 8u
#define DIS_VR_STORAGE_BINDINGS 2u
#define DIS_VR_FIRST_STORAGE 8u

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkExtent2D extent;
    VkFormat format;
    uint32_t mip_levels;
} DisImage;

typedef struct {
    VkPipeline pipeline;
} DisPass;

typedef struct {
    VkWriteDescriptorSet w[DIS_MAX_DESCRIPTOR_WRITES];
    VkDescriptorImageInfo img[DIS_MAX_DESCRIPTOR_WRITES];
    uint32_t count;
} DisBatch;

struct VkrDis {
    VkDevice device;
    VkPhysicalDevice physical_device;
    VkPhysicalDeviceMemoryProperties mem_props;

    uint32_t flow_min_side;
    uint32_t target_fps;
    float refresh_rate;

    VkExtent2D built_extent;
    VkExtent2D built_full_extent;
    VkrDisContentRect content;
    uint32_t built_min_side;
    VkFormat built_format;
    uint32_t levels;
    bool built;
    bool unavailable;
    bool layouts_primed;
    bool formats_audited;
    bool manual_flow_filter;
    bool debug_flow;
    bool direct_output;

    DisImage color[DIS_SLOTS];
    DisImage flow_color[DIS_SLOTS];
    DisImage grad;
    DisImage flow_luma[DIS_SLOTS];
    VkFormat luma_format;
    DisImage flow_sparse[DIS_MAX_LEVELS];
    DisImage flow_sparse_b[DIS_MAX_LEVELS];
    DisImage flow_dense;
    DisImage interp_out;

    DisImage vr_prep;
    DisImage vr_d1;
    DisImage vr_d2;
    DisImage vr_A;
    DisImage vr_B;
    DisImage vr_wt;
    DisImage vr_dw[2];
    DisImage flow_refined;
    DisImage hist[2];
    DisImage side;
    DisImage flow_out;
    DisImage splat_key;
    DisImage fill_cons;
    DisImage flow_fill;
    DisImage flow_prev;
    DisImage splat_field;

    // Farneback flow (DIS_ENGINE_FARNEBACK and _HYBRID): each frame's polynomial expansion,
    // one per slot with a mip per level, and the horizontally summed equations of a level.
    DisImage fb_b[DIS_SLOTS];
    DisImage fb_c[DIS_SLOTS];
    DisImage fb_sg;
    DisImage fb_sh;
    // The hybrid's Farneback level 1 when DIS searches level 1 too: a start for level 0's search.
    DisImage fb_l1;

    VkImageView view_color[DIS_SLOTS];
    VkImageView view_flow_color[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_flow_luma[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_grad[DIS_MAX_LEVELS];
    VkImageView view_sparse[DIS_MAX_LEVELS];
    VkImageView view_sparse_b[DIS_MAX_LEVELS];
    VkImageView view_dense[DIS_MAX_LEVELS];
    VkImageView view_interp_out;
    VkImageView view_vr_prep[DIS_MAX_LEVELS];
    VkImageView view_vr_d1[DIS_MAX_LEVELS];
    VkImageView view_vr_d2[DIS_MAX_LEVELS];
    VkImageView view_vr_A[DIS_MAX_LEVELS];
    VkImageView view_vr_B[DIS_MAX_LEVELS];
    VkImageView view_vr_wt[DIS_MAX_LEVELS];
    VkImageView view_vr_dw[2][DIS_MAX_LEVELS];
    VkImageView view_flow_refined[DIS_MAX_LEVELS];
    VkImageView view_hist[2];
    VkImageView view_side;
    VkImageView view_flow_out;
    VkImageView view_splat_key;
    VkImageView view_fill_cons;
    VkImageView view_flow_fill;
    VkImageView view_flow_prev;
    VkImageView view_splat_field;
    VkImageView view_fb_b[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_fb_c[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_fb_sg[DIS_MAX_LEVELS];
    VkImageView view_fb_sh[DIS_MAX_LEVELS];
    VkImageView view_fb_l1;

    VkSampler sampler;

    VkDescriptorSetLayout set_layout;
    VkPipelineLayout pipeline_layout;
    VkDescriptorPool pool;
    VkDescriptorSet luma_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet grad_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet inverse_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet densify_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet prop_ab_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet prop_ba_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet interp_sets[DIS_SLOTS][2][VKR_DIS_MAX_GENERATIONS];
    VkImageView interp_targets[DIS_SLOTS][2][VKR_DIS_MAX_GENERATIONS];
    VkDescriptorSet hist_sets[DIS_SLOTS][2];
    VkDescriptorSet side_sets[DIS_SLOTS];
    VkDescriptorSet pack_set;
    VkDescriptorSet splat_sets[DIS_SLOTS];
    VkDescriptorSet fill_sets[DIS_SLOTS][2];   // per overlay history direction
    // Farneback, all from vr_set_layout: the expansion of slot s at level l; the equations of
    // the pair ending in slot s at level l, starting from the level above ([0]) or from this
    // level's previous iteration ([1]); and the solve of level l.
    VkDescriptorSet fb_poly_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet fb_hsum_sets[DIS_SLOTS][DIS_MAX_LEVELS][2];
    VkDescriptorSet fb_vsolve_sets[DIS_MAX_LEVELS];
    // Farneback's level 1 into fb_l1: later iterations start from it, the solve writes it.
    VkDescriptorSet fb_hsum1_sets[DIS_SLOTS];
    VkDescriptorSet fb_vsolve1_set;

    VkDescriptorSetLayout vr_set_layout;
    VkPipelineLayout vr_pipeline_layout;
    VkDescriptorSet vr_prep_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet vr_d1_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_d2_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_w_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_coef_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_sor_ab_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_sor_ba_set[DIS_MAX_LEVELS];
    VkDescriptorSet vr_add_set[DIS_MAX_LEVELS];

    DisPass pass_luma;
    DisPass pass_gradient;
    DisPass pass_inverse;
    DisPass pass_propagate;
    DisPass pass_densify;
    DisPass pass_interp;
    DisPass pass_hist;
    DisPass pass_fb_poly;
    DisPass pass_fb_hsum;
    DisPass pass_fb_vsolve;
    DisPass pass_side;
    DisPass pass_pack;
    DisPass pass_splat;
    DisPass pass_fill;
    DisPass pass_vr_prep;
    DisPass pass_vr_d1;
    DisPass pass_vr_d2;
    DisPass pass_vr_w;
    DisPass pass_vr_coef;
    DisPass pass_vr_sor;
    DisPass pass_vr_add;

    uint64_t frame_count;
    int prev_idx;
    int next_idx;
    uint32_t active_slot;
    uint32_t last_generations;

    uint64_t src_sample_ns;
    uint64_t src_last_frames;
    float src_frame_accum;
    float src_time_accum;
    float src_interval;
    uint32_t src_samples;

    float smoothed_desired;

    int planned_gen;
    uint32_t gen_high_streak;
    uint32_t gen_low_streak;

    uint64_t plan_log_ns;
    int plan_log_gen;

    uint32_t hist_parity;
    bool hist_valid;
    // flow_out holds the flow of the pair just before the one being computed, so the search can
    // start from it; false after a reset, a rebuild or a frame whose flow was not computed.
    bool prior_valid;

    // Which estimator produces the flow; see DIS_ENGINE_*. The Farneback parameters, and the
    // four distinct entries of its inverted applicability moments.
    int flow_engine;
    int fb_iterations;
    int fb_radius;
    float fb_window_sigma;
    float fb_poly_sigma;
    float fb_robust;
    bool fb_hybrid_vr;       // DIS_ENGINE_HYBRID: refine its DIS levels variationally
    uint32_t fb_hybrid_level; // DIS_ENGINE_HYBRID: the first level left to Farneback
    uint32_t fb_hybrid_fp;    // DIS_ENGINE_HYBRID: cap on refinement fixed-point passes (0: none)
    uint32_t fb_hybrid_sor;   // DIS_ENGINE_HYBRID: cap on SOR sweeps per pass (0: none)
    bool fb_hybrid_l1_start;  // DIS_ENGINE_HYBRID on two DIS levels: Farneback's level 1 as a start
    bool fb_hybrid_vr_upper;  // DIS_ENGINE_HYBRID: refine the DIS levels above 0 as well
    float fb_ig[4];
};

typedef struct {
    float lesser;
    float upper;
    float normVal;
} DisGradientPC;

typedef struct {
    int level;
    int coarseLevel;
    int priorLevels;
    int fbLevels;
} DisInversePC;

typedef struct {
    float sigma;
    float ig11;
    float ig03;
    float ig33;
    float ig55;
} DisFbPolyPC;

typedef struct {
    int radius;
    float sigma;
    int useFlow;
    float robust;
} DisFbSumPC;

typedef struct {
    int dist;
} DisPropPC;

typedef struct {
    float t;
    int debugMode;
    int layer;
} DisInterpPC;

typedef struct {
    float alpha2;
    float eps2;
} DisVrWPC;

typedef struct {
    float delta2;
    float gamma2;
    float zeta2;
    float eps2;
} DisVrCoefPC;

typedef struct {
    float omega;
    int parity;
} DisVrSorPC;


static uint64_t dis_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static uint32_t dis_find_memory_type(VkrDis* d, uint32_t bits, VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < d->mem_props.memoryTypeCount; i++) {
        if ((bits & (1u << i)) &&
            (d->mem_props.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

// Per-stage GPU timestamps for the bench (tools/dis-bench); compiled out of the app.
#ifdef DIS_PROFILE
#define DIS_PROF_MAX 64u
static struct {
    VkQueryPool pool;
    PFN_vkCmdWriteTimestamp write;
    PFN_vkCmdResetQueryPool reset;
    PFN_vkGetQueryPoolResults get;
    float period_ns;
    const char* label[DIS_PROF_MAX];
    uint32_t n;
    const char* acc_label[DIS_PROF_MAX];
    double acc_ms[DIS_PROF_MAX];
    uint32_t acc_n;
    uint64_t frames;
} g_prof;

static void dis_prof_mark(VkCommandBuffer cmd, const char* label) {
    if (!g_prof.pool || g_prof.n >= DIS_PROF_MAX) return;
    g_prof.write(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, g_prof.pool, g_prof.n);
    g_prof.label[g_prof.n++] = label;
}

static void dis_prof_begin(VkrDis* d, VkCommandBuffer cmd) {
    if (!g_prof.pool) {
        g_prof.write = (PFN_vkCmdWriteTimestamp)wfg_vkd.GetDeviceProcAddr(d->device, "vkCmdWriteTimestamp");
        g_prof.reset = (PFN_vkCmdResetQueryPool)wfg_vkd.GetDeviceProcAddr(d->device, "vkCmdResetQueryPool");
        g_prof.get = (PFN_vkGetQueryPoolResults)wfg_vkd.GetDeviceProcAddr(d->device, "vkGetQueryPoolResults");
        PFN_vkCreateQueryPool create =
            (PFN_vkCreateQueryPool)wfg_vkd.GetDeviceProcAddr(d->device, "vkCreateQueryPool");
        VkPhysicalDeviceProperties props;
        wfg_vkd.GetPhysicalDeviceProperties(d->physical_device, &props);
        g_prof.period_ns = props.limits.timestampPeriod;
        VkQueryPoolCreateInfo qi;
        memset(&qi, 0, sizeof(qi));
        qi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qi.queryCount = DIS_PROF_MAX;
        if (!create || create(d->device, &qi, NULL, &g_prof.pool) != VK_SUCCESS) return;
    }
    g_prof.reset(cmd, g_prof.pool, 0, DIS_PROF_MAX);
    g_prof.n = 0;
    dis_prof_mark(cmd, "start");
}

// Call once the frame's last submit has completed.
void vkr_dis_profile_collect(VkrDis* d) {
    if (!g_prof.pool || g_prof.n < 2) return;
    uint64_t ts[DIS_PROF_MAX];
    if (g_prof.get(d->device, g_prof.pool, 0, g_prof.n, sizeof(ts), ts, sizeof(uint64_t),
                   VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) != VK_SUCCESS) return;
    for (uint32_t i = 1; i < g_prof.n; i++) {
        uint32_t k = 0;
        while (k < g_prof.acc_n && strcmp(g_prof.acc_label[k], g_prof.label[i]) != 0) k++;
        if (k == g_prof.acc_n) {
            if (g_prof.acc_n >= DIS_PROF_MAX) continue;
            g_prof.acc_label[g_prof.acc_n] = g_prof.label[i];
            g_prof.acc_ms[g_prof.acc_n++] = 0.0;
        }
        g_prof.acc_ms[k] += (double)(ts[i] - ts[i - 1]) * g_prof.period_ns * 1e-6;
    }
    g_prof.frames++;
    g_prof.n = 0;
}

void vkr_dis_profile_report(VkrDis* d) {
    (void)d;
    if (!g_prof.frames) return;
    double total = 0;
    for (uint32_t k = 0; k < g_prof.acc_n; k++) total += g_prof.acc_ms[k];
    printf("PROFILE over %llu frames (ms per real frame):\n", (unsigned long long)g_prof.frames);
    for (uint32_t k = 0; k < g_prof.acc_n; k++) {
        printf("  %-18s %7.3f\n", g_prof.acc_label[k], g_prof.acc_ms[k] / (double)g_prof.frames);
    }
    printf("  %-18s %7.3f\n", "total", total / (double)g_prof.frames);
    memset(g_prof.acc_ms, 0, sizeof(g_prof.acc_ms));
    g_prof.acc_n = 0;
    g_prof.frames = 0;
}
#define DIS_PROF_BEGIN(d, cmd) dis_prof_begin((d), (cmd))
#define DIS_PROF(cmd, label) dis_prof_mark((cmd), (label))
#else
#define DIS_PROF_BEGIN(d, cmd) ((void)0)
#define DIS_PROF(cmd, label) ((void)0)
#endif

static void dis_compute_barrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb;
    memset(&mb, 0, sizeof(mb));
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    wfg_vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
}

static void dis_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
                        VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                        VkAccessFlags src_access, VkAccessFlags dst_access) {
    VkImageMemoryBarrier b;
    memset(&b, 0, sizeof(b));
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
    b.subresourceRange.layerCount = 1;
    wfg_vkd.CmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

static uint32_t dis_collect_images(VkrDis* d, DisImage** out, uint32_t cap) {
    uint32_t n = 0;
    #define DIS_PUSH(img) do { if (n < cap) out[n++] = (img); } while (0)
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        DIS_PUSH(&d->color[s]);
        DIS_PUSH(&d->flow_color[s]);
        DIS_PUSH(&d->flow_luma[s]);
    }
    DIS_PUSH(&d->grad);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        DIS_PUSH(&d->flow_sparse[l]);
        DIS_PUSH(&d->flow_sparse_b[l]);
    }
    DIS_PUSH(&d->flow_dense);
    DIS_PUSH(&d->interp_out);
    DIS_PUSH(&d->vr_prep);
    DIS_PUSH(&d->vr_d1);
    DIS_PUSH(&d->vr_d2);
    DIS_PUSH(&d->vr_A);
    DIS_PUSH(&d->vr_B);
    DIS_PUSH(&d->vr_wt);
    DIS_PUSH(&d->vr_dw[0]);
    DIS_PUSH(&d->vr_dw[1]);
    DIS_PUSH(&d->flow_refined);
    DIS_PUSH(&d->hist[0]);
    DIS_PUSH(&d->hist[1]);
    DIS_PUSH(&d->side);
    DIS_PUSH(&d->flow_out);
    DIS_PUSH(&d->splat_key);
    DIS_PUSH(&d->fill_cons);
    DIS_PUSH(&d->flow_fill);
    DIS_PUSH(&d->flow_prev);
    DIS_PUSH(&d->splat_field);
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        DIS_PUSH(&d->fb_b[s]);
        DIS_PUSH(&d->fb_c[s]);
    }
    DIS_PUSH(&d->fb_sg);
    DIS_PUSH(&d->fb_sh);
    DIS_PUSH(&d->fb_l1);
    #undef DIS_PUSH
    return n;
}

#define DIS_MAX_OWNED_IMAGES 64u

static void dis_prime_layouts(VkrDis* d, VkCommandBuffer cmd) {
    if (d->layouts_primed) return;

    DisImage* imgs[DIS_MAX_OWNED_IMAGES];
    const uint32_t n = dis_collect_images(d, imgs, DIS_MAX_OWNED_IMAGES);

    VkImageMemoryBarrier bars[DIS_MAX_OWNED_IMAGES];
    uint32_t count = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (!imgs[i]->image) continue;
        VkImageMemoryBarrier* b = &bars[count++];
        memset(b, 0, sizeof(*b));
        b->sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b->srcAccessMask = 0;
        b->dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                           VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        b->oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b->newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b->srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b->dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b->image = imgs[i]->image;
        b->subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b->subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
        b->subresourceRange.layerCount = 1;
    }
    if (count == 0) return;

    wfg_vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, NULL, 0, NULL, count, bars);
    d->layouts_primed = true;
}

static VkFormat dis_pick_luma_format(VkrDis* d) {
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                      VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                      VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    const VkFormat candidates[2] = {VK_FORMAT_R16_SFLOAT, VK_FORMAT_R32_SFLOAT};
    for (uint32_t i = 0; i < 2; i++) {
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        wfg_vkd.GetPhysicalDeviceFormatProperties(d->physical_device, candidates[i], &fp);
        if ((fp.optimalTilingFeatures & need) == need) {
            if (i != 0) DIS_LOGI("R16F unusable for the luminance plane; using R32F");
            return candidates[i];
        }
    }
    return VK_FORMAT_UNDEFINED;
}

static bool dis_create_image(VkrDis* d, DisImage* out, uint32_t w, uint32_t h, VkFormat format,
                             uint32_t mip_levels, VkImageUsageFlags usage) {
    memset(out, 0, sizeof(*out));
    out->extent.width = w;
    out->extent.height = h;
    out->format = format;
    out->mip_levels = mip_levels;

    VkImageCreateInfo ic;
    memset(&ic, 0, sizeof(ic));
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = format;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = mip_levels;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = usage;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (wfg_vkd.CreateImage(d->device, &ic, NULL, &out->image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    wfg_vkd.GetImageMemoryRequirements(d->device, out->image, &mr);
    uint32_t type = dis_find_memory_type(d, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo ai;
    memset(&ai, 0, sizeof(ai));
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = type;
    if (wfg_vkd.AllocateMemory(d->device, &ai, NULL, &out->memory) != VK_SUCCESS) return false;
    wfg_vkd.BindImageMemory(d->device, out->image, out->memory, 0);
    return true;
}

static void dis_destroy_image(VkrDis* d, DisImage* img) {
    if (img->image) wfg_vkd.DestroyImage(d->device, img->image, NULL);
    if (img->memory) wfg_vkd.FreeMemory(d->device, img->memory, NULL);
    memset(img, 0, sizeof(*img));
}

static bool dis_create_view(VkrDis* d, VkImage image, VkFormat format, uint32_t base_level,
                            uint32_t level_count, VkImageView* out) {
    VkImageViewCreateInfo vi;
    memset(&vi, 0, sizeof(vi));
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.baseMipLevel = base_level;
    vi.subresourceRange.levelCount = level_count;
    vi.subresourceRange.layerCount = 1;
    return wfg_vkd.CreateImageView(d->device, &vi, NULL, out) == VK_SUCCESS;
}

static void dis_destroy_view(VkrDis* d, VkImageView* view) {
    if (*view) wfg_vkd.DestroyImageView(d->device, *view, NULL);
    *view = VK_NULL_HANDLE;
}

static VkPipeline dis_create_compute_pipeline_with_layout(VkrDis* d, const uint32_t* code,
                                                           size_t code_size, VkPipelineLayout layout,
                                                           const VkSpecializationInfo* spec) {
    VkShaderModuleCreateInfo smi;
    memset(&smi, 0, sizeof(smi));
    smi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smi.codeSize = code_size;
    smi.pCode = code;
    VkShaderModule sm;
    if (wfg_vkd.CreateShaderModule(d->device, &smi, NULL, &sm) != VK_SUCCESS) return VK_NULL_HANDLE;

    VkPipelineShaderStageCreateInfo stage;
    memset(&stage, 0, sizeof(stage));
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = sm;
    stage.pName = "main";
    stage.pSpecializationInfo = spec;

    VkComputePipelineCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pci.stage = stage;
    pci.layout = layout;

    VkPipeline pipeline;
    VkResult res = wfg_vkd.CreateComputePipelines(d->device, VK_NULL_HANDLE, 1, &pci, NULL, &pipeline);
    wfg_vkd.DestroyShaderModule(d->device, sm, NULL);
    return res == VK_SUCCESS ? pipeline : VK_NULL_HANDLE;
}

static VkPipeline dis_create_compute_pipeline(VkrDis* d, const uint32_t* code, size_t code_size) {
    return dis_create_compute_pipeline_with_layout(d, code, code_size, d->pipeline_layout, NULL);
}

static bool dis_create_pipelines(VkrDis* d) {
    // Samplers at 0-4, 6 and 7 (6 and 7 are only the search's previous-pair flow and the
    // hybrid's Farneback field), the storage image at 5.
    VkDescriptorSetLayoutBinding bindings[8];
    memset(bindings, 0, sizeof(bindings));
    for (uint32_t i = 0; i < 8; i++) {
        bindings[i].binding = i;
        bindings[i].descriptorType = i == 5 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                            : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo li;
    memset(&li, 0, sizeof(li));
    li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    li.bindingCount = 8;
    li.pBindings = bindings;
    if (wfg_vkd.CreateDescriptorSetLayout(d->device, &li, NULL, &d->set_layout) != VK_SUCCESS) {
        return false;
    }

    VkPushConstantRange pcr;
    memset(&pcr, 0, sizeof(pcr));
    pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcr.offset = 0;
    pcr.size = 32;

    VkPipelineLayoutCreateInfo pli;
    memset(&pli, 0, sizeof(pli));
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &d->set_layout;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    if (wfg_vkd.CreatePipelineLayout(d->device, &pli, NULL, &d->pipeline_layout) != VK_SUCCESS) {
        return false;
    }

    const uint32_t shared_sets = DIS_SLOTS * DIS_MAX_LEVELS * DIS_SHARED_SETS_PER_LEVEL
                               + DIS_SLOTS * 2u * VKR_DIS_MAX_GENERATIONS // independent output bindings
                               + DIS_SLOTS        // side-map sets
                               + 1u;              // flow pack set
    // VR sets exist only for the levels the refinement actually runs on.
    const uint32_t vr_sets = (DIS_SLOTS
                           + DIS_VR_SHARED_SETS) * DIS_VR_LEVELS;
    const uint32_t hist_sets = DIS_SLOTS * 2u + DIS_SLOTS + DIS_SLOTS * 2u + 1u;   // + splat, fill, pack
    const uint32_t fb_sets = DIS_SLOTS * DIS_MAX_LEVELS * 3u + DIS_MAX_LEVELS + DIS_SLOTS + 1u;
    const uint32_t vr_layout_sets = vr_sets + hist_sets + fb_sets;
    const uint32_t total_sets = shared_sets + vr_layout_sets;

    // The history sets are allocated from vr_set_layout, so they consume the full
    // VR footprint (8 samplers + 2 storage) per set, not just the bindings they
    // write. Counting them short here fails vkAllocateDescriptorSets with
    // VK_ERROR_OUT_OF_POOL_MEMORY, which disables DIS entirely.
    VkDescriptorPoolSize sizes[2];
    memset(sizes, 0, sizeof(sizes));
    sizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sizes[0].descriptorCount = shared_sets * DIS_SET_SAMPLERS
                             + vr_layout_sets * DIS_VR_SAMPLER_BINDINGS;
    sizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    sizes[1].descriptorCount = shared_sets * DIS_SET_STORAGE
                             + vr_layout_sets * DIS_VR_STORAGE_BINDINGS;
    VkDescriptorPoolCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pci.maxSets = total_sets;
    pci.poolSizeCount = 2;
    pci.pPoolSizes = sizes;
    if (wfg_vkd.CreateDescriptorPool(d->device, &pci, NULL, &d->pool) != VK_SUCCESS) {
        return false;
    }

    VkDescriptorSetLayoutBinding vr_bindings[DIS_VR_SAMPLER_BINDINGS + DIS_VR_STORAGE_BINDINGS];
    memset(vr_bindings, 0, sizeof(vr_bindings));
    for (uint32_t i = 0; i < DIS_VR_SAMPLER_BINDINGS; i++) {
        vr_bindings[i].binding = i;
        vr_bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        vr_bindings[i].descriptorCount = 1;
        vr_bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    for (uint32_t i = 0; i < DIS_VR_STORAGE_BINDINGS; i++) {
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].binding = DIS_VR_FIRST_STORAGE + i;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].descriptorCount = 1;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo vr_li;
    memset(&vr_li, 0, sizeof(vr_li));
    vr_li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    vr_li.bindingCount = DIS_VR_SAMPLER_BINDINGS + DIS_VR_STORAGE_BINDINGS;
    vr_li.pBindings = vr_bindings;
    if (wfg_vkd.CreateDescriptorSetLayout(d->device, &vr_li, NULL, &d->vr_set_layout) != VK_SUCCESS) {
        return false;
    }

    VkPushConstantRange vr_pcr;
    memset(&vr_pcr, 0, sizeof(vr_pcr));
    vr_pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    vr_pcr.offset = 0;
    vr_pcr.size = 32;

    VkPipelineLayoutCreateInfo vr_pli;
    memset(&vr_pli, 0, sizeof(vr_pli));
    vr_pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    vr_pli.setLayoutCount = 1;
    vr_pli.pSetLayouts = &d->vr_set_layout;
    vr_pli.pushConstantRangeCount = 1;
    vr_pli.pPushConstantRanges = &vr_pcr;
    if (wfg_vkd.CreatePipelineLayout(d->device, &vr_pli, NULL, &d->vr_pipeline_layout) != VK_SUCCESS) {
        return false;
    }

    d->pass_luma.pipeline = d->luma_format == VK_FORMAT_R16_SFLOAT
        ? dis_create_compute_pipeline(d, dis_luma_r16_comp, dis_luma_r16_comp_size)
        : dis_create_compute_pipeline(d, dis_luma_r32_comp, dis_luma_r32_comp_size);
    d->pass_gradient.pipeline = dis_create_compute_pipeline(d, dis_gradient_comp, dis_gradient_comp_size);
    d->pass_inverse.pipeline = dis_create_compute_pipeline(d, dis_inverse_search_comp, dis_inverse_search_comp_size);
    d->pass_propagate.pipeline = dis_create_compute_pipeline(d, dis_propagate_comp, dis_propagate_comp_size);
    d->pass_densify.pipeline = dis_create_compute_pipeline(d, dis_densify_comp, dis_densify_comp_size);
    const VkBool32 manual_filter = d->manual_flow_filter ? 1u : 0u;
    VkSpecializationMapEntry spec_entry;
    memset(&spec_entry, 0, sizeof(spec_entry));
    spec_entry.constantID = 0;
    spec_entry.offset = 0;
    spec_entry.size = sizeof(manual_filter);
    VkSpecializationInfo spec;
    memset(&spec, 0, sizeof(spec));
    spec.mapEntryCount = 1;
    spec.pMapEntries = &spec_entry;
    spec.dataSize = sizeof(manual_filter);
    spec.pData = &manual_filter;
    d->pass_interp.pipeline = dis_create_compute_pipeline_with_layout(
        d, dis_interpolate_comp, dis_interpolate_comp_size, d->pipeline_layout, &spec);

    d->pass_vr_prep.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_prep_comp, dis_vr_prep_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_d1.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_d1_comp, dis_vr_d1_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_d2.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_d2_comp, dis_vr_d2_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_w.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_w_comp, dis_vr_w_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_coef.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_coef_comp, dis_vr_coef_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_sor.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_sor_comp, dis_vr_sor_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_add.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_add_comp, dis_vr_add_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_hist.pipeline = dis_create_compute_pipeline_with_layout(d, dis_hist_comp, dis_hist_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_fb_poly.pipeline = dis_create_compute_pipeline_with_layout(d, dis_fb_poly_comp, dis_fb_poly_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_fb_hsum.pipeline = dis_create_compute_pipeline_with_layout(d, dis_fb_hsum_comp, dis_fb_hsum_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_fb_vsolve.pipeline = dis_create_compute_pipeline_with_layout(d, dis_fb_vsolve_comp, dis_fb_vsolve_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_side.pipeline = dis_create_compute_pipeline(d, dis_side_comp, dis_side_comp_size);
    d->pass_pack.pipeline = dis_create_compute_pipeline_with_layout(d, dis_flow_pack_comp, dis_flow_pack_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_splat.pipeline = dis_create_compute_pipeline_with_layout(d, dis_splat_comp, dis_splat_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_fill.pipeline = dis_create_compute_pipeline_with_layout(d, dis_fill_comp, dis_fill_comp_size, d->vr_pipeline_layout, NULL);

    if (!d->pass_gradient.pipeline || !d->pass_inverse.pipeline || !d->pass_propagate.pipeline ||
        !d->pass_densify.pipeline || !d->pass_interp.pipeline ||
        !d->pass_vr_prep.pipeline || !d->pass_vr_d1.pipeline || !d->pass_vr_d2.pipeline ||
        !d->pass_vr_w.pipeline || !d->pass_vr_coef.pipeline || !d->pass_vr_sor.pipeline ||
        !d->pass_vr_add.pipeline || !d->pass_hist.pipeline || !d->pass_side.pipeline ||
        !d->pass_pack.pipeline || !d->pass_splat.pipeline || !d->pass_fill.pipeline ||
        !d->pass_fb_poly.pipeline || !d->pass_fb_hsum.pipeline || !d->pass_fb_vsolve.pipeline) {
        return false;
    }
    return true;
}

static bool dis_create_sampler(VkrDis* d) {
    VkSamplerCreateInfo si;
    memset(&si, 0, sizeof(si));
    si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    si.magFilter = VK_FILTER_LINEAR;
    si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.minLod = 0.0f;
    si.maxLod = (float)DIS_MAX_LEVELS;
    return wfg_vkd.CreateSampler(d->device, &si, NULL, &d->sampler) == VK_SUCCESS;
}

static void dis_flow_extent(uint32_t min_side, uint32_t content_w, uint32_t content_h,
                            uint32_t* out_w, uint32_t* out_h) {
    const uint32_t minor = content_w < content_h ? content_w : content_h;
    if (minor == 0 || min_side == 0 || min_side >= minor) {
        *out_w = content_w;
        *out_h = content_h;
        return;
    }
    const double k = (double)min_side / (double)minor;
    *out_w = (uint32_t)((double)content_w * k + 0.5);
    *out_h = (uint32_t)((double)content_h * k + 0.5);
}

static uint32_t dis_levels_for(uint32_t w, uint32_t h) {
    uint32_t levels = 1;
    while ((w >> levels) >= 16 && (h >> levels) >= 16 && levels < DIS_MAX_LEVELS) {
        levels++;
    }
    return levels;
}

static uint32_t dis_sparse_extent(uint32_t extent) {
    return extent > 8u ? 1u + (extent - 8u) / DIS_PATCH_STRIDE : 1u;
}

// The four distinct entries of the inverted 6x6 moment matrix of Farneback's polynomial fit
// under a normalised Gaussian applicability of half-width n (OpenCV's FarnebackPrepareGaussian).
// The matrix is block-structured: 1 and 2 (x, y) are diagonal, 5 (xy) is diagonal, and
// 0, 3, 4 (1, x^2, y^2) couple as [[g00 g11 g11] [g11 g33 g55] [g11 g55 g33]].
static void dis_fb_moments(int n, double sigma, float out[4]) {
    double g[16];
    double s = 0.0;
    for (int x = -n; x <= n; x++) {
        g[x + n] = exp(-x * x / (2.0 * sigma * sigma));
        s += g[x + n];
    }
    for (int x = -n; x <= n; x++) g[x + n] /= s;

    double g00 = 0.0, g11 = 0.0, g33 = 0.0, g55 = 0.0;
    for (int y = -n; y <= n; y++) {
        for (int x = -n; x <= n; x++) {
            const double w = g[y + n] * g[x + n];
            g00 += w;
            g11 += w * x * x;
            g33 += w * x * x * x * x;
            g55 += w * x * x * y * y;
        }
    }
    // Inverse of the coupled block; by symmetry only its (0,0)-row entry against x^2 and the
    // x^2 diagonal are needed.
    const double m[3][3] = {{g00, g11, g11}, {g11, g33, g55}, {g11, g55, g33}};
    const double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                     - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                     + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    const double inv01 = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
    const double inv11 = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
    out[0] = (float)(1.0 / g11);   // ig11
    out[1] = (float)inv01;         // ig03
    out[2] = (float)inv11;         // ig33
    out[3] = (float)(1.0 / g55);   // ig55
}

static uint32_t dis_prop_steps_for(uint32_t level, uint32_t levels, uint32_t floor_steps) {
    static const uint32_t profile[DIS_PROP_STEPS_MAX] = {4u, 3u, 2u, 1u};
    const uint32_t from_coarse = (levels - 1u) - level;
    const uint32_t base = from_coarse < DIS_PROP_STEPS_MAX ? profile[from_coarse] : 1u;
    return base > floor_steps ? base : floor_steps;
}

typedef struct {
    uint32_t vr_fixed_point;
    uint32_t vr_sor;
    uint32_t prop_floor;
    uint32_t vr_levels;
} DisRefine;

static DisRefine dis_refine_for(uint32_t generations) {
    if (generations >= 3u) {
        // The 4x path gets the largest refinement budget in the ladder, because its
        // whole cost lands ONCE PER SOURCE PAIR while the interpolate pass runs three
        // times. At a 30 fps source that is a 33 ms budget for a 320x180 pyramid, so four
        // fixed-point passes and seven SOR sweeps on the finest level are a handful of
        // small dispatches - paid for by a field that is closer to right, which is the one
        // improvement that costs no softness anywhere.
        const DisRefine r = {4u, 7u, 2u, DIS_VR_LEVELS};
        return r;
    }
    if (generations == 2u) {
        const DisRefine r = {2u, 4u, 1u, DIS_VR_LEVELS};
        return r;
    }
    const DisRefine r = {1u, 3u, 1u, DIS_VR_LEVELS};
    return r;
}

static void dis_batch_flush(VkrDis* d, DisBatch* b) {
    if (b->count == 0) return;
    wfg_vkd.UpdateDescriptorSets(d->device, b->count, b->w, 0, NULL);
    b->count = 0;
}

static void dis_batch_sampled(VkrDis* d, DisBatch* b, VkDescriptorSet set, uint32_t binding,
                              VkImageView view, VkSampler sampler) {
    if (b->count == DIS_MAX_DESCRIPTOR_WRITES) dis_batch_flush(d, b);
    VkDescriptorImageInfo* info = &b->img[b->count];
    memset(info, 0, sizeof(*info));
    info->sampler = sampler;
    info->imageView = view;
    info->imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet* w = &b->w[b->count];
    memset(w, 0, sizeof(*w));
    w->sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w->dstSet = set;
    w->dstBinding = binding;
    w->descriptorCount = 1;
    w->descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w->pImageInfo = info;
    b->count++;
}

static void dis_batch_storage(VkrDis* d, DisBatch* b, VkDescriptorSet set, uint32_t binding,
                              VkImageView view) {
    if (b->count == DIS_MAX_DESCRIPTOR_WRITES) dis_batch_flush(d, b);
    VkDescriptorImageInfo* info = &b->img[b->count];
    memset(info, 0, sizeof(*info));
    info->imageView = view;
    info->imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet* w = &b->w[b->count];
    memset(w, 0, sizeof(*w));
    w->sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w->dstSet = set;
    w->dstBinding = binding;
    w->descriptorCount = 1;
    w->descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w->pImageInfo = info;
    b->count++;
}

static void dis_write_all_descriptors(VkrDis* d) {
    DisBatch b;
    memset(&b, 0, sizeof(b));
    const uint32_t L = d->levels;
    const uint32_t coarse = L - 1;
    const uint32_t vrL = L < DIS_VR_LEVELS ? L : DIS_VR_LEVELS;

    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        const uint32_t next = s;
        const uint32_t prev = (s + DIS_SLOTS - 1u) % DIS_SLOTS;

        for (uint32_t l = 0; l < L; l++) {
            dis_batch_sampled(d, &b, d->luma_sets[s][l], 0, d->view_flow_color[next][l], d->sampler);
            dis_batch_storage(d, &b, d->luma_sets[s][l], 5, d->view_flow_luma[next][l]);

            dis_batch_sampled(d, &b, d->grad_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_storage(d, &b, d->grad_sets[s][l], 5, d->view_grad[l]);

            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 2, d->view_grad[l], d->sampler);
            // The coarse estimate is the refined flow where the level above was
            // refined and the densified flow where the VR stage was skipped.
            const uint32_t coarse_l = l + 1 < L ? l + 1 : coarse;
            const VkImageView coarse_view = l + 1 < DIS_VR_LEVELS
                ? d->view_flow_refined[coarse_l] : d->view_dense[coarse_l];
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 3, coarse_view, d->sampler);
            dis_batch_storage(d, &b, d->inverse_sets[s][l], 5, d->view_sparse[l]);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 6, d->view_flow_out, d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 7, d->view_fb_l1, d->sampler);

            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 2, d->view_sparse[l], d->sampler);
            dis_batch_storage(d, &b, d->prop_ab_sets[s][l], 5, d->view_sparse_b[l]);

            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 2, d->view_sparse_b[l], d->sampler);
            dis_batch_storage(d, &b, d->prop_ba_sets[s][l], 5, d->view_sparse[l]);

            dis_batch_sampled(d, &b, d->densify_sets[s][l], 0, d->view_sparse[l], d->sampler);
            dis_batch_sampled(d, &b, d->densify_sets[s][l], 1, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->densify_sets[s][l], 2, d->view_flow_luma[next][l], d->sampler);
            dis_batch_storage(d, &b, d->densify_sets[s][l], 5, d->view_dense[l]);
        }

        for (uint32_t dir = 0; dir < 2u; dir++) {
            for (uint32_t g = 0; g < VKR_DIS_MAX_GENERATIONS; g++) {
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 0, d->view_color[prev], d->sampler);
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 1, d->view_color[next], d->sampler);
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 2, d->view_flow_fill, d->sampler);
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 3, d->view_side, d->sampler);
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 4, d->view_hist[dir], d->sampler);
                dis_batch_sampled(d, &b, d->interp_sets[s][dir][g], 6, d->view_splat_field, d->sampler);
                if (d->view_interp_out)
                    dis_batch_storage(d, &b, d->interp_sets[s][dir][g], 5, d->view_interp_out);
                d->interp_targets[s][dir][g] = d->view_interp_out;
            }

            dis_batch_sampled(d, &b, d->hist_sets[s][dir], 0, d->view_color[prev], d->sampler);
            dis_batch_sampled(d, &b, d->hist_sets[s][dir], 1, d->view_color[next], d->sampler);
            dis_batch_sampled(d, &b, d->hist_sets[s][dir], 2, d->view_hist[1u - dir], d->sampler);
            dis_batch_storage(d, &b, d->hist_sets[s][dir], DIS_VR_FIRST_STORAGE, d->view_hist[dir]);
        }

        dis_batch_sampled(d, &b, d->splat_sets[s], 0, d->view_flow_luma[prev][0], d->sampler);
        dis_batch_sampled(d, &b, d->splat_sets[s], 1, d->view_flow_luma[next][0], d->sampler);
        dis_batch_sampled(d, &b, d->splat_sets[s], 2, d->view_flow_fill, d->sampler);
        dis_batch_storage(d, &b, d->splat_sets[s], DIS_VR_FIRST_STORAGE, d->view_splat_key);
        dis_batch_storage(d, &b, d->splat_sets[s], DIS_VR_FIRST_STORAGE + 1, d->view_splat_field);

        for (uint32_t dir = 0; dir < 2u; dir++) {
            VkDescriptorSet fs = d->fill_sets[s][dir];
            dis_batch_sampled(d, &b, fs, 0, d->view_flow_out, d->sampler);
            dis_batch_sampled(d, &b, fs, 1, d->view_fill_cons, d->sampler);
            dis_batch_sampled(d, &b, fs, 2, d->view_flow_luma[prev][0], d->sampler);
            dis_batch_sampled(d, &b, fs, 3, d->view_flow_luma[next][0], d->sampler);
            dis_batch_sampled(d, &b, fs, 4, d->view_flow_refined[0], d->sampler);
            dis_batch_sampled(d, &b, fs, 5, d->view_hist[dir], d->sampler);
            dis_batch_sampled(d, &b, fs, 6, d->view_flow_prev, d->sampler);
            dis_batch_storage(d, &b, fs, DIS_VR_FIRST_STORAGE, d->view_fill_cons);
            dis_batch_storage(d, &b, fs, DIS_VR_FIRST_STORAGE + 1, d->view_flow_fill);
        }

        dis_batch_sampled(d, &b, d->side_sets[s], 0, d->view_flow_fill, d->sampler);
        dis_batch_storage(d, &b, d->side_sets[s], 5, d->view_side);

        for (uint32_t l = 0; l < vrL; l++) {
            dis_batch_sampled(d, &b, d->vr_prep_sets[s][l], 0, d->view_flow_color[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->vr_prep_sets[s][l], 1, d->view_flow_color[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->vr_prep_sets[s][l], 2, d->view_dense[l], d->sampler);
            dis_batch_storage(d, &b, d->vr_prep_sets[s][l], DIS_VR_FIRST_STORAGE, d->view_vr_prep[l]);
            dis_batch_storage(d, &b, d->vr_prep_sets[s][l], DIS_VR_FIRST_STORAGE + 1, d->view_vr_dw[0][l]);
        }
    }

    for (uint32_t l = 0; l < vrL; l++) {
        dis_batch_sampled(d, &b, d->vr_d1_set[l], 0, d->view_vr_prep[l], d->sampler);
        dis_batch_storage(d, &b, d->vr_d1_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_d1[l]);

        dis_batch_sampled(d, &b, d->vr_d2_set[l], 0, d->view_vr_d1[l], d->sampler);
        dis_batch_storage(d, &b, d->vr_d2_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_d2[l]);

        dis_batch_sampled(d, &b, d->vr_w_set[l], 0, d->view_dense[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_w_set[l], 1, d->view_vr_dw[0][l], d->sampler);
        dis_batch_storage(d, &b, d->vr_w_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_wt[l]);

        dis_batch_sampled(d, &b, d->vr_coef_set[l], 0, d->view_vr_prep[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_coef_set[l], 1, d->view_vr_d1[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_coef_set[l], 2, d->view_vr_d2[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_coef_set[l], 3, d->view_vr_dw[0][l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_coef_set[l], 4, d->view_dense[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_coef_set[l], 5, d->view_vr_wt[l], d->sampler);
        dis_batch_storage(d, &b, d->vr_coef_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_A[l]);
        dis_batch_storage(d, &b, d->vr_coef_set[l], DIS_VR_FIRST_STORAGE + 1, d->view_vr_B[l]);

        dis_batch_sampled(d, &b, d->vr_sor_ab_set[l], 0, d->view_vr_A[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_sor_ab_set[l], 1, d->view_vr_B[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_sor_ab_set[l], 3, d->view_vr_dw[0][l], d->sampler);
        dis_batch_storage(d, &b, d->vr_sor_ab_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_dw[1][l]);

        dis_batch_sampled(d, &b, d->vr_sor_ba_set[l], 0, d->view_vr_A[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_sor_ba_set[l], 1, d->view_vr_B[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_sor_ba_set[l], 3, d->view_vr_dw[1][l], d->sampler);
        dis_batch_storage(d, &b, d->vr_sor_ba_set[l], DIS_VR_FIRST_STORAGE, d->view_vr_dw[0][l]);

        dis_batch_sampled(d, &b, d->vr_add_set[l], 0, d->view_dense[l], d->sampler);
        dis_batch_sampled(d, &b, d->vr_add_set[l], 1, d->view_vr_dw[0][l], d->sampler);
        dis_batch_storage(d, &b, d->vr_add_set[l], DIS_VR_FIRST_STORAGE, d->view_flow_refined[l]);
    }

    // Not per slot: the input and the output are single images, and this set is written
    // once here and never updated, so sharing it across frames is safe.
    dis_batch_sampled(d, &b, d->pack_set, 0, d->view_flow_refined[0], d->sampler);
    dis_batch_sampled(d, &b, d->pack_set, 1, d->view_fill_cons, d->sampler);
    dis_batch_storage(d, &b, d->pack_set, DIS_VR_FIRST_STORAGE, d->view_flow_out);
    dis_batch_storage(d, &b, d->pack_set, DIS_VR_FIRST_STORAGE + 1, d->view_flow_prev);

    // Farneback. The pair ending in slot s runs from prev to s; a level starts from the level
    // above (normalised uv reads the same at any size), the coarsest from the previous pair's
    // flow, and later iterations from the level's own field.
    for (uint32_t l = 0; l < L; l++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            const uint32_t prev = (s + DIS_SLOTS - 1u) % DIS_SLOTS;
            dis_batch_sampled(d, &b, d->fb_poly_sets[s][l], 0, d->view_flow_luma[s][l], d->sampler);
            dis_batch_storage(d, &b, d->fb_poly_sets[s][l], DIS_VR_FIRST_STORAGE, d->view_fb_b[s][l]);
            dis_batch_storage(d, &b, d->fb_poly_sets[s][l], DIS_VR_FIRST_STORAGE + 1, d->view_fb_c[s][l]);
            for (uint32_t k = 0; k < 2; k++) {
                VkDescriptorSet set = d->fb_hsum_sets[s][l][k];
                // The coarsest level starts from the previous pair's flow: on a hard camera turn
                // that warm start is what keeps up with the motion.
                const VkImageView start = k == 1 ? d->view_flow_refined[l]
                    : (l + 1 < L ? d->view_flow_refined[l + 1] : d->view_flow_out);
                dis_batch_sampled(d, &b, set, 0, d->view_fb_b[prev][l], d->sampler);
                dis_batch_sampled(d, &b, set, 1, d->view_fb_c[prev][l], d->sampler);
                dis_batch_sampled(d, &b, set, 2, d->view_fb_b[s][l], d->sampler);
                dis_batch_sampled(d, &b, set, 3, d->view_fb_c[s][l], d->sampler);
                dis_batch_sampled(d, &b, set, 4, start, d->sampler);
                dis_batch_storage(d, &b, set, DIS_VR_FIRST_STORAGE, d->view_fb_sg[l]);
                dis_batch_storage(d, &b, set, DIS_VR_FIRST_STORAGE + 1, d->view_fb_sh[l]);
            }
        }
        dis_batch_sampled(d, &b, d->fb_vsolve_sets[l], 0, d->view_fb_sg[l], d->sampler);
        dis_batch_sampled(d, &b, d->fb_vsolve_sets[l], 1, d->view_fb_sh[l], d->sampler);
        dis_batch_storage(d, &b, d->fb_vsolve_sets[l], DIS_VR_FIRST_STORAGE, d->view_flow_refined[l]);
    }
    // Farneback's level 1 into fb_l1 (the hybrid with DIS on level 1 as well).
    if (L > 1) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            const uint32_t prev = (s + DIS_SLOTS - 1u) % DIS_SLOTS;
            VkDescriptorSet set = d->fb_hsum1_sets[s];
            dis_batch_sampled(d, &b, set, 0, d->view_fb_b[prev][1], d->sampler);
            dis_batch_sampled(d, &b, set, 1, d->view_fb_c[prev][1], d->sampler);
            dis_batch_sampled(d, &b, set, 2, d->view_fb_b[s][1], d->sampler);
            dis_batch_sampled(d, &b, set, 3, d->view_fb_c[s][1], d->sampler);
            dis_batch_sampled(d, &b, set, 4, d->view_fb_l1, d->sampler);
            dis_batch_storage(d, &b, set, DIS_VR_FIRST_STORAGE, d->view_fb_sg[1]);
            dis_batch_storage(d, &b, set, DIS_VR_FIRST_STORAGE + 1, d->view_fb_sh[1]);
        }
        dis_batch_sampled(d, &b, d->fb_vsolve1_set, 0, d->view_fb_sg[1], d->sampler);
        dis_batch_sampled(d, &b, d->fb_vsolve1_set, 1, d->view_fb_sh[1], d->sampler);
        dis_batch_storage(d, &b, d->fb_vsolve1_set, DIS_VR_FIRST_STORAGE, d->view_fb_l1);
    }
    dis_batch_flush(d, &b);
}

static void dis_destroy_views(VkrDis* d) {
    for (uint32_t s = 0; s < DIS_SLOTS; s++) dis_destroy_view(d, &d->view_color[s]);
    dis_destroy_view(d, &d->view_interp_out);
    dis_destroy_view(d, &d->view_hist[0]);
    dis_destroy_view(d, &d->view_hist[1]);
    dis_destroy_view(d, &d->view_side);
    dis_destroy_view(d, &d->view_flow_out);
    dis_destroy_view(d, &d->view_splat_key);
    dis_destroy_view(d, &d->view_fill_cons);
    dis_destroy_view(d, &d->view_flow_fill);
    dis_destroy_view(d, &d->view_flow_prev);
    dis_destroy_view(d, &d->view_splat_field);
    dis_destroy_view(d, &d->view_fb_l1);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        dis_destroy_view(d, &d->view_vr_prep[l]);
        dis_destroy_view(d, &d->view_vr_d1[l]);
        dis_destroy_view(d, &d->view_vr_d2[l]);
        dis_destroy_view(d, &d->view_vr_A[l]);
        dis_destroy_view(d, &d->view_vr_B[l]);
        dis_destroy_view(d, &d->view_vr_wt[l]);
        dis_destroy_view(d, &d->view_vr_dw[0][l]);
        dis_destroy_view(d, &d->view_vr_dw[1][l]);
        dis_destroy_view(d, &d->view_flow_refined[l]);
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            dis_destroy_view(d, &d->view_flow_color[s][l]);
            dis_destroy_view(d, &d->view_flow_luma[s][l]);
            dis_destroy_view(d, &d->view_fb_b[s][l]);
            dis_destroy_view(d, &d->view_fb_c[s][l]);
        }
        dis_destroy_view(d, &d->view_fb_sg[l]);
        dis_destroy_view(d, &d->view_fb_sh[l]);
        dis_destroy_view(d, &d->view_grad[l]);
        dis_destroy_view(d, &d->view_sparse[l]);
        dis_destroy_view(d, &d->view_sparse_b[l]);
        dis_destroy_view(d, &d->view_dense[l]);
    }
}

static void dis_destroy_images(VkrDis* d) {
    dis_destroy_views(d);
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        dis_destroy_image(d, &d->color[s]);
        dis_destroy_image(d, &d->flow_color[s]);
        dis_destroy_image(d, &d->flow_luma[s]);
    }
    dis_destroy_image(d, &d->grad);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        dis_destroy_image(d, &d->flow_sparse[l]);
        dis_destroy_image(d, &d->flow_sparse_b[l]);
    }
    dis_destroy_image(d, &d->flow_dense);
    dis_destroy_image(d, &d->interp_out);
    dis_destroy_image(d, &d->vr_prep);
    dis_destroy_image(d, &d->vr_d1);
    dis_destroy_image(d, &d->vr_d2);
    dis_destroy_image(d, &d->vr_A);
    dis_destroy_image(d, &d->vr_B);
    dis_destroy_image(d, &d->vr_wt);
    dis_destroy_image(d, &d->vr_dw[0]);
    dis_destroy_image(d, &d->vr_dw[1]);
    dis_destroy_image(d, &d->flow_refined);
    dis_destroy_image(d, &d->hist[0]);
    dis_destroy_image(d, &d->hist[1]);
    dis_destroy_image(d, &d->side);
    dis_destroy_image(d, &d->flow_out);
    dis_destroy_image(d, &d->splat_key);
    dis_destroy_image(d, &d->fill_cons);
    dis_destroy_image(d, &d->flow_fill);
    dis_destroy_image(d, &d->flow_prev);
    dis_destroy_image(d, &d->splat_field);
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        dis_destroy_image(d, &d->fb_b[s]);
        dis_destroy_image(d, &d->fb_c[s]);
    }
    dis_destroy_image(d, &d->fb_sg);
    dis_destroy_image(d, &d->fb_sh);
    dis_destroy_image(d, &d->fb_l1);
}

static bool dis_create_resources(VkrDis* d, uint32_t w, uint32_t h, uint32_t full_w,
                                 uint32_t full_h, VkFormat format) {
    dis_destroy_images(d);
    d->layouts_primed = false;

    const uint32_t L = d->levels;
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_create_image(d, &d->color[s], full_w, full_h, format, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
        if (!dis_create_image(d, &d->flow_color[s], w, h, format, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
        if (!dis_create_image(d, &d->flow_luma[s], w, h, d->luma_format, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    }
    if (!dis_create_image(d, &d->grad, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t spw = dis_sparse_extent(w >> l);
        const uint32_t sph = dis_sparse_extent(h >> l);
        if (!dis_create_image(d, &d->flow_sparse[l], spw, sph, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
        if (!dis_create_image(d, &d->flow_sparse_b[l], spw, sph, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    }
    if (!dis_create_image(d, &d->flow_dense, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!d->direct_output && !dis_create_image(d, &d->interp_out, full_w, full_h, VK_FORMAT_R8G8B8A8_UNORM, 1,
                          VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        return false;
    }

    if (!dis_create_image(d, &d->vr_prep, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_d1, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_d2, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_A, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_B, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_wt, w, h, VK_FORMAT_R32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_dw[0], w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_dw[1], w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->flow_refined, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    // R32_SFLOAT at half the content resolution: the storage format is already
    // required by the flow images, so the history adds no device requirement.
    const uint32_t hist_w = full_w > 1u ? full_w / 2u : 1u;
    const uint32_t hist_h = full_h > 1u ? full_h / 2u : 1u;
    if (!dis_create_image(d, &d->hist[0], hist_w, hist_h, VK_FORMAT_R32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->hist[1], hist_w, hist_h, VK_FORMAT_R32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->side, w, h, VK_FORMAT_R32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    // The finished level-0 field as the interpolate pass reads it: RG16F is filterable on
    // every device, so its three lookups per output pixel stay single bilinear taps.
    if (!dis_create_image(d, &d->flow_out, w, h, VK_FORMAT_R16G16_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    // How well the next frame confirms each level-0 vector, and the field with the unconfirmed
    // ones replaced, which is what the interpolation uses (dis_fill.comp).
    if (!dis_create_image(d, &d->fill_cons, w, h, VK_FORMAT_R32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->flow_prev, w, h, VK_FORMAT_R16G16B16A16_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->flow_fill, w, h, VK_FORMAT_R16G16B16A16_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    // The field splatted to a generated frame's time (dis_splat.comp): the winning priority per
    // texel (R32_UINT, whose storage atomics every device has) and the vector it carries, one
    // strip of the flow's height per generated frame of the pair.
    if (!dis_create_image(d, &d->splat_key, w, h * VKR_DIS_MAX_GENERATIONS, VK_FORMAT_R32_UINT, 1,
                          VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->splat_field, w, h * VKR_DIS_MAX_GENERATIONS,
                          VK_FORMAT_R16G16B16A16_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_create_image(d, &d->fb_b[s], w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
        if (!dis_create_image(d, &d->fb_c[s], w, h, VK_FORMAT_R32_SFLOAT, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    }
    if (!dis_create_image(d, &d->fb_sg, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->fb_sh, w, h, VK_FORMAT_R32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->fb_l1, w > 1u ? w >> 1 : 1u, h > 1u ? h >> 1 : 1u,
                          VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;

    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_create_view(d, d->color[s].image, format, 0, 1, &d->view_color[s])) return false;
    }
    for (uint32_t l = 0; l < L; l++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            if (!dis_create_view(d, d->flow_color[s].image, format, l, 1,
                                 &d->view_flow_color[s][l])) return false;
            if (!dis_create_view(d, d->flow_luma[s].image, d->luma_format, l, 1,
                                 &d->view_flow_luma[s][l])) return false;
        }
        if (!dis_create_view(d, d->grad.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_grad[l])) return false;
        if (!dis_create_view(d, d->flow_sparse[l].image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_sparse[l])) return false;
        if (!dis_create_view(d, d->flow_sparse_b[l].image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_sparse_b[l])) return false;
        if (!dis_create_view(d, d->flow_dense.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_dense[l])) return false;
        if (!dis_create_view(d, d->vr_prep.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_vr_prep[l])) return false;
        if (!dis_create_view(d, d->vr_d1.image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_vr_d1[l])) return false;
        if (!dis_create_view(d, d->vr_d2.image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_vr_d2[l])) return false;
        if (!dis_create_view(d, d->vr_A.image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_vr_A[l])) return false;
        if (!dis_create_view(d, d->vr_B.image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_vr_B[l])) return false;
        if (!dis_create_view(d, d->vr_wt.image, VK_FORMAT_R32_SFLOAT, l, 1, &d->view_vr_wt[l])) return false;
        if (!dis_create_view(d, d->vr_dw[0].image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_vr_dw[0][l])) return false;
        if (!dis_create_view(d, d->vr_dw[1].image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_vr_dw[1][l])) return false;
        if (!dis_create_view(d, d->flow_refined.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_flow_refined[l])) return false;
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            if (!dis_create_view(d, d->fb_b[s].image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_fb_b[s][l])) return false;
            if (!dis_create_view(d, d->fb_c[s].image, VK_FORMAT_R32_SFLOAT, l, 1, &d->view_fb_c[s][l])) return false;
        }
        if (!dis_create_view(d, d->fb_sg.image, VK_FORMAT_R32G32B32A32_SFLOAT, l, 1, &d->view_fb_sg[l])) return false;
        if (!dis_create_view(d, d->fb_sh.image, VK_FORMAT_R32_SFLOAT, l, 1, &d->view_fb_sh[l])) return false;
    }
    if (!d->direct_output && !dis_create_view(d, d->interp_out.image, VK_FORMAT_R8G8B8A8_UNORM, 0, 1, &d->view_interp_out)) return false;
    if (!dis_create_view(d, d->hist[0].image, VK_FORMAT_R32_SFLOAT, 0, 1, &d->view_hist[0])) return false;
    if (!dis_create_view(d, d->hist[1].image, VK_FORMAT_R32_SFLOAT, 0, 1, &d->view_hist[1])) return false;
    if (!dis_create_view(d, d->side.image, VK_FORMAT_R32_SFLOAT, 0, 1, &d->view_side)) return false;
    if (!dis_create_view(d, d->flow_out.image, VK_FORMAT_R16G16_SFLOAT, 0, 1, &d->view_flow_out)) return false;
    if (!dis_create_view(d, d->splat_key.image, VK_FORMAT_R32_UINT, 0, 1, &d->view_splat_key)) return false;
    if (!dis_create_view(d, d->fill_cons.image, VK_FORMAT_R32_SFLOAT, 0, 1, &d->view_fill_cons)) return false;
    if (!dis_create_view(d, d->flow_fill.image, VK_FORMAT_R16G16B16A16_SFLOAT, 0, 1, &d->view_flow_fill)) return false;
    if (!dis_create_view(d, d->flow_prev.image, VK_FORMAT_R16G16B16A16_SFLOAT, 0, 1, &d->view_flow_prev)) return false;
    if (!dis_create_view(d, d->splat_field.image, VK_FORMAT_R16G16B16A16_SFLOAT, 0, 1, &d->view_splat_field)) return false;
    if (!dis_create_view(d, d->fb_l1.image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_fb_l1)) return false;

    vkr_dis_reset(d);
    dis_write_all_descriptors(d);
    return true;
}

static bool dis_alloc(VkrDis* d, VkDescriptorSetLayout layout, uint32_t count,
                      VkDescriptorSet* out) {
    VkDescriptorSetLayout layouts[8];
    if (count > 8) return false;
    for (uint32_t i = 0; i < count; i++) layouts[i] = layout;
    VkDescriptorSetAllocateInfo ai;
    memset(&ai, 0, sizeof(ai));
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = d->pool;
    ai.descriptorSetCount = count;
    ai.pSetLayouts = layouts;
    const VkResult res = wfg_vkd.AllocateDescriptorSets(d->device, &ai, out);
    if (res != VK_SUCCESS) {
        DIS_LOGW("WFG descriptor allocation failed (%d) asking for %u sets", (int)res, count);
        return false;
    }
    return true;
}

static bool dis_allocate_sets(VkrDis* d) {
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
            VkDescriptorSet sets[6];
            if (!dis_alloc(d, d->set_layout, 6, sets)) return false;
            d->grad_sets[s][l] = sets[0];
            d->inverse_sets[s][l] = sets[1];
            d->densify_sets[s][l] = sets[2];
            d->prop_ab_sets[s][l] = sets[3];
            d->prop_ba_sets[s][l] = sets[4];
            d->luma_sets[s][l] = sets[5];
        }
        for (uint32_t dir = 0; dir < 2u; dir++)
            if (!dis_alloc(d, d->set_layout, VKR_DIS_MAX_GENERATIONS, d->interp_sets[s][dir])) return false;
        if (!dis_alloc(d, d->set_layout, 1, &d->side_sets[s])) return false;
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->hist_sets[s][0])) return false;
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->hist_sets[s][1])) return false;
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->splat_sets[s])) return false;
        if (!dis_alloc(d, d->vr_set_layout, 2, d->fill_sets[s])) return false;
        for (uint32_t l = 0; l < DIS_VR_LEVELS; l++) {
            if (!dis_alloc(d, d->vr_set_layout, 1, &d->vr_prep_sets[s][l])) return false;
        }
    }

    if (!dis_alloc(d, d->vr_set_layout, 1, &d->pack_set)) return false;

    for (uint32_t l = 0; l < DIS_VR_LEVELS; l++) {
        VkDescriptorSet vr_sets[DIS_VR_SHARED_SETS];
        if (!dis_alloc(d, d->vr_set_layout, DIS_VR_SHARED_SETS, vr_sets)) return false;
        d->vr_d1_set[l] = vr_sets[0];
        d->vr_d2_set[l] = vr_sets[1];
        d->vr_w_set[l] = vr_sets[2];
        d->vr_coef_set[l] = vr_sets[3];
        d->vr_sor_ab_set[l] = vr_sets[4];
        d->vr_sor_ba_set[l] = vr_sets[5];
        d->vr_add_set[l] = vr_sets[6];
    }

    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            VkDescriptorSet fb[3];
            if (!dis_alloc(d, d->vr_set_layout, 3, fb)) return false;
            d->fb_poly_sets[s][l] = fb[0];
            d->fb_hsum_sets[s][l][0] = fb[1];
            d->fb_hsum_sets[s][l][1] = fb[2];
        }
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->fb_vsolve_sets[l])) return false;
    }
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->fb_hsum1_sets[s])) return false;
    }
    if (!dis_alloc(d, d->vr_set_layout, 1, &d->fb_vsolve1_set)) return false;
    return true;
}

static void dis_dispatch(VkrDis* d, VkCommandBuffer cmd, VkPipeline pipeline, VkDescriptorSet set,
                         uint32_t w, uint32_t h) {
    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1, &set,
                              0, NULL);
    wfg_vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
}

static void dis_blit_rect(VkCommandBuffer cmd,
                          VkImage src, int32_t sx, int32_t sy, uint32_t sw, uint32_t sh,
                          VkImage dst, int32_t dx, int32_t dy, uint32_t dw, uint32_t dh,
                          VkFilter filter) {
    VkImageBlit blit;
    memset(&blit, 0, sizeof(blit));
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[0].x = sx;
    blit.srcOffsets[0].y = sy;
    blit.srcOffsets[1].x = sx + (int32_t)sw;
    blit.srcOffsets[1].y = sy + (int32_t)sh;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[0].x = dx;
    blit.dstOffsets[0].y = dy;
    blit.dstOffsets[1].x = dx + (int32_t)dw;
    blit.dstOffsets[1].y = dy + (int32_t)dh;
    blit.dstOffsets[1].z = 1;
    wfg_vkd.CmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &blit,
                     filter);
}

static void dis_blit_mip(VkCommandBuffer cmd, VkImage img, uint32_t src_level, uint32_t dst_level,
                         uint32_t src_w, uint32_t src_h, uint32_t dst_w, uint32_t dst_h) {
    VkImageBlit blit;
    memset(&blit, 0, sizeof(blit));
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.mipLevel = src_level;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src_w;
    blit.srcOffsets[1].y = (int32_t)src_h;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.mipLevel = dst_level;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)dst_w;
    blit.dstOffsets[1].y = (int32_t)dst_h;
    blit.dstOffsets[1].z = 1;
    wfg_vkd.CmdBlitImage(cmd, img, VK_IMAGE_LAYOUT_GENERAL, img, VK_IMAGE_LAYOUT_GENERAL, 1, &blit,
                     VK_FILTER_LINEAR);
}

static const char* dis_format_name(VkFormat f) {
    switch (f) {
        case VK_FORMAT_R16_SFLOAT: return "R16_SFLOAT";
        case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
        case VK_FORMAT_R32G32_SFLOAT: return "R32G32_SFLOAT";
        case VK_FORMAT_R16G16_SFLOAT: return "R16G16_SFLOAT";
        case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
        case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
        case VK_FORMAT_UNDEFINED: return "none";
        default: return "format";
    }
}

static void dis_missing_features(VkFormatFeatureFlags missing, char* out, size_t cap) {
    out[0] = '\0';
    const struct { VkFormatFeatureFlags bit; const char* name; } names[] = {
        {VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, "STORAGE_IMAGE"},
        {VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT, "SAMPLED_IMAGE"},
        {VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT, "SAMPLED_IMAGE_FILTER_LINEAR"},
        {VK_FORMAT_FEATURE_BLIT_SRC_BIT, "BLIT_SRC"},
        {VK_FORMAT_FEATURE_BLIT_DST_BIT, "BLIT_DST"},
    };
    for (uint32_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (!(missing & names[i].bit)) continue;
        if (out[0]) strncat(out, "+", cap - strlen(out) - 1);
        strncat(out, names[i].name, cap - strlen(out) - 1);
    }
    if (!out[0]) strncat(out, "none", cap - 1);
}

static bool dis_audit_formats(VkrDis* d) {
    const VkFormatFeatureFlags STORE = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    const VkFormatFeatureFlags READ = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    const VkFormatFeatureFlags FILTER = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;

    if (d->luma_format == VK_FORMAT_UNDEFINED) {
        DIS_LOGW("WFG needs a single-channel float plane it can both write and filter; "
                 "neither R16_SFLOAT nor R32_SFLOAT qualifies on this device");
        return false;
    }

    const struct {
        VkFormat format;
        VkFormatFeatureFlags need;
        const char* purpose;
    } reqs[] = {
        {VK_FORMAT_R32G32_SFLOAT, STORE | READ, "optical flow"},
        {VK_FORMAT_R16G16_SFLOAT, STORE | READ, "interpolation flow"},
        {VK_FORMAT_R32G32B32A32_SFLOAT, STORE | READ, "sparse flow and refinement"},
        {VK_FORMAT_R32_SFLOAT, STORE | READ, "refinement weights"},
        {VK_FORMAT_R8G8B8A8_UNORM, STORE | VK_FORMAT_FEATURE_BLIT_SRC_BIT,
         "interpolated output"},
        {d->luma_format, STORE | READ | FILTER, "luminance plane"},
    };

    char line[512];
    line[0] = '\0';
    bool ok = true;

    for (uint32_t i = 0; i < sizeof(reqs) / sizeof(reqs[0]); i++) {
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        wfg_vkd.GetPhysicalDeviceFormatProperties(d->physical_device, reqs[i].format, &fp);
        const VkFormatFeatureFlags missing = reqs[i].need & ~fp.optimalTilingFeatures;

        char entry[96];
        snprintf(entry, sizeof(entry), "%s%s=%s", line[0] ? " " : "",
                 dis_format_name(reqs[i].format), missing ? "MISSING" : "ok");
        strncat(line, entry, sizeof(line) - strlen(line) - 1);

        if (missing) {
            char names[256];
            dis_missing_features(missing, names, sizeof(names));
            DIS_LOGW("WFG needs %s on %s for %s, and this device does not report it",
                     names, dis_format_name(reqs[i].format), reqs[i].purpose);
            ok = false;
        }
    }

    VkFormatProperties flow_fp;
    memset(&flow_fp, 0, sizeof(flow_fp));
    wfg_vkd.GetPhysicalDeviceFormatProperties(d->physical_device, VK_FORMAT_R16G16_SFLOAT, &flow_fp);
    d->manual_flow_filter = (flow_fp.optimalTilingFeatures & FILTER) == 0;

    DIS_LOGI("WFG format support: %s | flow filtering: %s", line,
             d->manual_flow_filter ? "in shader (driver cannot filter R16G16_SFLOAT)"
                                   : "sampler");
    return ok;
}

bool vkr_dis_bind_dispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance) {
    if (!wfg_vkd_bind_proc(gipa, instance)) return false;
    return wfg_vkd.AllocateDescriptorSets &&
        wfg_vkd.AllocateMemory &&
        wfg_vkd.BindImageMemory &&
        wfg_vkd.CmdBindDescriptorSets &&
        wfg_vkd.CmdBindPipeline &&
        wfg_vkd.CmdBlitImage &&
        wfg_vkd.CmdDispatch &&
        wfg_vkd.CmdPipelineBarrier &&
        wfg_vkd.CmdPushConstants &&
        wfg_vkd.CreateComputePipelines &&
        wfg_vkd.CreateDescriptorPool &&
        wfg_vkd.CreateDescriptorSetLayout &&
        wfg_vkd.CreateImage &&
        wfg_vkd.CreateImageView &&
        wfg_vkd.CreatePipelineLayout &&
        wfg_vkd.CreateSampler &&
        wfg_vkd.CreateShaderModule &&
        wfg_vkd.DestroyDescriptorPool &&
        wfg_vkd.DestroyDescriptorSetLayout &&
        wfg_vkd.DestroyImage &&
        wfg_vkd.DestroyImageView &&
        wfg_vkd.DestroyPipeline &&
        wfg_vkd.DestroyPipelineLayout &&
        wfg_vkd.DestroySampler &&
        wfg_vkd.DestroyShaderModule &&
        wfg_vkd.FreeMemory &&
        wfg_vkd.GetDeviceProcAddr &&
        wfg_vkd.GetImageMemoryRequirements &&
        wfg_vkd.GetPhysicalDeviceFormatProperties &&
        wfg_vkd.GetPhysicalDeviceMemoryProperties &&
        wfg_vkd.GetPhysicalDeviceProperties &&
        wfg_vkd.UpdateDescriptorSets;
}

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE) return NULL;

    VkrDis* d = (VkrDis*)calloc(1, sizeof(VkrDis));
    if (!d) return NULL;
    d->device = device;
    d->physical_device = physical_device;
    d->flow_min_side = DIS_DEFAULT_FLOW_MIN_SIDE;
    d->target_fps = 0;
    d->refresh_rate = 0.0f;
    d->plan_log_gen = -1;

    // Flow estimator: DIS unless asked for Farneback, by WFG_ENGINE=farneback in the
    // environment (the desktop bench) or, on a device,
    //   adb shell setprop debug.winnative.wfg.engine farneback
    // The hybrid is the default: on the bench it matches DIS overall (better on fast camera
    // motion, worse on small fast objects with a periodic texture) at 2.3x less flow time on
    // Adreno 750, nearly all of DIS's time there being the level-0 SOR sweeps.
    d->flow_engine = DIS_ENGINE_HYBRID;
    const char* engine = getenv("WFG_ENGINE");
#ifdef __ANDROID__
    char engine_prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.winnative.wfg.engine", engine_prop) > 0) engine = engine_prop;
#endif
    if (engine && strcmp(engine, "dis") == 0) d->flow_engine = DIS_ENGINE_DIS;
    if (engine && strcmp(engine, "farneback") == 0) d->flow_engine = DIS_ENGINE_FARNEBACK;
    d->fb_iterations = DIS_FB_ITERATIONS;
    d->fb_radius = DIS_FB_RADIUS;
    d->fb_window_sigma = DIS_FB_WINDOW_SIGMA;
    d->fb_poly_sigma = DIS_FB_POLY_SIGMA;
    d->fb_robust = DIS_FB_ROBUST;
    d->fb_hybrid_vr = true;
    d->fb_hybrid_level = 2;
    d->fb_hybrid_l1_start = true;
    d->fb_hybrid_vr_upper = false;
    d->fb_hybrid_fp = DIS_HYB_FIXED_POINT;
    d->fb_hybrid_sor = DIS_HYB_SOR;
    {
        const char* v;
        if ((v = getenv("WFG_FB_ROBUST")) != NULL) d->fb_robust = (float)atof(v);
        if ((v = getenv("WFG_HYB_VR")) != NULL) d->fb_hybrid_vr = atoi(v) != 0;
        if ((v = getenv("WFG_HYB_LEVEL")) != NULL) d->fb_hybrid_level = (uint32_t)atoi(v);
        if ((v = getenv("WFG_HYB_FP")) != NULL) d->fb_hybrid_fp = (uint32_t)atoi(v);
        if ((v = getenv("WFG_HYB_SOR")) != NULL) d->fb_hybrid_sor = (uint32_t)atoi(v);
        if ((v = getenv("WFG_HYB_L1START")) != NULL) d->fb_hybrid_l1_start = atoi(v) != 0;
        if ((v = getenv("WFG_HYB_VR1")) != NULL) d->fb_hybrid_vr_upper = atoi(v) != 0;
        if ((v = getenv("WFG_FB_ITERS")) != NULL) d->fb_iterations = atoi(v);
        if ((v = getenv("WFG_FB_RADIUS")) != NULL) d->fb_radius = atoi(v);
        if ((v = getenv("WFG_FB_WSIGMA")) != NULL) d->fb_window_sigma = (float)atof(v);
        if ((v = getenv("WFG_FB_PSIGMA")) != NULL) d->fb_poly_sigma = (float)atof(v);
    }
    dis_fb_moments(DIS_FB_POLY_N, d->fb_poly_sigma, d->fb_ig);
    DIS_LOGI("WFG flow estimator: %s",
             d->flow_engine == DIS_ENGINE_DIS ? "DIS"
             : d->flow_engine == DIS_ENGINE_FARNEBACK ? "Farneback" : "Farneback + DIS levels 1-0");
    wfg_vkd.GetPhysicalDeviceMemoryProperties(physical_device, &d->mem_props);
    d->luma_format = dis_pick_luma_format(d);
    if (!dis_audit_formats(d)) {
        DIS_LOGW("WFG cannot run on this device's format support; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }

    if (!dis_create_sampler(d) || !dis_create_pipelines(d)) {
        DIS_LOGW("WFG shaders could not be built; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }
    if (!dis_allocate_sets(d)) {
        DIS_LOGW("WFG descriptor sets could not be allocated; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }
    DIS_LOGI("WFG frame generation ready");
    return d;
}

void vkr_dis_destroy(VkrDis* d) {
    if (!d) return;
    dis_destroy_images(d);
    if (d->sampler) wfg_vkd.DestroySampler(d->device, d->sampler, NULL);
    if (d->pass_luma.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_luma.pipeline, NULL);
    if (d->pass_gradient.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_gradient.pipeline, NULL);
    if (d->pass_inverse.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_inverse.pipeline, NULL);
    if (d->pass_propagate.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_propagate.pipeline, NULL);
    if (d->pass_densify.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_densify.pipeline, NULL);
    if (d->pass_interp.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_interp.pipeline, NULL);
    if (d->pass_vr_prep.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_prep.pipeline, NULL);
    if (d->pass_vr_d1.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_d1.pipeline, NULL);
    if (d->pass_vr_d2.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_d2.pipeline, NULL);
    if (d->pass_vr_w.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_w.pipeline, NULL);
    if (d->pass_vr_coef.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_coef.pipeline, NULL);
    if (d->pass_vr_sor.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_sor.pipeline, NULL);
    if (d->pass_vr_add.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_vr_add.pipeline, NULL);
    if (d->pass_hist.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_hist.pipeline, NULL);
    if (d->pass_side.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_side.pipeline, NULL);
    if (d->pass_pack.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_pack.pipeline, NULL);
    if (d->pass_splat.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_splat.pipeline, NULL);
    if (d->pass_fill.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_fill.pipeline, NULL);
    if (d->pass_fb_poly.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_fb_poly.pipeline, NULL);
    if (d->pass_fb_hsum.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_fb_hsum.pipeline, NULL);
    if (d->pass_fb_vsolve.pipeline) wfg_vkd.DestroyPipeline(d->device, d->pass_fb_vsolve.pipeline, NULL);
    if (d->pool) wfg_vkd.DestroyDescriptorPool(d->device, d->pool, NULL);
    if (d->pipeline_layout) wfg_vkd.DestroyPipelineLayout(d->device, d->pipeline_layout, NULL);
    if (d->vr_pipeline_layout) wfg_vkd.DestroyPipelineLayout(d->device, d->vr_pipeline_layout, NULL);
    if (d->set_layout) wfg_vkd.DestroyDescriptorSetLayout(d->device, d->set_layout, NULL);
    if (d->vr_set_layout) wfg_vkd.DestroyDescriptorSetLayout(d->device, d->vr_set_layout, NULL);
    free(d);
}

void vkr_dis_configure(VkrDis* d, uint32_t flow_min_side, uint32_t target_fps, float refresh_rate) {
    if (!d) return;
    uint32_t side = flow_min_side < DIS_FLOW_MIN_SIDE_FLOOR ? DIS_FLOW_MIN_SIDE_FLOOR
                  : (flow_min_side > DIS_FLOW_MIN_SIDE_CEIL ? DIS_FLOW_MIN_SIDE_CEIL
                                                            : flow_min_side);
    d->flow_min_side = side;
    d->target_fps = target_fps;
    d->refresh_rate = refresh_rate > 0.0f ? refresh_rate : 0.0f;
}

void vkr_dis_set_direct_output(VkrDis* d, bool direct) {
    if (d && !d->built) d->direct_output = direct;
}

void vkr_dis_set_debug_flow(VkrDis* d, bool debug_flow) {
    if (!d) return;
    d->debug_flow = debug_flow;
}

bool vkr_dis_needs_rebuild(const VkrDis* d, uint32_t width, uint32_t height, VkFormat format,
                           VkrDisContentRect content) {
    if (!d || d->unavailable) return false;
    return !d->built || d->built_full_extent.width != width ||
           d->built_full_extent.height != height || d->built_format != format ||
           d->built_min_side != d->flow_min_side ||
           d->content.width != content.width || d->content.height != content.height;
}

bool vkr_dis_prepare(VkrDis* d, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content) {
    if (!d || d->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    if (content.width < DIS_MIN_EXTENT || content.height < DIS_MIN_EXTENT ||
        content.x < 0 || content.y < 0 ||
        (uint32_t)content.x + content.width > width ||
        (uint32_t)content.y + content.height > height) {
        content.x = 0;
        content.y = 0;
        content.width = width;
        content.height = height;
    }
    uint32_t w, h;
    dis_flow_extent(d->flow_min_side, content.width, content.height, &w, &h);
    if (w < DIS_MIN_EXTENT) w = DIS_MIN_EXTENT;
    if (h < DIS_MIN_EXTENT) h = DIS_MIN_EXTENT;

    const uint32_t levels = dis_levels_for(w, h);

    if (d->built && d->built_extent.width == w && d->built_extent.height == h &&
        d->built_full_extent.width == width && d->built_full_extent.height == height &&
        d->built_format == format && d->built_min_side == d->flow_min_side && d->levels == levels &&
        d->content.width == content.width && d->content.height == content.height) {
        d->content.x = content.x;
        d->content.y = content.y;
        return true;
    }

    d->content = content;
    if (!d->formats_audited) {
        d->formats_audited = true;
        const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                          VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
                                          VK_FORMAT_FEATURE_BLIT_SRC_BIT |
                                          VK_FORMAT_FEATURE_BLIT_DST_BIT;
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        wfg_vkd.GetPhysicalDeviceFormatProperties(d->physical_device, format, &fp);
        const VkFormatFeatureFlags missing = need & ~fp.optimalTilingFeatures;
        if (missing) {
            char names[256];
            dis_missing_features(missing, names, sizeof(names));
            DIS_LOGW("WFG needs %s on the guest frame format (%d) and this device does not "
                     "report it; frame generation stays off", names, (int)format);
            d->unavailable = true;
            return false;
        }
    }

    d->levels = levels;

    if (!dis_create_resources(d, w, h, content.width, content.height, format)) {
        DIS_LOGW("WFG resource build failed at %ux%u; frame generation unavailable", w, h);
        dis_destroy_images(d);
        d->unavailable = true;
        return false;
    }

    d->built_extent.width = w;
    d->built_extent.height = h;
    d->built_full_extent.width = width;
    d->built_full_extent.height = height;
    d->built_format = format;
    d->built_min_side = d->flow_min_side;
    d->built = true;
    d->frame_count = 0;
    d->prior_valid = false;
    d->prev_idx = 0;
    d->next_idx = 0;
    d->active_slot = 0;
    d->last_generations = 0;
    DIS_LOGI("WFG resources built at %ux%u (flow min side %u, %u levels); content rect "
             "%dx%d+%d+%d inside a %ux%u composite",
             w, h, d->flow_min_side, levels, (int)content.width, (int)content.height,
             (int)content.x, (int)content.y, width, height);
    return true;
}

static void dis_track_source(VkrDis* d, uint64_t now, uint64_t source_frames) {
    if (d->src_sample_ns == 0) {
        d->src_sample_ns = now;
        d->src_last_frames = source_frames;
        return;
    }

    const uint64_t dt = now - d->src_sample_ns;
    if (dt == 0) return;
    // A burst of presents can land inside one millisecond (startup, alt-tab) and
    // a single such sample spikes the rate estimate to hundreds of fps, which
    // then walks the planner up and down the generation ladder. Let the window
    // span the burst instead.
    if (dt < 2000000ull) return;
    d->src_sample_ns = now;

    const uint64_t drawn =
        source_frames > d->src_last_frames ? source_frames - d->src_last_frames : 0;
    d->src_last_frames = source_frames;

    if (dt > DIS_SRC_STALE_NS) {
        d->src_frame_accum = 0.0f;
        d->src_time_accum = 0.0f;
        d->src_interval = 0.0f;
        d->src_samples = 0;
        return;
    }

    const float elapsed = (float)dt * 1.0e-9f;
    d->src_frame_accum += ((float)drawn - d->src_frame_accum) * DIS_SRC_SMOOTHING;
    d->src_time_accum += (elapsed - d->src_time_accum) * DIS_SRC_SMOOTHING;
    d->src_interval =
        d->src_frame_accum > 0.01f ? d->src_time_accum / d->src_frame_accum : 0.0f;
    if (d->src_samples < DIS_MIN_RATE_SAMPLES) d->src_samples++;
}

static int dis_gen_for_ratio(float ratio, uint32_t capacity) {
    float outputs = ceilf(ratio - DIS_RATIO_SLACK);
    if (outputs < 2.0f) outputs = 2.0f;
    int gen = (int)outputs - 1;
    if (gen < 1) gen = 1;
    if (gen > (int)capacity) gen = (int)capacity;
    return gen;
}

static void dis_log_plan(VkrDis* d, uint64_t now, float source_rate, float desired,
                         float ratio, uint32_t capacity) {
    if (d->planned_gen == d->plan_log_gen && now - d->plan_log_ns < DIS_PLAN_LOG_NS) return;
    d->plan_log_gen = d->planned_gen;
    d->plan_log_ns = now;
    DIS_LOGI("WFG plan: source %.1f fps, target %.1f fps, ratio %.2f -> %d generated "
             "(capacity %u, output %.1f fps)",
             (double)source_rate, (double)desired, (double)ratio, d->planned_gen, capacity,
             (double)(source_rate * (float)(d->planned_gen + 1)));
}

uint32_t vkr_dis_plan(VkrDis* d, uint32_t capacity, uint64_t source_frames) {
    if (!d || d->unavailable || !d->built) return 0;
    if (capacity > VKR_DIS_MAX_GENERATIONS) capacity = VKR_DIS_MAX_GENERATIONS;

    const uint64_t now = dis_now_ns();
    dis_track_source(d, now, source_frames);

    if (capacity == 0 || d->frame_count < 2 || d->src_samples < DIS_MIN_RATE_SAMPLES ||
        d->src_interval <= 0.0f) {
        d->planned_gen = 0;
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
        return 0;
    }

    const float source_rate = 1.0f / d->src_interval;
    float desired = d->target_fps > 0 ? (float)d->target_fps : d->refresh_rate;
    if (d->refresh_rate > 0.0f && desired > d->refresh_rate) desired = d->refresh_rate;
    if (desired <= 0.0f) return 0;

    if (d->smoothed_desired <= 0.0f) {
        d->smoothed_desired = desired;
    } else {
        d->smoothed_desired += (desired - d->smoothed_desired) * 0.25f;
    }
    const float eff_desired = d->smoothed_desired;

    const float ratio = eff_desired / source_rate;

    if (ratio <= 1.0f) {
        d->planned_gen = 0;
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
        dis_log_plan(d, now, source_rate, eff_desired, ratio, capacity);
        return 0;
    }

    const int cur = d->planned_gen > (int)capacity ? (int)capacity : d->planned_gen;
    // Not from a game below ~20 fps. Measured from 15, 20, 24 and 30 fps to 120 Hz: a fast camera
    // turn from 24 fps still interpolates (19.96 dB against 12.4 for repeating the frame), from
    // 20 fps it collapses to the repeat (13.1 against 12.1) - the motion between two real frames
    // outruns the search - and every generated frame also waits for the next real one, 50-66 ms
    // there. Off below 20, back on above 24, so a game around the limit does not flicker on and off.
    const bool fast_enough = cur > 0 ? source_rate >= DIS_MIN_SOURCE_FPS_OFF
                                     : source_rate >= DIS_MIN_SOURCE_FPS_ON;
    const bool generate = fast_enough && (cur > 0 ? (ratio >= DIS_MIN_GEN_RATIO - DIS_RATIO_HYST)
                                                  : (ratio >= DIS_MIN_GEN_RATIO));

    int raw = 0;
    if (generate) {
        const int want_up = dis_gen_for_ratio(ratio - DIS_RATIO_HYST, capacity);
        const int want_down = dis_gen_for_ratio(ratio + DIS_RATIO_HYST, capacity);
        raw = cur;
        if (want_up > cur) {
            raw = want_up;
        } else if (want_down < cur) {
            raw = want_down;
        }
        if (raw < 1) raw = 1;
        // Never more frames than the display can show. dis_gen_for_ratio rounds the output count
        // up so as not to fall short of the target, but when the target is the refresh rate
        // itself that overshoots it: 28.4 fps at 120 Hz is a ratio of 4.22, rounded to 5 outputs
        // - 142 fps. The swapchain then runs out of free images, generated frames time out on
        // acquire and are dropped, and the pair shows one frame fewer than its neighbours: a
        // visible jerk. Capped at the display rate, with room for the rate estimate's noise: a
        // steady 30 fps game reads anywhere from 27.6 to 31.6 on a Snapdragon 8 Gen 3, and each
        // flip of the count is a hitch of its own. 5% to step up to a count, 10% to step down
        // from the one in use.
        if (d->refresh_rate > 0.0f) {
            const float slack = raw > d->planned_gen ? 1.05f : 1.10f;
            const int max_gen = (int)floorf(d->refresh_rate * slack / source_rate) - 1;
            if (max_gen >= 1 && raw > max_gen) raw = max_gen;
        }
    }

    if (raw > d->planned_gen) {
        d->gen_low_streak = 0;
        d->gen_high_streak++;
        // Once generating, a step up waits for half a second of the slower rate: a game that
        // hitches for a few frames reads as 25 fps from 30, and a step to 4 and back is two
        // changes of cadence plus a frame the display cannot take. A step down stays quick,
        // since too many frames is what overflows the swapchain.
        int up_streak = d->planned_gen > 0 ? (int)(source_rate * 0.5f) : 2;
        if (up_streak < 2) up_streak = 2;
        if (d->gen_high_streak >= (uint32_t)up_streak) {
            d->planned_gen = raw;
            d->gen_high_streak = 0;
        }
    } else if (raw < d->planned_gen) {
        d->gen_high_streak = 0;
        d->gen_low_streak++;
        if (d->gen_low_streak >= 3) {
            d->planned_gen = raw;
            d->gen_low_streak = 0;
        }
    } else {
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
    }

    dis_log_plan(d, now, source_rate, eff_desired, ratio, capacity);
    return (uint32_t)d->planned_gen;
}

// Solver budget for one level. The refinement is a red-black SOR, and the
// number of sweeps a SOR needs scales with how far information has to travel
// across the grid - a level is half the size per axis, so it reaches the same
// relative distance in fewer sweeps. Spending the finest level's sweep count on
// every level buys nothing numerically and costs a dispatch and a barrier each.
static void dis_vr_budget(const DisRefine* refine, uint32_t l, uint32_t* fixed_point,
                          uint32_t* sor) {
    *fixed_point = l == 0 ? refine->vr_fixed_point : 1u;
    // vr_sor is the level-0 budget and coarser levels fall off twice as fast as the grid
    // shrinks. A flat slope spent the finest level's raised count on every level: with the
    // 4x budget at 7 that would be 7, 6, 5, 4 - eight extra dispatches and barriers per
    // pair for sweeps that buy nothing, since a half-size level crosses the same relative
    // distance in fewer of them. This gives 7, 5, 3, 2 instead.
    const uint32_t drop = l * 2u;
    const uint32_t s = refine->vr_sor > drop ? refine->vr_sor - drop : DIS_VR_SOR_FLOOR;
    *sor = s < DIS_VR_SOR_FLOOR ? DIS_VR_SOR_FLOOR : s;
}

static void dis_vr_level(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, uint32_t l,
                         uint32_t lw, uint32_t lh, const DisRefine* refine, bool full) {
    const uint32_t gw = (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE;
    const uint32_t gh = (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE;

    uint32_t vr_fixed_point = 0;
    uint32_t vr_sor = 0;
    dis_vr_budget(refine, l, &vr_fixed_point, &vr_sor);
    // The hybrid's level 0 starts from Farneback's field, which is already close.
    if (d->flow_engine == DIS_ENGINE_HYBRID) {
        if (d->fb_hybrid_fp > 0 && vr_fixed_point > d->fb_hybrid_fp) vr_fixed_point = d->fb_hybrid_fp;
        if (d->fb_hybrid_sor > 0 && vr_sor > d->fb_hybrid_sor) vr_sor = d->fb_hybrid_sor;
    }

    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_prep.pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_prep_sets[slot][l], 0, NULL);
    wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
    dis_compute_barrier(cmd);

    if (full) {
        wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_d1.pipeline);
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->vr_d1_set[l], 0, NULL);
        wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
        dis_compute_barrier(cmd);

        wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_d2.pipeline);
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->vr_d2_set[l], 0, NULL);
        wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
        dis_compute_barrier(cmd);
        if (l == 0) DIS_PROF(cmd, "L0 vr prep+deriv");

        for (uint32_t k = 0; k < vr_fixed_point; k++) {
            DisVrWPC wpc;
            wpc.alpha2 = DIS_VR_ALPHA * 0.5f;
            wpc.eps2 = DIS_VR_EPS * DIS_VR_EPS;
            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_w.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout,
                                      0, 1, &d->vr_w_set[l], 0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(wpc), &wpc);
            wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
            dis_compute_barrier(cmd);
            if (l == 0) DIS_PROF(cmd, "L0 vr w");

            DisVrCoefPC cpc;
            cpc.delta2 = DIS_VR_DELTA * 0.5f;
            cpc.gamma2 = DIS_VR_GAMMA * 0.5f;
            cpc.zeta2 = DIS_VR_ZETA * DIS_VR_ZETA;
            cpc.eps2 = DIS_VR_EPS * DIS_VR_EPS;
            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_coef.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout,
                                      0, 1, &d->vr_coef_set[l], 0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(cpc), &cpc);
            wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
            dis_compute_barrier(cmd);
            if (l == 0) DIS_PROF(cmd, "L0 vr coef");

            // ab then ba per iteration, so every iteration ends in vr_dw[0]. Fusing iterations
            // into shared-memory tiles was measured and lost on Adreno 750 (1.9 ms against 1.45
            // at level 0 with a third of the dispatches), so the half-sweeps stay separate.
            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_sor.pipeline);
            for (uint32_t it = 0; it < vr_sor; it++) {
                for (int parity = 0; parity < 2; parity++) {
                    DisVrSorPC spc;
                    spc.omega = DIS_VR_OMEGA;
                    spc.parity = parity;
                    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                                              d->vr_pipeline_layout, 0, 1,
                                              parity ? &d->vr_sor_ba_set[l] : &d->vr_sor_ab_set[l],
                                              0, NULL);
                    wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT,
                                         0, sizeof(spc), &spc);
                    wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
                    dis_compute_barrier(cmd);
                }
            }
            if (l == 0) DIS_PROF(cmd, "L0 vr sor");
        }
    }

    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_add.pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_add_set[l], 0, NULL);
    wfg_vkd.CmdDispatch(cmd, gw, gh, 1);
    dis_compute_barrier(cmd);
}

// Farneback: the polynomial expansion of every level of the frame just put in slot. It is the
// second frame of this pair and the first of the next, so each frame is expanded once.
static void dis_fb_expand(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, uint32_t L,
                          uint32_t w, uint32_t h) {
    DisFbPolyPC ppc;
    ppc.sigma = d->fb_poly_sigma;
    ppc.ig11 = d->fb_ig[0];
    ppc.ig03 = d->fb_ig[1];
    ppc.ig33 = d->fb_ig[2];
    ppc.ig55 = d->fb_ig[3];
    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_fb_poly.pipeline);
    wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ppc), &ppc);
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->fb_poly_sets[slot][l], 0, NULL);
        wfg_vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
    }
    dis_compute_barrier(cmd);
}

// Farneback flow for the pair ending in slot, into flow_refined, from the coarsest level down
// to min_level: each level starts from the one above (the coarsest from the previous pair's
// flow, or zero), then fb_iterations of equations summed over the window and solved. Two
// dispatches an iteration.
static void dis_farneback(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, uint32_t L,
                          uint32_t w, uint32_t h, uint32_t min_level, bool l1_apart) {
    for (uint32_t li = 0; li + min_level < L; li++) {
        const uint32_t l = L - 1u - li;
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        for (int it = 0; it < d->fb_iterations; it++) {
            DisFbSumPC spc;
            spc.radius = d->fb_radius;
            spc.sigma = d->fb_window_sigma;
            spc.useFlow = (it > 0 || l + 1u < L || d->prior_valid) ? 1 : 0;
            spc.robust = d->fb_robust;

            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_fb_hsum.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                      (l == 1 && l1_apart && it > 0) ? &d->fb_hsum1_sets[slot]
                                      : &d->fb_hsum_sets[slot][l][it > 0 ? 1 : 0], 0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(spc), &spc);
            wfg_vkd.CmdDispatch(cmd, (lw + 63u) / 64u, lh, 1);
            dis_compute_barrier(cmd);

            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_fb_vsolve.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                      (l == 1 && l1_apart) ? &d->fb_vsolve1_set : &d->fb_vsolve_sets[l],
                                      0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(spc), &spc);
            wfg_vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                            (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
            dis_compute_barrier(cmd);
        }
    }
    DIS_PROF(cmd, "farneback");
}

// One pass of dis_fill.comp: 0 scores how well the next frame confirms each vector, 1 writes
// the field with the unconfirmed ones replaced. The caller places the barriers.
static void dis_fill_pass(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, int mode, int accel,
                          uint32_t w, uint32_t h) {
    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_fill.pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->fill_sets[slot][d->hist_parity], 0, NULL);
    const struct { int mode; int accel; } fpc = {mode, accel};
    wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(fpc),
                         &fpc);
    wfg_vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
}

static void dis_splat_pass(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, uint32_t generations,
                           int mode);

void vkr_dis_process(VkrDis* d, VkCommandBuffer cmd, VkImage source, uint32_t width,
                     uint32_t height, uint32_t generations) {
    if (!d || !d->built || d->unavailable) return;

    d->last_generations = generations;

    dis_prime_layouts(d, cmd);
    DIS_PROF_BEGIN(d, cmd);

    const DisRefine refine = dis_refine_for(generations);
    const uint32_t L = d->levels;
    const uint32_t coarse = L - 1;
    const uint32_t w = d->built_extent.width;
    const uint32_t h = d->built_extent.height;
    const uint32_t full_w = d->content.width;
    const uint32_t full_h = d->content.height;
    const int32_t cx = d->content.x;
    const int32_t cy = d->content.y;
    (void)width; (void)height;

    const uint32_t slot = (uint32_t)(d->frame_count % DIS_SLOTS);
    DisImage* full_dst = &d->color[slot];
    DisImage* flow_dst = &d->flow_color[slot];

    dis_barrier(cmd, full_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    dis_blit_rect(cmd, source, cx, cy, full_w, full_h,
                  full_dst->image, 0, 0, full_w, full_h, VK_FILTER_LINEAR);
    dis_barrier(cmd, full_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

    dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    dis_blit_rect(cmd, source, cx, cy, full_w, full_h,
                  flow_dst->image, 0, 0, w, h, VK_FILTER_LINEAR);
    for (uint32_t l = 1; l < L; l++) {
        dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        dis_blit_mip(cmd, flow_dst->image, l - 1, l, w >> (l - 1), h >> (l - 1), w >> l, h >> l);
    }
    dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

    d->next_idx = (int)slot;
    d->prev_idx = (int)((slot + DIS_SLOTS - 1u) % DIS_SLOTS);
    d->active_slot = slot;
    d->frame_count++;

    for (uint32_t l = 0; l < L; l++) {
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_luma.pipeline);
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                  &d->luma_sets[slot][l], 0, NULL);
        wfg_vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
    }

    const bool wants_flow = generations > 0 || d->debug_flow;
    // The field splatted to every generated frame's time (dis_splat.comp): cleared here, under
    // the luma pass's barrier; splatted next to the pack pass and resolved after it.
    const uint32_t splat_gens =
        generations < VKR_DIS_MAX_GENERATIONS ? generations : VKR_DIS_MAX_GENERATIONS;
    if (splat_gens > 0) dis_splat_pass(d, cmd, slot, splat_gens, 0);

    dis_compute_barrier(cmd);
    DIS_PROF(cmd, "copy+luma");

    const bool farneback = d->flow_engine == DIS_ENGINE_FARNEBACK;
    const bool hybrid = d->flow_engine == DIS_ENGINE_HYBRID;
    if (farneback || hybrid) dis_fb_expand(d, cmd, slot, L, w, h);

    if (!wants_flow) {
        d->prior_valid = false;
        return;
    }
    if (farneback) {
        dis_farneback(d, cmd, slot, L, w, h, 0, false);
    } else {
        // The hybrid's coarse levels are Farneback's; the search on the levels below starts from
        // them (its coarse estimate is flow_refined[l + 1]).
        if (hybrid) {
            // With DIS on level 1 too, Farneback still solves level 1 - into fb_l1, as the other
            // start of level 0's search.
            const bool l1_apart = d->fb_hybrid_l1_start && d->fb_hybrid_level == 2u && L > 2;
            dis_farneback(d, cmd, slot, L, w, h, l1_apart ? 1u : d->fb_hybrid_level, l1_apart);
        }
        DisGradientPC gpc;
        gpc.lesser = 3.0f;
        gpc.upper = 10.0f;
        gpc.normVal = 1.0f / (2.0f * 10.0f + 4.0f * 3.0f);

        for (uint32_t l = 0; l < L; l++) {
            const uint32_t lw = w >> l;
            const uint32_t lh = h >> l;
            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_gradient.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                      &d->grad_sets[slot][l], 0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(gpc), &gpc);
            wfg_vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                            (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
        }

        dis_compute_barrier(cmd);
        DIS_PROF(cmd, "gradient");

        static const char* const k_search[DIS_MAX_LEVELS] = {"L0 search", "L1 search", "L2 search", "L3 search", "L4 search", "L5 search", "L6 search", "L7 search"};
        static const char* const k_vr[DIS_MAX_LEVELS] = {"L0 refine", "L1 refine", "L2 refine", "L3 refine", "L4 refine", "L5 refine", "L6 refine", "L7 refine"};
        static const char* const k_inv[DIS_MAX_LEVELS] = {"L0 inverse", "L1 inverse", "L2 inverse", "L3 inverse", "L4 inverse", "L5 inverse", "L6 inverse", "L7 inverse"};
        static const char* const k_prop[DIS_MAX_LEVELS] = {"L0 propagate", "L1 propagate", "L2 propagate", "L3 propagate", "L4 propagate", "L5 propagate", "L6 propagate", "L7 propagate"};
        (void)k_search; (void)k_vr; (void)k_inv; (void)k_prop;
        for (uint32_t li = 0; li < L; li++) {
            const uint32_t l = coarse - li;
            if (hybrid && l >= d->fb_hybrid_level) continue;
            const uint32_t lw = w >> l;
            const uint32_t lh = h >> l;
            const uint32_t spw = dis_sparse_extent(lw);
            const uint32_t sph = dis_sparse_extent(lh);

            DisInversePC ipc;
            ipc.level = (int)l;
            ipc.coarseLevel = (int)coarse;
            ipc.priorLevels = d->prior_valid ? (int)((1u << L) - 1u) : 0;
            ipc.fbLevels = (hybrid && d->fb_hybrid_l1_start && d->fb_hybrid_level == 2u && L > 2) ? 1 : 0;
            wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_inverse.pipeline);
            wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                      &d->inverse_sets[slot][l], 0, NULL);
            wfg_vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ipc), &ipc);
            wfg_vkd.CmdDispatch(cmd, (spw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                            (sph + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);

            dis_compute_barrier(cmd);
            DIS_PROF(cmd, k_inv[l]);

            uint32_t prop_passes = dis_prop_steps_for(l, L, refine.prop_floor);
            const uint32_t prop_doubling = prop_passes;
            if (prop_passes & 1u) prop_passes++;

            for (uint32_t k = 0; k < prop_passes; k++) {
                VkDescriptorSet prop_set =
                    (k & 1u) ? d->prop_ba_sets[slot][l] : d->prop_ab_sets[slot][l];
                DisPropPC ppc;
                ppc.dist = k < prop_doubling ? (int)(1u << k) : 1;
                wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_propagate.pipeline);
                wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0,
                                          1, &prop_set, 0, NULL);
                wfg_vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                     sizeof(ppc), &ppc);
                wfg_vkd.CmdDispatch(cmd, (spw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                                (sph + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);

                dis_compute_barrier(cmd);
            }
            DIS_PROF(cmd, k_prop[l]);

            dis_dispatch(d, cmd, d->pass_densify.pipeline, d->densify_sets[slot][l], lw, lh);

            dis_compute_barrier(cmd);
            DIS_PROF(cmd, k_search[l]);

            if (l < refine.vr_levels) {
                // Without the full refinement the pass still moves the dense field into
                // flow_refined, where the rest of the pipeline reads level 0.
                dis_vr_level(d, cmd, slot, l, lw, lh, &refine,
                             !hybrid || (d->fb_hybrid_vr && (l == 0 || d->fb_hybrid_vr_upper)));
                DIS_PROF(cmd, k_vr[l]);
            }
        }
    }

    if (generations > 0 || d->debug_flow) {
        const uint32_t hist_w = full_w > 1u ? full_w / 2u : 1u;
        const uint32_t hist_h = full_h > 1u ? full_h / 2u : 1u;
        const uint32_t hist_dir = 1u - d->hist_parity;
        const int hist_reset = d->hist_valid ? 0 : 1;

        // The packed field, how well the next frame confirms each vector (both from the refined
        // field) and the overlay history (from the colour pair) are independent of each other,
        // so they share one barrier.
        // The pack keeps the previous pair's field for the fill's acceleration estimate; it is
        // that pair's only when that pair computed one.
        const int had_prior = d->prior_valid ? 1 : 0;
        wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_pack.pipeline);
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->pack_set, 0, NULL);
        wfg_vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
        d->prior_valid = true;

        wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_hist.pipeline);
        wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->hist_sets[slot][hist_dir], 0, NULL);
        wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                             sizeof(hist_reset), &hist_reset);
        wfg_vkd.CmdDispatch(cmd, (hist_w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (hist_h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
        d->hist_parity = hist_dir;
        d->hist_valid = true;
        dis_compute_barrier(cmd);

        // How well the next frame confirms each vector - after the pack has kept the previous
        // pair's - then the field the interpolation uses, unconfirmed vectors replaced
        // (dis_fill.comp).
        dis_fill_pass(d, cmd, slot, 0, had_prior, w, h);
        dis_compute_barrier(cmd);
        dis_fill_pass(d, cmd, slot, 1, had_prior, w, h);
        dis_compute_barrier(cmd);

        // Which real frame a true occlusion takes, and how sure, per level-0 texel.
        dis_dispatch(d, cmd, d->pass_side.pipeline, d->side_sets[slot], w, h);
        if (splat_gens > 0) dis_splat_pass(d, cmd, slot, splat_gens, 1);
        dis_compute_barrier(cmd);
        DIS_PROF(cmd, "pack+hist+side");
        if (splat_gens > 0) {
            dis_splat_pass(d, cmd, slot, splat_gens, 2);
            dis_compute_barrier(cmd);
            DIS_PROF(cmd, "splat");
        }
    }
}

// One pass of dis_splat.comp over every generated frame of the pair: 0 clears, 1 splats, 2
// writes the winners. The caller places the barriers.
static void dis_splat_pass(VkrDis* d, VkCommandBuffer cmd, uint32_t slot, uint32_t generations,
                           int mode) {
    const struct { int gens; int mode; } spc = {(int)generations, mode};
    const uint32_t fw = d->flow_out.extent.width;
    const uint32_t fh = d->flow_out.extent.height;
    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_splat.pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->splat_sets[slot], 0, NULL);
    wfg_vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(spc),
                         &spc);
    wfg_vkd.CmdDispatch(cmd, (fw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (fh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
}

static void dis_render_into(VkrDis* d, VkCommandBuffer cmd, float t, int layer, int debug_mode,
                            VkImage target_image, uint32_t width, uint32_t height,
                            VkImage base_image, VkImageView target_view) {
    const uint32_t w = d->content.width;
    const uint32_t h = d->content.height;

    DisInterpPC ipc;
    ipc.t = t;
    ipc.debugMode = debug_mode;
    ipc.layer = layer;

    // The direct path is valid for the compositor's full-size RGBA8 targets.
    // Each generation has its own set; never mutate a set used by another
    // generation recorded in this same command buffer.
    const bool direct = target_view != VK_NULL_HANDLE && d->content.x == 0 &&
        d->content.y == 0 && w == width && h == height;
    VkDescriptorSet output_set = d->interp_sets[d->active_slot][d->hist_parity][layer];
    VkImageView output_view = direct ? target_view : d->view_interp_out;
    if (output_view == VK_NULL_HANDLE) return;
    VkImageView* cached = &d->interp_targets[d->active_slot][d->hist_parity][layer];
    if (*cached != output_view) {
        VkDescriptorImageInfo info = {VK_NULL_HANDLE, output_view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet write = {0};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = output_set;
        write.dstBinding = 5;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        write.pImageInfo = &info;
        wfg_vkd.UpdateDescriptorSets(d->device, 1, &write, 0, NULL);
        *cached = output_view;
    }
    if (direct)
        dis_barrier(cmd, target_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
    wfg_vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_interp.pipeline);
    wfg_vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                              &output_set, 0, NULL);
    wfg_vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ipc), &ipc);
    wfg_vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
    DIS_PROF(cmd, "interp shader");
    // The compositor supplies the final compute->present/copy dependency.
    // Avoid an extra compute->read barrier on every direct generated frame.
    if (direct) return;

    dis_barrier(cmd, d->interp_out.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);

    dis_barrier(cmd, target_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_ACCESS_TRANSFER_WRITE_BIT);

    const uint32_t comp_w = d->built_full_extent.width;
    const uint32_t comp_h = d->built_full_extent.height;
    const float tsx = comp_w > 0 ? (float)width / (float)comp_w : 1.0f;
    const float tsy = comp_h > 0 ? (float)height / (float)comp_h : 1.0f;
    const int32_t tx = (int32_t)((float)d->content.x * tsx + 0.5f);
    const int32_t ty = (int32_t)((float)d->content.y * tsy + 0.5f);
    uint32_t tw = (uint32_t)((float)w * tsx + 0.5f);
    uint32_t th = (uint32_t)((float)h * tsy + 0.5f);
    if (tw == 0) tw = 1;
    if (th == 0) th = 1;
    if ((uint32_t)tx + tw > width) tw = width - (uint32_t)tx;
    if ((uint32_t)ty + th > height) th = height - (uint32_t)ty;

    if (base_image != VK_NULL_HANDLE) {
        const int32_t cx = d->content.x;
        const int32_t cy = d->content.y;
        const int32_t cr = cx + (int32_t)w;
        const int32_t cb = cy + (int32_t)h;
        const int32_t trx = tx + (int32_t)tw;
        const int32_t tby = ty + (int32_t)th;
        const int32_t src[4][4] = {
            {0,  0,  cx,                    (int32_t)comp_h},
            {cr, 0,  (int32_t)comp_w - cr,  (int32_t)comp_h},
            {cx, 0,  (int32_t)w,            cy},
            {cx, cb, (int32_t)w,            (int32_t)comp_h - cb},
        };
        const int32_t dst[4][4] = {
            {0,   0,   tx,                     (int32_t)height},
            {trx, 0,   (int32_t)width - trx,   (int32_t)height},
            {tx,  0,   (int32_t)tw,            ty},
            {tx,  tby, (int32_t)tw,            (int32_t)height - tby},
        };
        bool any_strip = false;
        for (uint32_t i = 0; i < 4; i++) {
            if (src[i][2] <= 0 || src[i][3] <= 0 || dst[i][2] <= 0 || dst[i][3] <= 0) continue;
            dis_blit_rect(cmd, base_image, src[i][0], src[i][1],
                          (uint32_t)src[i][2], (uint32_t)src[i][3],
                          target_image, dst[i][0], dst[i][1],
                          (uint32_t)dst[i][2], (uint32_t)dst[i][3], VK_FILTER_LINEAR);
            any_strip = true;
        }
        if (any_strip) {
            dis_barrier(cmd, target_image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        }
    }

    dis_blit_rect(cmd, d->interp_out.image, 0, 0, w, h,
                  target_image, tx, ty, tw, th, VK_FILTER_LINEAR);

    dis_barrier(cmd, d->interp_out.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
    DIS_PROF(cmd, "interp blit");
}

void vkr_dis_generate_into(VkrDis* d, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image, VkImageView target_view,
                           uint32_t width, uint32_t height, VkImage base_image) {
    (void)target_index;
    if (!d || !d->built || d->unavailable) return;
    if (d->last_generations == 0 || generation >= d->last_generations ||
        generation >= VKR_DIS_MAX_GENERATIONS) return;

    const float t = (float)(generation + 1) / (float)(d->last_generations + 1);
    dis_render_into(d, cmd, t, (int)generation, d->debug_flow ? 1 : 0, target_image, width, height,
                    base_image, target_view);
}

void vkr_dis_debug_into(VkrDis* d, VkCommandBuffer cmd, VkImage target_image, uint32_t width,
                        uint32_t height) {
    if (!d || !d->built || d->unavailable || !d->debug_flow) return;
    if (d->frame_count < 2) return;
    dis_render_into(d, cmd, 0.5f, 0, 1, target_image, width, height, VK_NULL_HANDLE, VK_NULL_HANDLE);
}

void vkr_dis_forget_targets(VkrDis* d) {
    if (d) memset(d->interp_targets, 0, sizeof(d->interp_targets));
}

void vkr_dis_reset(VkrDis* d) {
    if (!d) return;
    d->frame_count = 0;
    d->last_generations = 0;
    d->prev_idx = 0;
    d->next_idx = 0;
    d->active_slot = 0;
    d->src_sample_ns = 0;
    d->src_last_frames = 0;
    d->src_frame_accum = 0.0f;
    d->src_time_accum = 0.0f;
    d->src_interval = 0.0f;
    d->src_samples = 0;
    d->smoothed_desired = 0.0f;
    d->planned_gen = 0;
    d->gen_high_streak = 0;
    d->gen_low_streak = 0;
    d->plan_log_gen = -1;
    d->plan_log_ns = 0;
    d->hist_parity = 0;
    d->hist_valid = false;
    d->prior_valid = false;
}
