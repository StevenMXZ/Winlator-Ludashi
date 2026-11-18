#ifndef WINLATOR_SOCKET_UTILS_H
#define WINLATOR_SOCKET_UTILS_H

#include <sys/socket.h>

#define MAX_FDS 32

static inline int send_fds(int sockFd, int* fds, int numFds, void* data, int size) {
    if (!data) size = 1;
    char zero = 0;
    struct iovec iovmsg = {.iov_base = data ? data : &zero, .iov_len = size};
    struct {
        struct cmsghdr align;
        int fds[MAX_FDS];
    } ctrlmsg;

    struct msghdr msg = {
        .msg_name = NULL,
        .msg_namelen = 0,
        .msg_iov = &iovmsg,
        .msg_iovlen = 1,
        .msg_flags = 0,
        .msg_control = &ctrlmsg,
        .msg_controllen = sizeof(struct cmsghdr) + numFds * sizeof(int)
    };

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = msg.msg_controllen;

    for (int i = 0; i < numFds; i++) ((int*)CMSG_DATA(cmsg))[i] = fds[i];
    return sendmsg(sockFd, &msg, 0);
}

static inline int recv_fds(int sockFd, int* outFds, int* outNumFds, void* outData, int size) {
    if (!outData) size = 1;
    char zero = 0;
    struct iovec iovmsg = {.iov_base = outData ? outData : &zero, .iov_len = size};
    struct {
        struct cmsghdr align;
        int fds[MAX_FDS];
    } ctrlmsg;

    struct msghdr msg = {
        .msg_name = NULL,
        .msg_namelen = 0,
        .msg_iov = &iovmsg,
        .msg_iovlen = 1,
        .msg_flags = 0,
        .msg_control = &ctrlmsg,
        .msg_controllen = sizeof(struct cmsghdr) + MAX_FDS * sizeof(int)
    };

    *outNumFds = 0;
    int res = recvmsg(sockFd, &msg, 0);
    if (res > 0) {
        struct cmsghdr* cmsg;
        for (cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
                int numFds = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                if (numFds > 0) {
                    for (int i = 0; i < numFds; i++) outFds[i] = ((int*)CMSG_DATA(cmsg))[i];
                    *outNumFds = numFds;
                }
            }
        }
    }
    return res;
}

static inline int sock_read(int fd, char* buffer, int size) {
    char *ptr = buffer;
    int left;
    int result;

    left = size;
    do {
        result = read(fd, ptr, left);
        if (result <= 0) return result == -1 ? -1 : 0;
        left -= result;
        ptr += result;
    }
    while (left);

    return size;
}

static inline int sock_write(int fd, char* buffer, int size) {
    char *ptr = buffer;
    int left;
    int result;
    left = size;

    do {
        result = write(fd, ptr, left);
        if (result < 0) return -1;
        left -= result;
        ptr += result;
    }
    while (left);

    return size;
}

#endif