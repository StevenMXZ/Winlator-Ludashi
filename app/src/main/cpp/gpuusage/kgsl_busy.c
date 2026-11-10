// SPDX-License-Identifier: GPL-3.0-only
// Adapted from The412Banner/Ludashi 0ab047311ccc55b2911d984727d2d57ac6c0ebf7.
// Ported from DroidDeck (github.com/Droid-Deck/DroidDeck, PRs #1969 and #1981).
// Whole-GPU busy share without root on Adreno (KGSL) devices whose kgsl sysfs is blocked for apps: a thread
// samples the memstore timestamps (kgsl_busy.h) once a millisecond while running. Ringbuffer entries are used where
// the kernel writes them; on hardware-scheduled kernels, which leave them at 0, the contexts' own entries are used.
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include <sys/resource.h>

#include "../adrenotools/src/hook/kgsl.h"
#include "kgsl_busy.h"

#define SAMPLE_NS 1000000L
// Context mode: how many samples between scans of the context entries.
#define SCAN_EVERY 200

enum { MODE_RINGBUFFER = 1, MODE_CONTEXTS = 2 };

struct reader {
    int fd;
    void *map;
    size_t bytes;
    int first;
    int mode;
    uint32_t last[KGSL_BUSY_SLOTS];
    uint32_t *seen, *ctx_last;
    int live[KGSL_BUSY_MAX_LIVE];
    int live_count;
    pthread_mutex_t lock;
    pthread_t thread;
    int thread_on;
    atomic_int run;
    atomic_uint_fast64_t busy, samples, moves;
};

static void *sample_loop(void *arg) {
    struct reader *r = arg;
    pthread_setname_np(pthread_self(), "hud-gpu-busy");
    setpriority(PRIO_PROCESS, 0, 10);
    const struct timespec gap = {0, SAMPLE_NS};
    int until_scan = 0;
    while (atomic_load(&r->run)) {
        int moved = 0, busy;
        if (r->mode == MODE_CONTEXTS) {
            if (until_scan-- <= 0) {
                r->live_count = kgsl_busy_scan_contexts(r->map, r->first, r->seen, r->live, KGSL_BUSY_MAX_LIVE);
                until_scan = SCAN_EVERY;
            }
            busy = kgsl_busy_sample_contexts(r->map, r->live, r->live_count, r->ctx_last, &moved);
        } else {
            busy = kgsl_busy_sample(r->map, r->first, r->last, &moved);
        }
        if (busy) atomic_fetch_add(&r->busy, 1);
        if (moved) atomic_fetch_add(&r->moves, 1);
        atomic_fetch_add(&r->samples, 1);
        nanosleep(&gap, NULL);
    }
    return NULL;
}

static void reader_run(struct reader *r, int on) {
    pthread_mutex_lock(&r->lock);
    if (on && !r->thread_on) {
        atomic_store(&r->run, 1);
        if (pthread_create(&r->thread, NULL, sample_loop, r) == 0) r->thread_on = 1;
        else atomic_store(&r->run, 0);
    } else if (!on && r->thread_on) {
        atomic_store(&r->run, 0);
        pthread_join(r->thread, NULL);
        r->thread_on = 0;
    }
    pthread_mutex_unlock(&r->lock);
}

static void reader_close(struct reader *r) {
    if (!r) return;
    reader_run(r, 0);
    if (r->map && r->map != MAP_FAILED) munmap(r->map, r->bytes);
    if (r->fd >= 0) close(r->fd);
    free(r->seen);
    free(r->ctx_last);
    pthread_mutex_destroy(&r->lock);
    free(r);
}

// Returns 0 or a negative errno; *out is set on success.
static int reader_open(struct reader **out) {
    struct reader *r = calloc(1, sizeof(*r));
    if (!r) return -ENOMEM;
    pthread_mutex_init(&r->lock, NULL);
    r->fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (r->fd < 0) { int e = -errno; reader_close(r); return e; }
    struct kgsl_shadowprop shadow;
    memset(&shadow, 0, sizeof(shadow));
    struct kgsl_device_getproperty prop = {.type = KGSL_PROP_DEVICE_SHADOW, .value = &shadow, .sizebytes = sizeof(shadow)};
    if (ioctl(r->fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &prop)) { int e = -errno; reader_close(r); return e; }
    r->bytes = shadow.size;
    r->first = kgsl_busy_first_slot(shadow.size);
    if (r->first < 0) { reader_close(r); return -ERANGE; }
    r->map = mmap(NULL, r->bytes, PROT_READ, MAP_SHARED, r->fd, (off_t)shadow.gpuaddr);
    if (r->map == MAP_FAILED) { int e = -errno; reader_close(r); return e; }
    int moved = 0, started = 0;
    kgsl_busy_sample(r->map, r->first, r->last, &moved);
    for (int i = 0; i < KGSL_BUSY_SLOTS; i++) started |= r->last[i] != 0;
    if (started) {
        r->mode = MODE_RINGBUFFER;
    } else {
        r->seen = calloc((size_t)r->first, sizeof(uint32_t));
        r->ctx_last = calloc((size_t)r->first, sizeof(uint32_t));
        if (!r->seen || !r->ctx_last) { reader_close(r); return -ENOMEM; }
        r->live_count = kgsl_busy_scan_contexts(r->map, r->first, r->seen, r->live, KGSL_BUSY_MAX_LIVE);
        kgsl_busy_sample_contexts(r->map, r->live, r->live_count, r->ctx_last, &moved);
        r->mode = MODE_CONTEXTS;
    }
    *out = r;
    return 0;
}

#ifndef KGSL_BUSY_NO_JNI
#include <jni.h>

JNIEXPORT jlong JNICALL Java_com_winlator_cmod_widget_gpu_KgslBusy_nativeOpen(JNIEnv *env, jclass cls, jintArray error) {
    struct reader *r = NULL;
    int e = reader_open(&r);
    if (e && error && (*env)->GetArrayLength(env, error) > 0) {
        jint v = e;
        (*env)->SetIntArrayRegion(env, error, 0, 1, &v);
    }
    return e ? 0 : (jlong)(intptr_t)r;
}

JNIEXPORT jint JNICALL Java_com_winlator_cmod_widget_gpu_KgslBusy_nativeMode(JNIEnv *env, jclass cls, jlong handle) {
    return handle ? ((struct reader *)(intptr_t)handle)->mode : 0;
}

JNIEXPORT void JNICALL Java_com_winlator_cmod_widget_gpu_KgslBusy_nativeRun(JNIEnv *env, jclass cls, jlong handle, jboolean on) {
    if (handle) reader_run((struct reader *)(intptr_t)handle, on ? 1 : 0);
}

JNIEXPORT jboolean JNICALL Java_com_winlator_cmod_widget_gpu_KgslBusy_nativeRead(JNIEnv *env, jclass cls, jlong handle, jlongArray out) {
    if (!handle || !out || (*env)->GetArrayLength(env, out) < 3) return JNI_FALSE;
    struct reader *r = (struct reader *)(intptr_t)handle;
    jlong v[3] = {(jlong)atomic_load(&r->busy), (jlong)atomic_load(&r->samples), (jlong)atomic_load(&r->moves)};
    (*env)->SetLongArrayRegion(env, out, 0, 3, v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_winlator_cmod_widget_gpu_KgslBusy_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    reader_close((struct reader *)(intptr_t)handle);
}
#endif

