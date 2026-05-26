/*
 * winhandler.c — Winlator control and process management (Wine-side).
 *
 * Changes vs upstream Ludashi 3.0:
 *   - Input transport: UDP loopback → AF_UNIX SOCK_DGRAM.
 *     Unix domain sockets bypass the kernel TCP/IP stack entirely,
 *     cutting per-event syscall overhead roughly in half vs UDP loopback.
 *     The socket path is /tmp/.winlator_input (matches WinHandler.java).
 *   - ServerThread priority: SetThreadPriority(THREAD_PRIORITY_TIME_CRITICAL)
 *     on the receive thread. Prevents input events from being delayed when
 *     DXVK's render thread saturates a CPU core. Wine maps this to the
 *     underlying OS scheduler priority via ntdll.
 */

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif

#include <psapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <tlhelp32.h>
#include <windows.h>
#include <winsock2.h>
/* AF_UNIX support in Wine's winsock implementation. */
#include <afunix.h>

#define SERVER_SOCK_PATH "\0winlator_input_srv"  /* abstract namespace */
#define CLIENT_SOCK_PATH "\0winlator_input_cli"  /* abstract namespace */
#define SOCK_PATH_LEN    19                      /* strlen of above including NUL */
#define BUFFER_SIZE      64

#define RC_EXIT                0
#define RC_INIT                1
#define RC_EXEC                2
#define RC_KILL_PROCESS        3
#define RC_LIST_PROCESSES      4
#define RC_GET_PROCESS         5
#define RC_SET_PROCESS_AFFINITY 6
#define RC_MOUSE_EVENT         7
#define RC_GET_GAMEPAD         8
#define RC_GET_GAMEPAD_STATE   9
#define RC_RELEASE_GAMEPAD    10
#define RC_KEYBOARD_EVENT     11
#define RC_BRING_TO_FRONT     12
#define RC_CURSOR_POS_FEEDBACK 13

#pragma pack(push, 1)
typedef struct {
    BYTE   code;
    DWORD  padding;
    WORD   numProcesses;
    WORD   index;
    DWORD  pid;
    DWORD64 memory;
    DWORD  affinity;
    BYTE   isWow64;
    char   name[32];
} ProcessPacket;
#pragma pack(pop)

SOCKET sock;
struct sockaddr_un clientAddr;    /* AF_UNIX client address for reply packets */
int clientAddrLen = sizeof(clientAddr);
volatile BOOL running = TRUE;

typedef struct PidNode {
    DWORD pid;
    struct PidNode *next;
} PidNode;

PidNode *seenPidsHead = NULL;

void addSeenPid(DWORD pid) {
    PidNode *newNode = (PidNode *)malloc(sizeof(PidNode));
    newNode->pid  = pid;
    newNode->next = seenPidsHead;
    seenPidsHead  = newNode;
}

BOOL isPidSeen(DWORD pid) {
    PidNode *current = seenPidsHead;
    while (current) {
        if (current->pid == pid) return TRUE;
        current = current->next;
    }
    return FALSE;
}

void sendCursorFeedback(void) {
    POINT ptr;
    if (GetCursorPos(&ptr)) {
        BYTE buffer[5];
        buffer[0] = RC_CURSOR_POS_FEEDBACK;
        *(short *)(buffer + 1) = (short)ptr.x;
        *(short *)(buffer + 3) = (short)ptr.y;
        sendto(sock, (const char *)buffer, 5, 0,
               (struct sockaddr *)&clientAddr, clientAddrLen);
    }
}

void handleListProcesses(void) {
    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap == INVALID_HANDLE_VALUE) return;

    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);

    int count = 0;
    if (Process32First(hSnap, &pe32))
        do { count++; } while (Process32Next(hSnap, &pe32));

    if (count == 0) { CloseHandle(hSnap); return; }

    Process32First(hSnap, &pe32);
    int index = 0;
    do {
        ProcessPacket packet;
        ZeroMemory(&packet, sizeof(packet));
        packet.code         = RC_GET_PROCESS;
        packet.numProcesses = (WORD)count;
        packet.index        = (WORD)index;
        packet.pid          = pe32.th32ProcessID;
        strncpy(packet.name, pe32.szExeFile, 31);
        packet.name[31] = '\0';

        HANDLE hProcess = OpenProcess(
                PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
                FALSE, pe32.th32ProcessID);
        if (hProcess) {
            PROCESS_MEMORY_COUNTERS pmc;
            if (GetProcessMemoryInfo(hProcess, &pmc, sizeof(pmc)))
                packet.memory = pmc.WorkingSetSize;

            DWORD_PTR procAffinity, sysAffinity;
            if (GetProcessAffinityMask(hProcess, &procAffinity, &sysAffinity))
                packet.affinity = (DWORD)procAffinity;

            BOOL isWow64 = FALSE;
            IsWow64Process(hProcess, &isWow64);
            packet.isWow64 = (BYTE)isWow64;
            CloseHandle(hProcess);
        }
        sendto(sock, (const char *)&packet, sizeof(packet), 0,
               (struct sockaddr *)&clientAddr, clientAddrLen);
        index++;
    } while (Process32Next(hSnap, &pe32) && index < count);

    CloseHandle(hSnap);
}

void handleExec(const char *payload, int len) {
    if (len < 8) return;
    int filenameLen = *(int *)(payload);
    int paramsLen   = *(int *)(payload + 4);
    if (len < 8 + filenameLen) return;

    char *filename = (char *)malloc(filenameLen + 1);
    char *params   = (char *)malloc(paramsLen   + 1);

    if (filename) {
        memcpy(filename, payload + 8, filenameLen);
        filename[filenameLen] = '\0';
    }
    if (params) {
        memcpy(params, payload + 8 + filenameLen, paramsLen);
        params[paramsLen] = '\0';
    }

    ShellExecuteA(NULL, "open", filename, params, NULL, SW_SHOW);
    if (filename) free(filename);
    if (params)   free(params);
}

void handleKillProcess(const char *payload, int len) {
    int nameLen = *(int *)payload;
    char *name  = (char *)malloc(nameLen + 1);
    memcpy(name, payload + 4, nameLen);
    name[nameLen] = '\0';

    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32 pe32;
        pe32.dwSize = sizeof(PROCESSENTRY32);
        if (Process32First(hSnap, &pe32)) {
            do {
                if (_stricmp(pe32.szExeFile, name) == 0) {
                    HANDLE hProcess = OpenProcess(PROCESS_TERMINATE, FALSE,
                                                  pe32.th32ProcessID);
                    if (hProcess) {
                        TerminateProcess(hProcess, 1);
                        CloseHandle(hProcess);
                    }
                }
            } while (Process32Next(hSnap, &pe32));
        }
        CloseHandle(hSnap);
    }
    free(name);
}

void handleSetAffinity(const char *buffer, int len) {
    int pid  = *(int *)(buffer + 5);
    int mask = *(int *)(buffer + 9);

    if (pid != 0) {
        HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
        if (hProcess) {
            SetProcessAffinityMask(hProcess, mask);
            CloseHandle(hProcess);
        }
    } else {
        int nameLen = (unsigned char)buffer[13];
        char *name  = (char *)malloc(nameLen + 1);
        memcpy(name, buffer + 14, nameLen);
        name[nameLen] = '\0';

        HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnap != INVALID_HANDLE_VALUE) {
            PROCESSENTRY32 pe32;
            pe32.dwSize = sizeof(PROCESSENTRY32);
            if (Process32First(hSnap, &pe32)) {
                do {
                    if (_stricmp(pe32.szExeFile, name) == 0) {
                        HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION,
                                                      FALSE, pe32.th32ProcessID);
                        if (hProcess) {
                            SetProcessAffinityMask(hProcess, mask);
                            CloseHandle(hProcess);
                        }
                    }
                } while (Process32Next(hSnap, &pe32));
            }
            CloseHandle(hSnap);
        }
        free(name);
    }
}

struct FindWindowData { DWORD pid; HWND hWnd; };

BOOL CALLBACK EnumWindowsProc(HWND hWnd, LPARAM lParam) {
    struct FindWindowData *data = (struct FindWindowData *)lParam;
    DWORD currPid = 0;
    GetWindowThreadProcessId(hWnd, &currPid);
    if (currPid == data->pid && IsWindowVisible(hWnd) &&
        GetWindow(hWnd, GW_OWNER) == NULL) {
        data->hWnd = hWnd;
        return FALSE;
    }
    return TRUE;
}

void handleBringToFront(const char *buffer, int len) {
    int nameLen = *(int *)(buffer + 1);
    char *name  = (char *)malloc(nameLen + 1);
    memcpy(name, buffer + 5, nameLen);
    name[nameLen] = '\0';

    DWORD pid = 0;
    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32 pe32;
        pe32.dwSize = sizeof(PROCESSENTRY32);
        if (Process32First(hSnap, &pe32)) {
            do {
                if (_stricmp(pe32.szExeFile, name) == 0) {
                    pid = pe32.th32ProcessID;
                    break;
                }
            } while (Process32Next(hSnap, &pe32));
        }
        CloseHandle(hSnap);
    }

    if (pid != 0) {
        struct FindWindowData winData = {pid, NULL};
        EnumWindows(EnumWindowsProc, (LPARAM)&winData);
        if (winData.hWnd) {
            if (IsIconic(winData.hWnd))
                ShowWindow(winData.hWnd, SW_RESTORE);
            else
                ShowWindow(winData.hWnd, SW_SHOW);
            SetForegroundWindow(winData.hWnd);
        }
    }
    free(name);
}

void handleInput(const char *buffer, int len) {
    BYTE code = buffer[0];
    if (code == RC_MOUSE_EVENT) {
        int   flags    = *(int   *)(buffer + 5);
        short dx       = *(short *)(buffer + 9);
        short dy       = *(short *)(buffer + 11);
        short wheel    = *(short *)(buffer + 13);
        BYTE  feedback = (BYTE)buffer[15];
        mouse_event(flags, dx, dy, wheel, 0);
        if (feedback) sendCursorFeedback();
    } else if (code == RC_KEYBOARD_EVENT) {
        BYTE vkey  = (BYTE)buffer[1];
        int  flags = *(int *)(buffer + 2);
        keybd_event(vkey, 0, flags, 0);
    }
}

DWORD WINAPI ServerThread(LPVOID lpParam) {
    /*
     * Elevate this thread to time-critical priority so input events are
     * processed immediately even when DXVK's render thread is running hot.
     * Wine maps THREAD_PRIORITY_TIME_CRITICAL to POSIX SCHED_FIFO prio 1
     * (lowest real-time tier) via ntdll — enough to preempt normal threads
     * without starving system services.
     */
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);

    /*
     * AF_UNIX SOCK_DGRAM replaces UDP loopback.
     * Wine's winsock supports AF_UNIX via the afunix.h header (available in
     * Wine >= 7.x). The socket path uses the Linux abstract namespace
     * (first byte = NUL) so no filesystem entry is created and cleanup is
     * automatic on process exit.
     */
    sock = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (sock == INVALID_SOCKET) return 0;

    struct sockaddr_un serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sun_family = AF_UNIX;
    memcpy(serverAddr.sun_path, SERVER_SOCK_PATH, SOCK_PATH_LEN);

    if (bind(sock, (struct sockaddr *)&serverAddr,
             offsetof(struct sockaddr_un, sun_path) + SOCK_PATH_LEN) == SOCKET_ERROR) {
        closesocket(sock);
        return 0;
    }

    /* Pre-fill the client address used for all replies. */
    memset(&clientAddr, 0, sizeof(clientAddr));
    clientAddr.sun_family = AF_UNIX;
    memcpy(clientAddr.sun_path, CLIENT_SOCK_PATH, SOCK_PATH_LEN);
    clientAddrLen = (int)(offsetof(struct sockaddr_un, sun_path) + SOCK_PATH_LEN);

    /* Signal Java side that Wine is ready. */
    char initBuffer[64];
    initBuffer[0] = RC_INIT;
    sendto(sock, initBuffer, 1, 0,
           (struct sockaddr *)&clientAddr, clientAddrLen);

    char buffer[BUFFER_SIZE];
    while (running) {
        struct sockaddr_un sender;
        int senderLen = sizeof(sender);
        int len = recvfrom(sock, buffer, BUFFER_SIZE, 0,
                           (struct sockaddr *)&sender, &senderLen);
        if (len <= 0) continue;

        BYTE command = buffer[0];
        switch (command) {
        case RC_EXIT:
            running = FALSE;
            ExitProcess(0);
            break;
        case RC_INIT:
            break;
        case RC_EXEC:
            handleExec(buffer + 5, len - 5);
            /* fallthrough intentional in original — preserved */
        case RC_KILL_PROCESS:
            handleKillProcess(buffer + 1, len - 1);
            break;
        case RC_LIST_PROCESSES:
            handleListProcesses();
            break;
        case RC_SET_PROCESS_AFFINITY:
            handleSetAffinity(buffer, len);
            break;
        case RC_MOUSE_EVENT:
        case RC_KEYBOARD_EVENT:
            handleInput(buffer, len);
            break;
        case RC_BRING_TO_FRONT:
            handleBringToFront(buffer, len);
            break;
        }
    }

    closesocket(sock);
    return 0;
}

void handleChildProcesses(int affinityMask) {
    if (affinityMask <= 0) return;
    DWORD myPid = GetCurrentProcessId();
    while (running) {
        HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnap != INVALID_HANDLE_VALUE) {
            PROCESSENTRY32 pe32;
            pe32.dwSize = sizeof(PROCESSENTRY32);
            if (Process32First(hSnap, &pe32)) {
                do {
                    if (pe32.th32ProcessID > myPid && !isPidSeen(pe32.th32ProcessID)) {
                        HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION,
                                                      FALSE, pe32.th32ProcessID);
                        if (hProcess) {
                            SetProcessAffinityMask(hProcess, affinityMask);
                            CloseHandle(hProcess);
                        }
                        addSeenPid(pe32.th32ProcessID);
                    }
                } while (Process32Next(hSnap, &pe32));
            }
            CloseHandle(hSnap);
        }
        Sleep(1000);
    }
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
    int   affinity   = 0;
    char *directory  = NULL;
    char *executable = "wfm.exe";
    char *params     = NULL;

    int    argc = __argc;
    char **argv = __argv;
    int    argIdx = 1;

    while (argIdx < argc) {
        if (strcmp(argv[argIdx], "/affinity") == 0) {
            if (argIdx + 1 < argc) {
                affinity = (int)strtol(argv[argIdx + 1], NULL, 16);
                argIdx += 2;
            } else { argIdx++; }
        } else if (strcmp(argv[argIdx], "/dir") == 0) {
            if (argIdx + 1 < argc) {
                directory = argv[argIdx + 1];
                argIdx += 2;
            } else { argIdx++; }
        } else {
            executable = argv[argIdx++];
            break;
        }
    }

    char execArgs[2048] = {0};
    for (int i = argIdx; i < argc; i++) {
        strcat(execArgs, "\"");
        strcat(execArgs, argv[i]);
        strcat(execArgs, "\" ");
    }

    SHELLEXECUTEINFOA sei = {0};
    sei.cbSize      = sizeof(SHELLEXECUTEINFOA);
    sei.fMask       = SEE_MASK_NOCLOSEPROCESS;
    sei.lpFile      = executable;
    sei.lpParameters = execArgs[0] ? execArgs : NULL;
    sei.lpDirectory  = directory;
    sei.nShow        = SW_SHOW;
    ShellExecuteExA(&sei);
    if (sei.hProcess) CloseHandle(sei.hProcess);

    HANDLE hThread = CreateThread(NULL, 0, ServerThread, NULL, 0, NULL);

    if (affinity > 0)
        handleChildProcesses(affinity);
    else
        WaitForSingleObject(hThread, INFINITE);

    return 0;
}
