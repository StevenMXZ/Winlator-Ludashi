#pragma once
#include <array>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>

// Conservative coverage test in logical NDC. Android pre-rotation is restricted
// to quarter turns, which preserve a complete [-1,1] square.
inline bool NativeCoversOutput(float x0, float y0, float x1, float y1) {
    return std::isfinite(x0) && std::isfinite(y0) && std::isfinite(x1) && std::isfinite(y1) &&
        x0 <= -1.f && y0 <= -1.f && x1 >= 1.f && y1 >= 1.f;
}

struct NativeVulkanMetrics {
    using Clock = std::chrono::steady_clock;
    enum Stage { Slot, Acquire, ImageWait, Prepare, Record, Submit, Present, Count };
    struct Timing {
        uint64_t total{}, maximum{}, count{};
        void Add(uint64_t ns) { total += ns; maximum = std::max(maximum, ns); ++count; }
        double AverageUs() const { return count ? total / 1000.0 / count : 0.; }
        double MaximumUs() const { return maximum / 1000.0; }
    };
    std::array<Timing, Count> stages{};
    uint64_t frames{}, opaque{}, noClear{};
    Clock::time_point window{};
    void RecordStage(Stage stage, Clock::time_point start) {
        stages[stage].Add(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count());
    }
};
