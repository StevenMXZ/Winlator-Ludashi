#pragma once

#include "base/types.h"
#include <string>

namespace xorg {

class Atom {
public:
    static std::string GetName(const u32 id);

    static s32 GetID(const std::string& name);

    static u32 Intern(const std::string& name);

    static bool IsValid(u32 id);
    // Allows consumers to cache lookups until another atom is interned.
    static u32 Count();
};

}  // namespace xorg