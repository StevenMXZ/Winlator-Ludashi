// Appended to the production recordCmdBuf implementation by test_composition.py.
static void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
int main() {
#ifdef LUDASHI_NATIVE_X11
    constexpr bool native=true;
#else
    constexpr bool native=false;
#endif
    VulkanRendererContext context;
    using Draw=VulkanRendererContext::DrawEntry;
    std::vector<Draw> draws{{20,21,0,0,0,1280,720,true,false}};
    const VkRect2D full{{0,0},{1280,720}};
    auto record = [&](bool cursor=false, VkRect2D scissor=VkRect2D{{0,0},{1280,720}},
                      float ox=0, float sx=1, bool frameGen=false) {
        context.vk_={};
        std::vector<VkImageMemoryBarrier> a,b,c;
        context.recordCmdBuf(1,0,draws,a,b,c,0,false,ox,0,sx,1,1280,720,
                             10,10,0,0,16,16,cursor,scissor,0,frameGen);
    };
    record();
    Check(context.vk_.pass==(native?2:1),"fullscreen render pass");
    Check(context.vk_.drawPipelines==std::vector<uint64_t>{4},"existing opaque window pipeline in both builds");
    Check(context.vk_.alpha==std::vector<int>{0},"window must still force opaque alpha");
    Check(!context.nativeMetrics.opaque && !context.nativeMetrics.noClear,"disabled diagnostics");
    record(true);
    Check(context.vk_.drawPipelines==std::vector<uint64_t>{4,3},"cursor must restore blending");
    Check(context.vk_.alpha==std::vector<int>{0,1},"cursor alpha must be retained");
    record(false,full,128,.8f);
    Check(context.vk_.pass==1,"letterbox margins need CLEAR");
    record(false,{{1,0},{1279,720}});
    Check(context.vk_.pass==1,"partial scissor needs CLEAR");
    draws[0].w=1200; record(); Check(context.vk_.pass==1,"smaller drawable needs CLEAR");
    draws[0].w=1280;
    draws.push_back({22,23,0,40,40,40,40,true,false}); record();
    Check(context.vk_.pass==1,"multiple layers use general pass");
    draws.pop_back();
    draws[0].ds=0; record(); Check(context.vk_.pass==1,"missing descriptor cannot discard background");
    draws[0].ds=21;
    context.opaquePipeline=0; record(true);
    Check(context.vk_.pass==1 && context.vk_.drawPipelines==std::vector<uint64_t>{3,3},"pipeline creation fallback");
    context.opaquePipeline=4;
    context.nativeNoClearPass=0; record(); Check(context.vk_.pass==1,"render-pass creation fallback");
    context.nativeNoClearPass=2;
    const uint64_t effectPipelines[]={6,7,8,9,15,8};
    for (int effect=0;effect<6;++effect) {
        context.filterMode=effect==0?2:effect==1?3:effect==4?4:effect==5?5:0;
        context.postFXMode=effect==2?1:0;
        context.stretchMode=effect==3?1:0;
        record(true);
        Check(context.vk_.pass==1,"effects need general render pass");
        Check(context.vk_.drawPipelines==std::vector<uint64_t>{effectPipelines[effect],3},"effect and cursor pipeline selection");
    }
    context.filterMode=context.postFXMode=context.stretchMode=0;
    record(false,full,0,1,true);
    Check(context.vk_.pass==13,"frame generation must retain its own render pass");
    context.nativeDiagnosticsEnabled=true; record();
    Check(context.nativeMetrics.opaque==(native?1u:0u) && context.nativeMetrics.noClear==(native?1u:0u),"native diagnostics counters");
    Check(!NativeCoversOutput(-1,-1,INFINITY,1) && !NativeCoversOutput(-1,-1,NAN,1),"invalid coverage must fall back");
    std::cout << "PASS: " << (native?"native":"Java") << " production command recorder: opacity, fullscreen/letterbox/scissor, cursor alpha, overlays, effects and driver fallbacks\n";
}
