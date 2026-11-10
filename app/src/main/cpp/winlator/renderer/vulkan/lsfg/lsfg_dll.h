#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum LsfgStatus {
    LSFG_OK = 0,
    LSFG_NOT_INSTALLED = 1,
    LSFG_UNREADABLE_FILE = 2,
    LSFG_NOT_PORTABLE_EXECUTABLE = 3,
    LSFG_MISSING_SHADERS = 4,
    LSFG_INVALID_SHADERS = 5,
    LSFG_CACHE_UNUSABLE = 6
} LsfgStatus;

typedef enum LsfgVariant {
    LSFG_VARIANT_NONE = 0,
    LSFG_VARIANT_FP16 = 1,
    LSFG_VARIANT_FP32 = 2
} LsfgVariant;

#define LSFG_SHADER_MIPMAPS     255u
#define LSFG_SHADER_GENERATE    256u
#define LSFG_SHADER_PERF_FIRST  280u
#define LSFG_SHADER_PERF_LAST   302u
#define LSFG_SHADER_COUNT       25u

typedef struct LsfgModule {
    uint32_t  id;
    uint32_t* words;
    uint32_t  word_count;
} LsfgModule;

typedef struct LsfgModuleSet {
    LsfgModule  modules[LSFG_SHADER_COUNT];
    uint32_t    count;
    LsfgVariant variant;
} LsfgModuleSet;

LsfgStatus lsfg_build_cache(const char* dll_path, const char* cache_path, bool prefer_fp16);

LsfgStatus lsfg_load_modules(const char* cache_path, LsfgModuleSet* out_set);

void lsfg_release_modules(LsfgModuleSet* set);

const uint32_t* lsfg_find_module(const LsfgModuleSet* set, uint32_t id, uint32_t* out_word_count);

#ifdef __cplusplus
}
#endif
