#include <iostream>
#include <android/log.h>
#include "base/log.h"

namespace base::log {

static auto log_level = Level::Trace;

void SetLogLevel(Level level) { log_level = level; }

void LogMessage(Level level, const std::string& message) {
    if (log_level > level) {
        return;
    }
    __android_log_write(ANDROID_LOG_ERROR, "LudashiX11", message.c_str());
}

void Panic(const std::string& message) {
    LogMessage(Level::Error, message);
    throw std::runtime_error(message);
}

}  // namespace base::log