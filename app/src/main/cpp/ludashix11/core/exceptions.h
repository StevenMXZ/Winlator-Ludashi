#pragma once

#include "base/types.h"

namespace xorg {

class XRequestError {
public:
    const u8 code;
    const u32 data;

    XRequestError(const u8 code, const u32 data) : code(code), data(data) {}
};

#define DECL_EXCEPTION(name, code)                                                                 \
    class name##Error final : public XRequestError {                                               \
    public:                                                                                        \
        explicit name##Error(const u32 data) : XRequestError(code, data) {}                        \
    }

#define DECL_EXCEPTION2(name, code)                                                                \
    class name##Error final : public XRequestError {                                               \
    public:                                                                                        \
        explicit name##Error() : XRequestError(code, 0) {}                                         \
    }

DECL_EXCEPTION2(BadRequest, 1);
DECL_EXCEPTION(BadValue, 2);
DECL_EXCEPTION(BadWindow, 3);
DECL_EXCEPTION(BadPixmap, 4);
DECL_EXCEPTION(BadAtom, 5);
DECL_EXCEPTION(BadCursor, 6);
DECL_EXCEPTION2(BadMatch, 8);
DECL_EXCEPTION(BadDrawable, 9);
DECL_EXCEPTION2(BadAccess, 10);
DECL_EXCEPTION2(BadAlloc, 11);
DECL_EXCEPTION(BadGC, 13);
DECL_EXCEPTION(BadIdChoice, 14);
DECL_EXCEPTION2(BadLength, 16);

}  // namespace xorg