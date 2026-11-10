#pragma once

#include <windows.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <string>
#include <unordered_map>
#include <vector>

#include "agent_channel.h"

namespace af {

#ifdef __i386__
#define AF_THISCALL __thiscall
#else
#define AF_THISCALL
#endif

// ISteamClient (SDK): slot 8 = GetISteamFriends(HSteamUser, HSteamPipe, const char* version).
static const int kVtClient_GetISteamFriends = 8;

// ISteamFriends017 slots (SDK isteamfriends.h; identical for 015/016 up to slot 71).
static const int kVtF_GetPersonaName              = 0;
static const int kVtF_GetPersonaState             = 2;
static const int kVtF_GetFriendCount              = 3;
static const int kVtF_GetFriendByIndex            = 4;
static const int kVtF_GetFriendRelationship       = 5;
static const int kVtF_GetFriendPersonaState       = 6;
static const int kVtF_GetFriendPersonaName        = 7;
static const int kVtF_GetFriendGamePlayed         = 8;
static const int kVtF_RequestUserInformation      = 37;   // bool(CSteamID, bool bRequireNameOnly)
static const int kVtF_GetFriendRichPresence       = 45;   // const char*(CSteamID, const char* key)
static const int kVtF_SetListenForFriendsMessages = 64;
static const int kVtF_ReplyToFriendMessage        = 65;
static const int kVtF_GetFriendMessage            = 66;
static const int kVtF_Count                       = 67;   // slots we need to exist

static const int kFriendFlagImmediate  = 0x04;   // k_EFriendFlagImmediate
static const int kCbPersonaStateChange = 304;    // PersonaStateChange_t
static const int kCbFriendChatMsg      = 343;    // GameConnectedFriendChatMsg_t
static const int kChatEntryTypeChatMsg = 1;      // k_EChatEntryTypeChatMsg
static const int kChatEntryTypeTyping  = 2;      // k_EChatEntryTypeTyping
static const int kMaxChatBytes         = 8192;
static const int kRequestBatch         = 20;     // RequestUserInformation calls per tick (rate limit)
static const int kRequestRounds        = 3;      // re-ask for still-unknown friends at most this often
static const int kRelFriend            = 3;      // k_EFriendRelationshipFriend
static const int kRelRequestRecipient  = 2;      // k_EFriendRelationshipRequestRecipient
static const int kRelRequestInitiator  = 4;      // k_EFriendRelationshipRequestInitiator

// ISteamFriends002 (SDK 2006; the client still serves it — lsteamclient thunks the same layout):
// 0 GetPersonaName, 1 SetPersonaName, 2 GetPersonaState, 3 SetPersonaState(EPersonaState), 4 GetFriendCount.
static const int kVtF2_GetPersonaName  = 0;
static const int kVtF2_GetPersonaState = 2;
static const int kVtF2_SetPersonaState = 3;
static const int kPersonaStateOnline   = 1;      // k_EPersonaStateOnline
static const int kPresenceSettleMs     = 20000;  // after going online: CM presence stream settle window

typedef void* (*af_create_interface_fn)(const char* version, int* rc);
typedef void (AF_THISCALL *SetPersonaStateFn)(void* self, int state);

typedef const char* (AF_THISCALL *GetPersonaNameFn)(void* self);
typedef int         (AF_THISCALL *GetPersonaStateFn)(void* self);
typedef int         (AF_THISCALL *GetFriendCountFn)(void* self, int flags);
typedef uint64_t*   (AF_THISCALL *GetFriendByIndexFn)(void* self, uint64_t* out, int idx, int flags);
typedef int         (AF_THISCALL *GetFriendRelationshipFn)(void* self, uint64_t sid);
typedef int         (AF_THISCALL *GetFriendPersonaStateFn)(void* self, uint64_t sid);
typedef const char* (AF_THISCALL *GetFriendPersonaNameFn)(void* self, uint64_t sid);
typedef bool        (AF_THISCALL *GetFriendGamePlayedFn)(void* self, uint64_t sid, void* info);
typedef bool        (AF_THISCALL *RequestUserInformationFn)(void* self, uint64_t sid, bool nameOnly);
typedef const char* (AF_THISCALL *GetFriendRichPresenceFn)(void* self, uint64_t sid, const char* key);
typedef bool        (AF_THISCALL *SetListenFn)(void* self, bool listen);
typedef bool        (AF_THISCALL *ReplyToFriendMessageFn)(void* self, uint64_t sid, const char* text);
typedef int         (AF_THISCALL *GetFriendMessageFn)(void* self, uint64_t sid, int msgId, void* data, int cub, int* type);

// FriendGameInfo_t (pack 8): CGameID m_gameID@0, uint32 ip@8, uint16 port@12, uint16 query@14,
// CSteamID lobby@16 — 24 bytes.
struct FriendGameInfo { uint64_t gameId; uint32_t ip; uint16_t port; uint16_t query; uint64_t lobby; };

static void*        g_friends = nullptr;
static bool         g_enabled = false;
static bool         g_ready = false;
static bool         g_rpOk = false;          // GetFriendRichPresence slot usable
static ULONGLONG    g_lastRoster = 0;
static ac_log_fn    g_log = nullptr;

// One friend as the client last showed it to us. `known` = the client confirmed this friend's
// presence (RequestUserInformation said "already available", or a PersonaStateChange_t arrived);
// until then `state` is the post-logon default (Offline) and must not be trusted.
struct Snap {
    std::string name;
    int         state = 0;
    int         rel = 0;
    uint32_t    app = 0;
    std::string rp;
    bool        known = false;
    bool        emitted = false;   // a `persona` for this exact snapshot has been sent
};
static std::unordered_map<uint64_t, Snap> g_cache;
static std::vector<uint64_t> g_pending;      // RequestUserInformation queue (drained kRequestBatch per tick)
static int  g_reqSent = 0;                   // requests the client actually sent (round total)
static int  g_reqCached = 0;                 // "already available" answers (round total)
static int  g_reqRounds = 0;
static bool g_reqRoundOpen = false;          // a round's summary log line is still owed
static ULONGLONG g_onlineAt = 0;             // tick when we announced Online (0 = never)
static bool g_settled = false;               // settle window elapsed: unknown friends treated as Offline
static int  g_cbPersona = 0;                 // PersonaStateChange_t seen since the relay went live
static int  g_cbPersonaRoster = 0;           // ... since the last roster line (log only)
static int  g_cbPersonaEver = 0;             // PersonaStateChange_t seen since PROCESS start (counted before the relay arms)
static int  g_cbPresenceEver = 0;            // ... of those, carrying presence flags (Status/ComeOnline/GoneOffline/GamePlayed)
// EPersonaChange bits that carry PRESENCE: Status 0x2, ComeOnline 0x4, GoneOffline 0x8, GamePlayed 0x10. The
// logon-time stream is names/avatars only (device, p3d-2: 21 callbacks at logon, zero presence, while the app
// session saw 1 in-game + 2 away) — only these bits prove the CM is streaming presence to this session.
static const int kPersonaPresenceFlags = 0x0002 | 0x0004 | 0x0008 | 0x0010;

static inline bool exec_ptr(void* p) {
    if (!p) return false;
    MEMORY_BASIC_INFORMATION mbi;
    if (!VirtualQuery(p, &mbi, sizeof(mbi))) return false;
    if (mbi.State != MEM_COMMIT) return false;
    DWORD pr = mbi.Protect & 0xFF;
    return pr == PAGE_EXECUTE || pr == PAGE_EXECUTE_READ ||
           pr == PAGE_EXECUTE_READWRITE || pr == PAGE_EXECUTE_WRITECOPY;
}

static inline void** vt() { return g_friends ? *(void***) g_friends : nullptr; }

template <typename F> static inline F slot(int idx) {
    void** v = vt();
    return v ? (F) v[idx] : (F) nullptr;
}

static inline bool ready() { return g_enabled && g_ready && g_friends && ac::alive(); }

// Our own persona state as the client reports it (-1 = interface not up).
static inline int self_state() {
    GetPersonaStateFn fn = slot<GetPersonaStateFn>(kVtF_GetPersonaState);
    return (g_friends && fn) ? fn(g_friends) : -1;
}

static inline std::string u64s(uint64_t v) {
    char b[32];
    snprintf(b, sizeof(b), "%llu", (unsigned long long) v);
    return b;
}

// Reads one friend from the client: name / state / relationship / game app id, plus the "status"
// rich-presence value when the friend is in a game (the only case it can be non-empty; cheap: one
// string lookup in the client's cache). `known`/`emitted` are NOT touched here.
static inline void read_snap(uint64_t sid, Snap& s) {
    GetFriendPersonaNameFn nameFn = slot<GetFriendPersonaNameFn>(kVtF_GetFriendPersonaName);
    GetFriendPersonaStateFn stFn = slot<GetFriendPersonaStateFn>(kVtF_GetFriendPersonaState);
    GetFriendRelationshipFn relFn = slot<GetFriendRelationshipFn>(kVtF_GetFriendRelationship);
    GetFriendGamePlayedFn gameFn = slot<GetFriendGamePlayedFn>(kVtF_GetFriendGamePlayed);
    GetFriendRichPresenceFn rpFn = g_rpOk ? slot<GetFriendRichPresenceFn>(kVtF_GetFriendRichPresence) : nullptr;
    s.name.clear();
    s.state = 0; s.rel = 0; s.app = 0; s.rp.clear();
    if (nameFn) { const char* n = nameFn(g_friends, sid); if (n) s.name = n; }
    if (stFn) s.state = stFn(g_friends, sid);
    if (relFn) s.rel = relFn(g_friends, sid);
    if (gameFn) {
        FriendGameInfo fgi;
        memset(&fgi, 0, sizeof(fgi));
        if (gameFn(g_friends, sid, &fgi)) s.app = (uint32_t) (fgi.gameId & 0xFFFFFFu);   // CGameID: app id = low 24 bits
    }
    if (rpFn && s.app != 0) { const char* rp = rpFn(g_friends, sid, "status"); if (rp) s.rp = rp; }
}

static inline bool same_presence(const Snap& a, const Snap& b) {
    return a.state == b.state && a.app == b.app && a.rel == b.rel && a.name == b.name && a.rp == b.rp;
}

// One friend's snapshot as a JSON object body (fields after `"sid"`, no braces).
static inline std::string snap_fields(uint64_t sid, const Snap& s) {
    char tail[128];
    snprintf(tail, sizeof(tail), ",\"state\":%d,\"rel\":%d,\"app\":%lu,\"k\":%d",
             s.state, s.rel, (unsigned long) s.app, s.known ? 1 : 0);
    std::string j = "\"sid\":\"" + u64s(sid) + "\",\"name\":" + ac::json_str(s.name.c_str()) + tail;
    if (!s.rp.empty()) j += ",\"rp\":" + ac::json_str(s.rp.c_str());
    return j;
}

// Re-reads the friend, stores it, and returns the cache entry.
static inline Snap& refresh_snap(uint64_t sid) {
    Snap& s = g_cache[sid];
    Snap fresh;
    fresh.known = s.known;
    fresh.emitted = s.emitted;
    read_snap(sid, fresh);
    if (fresh.state != 0) fresh.known = true;              // a non-Offline read is real information
    if (!same_presence(s, fresh)) fresh.emitted = false;   // something changed → a persona is owed
    s = fresh;
    return s;
}

// `persona` event for one friend — only when its presence is confirmed and differs from what was
// last sent (or nothing was sent yet). Relationship-filtered: friends and pending requests only
// (PersonaStateChange_t also fires for clan members, chat peers and ourselves).
static inline void emit_persona(uint64_t sid) {
    if (!ready() || sid == 0) return;
    Snap& s = refresh_snap(sid);
    if (s.rel != kRelFriend && s.rel != kRelRequestRecipient && s.rel != kRelRequestInitiator) return;
    if (!s.known || s.emitted) return;
    ac::emit("{\"ev\":\"persona\"," + snap_fields(sid, s) + "}");
    s.emitted = true;
}

// Full roster + self persona → `friends` event. Every entry carries `k` (presence known).
static inline void emit_roster() {
    if (!ready()) return;
    GetFriendCountFn countFn = slot<GetFriendCountFn>(kVtF_GetFriendCount);
    GetFriendByIndexFn byIdxFn = slot<GetFriendByIndexFn>(kVtF_GetFriendByIndex);
    GetPersonaNameFn selfNameFn = slot<GetPersonaNameFn>(kVtF_GetPersonaName);
    GetPersonaStateFn selfStFn = slot<GetPersonaStateFn>(kVtF_GetPersonaState);
    if (!countFn || !byIdxFn) return;
    int n = countFn(g_friends, kFriendFlagImmediate);
    if (n < 0) n = 0;
    if (n > 2000) n = 2000;
    std::string j = "{\"ev\":\"friends\",\"self\":{\"name\":";
    const char* selfName = selfNameFn ? selfNameFn(g_friends) : "";
    j += ac::json_str(selfName ? selfName : "");
    char st[48];
    snprintf(st, sizeof(st), ",\"state\":%d},\"count\":%d,\"list\":[", selfStFn ? selfStFn(g_friends) : 0, n);
    j += st;
    int emitted = 0, known = 0;
    for (int i = 0; i < n; ++i) {
        uint64_t sid = 0;
        byIdxFn(g_friends, &sid, i, kFriendFlagImmediate);
        if (sid == 0) continue;
        Snap& s = refresh_snap(sid);
        if (s.known) { ++known; s.emitted = true; }   // the roster line carries this exact snapshot
        if (emitted++ > 0) j += ',';
        j += "{" + snap_fields(sid, s) + "}";
    }
    j += "]}";
    ac::emit(j);
    g_lastRoster = GetTickCount64();
    if (g_log) g_log("[ludashi-launcher] friends: roster sent (%d friend(s), %d with known presence, self state=%d, "
                     "%d persona callback(s) since last roster, %d since relay start, %d with presence flags since process start)",
                     n, known, self_state(), g_cbPersonaRoster, g_cbPersona, g_cbPresenceEver);
    g_cbPersonaRoster = 0;
}

// Announces Online through ISteamFriends002::SetPersonaState so the CM streams the friends'
// presence to this session (see the header comment). Only when the client reports itself Offline
// — a state the user picked elsewhere is left alone. The 002 adapter is validated first: its
// GetPersonaName/GetPersonaState (slots 0/2) must agree with the 017 adapter, else no call is made.
typedef void* (AF_THISCALL *GetISteamFriendsFn)(void* self, int hUser, int hPipe, const char* version);

static inline void go_online(GetISteamFriendsFn gif, void* client, int hUser, int pipe) {
    int before = self_state();
    // The client REPORTS Online (1) for a headless session that never announced anything (device,
    // p3d-1/2: state=1, 21 name-only callbacks at logon, zero presence) — the read is the local
    // default, not proof of a ClientChangeStatus. This session is ours and headless (nobody set a
    // state on it), so announce whenever the CM has not streamed presence yet; idempotent otherwise.
    if (g_cbPresenceEver > 0) {
        if (g_log) g_log("[ludashi-launcher] friends: self persona state=%d, %d presence callback(s) already seen - stream is live",
                         before, g_cbPresenceEver);
        g_onlineAt = GetTickCount64();
        return;
    }
    if (g_log) g_log("[ludashi-launcher] friends: self persona state=%d, %d persona callback(s) but none with presence flags - announcing Online",
                     before, g_cbPersonaEver);
    void* f2 = gif(client, hUser, pipe, "SteamFriends002");
    if (!f2) { if (g_log) g_log("[ludashi-launcher] friends: SteamFriends002 not served - cannot announce Online (presence may stay unknown)"); return; }
    void** v2 = *(void***) f2;
    for (int i = 0; i <= kVtF2_SetPersonaState; ++i) {
        if (!exec_ptr(v2[i])) { if (g_log) g_log("[ludashi-launcher] friends: SteamFriends002 slot %d not exec - not announcing Online", i); return; }
    }
    GetPersonaNameFn selfNameFn = slot<GetPersonaNameFn>(kVtF_GetPersonaName);
    const char* n017 = selfNameFn ? selfNameFn(g_friends) : nullptr;
    const char* n002 = ((GetPersonaNameFn) v2[kVtF2_GetPersonaName])(f2);
    int s002 = ((GetPersonaStateFn) v2[kVtF2_GetPersonaState])(f2);
    if (!n017 || !n002 || strcmp(n017, n002) != 0 || s002 != before) {
        if (g_log) g_log("[ludashi-launcher] friends: SteamFriends002 layout check failed (name match=%d, state %d vs %d) - not announcing Online",
                         (n017 && n002 && strcmp(n017, n002) == 0) ? 1 : 0, s002, before);
        return;
    }
    ((SetPersonaStateFn) v2[kVtF2_SetPersonaState])(f2, kPersonaStateOnline);
    g_onlineAt = GetTickCount64();
    if (g_log) g_log("[ludashi-launcher] friends: self persona state %d -> %d (SteamFriends002::SetPersonaState Online)", before, self_state());
}

// Queues RequestUserInformation for every roster friend whose presence is not confirmed yet.
// Bounded to kRequestRounds rounds per run (init + the periodic roster ticks).
static inline void queue_presence_requests() {
    if (!ready() || g_reqRounds >= kRequestRounds) return;
    if (!slot<RequestUserInformationFn>(kVtF_RequestUserInformation)) return;
    GetFriendCountFn countFn = slot<GetFriendCountFn>(kVtF_GetFriendCount);
    GetFriendByIndexFn byIdxFn = slot<GetFriendByIndexFn>(kVtF_GetFriendByIndex);
    if (!countFn || !byIdxFn) return;
    int n = countFn(g_friends, kFriendFlagImmediate);
    if (n < 0) n = 0;
    if (n > 2000) n = 2000;
    int queued = 0;
    for (int i = 0; i < n; ++i) {
        uint64_t sid = 0;
        byIdxFn(g_friends, &sid, i, kFriendFlagImmediate);
        if (sid == 0) continue;
        std::unordered_map<uint64_t, Snap>::iterator it = g_cache.find(sid);
        if (it != g_cache.end() && it->second.known) continue;
        bool dup = false;
        for (uint64_t p : g_pending) if (p == sid) { dup = true; break; }
        if (!dup) { g_pending.push_back(sid); ++queued; }
    }
    if (queued == 0) return;
    ++g_reqRounds;
    g_reqSent = 0;
    g_reqCached = 0;
    g_reqRoundOpen = true;
    if (g_log) g_log("[ludashi-launcher] friends: presence round %d - %d friend(s) queued", g_reqRounds, queued);
}

// Sends at most kRequestBatch queued RequestUserInformation(sid, false) calls (name + avatar).
// `true` = the client asked the CM and a PersonaStateChange_t follows; `false` = the NAME is
// already cached — that says nothing about presence (p3c's mistake), so nothing is marked known
// here: confirmation comes from on_callback / a non-Offline read / the settle window only.
static inline void pump_presence_requests() {
    if (g_pending.empty() || !ready()) return;
    RequestUserInformationFn req = slot<RequestUserInformationFn>(kVtF_RequestUserInformation);
    if (!req) { g_pending.clear(); return; }
    int batch = 0;
    while (!g_pending.empty() && batch < kRequestBatch) {
        uint64_t sid = g_pending.back();
        g_pending.pop_back();
        ++batch;
        if (req(g_friends, sid, false)) ++g_reqSent; else ++g_reqCached;
    }
    if (g_pending.empty() && g_reqRoundOpen) {
        g_reqRoundOpen = false;
        if (g_log) g_log("[ludashi-launcher] friends: persona requests sent (%d), %d already cached",
                         g_reqSent, g_reqCached);
    }
}

// Callback drain hook: called with the CallbackMsg_t buffer for every callback the loops see —
// from EVERY drain site in main.cpp, including the ones before the relay is armed: the CM's
// presence stream (if any) lands right after logon, long before the game is running, and those
// PersonaStateChange_t are what prove the client's Offline reads are real (see the settle logic).
static inline void on_callback(const char* cb) {
    if (!cb) return;
    int cbid = *(const int*) (cb + 4);
    const char* param = *(const char* const*) (cb + 8);
    int cub = *(const int*) (cb + 16);
    if (cbid == kCbPersonaStateChange) {
        ++g_cbPersonaEver;
        if (param && cub >= 12) {
            int flags; memcpy(&flags, param + 8, 4);   // PersonaStateChange_t::m_nChangeFlags
            if (flags & kPersonaPresenceFlags) ++g_cbPresenceEver;
        }
    }
    if (!ready()) return;
    if (cbid == kCbPersonaStateChange) {
        // PersonaStateChange_t: uint64 m_ulSteamID@0, int m_nChangeFlags@8. Arrival = the client
        // now holds this user's presence (answer to RequestUserInformation, or a live change).
        if (!param || cub < 12) return;
        uint64_t sid; memcpy(&sid, param, 8);
        if (sid == 0) return;
        ++g_cbPersona;
        ++g_cbPersonaRoster;
        g_cache[sid].known = true;
        emit_persona(sid);
    } else if (cbid == kCbFriendChatMsg) {
        // GameConnectedFriendChatMsg_t: CSteamID m_steamIDUser@0, int m_iMessageID@8
        if (!param || cub < 12) return;
        uint64_t sid; memcpy(&sid, param, 8);
        int msgId; memcpy(&msgId, param + 8, 4);
        GetFriendMessageFn getMsg = slot<GetFriendMessageFn>(kVtF_GetFriendMessage);
        if (!getMsg || sid == 0) return;
        std::vector<char> buf((size_t) kMaxChatBytes + 1, 0);
        int type = 0;
        int got = getMsg(g_friends, sid, msgId, buf.data(), kMaxChatBytes, &type);
        if (got < 0) got = 0;
        if (got > kMaxChatBytes) got = kMaxChatBytes;
        buf[(size_t) got] = 0;
        if (type == kChatEntryTypeTyping) {
            ac::emit("{\"ev\":\"chat_typing\",\"sid\":\"" + u64s(sid) + "\"}");
            return;
        }
        if (type != kChatEntryTypeChatMsg || got == 0) return;
        char ts[48];
        snprintf(ts, sizeof(ts), ",\"ts\":%lld}", (long long) (time(NULL)));
        ac::emit("{\"ev\":\"chat_in\",\"sid\":\"" + u64s(sid) + "\",\"text\":" + ac::json_str(buf.data()) + ts);
        if (g_log) g_log("[ludashi-launcher] friends: chat message relayed (%d byte(s))", got);
    }
}

// ---- command JSON helpers -----------------------------------------------------------

static inline void append_utf8(std::string& out, unsigned cp) {
    if (cp < 0x80) out += (char) cp;
    else if (cp < 0x800) { out += (char) (0xC0 | (cp >> 6)); out += (char) (0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { out += (char) (0xE0 | (cp >> 12)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
    else { out += (char) (0xF0 | (cp >> 18)); out += (char) (0x80 | ((cp >> 12) & 0x3F)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
}

// Value of "key" in a one-line JSON object: a quoted string (unescaped) or a bare token.
static inline std::string field(const std::string& line, const char* key) {
    std::string needle = std::string("\"") + key + "\"";
    size_t k = line.find(needle);
    if (k == std::string::npos) return std::string();
    size_t p = k + needle.size();
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    if (p >= line.size() || line[p] != ':') return std::string();
    ++p;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    if (p >= line.size()) return std::string();
    std::string out;
    if (line[p] == '"') {
        ++p;
        while (p < line.size()) {
            char c = line[p];
            if (c == '"') break;
            if (c == '\\' && p + 1 < line.size()) {
                char e = line[++p];
                switch (e) {
                    case 'n': out += '\n'; break;
                    case 'r': out += '\r'; break;
                    case 't': out += '\t'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'u': {
                        if (p + 4 < line.size()) {
                            unsigned cp = (unsigned) strtoul(line.substr(p + 1, 4).c_str(), NULL, 16);
                            p += 4;
                            // surrogate pair
                            if (cp >= 0xD800 && cp <= 0xDBFF && p + 6 < line.size() &&
                                line[p + 1] == '\\' && line[p + 2] == 'u') {
                                unsigned lo = (unsigned) strtoul(line.substr(p + 3, 4).c_str(), NULL, 16);
                                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                    p += 6;
                                }
                            }
                            append_utf8(out, cp);
                        }
                        break;
                    }
                    default: out += e; break;
                }
                ++p;
                continue;
            }
            out += c;
            ++p;
        }
        return out;
    }
    while (p < line.size() && line[p] != ',' && line[p] != '}' && line[p] != ' ') out += line[p++];
    return out;
}

// ---- commands (queued by the channel reader thread, run here on the main thread) -----

static inline void handle_command(const std::string& cmd, const std::string& line) {
    if (cmd == "friends_refresh") {
        emit_roster();
    } else if (cmd == "chat_send") {
        uint64_t sid = (uint64_t) strtoull(field(line, "sid").c_str(), NULL, 10);
        std::string text = field(line, "text");
        bool ok = false;
        ReplyToFriendMessageFn reply = slot<ReplyToFriendMessageFn>(kVtF_ReplyToFriendMessage);
        if (ready() && reply && sid != 0 && !text.empty()) ok = reply(g_friends, sid, text.c_str());
        ac::emit("{\"ev\":\"chat_sent\",\"sid\":\"" + u64s(sid) + "\",\"ok\":" + (ok ? "true" : "false") + "}");
        if (g_log) g_log("[ludashi-launcher] friends: chat send -> %s", ok ? "ok" : "refused");
    } else if (cmd == "chat_typing") {
        // No public typing primitive in ISteamFriends — accepted, ignored (documented).
    }
}

// Per-loop-tick work: drain queued commands, one batch of presence requests, periodic roster
// snapshot only on explicit refresh (Ludashi); initial presence requests remain bounded.
static inline void tick() {
    if (!g_enabled) return;
    std::vector<std::string> pending = ac::drain_friends_commands();
    for (const std::string& line : pending) handle_command(ac::extract_cmd(line), line);
    pump_presence_requests();
    // Settle: once the CM has streamed presence for this session (>= 1 PersonaStateChange_t) and the
    // window passed, every friend it never mentioned is genuinely Offline — confirm them so the app
    // can drop "Status unknown". Without a single callback nothing is assumed (the app keeps its hint).
    if (ready() && !g_settled && g_onlineAt != 0 && GetTickCount64() - g_onlineAt > (ULONGLONG) kPresenceSettleMs) {
        g_settled = true;
        int assumed = 0;
        if (g_cbPresenceEver > 0) {
            for (std::unordered_map<uint64_t, Snap>::iterator it = g_cache.begin(); it != g_cache.end(); ++it)
                if (!it->second.known) { it->second.known = true; ++assumed; }
        }
        if (g_log) g_log("[ludashi-launcher] friends: presence settled after %d ms - %d persona callback(s) since relay start, %d since process start (%d with presence flags), %d friend(s) assumed Offline%s",
                         kPresenceSettleMs, g_cbPersona, g_cbPersonaEver, g_cbPresenceEver, assumed,
                         g_cbPresenceEver == 0 ? " (no presence stream from the CM - left unknown)" : "");
        if (assumed > 0) emit_roster();
    }
    // Ludashi: refresh the roster only on initial connection or an explicit command.

}

// Relay disabled for this run: log once, tell the app (`friends_relay{state:"off",reason}`), and make
// every later call a no-op.
static inline bool relay_off(const char* reason) {
    g_friends = nullptr;
    g_ready = false;
    g_enabled = false;
    g_cache.clear();
    g_pending.clear();
    g_onlineAt = 0;
    g_settled = false;
    if (g_log) g_log("[ludashi-launcher] friends: %s - relay off", reason);
    ac::emit_friends_relay("off", reason);
    return false;
}

static inline bool init(af_create_interface_fn ci, int hUser, int pipe, ac_log_fn log, const char* stage) {
    g_log = log;
    const char* env = getenv("LUDASHI_AGENT_FRIENDS");
    g_enabled = env && (*env == '1' || *env == 't' || *env == 'T');
    if (!g_enabled) return relay_off("LUDASHI_AGENT_FRIENDS not set");
    if (!ac::alive()) return relay_off("channel not up");
    if (!ci) return relay_off("CreateInterface export missing");
    if (g_log) g_log("[ludashi-launcher] friends: starting relay (%s)", stage ? stage : "");
    static const char* clientVersions[] = { "SteamClient021", "SteamClient020", "SteamClient019", "SteamClient018", "SteamClient017" };
    void* client = nullptr;
    for (const char* v : clientVersions) {
        int rc = 0;
        client = ci(v, &rc);
        if (client) { if (g_log) g_log("[ludashi-launcher] friends: %s -> %p", v, client); break; }
    }
    if (!client) return relay_off("no ISteamClient");
    void** cvt = *(void***) client;
    if (!exec_ptr(cvt[kVtClient_GetISteamFriends])) return relay_off("GetISteamFriends slot not exec");
    GetISteamFriendsFn gif = (GetISteamFriendsFn) cvt[kVtClient_GetISteamFriends];
    static const char* friendsVersions[] = { "SteamFriends017", "SteamFriends016", "SteamFriends015" };
    for (const char* v : friendsVersions) {
        g_friends = gif(client, hUser, pipe, v);
        if (g_friends) { if (g_log) g_log("[ludashi-launcher] friends: %s -> %p", v, g_friends); break; }
    }
    if (!g_friends) return relay_off("no ISteamFriends");
    void** fvt = vt();
    for (int i = 0; i < kVtF_Count; ++i) {
        // Only the slots we call must be executable; the rest are just read for the bound check.
        if (i == kVtF_GetPersonaName || i == kVtF_GetPersonaState || i == kVtF_GetFriendCount ||
            i == kVtF_GetFriendByIndex || i == kVtF_GetFriendRelationship || i == kVtF_GetFriendPersonaState ||
            i == kVtF_GetFriendPersonaName || i == kVtF_GetFriendGamePlayed || i == kVtF_SetListenForFriendsMessages ||
            i == kVtF_ReplyToFriendMessage || i == kVtF_GetFriendMessage || i == kVtF_RequestUserInformation) {
            if (!exec_ptr(fvt[i])) {
                char why[64];
                snprintf(why, sizeof(why), "ISteamFriends slot %d not exec", i);
                return relay_off(why);
            }
        }
    }
    // Rich presence is optional: a non-executable slot just drops the `rp` field.
    g_rpOk = exec_ptr(fvt[kVtF_GetFriendRichPresence]);
    g_cache.clear();
    g_pending.clear();
    g_reqRounds = 0;
    g_reqRoundOpen = false;
    g_onlineAt = 0;
    g_settled = false;
    g_cbPersona = 0;
    g_cbPersonaRoster = 0;
    SetListenFn listen = slot<SetListenFn>(kVtF_SetListenForFriendsMessages);
    bool listening = listen ? listen(g_friends, true) : false;
    g_ready = true;
    if (g_log) g_log("[ludashi-launcher] friends: relay live (listen=%d, rp=%d, %s)", listening ? 1 : 0, g_rpOk ? 1 : 0, stage ? stage : "");
    ac::emit_friends_relay("live", stage ? stage : "");
    // p3d: announce Online FIRST — that is what makes the CM stream the friends' presence to this
    // session (the roster below is all "Offline"/k:0 until the PersonaStateChange_t stream lands).
    go_online(gif, client, hUser, pipe);
    emit_roster();
    // Name/avatar requests (kRequestBatch per tick); presence confirmation comes from the callbacks.
    queue_presence_requests();
    pump_presence_requests();
    return true;
}

}  // namespace af
