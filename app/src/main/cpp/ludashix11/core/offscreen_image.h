#pragma once

#include <utility>
#include "core/protocol_object.h"

namespace ludashi::x11 {

class NativeDrawable;

// A pixmap XID holds a reference to an image. Presentation and GLX can keep
// the image alive independently of the XID that introduced it.
class OffscreenImage final : public ProtocolObject {
public:
    OffscreenImage(u32 id, sp<NativeDrawable> image)
        : ProtocolObject(ObjectKind::OffscreenImage, id), backing(std::move(image)) {}

    const sp<NativeDrawable>& Image() const { return backing; }

private:
    const sp<NativeDrawable> backing;
};

} // namespace ludashi::x11
