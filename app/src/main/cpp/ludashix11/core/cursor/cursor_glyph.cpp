#include "core/cursor/cursor_glyph.h"

#include <iterator>
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {

#include "font_data.inc"

} // namespace

const CursorGlyph& GetCursorGlyph(uint32_t code) {
    if (code >= std::size(glyphs)) throw BadValueError(code);
    return glyphs[code];
}

} // namespace ludashi::x11
