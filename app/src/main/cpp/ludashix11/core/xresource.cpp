#include "core/pixmap.h"
#include "core/window.h"
#include "core/xclient.h"
#include "core/xresource.h"

namespace xorg {

Drawable* XResource::AsDrawable() {
    if (type == XResourceType::Pixmap) {
        return As<Pixmap>()->GetDrawable();
    }
    if (type == XResourceType::Window) {
        return As<Window>()->GetDrawable();
    }
    return nullptr;
}

}  // namespace xorg
