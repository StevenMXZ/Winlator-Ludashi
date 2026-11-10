#include "core/atom.h"
#include <string>
#include <vector>

namespace xorg {

static std::vector<std::string> atoms = {"",
                                         "PRIMARY",
                                         "SECONDARY",
                                         "ARC",
                                         "ATOM",
                                         "BITMAP",
                                         "CARDINAL",
                                         "COLORMAP",
                                         "CURSOR",
                                         "CUT_BUFFER0",
                                         "CUT_BUFFER1",
                                         "CUT_BUFFER2",
                                         "CUT_BUFFER3",
                                         "CUT_BUFFER4",
                                         "CUT_BUFFER5",
                                         "CUT_BUFFER6",
                                         "CUT_BUFFER7",
                                         "DRAWABLE",
                                         "FONT",
                                         "INTEGER",
                                         "PIXMAP",
                                         "POINT",
                                         "RECTANGLE",
                                         "RESOURCE_MANAGER",
                                         "RGB_COLOR_MAP",
                                         "RGB_BEST_MAP",
                                         "RGB_BLUE_MAP",
                                         "RGB_DEFAULT_MAP",
                                         "RGB_GRAY_MAP",
                                         "RGB_GREEN_MAP",
                                         "RGB_RED_MAP",
                                         "STRING",
                                         "VISUALID",
                                         "WINDOW",
                                         "WM_COMMAND",
                                         "WM_HINTS",
                                         "WM_CLIENT_MACHINE",
                                         "WM_ICON_NAME",
                                         "WM_ICON_SIZE",
                                         "WM_NAME",
                                         "WM_NORMAL_HINTS",
                                         "WM_SIZE_HINTS",
                                         "WM_ZOOM_HINTS",
                                         "MIN_SPACE",
                                         "NORM_SPACE",
                                         "MAX_SPACE",
                                         "END_SPACE",
                                         "SUPERSC.LPT_X",
                                         "SUPERSC.LPT_Y",
                                         "SUBSC.LPT_X",
                                         "SUBSC.LPT_Y",
                                         "UNDERLINE_POSITION",
                                         "UNDERLINE_THICKNESS",
                                         "STRIKEOUT_ASCENT",
                                         "STRIKEOUT_DESCENT",
                                         "ITALIC_ANGLE",
                                         "X_HEIGHT",
                                         "QUAD_WIDTH",
                                         "WEIGHT",
                                         "POINT_SIZE",
                                         "RESOLUTION",
                                         "COPYRIGHT",
                                         "NOTICE",
                                         "FONT_NAME",
                                         "FAMILY_NAME",
                                         "FULL_NAME",
                                         "CAP_HEIGHT",
                                         "WM_CLASS",
                                         "WM_TRANSIENT_FOR"};

std::string Atom::GetName(const u32 id) {
    if (id < atoms.size()) {
        return atoms[id];
    }
    return {};
}

s32 Atom::GetID(const std::string& name) {
    for (u32 i = 1; i < atoms.size(); i++) {
        if (atoms[i] == name) {
            return i;
        }
    }
    return -1;
}

u32 Atom::Intern(const std::string& name) {
    s32 id = GetID(name);
    if (id == -1) {
        id = atoms.size();
        atoms.push_back(name);
    }
    return id;
}

bool Atom::IsValid(u32 id) { return id < atoms.size(); }

u32 Atom::Count() { return static_cast<u32>(atoms.size()); }

}  // namespace xorg
