#ifndef WINLATOR_SOCKET_UTILS_H
#define WINLATOR_SOCKET_UTILS_H
#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#define MAX_FDS 32
static inline int send_fds(int fd, int* fds, int count, void* data, int size) {
    char zero = 0;
    struct iovec iov = {data ? data : &zero, data ? (size_t)size : 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MAX_FDS * sizeof(int))]; } control = {0};
    struct msghdr msg = {0}; msg.msg_iov = &iov; msg.msg_iovlen = 1;
    if (count < 0 || count > MAX_FDS) { errno = EINVAL; return -1; }
    if (count) {
        msg.msg_control = control.bytes; msg.msg_controllen = CMSG_SPACE(count * sizeof(int));
        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(count * sizeof(int));
        memcpy(CMSG_DATA(cmsg), fds, count * sizeof(int));
    }
    int result;
    do { result = sendmsg(fd, &msg, MSG_NOSIGNAL); } while (result < 0 && errno == EINTR);
    return result;
}
static inline int recv_fds(int fd, int* fds, int* count, void* data, int size) {
    char zero;
    struct iovec iov = {data ? data : &zero, data ? (size_t)size : 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MAX_FDS * sizeof(int))]; } control;
    struct msghdr msg = {0}; msg.msg_iov = &iov; msg.msg_iovlen = 1;
    msg.msg_control = control.bytes; msg.msg_controllen = sizeof(control.bytes);
    *count = 0;
    int result;
    do { result = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC); } while (result < 0 && errno == EINTR);
    if (result <= 0) return result;
    for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS || cmsg->cmsg_len < CMSG_LEN(0)) continue;
        size_t n = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t i = 0; i < n; ++i) {
            int received; memcpy(&received, (char*)CMSG_DATA(cmsg) + i * sizeof(int), sizeof(int));
            if (*count < MAX_FDS) fds[(*count)++] = received; else close(received);
        }
    }
    if (msg.msg_flags & MSG_CTRUNC) {
        for (int i = 0; i < *count; ++i) close(fds[i]);
        *count = 0; errno = EMSGSIZE; return -1;
    }
    return result;
}
#endif
