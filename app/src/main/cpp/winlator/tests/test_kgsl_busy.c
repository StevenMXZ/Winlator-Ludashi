#define _GNU_SOURCE
#define KGSL_BUSY_NO_JNI
#define __user
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include "../../gpuusage/kgsl_busy.c"

int main(void) {
    assert(kgsl_busy_first_slot(0) == -1);
    assert(kgsl_busy_first_slot(7 * 40) == -1);
    assert(kgsl_busy_first_slot(16 * 40) == 10);
    uint32_t memstore[16 * KGSL_BUSY_ENTRY_WORDS] = {0};
    uint32_t last[KGSL_BUSY_SLOTS] = {0};
    int moved = 0;
    assert(!kgsl_busy_sample(memstore, 10, last, &moved));
    assert(!moved);
    memstore[10 * KGSL_BUSY_ENTRY_WORDS] = 11;
    moved = 0;
    assert(kgsl_busy_sample(memstore, 10, last, &moved));
    assert(moved);
    memstore[10 * KGSL_BUSY_ENTRY_WORDS + KGSL_BUSY_EOP] = 11;
    moved = 0;
    assert(!kgsl_busy_sample(memstore, 10, last, &moved));
    assert(!moved);

    uint32_t seen[10] = {0}, ctx_last[10] = {0};
    int live[KGSL_BUSY_MAX_LIVE];
    memstore[2 * KGSL_BUSY_ENTRY_WORDS] = 20;
    int count = kgsl_busy_scan_contexts(memstore, 10, seen, live, KGSL_BUSY_MAX_LIVE);
    assert(count == 1 && live[0] == 2);
    moved = 0;
    assert(kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved));
    assert(moved);
    memstore[2 * KGSL_BUSY_ENTRY_WORDS + KGSL_BUSY_EOP] = 20;
    moved = 0;
    assert(!kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved));
    assert(!moved);
    memstore[2 * KGSL_BUSY_ENTRY_WORDS] = 0;
    memstore[2 * KGSL_BUSY_ENTRY_WORDS + KGSL_BUSY_EOP] = 0;
    kgsl_busy_scan_contexts(memstore, 10, seen, live, KGSL_BUSY_MAX_LIVE);
    count = kgsl_busy_scan_contexts(memstore, 10, seen, live, KGSL_BUSY_MAX_LIVE);
    assert(count == 0);

    // More live contexts than the cap: prioritize newly active/busy entries safely.
    uint32_t *many = calloc(256 * KGSL_BUSY_ENTRY_WORDS, sizeof(uint32_t));
    uint32_t many_seen[250] = {0};
    assert(many);
    for (int i = 1; i < 250; ++i) many[i * KGSL_BUSY_ENTRY_WORDS] = 1;
    count = kgsl_busy_scan_contexts(many, 250, many_seen, live, KGSL_BUSY_MAX_LIVE);
    assert(count == KGSL_BUSY_MAX_LIVE);
    for (int i = 0; i < count; ++i) assert(live[i] > 0 && live[i] < 250);
    free(many);

    // Exercise the actual polling thread and stop/join, without opening a GPU.
    struct reader *reader = calloc(1, sizeof(*reader));
    assert(reader);
    reader->fd = -1;
    reader->map = memstore;
    reader->first = 10;
    reader->mode = MODE_RINGBUFFER;
    pthread_mutex_init(&reader->lock, NULL);
    atomic_init(&reader->run, 0);
    atomic_init(&reader->samples, 0);
    atomic_init(&reader->busy, 0);
    atomic_init(&reader->moves, 0);
    memstore[10 * KGSL_BUSY_ENTRY_WORDS] = 12;
    reader_run(reader, 1);
    const struct timespec gap = {0, 10000000};
    for (int i = 0; i < 20 && atomic_load(&reader->samples) == 0; ++i) nanosleep(&gap, NULL);
    reader_run(reader, 0);
    uint64_t samples = atomic_load(&reader->samples);
    assert(samples > 0 && atomic_load(&reader->busy) == samples);
    nanosleep(&gap, NULL);
    assert(atomic_load(&reader->samples) == samples);
    reader_run(reader, 0); // Closing an already stopped reader is safe.
    reader->map = NULL; // The simulated map belongs to this test's stack.
    reader_close(reader);
    return 0;
}
