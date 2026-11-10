#pragma once
#include <memory>
namespace xorg {
class XServer;
class NativeDisplayXBridge {
    struct Impl;
    std::unique_ptr<Impl> impl;

  public:
    explicit NativeDisplayXBridge(XServer &);
    ~NativeDisplayXBridge();
    void ResetState();
};
} // namespace xorg
