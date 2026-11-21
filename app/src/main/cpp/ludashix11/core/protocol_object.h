#pragma once

#include <type_traits>
#include "base/native_types.h"

namespace ludashi::x11 {

enum class ObjectKind : u8 {
    WindowNode,
    GC,
    OffscreenImage,
    PointerSprite,
    Fence,
    PresentSubscription,
    GLXContext,
    GLXDrawable,
    RenderPicture,
};

class ProtocolObject {
public:
    explicit ProtocolObject(ObjectKind type, u32 id) : id(id), type(type) {}

    virtual ~ProtocolObject() = default;

    ObjectKind GetType() const { return type; }

    template <typename T> T* As() {
        static_assert(std::is_base_of_v<ProtocolObject, T>);
        return static_cast<T*>(this);
    }

    const u32 id;

private:
    ObjectKind type;
};

}  // namespace ludashi::x11
