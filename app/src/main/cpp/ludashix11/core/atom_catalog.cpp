#include "core/atom_catalog.h"

#include <algorithm>
#include <array>

namespace ludashi::x11 {
namespace {

// X11 defines these IDs even before a client interns its first atom.
constexpr auto predefined = std::to_array<std::string_view>({
    "PRIMARY", "SECONDARY", "ARC", "ATOM", "BITMAP", "CARDINAL", "COLORMAP", "CURSOR",
    "CUT_BUFFER0", "CUT_BUFFER1", "CUT_BUFFER2", "CUT_BUFFER3", "CUT_BUFFER4", "CUT_BUFFER5",
    "CUT_BUFFER6", "CUT_BUFFER7", "DRAWABLE", "FONT", "INTEGER", "PIXMAP", "POINT", "RECTANGLE",
    "RESOURCE_MANAGER", "RGB_COLOR_MAP", "RGB_BEST_MAP", "RGB_BLUE_MAP", "RGB_DEFAULT_MAP",
    "RGB_GRAY_MAP", "RGB_GREEN_MAP", "RGB_RED_MAP", "STRING", "VISUALID", "WINDOW", "WM_COMMAND",
    "WM_HINTS", "WM_CLIENT_MACHINE", "WM_ICON_NAME", "WM_ICON_SIZE", "WM_NAME", "WM_NORMAL_HINTS",
    "WM_SIZE_HINTS", "WM_ZOOM_HINTS", "MIN_SPACE", "NORM_SPACE", "MAX_SPACE", "END_SPACE",
    "SUPERSC.LPT_X", "SUPERSC.LPT_Y", "SUBSC.LPT_X", "SUBSC.LPT_Y", "UNDERLINE_POSITION",
    "UNDERLINE_THICKNESS", "STRIKEOUT_ASCENT", "STRIKEOUT_DESCENT", "ITALIC_ANGLE", "X_HEIGHT",
    "QUAD_WIDTH", "WEIGHT", "POINT_SIZE", "RESOLUTION", "COPYRIGHT", "NOTICE", "FONT_NAME",
    "FAMILY_NAME", "FULL_NAME", "CAP_HEIGHT", "WM_CLASS", "WM_TRANSIENT_FOR",
});
static_assert(predefined.size() == 68);

struct FixedAtom {
    std::string_view name;
    uint32_t id;
};

constexpr auto fixedByName = [] {
    std::array<FixedAtom, predefined.size()> entries{};
    for (uint32_t index = 0; index < entries.size(); ++index)
        entries[index] = {predefined[index], index + 1};
    std::ranges::sort(entries, {}, &FixedAtom::name);
    return entries;
}();
constexpr uint32_t firstDynamicId = predefined.size() + 1;

} // namespace

std::string_view AtomCatalog::Name(uint32_t id) const {
    if (id == 0) return {};
    if (id < firstDynamicId) return predefined[id - 1];
    const auto offset = id - firstDynamicId;
    return offset < names.size() ? names[offset] : std::string_view{};
}

int32_t AtomCatalog::Find(std::string_view name) const {
    if (const auto entry = ids.find(name); entry != ids.end()) return static_cast<int32_t>(entry->second);
    const auto fixed = std::ranges::lower_bound(fixedByName, name, {}, &FixedAtom::name);
    return fixed != fixedByName.end() && fixed->name == name ? static_cast<int32_t>(fixed->id) : -1;
}

uint32_t AtomCatalog::Intern(std::string_view name) {
    if (const auto existing = Find(name); existing >= 0) return existing;

    if (names.size() == names.capacity()) names.reserve(std::max<size_t>(16, names.size() * 2));
    const auto id = firstDynamicId + static_cast<uint32_t>(names.size());
    const auto entry = ids.emplace(name, id).first;
    names.push_back(entry->first);
    return id;
}

bool AtomCatalog::Contains(uint32_t id) const {
    return id != 0 && id < Count();
}

size_t AtomCatalog::Count() const {
    return firstDynamicId + names.size();
}

} // namespace ludashi::x11
