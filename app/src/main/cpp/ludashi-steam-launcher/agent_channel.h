#pragma once

#include <winsock2.h>
#include <windows.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

typedef void (*ac_log_fn)(const char* fmt, ...);

namespace ac {

static SOCKET               g_sock = INVALID_SOCKET;
static std::atomic<bool>    g_alive{false};
static std::atomic<bool>    g_logoff_requested{false};
static std::atomic<bool>    g_shutdown_sent{false};
static std::atomic<bool>    g_logged_in{false};
static std::atomic<bool>    g_game_running{false};
static std::atomic<bool>    g_secure{false};
static std::atomic<uint32_t> g_appid{0};
static std::mutex           g_send_mu;
static ULONGLONG            g_t0 = 0;
static ac_log_fn            g_log = nullptr;
static const char*          g_shutdown_reason = "exit";
static std::string          g_region;   // LUDASHI_STEAM_REGION as seeded ("" = none)
// Commands the friends relay (agent_friends.h) runs on the main thread; the reader thread only
// queues them here (Steam interfaces are never touched from the socket thread).
static std::mutex           g_cmd_mu;
static std::vector<std::string> g_friends_cmds;

static inline bool alive() { return g_alive.load(std::memory_order_relaxed); }

static inline long long ms_since_start() {
    return (long long)(GetTickCount64() - g_t0);
}

static inline bool logoff_requested() { return g_logoff_requested.load(); }

static inline void set_shutdown_reason(const char* r) { if (r) g_shutdown_reason = r; }
// Region the agent seeded the genuine client's CM list with (reported in started/status).
static inline void set_region(const char* r) { g_region = r ? r : ""; }

// JSON string literal (with surrounding quotes). Escapes '"', '\\' and control
// characters; bytes >= 0x80 are passed through unchanged (expected UTF-8).
static inline std::string json_str(const char* s) {
    std::string out;
    out.reserve(s ? strlen(s) + 2 : 2);
    out += '"';
    for (const unsigned char* p = (const unsigned char*)(s ? s : ""); *p; ++p) {
        unsigned char c = *p;
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char u[8];
                    snprintf(u, sizeof(u), "\\u%04x", (unsigned) c);
                    out += u;
                } else {
                    out += (char) c;
                }
        }
    }
    out += '"';
    return out;
}

static inline void mark_dead(const char* why, int err) {
    bool was = g_alive.exchange(false);
    if (was && g_log) g_log("[ludashi-launcher] agent channel: closed (%s, err=%d)", why, err);
}

// Sends one already-formatted JSON object + '\n'. Bounded by SO_SNDTIMEO (500 ms).
static inline void send_line(const std::string& json) {
    if (!alive()) return;
    std::string line = json;
    line += '\n';
    std::lock_guard<std::mutex> lk(g_send_mu);
    if (!alive()) return;
    const char* p = line.data();
    int left = (int) line.size();
    while (left > 0) {
        int n = send(g_sock, p, left, 0);
        if (n == SOCKET_ERROR || n == 0) {
            mark_dead("send failed", WSAGetLastError());
            return;
        }
        p += n;
        left -= n;
    }
}

static inline void emit(const std::string& json) { if (alive()) send_line(json); }

// ---- events -------------------------------------------------------------------

static inline void emit_started(uint32_t appid) {
    g_appid.store(appid);
    if (!alive()) return;
    char b[160];
    snprintf(b, sizeof(b), "{\"ev\":\"started\",\"pid\":%lu,\"appid\":%lu,\"agent\":\"p3d\",\"region\":",
             (unsigned long) GetCurrentProcessId(), (unsigned long) appid);
    emit(std::string(b) + json_str(g_region.c_str()) + "}");
}

static inline std::string mask_steamid(uint64_t sid) {
    char d[32];
    snprintf(d, sizeof(d), "%llu", (unsigned long long) sid);
    size_t n = strlen(d);
    std::string m = "***";
    m += (n > 4) ? (d + n - 4) : d;
    return m;
}

static inline void emit_logged_in(uint64_t sid) {
    g_logged_in.store(true);
    if (!alive()) return;
    std::string j = "{\"ev\":\"logged_in\",\"steamid\":" + json_str(mask_steamid(sid).c_str());
    char t[48];
    snprintf(t, sizeof(t), ",\"ms\":%lld}", ms_since_start());
    j += t;
    emit(j);
}

static inline void emit_login_failed(int eresult, const char* reason) {
    if (!alive()) return;
    char b[64];
    snprintf(b, sizeof(b), "{\"ev\":\"login_failed\",\"eresult\":%d,\"reason\":", eresult);
    emit(std::string(b) + json_str(reason) + "}");
}

static inline void emit_appinfo(const char* state) {
    if (!alive()) return;
    emit("{\"ev\":\"appinfo\",\"state\":" + json_str(state) + "}");
}

static inline void emit_launch_accepted() {
    if (!alive()) return;
    emit("{\"ev\":\"launch_accepted\"}");
}

static inline void emit_launch_refused(int error, const char* reason) {
    if (!alive()) return;
    char b[64];
    snprintf(b, sizeof(b), "{\"ev\":\"launch_refused\",\"error\":%d,\"reason\":", error);
    emit(std::string(b) + json_str(reason) + "}");
}

static inline void emit_game_spawned(const char* exe, uint32_t pid, bool secure) {
    g_game_running.store(true);
    g_secure.store(secure);
    if (!alive()) return;
    char b[64];
    snprintf(b, sizeof(b), ",\"pid\":%lu,\"secure\":%s}", (unsigned long) pid,
             secure ? "true" : "false");
    emit("{\"ev\":\"game_spawned\",\"exe\":" + json_str(exe) + b);
}

// `vac` = the app's LUDASHI_STEAM_VAC policy for this title (true when the env is absent): true means the
// direct start loses a secure launch the title needs; false means the title never needed one.
static inline void emit_insecure_fallback(const char* exe, const char* reason, bool vac) {
    if (!alive()) return;
    emit("{\"ev\":\"insecure_fallback\",\"exe\":" + json_str(exe) +
         ",\"reason\":" + json_str(reason) + ",\"vac\":" + (vac ? "true" : "false") + "}");
}

// Friends/chat relay verdict (agent p3b): `live` once ISteamFriends is armed, `off` with the reason
// otherwise. Emitted once per run, after the game process is running (or in the M1 resident loop).
static inline void emit_friends_relay(const char* state, const char* reason) {
    if (!alive()) return;
    emit("{\"ev\":\"friends_relay\",\"state\":" + json_str(state) +
         ",\"reason\":" + json_str(reason ? reason : "") + "}");
}

static inline void emit_direct_exe(const char* exe) {
    if (!alive()) return;
    emit("{\"ev\":\"direct_exe\",\"exe\":" + json_str(exe) + "}");
}

static inline void emit_game_exited(int code) {
    g_game_running.store(false);
    if (!alive()) return;
    char b[96];
    snprintf(b, sizeof(b), "{\"ev\":\"game_exited\",\"code\":%d,\"ms\":%lld}",
             code, ms_since_start());
    emit(b);
}

static inline void emit_session_lost() {
    g_logged_in.store(false);
    if (!alive()) return;
    emit("{\"ev\":\"session_lost\"}");
}

static inline void emit_achievement(const char* api) {
    if (!alive()) return;
    emit("{\"ev\":\"achievement\",\"api\":" + json_str(api) + "}");
}

static inline void emit_status() {
    if (!alive()) return;
    char b[192];
    snprintf(b, sizeof(b),
             "{\"ev\":\"status\",\"logged_in\":%s,\"game_running\":%s,\"secure\":%s,\"appid\":%lu,\"region\":",
             g_logged_in.load() ? "true" : "false",
             g_game_running.load() ? "true" : "false",
             g_secure.load() ? "true" : "false",
             (unsigned long) g_appid.load());
    emit(std::string(b) + json_str(g_region.c_str()) + "}");
}

// Last event on every exit path; sent at most once, then the send side is closed so
// the app sees EOF.
static inline void emit_shutdown(const char* reason, int code) {
    bool expected = false;
    if (!g_shutdown_sent.compare_exchange_strong(expected, true)) return;
    if (!alive()) return;
    char b[48];
    snprintf(b, sizeof(b), ",\"code\":%d}", code);
    emit("{\"ev\":\"shutdown\",\"reason\":" + json_str(reason ? reason : g_shutdown_reason) + b);
    std::lock_guard<std::mutex> lk(g_send_mu);
    g_alive.store(false);
    shutdown(g_sock, SD_SEND);
}

// ---- commands (app -> agent) ----------------------------------------------------

// Tiny hand-written extractor: returns the value of the first "cmd":"<value>" pair.
static inline std::string extract_cmd(const std::string& line) {
    size_t k = line.find("\"cmd\"");
    if (k == std::string::npos) return std::string();
    size_t p = k + 5;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    if (p >= line.size() || line[p] != ':') return std::string();
    ++p;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    if (p >= line.size() || line[p] != '"') return std::string();
    ++p;
    size_t e = line.find('"', p);
    if (e == std::string::npos) return std::string();
    return line.substr(p, e - p);
}

static inline void handle_command(const std::string& line) {
    std::string cmd = extract_cmd(line);
    if (cmd == "status") {
        emit_status();
    } else if (cmd == "logoff") {
        bool was = g_logoff_requested.exchange(true);
        if (!was && g_log) g_log("[ludashi-launcher] agent channel: logoff requested by app");
    } else if (cmd == "chat_send" || cmd == "friends_refresh" || cmd == "chat_typing") {
        // Friends relay commands (agent p3): queued for the main thread — see agent_friends.h.
        std::lock_guard<std::mutex> lk(g_cmd_mu);
        if (g_friends_cmds.size() < 256) g_friends_cmds.push_back(line);
    }
    // unknown / malformed commands are ignored
}

// Main-thread drain of the queued friends-relay commands (agent_friends.h tick()).
static inline std::vector<std::string> drain_friends_commands() {
    std::vector<std::string> out;
    std::lock_guard<std::mutex> lk(g_cmd_mu);
    out.swap(g_friends_cmds);
    return out;
}

static inline void reader_thread() {
    std::string acc;
    char buf[512];
    for (;;) {
        int n = recv(g_sock, buf, (int) sizeof(buf), 0);
        if (n <= 0) {
            mark_dead(n == 0 ? "peer closed" : "recv failed", n == 0 ? 0 : WSAGetLastError());
            return;
        }
        acc.append(buf, (size_t) n);
        size_t nl;
        while ((nl = acc.find('\n')) != std::string::npos) {
            std::string line = acc.substr(0, nl);
            acc.erase(0, nl + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) handle_command(line);
        }
        if (acc.size() > 8192) acc.clear();  // garbage guard: drop an unterminated blob
    }
}

// ---- init -------------------------------------------------------------------------

// Reads LUDASHI_AGENT_PORT; if absent (or not a valid port) returns immediately with NO side
// effects. Otherwise attempts one bounded connect to 127.0.0.1:<port>.
static inline void init_from_env(ac_log_fn log) {
    g_log = log;
    g_t0 = GetTickCount64();
    // Region description travels in `started`/`status`; read it here so the very first
    // event already carries it (the CM-list seed itself runs later in main()).
    const char* region = getenv("LUDASHI_STEAM_REGION");
    g_region = region ? region : "";
    const char* pe = getenv("LUDASHI_AGENT_PORT");
    if (!pe || !*pe) return;
    long port = strtol(pe, NULL, 10);
    if (port <= 0 || port > 65535) {
        if (g_log) g_log("[ludashi-launcher] agent channel: unavailable (bad LUDASHI_AGENT_PORT '%s')", pe);
        return;
    }

    WSADATA wsa;
    int wr = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (wr != 0) {
        if (g_log) g_log("[ludashi-launcher] agent channel: unavailable (%d)", wr);
        return;
    }
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        int e = WSAGetLastError();
        if (g_log) g_log("[ludashi-launcher] agent channel: unavailable (%d)", e);
        WSACleanup();
        return;
    }

    u_long nb = 1;
    ioctlsocket(s, FIONBIO, &nb);

    sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((u_short) port);
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    int err = 0;
    int cr = connect(s, (const sockaddr*) &sa, sizeof(sa));
    if (cr == SOCKET_ERROR) {
        err = WSAGetLastError();
        if (err == WSAEWOULDBLOCK) {
            err = 0;
            fd_set wfds, efds;
            FD_ZERO(&wfds);
            FD_ZERO(&efds);
            FD_SET(s, &wfds);
            FD_SET(s, &efds);
            timeval tv;
            tv.tv_sec = 1;
            tv.tv_usec = 500000;  // 1500 ms cap
            int sr = select(0, NULL, &wfds, &efds, &tv);
            if (sr == 0) {
                err = WSAETIMEDOUT;
            } else if (sr == SOCKET_ERROR) {
                err = WSAGetLastError();
            } else if (FD_ISSET(s, &efds) || !FD_ISSET(s, &wfds)) {
                int so = 0;
                int sl = sizeof(so);
                if (getsockopt(s, SOL_SOCKET, SO_ERROR, (char*) &so, &sl) == 0 && so != 0) err = so;
                else err = WSAECONNREFUSED;
            }
        }
    }
    if (err != 0) {
        if (g_log) g_log("[ludashi-launcher] agent channel: unavailable (%d)", err);
        closesocket(s);
        WSACleanup();
        return;
    }

    nb = 0;
    ioctlsocket(s, FIONBIO, &nb);
    DWORD sndTo = 500;
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char*) &sndTo, sizeof(sndTo));
    BOOL nodelay = TRUE;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char*) &nodelay, sizeof(nodelay));

    g_sock = s;
    g_alive.store(true);
    const char* nonce = getenv("LUDASHI_AGENT_NONCE");
    if (nonce) send_line("{\"ev\":\"hello\",\"nonce\":" + json_str(nonce) + "}");
    if (g_log) g_log("[ludashi-launcher] agent channel: connected to 127.0.0.1:%ld", port);
    std::thread(reader_thread).detach();
}

}  // namespace ac
