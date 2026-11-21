#pragma once

#include <cstdint>
#include <X11/X.h>

namespace ludashi::x11 {

// Core requests and extensions share the same wire error packet. The value
// field may contain either a resource ID or a rejected argument.
struct XRequestError {
    const std::uint8_t code;
    const std::uint32_t data;
};

template<std::uint8_t Code>
struct RequestFailure final : XRequestError {
    explicit RequestFailure(std::uint32_t value = 0) : XRequestError{Code, value} {}
};

using BadRequestError = RequestFailure<BadRequest>;
using BadValueError = RequestFailure<BadValue>;
using BadWindowError = RequestFailure<BadWindow>;
using BadPixmapError = RequestFailure<BadPixmap>;
using BadAtomError = RequestFailure<BadAtom>;
using BadCursorError = RequestFailure<BadCursor>;
using BadMatchError = RequestFailure<BadMatch>;
using BadDrawableError = RequestFailure<BadDrawable>;
using BadAccessError = RequestFailure<BadAccess>;
using BadAllocError = RequestFailure<BadAlloc>;
using BadGCError = RequestFailure<BadGC>;
using BadIdChoiceError = RequestFailure<BadIDChoice>;
using BadLengthError = RequestFailure<BadLength>;

} // namespace ludashi::x11
