#pragma once

#include <memory>
#include "base/types.h"

namespace xorg {

class XClient;
class Drawable;

enum class XResourceType {
    Window,
    GC,
    Pixmap,
    Visual,
    Cursor,
    Fence,
    PresentSubscription,
    GLXContext,
    GLXDrawable,
};

class XResource {
public:
    explicit XResource(XResourceType type, u32 id) : id(id), type(type) {}

    virtual ~XResource() = default;

    virtual void OnOwnerDies(XClient* owner) {}

    XResourceType GetType() const { return type; }

    bool IsDrawable() const {
        return type == XResourceType::Pixmap || type == XResourceType::Window;
    }

    template <typename T> T* As() {
        static_assert(std::is_base_of_v<XResource, T>);
        return static_cast<T*>(this);
    }

    Drawable* AsDrawable();

    const u32 id;

private:
    XResourceType type;
};

}  // namespace xorg

