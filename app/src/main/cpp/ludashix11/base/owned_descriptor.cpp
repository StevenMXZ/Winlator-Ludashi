#include "base/owned_descriptor.h"

#include <unistd.h>

namespace ludashi::x11 {

OwnedDescriptor::~OwnedDescriptor() {
    if (fd >= 0) close(fd);
}

void OwnedDescriptor::Reset(int replacement) noexcept {
    if (fd == replacement) return;
    const int previous = std::exchange(fd, replacement);
    if (previous >= 0) close(previous);
}

} // namespace ludashi::x11
