// WFG adapter for the existing LSFG compositor/presentation path.
// The DIS implementation is MIT; see cpp/wfg/LICENSE for attribution.
#include "vkr_wfg.h"
#include "wfg_quality.h"
#include "vkr_dis.h"
#include "../lsfg/lsfg_pacer.hpp"
#include <algorithm>
#include <new>

struct VkrWfg {
    VkrDis* dis{};
    lsfg::LsfgPacer pacer;
    uint64_t sourceFrames{}, pendingSourceFrames{}, realFrames{};
    bool seenSource{}, newFrame{}, generating{}, prepared{}, capture{true};
};

bool vkr_wfg_bind_dispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance) {
    return vkr_dis_bind_dispatch(gipa, instance);
}
VkrWfg* vkr_wfg_create(VkDevice device, VkPhysicalDevice physicalDevice) {
    auto* engine = new (std::nothrow) VkrWfg;
    if (!engine) return nullptr;
    engine->dis = vkr_dis_create(device, physicalDevice);
    if (!engine->dis) { delete engine; return nullptr; }
    vkr_dis_set_direct_output(engine->dis, true);
    return engine;
}
void vkr_wfg_destroy(VkrWfg* engine) {
    if (!engine) return;
    vkr_dis_destroy(engine->dis);
    delete engine;
}
void vkr_wfg_configure(VkrWfg* engine, uint32_t multiplier, uint32_t targetRate,
                        uint32_t quality, float refreshRate) {
    if (!engine) return;
    auto config = engine->pacer.Config();
    config.multiplier = std::clamp(multiplier, 2u, 4u);
    config.target_rate = targetRate;
    config.refresh_rate = refreshRate;
    engine->pacer.SetConfig(config);
    vkr_dis_configure(engine->dis, wfgFlowSide(quality), targetRate, refreshRate);
}
bool vkr_wfg_prepare(VkrWfg* engine, uint32_t width, uint32_t height, VkFormat format) {
    if (!engine) return false;
    const VkrDisContentRect content{0, 0, width, height};
    const bool rebuild = vkr_dis_needs_rebuild(engine->dis, width, height, format, content);
    engine->prepared = vkr_dis_prepare(engine->dis, width, height, format, content);
    if (rebuild) {
        engine->pacer.Reset();
        engine->seenSource = engine->generating = false;
        engine->realFrames = 0;
    }
    return engine->prepared;
}
uint32_t vkr_wfg_plan(VkrWfg* engine, uint32_t capacity, uint64_t sourceFrames,
                        float refreshRate) {
    if (!engine || !engine->prepared) return 0;
    engine->newFrame = !engine->seenSource || engine->sourceFrames != sourceFrames;
    if (!engine->newFrame) return 0; // cursor/UI-only redraw: no motion work or history advance
    engine->pendingSourceFrames = sourceFrames;
    auto config = engine->pacer.Config();
    config.refresh_rate = refreshRate;
    engine->pacer.SetConfig(config);
    const auto plan = engine->pacer.Plan(std::min(capacity, VKR_DIS_MAX_GENERATIONS), sourceFrames);
    const auto stats = engine->pacer.Stats();
    const float rate = stats.source_rate;
    // Keep the DIS motion-range safety floor, independently of output scheduling.
    // The old WFG integer multiplier ladder/queue trimming is deliberately unused.
    engine->generating = rate >= (engine->generating ? 20.f : 24.f);
    const float target = config.target_rate > 0
        ? std::min(static_cast<float>(config.target_rate), refreshRate) : refreshRate;
    const bool noHeadroom = stats.rates_settled && target > 0.f && rate >= target * .99f;
    const bool capture = !stats.rates_settled || (engine->generating && !noHeadroom);
    if (!capture && engine->capture) {
        vkr_dis_reset(engine->dis);
        engine->realFrames = 0;
    }
    engine->capture = capture;
    // A saturated display or a source below DIS's useful range goes through
    // the normal compositor until interpolation becomes useful again.
    if (!capture) {
        engine->seenSource = true;
        engine->sourceFrames = sourceFrames;
    }
    return engine->capture && engine->generating && engine->realFrames >= 2 && plan.warm
        ? static_cast<uint32_t>(plan.generations) : 0;
}
bool vkr_wfg_needs_history(const VkrWfg* engine) {
    return engine && engine->prepared && engine->capture && engine->newFrame;
}
void vkr_wfg_process(VkrWfg* engine, VkCommandBuffer cmd, VkImage source,
                      uint32_t width, uint32_t height, uint32_t generations) {
    if (!vkr_wfg_needs_history(engine)) return;
    engine->newFrame = false;
    engine->seenSource = true;
    engine->sourceFrames = engine->pendingSourceFrames;
    ++engine->realFrames;
    vkr_dis_process(engine->dis, cmd, source, width, height, generations);
}
void vkr_wfg_generate_into(VkrWfg* engine, VkCommandBuffer cmd, uint32_t generation,
                            VkImage target, VkImageView view, uint32_t width, uint32_t height) {
    if (!engine || !engine->prepared) return;
    vkr_dis_generate_into(engine->dis, cmd, generation, generation, target, view,
                          width, height, VK_NULL_HANDLE);
}
void vkr_wfg_forget_targets(VkrWfg* engine) {
    if (!engine) return;
    vkr_dis_forget_targets(engine->dis);
    vkr_dis_reset(engine->dis);
    engine->pacer.Reset();
    engine->seenSource = engine->newFrame = engine->generating = false;
    engine->capture = true;
    engine->realFrames = 0;
}
