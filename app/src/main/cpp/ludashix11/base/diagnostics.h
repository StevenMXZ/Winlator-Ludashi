#pragma once

#include <iterator>
#include <utility>
#include <fmt/format.h>

namespace ludashi::diagnostics {

enum class Severity { Info, Warning, Error };

void Emit(Severity severity, const char* message);

template <typename... Args>
void Write(Severity severity, fmt::format_string<Args...> format, Args&&... args) {
    fmt::memory_buffer message;
    fmt::format_to(std::back_inserter(message), format, std::forward<Args>(args)...);
    message.push_back('\0');
    Emit(severity, message.data());
}

} // namespace ludashi::diagnostics
