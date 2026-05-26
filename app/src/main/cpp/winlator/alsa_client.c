/*
 * alsa_client.c — AAudio backend for Winlator's ALSA bridge.
 *
 * Changes vs upstream Ludashi 3.0:
 *   - AAUDIO_USAGE_GAME: tells the Android audio HAL this is a game stream,
 *     enabling the low-latency DSP mixing path on Qualcomm platforms (SM8550).
 *     Previously the stream had no usage hint and defaulted to MEDIA.
 *   - AAUDIO_CONTENT_TYPE_SONIFICATION: matches usage semantics expected by
 *     the HAL for real-time game audio effects.
 *   - Callback (pull) mode replaces blocking AAudioStream_write():
 *     Android calls our dataCallback from a dedicated real-time audio thread
 *     (SCHED_FIFO, elevated priority). The old write() path blocked for up to
 *     WAIT_COMPLETION_TIMEOUT (100 ms) on any render stall, causing glitches
 *     during shader compile spikes or GC pauses. In callback mode the RT
 *     thread always runs at the right time regardless of Wine's thread state.
 *   - A lock-free ring buffer (power-of-2 size) sits between Wine's write
 *     calls and the callback.  Wine writes into the ring; the callback drains
 *     it.  If the callback fires and the ring is empty (underrun) it outputs
 *     silence — a clean gap instead of a stall-induced glitch.
 *   - errorCallback reconnects the stream on disconnect (e.g. headphone plug).
 */

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>     /* dlopen/dlsym for API 28+ AAudio functions */
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "WinlatorAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Fallback timeout for state-change waits (100 ms). */
#define WAIT_NS (100 * 1000000L)

/* Ring buffer: holds up to RING_FRAMES frames.
 * Must be a power of 2 for the mask trick.
 * 8192 frames @ 48 kHz ≈ 170 ms — enough headroom for a shader-compile spike
 * without glitching, small enough not to add audible latency under normal load. */
#define RING_FRAMES 8192
#define RING_MASK   (RING_FRAMES - 1)

enum Format { U8, S16LE, S16BE, FLOATLE, FLOATBE };

/* Per-stream state kept alive between JNI calls. */
typedef struct {
    AAudioStream *stream;

    /* Ring buffer storage — allocated at stream-open time based on frame size. */
    void        *ring;
    int          frame_bytes;   /* bytes per frame (channels × sample size)   */
    int          ring_bytes;    /* RING_FRAMES × frame_bytes                   */

    /* Lock-free read/write cursors (frame indices, wrap naturally). */
    atomic_int   wr;            /* written by Wine's thread                    */
    atomic_int   rd;            /* read  by the RT audio callback              */
} StreamCtx;

/* ── Helpers ────────────────────────────────────────────────────────────── */

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE: case FLOATBE: return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:                    return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE: case S16BE:
        default:                    return AAUDIO_FORMAT_PCM_I16;
    }
}

static int frameSize(aaudio_format_t fmt, int channels) {
    int sample = (fmt == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
    return sample * channels;
}

/* ── AAudio callbacks ───────────────────────────────────────────────────── */

/*
 * dataCallback — called by the AAudio RT thread every ~few ms.
 * Drains the ring buffer into `audioData`; pads with silence on underrun.
 */
static aaudio_data_callback_result_t dataCallback(
        AAudioStream *stream, void *userData,
        void *audioData, int32_t numFrames)
{
    (void)stream;  /* unused — we already have ctx->stream via userData */
    StreamCtx *ctx = (StreamCtx *)userData;
    char      *dst = (char *)audioData;
    int        fb  = ctx->frame_bytes;

    int avail = atomic_load_explicit(&ctx->wr, memory_order_acquire)
              - atomic_load_explicit(&ctx->rd, memory_order_relaxed);
    if (avail < 0) avail = 0;
    if (avail > RING_FRAMES) avail = RING_FRAMES; /* sanity */

    int toCopy  = (avail < numFrames) ? avail : numFrames;
    int toSilence = numFrames - toCopy;

    /* Copy available frames from the ring. */
    int rd = atomic_load_explicit(&ctx->rd, memory_order_relaxed) & RING_MASK;
    for (int i = 0; i < toCopy; i++) {
        memcpy(dst, (char *)ctx->ring + rd * fb, fb);
        dst += fb;
        rd = (rd + 1) & RING_MASK;
    }
    atomic_fetch_add_explicit(&ctx->rd, toCopy, memory_order_release);

    /* Pad the rest with silence (underrun). */
    if (toSilence > 0) {
        memset(dst, 0, toSilence * fb);
        if (toSilence > numFrames / 2) /* only warn on significant underrun */
            LOGW("dataCallback: underrun %d/%d frames", toSilence, numFrames);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/*
 * errorCallback — reconnect the stream on disconnect events
 * (Bluetooth switch, headphone plug/unplug).
 */
static void errorCallback(AAudioStream *stream, void *userData, aaudio_result_t error) {
    (void)userData;  /* unused — restart uses the stream handle directly */
    LOGW("errorCallback: error=%d, attempting stream restart", error);
    /* AAudioStream_requestStart is safe to call from any thread. */
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        aaudio_result_t res = AAudioStream_requestStart(stream);
        LOGI("errorCallback: restart result=%d", res);
    }
}

/* ── Stream lifecycle ───────────────────────────────────────────────────── */

static StreamCtx *aaudioCreate(int32_t format, int8_t channelCount,
                               int32_t sampleRate, int32_t bufferSize) {
    AAudioStreamBuilder *builder = NULL;
    AAudioStream        *stream  = NULL;

    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("aaudioCreate: createStreamBuilder failed: %d", result);
        return NULL;
    }

    aaudio_format_t fmt = toAAudioFormat(format);

    /* Allocate StreamCtx FIRST so we can pass it as userData to the builder
     * — avoids the open-close-reopen dance the previous version used. */
    StreamCtx *ctx = (StreamCtx *)calloc(1, sizeof(StreamCtx));
    if (!ctx) {
        AAudioStreamBuilder_delete(builder);
        LOGE("aaudioCreate: calloc StreamCtx failed");
        return NULL;
    }
    ctx->frame_bytes = frameSize(fmt, channelCount);
    ctx->ring_bytes  = RING_FRAMES * ctx->frame_bytes;
    ctx->ring        = calloc(1, ctx->ring_bytes);
    atomic_init(&ctx->wr, 0);
    atomic_init(&ctx->rd, 0);
    if (!ctx->ring) {
        AAudioStreamBuilder_delete(builder);
        free(ctx);
        LOGE("aaudioCreate: calloc ring failed");
        return NULL;
    }

    /*
     * AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: request the smallest buffer the
     * HAL will give us (FAST path on Qualcomm = ~5 ms round-trip). Available
     * since API 26, so no guard needed.
     */
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    /* setUsage / setContentType are API 28+. The NDK headers mark them
     * __INTRODUCED_IN(28), which makes them a hard compile error below the
     * project's minSdk=26 (yes, even when wrapped in __builtin_available — a
     * known NDK quirk). Load them dynamically via dlsym instead: present at
     * runtime on Android 9.0+, absent (and silently skipped) on 8.0/8.1.
     *
     * Resolved once, cached in static pointers — the dlsym calls happen on
     * the first stream open, never on the hot audio path. */
    typedef void (*pfn_setUsage)(AAudioStreamBuilder*, aaudio_usage_t);
    typedef void (*pfn_setContentType)(AAudioStreamBuilder*, aaudio_content_type_t);
    static pfn_setUsage       s_setUsage       = NULL;
    static pfn_setContentType s_setContentType = NULL;
    static int                s_aaudio_probed  = 0;
    if (!s_aaudio_probed) {
        s_aaudio_probed = 1;
        /* libaaudio.so is already loaded (we use core AAudio functions above);
         * RTLD_DEFAULT searches the loaded namespace without re-opening it. */
        s_setUsage       = (pfn_setUsage)      dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage");
        s_setContentType = (pfn_setContentType)dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setContentType");
        LOGI("aaudio API 28 features: setUsage=%s setContentType=%s",
             s_setUsage ? "yes" : "no", s_setContentType ? "yes" : "no");
    }
    if (s_setUsage)       s_setUsage(builder, AAUDIO_USAGE_GAME);
    if (s_setContentType) s_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);

    AAudioStreamBuilder_setFormat(builder, fmt);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);

    /* Callback mode: no blocking writes; audio delivered by RT thread.
     * userData is the StreamCtx we just allocated. */
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, ctx);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, ctx);

    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        free(ctx->ring);
        free(ctx);
        LOGE("aaudioCreate: openStream failed: %d", result);
        return NULL;
    }
    ctx->stream = stream;

    /* In callback mode the buffer-size hint is advisory; set it anyway. */
    AAudioStream_setBufferSizeInFrames(stream, bufferSize);

    LOGI("aaudioCreate: stream=%p fmt=%d ch=%d rate=%d frameBytes=%d ringBytes=%d",
         (void*)stream, fmt, channelCount, sampleRate, ctx->frame_bytes, ctx->ring_bytes);
    return ctx;
}

/*
 * aaudioWrite — called by Wine's audio thread.
 * Copies `numFrames` into the ring buffer; returns frames written.
 * Drops frames silently if the ring is full (back-pressure from the RT thread).
 */
static int aaudioWrite(StreamCtx *ctx, void *buffer, int numFrames) {
    int fb   = ctx->frame_bytes;
    int wr   = atomic_load_explicit(&ctx->wr, memory_order_relaxed);
    int rd   = atomic_load_explicit(&ctx->rd, memory_order_acquire);
    int free = RING_FRAMES - (wr - rd);

    if (free <= 0) {
        LOGW("aaudioWrite: ring full, dropping %d frames", numFrames);
        return 0;
    }

    int toCopy = (numFrames < free) ? numFrames : free;
    char *src  = (char *)buffer;
    int  wridx = wr & RING_MASK;

    for (int i = 0; i < toCopy; i++) {
        memcpy((char *)ctx->ring + wridx * fb, src, fb);
        src  += fb;
        wridx = (wridx + 1) & RING_MASK;
    }
    atomic_fetch_add_explicit(&ctx->wr, toCopy, memory_order_release);
    return toCopy;
}

static void aaudioStart(StreamCtx *ctx) {
    AAudioStream_requestStart(ctx->stream);
    AAudioStream_waitForStateChange(ctx->stream, AAUDIO_STREAM_STATE_STARTING,
                                    NULL, WAIT_NS);
}

static void aaudioStop(StreamCtx *ctx) {
    AAudioStream_requestStop(ctx->stream);
    AAudioStream_waitForStateChange(ctx->stream, AAUDIO_STREAM_STATE_STOPPING,
                                    NULL, WAIT_NS);
}

static void aaudioPause(StreamCtx *ctx) {
    AAudioStream_requestPause(ctx->stream);
    AAudioStream_waitForStateChange(ctx->stream, AAUDIO_STREAM_STATE_PAUSING,
                                    NULL, WAIT_NS);
}

static void aaudioFlush(StreamCtx *ctx) {
    /* Drain the ring buffer so stale audio isn't replayed after resume. */
    atomic_store_explicit(&ctx->rd, atomic_load_explicit(&ctx->wr,
                          memory_order_acquire), memory_order_release);

    AAudioStream_requestFlush(ctx->stream);
    AAudioStream_waitForStateChange(ctx->stream, AAUDIO_STREAM_STATE_FLUSHING,
                                    NULL, WAIT_NS);
}

static void aaudioClose(StreamCtx *ctx) {
    if (!ctx) return;
    AAudioStream_close(ctx->stream);
    free(ctx->ring);
    free(ctx);
}

/* ── JNI interface ──────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj,
        jint format, jbyte channelCount, jint sampleRate, jint bufferSize) {
    return (jlong)aaudioCreate(format, channelCount, sampleRate, bufferSize);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj,
        jlong streamPtr, jobject buffer, jint numFrames) {
    StreamCtx *ctx = (StreamCtx *)(uintptr_t)streamPtr;
    if (!ctx) return -1;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    return aaudioWrite(ctx, data, numFrames);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj,
        jlong streamPtr) {
    StreamCtx *ctx = (StreamCtx *)(uintptr_t)streamPtr;
    if (ctx) aaudioStart(ctx);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj,
        jlong streamPtr) {
    StreamCtx *ctx = (StreamCtx *)(uintptr_t)streamPtr;
    if (ctx) aaudioStop(ctx);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj,
        jlong streamPtr) {
    StreamCtx *ctx = (StreamCtx *)(uintptr_t)streamPtr;
    if (ctx) aaudioPause(ctx);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj,
        jlong streamPtr) {
    StreamCtx *ctx = (StreamCtx *)(uintptr_t)streamPtr;
    if (ctx) aaudioFlush(ctx);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj,
        jlong streamPtr) {
    aaudioClose((StreamCtx *)(uintptr_t)streamPtr);
}
