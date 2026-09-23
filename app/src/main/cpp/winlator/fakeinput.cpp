#include "fakeinput_ring.hpp"

#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <iostream>
#include <unordered_map>
#include <memory>
#include <fstream>
#include <algorithm>
#include <mutex>

#include <fcntl.h>
#include <dirent.h>
#include <stdio.h>
#include <sys/types.h>
#include <unistd.h>
#include <dlfcn.h>
#include <poll.h>
#include <stdarg.h>
#include <string.h>
#include <stdbool.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <linux/input.h>

#define EXPORT __attribute__((visibility("default"))) extern "C"
#define FAKEINPUT_GUEST_LOG_TAG "FakeInputGuest"
#define GUEST_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FAKEINPUT_GUEST_LOG_TAG, __VA_ARGS__)
#define GUEST_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FAKEINPUT_GUEST_LOG_TAG, __VA_ARGS__)
static constexpr const char* FAKEINPUT_DIAG_BUILD = "asharedmemory-diag-v1";


std::unordered_map<int, const char *> controller_map;
static bool initialized = false;
static const char *hook_dir = nullptr;
static bool vibration_enabled = true;
volatile sig_atomic_t stop_flag = 0;

static int (*my_open)(const char *, int, ...) = nullptr;
static int (*my_openat)(int, const char *, int, ...) = nullptr;
static int (*my_stat)(const char *, struct stat *) = nullptr;
static int (*my_fstat)(int fd, struct stat *buf) = nullptr;
static int (*my_scandir)(const char *, struct dirent***, int(*)(const struct dirent *), int(*)(const struct dirent**, const struct dirent**));
static int (*my_inotify_add_watch)(int, const char *, uint32_t);
static int (*my_close)(int);
static ssize_t (*my_write)(int, const void *, size_t) = nullptr;

namespace Logger {
	int log_enabled;

	void init() {
		log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
	}

	void log(const char *message, ...) {
		if (!log_enabled)
			return;

		va_list args;
		va_start(args, message);
		vfprintf(stderr, message, args);
		va_end(args);

		std::cerr.flush();
	}
}

void handle_sigint(int sig)  { 
    stop_flag = 1;
} 

void setup_signal_handler() {
    if (!initialized) {
        signal(SIGINT, handle_sigint);
        initialized = true;
    }
}

static std::unordered_map<int, struct ff_effect> ff_effects;
static int next_ff_id = 0;

void send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!vibration_enabled)
    return;

  int sock = socket(AF_UNIX, SOCK_STREAM, 0);
  if (sock < 0)
    return;

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  const char *name = "winlator_vibration";
  memcpy(addr.sun_path + 1, name, strlen(name));
  socklen_t addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);

  if (connect(sock, (struct sockaddr *)&addr, addrlen) < 0) {
    syscall(SYS_close, sock);
    return;
  }

  uint16_t data[4];
  data[0] = (uint16_t)strong;
  data[1] = (uint16_t)weak;
  data[2] = duration_ms;
  data[3] = slot;
  send(sock, data, sizeof(data), 0);
  syscall(SYS_close, sock);
}

__attribute__((constructor))
static void library_init() {
	if (!hook_dir)
		hook_dir = getenv("FAKE_EVDEV_DIR") ? getenv("FAKE_EVDEV_DIR") : "/data/data/com.termux/files/home/fake-input";

	vibration_enabled = getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));
	Logger::init();
    GUEST_LOGI("loaded build=%s FAKE_EVDEV_DIR=%s vibration=%d",
               FAKEINPUT_DIAG_BUILD,
               hook_dir ? hook_dir : "(null)",
               vibration_enabled ? 1 : 0);
}
  
__attribute__((visibility("hidden"))) 
char *from_real_to_fake_path(const char *pathname) {
	const char *event = strrchr(pathname, '/') + 1;
	char *fake_path;
	asprintf(&fake_path, "%s/%s", hook_dir, event);
	return fake_path;
}

__attribute__((visibility("hidden")))
const char *get_event(const char *pathname) {
	const char *event = strrchr(pathname, '/') + 1;
	return event;
}

__attribute__((visibility("hidden")))
int get_event_number(const char *event) {
    int event_number = atoi(event + strlen(event) - 1);
    return event_number;
}

EXPORT int open(const char *pathname, int flags, ...) {
    va_list va;
    mode_t mode;
    int fd;
    bool hasMode;
    bool isFromInput;

    va_start(va, flags);

    hasMode = flags & O_CREAT;
    isFromInput = false;
    
    if (hasMode) {
        mode = va_arg(va, mode_t);
    }
    
    va_end(va);

	if (!my_open)
    	*(void **)&my_open = dlsym(RTLD_NEXT, "open");

	if (pathname) {
		if (strstr(pathname, "/dev/input/event")) {
		    pathname = from_real_to_fake_path(pathname);
		    isFromInput = true;
		}
		else if (!strcmp(pathname, "/dev/input")) {
			pathname = hook_dir;
		}
	}
	    
	if (hasMode)
	    fd = my_open(pathname, flags, mode);
	else
	    fd = my_open(pathname, flags);

    if (isFromInput && fd >= 0) {
        const char* event_name = get_event(pathname);
        const int slot = get_event_number(event_name);
        syscall(SYS_close, fd);
        fd = fakeinput_ring_open(slot, flags);
        if (fd >= 0) controller_map[fd] = strdup(event_name);
    }
	    
	return fd;
}

EXPORT int openat(int dirfd, const char *pathname, int flags, ...) {
    va_list va;
    mode_t mode;
    int fd;
    bool hasMode;
    bool isFromInput;
    
    va_start(va, flags);

    isFromInput = false;
    hasMode = flags & O_CREAT;
    
    if (hasMode) {
        mode = va_arg(va, mode_t);
    }
    
    va_end(va);

    if (!my_openat)
    	*(void **)&my_openat = dlsym(RTLD_NEXT, "openat");
    
    if (pathname) {
        if (strstr(pathname, "/dev/input/event")) {
            pathname = from_real_to_fake_path(pathname);
            isFromInput = true;
        }
        else if (!strcmp(pathname, "/dev/input")) {                                    
            pathname = hook_dir;             
        }
    }
    
    if (hasMode)
        fd = my_openat(dirfd, pathname, flags, mode);
    else
        fd = my_openat(dirfd, pathname, flags);

    if (isFromInput && fd >= 0) {
        const char* event_name = get_event(pathname);
        const int slot = get_event_number(event_name);
        syscall(SYS_close, fd);
        fd = fakeinput_ring_open(slot, flags);
        if (fd >= 0) controller_map[fd] = strdup(event_name);
    }

    return fd;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
	if (!my_stat)
		*(void **)&my_stat = dlsym(RTLD_NEXT, "stat");

     const char *event = nullptr;
     int event_number = -1;

	if (pathname) {
		if (strstr(pathname, "/dev/input/event")) {
		    pathname = from_real_to_fake_path(pathname);
		    event = get_event(pathname);
		    event_number = get_event_number(event);
		}
		else if (!strcmp(pathname, "/dev/input")) {                                    
		    pathname = hook_dir;             
		}
	}

	int ret = my_stat(pathname, statbuf);
    
    if (event && event_number >= 0) {
		statbuf->st_rdev = makedev(1, event_number);
	}

	return ret;
}

EXPORT int fstat(int fd, struct stat *buf) {
	if (!my_fstat)
    	*(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");

    int ret = my_fstat(fd, buf);

    auto controller = controller_map.find(fd);
    if (controller != controller_map.end()) {
    	buf->st_rdev = makedev(1, get_event_number(controller->second));
    }

    return ret;
  }

EXPORT int scandir(const char *dirp, struct dirent ***namelist, int(*filter)(const struct dirent *), int(*compar)(const struct dirent **, const struct dirent **)) {
	if (!my_scandir)
		*(void **)&my_scandir = dlsym(RTLD_NEXT, "scandir");
	
	if (dirp) {
	    if (!strcmp(dirp, "/dev/input")) {
	        dirp = hook_dir;
	    }
    }

	return my_scandir(dirp, namelist, filter, compar);
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
	if (!my_inotify_add_watch)
		*(void **)&my_inotify_add_watch = dlsym(RTLD_NEXT, "inotify_add_watch");

    if (pathname) {
        if (strstr(pathname, "/dev/input/event")) {
            pathname = from_real_to_fake_path(pathname);
        }
        else if (!strcmp(pathname, "/dev/input")) {
            pathname = hook_dir;
    	}
    }

    return my_inotify_add_watch(fd, pathname, mask);
}

EXPORT int ioctl(int fd, int op, ...) {
	va_list va;
	void *argp;
	
	va_start(va, op);
	argp = va_arg(va, void *);
	va_end(va);

	auto controller = controller_map.find(fd);
	if (controller == controller_map.end()) {
		return syscall(SYS_ioctl, fd, op, argp);
	}

	int type = (op >> 8 & 0xFF);
	int number = (op >> 0 & 0xFF);
	const char *event = controller->second;
	int event_number = get_event_number(event);

    if (type == 0x45 && number == 0x1) {
        Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
        int version = 65536;
        memcpy(argp, (void *)&version, sizeof(int));
        return 0;
    }
    else if (type == 0x45 && number == 0x2) {
        Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
        struct input_id id;
        memset(&id, 0, sizeof(id));
        id.bustype = 0x03;
        id.vendor = 0x1234 + event_number;
        id.product = 0x5678 + event_number;
        id.version = 0x0110;
        memcpy(argp, (void *)&id, sizeof(id));
        return 0;
    }
    else if (type == 0x45 && number == 0x6) {
    	Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    	char *name;
    	
    	asprintf(&name, "Generic HID Gamepad %d", event_number);
    	
    	strcpy((char *)argp, name);
    	return 0;
    }
    else if (type == 0x45 && number == 0x9) {
        Logger::log("Hooking ioctl EVIOCGPROP for event %s\n", event);
        return 0;
    }
    else if (type == 0x45 && number == 0x18) {
    	Logger::log("Hooking ioctl EVIOCGKEY(len) for event %s\n", event);
    	char bitmask[KEY_MAX / 8] = {0};
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x20) {
    	Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
        char bitmask[EV_MAX / 8] = {0};
        bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
        bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
        bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;	
    }
    else if (type == 0x45 && number == 0x21) {
        Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
        char bitmask[KEY_MAX / 8] = {0};
        for (int i = 0x130; i <= 0x13e; i++) {
            if (i == 0x130)
                bitmask[BTN_A / 8] |= (1 << (BTN_A % 8));
            else if (i == 0x131)
                bitmask[BTN_B / 8] |= (1 << (BTN_B % 8));
            else if (i == 0x132)
                continue;
            else if (i == 0x133)
                bitmask[BTN_X / 8] |= (1 << (BTN_X % 8));
            else if (i == 0x134)
                bitmask[BTN_Y / 8] |= (1 << (BTN_Y % 8));
            else if (i == 0x135)
                continue;
            else
                bitmask[i / 8] |= (1 << (i % 8));
        }
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x22) {
    	Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    	char bitmask[REL_MAX / 8] = {0};
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;
    }
    else if (type == 0x45 && number == 0x23) {
    	Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    	char bitmask[ABS_MAX / 8] = {0};
    	bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    	bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    	bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    	bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    	bitmask[ABS_GAS / 8] |= (1 << (ABS_GAS % 8));
    	bitmask[ABS_BRAKE / 8] |= (1 << (ABS_BRAKE % 8));
    	bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    	bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    	memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    	return 0;
    }
    else if (type == 0x45 && number == 0x35) {
        Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
        char bitmask[FF_MAX / 8] = {0};
        bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
        bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
        memcpy(argp, (void *)&bitmask, sizeof(bitmask));
        return 0;
    }
    else if (type == 0x45 && number == 0x80) {
        struct ff_effect *effect = (struct ff_effect *)argp;
        if (effect->id == -1) {
            effect->id = next_ff_id++;
        }
        ff_effects[effect->id] = *effect;

        uint16_t duration = effect->replay.length;
        if (effect->type == FF_RUMBLE) {
            send_vibration(effect->u.rumble.strong_magnitude, effect->u.rumble.weak_magnitude, duration, (uint16_t)event_number);
        } else if (effect->type == FF_PERIODIC) {
            send_vibration(effect->u.periodic.magnitude, effect->u.periodic.magnitude, duration, (uint16_t)event_number);
        }
        return 0;
    }
    else if (type == 0x45 && number == 0x81) {
        int id = (intptr_t)argp;
        ff_effects.erase(id);
        return 0;
    }
    else if (type == 0x45 && number == 0x84) {
        int max_effects = 16;
        memcpy(argp, &max_effects, sizeof(int));
        return 0;
    }
    else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    	Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    	struct input_absinfo abs_info;
    	memset(&abs_info, 0, sizeof(abs_info));
    	if (number >= 0x40 && number <= 0x44) {
    		abs_info.value = 0;
    		abs_info.minimum = -32768;
    		abs_info.maximum = 32767;
    	}
    	else if (number >= 0x49 && number <= 0x4A) {
    		abs_info.value = 0;
    		abs_info.minimum = 0;
    		abs_info.maximum = 255;
    	}
    	else if (number >= 0x50 && number <= 0x51) {
    		abs_info.value = 0;
    		abs_info.minimum = -1;
    		abs_info.maximum = 1;
    	}
    	memcpy(argp, (void *)&abs_info, sizeof(abs_info));
    	return 0;
    }
    else if (type == 0x45 && number == 0x90) {
    	Logger::log("Hooking ioctl EVIOCGRAB for event %s\n", event);
    	/* Always pretend this succeeds */
    	return 0;
    }
    else if (type == 0x6A && number == 0x13) {
    	Logger::log("Hooking ioctl JSIOCGNAME(len) for event %s\n", event);
    	char *name;
        asprintf(&name, "Generic HID Gamepad %d", event_number);
    	strcpy((char *)argp, name);
    	return 0;
    }
    else {
    	Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    	return syscall(SYS_ioctl, fd, op, argp);
    }
}

EXPORT int close(int fd) {
	if (!my_close)
		*(void **)&my_close = dlsym(RTLD_NEXT, "close");

    fakeinput_ring_unregister_fd(fd);

	auto controller = controller_map.find(fd);
	if (controller != controller_map.end()) {
	    Logger::log("Removing controller, fd %d event %s\n", controller->first, controller->second);
	    free((void *)controller->second);
		controller_map.erase(fd);
	}

	return my_close(fd);
}

static void clone_controller_fd(int source, int target) {
    auto controller = controller_map.find(source);
    if (controller == controller_map.end()) return;
    if (fakeinput_ring_clone_fd(source, target))
        controller_map[target] = strdup(controller->second);
}

EXPORT int dup(int oldfd) {
    const int fd = static_cast<int>(syscall(SYS_dup, oldfd));
    if (fd >= 0) clone_controller_fd(oldfd, fd);
    return fd;
}

EXPORT int dup2(int oldfd, int newfd) {
    if (oldfd == newfd)
        return fcntl(oldfd, F_GETFD) < 0 ? -1 : oldfd;
    const int fd = static_cast<int>(syscall(SYS_dup3, oldfd, newfd, 0));
    if (fd >= 0 && oldfd != newfd) {
        fakeinput_ring_unregister_fd(newfd);
        auto previous = controller_map.find(newfd);
        if (previous != controller_map.end()) {
            free((void*)previous->second);
            controller_map.erase(previous);
        }
        clone_controller_fd(oldfd, newfd);
    }
    return fd;
}

EXPORT int dup3(int oldfd, int newfd, int flags) {
    const int fd = static_cast<int>(syscall(SYS_dup3, oldfd, newfd, flags));
    if (fd >= 0) {
        fakeinput_ring_unregister_fd(newfd);
        auto previous = controller_map.find(newfd);
        if (previous != controller_map.end()) {
            free((void*)previous->second);
            controller_map.erase(previous);
        }
        clone_controller_fd(oldfd, newfd);
    }
    return fd;
}

EXPORT int fcntl(int fd, int cmd, ...) {
    bool has_arg = cmd != F_GETFD && cmd != F_GETFL && cmd != F_GETOWN;
#ifdef F_GETSIG
    has_arg = has_arg && cmd != F_GETSIG;
#endif
#ifdef F_GETLEASE
    has_arg = has_arg && cmd != F_GETLEASE;
#endif
#ifdef F_GETPIPE_SZ
    has_arg = has_arg && cmd != F_GETPIPE_SZ;
#endif
#ifdef F_GET_SEALS
    has_arg = has_arg && cmd != F_GET_SEALS;
#endif
    long arg = 0;
    if (has_arg) {
        va_list ap;
        va_start(ap, cmd);
        arg = va_arg(ap, long);
        va_end(ap);
    }
    const int result = static_cast<int>(syscall(SYS_fcntl, fd, cmd, arg));
    if (result >= 0 && (cmd == F_DUPFD || cmd == F_DUPFD_CLOEXEC))
        clone_controller_fd(fd, result);
    return result;
}

EXPORT ssize_t read(int fd, void *buf, size_t count) {
    if (fakeinput_ring_has_fd(fd))
        return fakeinput_ring_read(fd, buf, count);

    auto controller = controller_map.find(fd);
    
    if (controller != controller_map.end()) {
        ssize_t bytes_read = 0;
        int flags = fcntl(fd, F_GETFL);
        bool isNonBlock = flags & O_NONBLOCK;
        bytes_read = syscall(SYS_read, fd, buf, count);
        while(bytes_read == 0) {
            struct stat statbuf;
            if (!my_fstat) *(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");
            if (my_fstat && my_fstat(fd, &statbuf) == 0 && statbuf.st_nlink == 0) {
                errno = ENODEV;
                return -1;
            }
            if (isNonBlock) {
                break;
            }
            setup_signal_handler();
            if (stop_flag) {
            	bytes_read = -1;
            	errno = EINTR;
            	return bytes_read;
            }
            struct pollfd pfd;
            pfd.fd = fd;
            pfd.events = POLLIN;
            if (poll(&pfd, 1, 1000) > 0) {
                // If it's a disconnected pipe, poll returns immediately with POLLHUP.
                // We fallback to usleep to prevent a 100% CPU busy-spin loop.
                if (pfd.revents & POLLHUP) {
                    usleep(1000); 
                }
            }
            bytes_read = syscall(SYS_read, fd, buf, count);
        }
        
    	return bytes_read;
    }
    return syscall(SYS_read, fd, buf, count);
}

static void check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type == EV_FF) {
    int id = ev->code;
    int value = ev->value;
    if (value > 0) {
      auto it = ff_effects.find(id);
      if (it != ff_effects.end()) {
        uint16_t duration = it->second.replay.length;
        if (it->second.type == FF_RUMBLE) {
          send_vibration(it->second.u.rumble.strong_magnitude,
                         it->second.u.rumble.weak_magnitude, duration, slot);
        } else if (it->second.type == FF_PERIODIC) {
          send_vibration(it->second.u.periodic.magnitude,
                         it->second.u.periodic.magnitude, duration, slot);
        }
      }
    } else {
      send_vibration(0, 0, 0, slot);
    }
  }
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  if (!my_write)
    *(void **)&my_write = dlsym(RTLD_NEXT, "write");

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (count == sizeof(struct input_event)) {
      const struct input_event *ev = (const struct input_event *)buf;
      uint16_t slot = (uint16_t)get_event_number(controller->second);
      check_ff_event(ev, slot);
    }
    // The public FD is an eventfd. Guest writes are output commands, never
    // input events; only our producer is allowed to signal its counter.
    return (ssize_t)count;
  }
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    uint16_t slot = (uint16_t)get_event_number(controller->second);
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      total += (ssize_t)iov[i].iov_len;
      if (iov[i].iov_len == sizeof(struct input_event)) {
        const struct input_event *ev = (const struct input_event *)iov[i].iov_base;
        check_ff_event(ev, slot);
      }
    }
    return total;
  }
  return syscall(SYS_writev, fd, iov, iovcnt);
}
