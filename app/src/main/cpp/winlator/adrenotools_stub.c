// ARMv7 compatibility stubs for the AArch64-only AdrenoTools integration.
// ARM32 uses Android's system Vulkan loader instead of custom Adreno ICD hooks.
#include <adrenotools/driver.h>

void *adrenotools_open_libvulkan(
    int dlopenMode, int featureFlags,
    const char *tmpLibDir, const char *hookLibDir,
    const char *customDriverDir, const char *customDriverName,
    const char *fileRedirectDir, void **userMappingHandle) {
    (void)dlopenMode;
    (void)featureFlags;
    (void)tmpLibDir;
    (void)hookLibDir;
    (void)customDriverDir;
    (void)customDriverName;
    (void)fileRedirectDir;
    (void)userMappingHandle;
    return 0;
}

bool adrenotools_import_user_mem(void *handle, void *hostPtr, uint64_t size) {
    (void)handle;
    (void)hostPtr;
    (void)size;
    return false;
}

bool adrenotools_mem_gpu_allocate(void *handle, uint64_t *size) {
    (void)handle;
    (void)size;
    return false;
}

bool adrenotools_mem_cpu_map(void *handle, void *hostPtr, uint64_t size) {
    (void)handle;
    (void)hostPtr;
    (void)size;
    return false;
}

bool adrenotools_validate_gpu_mapping(void *handle) {
    (void)handle;
    return false;
}

void adrenotools_set_turbo(bool turbo) {
    (void)turbo;
}
