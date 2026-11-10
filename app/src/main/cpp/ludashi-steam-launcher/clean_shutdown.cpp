#include "clean_shutdown.h"

#include <windows.h>
#include <tlhelp32.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>

#ifdef __i386__
#define LUDASHI_THISCALL __thiscall
#else
#define LUDASHI_THISCALL
#endif

namespace {

using Steam_LogOff_fn = void (*)(int, int);
using Steam_ReleaseUser_fn = void (*)(int, int);
using Steam_BReleaseSteamPipe_fn = bool (*)(int);
using Steam_BLoggedOn_fn = bool (*)(int, int);
using Steam_BGetCallback_fn = bool (*)(int, void*);
using Steam_FreeLastCallback_fn = void (*)(int);

Steam_LogOff_fn g_logoff = nullptr;
Steam_ReleaseUser_fn g_release_user = nullptr;
Steam_BReleaseSteamPipe_fn g_release_pipe = nullptr;
Steam_BLoggedOn_fn g_bloggedon = nullptr;
Steam_BGetCallback_fn g_bgetcallback = nullptr;
Steam_FreeLastCallback_fn g_freelastcallback = nullptr;

int g_pipe = 0;
int g_user = 0;
char g_log_path[MAX_PATH] = {0};
char g_game_exe[260] = {0};
char g_game_dir[MAX_PATH] = {0};

void* g_cs_engine = nullptr;
int g_cs_hUser = 0;
int g_cs_hPipe = 0;
unsigned int g_cs_appId = 0;

constexpr int kVtEngine_GetIClientRemoteStorage = 24;
constexpr int kVtRS_EvaluateRemoteStorageSyncState = 71;
constexpr int kVtRS_GetRemoteStorageSyncState      = 73;
constexpr int kVtRS_IsCloudEnabledForAccount     = 23;
constexpr int kVtRS_IsCloudEnabledForApp         = 24;
constexpr int kVtRS_GetLastKnownSyncState        = 72;
constexpr int kVtRS_GetConflictingFileTimestamps = 75;
constexpr int kVtRS_ResolveSyncConflict          = 77;
constexpr int kVtRS_IsAppSyncInProgress          = 79;
constexpr int kVtRS_RunAutoCloudOnAppLaunch      = 80;
constexpr int kCloudDrainCapPerTick              = 64;
constexpr int kVtRS_RunAutoCloudOnAppExit        = 81;
constexpr int kVtRS_SynchronizeApp               = 78;
constexpr unsigned int kSyncToClient = 1;
constexpr unsigned int kSyncToServer = 2;
constexpr unsigned long long kSyncFlagNone            = 0;
constexpr unsigned long long kSyncFlagAutoCloudExit   = 4;
constexpr int kCbRemoteStorageAppSyncedClient = 1301;
constexpr int kCbRemoteStorageAppSyncedServer = 1302;
constexpr int kCbClientRemoteStorageAppSyncedClient = 1250001;
constexpr int kCbClientRemoteStorageAppSyncedServer = 1250002;
constexpr int kEResultOK = 1;
constexpr int kEResultTooManyPending = 108;
constexpr int kCloudSeenIdSlots = 24;
constexpr int kCloudCallbackGraceMs = 2500;

constexpr int kSyncDisabled       = 0;
constexpr int kSyncUnknown        = 1;
constexpr int kSyncSynchronized   = 2;
constexpr int kSyncInProgress     = 3;
constexpr int kSyncChangesInCloud = 4;
constexpr int kSyncChangesLocally = 5;
constexpr int kSyncChangesBoth    = 6;
constexpr int kSyncConflicting    = 7;
constexpr int kSyncNotInitialized = 8;

// Small atomic protocol consumed by the Android UI. Never infer success from elapsed time.
void cs_report(unsigned int appId, const char* phase, int state, int transfers = 0) {
    const char* tmp = "C:\\ludashi-steam-cloud-status.tmp";
    FILE* f = fopen(tmp, "wb");
    if (!f) return;
    fprintf(f, "appid=%u\nphase=%s\nstate=%d\ntransfers=%d\n", appId, phase, state, transfers);
    fclose(f);
    MoveFileExA(tmp, "C:\\ludashi-steam-cloud-status.txt", MOVEFILE_REPLACE_EXISTING);
}

const char* cs_sync_state_name(int v) {
    switch (v) {
        case kSyncDisabled:       return "disabled";
        case kSyncUnknown:        return "unknown";
        case kSyncSynchronized:   return "synchronized";
        case kSyncInProgress:     return "inprogress";
        case kSyncChangesInCloud: return "changesincloud";
        case kSyncChangesLocally: return "changeslocally";
        case kSyncChangesBoth:    return "changesincloudandlocally";
        case kSyncConflicting:    return "conflictingchanges";
        case kSyncNotInitialized: return "notinitialized";
        default:                  return "?";
    }
}

const char* const kCloudConflictRequestPath = "C:\\ludashi-steam-cloud-conflict.txt";
const char* const kCloudConflictAnswerPath  = "C:\\ludashi-steam-cloud-conflict-answer.txt";
constexpr int kCloudConflictAnswerWaitMs = 600000;
constexpr int kCloudAttemptCapMs = 90000;
constexpr int kCloudMinSettleMs = 500;
constexpr int kCloudMaxPendingAttempts = 2;
constexpr int kCloudStableSettleMs = 1500;

bool cs_is_exec_ptr(void* p) {
    if (!p) return false;
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery(p, &mbi, sizeof(mbi)) == 0) return false;
    if (mbi.State != MEM_COMMIT) return false;
    DWORD x = mbi.Protect & 0xFF;
    return x == PAGE_EXECUTE || x == PAGE_EXECUTE_READ ||
           x == PAGE_EXECUTE_READWRITE || x == PAGE_EXECUTE_WRITECOPY;
}

#ifndef PROCESS_QUERY_LIMITED_INFORMATION
#define PROCESS_QUERY_LIMITED_INFORMATION 0x1000
#endif

using QueryFullProcessImageNameA_fn = BOOL (WINAPI*)(HANDLE, DWORD, LPSTR, PDWORD);

bool process_image_path(DWORD pid, char* out, DWORD outSize) {
    static QueryFullProcessImageNameA_fn queryImageName = nullptr;
    static bool resolved = false;
    if (!resolved) {
        resolved = true;
        HMODULE k32 = ::GetModuleHandleA("kernel32.dll");
        if (k32) {
            queryImageName = reinterpret_cast<QueryFullProcessImageNameA_fn>(
                ::GetProcAddress(k32, "QueryFullProcessImageNameA"));
        }
    }
    if (!queryImageName) return false;
    HANDLE h = ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) h = ::OpenProcess(PROCESS_QUERY_INFORMATION, FALSE, pid);
    if (!h) return false;
    DWORD size = outSize;
    BOOL ok = queryImageName(h, 0, out, &size);
    ::CloseHandle(h);
    return ok != FALSE && out[0] != '\0';
}

constexpr size_t kMinGameDirPrefixLength = 8;

bool path_under_game_dir(const char* path) {
    if (!g_game_dir[0] || !path || !path[0]) return false;
    size_t n = std::strlen(g_game_dir);
    if (n < kMinGameDirPrefixLength) return false;
    if (::_strnicmp(path, g_game_dir, n) != 0) return false;
    char next = path[n];
    return next == '\\' || next == '/';
}

bool process_belongs_to_game(DWORD pid, const char* procName, const char* exeName) {
    if (pid == 0 || pid == ::GetCurrentProcessId()) return false;
    if (ludashi_game_image_matches(procName, exeName)) return true;
    if (!g_game_dir[0]) return false;
    char path[MAX_PATH];
    path[0] = '\0';
    if (!process_image_path(pid, path, MAX_PATH)) return false;
    return path_under_game_dir(path);
}

int kill_processes_by_name(const char* exeName) {
    if (!exeName || !exeName[0]) return 0;
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(pe);
    int killed = 0;
    if (::Process32First(snap, &pe)) {
        do {
            if (process_belongs_to_game(pe.th32ProcessID, pe.szExeFile, exeName)) {
                HANDLE h = ::OpenProcess(PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
                if (h) {
                    if (::TerminateProcess(h, 0)) killed++;
                    ::CloseHandle(h);
                }
            }
        } while (::Process32Next(snap, &pe));
    }
    ::CloseHandle(snap);
    return killed;
}

int count_processes_by_name(const char* exeName) {
    if (!exeName || !exeName[0]) return 0;
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(pe);
    int n = 0;
    if (::Process32First(snap, &pe)) {
        do {
            if (process_belongs_to_game(pe.th32ProcessID, pe.szExeFile, exeName)) n++;
        } while (::Process32Next(snap, &pe));
    }
    ::CloseHandle(snap);
    return n;
}

std::vector<DWORD> g_close_pids;

BOOL CALLBACK close_enum_proc(HWND hwnd, LPARAM lp) {
    DWORD pid = 0;
    ::GetWindowThreadProcessId(hwnd, &pid);
    for (DWORD p : g_close_pids) {
        if (p == pid) {
            ::PostMessageA(hwnd, WM_CLOSE, 0, 0);
            break;
        }
    }
    return TRUE;
}

int graceful_close_game(const char* exeName) {
    g_close_pids.clear();
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32 pe;
        pe.dwSize = sizeof(pe);
        if (::Process32First(snap, &pe)) {
            do {
                if (process_belongs_to_game(pe.th32ProcessID, pe.szExeFile, exeName)) {
                    g_close_pids.push_back(pe.th32ProcessID);
                }
            } while (::Process32Next(snap, &pe));
        }
        ::CloseHandle(snap);
    }
    if (!g_close_pids.empty()) {
        ::EnumWindows(close_enum_proc, 0);
    }
    return (int) g_close_pids.size();
}

void (*g_log_fn)(const char* line) = nullptr;

std::atomic<bool> g_armed{false};
std::atomic<bool> g_done{false};
std::atomic<bool> g_teardown_complete{false};
std::atomic<bool> g_watch_run{false};

constexpr const char* kSentinelPath = "C:\\ludashi-launcher.shutdown";

void ludashi_log(const char* msg) {
    char line[512];
    std::snprintf(line, sizeof(line), "[ludashi-launcher] %s", msg);

    if (g_log_fn) {
        g_log_fn(line);
        return;
    }
    if (g_log_path[0] == '\0') return;
    FILE* f = std::fopen(g_log_path, "a");
    if (!f) return;
    std::fprintf(f, "%s\n", line);
    std::fclose(f);
}

void teardown(const char* reason) {
    bool expected = false;
    if (!g_done.compare_exchange_strong(expected, true)) return;

    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "clean-shutdown teardown begin (reason=%s pipe=%d user=%d)",
                  reason ? reason : "?", g_pipe, g_user);
    ludashi_log(buf);

    if (g_game_exe[0] && g_pipe != 0) {
        int targeted = graceful_close_game(g_game_exe);
        if (targeted == 0) {
            ludashi_log("game already exited — skipping graceful-close wait");
        } else {
            std::snprintf(buf, sizeof(buf),
                          "graceful close \"%s\" (WM_CLOSE to %d game process(es)); "
                          "waiting up to 10s for the game to flush its save and call "
                          "SteamAPI_Shutdown before the cloud upload", g_game_exe, targeted);
            ludashi_log(buf);

            const int kMaxWaitMs = 10000;
            int waited = 0;
            while (waited < kMaxWaitMs && count_processes_by_name(g_game_exe) > 0) {
                if (g_bgetcallback && g_freelastcallback) {
                    char cb[64];
                    while (g_bgetcallback(g_pipe, cb)) g_freelastcallback(g_pipe);
                }
                ::Sleep(100);
                waited += 100;
            }
            bool gone = count_processes_by_name(g_game_exe) == 0;
            if (gone) {
                std::snprintf(buf, sizeof(buf),
                              "game \"%s\" exited gracefully after %dms — steamclient "
                              "should have emitted games-played([])", g_game_exe, waited);
                ludashi_log(buf);
            } else {
                int killed = kill_processes_by_name(g_game_exe);
                std::snprintf(buf, sizeof(buf),
                              "game \"%s\" ignored WM_CLOSE for %dms — hard-killed %d "
                              "(games-played reap may be delayed)",
                              g_game_exe, waited, killed);
                ludashi_log(buf);
            }
            if (g_bgetcallback && g_freelastcallback) {
                char cb[64];
                for (int i = 0; i < 4; ++i) {
                    while (g_bgetcallback(g_pipe, cb)) g_freelastcallback(g_pipe);
                    ::Sleep(100);
                }
            } else {
                ::Sleep(400);
            }
            ludashi_log("games-played reap window elapsed");
        }
    }

    if (g_cs_engine && g_cs_appId != 0) {
        if (reason && strcmp(reason, "launch-failed") == 0) {
            ludashi_log("cloud: exit sync skipped — the game never ran, so there is nothing new "
                   "to upload and an exit sync could push stale local files over the cloud");
        } else {
            const char* acEnv = getenv("LUDASHI_STEAM_AGENT_CLOUD");
            const char* offEnv = getenv("LUDASHI_STEAM_OFFLINE");
            const char* netEnv = getenv("LUDASHI_STEAM_NET_DOWN");
            const bool offline = (offEnv && offEnv[0] != '0' && offEnv[0] != '\0')
                || (netEnv && netEnv[0] != '0' && netEnv[0] != '\0');
            if (offline) {
                ludashi_log("cloud: exit upload skipped — Steam Offline Mode keeps this session's "
                       "saves local until the shortcut goes back online");
            } else if (!acEnv || acEnv[0] != '0') {
                ludashi_launcher_cloud_run(g_cs_engine, g_cs_hUser, g_cs_hPipe, g_cs_appId, 1, 60000);
            } else {
                ludashi_log("cloud: exit upload skipped — cloud saves are turned off for this "
                       "shortcut");
            }
        }
    }

    if (g_logoff && g_user != 0 && g_pipe != 0) {
        g_logoff(g_pipe, g_user);
        ludashi_log("Steam_LogOff sent");

        const int kMinMs = 300, kMaxMs = 700, kStepMs = 100;
        int waited = 0;
        bool loggedOff = false;
        while (waited < kMaxMs) {
            ::Sleep(kStepMs);
            waited += kStepMs;
            if (g_bloggedon && !g_bloggedon(g_pipe, g_user)) {
                loggedOff = true;
                if (waited >= kMinMs) break;
            }
        }
        std::snprintf(buf, sizeof(buf),
                      "logoff flush wait done (%dms, BLoggedOn=%s)",
                      waited, loggedOff ? "false(logged-off)" : "true/unknown");
        ludashi_log(buf);
    }
    if (g_release_user && g_user != 0 && g_pipe != 0) {
        g_release_user(g_pipe, g_user);
        ludashi_log("Steam_ReleaseUser done");
    }
    if (g_release_pipe && g_pipe != 0) {
        bool ok = g_release_pipe(g_pipe);
        std::snprintf(buf, sizeof(buf), "Steam_BReleaseSteamPipe -> %d", ok ? 1 : 0);
        ludashi_log(buf);
    }

    ludashi_log("clean logoff complete");

    FILE* done = fopen("C:\\ludashi-steam-shutdown-complete.txt", "wb");
    if (done) { fputs("done", done); fclose(done); }
    ::DeleteFileA(kSentinelPath);

    g_teardown_complete.store(true);
}

BOOL WINAPI ctrl_handler(DWORD type) {
    switch (type) {
        case CTRL_CLOSE_EVENT:
        case CTRL_LOGOFF_EVENT:
        case CTRL_SHUTDOWN_EVENT:
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
            teardown("console-ctrl");
            return TRUE;
        default:
            return FALSE;
    }
}

void watch_loop() {
    while (g_watch_run.load()) {
        if (::GetFileAttributesA(kSentinelPath) != INVALID_FILE_ATTRIBUTES) {
            teardown("sentinel");
            // Main may already be uploading after a natural game exit. Do not cut
            // that upload short just because compare_exchange rejected a second teardown.
            while (!g_teardown_complete.load()) ::Sleep(50);
            g_watch_run.store(false);
            ::ExitProcess(0);
            return;
        }
        ::Sleep(150);
    }
}

}

extern "C" void ludashi_launcher_set_log_sink(void (*log_fn)(const char* line)) {
    g_log_fn = log_fn;
}

extern "C" void ludashi_launcher_set_game_exe(const char* exeName) {
    if (exeName && exeName[0]) {
        std::snprintf(g_game_exe, sizeof(g_game_exe), "%s", exeName);
    } else {
        g_game_exe[0] = '\0';
    }
}

extern "C" void ludashi_launcher_set_game_dir(const char* dirPath) {
    if (!dirPath || !dirPath[0]) {
        g_game_dir[0] = '\0';
        return;
    }
    std::snprintf(g_game_dir, sizeof(g_game_dir), "%s", dirPath);
    size_t n = std::strlen(g_game_dir);
    while (n > 0 && (g_game_dir[n - 1] == '\\' || g_game_dir[n - 1] == '/')) {
        g_game_dir[--n] = '\0';
    }
}

extern "C" int ludashi_launcher_count_game_processes(void) {
    if (!g_game_exe[0] && !g_game_dir[0]) return 0;
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return -1;
    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(pe);
    int n = 0;
    if (::Process32First(snap, &pe)) {
        do {
            if (process_belongs_to_game(pe.th32ProcessID, pe.szExeFile, g_game_exe)) n++;
        } while (::Process32Next(snap, &pe));
    }
    ::CloseHandle(snap);
    return n;
}

extern "C" unsigned long ludashi_launcher_first_game_pid(void) {
    if (!g_game_exe[0] && !g_game_dir[0]) return 0;
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(pe);
    DWORD found = 0;
    if (::Process32First(snap, &pe)) {
        do {
            if (process_belongs_to_game(pe.th32ProcessID, pe.szExeFile, g_game_exe)) {
                found = pe.th32ProcessID;
                break;
            }
        } while (::Process32Next(snap, &pe));
    }
    ::CloseHandle(snap);
    return (unsigned long) found;
}

extern "C" void ludashi_launcher_arm_clean_shutdown(void* hSteamClient, int pipe,
                                               int user, const char* logPath) {
    bool expected = false;
    if (!g_armed.compare_exchange_strong(expected, true)) return;

    g_pipe = pipe;
    g_user = user;
    if (logPath && logPath[0]) {
        std::snprintf(g_log_path, sizeof(g_log_path), "%s", logPath);
    }

    HMODULE h = reinterpret_cast<HMODULE>(hSteamClient);
    if (h) {
        g_logoff = reinterpret_cast<Steam_LogOff_fn>(
            ::GetProcAddress(h, "Steam_LogOff"));
        g_release_user = reinterpret_cast<Steam_ReleaseUser_fn>(
            ::GetProcAddress(h, "Steam_ReleaseUser"));
        g_release_pipe = reinterpret_cast<Steam_BReleaseSteamPipe_fn>(
            ::GetProcAddress(h, "Steam_BReleaseSteamPipe"));
        g_bloggedon = reinterpret_cast<Steam_BLoggedOn_fn>(
            ::GetProcAddress(h, "Steam_BLoggedOn"));
        g_bgetcallback = reinterpret_cast<Steam_BGetCallback_fn>(
            ::GetProcAddress(h, "Steam_BGetCallback"));
        g_freelastcallback = reinterpret_cast<Steam_FreeLastCallback_fn>(
            ::GetProcAddress(h, "Steam_FreeLastCallback"));
    }

    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "clean-shutdown armed (pipe=%d user=%d logoff=%p releaseUser=%p "
                  "releasePipe=%p bLoggedOn=%p sentinel=%s)",
                  pipe, user, reinterpret_cast<void*>(g_logoff),
                  reinterpret_cast<void*>(g_release_user),
                  reinterpret_cast<void*>(g_release_pipe),
                  reinterpret_cast<void*>(g_bloggedon), kSentinelPath);
    ludashi_log(buf);

    ::SetConsoleCtrlHandler(ctrl_handler, TRUE);

    ::DeleteFileA(kSentinelPath);

    g_watch_run.store(true);
    std::thread(watch_loop).detach();
}

extern "C" bool ludashi_launcher_shutdown_started(void) { return g_done.load(); }

extern "C" void ludashi_launcher_ready_for_shutdown(void) {
    FILE* ready = fopen("C:\\ludashi-steam-launcher-ready.txt", "wb");
    if (ready) { fputs("ready", ready); fclose(ready); }
}

extern "C" void ludashi_launcher_set_cloud_context(void* engine, int hUser, int hPipe,
                                              unsigned int appId) {
    g_cs_engine = engine;
    g_cs_hUser = hUser;
    g_cs_hPipe = hPipe;
    g_cs_appId = appId;
}

static bool cs_wait_conflict_answer(int timeoutMs, bool* keepLocalOut, int* waitedOut) {
    const int stepMs = 200;
    for (int waited = 0; waited < timeoutMs; waited += stepMs) {
        if (g_bgetcallback && g_freelastcallback) {
            char cb[64];
            while (g_bgetcallback(g_pipe, cb)) g_freelastcallback(g_pipe);
        }
        FILE* f = fopen(kCloudConflictAnswerPath, "rb");
        if (f) {
            char buf[32] = {0};
            size_t got = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            buf[got] = '\0';
            for (char* p = buf; *p; ++p) {
                if (*p == '\r' || *p == '\n') { *p = '\0'; break; }
            }
            DeleteFileA(kCloudConflictAnswerPath);
            DeleteFileA(kCloudConflictRequestPath);
            if (waitedOut) *waitedOut = waited;
            if (strcmp(buf, "local") != 0 && strcmp(buf, "cloud") != 0) return false;
            *keepLocalOut = (strcmp(buf, "local") == 0);
            char line[128];
            std::snprintf(line, sizeof(line),
                          "cloud: conflict answer=\"%s\" -> keepLocal=%d",
                          buf, *keepLocalOut ? 1 : 0);
            ludashi_log(line);
            return true;
        }
        ::Sleep(stepMs);
    }
    DeleteFileA(kCloudConflictRequestPath);
    if (waitedOut) *waitedOut = timeoutMs;
    ludashi_log("cloud: no conflict answer in time — leaving both copies intact");
    return false;
}

static bool cs_resolve_conflict(void* rs, void** rs_vt, unsigned int appId,
                                int* userWaitMsOut) {
    void* tsP  = rs_vt[kVtRS_GetConflictingFileTimestamps];
    void* resP = rs_vt[kVtRS_ResolveSyncConflict];
    if (!cs_is_exec_ptr(tsP) || !cs_is_exec_ptr(resP)) {
        ludashi_log("cloud: conflict slots not executable — cannot prompt");
        return false;
    }
    using TsFn  = bool (LUDASHI_THISCALL *)(void*, unsigned int, unsigned int*, unsigned int*);
    using ResFn = bool (LUDASHI_THISCALL *)(void*, unsigned int, bool);

    unsigned int localTime = 0, remoteTime = 0;
    bool haveTs = reinterpret_cast<TsFn>(tsP)(rs, appId, &localTime, &remoteTime);

    char line[192];
    std::snprintf(line, sizeof(line),
                  "CLOUD-CONFLICT: appid=%u local=%u remote=%u ok=%d",
                  appId, localTime, remoteTime, haveTs ? 1 : 0);
    ludashi_log(line);

    DeleteFileA(kCloudConflictAnswerPath);
    FILE* f = fopen(kCloudConflictRequestPath, "wb");
    if (f) {
        fprintf(f, "appid=%u\nlocal=%u\nremote=%u\n", appId, localTime, remoteTime);
        fclose(f);
    }

    cs_report(appId, "launch", kSyncConflicting);
    bool keepLocal = false;
    int userWaited = 0;
    bool answered = cs_wait_conflict_answer(kCloudConflictAnswerWaitMs, &keepLocal, &userWaited);
    if (userWaitMsOut) *userWaitMsOut += userWaited;
    if (!answered) return false;

    bool ok = reinterpret_cast<ResFn>(resP)(rs, appId, keepLocal);
    std::snprintf(line, sizeof(line),
                  "cloud: ResolveSyncConflict(app=%u keepLocal=%d) -> %d",
                  appId, keepLocal ? 1 : 0, ok ? 1 : 0);
    ludashi_log(line);
    return ok;
}

extern "C" int ludashi_launcher_cloud_run(void* engine, int hUser, int hPipe,
                                     unsigned int appId, int onExit, int timeoutMs) {
    cs_report(appId, onExit ? "exit" : "launch", kSyncInProgress);
    auto failed = [&]() { cs_report(appId, onExit ? "exit" : "launch", -1); return -1; };
    if (!engine || appId == 0) return failed();
    void** engine_vt = *reinterpret_cast<void***>(engine);
    void* getRsP = engine_vt[kVtEngine_GetIClientRemoteStorage];
    if (!cs_is_exec_ptr(getRsP)) {
        ludashi_log("cloud: GetIClientRemoteStorage slot not executable — skipping sync");
        return failed();
    }
    using GetRsFn = void* (LUDASHI_THISCALL *)(void*, int, int);
    void* rs = reinterpret_cast<GetRsFn>(getRsP)(engine, hUser, hPipe);
    {
        char rsbuf[256];
        std::snprintf(rsbuf, sizeof(rsbuf), "cloud: IClientRemoteStorage -> %p", rs);
        ludashi_log(rsbuf);
        if (rs) {
            void** rsvt = *reinterpret_cast<void***>(rs);
            HMODULE m = NULL;
            char mod[MAX_PATH] = {0};
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                                   | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                   (LPCSTR) rsvt, &m) && m) {
                GetModuleFileNameA(m, mod, sizeof(mod));
                std::snprintf(rsbuf, sizeof(rsbuf),
                    "cloud: rs vtable=%p base=%p vt_rva=0x%lx expected_rva=0x1006690 match=%d mod=%s",
                    (void*) rsvt, (void*) m,
                    (unsigned long) ((char*) rsvt - (char*) m),
                    (int) (((char*) rsvt - (char*) m) == 0x1006690), mod);
            } else {
                std::snprintf(rsbuf, sizeof(rsbuf), "cloud: rs vtable=%p (module unknown)", (void*) rsvt);
            }
            ludashi_log(rsbuf);

            using EnabledAccFn = bool (LUDASHI_THISCALL *)(void*);
            using EnabledAppFn = bool (LUDASHI_THISCALL *)(void*, unsigned int);
            void* accP = rsvt[kVtRS_IsCloudEnabledForAccount];
            void* appP = rsvt[kVtRS_IsCloudEnabledForApp];
            int accOn = -1, appOn = -1;
            if (cs_is_exec_ptr(accP)) accOn = reinterpret_cast<EnabledAccFn>(accP)(rs) ? 1 : 0;
            if (cs_is_exec_ptr(appP)) appOn = reinterpret_cast<EnabledAppFn>(appP)(rs, appId) ? 1 : 0;
            std::snprintf(rsbuf, sizeof(rsbuf),
                "cloud: IsCloudEnabledForAccount=%d IsCloudEnabledForApp(%u)=%d "
                "(-1 = slot not callable)", accOn, appId, appOn);
            ludashi_log(rsbuf);
        }
    }
    if (!rs) {
        ludashi_log("cloud: IClientRemoteStorage null — skipping sync");
        return failed();
    }
    void** rs_vt = *reinterpret_cast<void***>(rs);
    void* runP    = rs_vt[onExit ? kVtRS_RunAutoCloudOnAppExit : kVtRS_RunAutoCloudOnAppLaunch];
    void* inProgP = rs_vt[kVtRS_IsAppSyncInProgress];
    void* stateP  = rs_vt[kVtRS_GetLastKnownSyncState];
    if (!cs_is_exec_ptr(runP) || !cs_is_exec_ptr(inProgP) || !cs_is_exec_ptr(stateP)) {
        ludashi_log("cloud: RemoteStorage slot(s) not executable — skipping sync");
        return failed();
    }
    using RunFn    = bool (LUDASHI_THISCALL *)(void*, unsigned int);
    using InProgFn = bool (LUDASHI_THISCALL *)(void*, unsigned int);
    using StateFn  = int  (LUDASHI_THISCALL *)(void*, unsigned int);
    using CurFn    = int  (LUDASHI_THISCALL *)(void*, unsigned int);
    using EvalFn   = void (LUDASHI_THISCALL *)(void*, unsigned int, bool);
    using SyncFn   = bool (LUDASHI_THISCALL *)(void*, unsigned int, unsigned int, unsigned long long);

    void* curP = rs_vt[kVtRS_GetRemoteStorageSyncState];
    if (!cs_is_exec_ptr(curP)) curP = nullptr;
    void* evalP = rs_vt[kVtRS_EvaluateRemoteStorageSyncState];
    if (!cs_is_exec_ptr(evalP)) evalP = nullptr;
    void* syncP = rs_vt[kVtRS_SynchronizeApp];
    if (!cs_is_exec_ptr(syncP)) {
        syncP = nullptr;
        ludashi_log("cloud: SynchronizeApp slot not executable — AutoCloud can only evaluate, "
               "so no files will be transferred");
    }

    const char* phase = onExit ? "exit" : "launch";
    cs_report(appId, phase, kSyncInProgress);
    char buf[512];
    int finalState = -1;
    const DWORD startTick = ::GetTickCount();
    int userWaitMs = 0;
    bool sawSyncCallback = false;
    int lastSyncResult = 0;
    int lastSyncTransfers = 0;

    for (int attempt = 1; attempt <= 4; ++attempt) {
        int elapsed = (int) (::GetTickCount() - startTick) - userWaitMs;
        int remaining = timeoutMs - elapsed;
        if (remaining <= 0) {
            std::snprintf(buf, sizeof(buf),
                "cloud: %s sync budget of %dms exhausted after %d attempt(s)",
                phase, timeoutMs, attempt - 1);
            ludashi_log(buf);
            break;
        }
        if (evalP) {
            reinterpret_cast<EvalFn>(evalP)(rs, appId, true);
            int lastSt = reinterpret_cast<StateFn>(stateP)(rs, appId);
            int curSt = curP ? reinterpret_cast<CurFn>(curP)(rs, appId) : -1;
            std::snprintf(buf, sizeof(buf),
                "cloud: EvaluateRemoteStorageSyncState(%u,true) before attempt %d; "
                "lastKnown=%d (%s) current=%d (%s)",
                appId, attempt, lastSt, cs_sync_state_name(lastSt),
                curSt, curSt >= 0 ? cs_sync_state_name(curSt) : "n/a");
            ludashi_log(buf);
        }
        if (onExit || !syncP) {
            bool started = reinterpret_cast<RunFn>(runP)(rs, appId);
            std::snprintf(buf, sizeof(buf),
                "cloud: RunAutoCloudOnApp%s(app=%u) attempt %d -> %d",
                onExit ? "Exit" : "Launch", appId, attempt, started ? 1 : 0);
            ludashi_log(buf);
        }
        if (syncP) {
            const unsigned int mode = onExit ? kSyncToServer : kSyncToClient;
            const unsigned long long acFlags = onExit ? kSyncFlagAutoCloudExit : kSyncFlagNone;
            bool queued = reinterpret_cast<SyncFn>(syncP)(rs, appId, mode, acFlags);
            std::snprintf(buf, sizeof(buf),
                "cloud: SynchronizeApp(app=%u mode=%u flags=%llu %s) attempt %d -> %d",
                appId, mode, acFlags, onExit ? "up: local -> cloud" : "down: cloud -> local",
                attempt, queued ? 1 : 0);
            ludashi_log(buf);
        }

        int attemptCap = remaining < kCloudAttemptCapMs ? remaining : kCloudAttemptCapMs;
        int waited = 0;
        int nextHeartbeat = 5000;
        int stableState = -1;
        int stableMs = 0;
        bool synced = false;
        int syncedResult = 0;
        int syncedTransfers = 0;
        const int wantCb = onExit ? kCbRemoteStorageAppSyncedServer : kCbRemoteStorageAppSyncedClient;
        int seenIds[kCloudSeenIdSlots] = {0};
        int seenCounts[kCloudSeenIdSlots] = {0};
        int seenDistinct = 0;
        int clearedAt = -1;
        const DWORD attemptStart = ::GetTickCount();
        while (waited < attemptCap) {
            if (g_bgetcallback && g_freelastcallback) {
                unsigned char cb[64];
                int drained = 0;
                while (drained < kCloudDrainCapPerTick && g_bgetcallback(g_pipe, cb)) {
                    int cbId = 0;
                    std::memcpy(&cbId, cb + 4, sizeof(cbId));
                    {
                        int slot = 0;
                        while (slot < seenDistinct && seenIds[slot] != cbId) ++slot;
                        if (slot == seenDistinct && seenDistinct < kCloudSeenIdSlots) {
                            seenIds[slot] = cbId;
                            ++seenDistinct;
                        }
                        if (slot < seenDistinct) ++seenCounts[slot];
                    }
                    if (cbId == kCbClientRemoteStorageAppSyncedClient) cbId = kCbRemoteStorageAppSyncedClient;
                    if (cbId == kCbClientRemoteStorageAppSyncedServer) cbId = kCbRemoteStorageAppSyncedServer;
                    if (cbId == kCbRemoteStorageAppSyncedClient || cbId == kCbRemoteStorageAppSyncedServer) {
                        const unsigned char* param = nullptr;
                        std::memcpy(&param, cb + 8, sizeof(param));
                        unsigned int cbApp = 0;
                        int cbResult = 0;
                        int cbTransfers = 0;
                        if (param) {
                            std::memcpy(&cbApp, param, sizeof(cbApp));
                            std::memcpy(&cbResult, param + 4, sizeof(cbResult));
                            std::memcpy(&cbTransfers, param + 8, sizeof(cbTransfers));
                        }
                        std::snprintf(buf, sizeof(buf),
                            "cloud: RemoteStorageAppSynced%s_t app=%u eResult=%d transfers=%d after %dms",
                            cbId == kCbRemoteStorageAppSyncedClient ? "Client" : "Server",
                            cbApp, cbResult, cbTransfers, waited);
                        ludashi_log(buf);
                        if (cbId == wantCb && (cbApp == appId || cbApp == 0)) {
                            synced = true;
                            syncedResult = cbResult;
                            syncedTransfers = cbTransfers;
                        }
                    }
                    g_freelastcallback(g_pipe);
                    ++drained;
                }
                if (drained >= kCloudDrainCapPerTick) {
                    std::snprintf(buf, sizeof(buf),
                        "cloud: callback queue still non-empty after draining %d entries — "
                        "continuing without a full drain", drained);
                    ludashi_log(buf);
                }
            }
            if (synced) break;
            ::Sleep(10);
            const int prevWaited = waited;
            waited = (int) (::GetTickCount() - attemptStart);
            if (waited >= nextHeartbeat) {
                std::snprintf(buf, sizeof(buf),
                    "cloud: %s sync still in progress after %dms (cap %dms)", phase, waited, attemptCap);
                ludashi_log(buf);
                nextHeartbeat += 5000;
            }
            if (waited >= kCloudMinSettleMs) {
                if (!reinterpret_cast<InProgFn>(inProgP)(rs, appId)) {
                    if (clearedAt < 0) {
                        clearedAt = waited;
                        std::snprintf(buf, sizeof(buf),
                            "cloud: IsAppSyncInProgress cleared after %dms without a sync callback — "
                            "holding up to %dms for Steam's completion callback", waited, kCloudCallbackGraceMs);
                        ludashi_log(buf);
                    }
                    if (waited - clearedAt >= kCloudCallbackGraceMs) {
                        std::snprintf(buf, sizeof(buf),
                            "cloud: no completion callback within %dms of the in-progress flag clearing",
                            waited - clearedAt);
                        ludashi_log(buf);
                        break;
                    }
                    continue;
                }
                const int cur = curP ? reinterpret_cast<CurFn>(curP)(rs, appId) : kSyncInProgress;
                if (cur == stableState && cur != kSyncInProgress) {
                    stableMs += waited - prevWaited;
                    if (stableMs >= kCloudStableSettleMs) {
                        std::snprintf(buf, sizeof(buf),
                            "cloud: %s sync has reported \"%s\" with no transfer left for %dms while "
                            "IsAppSyncInProgress stayed set — treating the sync as finished",
                            phase, cs_sync_state_name(cur), stableMs);
                        ludashi_log(buf);
                        break;
                    }
                } else {
                    stableState = cur;
                    stableMs = 0;
                }
            }
        }
        {
            char ids[400];
            int used = 0;
            for (int slot = 0; slot < seenDistinct && used < (int) sizeof(ids) - 24; ++slot) {
                used += std::snprintf(ids + used, sizeof(ids) - used, "%s%d x%d",
                                      slot ? ", " : "", seenIds[slot], seenCounts[slot]);
            }
            std::snprintf(buf, sizeof(buf), "cloud: callbacks seen while waiting: %s",
                          seenDistinct ? ids : "(none)");
            ludashi_log(buf);
        }
        finalState = reinterpret_cast<StateFn>(stateP)(rs, appId);
        std::snprintf(buf, sizeof(buf),
            "cloud: %s sync settled state=%d (%s) after %dms%s eResult=%d transfers=%d",
            phase, finalState, cs_sync_state_name(finalState), waited,
            synced ? " via callback" : " without callback", syncedResult, syncedTransfers);
        ludashi_log(buf);
        if (synced) {
            sawSyncCallback = true;
            lastSyncResult = syncedResult;
            lastSyncTransfers = syncedTransfers;
        }
        if (synced && syncedResult != kEResultOK) {
            if (syncedResult == kEResultTooManyPending) {
                std::snprintf(buf, sizeof(buf),
                    "cloud: %s sync refused with EResult %d (TooManyPending) — Steam still has "
                    "unfinished cloud operations registered for this app on another machine or "
                    "an earlier session, so it transferred nothing",
                    phase, syncedResult);
            } else {
                std::snprintf(buf, sizeof(buf),
                    "cloud: %s sync job reported EResult %d — Steam did not complete the transfer",
                    phase, syncedResult);
            }
            ludashi_log(buf);
            if (attempt < kCloudMaxPendingAttempts) continue;
            break;
        }

        if (finalState == kSyncSynchronized || finalState == kSyncDisabled) break;

        if (finalState == kSyncConflicting) {
            if (onExit) {
                ludashi_log("cloud: conflict on exit — leaving both copies intact and deferring the "
                       "prompt to the next launch, where the UI is alive to answer it");
                break;
            }
            if (!cs_resolve_conflict(rs, rs_vt, appId, &userWaitMs)) { finalState = -1; break; }
            continue;
        }

        if (finalState == kSyncChangesInCloud || finalState == kSyncChangesLocally ||
            finalState == kSyncChangesBoth || finalState == kSyncUnknown ||
            finalState == kSyncNotInitialized) {
            if (!onExit && finalState == kSyncChangesLocally) {
                ludashi_log("cloud: launch sync finished with local saves ahead of the cloud — there "
                       "is nothing to download, so the game starts now and the exit sync "
                       "uploads them");
                break;
            }
            if (attempt >= kCloudMaxPendingAttempts) {
                std::snprintf(buf, sizeof(buf),
                    "cloud: %s sync still reports \"%s\" after %d attempt(s) — proceeding rather "
                    "than re-running a sync that has already settled",
                    phase, cs_sync_state_name(finalState), attempt);
                ludashi_log(buf);
                break;
            }
            continue;
        }
        break;
    }

    if (!onExit && (finalState == kSyncSynchronized || finalState == kSyncChangesLocally) &&
        !(sawSyncCallback && lastSyncResult != kEResultOK)) {
        bool recorded = reinterpret_cast<RunFn>(runP)(rs, appId);
        std::snprintf(buf, sizeof(buf),
            "cloud: RunAutoCloudOnAppLaunch(app=%u) after the download -> %d "
            "(launch record for the exit diff)", appId, recorded ? 1 : 0);
        ludashi_log(buf);
    }

    if (sawSyncCallback && lastSyncResult != kEResultOK) {
        std::snprintf(buf, sizeof(buf),
            "cloud: %s sync FAILED for app=%u — Steam returned EResult %d and moved %d file(s); "
            "local saves kept",
            phase, appId, lastSyncResult, lastSyncTransfers);
        finalState = -1;
    } else if (finalState == kSyncSynchronized) {
        std::snprintf(buf, sizeof(buf),
            "cloud: %s sync COMPLETE for app=%u — saves are synchronized (%d file(s) transferred)",
            phase, appId, lastSyncTransfers);
    } else if (finalState == kSyncDisabled) {
        std::snprintf(buf, sizeof(buf),
            "cloud: %s sync skipped for app=%u — Steam Cloud disabled",
            phase, appId);
    } else {
        std::snprintf(buf, sizeof(buf),
            "cloud: %s sync did NOT reach synchronized for app=%u "
            "(final state=%d %s) — local saves kept",
            phase, appId, finalState, cs_sync_state_name(finalState));
    }
    ludashi_log(buf);
    if (onExit && finalState == kSyncChangesLocally) {
        ludashi_log("cloud: exit sync left local saves ahead of Steam Cloud — the app must upload them");
    }
    cs_report(appId, phase, finalState, lastSyncTransfers);
    return finalState;
}

extern "C" void ludashi_launcher_clean_shutdown_now(const char* reason) {
    teardown(reason ? reason : "explicit");
}

extern "C" void ludashi_launcher_wait_clean_shutdown(int maxMs) {
    int waited = 0;
    while (g_done.load() && !g_teardown_complete.load() && waited < maxMs) {
        ::Sleep(50);
        waited += 50;
    }
}
