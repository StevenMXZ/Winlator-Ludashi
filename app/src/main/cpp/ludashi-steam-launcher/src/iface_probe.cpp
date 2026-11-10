#include <windows.h>
#include <iphlpapi.h>

#ifdef __i386__
#define LUDASHI_STEAM_API_DLL "steam_api.dll"
#define LUDASHI_PROBE_ARCH "32"
#else
#define LUDASHI_STEAM_API_DLL "steam_api64.dll"
#define LUDASHI_PROBE_ARCH "64"
#endif
#include <cstdio>
#include <cstdlib>
#include <cstring>

typedef bool (*SteamAPI_Init_t)();
typedef int (*SteamInternal_SteamAPI_Init_t)(const char*, char*);
typedef int (*SteamAPI_GetHSteamUser_t)();
typedef void* (*SteamInternal_FindOrCreateUserInterface_t)(int, const char*);
typedef void* (*SteamInternal_ContextInit_t)(void*);
typedef void (*SteamAPI_Shutdown_t)();

static FILE* g_log = nullptr;

static void plog(const char* fmt, ...) __attribute__((format(gnu_printf, 1, 2)));
static void plog(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (g_log) {
        fputs(buf, g_log);
        fputc('\n', g_log);
        fflush(g_log);
    }
}

static const char* const kInterfaces[] = {
    "STEAMAPPS_INTERFACE_VERSION008",
    "STEAMUSERSTATS_INTERFACE_VERSION012",
    "STEAMREMOTESTORAGE_INTERFACE_VERSION016",
    "STEAMUGC_INTERFACE_VERSION020",
    "STEAMHTTP_INTERFACE_VERSION003",
    "STEAMHTMLSURFACE_INTERFACE_VERSION_005",
    "STEAMSCREENSHOTS_INTERFACE_VERSION003",
    "STEAMREMOTEPLAY_INTERFACE_VERSION002",
    "STEAMMUSIC_INTERFACE_VERSION001",
    "STEAMMUSICREMOTE_INTERFACE_VERSION001",
    "STEAMPARENTALSETTINGS_INTERFACE_VERSION001",
    "STEAMUSER_INTERFACE_VERSION023",
    "STEAMFRIENDS_INTERFACE_VERSION017",
    "STEAMUTILS_INTERFACE_VERSION010",
    "STEAMNETWORKING_INTERFACE_VERSION006",
    "STEAMMATCHMAKING_INTERFACE_VERSION009",
};

typedef int (*Flat_GetHSteamUser_t)(void*);
typedef bool (*Flat_BLoggedOn_t)(void*);
typedef unsigned long long (*Flat_GetSteamID_t)(void*);
typedef unsigned int (*Flat_GetAuthSessionTicket_t)(void*, void*, int, unsigned int*, void*);
typedef int (*Flat_GetEncryptedAppTicket_t)(void*, void*, int, unsigned int*);
typedef unsigned long long (*Flat_RequestEncryptedAppTicket_t)(void*, void*, int);
typedef void (*RunCallbacks_t)(void);
typedef int (*Flat_GetAppBuildId_t)(void*);
typedef bool (*Flat_BIsSubscribed_t)(void*);

static void probe_auth_ticket(HMODULE api, void* user, void* apps) {
    Flat_GetHSteamUser_t fGetUser =
        (Flat_GetHSteamUser_t) GetProcAddress(api, "SteamAPI_ISteamUser_GetHSteamUser");
    Flat_BLoggedOn_t fLoggedOn =
        (Flat_BLoggedOn_t) GetProcAddress(api, "SteamAPI_ISteamUser_BLoggedOn");
    Flat_GetSteamID_t fSteamId =
        (Flat_GetSteamID_t) GetProcAddress(api, "SteamAPI_ISteamUser_GetSteamID");
    Flat_GetAuthSessionTicket_t fTicket =
        (Flat_GetAuthSessionTicket_t) GetProcAddress(api, "SteamAPI_ISteamUser_GetAuthSessionTicket");
    Flat_GetEncryptedAppTicket_t fEnc =
        (Flat_GetEncryptedAppTicket_t) GetProcAddress(api, "SteamAPI_ISteamUser_GetEncryptedAppTicket");
    Flat_GetAppBuildId_t fBuildId =
        (Flat_GetAppBuildId_t) GetProcAddress(api, "SteamAPI_ISteamApps_GetAppBuildId");
    Flat_BIsSubscribed_t fSubscribed =
        (Flat_BIsSubscribed_t) GetProcAddress(api, "SteamAPI_ISteamApps_BIsSubscribed");

    if (apps && fBuildId) {
        plog("[ludashi-probe] version: ISteamApps::GetAppBuildId() = %d", fBuildId(apps));
    }
    if (apps && fSubscribed) {
        plog("[ludashi-probe] version: ISteamApps::BIsSubscribed() = %d", fSubscribed(apps) ? 1 : 0);
    }

    if (!user) {
        plog("[ludashi-probe] auth: no ISteamUser — cannot test the ticket the game presents");
        return;
    }
    if (!fGetUser || !fLoggedOn || !fSteamId || !fTicket) {
        plog("[ludashi-probe] auth: flat exports missing (GetHSteamUser=%p BLoggedOn=%p "
             "GetSteamID=%p GetAuthSessionTicket=%p)",
             (void*) fGetUser, (void*) fLoggedOn, (void*) fSteamId, (void*) fTicket);
        return;
    }

    plog("[ludashi-probe] auth anchors: GetHSteamUser=%d BLoggedOn=%d GetSteamID=%llu",
         fGetUser(user), fLoggedOn(user) ? 1 : 0, fSteamId(user));

    unsigned char ticket[2048];
    memset(ticket, 0, sizeof(ticket));
    unsigned int cb = 0;
    unsigned int h = fTicket(user, ticket, (int) sizeof(ticket), &cb, nullptr);
    plog("[ludashi-probe] auth: GetAuthSessionTicket -> handle=%u length=%u", h, cb);
    if (h == 0 || cb == 0) {
        plog("[ludashi-probe] auth: *** NO TICKET ISSUED *** — the game cannot authenticate "
             "to its own backend with this session");
    } else {
        char hex[97];
        unsigned int n = cb < 32 ? cb : 32;
        for (unsigned int i = 0; i < n; ++i) snprintf(hex + i * 3, 4, "%02x ", ticket[i]);
        hex[n * 3] = '\0';
        plog("[ludashi-probe] auth: ticket first %u bytes: %s", n, hex);
    }

    Flat_RequestEncryptedAppTicket_t fReqEnc =
        (Flat_RequestEncryptedAppTicket_t) GetProcAddress(api, "SteamAPI_ISteamUser_RequestEncryptedAppTicket");
    RunCallbacks_t fRun = (RunCallbacks_t) GetProcAddress(api, "SteamAPI_RunCallbacks");

    {
        typedef const char* (*S_t)(void*);
        typedef bool (*B_t)(void*);
        typedef unsigned int (*U_t)(void*);
        typedef unsigned int (*UA_t)(void*, unsigned int);
        typedef int (*I_t)(void*);
        struct { const char* nm; const char* sym; int kind; } q[] = {
            {"ISteamUtils::GetIPCountry",          "SteamAPI_ISteamUtils_GetIPCountry", 0},
            {"ISteamUtils::GetSteamUILanguage",    "SteamAPI_ISteamUtils_GetSteamUILanguage", 0},
            {"ISteamApps::GetCurrentGameLanguage", "SteamAPI_ISteamApps_GetCurrentGameLanguage", 0},
            {"ISteamApps::BIsCybercafe",           "SteamAPI_ISteamApps_BIsCybercafe", 1},
            {"ISteamApps::BIsLowViolence",         "SteamAPI_ISteamApps_BIsLowViolence", 1},
            {"ISteamApps::BIsSubscribedFromFreeWeekend","SteamAPI_ISteamApps_BIsSubscribedFromFreeWeekend", 1},
            {"ISteamUser::BIsBehindNAT",           "SteamAPI_ISteamUser_BIsBehindNAT", 1},
            {"ISteamUtils::GetServerRealTime",     "SteamAPI_ISteamUtils_GetServerRealTime", 2},
            {"ISteamUtils::GetSecondsSinceAppActive","SteamAPI_ISteamUtils_GetSecondsSinceAppActive", 2},
            {"ISteamUser::GetPlayerSteamLevel",    "SteamAPI_ISteamUser_GetPlayerSteamLevel", 3},
        };
        for (unsigned i = 0; i < sizeof(q)/sizeof(q[0]); ++i) {
            void* fn = (void*) GetProcAddress(api, q[i].sym);
            void* self = user;
            if (strstr(q[i].sym, "ISteamUtils")) {
                typedef void* (*GU_t)(void);
                GU_t g = (GU_t) GetProcAddress(api, "SteamAPI_SteamUtils_v010");
                if (!g) g = (GU_t) GetProcAddress(api, "SteamAPI_SteamUtils_v009");
                self = g ? g() : nullptr;
            } else if (strstr(q[i].sym, "ISteamApps")) {
                self = apps;
            }
            if (!fn || !self) { plog("[ludashi-probe] q %-42s = <unavailable>", q[i].nm); continue; }
            if (q[i].kind == 0) {
                const char* v = ((S_t) fn)(self);
                plog("[ludashi-probe] q %-42s = \"%s\"", q[i].nm, v ? v : "(null)");
            } else if (q[i].kind == 1) {
                plog("[ludashi-probe] q %-42s = %d", q[i].nm, ((B_t) fn)(self) ? 1 : 0);
            } else if (q[i].kind == 2) {
                plog("[ludashi-probe] q %-42s = %u", q[i].nm, ((U_t) fn)(self));
            } else {
                plog("[ludashi-probe] q %-42s = %d", q[i].nm, ((I_t) fn)(self));
            }
        }
        void* lenFn = (void*) GetProcAddress(api, "SteamAPI_ISteamUser_GetAuthTicketForWebApi");
        plog("[ludashi-probe] q GetAuthTicketForWebApi export           = %s",
             lenFn ? "present" : "MISSING");
    }

    {
        DWORD vser = 0; char volName[MAX_PATH] = {0}, fsName[MAX_PATH] = {0};
        DWORD maxComp = 0, flags = 0;
        BOOL okv = GetVolumeInformationA("C:\\", volName, sizeof(volName), &vser,
                                         &maxComp, &flags, fsName, sizeof(fsName));
        plog("[ludashi-probe] hw  GetVolumeInformation(C:) ok=%d serial=0x%08lx label=\"%s\" fs=\"%s\"",
             okv ? 1 : 0, (unsigned long) vser, volName, fsName);

        HMODULE ip = LoadLibraryA("iphlpapi.dll");
        typedef unsigned long (WINAPI *GAI_t)(void*, unsigned long*);
        GAI_t gai = ip ? (GAI_t) GetProcAddress(ip, "GetAdaptersInfo") : nullptr;
        if (!gai) { plog("[ludashi-probe] hw  GetAdaptersInfo UNAVAILABLE"); }
        else {
            unsigned long sz = 0;
            unsigned long rc = gai(nullptr, &sz);
            plog("[ludashi-probe] hw  GetAdaptersInfo sizeprobe rc=%lu needed=%lu", rc, sz);
            if (sz) {
                unsigned char* buf = (unsigned char*) malloc(sz);
                rc = gai(buf, &sz);
                int n = 0;
                if (rc == 0) {
                    for (PIP_ADAPTER_INFO ai = (PIP_ADAPTER_INFO) buf; ai && n < 6; ai = ai->Next) {
                        char mac[64] = {0};
                        for (UINT k = 0; k < ai->AddressLength && k < 8; ++k)
                            snprintf(mac + k*3, 4, "%02x:", ai->Address[k]);
                        plog("[ludashi-probe] hw  adapter[%d] idx=%lu type=%u addrlen=%u mac=%s ip=%s",
                             n, ai->Index, ai->Type, ai->AddressLength, mac,
                             ai->IpAddressList.IpAddress.String);
                        n++;
                    }
                }
                plog("[ludashi-probe] hw  GetAdaptersInfo rc=%lu adapters=%d", rc, n);
                free(buf);
            }
        }
        char cname[256] = {0}; DWORD cl = sizeof(cname);
        GetComputerNameA(cname, &cl);
        plog("[ludashi-probe] hw  ComputerName=\"%s\"", cname);
    }

    typedef bool (*Flat_IsAPICallCompleted_t)(void*, unsigned long long, bool*);
    typedef void* (*Flat_SteamUtils_t)(void);
    Flat_IsAPICallCompleted_t fDone =
        (Flat_IsAPICallCompleted_t) GetProcAddress(api, "SteamAPI_ISteamUtils_IsAPICallCompleted");
    Flat_SteamUtils_t fUtils = (Flat_SteamUtils_t) GetProcAddress(api, "SteamAPI_SteamUtils_v010");
    if (!fUtils) fUtils = (Flat_SteamUtils_t) GetProcAddress(api, "SteamAPI_SteamUtils_v009");
    void* utils = fUtils ? fUtils() : nullptr;
    plog("[ludashi-probe] callresult: ISteamUtils=%p IsAPICallCompleted=%p",
         utils, (void*) fDone);

    typedef unsigned int (*Flat_GetAppID_t)(void*);
    Flat_GetAppID_t fAppId =
        (Flat_GetAppID_t) GetProcAddress(api, "SteamAPI_ISteamUtils_GetAppID");
    if (utils && fAppId) {
        plog("[ludashi-probe] pipe app context: ISteamUtils::GetAppID -> %u (expect 291550)",
             fAppId(utils));
    } else {
        plog("[ludashi-probe] pipe app context: GetAppID unavailable (utils=%p fn=%p)",
             utils, (void*) fAppId);
    }

    if (fEnc) {
        unsigned char pre[2048];
        unsigned int precb = 0;
        bool prerc = fEnc(user, pre, sizeof(pre), &precb);
        plog("[ludashi-probe] auth: GetEncryptedAppTicket BEFORE any request -> rc=%d length=%u "
             "(this is what SteamAir sees if it never calls Request)",
             prerc ? 1 : 0, precb);
    }

    if (fEnc && fReqEnc) {
        unsigned long long call = fReqEnc(user, nullptr, 0);
        plog("[ludashi-probe] auth: RequestEncryptedAppTicket -> HSteamAPICall=%llu", call);
        if (utils && fDone && call) {
            bool failed = false;
            bool done = false;
            int cw = 0;
            for (int i = 0; i < 150; ++i) {
                if (fRun) fRun();
                Sleep(100);
                cw += 100;
                failed = false;
                done = fDone(utils, call, &failed);
                if (done) break;
            }
            plog("[ludashi-probe] callresult: IsAPICallCompleted(0x%llx) -> done=%d failed=%d after %dms "
                 "-- %s", call, done ? 1 : 0, failed ? 1 : 0, cw,
                 done ? "CallResults DO complete for this pipe (the game's CCallResult would fire)"
                      : "*** NEVER COMPLETED *** async Steam API calls never finish here, so any "
                        "game using CCallResult (e.g. EncryptedAppTicketResponse_t) hangs forever");
        }
        unsigned char enc[4096];
        unsigned int cbEnc = 0;
        int rc = 0;
        int waited = 0;
        for (int i = 0; i < 200; ++i) {
            if (fRun) fRun();
            Sleep(100);
            waited += 100;
            memset(enc, 0, sizeof(enc));
            cbEnc = 0;
            rc = fEnc(user, enc, (int) sizeof(enc), &cbEnc);
            if (rc && cbEnc) break;
        }
        plog("[ludashi-probe] auth: GetEncryptedAppTicket after request -> rc=%d length=%u (%dms)",
             rc, cbEnc, waited);
        if (rc && cbEnc) {
            char hex[97];
            unsigned int n = cbEnc < 32 ? cbEnc : 32;
            for (unsigned int i = 0; i < n; ++i) snprintf(hex + i * 3, 4, "%02x ", enc[i]);
            hex[n * 3] = '\0';
            plog("[ludashi-probe] auth: encrypted app ticket first %u bytes: %s", n, hex);
            plog("[ludashi-probe] auth: encrypted app ticket OBTAINED — publisher-backend auth works");
        } else {
            plog("[ludashi-probe] auth: *** ENCRYPTED APP TICKET NOT ISSUED *** — this is how a Steam "
                 "game authenticates to a publisher backend");
        }
    }
}

static void import_debug_ca() {
    const char* path = "C:\\ludashi-mitm-ca.der";
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD sz = GetFileSize(f, NULL);
    if (sz == 0 || sz > 65536) { CloseHandle(f); return; }
    unsigned char* buf = (unsigned char*) malloc(sz);
    DWORD got = 0;
    BOOL rd = ReadFile(f, buf, sz, &got, NULL);
    CloseHandle(f);
    if (!rd || got == 0) { free(buf); return; }

    const DWORD locations[2] = { CERT_SYSTEM_STORE_CURRENT_USER, CERT_SYSTEM_STORE_LOCAL_MACHINE };
    const char* names[2] = { "CURRENT_USER", "LOCAL_MACHINE" };
    for (int i = 0; i < 2; ++i) {
        HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_A, 0, 0, locations[i], "ROOT");
        if (!store) {
            plog("[ludashi-probe] mitm-ca: CertOpenStore(%s) failed GLE=%lu",
                 names[i], (unsigned long) GetLastError());
            continue;
        }
        BOOL ok = CertAddEncodedCertificateToStore(store, X509_ASN_ENCODING, buf, got,
                                                   CERT_STORE_ADD_REPLACE_EXISTING, NULL);
        plog("[ludashi-probe] mitm-ca: import into %s ROOT -> %s (GLE=%lu, %u bytes)",
             names[i], ok ? "ok" : "FAILED", (unsigned long) GetLastError(), (unsigned) got);
        CertCloseStore(store, 0);
    }
    free(buf);
}

static void probe_ane_dlls(const char* gameDir) {
    static const char* const kAnes[][2] = {
        { "DnaManager", "DnaManager.dll" },
        { "SteamAir", "SteamAir.dll" },
        { "DesktopExtension", "DesktopExtension.dll" },
        { "RawData", "RawData.dll" },
        { "MultiKeyboard", "MultiKeyboard.dll" },
        { "SoundEngineExtension", "SoundEngineExtension.dll" },
        { "WindowsExtensionWrapper", "FrameFixDLL.dll" },
        { "EpicAir", "EpicAir.dll" },
    };
    const int n = (int) (sizeof(kAnes) / sizeof(kAnes[0]));
    plog("[ludashi-probe] ---- ANE LoadLibrary probe (%d extensions) ----", n);
    char airDir[MAX_PATH];
    snprintf(airDir, sizeof(airDir), "%s\\Adobe AIR\\Versions\\1.0", gameDir);
    char airDll[MAX_PATH];
    snprintf(airDll, sizeof(airDll), "%s\\Adobe AIR.dll", airDir);
    SetDllDirectoryA(airDir);
    HMODULE air = LoadLibraryExA(airDll, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    plog("[ludashi-probe] Adobe AIR.dll %s (GLE=%lu) path=%s",
         air ? "LOADED" : "FAILED", (unsigned long) GetLastError(), airDll);
    for (int i = 0; i < n; ++i) {
        char path[MAX_PATH];
        snprintf(path, sizeof(path),
                 "%s\\META-INF\\AIR\\extensions\\%s\\META-INF\\ANE\\Windows-x86-64\\%s",
                 gameDir, kAnes[i][0], kAnes[i][1]);
        DWORD attr = GetFileAttributesA(path);
        if (attr == INVALID_FILE_ATTRIBUTES) {
            plog("[ludashi-probe] ANE %-24s MISSING on disk (%s)", kAnes[i][0], path);
            continue;
        }
        HMODULE h = LoadLibraryExA(path, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
        if (h) {
            void* init = (void*) GetProcAddress(h, "DnaInitializer");
            plog("[ludashi-probe] ANE %-24s LOADED ok%s", kAnes[i][0],
                 init ? " (DnaInitializer present)" : "");
            FreeLibrary(h);
        } else {
            plog("[ludashi-probe] ANE %-24s LoadLibrary FAILED GLE=%lu", kAnes[i][0],
                 (unsigned long) GetLastError());
        }
    }
    static const char* const kDeps[] = {
        "msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll", "ucrtbase.dll",
        "api-ms-win-crt-runtime-l1-1-0.dll", "api-ms-win-crt-string-l1-1-0.dll",
        "api-ms-win-crt-stdio-l1-1-0.dll", "api-ms-win-crt-heap-l1-1-0.dll",
        "api-ms-win-crt-convert-l1-1-0.dll", "iphlpapi.dll", "ws2_32.dll",
        "setupapi.dll", "ole32.dll", "oleaut32.dll", "shell32.dll",
    };
    const int m = (int) (sizeof(kDeps) / sizeof(kDeps[0]));
    plog("[ludashi-probe] ---- dependency probe (%d dlls) ----", m);
    for (int i = 0; i < m; ++i) {
        HMODULE h = LoadLibraryA(kDeps[i]);
        if (h) { plog("[ludashi-probe] dep %-38s ok", kDeps[i]); FreeLibrary(h); }
        else plog("[ludashi-probe] dep %-38s MISSING GLE=%lu", kDeps[i],
                  (unsigned long) GetLastError());
    }
}

int main(int argc, char** argv) {
    const char* gameDir = (argc > 1) ? argv[1] : "";
    const char* appId = (argc > 2) ? argv[2] : "";
    const char* logPath = (argc > 3) ? argv[3] : "C:\\ludashi-iface-probe.log";

    g_log = fopen(logPath, "w");
    import_debug_ca();
    plog("[ludashi-probe] start gameDir=\"%s\" appId=%s", gameDir, appId);
    probe_ane_dlls(gameDir);

    if (appId && appId[0]) {
        SetEnvironmentVariableA("SteamAppId", appId);
        SetEnvironmentVariableA("SteamGameId", appId);
    }

    char dllPath[MAX_PATH];
    snprintf(dllPath, sizeof(dllPath), "%s\\" LUDASHI_STEAM_API_DLL, gameDir);
    HMODULE api = LoadLibraryA(dllPath);
    if (!api) {
        plog("[ludashi-probe] LoadLibrary(\"%s\") failed GLE=%lu; trying bare name",
             dllPath, (unsigned long) GetLastError());
        api = LoadLibraryA(LUDASHI_STEAM_API_DLL);
    }
    if (!api) {
        plog("[ludashi-probe] FATAL: " LUDASHI_STEAM_API_DLL " not loadable GLE=%lu",
             (unsigned long) GetLastError());
        return 2;
    }
    plog("[ludashi-probe] arch=" LUDASHI_PROBE_ARCH "-bit " LUDASHI_STEAM_API_DLL " loaded at %p", (void*) api);

    SteamAPI_Init_t initLegacy =
        (SteamAPI_Init_t) GetProcAddress(api, "SteamAPI_Init");
    SteamInternal_SteamAPI_Init_t initInternal =
        (SteamInternal_SteamAPI_Init_t) GetProcAddress(api, "SteamInternal_SteamAPI_Init");
    SteamAPI_GetHSteamUser_t getUser =
        (SteamAPI_GetHSteamUser_t) GetProcAddress(api, "SteamAPI_GetHSteamUser");
    SteamInternal_FindOrCreateUserInterface_t findIface =
        (SteamInternal_FindOrCreateUserInterface_t)
            GetProcAddress(api, "SteamInternal_FindOrCreateUserInterface");
    SteamAPI_Shutdown_t shutdown =
        (SteamAPI_Shutdown_t) GetProcAddress(api, "SteamAPI_Shutdown");

    plog("[ludashi-probe] exports: SteamAPI_Init=%p SteamInternal_SteamAPI_Init=%p "
         "GetHSteamUser=%p FindOrCreateUserInterface=%p",
         (void*) initLegacy, (void*) initInternal, (void*) getUser, (void*) findIface);

    bool ok = false;
    if (initInternal) {
        char err[1024];
        memset(err, 0, sizeof(err));
        int rc = initInternal(nullptr, err);
        ok = (rc == 0);
        plog("[ludashi-probe] SteamInternal_SteamAPI_Init -> %d (%s) err=\"%s\"",
             rc, ok ? "OK" : "FAILED", err);
    } else if (initLegacy) {
        ok = initLegacy();
        plog("[ludashi-probe] SteamAPI_Init -> %s", ok ? "true" : "false");
    } else {
        plog("[ludashi-probe] FATAL: no init export found");
        return 3;
    }

    if (!ok) {
        plog("[ludashi-probe] SteamAPI init FAILED — this is what SteamAir would see");
        return 4;
    }

    int hUser = getUser ? getUser() : 0;
    plog("[ludashi-probe] HSteamUser=%d", hUser);

    if (!findIface) {
        plog("[ludashi-probe] FATAL: SteamInternal_FindOrCreateUserInterface missing");
        return 5;
    }

    int served = 0, missing = 0;
    for (size_t i = 0; i < sizeof(kInterfaces) / sizeof(kInterfaces[0]); ++i) {
        void* p = findIface(hUser, kInterfaces[i]);
        if (p) served++; else missing++;
        plog("[ludashi-probe] iface %-44s -> %s (%p)",
             kInterfaces[i], p ? "SERVED" : "*** NULL ***", p);
    }
    plog("[ludashi-probe] summary: served=%d missing=%d", served, missing);

    probe_auth_ticket(api,
                      findIface(hUser, "SteamUser023"),
                      findIface(hUser, "STEAMAPPS_INTERFACE_VERSION008"));

    if (shutdown) shutdown();
    plog("[ludashi-probe] done");
    if (g_log) fclose(g_log);
    return 0;
}
