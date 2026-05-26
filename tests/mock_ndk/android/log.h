/*
 * mock_ndk/android/log.h — Controllable mock for Android logging API.
 *
 * Captures log calls so tests can verify that "AHB_Bridge" appears in
 * the tag and that the correct log level is used.
 */
#pragma once

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* Android log priority levels (matches android/log.h) */
typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;

/* ── Log capture infrastructure ─────────────────────────────────────────── */
#ifdef AHB_BRIDGE_TEST_MODE

#ifdef __cplusplus
extern "C" {
#endif

#define MOCK_LOG_MAX_ENTRIES  64
#define MOCK_LOG_MAX_MSG_LEN  512
#define MOCK_LOG_MAX_TAG_LEN  64

typedef struct MockLogEntry {
    int  priority;
    char tag[MOCK_LOG_MAX_TAG_LEN];
    char message[MOCK_LOG_MAX_MSG_LEN];
} MockLogEntry;

extern MockLogEntry mock_log_entries[MOCK_LOG_MAX_ENTRIES];
extern int          mock_log_count;

/* Reset the captured log buffer. */
void mock_log_reset(void);

/*
 * Returns 1 if any captured log entry has the given tag and the message
 * contains the given substring. Returns 0 otherwise.
 */
int mock_log_contains(const char* tag, const char* substring);

/*
 * Returns 1 if any captured log entry has the given tag, the given priority,
 * and the message contains the given substring.
 */
int mock_log_contains_with_priority(int priority, const char* tag, const char* substring);

#ifdef __cplusplus
}
#endif

/* ── Macro shim: redirect __android_log_print to our capture function ─────── */
static inline int __mock_android_log_print(int prio, const char* tag, const char* fmt, ...) {
    if (mock_log_count < MOCK_LOG_MAX_ENTRIES) {
        MockLogEntry* e = &mock_log_entries[mock_log_count++];
        e->priority = prio;
        strncpy(e->tag, tag ? tag : "", MOCK_LOG_MAX_TAG_LEN - 1);
        e->tag[MOCK_LOG_MAX_TAG_LEN - 1] = '\0';
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(e->message, MOCK_LOG_MAX_MSG_LEN, fmt, ap);
        va_end(ap);
    }
    return 0;
}

#define __android_log_print(prio, tag, ...) __mock_android_log_print((prio), (tag), __VA_ARGS__)

#else /* !AHB_BRIDGE_TEST_MODE */

int __android_log_print(int prio, const char* tag, const char* fmt, ...);

#endif /* AHB_BRIDGE_TEST_MODE */
