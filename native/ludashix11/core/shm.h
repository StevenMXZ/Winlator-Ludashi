#pragma once

#include <mutex>
#include <string>
#include <unordered_map>

#include <X11/Xmd.h>

namespace xorg {

struct ShmEntry {
    int id;
    void* addr;
    int fd;
    size_t size;
};

struct AttachEntry {
    int shmid;
    void* addr;
    size_t size;
};

class ShmManager {
public:
    explicit ShmManager(const std::string& sock_path);
#ifdef LUDASHI_HOST_TEST
    explicit ShmManager(int fd) : server_fd(fd) {}
#endif

    ~ShmManager();

    bool Attach(u32 xid, int shm_id);

    void Detach(u32 xid);

    const AttachEntry& Get(u32 xid);

private:
    bool ConnectServer();

    void* ShmAttach(int shmid);

    int ShmDetach(int shmid);

    int IPCGetFD(int shmid);

    int server_fd{-1};
    std::string sock_path;
    std::mutex mutex;
    std::unordered_map<int, ShmEntry> shm_entries;
    std::unordered_map<u32, AttachEntry> attach_entries;
};

}  // namespace xorg
