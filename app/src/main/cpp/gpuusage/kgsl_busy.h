// SPDX-License-Identifier: GPL-3.0-only
#ifndef LUDASHI_KGSL_BUSY_H
#define LUDASHI_KGSL_BUSY_H
// kgsl lets any client map its memstore read-only (KGSL_PROP_DEVICE_SHADOW). The CP writes a ringbuffer's
// start-of-pipe timestamp there before each submission's command buffers and its end-of-pipe timestamp after them
// (adreno_gen7_ringbuffer.c gen7_ringbuffer_addcmds), so start != end means that ringbuffer has work on the GPU.
// Ringbuffer entries start at KGSL_MEMSTORE_MAX: entries - 2 - 4 levels, or entries - 1 - 4 on kernels without the
// LPAC slot. Entries - 6 .. entries - 2 cover both; a context entry in that range follows the same start/end rule.
#include <stddef.h>
#include <stdint.h>

#define KGSL_BUSY_ENTRY_WORDS 10
#define KGSL_BUSY_SOP 0
#define KGSL_BUSY_EOP 2
#define KGSL_BUSY_SLOTS 5

static inline int kgsl_busy_first_slot(size_t bytes) {
    size_t entries = bytes / (KGSL_BUSY_ENTRY_WORDS * sizeof(uint32_t));
    return entries < 8 ? -1 : (int)(entries - 6);
}

// 1 when a watched slot has a submission between its start and end timestamps. *moved is set when a start
// timestamp differs from last[], which then holds the new ones.
static inline int kgsl_busy_sample(const volatile uint32_t *memstore, int first, uint32_t last[KGSL_BUSY_SLOTS], int *moved) {
    int busy = 0;
    for (int i = 0; i < KGSL_BUSY_SLOTS; i++) {
        const volatile uint32_t *entry = memstore + (size_t)(first + i) * KGSL_BUSY_ENTRY_WORDS;
        uint32_t sop = entry[KGSL_BUSY_SOP], eop = entry[KGSL_BUSY_EOP];
        if (sop != eop) busy = 1;
        if (sop != last[i]) {
            *moved = 1;
            last[i] = sop;
        }
    }
    return busy;
}

// Hardware-scheduled kernels (every gen8 GPU and some gen7 ones: .gpudev = *_hwsched_gpudev in adreno-gpulist.h)
// leave the ringbuffer entries at 0. The GMU firmware writes each context's own entry instead
// (gen8_hwsched_hfi.c send_context_pointers). Context ids run from 1 below the ringbuffer entries, lowest free first,
// and kgsl zeroes an entry when its context is created or destroyed.
#define KGSL_BUSY_MAX_LIVE 64

// Collects the contexts to sample: every entry below first with a non-zero start stamp (kgsl zeroes an entry when its
// context is created or destroyed). Entries whose start stamp changed since the previous scan (seen[]) or that are
// busy now come first, so the max_live cap only drops idle ones. Returns how many ids went into live[].
static inline int kgsl_busy_scan_contexts(const volatile uint32_t *memstore, int first, uint32_t *seen, int *live, int max_live) {
    int n = 0, idle = 0;
    int idle_ids[KGSL_BUSY_MAX_LIVE];
    for (int id = 1; id < first; id++) {
        const volatile uint32_t *entry = memstore + (size_t)id * KGSL_BUSY_ENTRY_WORDS;
        uint32_t sop = entry[KGSL_BUSY_SOP], eop = entry[KGSL_BUSY_EOP];
        int changed = sop != seen[id];
        seen[id] = sop;
        if ((changed || sop != eop) && n < max_live) live[n++] = id;
        else if (sop != 0 && idle < KGSL_BUSY_MAX_LIVE) idle_ids[idle++] = id;
    }
    for (int i = 0; i < idle && n < max_live; i++) live[n++] = idle_ids[i];
    return n;
}

// 1 when a live context has a submission between its start and end timestamps. *moved is set when a start
// timestamp differs from last[] (indexed by context id), which then holds the new ones.
static inline int kgsl_busy_sample_contexts(const volatile uint32_t *memstore, const int *live, int count, uint32_t *last, int *moved) {
    int busy = 0;
    for (int i = 0; i < count; i++) {
        const volatile uint32_t *entry = memstore + (size_t)live[i] * KGSL_BUSY_ENTRY_WORDS;
        uint32_t sop = entry[KGSL_BUSY_SOP], eop = entry[KGSL_BUSY_EOP];
        if (sop != eop) busy = 1;
        if (sop != last[live[i]]) {
            *moved = 1;
            last[live[i]] = sop;
        }
    }
    return busy;
}

#endif

