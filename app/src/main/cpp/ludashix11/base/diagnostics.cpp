#include "base/diagnostics.h"

#include <android/log.h>

namespace ludashi::diagnostics {

void Emit(Severity severity, const char* message) {
    int priority = ANDROID_LOG_INFO;
    if (severity == Severity::Warning) priority = ANDROID_LOG_WARN;
    if (severity == Severity::Error) priority = ANDROID_LOG_ERROR;
    __android_log_write(priority, "LudashiX11", message);
}

} // namespace ludashi::diagnostics
