#include <android/surface_control.h>
#include <android/performance_hint.h>
#include <functional>
#include <string>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <cassert>
struct ASurfaceControl {
    bool root{}, visible{}, backPressure{}, released{};
    int order{}, transparency{};
    ARect source{}, destination{};
    AHardwareBuffer *buffer{};
};
struct ASurfaceTransaction {
    std::vector<std::function<void()>> apply;
    void *value{};
    ASurfaceTransaction_OnComplete complete{};
    bool clear{};
};
static std::vector<ASurfaceControl *> controls;
static std::vector<std::pair<void *, ASurfaceTransaction_OnComplete>> completions;
static std::mutex mutex;
static int created{}, released{}, fences{}, properties{}, transactions{};
extern "C" int TestTransactions() { return transactions; }
extern "C" int TestProperties() { return properties; }
static int64_t presentationTime{};
extern "C" ASurfaceControl *ASurfaceControl_create(ASurfaceControl *, const char *) {
    auto *result = new ASurfaceControl;
    controls.push_back(result);
    ++created;
    return result;
}
extern "C" ASurfaceControl *ASurfaceControl_createFromWindow(ANativeWindow *, const char *name) {
    auto *result = ASurfaceControl_create(nullptr, name);
    result->root = true;
    return result;
}
extern "C" void ASurfaceControl_release(ASurfaceControl *control) {
    assert(!control->released);
    control->released = true;
    ++released;
}
extern "C" ASurfaceTransaction *ASurfaceTransaction_create() {
    ++transactions;
    return new ASurfaceTransaction;
}
extern "C" void ASurfaceTransaction_delete(ASurfaceTransaction *transaction) {
    delete transaction;
}
extern "C" void TestFinish() {
    std::vector<std::pair<void *, ASurfaceTransaction_OnComplete>> ready;
    {
        std::lock_guard lock(mutex);
        ready.swap(completions);
    }
    for (auto &[value, callback] : ready)
        callback(value, nullptr);
}
extern "C" void ASurfaceTransaction_apply(ASurfaceTransaction *transaction) {
    for (auto &operation : transaction->apply)
        operation();
    if (transaction->complete) {
        std::lock_guard lock(mutex);
        completions.emplace_back(transaction->value, transaction->complete);
    }
    if (transaction->clear)
        TestFinish();
    // Android apply consumes the transaction's operations and callbacks.
    transaction->apply.clear();
    transaction->value = nullptr;
    transaction->complete = nullptr;
    transaction->clear = false;
}
extern "C" void ASurfaceTransaction_setBuffer(ASurfaceTransaction *transaction, ASurfaceControl *control,
                                              AHardwareBuffer *buffer, int fence) {
    if (buffer)
        AHardwareBuffer_acquire(buffer);
    auto owned = std::shared_ptr<AHardwareBuffer>(buffer, [](auto *value) {
        if (value)
            AHardwareBuffer_release(value);
    });
    transaction->apply.push_back([control, owned, fence] {
        if (fence >= 0) {
            ++fences;
            close(fence);
        }
        if (control->buffer)
            AHardwareBuffer_release(control->buffer);
        control->buffer = owned.get();
        if (control->buffer)
            AHardwareBuffer_acquire(control->buffer);
    });
}
extern "C" void ASurfaceTransaction_setGeometry(ASurfaceTransaction *transaction, ASurfaceControl *control,
                                                const ARect &source, const ARect &destination, int32_t) {
    ++properties;
    transaction->apply.push_back([=] {
        control->source = source;
        control->destination = destination;
    });
}
extern "C" void ASurfaceTransaction_setZOrder(ASurfaceTransaction *transaction, ASurfaceControl *control,
                                              int32_t order) {
    ++properties;
    transaction->apply.push_back([=] { control->order = order; });
}
extern "C" void ASurfaceTransaction_setVisibility(ASurfaceTransaction *transaction, ASurfaceControl *control,
                                                  ASurfaceTransactionVisibility visibility) {
    ++properties;
    transaction->apply.push_back(
        [=] { control->visible = visibility == ASURFACE_TRANSACTION_VISIBILITY_SHOW; });
    if (control->root && visibility == ASURFACE_TRANSACTION_VISIBILITY_HIDE)
        transaction->clear = true;
}
extern "C" void ASurfaceTransaction_setBufferTransparency(ASurfaceTransaction *transaction,
                                                          ASurfaceControl *control,
                                                          ASurfaceTransactionTransparency value) {
    ++properties;
    transaction->apply.push_back([=] { control->transparency = value; });
}
extern "C" void ASurfaceTransaction_setOnComplete(ASurfaceTransaction *transaction, void *value,
                                                  ASurfaceTransaction_OnComplete complete) {
    transaction->value = value;
    transaction->complete = complete;
}
extern "C" void ASurfaceTransaction_reparent(ASurfaceTransaction *, ASurfaceControl *, ASurfaceControl *) {}
extern "C" void ASurfaceTransaction_setEnableBackPressure(ASurfaceTransaction *transaction,
                                                          ASurfaceControl *control, bool enabled) {
    ++properties;
    transaction->apply.push_back([=] { control->backPressure = enabled; });
}
extern "C" void ASurfaceTransaction_setDesiredPresentTime(ASurfaceTransaction *, int64_t time) {
    presentationTime = time;
}
extern "C" int TestControls() {
    return created;
}
extern "C" int TestReleased() {
    return released;
}
extern "C" int TestFences() {
    return fences;
}
extern "C" int64_t TestTime() {
    return presentationTime;
}
extern "C" void TestLayer(int index, int *output) {
    auto *layer = controls.at(index);
    output[0] = layer->order;
    output[1] = layer->visible;
    output[2] = layer->backPressure;
    output[3] = layer->destination.left;
    output[4] = layer->destination.right;
    output[5] = layer->transparency;
    uint32_t first{};
    if (layer->buffer)
        memcpy(&first, layer->buffer->pixels.data(), 4);
    memcpy(output + 6, &first, 4);
}
extern "C" void TestReset() {
    for (auto *control : controls) {
        assert(!control->buffer);
        delete control;
    }
    controls.clear();
}

static int hintCreated{}, hintClosed{}, hintReports{};
extern "C" APerformanceHintManager *APerformanceHint_getManager() {
    static APerformanceHintManager manager;
    return &manager;
}
extern "C" APerformanceHintSession *APerformanceHint_createSession(APerformanceHintManager *,
                                                                   const int32_t *threads, size_t count,
                                                                   int64_t target) {
    assert(threads && count == 1 && target > 0);
    ++hintCreated;
    return new APerformanceHintSession;
}
extern "C" int APerformanceHint_updateTargetWorkDuration(APerformanceHintSession *, int64_t value) {
    assert(value > 0);
    return 0;
}
extern "C" int APerformanceHint_reportActualWorkDuration(APerformanceHintSession *, int64_t value) {
    assert(value > 0);
    ++hintReports;
    return 0;
}
extern "C" void APerformanceHint_closeSession(APerformanceHintSession *session) {
    delete session;
    ++hintClosed;
}
extern "C" int TestHintCreated() {
    return hintCreated;
}
extern "C" int TestHintClosed() {
    return hintClosed;
}
extern "C" int TestHintReports() {
    return hintReports;
}

extern "C" void TestBufferDesc(int index, AHardwareBuffer_Desc *description) {
    assert(controls.at(index)->buffer);
    AHardwareBuffer_describe(controls.at(index)->buffer, description);
}
