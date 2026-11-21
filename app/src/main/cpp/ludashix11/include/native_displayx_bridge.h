#pragma once
#include <memory>
namespace ludashi::x11 {
class DisplayRuntime;
class NativeDisplayXBridge {
    struct Impl;
    std::unique_ptr<Impl> impl;

  public:
    explicit NativeDisplayXBridge(DisplayRuntime &);
    ~NativeDisplayXBridge();
    void ResetState();
};
} // namespace ludashi::x11
