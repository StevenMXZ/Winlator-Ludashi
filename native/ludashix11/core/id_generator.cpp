#include "core/id_generator.h"

namespace xorg {

static std::atomic<s32> id{0};

s32 GenerateID() { return ++id; }

}  // namespace xorg