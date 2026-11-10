#!/usr/bin/env python3
"""Run the production present loop with deterministic SurfaceControl/ADPF hooks."""
import pathlib
import subprocess
import tempfile
root = pathlib.Path(__file__).resolve().parents[1]
source = (root / 'renderer/displayx.cpp').read_text()
loop = source[source.index('void DisplayX::presentThreadLoop()'):source.index('void DisplayX::start()')]
harness = r'''
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <memory>
#include <mutex>
#include <vector>
#include <unistd.h>
struct ASurfaceTransaction {};
struct APerformanceHintManager {};
struct APerformanceHintSession {};
struct Composer { void *dstBuffer{}; };
struct Drawable { Composer *composerTexture{}; void *ahb{}; bool isDisplayX{}; };
struct Window { void *control{}; bool enabled{}, backPressureEnabled{}; };
struct Request { Window *window{}; Drawable *drawable{}; int sync_fence{-1}, clientFd{-1}, presentMode{}; };
struct Queue {
    bool empty() { return false; }
    std::deque<std::unique_ptr<Request>> getLast() { return {}; }
    std::deque<std::unique_ptr<Request>> buildWindowTree() { return {}; }
};
struct Lock {
    std::mutex mutex;
    auto lock() { return std::unique_lock<std::mutex>(mutex); }
    template<class L, class P> void wait(L &, P p) { assert(p()); }
};
struct Cache { void detachEnv(void *) {} };
struct Server { float refreshRate{60}; };
struct ComposerAPI { bool isSuitableForColorSwap(Drawable *) { return false; } };
struct DisplayX {
    struct OnCompleteContext { std::vector<std::unique_ptr<Request>> requests; };
    Lock presentLock;
    Queue presentRequests;
    bool stopped{}, requestUpdate{true}, presentRR{true}, hasSurface{true}, surfaceChanged{true}, paused{};
    bool perfMode{true}; int eventsPending{}, vsyncId{-1};
    void *env{};
    Cache cacheValue, *cache{&cacheValue};
    Server server, *xServer{&server};
    ComposerAPI composer, *effectComposer{&composer};
    APerformanceHintManager *performanceHintManager{};
    APerformanceHintSession *performanceHintSession{};
    bool isPerformanceHintAPIAvailable() { return true; }
    int64_t getCurrentTimeNanos() { static int64_t now = 1000000000; return now += 250000; }
    static void onCompleteCallback(void *, void *) {}
    void presentThreadLoop();
};
constexpr int VK_PRESENT_MODE_FIFO_KHR = 2;
static DisplayX *active;
static int frames{}, created{}, closed{}, deleted{}, reports{}, updated{};
static int64_t target{};
static APerformanceHintManager manager;
static APerformanceHintSession session;
auto pfnASurfaceTransactionCreate = +[] { return new ASurfaceTransaction; };
auto pfnASurfaceTransactionDelete = +[](ASurfaceTransaction *t) { ++deleted; delete t; };
auto pfnASurfaceTransactionSetFrameTimeline = +[](ASurfaceTransaction *, int) {};
auto pfnASurfaceTransactionSetBuffer = +[](ASurfaceTransaction *, void *, void *, int) {};
auto pfnASurfaceTransactionSetEnableBackPressure = +[](ASurfaceTransaction *, void *, bool) {};
auto pfnASurfaceTransactionSetOnComplete = +[](ASurfaceTransaction *, void *, void (*)(void *, void *)) {};
auto pfnAPerformanceHintGetManager = +[] { return &manager; };
auto pfnAPerformanceHintCreateSession = +[](APerformanceHintManager *, const int32_t *ids, size_t n, int64_t t) {
    assert(ids && n == 1 && t == 16666666); target = t; ++created; return &session;
};
auto pfnAPerformanceHintUpdateTargetWorkDuration = +[](APerformanceHintSession *, int64_t t) {
    assert(t == 8333333); target = t; ++updated; return 0;
};
auto pfnAPerformanceHintCloseSession = +[](APerformanceHintSession *) { ++closed; };
auto pfnAPerformanceHintReportActualWorkDuration = +[](APerformanceHintSession *, int64_t work) {
    assert(work == 250000 && work < target); ++reports; return 0;
};
auto pfnASurfaceTransactionApply = +[](ASurfaceTransaction *) {
    ++frames; active->requestUpdate = true;
    if (frames == 1) active->perfMode = true; // Enable in-game after starting disabled.
    if (frames == 2) active->server.refreshRate = 120;
    if (frames == 3) active->perfMode = false;
    if (frames == 4) active->stopped = true;
};
'''
main = r'''
int main() {
    DisplayX renderer; active = &renderer; renderer.perfMode = false;
    renderer.presentThreadLoop();
    assert(frames == 4 && created == 1 && closed == 1 && reports == 2 && updated == 1 && deleted == 1);
    puts("PASS: DisplayX ADPF refresh target, CPU work duration, live toggle and transaction cleanup");
}
'''
with tempfile.TemporaryDirectory(prefix='displayx-adpf-') as d:
    path = pathlib.Path(d)
    (path / 'test.cpp').write_text(harness + loop + main)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', str(path / 'test.cpp'), '-pthread', '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
