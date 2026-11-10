#pragma once

#include "base/types.h"

namespace base {

template <typename T> constexpr T AlignUp(T value, size_t size) {
    auto mod{static_cast<T>(value % size)};
    value -= mod;
    return static_cast<T>(mod == T{0} ? value : value + size);
}

template <typename T> constexpr T AlignDiff(T value, size_t size) {
    return static_cast<T>(AlignUp(value, size) - value);
}

template <typename T> constexpr T AlignDown(T value, size_t size) {
    return static_cast<T>(value - value % size);
}

}  // namespace base
