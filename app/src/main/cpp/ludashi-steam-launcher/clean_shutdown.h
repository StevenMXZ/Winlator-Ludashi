#pragma once

#ifdef __cplusplus
extern "C" {
#endif

void ludashi_launcher_set_log_sink(void (*log_fn)(const char* line));

void ludashi_launcher_set_game_exe(const char* exeName);

void ludashi_launcher_set_game_dir(const char* dirPath);

int ludashi_launcher_count_game_processes(void);

unsigned long ludashi_launcher_first_game_pid(void);

void ludashi_launcher_arm_clean_shutdown(void* hSteamClient, int pipe, int user,
                                    const char* logPath);

bool ludashi_launcher_shutdown_started(void);
void ludashi_launcher_ready_for_shutdown(void);

void ludashi_launcher_set_cloud_context(void* engine, int hUser, int hPipe, unsigned int appId);

int ludashi_launcher_cloud_run(void* engine, int hUser, int hPipe,
                          unsigned int appId, int onExit, int timeoutMs);

int ludashi_launcher_cloud_choice(void);

void ludashi_launcher_clean_shutdown_now(const char* reason);

void ludashi_launcher_wait_clean_shutdown(int maxMs);

#ifdef __cplusplus
}

#include <ctype.h>
#include <stddef.h>
#include <string.h>

static const int kLudashiGameStemMinLength = 3;

inline void ludashi_game_exe_stem(const char* path, char* out, size_t outSize) {
    if (!out || outSize == 0) return;
    out[0] = '\0';
    if (!path || !path[0]) return;

    const char* base = path;
    for (const char* p = path; *p; ++p) {
        if (*p == '\\' || *p == '/') base = p + 1;
    }

    size_t n = strlen(base);
    if (n >= outSize) n = outSize - 1;
    for (size_t i = 0; i < n; ++i) out[i] = (char) tolower((unsigned char) base[i]);
    out[n] = '\0';

    if (n > 4 && strcmp(out + (n - 4), ".exe") == 0) {
        n -= 4;
        out[n] = '\0';
    }

    static const char* const kHandoffSuffixes[] = {
        ".original", ".unpacked",
        "-win64-shipping", "-win32-shipping", "-shipping",
        "_win64", "_win32", "-win64", "-win32", "_win", "-win",
        "_amd64", "-amd64", "_x64", "_x86", "-x64", "-x86",
        "_64", "_32", "-64", "-32",
        "_dx12", "_dx11", "_dx9", "-dx12", "-dx11",
        "_vulkan", "-vulkan", "_opengl", "-opengl",
        "_launcher", "-launcher", "launcher",
        "_game", "-game", "game",
        "_app", "-app",
        "64", "32",
    };
    const size_t kSuffixCount = sizeof(kHandoffSuffixes) / sizeof(kHandoffSuffixes[0]);

    bool stripped = true;
    while (stripped && n > 0) {
        stripped = false;
        for (size_t i = 0; i < kSuffixCount; ++i) {
            size_t slen = strlen(kHandoffSuffixes[i]);
            if (n <= slen) continue;
            if ((int)(n - slen) < kLudashiGameStemMinLength) continue;
            if (strcmp(out + (n - slen), kHandoffSuffixes[i]) != 0) continue;
            n -= slen;
            out[n] = '\0';
            stripped = true;
            break;
        }
    }
}

inline bool ludashi_game_image_matches(const char* procName, const char* gameExe) {
    if (!procName || !gameExe || !gameExe[0] || !procName[0]) return false;
    if (_stricmp(procName, gameExe) == 0) return true;

    char procStem[260];
    char gameStem[260];
    ludashi_game_exe_stem(procName, procStem, sizeof(procStem));
    ludashi_game_exe_stem(gameExe, gameStem, sizeof(gameStem));
    if (!procStem[0] || !gameStem[0]) return false;
    return strcmp(procStem, gameStem) == 0;
}
#endif
