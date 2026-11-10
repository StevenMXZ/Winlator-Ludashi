#pragma once

#include <fmt/format.h>

namespace base::log {

enum class Level {
    Trace,
    Info,
    Debug,
    Warning,
    Error,
    Max,
};

void SetLogLevel(Level log_level);

void LogMessage(Level level, const std::string& message);

template <typename... Args> void LogMessage(Level log_level,
                                            const char* filename,
                                            unsigned int line_num,
                                            const char* function,
                                            const char* format,
                                            const Args&... args) {
    const auto info = fmt::vformat(format, fmt::make_format_args(args...));
#if CONFIG_DEBUG_MSG
    const auto message = fmt::format("{}:{}:{}: {}", filename, line_num, function, info);
    LogMessage(log_level, message);
#else
    LogMessage(log_level, info);
#endif
}

[[noreturn]] void Panic(const std::string& message);

}  // namespace base::log

#ifdef CONFIG_STRIP_LOG
#define LOG(level, ...) (void(0))
#else
#define LOG(level, ...) base::log::LogMessage(base::log::Level::level, __FILE__, __LINE__, __FUNCTION__, __VA_ARGS__)
#endif

#define TRACE(...) ((void)0)
#define LOGI(...) LOG(Info, __VA_ARGS__)
#define LOGD(...) LOG(Debug, __VA_ARGS__)
#define LOGW(...) LOG(Warning, __VA_ARGS__)
#define LOGE(...) LOG(Error, __VA_ARGS__)

#define ASSERT(cond)                                                                                                   \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            base::log::Panic(fmt::format("Assert failed: \"{}\"", #cond));                                             \
        }                                                                                                              \
    } while (0)

#define ASSERT_MSG(cond, ...)                                                                                          \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            base::log::Panic(fmt::format("Assert failed: " __VA_ARGS__));                                              \
        }                                                                                                              \
    } while (0)

#define PANIC(...) base::log::Panic(fmt::format(__VA_ARGS__))