#include <vulkan/vulkan.h>

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <time.h>
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <fcntl.h>
#include <android/api-level.h>
#include "../adrenotools/include/adrenotools/driver.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

VkInstance instance;
VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;

// Diagnostics only: record the last concrete failure stage for Winlator Labs.
// Never change normal Vulkan initialization or game renderer decisions.
static char labs_last_failure[400] = {0};

static const char *labs_vk_error_name(VkResult code) {
    switch (code) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        default: return "Other Vulkan error";
    }
}



static char *get_native_library_dir(JNIEnv *env, jobject context) {
    char *native_libdir;

    if (context != NULL) {
        jclass class_ = (*env)->FindClass(env,"com/winlator/cmod/core/AppUtils");
        jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(env, class_, "getNativeLibDir",
                                                               "(Landroid/content/Context;)Ljava/lang/String;");
        jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(env, class_,
                                                                     getNativeLibraryDir,
                                                                     context);
        if (nativeLibDir)
            native_libdir = (char *)(*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    }

    return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context, const char *driver_name) {
    char *driver_path;
    char *absolute_path;

    jclass contextWrapperClass = (*env)->FindClass(env, "android/content/ContextWrapper");
    jmethodID  getFilesDir = (*env)->GetMethodID(env, contextWrapperClass, "getFilesDir", "()Ljava/io/File;");
    jobject  filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
    jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
    jmethodID getAbsolutePath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring absolutePath = (jstring)(*env)->CallObjectMethod(env,filesDirObj,
                                                             getAbsolutePath);

    if (absolutePath) {
        absolute_path = (char *)(*env)->GetStringUTFChars(env,absolutePath, NULL);
        asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path, driver_name);
        (*env)->ReleaseStringUTFChars(env,absolutePath, absolute_path);
    }

    return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context, const char *driver_name) {
    char *library_name;

    jclass adrenotoolsManager = (*env)->FindClass(env, "com/winlator/cmod/contents/AdrenotoolsManager");
    jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>", "(Landroid/content/Context;)V");
    jobject  adrenotoolsManagerObj = (*env)->NewObject(env, adrenotoolsManager, constructor, context);
    jmethodID getLibraryName = (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName","(Ljava/lang/String;)Ljava/lang/String;");
    jstring driverName = (*env)->NewStringUTF(env, driver_name);
    jstring libraryName = (jstring)(*env)->CallObjectMethod(env, adrenotoolsManagerObj,getLibraryName, driverName);

    if (libraryName)
        library_name = (char *)(*env)->GetStringUTFChars(env, libraryName, NULL);

    return library_name;
}

static void preload_first_existing(const char **candidates) {
    for (int i = 0; candidates[i]; i++) {
        if (dlopen(candidates[i], RTLD_GLOBAL | RTLD_NOW))
            return;
    }
}

static void preload_vendor_icd_deps() {
    const char *jpeg_candidates[] = {
        "/system/lib64/libjpeg.so",
        "/system_ext/lib64/libjpeg.so",
        "libjpeg.so",
        NULL,
    };
    preload_first_existing(jpeg_candidates);

    const char *crypto_candidates[] = {
        "libcrypto.so",
        NULL,
    };
    preload_first_existing(crypto_candidates);
}

static void init_original_vulkan() {
    vulkan_handle = dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
}

static void init_vulkan(JNIEnv  *env, jobject context, const char *driver_name) {
    char *tmpdir = NULL;
    char *library_name = NULL;
    char *native_library_dir = NULL;
    char *driver_path = get_driver_path(env, context, driver_name);

    // Never pass uninitialized paths to AdrenoTools for incomplete imports.
    if (!driver_path || access(driver_path, F_OK) != 0) {
        free(driver_path);
        vulkan_handle = NULL;
        return;
    }

    library_name = get_library_name(env, context, driver_name);
    native_library_dir = get_native_library_dir(env, context);
    if (!library_name || !library_name[0] || !native_library_dir ||
        asprintf(&tmpdir, "%stemp", driver_path) < 0) {
        free(driver_path);
        free(tmpdir);
        vulkan_handle = NULL;
        return;
    }

    preload_vendor_icd_deps();
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    vulkan_handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM,
        tmpdir, native_library_dir, driver_path, library_name, NULL, NULL);
    free(tmpdir);
    free(driver_path);
}

static VkResult create_instance_impl(jstring driverName, JNIEnv *env, jobject context, int caps_probe) {
    VkResult result;
    VkInstanceCreateInfo create_info = {};
    char *driver_name = NULL;
    if (caps_probe) labs_last_failure[0] = 0;

    if (driverName != NULL)
        driver_name = (char *)(*env)->GetStringUTFChars(env, driverName, NULL);

    if (caps_probe && driver_name && strcmp(driver_name, "System")) {
        // Give a concrete missing-package reason before invoking AdrenoTools.
        char *driver_path = get_driver_path(env, context, driver_name);
        char manifest_path[1024];
        if (!driver_path || access(driver_path, F_OK) != 0) {
            snprintf(labs_last_failure, sizeof(labs_last_failure),
                "Driver directory not found in installed AdrenoTools packages.");
        } else {
            snprintf(manifest_path, sizeof(manifest_path), "%smeta.json", driver_path);
            if (access(manifest_path, R_OK) != 0)
                snprintf(labs_last_failure, sizeof(labs_last_failure),
                    "AdrenoTools metadata file (meta.json) is missing or unreadable.");
        }
        free(driver_path);
        if (labs_last_failure[0]) return VK_ERROR_INITIALIZATION_FAILED;
    }

    if (driver_name && strcmp(driver_name, "System"))
        init_vulkan(env, context, driver_name);
    else
        init_original_vulkan();

    if (!vulkan_handle) {
        if (caps_probe) {
            const char *reason = dlerror();
            snprintf(labs_last_failure, sizeof(labs_last_failure),
                "%s driver library could not be loaded: %s. Check AdrenoTools metadata, "
                "library architecture, required dependencies and GPU compatibility.",
                (driver_name && strcmp(driver_name, "System")) ? "Custom" : "System",
                reason ? reason : "loader returned no handle");
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    PFN_vkGetInstanceProcAddr gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = (PFN_vkEnumerateInstanceVersion)dlsym(vulkan_handle, "vkEnumerateInstanceVersion");

    if (!gip || !createInstance || !enumerateInstanceVersion) {
        if (caps_probe) snprintf(labs_last_failure, sizeof(labs_last_failure),
            "Driver loaded but required Vulkan exports are missing "
            "(vkGetInstanceProcAddr, vkCreateInstance or vkEnumerateInstanceVersion).");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    int apiLevel = android_get_device_api_level();

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Winlator";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "Winlator";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    if (apiLevel > 32)
        app_info.apiVersion = VK_API_VERSION_1_0;
    else
        enumerateInstanceVersion(&app_info.apiVersion);

    // The read-only Labs probe may request features2 without changing normal driver setup.
    if (caps_probe && enumerateInstanceVersion) {
        uint32_t instance_version = VK_API_VERSION_1_0;
        if (enumerateInstanceVersion(&instance_version) == VK_SUCCESS &&
            instance_version >= VK_API_VERSION_1_1)
            app_info.apiVersion = VK_API_VERSION_1_1;
    }

    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pNext = NULL;
    create_info.flags = 0;
    create_info.pApplicationInfo = &app_info;
    create_info.enabledLayerCount = 0;
    create_info.enabledExtensionCount = 0;

    result = createInstance(&create_info, NULL, &instance);

    if (result != VK_SUCCESS) {
        if (caps_probe) snprintf(labs_last_failure, sizeof(labs_last_failure),
            "vkCreateInstance failed: %s (%d). The selected driver may not support "
            "the Vulkan instance version requested by Android.",
            labs_vk_error_name(result), (int)result);
        return result;
    }

    getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
    destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
    enumerateDeviceExtensionProperties = (PFN_vkEnumerateDeviceExtensionProperties)gip(instance, "vkEnumerateDeviceExtensionProperties");
    enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");

    if (!getPhysicalDeviceProperties || !destroyInstance ||
        !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices) {
        if (caps_probe) snprintf(labs_last_failure, sizeof(labs_last_failure),
            "Vulkan instance was created, but one or more physical-device "
            "enumeration functions are missing.");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    return VK_SUCCESS;
}

static VkResult create_instance(jstring driverName, JNIEnv *env, jobject context) {
    return create_instance_impl(driverName, env, context, 0);
}

static VkResult enumerate_physical_devices() {
    VkResult result;
    uint32_t deviceCount;

    result = enumeratePhysicalDevices(instance, &deviceCount, NULL);

    if (result != VK_SUCCESS)
        return result;

    if (deviceCount < 1)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkPhysicalDevice *pdevices = malloc(sizeof(VkPhysicalDevice) * deviceCount);
    if (!pdevices)
        return VK_ERROR_OUT_OF_HOST_MEMORY;

    result = enumeratePhysicalDevices(instance, &deviceCount, pdevices);

    if (result != VK_SUCCESS) {
        free(pdevices);
        return result;
    }

    physicalDevice = pdevices[0];
    free(pdevices);

    if (physicalDevice == VK_NULL_HANDLE)
        return VK_ERROR_INITIALIZATION_FAILED;

    return VK_SUCCESS;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getVulkanVersion(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *driverVersion;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    uint32_t api_version_major = VK_VERSION_MAJOR(props.apiVersion);
    uint32_t api_version_minor = VK_VERSION_MINOR(props.apiVersion);
    uint32_t api_version_patch = VK_VERSION_PATCH(props.apiVersion);
    asprintf(&driverVersion, "%d.%d.%d", api_version_major, api_version_minor, api_version_patch);

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return (*env)->NewStringUTF(env, driverVersion);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_GPUInformation_getVendorID(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    uint32_t vendorID;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return 0;
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return 0;
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    vendorID = props.vendorID;

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return vendorID;
}


JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getRenderer(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *renderer;


    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    asprintf(&renderer, "%s", props.deviceName);

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return (*env)->NewStringUTF(env, renderer);
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_GPUInformation_enumerateExtensions(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    jobjectArray extensions;
    VkResult result;
    uint32_t extensionCount;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    }

    result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL);

    if (result != VK_SUCCESS || extensionCount < 1) {
        printf("Failed to query extension count");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    }

    VkExtensionProperties *extensionProperties = malloc(sizeof(VkExtensionProperties) * extensionCount);
    if (!extensionProperties) {
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    }
    result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount,
                                                extensionProperties);

    if (result != VK_SUCCESS) {
        free(extensionProperties);
        printf("Failed to query extensions");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    }

    extensions = (jobjectArray) (*env)->NewObjectArray(env, extensionCount,
                                                       (*env)->FindClass(env, "java/lang/String"),
                                                       NULL);
    for (int i = 0; i < extensionCount; i++) {
        (*env)->SetObjectArrayElement(env, extensions, i,
                                      (*env)->NewStringUTF(env, extensionProperties[i].extensionName));
    }

    free(extensionProperties);
    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return extensions;
}


// Read-only Vulkan capabilities for Winlator Labs. Called only on explicit user request.
// Returns tab-delimited rows: section, name, value. Unknown features are omitted.
static void labs_add(JNIEnv *env, jobjectArray rows, jint *index,
                     const char *section, const char *name, const char *value) {
    char row[768];
    snprintf(row, sizeof(row), "%s\t%s\t%s", section, name, value ? value : "");
    jstring item = (*env)->NewStringUTF(env, row);
    (*env)->SetObjectArrayElement(env, rows, (*index)++, item);
    (*env)->DeleteLocalRef(env, item);
}

static void labs_number(JNIEnv *env, jobjectArray rows, jint *index,
                        const char *section, const char *name, uint64_t value) {
    char number[40];
    snprintf(number, sizeof(number), "%llu", (unsigned long long)value);
    labs_add(env, rows, index, section, name, number);
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_GPUInformation_queryCapabilities(
    JNIEnv *env, jclass ignored, jstring driverName, jobject context) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray empty = (*env)->NewObjectArray(env, 1, string_class, NULL);
    jint err_index = 0;
    VkResult instance_status = create_instance_impl(driverName, env, context, 1);
    if (instance_status != VK_SUCCESS) {
        char error_message[480];
        snprintf(error_message, sizeof(error_message), "%s",
            labs_last_failure[0] ? labs_last_failure :
            "Vulkan instance initialization failed without further driver details");
        labs_add(env, empty, &err_index, "error", "Vulkan", error_message);
        goto cleanup_error;
    }

    VkResult device_status = enumerate_physical_devices();
    if (device_status != VK_SUCCESS) {
        char error_message[240];
        snprintf(error_message, sizeof(error_message),
            "vkEnumeratePhysicalDevices failed: %s (%d). "
            "The driver did not expose a usable GPU.",
            labs_vk_error_name(device_status), (int)device_status);
        labs_add(env, empty, &err_index, "error", "Vulkan", error_message);
        goto cleanup_error;
    }

    PFN_vkGetInstanceProcAddr gip =
        (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    PFN_vkGetPhysicalDeviceFeatures get_features =
        (PFN_vkGetPhysicalDeviceFeatures)gip(instance, "vkGetPhysicalDeviceFeatures");
    PFN_vkGetPhysicalDeviceFeatures2 get_features2 =
        (PFN_vkGetPhysicalDeviceFeatures2)gip(instance, "vkGetPhysicalDeviceFeatures2");
    PFN_vkGetPhysicalDeviceFormatProperties get_format =
        (PFN_vkGetPhysicalDeviceFormatProperties)gip(instance, "vkGetPhysicalDeviceFormatProperties");
    if (!get_features || !get_format) {
        labs_add(env, empty, &err_index, "error", "Vulkan",
                 "Required Vulkan capability query entry points are missing");
        goto cleanup_error;
    }

    VkPhysicalDeviceProperties props = {0};
    VkPhysicalDeviceFeatures base_features = {0};
    getPhysicalDeviceProperties(physicalDevice, &props);
    get_features(physicalDevice, &base_features);

    uint32_t extension_count = 0;
    VkExtensionProperties *extensions = NULL;
    VkResult ext_status = enumerateDeviceExtensionProperties(
        physicalDevice, NULL, &extension_count, NULL);
    char extension_warning[220] = {0};
    if (ext_status == VK_SUCCESS && extension_count > 0 && extension_count <= 4096) {
        extensions = calloc(extension_count, sizeof(VkExtensionProperties));
        if (!extensions ||
            enumerateDeviceExtensionProperties(physicalDevice, NULL, &extension_count, extensions)
                != VK_SUCCESS) {
            free(extensions);
            extensions = NULL;
            extension_count = 0;
            snprintf(extension_warning, sizeof(extension_warning),
                "Driver failed to enumerate device extension names or ran out of memory");
        }
    } else {
        if (ext_status != VK_SUCCESS)
            snprintf(extension_warning, sizeof(extension_warning),
                "Extension enumeration failed: %s (%d)",
                labs_vk_error_name(ext_status), (int)ext_status);
        else if (extension_count > 4096)
            snprintf(extension_warning, sizeof(extension_warning),
                "Extension count exceeded diagnostics safety limit");
        else
            snprintf(extension_warning, sizeof(extension_warning),
                "Driver reported no Vulkan device extensions");
        extension_count = 0;
    }

    // Headroom for core features, limits, formats, and optional Vulkan 1.1-1.3 features.
    jobjectArray rows = (*env)->NewObjectArray(env, extension_count + 256, string_class, NULL);
    jint n = 0;
    char formatted[96];
    labs_add(env, rows, &n, "overview", "GPU", props.deviceName);
    if (extension_warning[0])
        labs_add(env, rows, &n, "warning", "Extensions", extension_warning);
    snprintf(formatted, sizeof(formatted), "%u.%u.%u",
        VK_VERSION_MAJOR(props.apiVersion),
        VK_VERSION_MINOR(props.apiVersion),
        VK_VERSION_PATCH(props.apiVersion));
    labs_add(env, rows, &n, "overview", "Vulkan API", formatted);
    snprintf(formatted, sizeof(formatted), "0x%04x", props.vendorID);
    labs_add(env, rows, &n, "overview", "Vendor ID", formatted);
    snprintf(formatted, sizeof(formatted), "0x%04x", props.deviceID);
    labs_add(env, rows, &n, "overview", "Device ID", formatted);
    snprintf(formatted, sizeof(formatted), "0x%08x", props.driverVersion);
    labs_add(env, rows, &n, "overview", "Driver version (raw)", formatted);
    labs_number(env, rows, &n, "overview", "Device type", props.deviceType);

#define LABS_FEATURE(field) labs_add(env, rows, &n, "feature", #field, base_features.field ? "Supported" : "Unsupported")
    LABS_FEATURE(robustBufferAccess);
    LABS_FEATURE(fullDrawIndexUint32);
    LABS_FEATURE(imageCubeArray);
    LABS_FEATURE(independentBlend);
    LABS_FEATURE(geometryShader);
    LABS_FEATURE(tessellationShader);
    LABS_FEATURE(sampleRateShading);
    LABS_FEATURE(dualSrcBlend);
    LABS_FEATURE(logicOp);
    LABS_FEATURE(multiDrawIndirect);
    LABS_FEATURE(drawIndirectFirstInstance);
    LABS_FEATURE(depthClamp);
    LABS_FEATURE(depthBiasClamp);
    LABS_FEATURE(fillModeNonSolid);
    LABS_FEATURE(depthBounds);
    LABS_FEATURE(wideLines);
    LABS_FEATURE(largePoints);
    LABS_FEATURE(alphaToOne);
    LABS_FEATURE(multiViewport);
    LABS_FEATURE(samplerAnisotropy);
    LABS_FEATURE(textureCompressionETC2);
    LABS_FEATURE(textureCompressionASTC_LDR);
    LABS_FEATURE(textureCompressionBC);
    LABS_FEATURE(occlusionQueryPrecise);
    LABS_FEATURE(pipelineStatisticsQuery);
    LABS_FEATURE(vertexPipelineStoresAndAtomics);
    LABS_FEATURE(fragmentStoresAndAtomics);
    LABS_FEATURE(shaderTessellationAndGeometryPointSize);
    LABS_FEATURE(shaderImageGatherExtended);
    LABS_FEATURE(shaderStorageImageExtendedFormats);
    LABS_FEATURE(shaderStorageImageMultisample);
    LABS_FEATURE(shaderStorageImageReadWithoutFormat);
    LABS_FEATURE(shaderStorageImageWriteWithoutFormat);
    LABS_FEATURE(shaderUniformBufferArrayDynamicIndexing);
    LABS_FEATURE(shaderSampledImageArrayDynamicIndexing);
    LABS_FEATURE(shaderStorageBufferArrayDynamicIndexing);
    LABS_FEATURE(shaderStorageImageArrayDynamicIndexing);
    LABS_FEATURE(shaderClipDistance);
    LABS_FEATURE(shaderCullDistance);
    LABS_FEATURE(shaderFloat64);
    LABS_FEATURE(shaderInt64);
    LABS_FEATURE(shaderInt16);
    LABS_FEATURE(shaderResourceResidency);
    LABS_FEATURE(shaderResourceMinLod);
    LABS_FEATURE(sparseBinding);
    LABS_FEATURE(sparseResidencyBuffer);
    LABS_FEATURE(sparseResidencyImage2D);
    LABS_FEATURE(sparseResidencyImage3D);
    LABS_FEATURE(sparseResidency2Samples);
    LABS_FEATURE(sparseResidency4Samples);
    LABS_FEATURE(sparseResidency8Samples);
    LABS_FEATURE(sparseResidency16Samples);
    LABS_FEATURE(sparseResidencyAliased);
    LABS_FEATURE(variableMultisampleRate);
    LABS_FEATURE(inheritedQueries);
#undef LABS_FEATURE

    // Only report extension/core feature bits if the physical-device features2
    // entrypoint is actually available. Lack of query support is not "Unsupported".
    if (get_features2 && props.apiVersion >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceVulkan11Features f11 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES
        };
        VkPhysicalDeviceVulkan12Features f12 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES
        };
        VkPhysicalDeviceVulkan13Features f13 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
        };
        VkPhysicalDeviceFeatures2 f2 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &f11
        };
        if (props.apiVersion >= VK_API_VERSION_1_2) f11.pNext = &f12;
        if (props.apiVersion >= VK_API_VERSION_1_3) f12.pNext = &f13;
        get_features2(physicalDevice, &f2);
#define LABS_FEATURE11(field) labs_add(env, rows, &n, "feature", #field, f11.field ? "Supported" : "Unsupported")
#define LABS_FEATURE12(field) labs_add(env, rows, &n, "feature", #field, f12.field ? "Supported" : "Unsupported")
#define LABS_FEATURE13(field) labs_add(env, rows, &n, "feature", #field, f13.field ? "Supported" : "Unsupported")
        LABS_FEATURE11(storageBuffer16BitAccess);
        LABS_FEATURE11(uniformAndStorageBuffer16BitAccess);
        LABS_FEATURE11(storagePushConstant16);
        LABS_FEATURE11(multiview);
        LABS_FEATURE11(variablePointers);
        LABS_FEATURE11(protectedMemory);
        LABS_FEATURE11(samplerYcbcrConversion);
        LABS_FEATURE11(shaderDrawParameters);
        if (props.apiVersion >= VK_API_VERSION_1_2) {
            LABS_FEATURE12(shaderFloat16);
            LABS_FEATURE12(shaderInt8);
            LABS_FEATURE12(descriptorIndexing);
            LABS_FEATURE12(runtimeDescriptorArray);
            LABS_FEATURE12(descriptorBindingPartiallyBound);
            LABS_FEATURE12(timelineSemaphore);
            LABS_FEATURE12(bufferDeviceAddress);
            LABS_FEATURE12(vulkanMemoryModel);
            LABS_FEATURE12(scalarBlockLayout);
            LABS_FEATURE12(hostQueryReset);
            LABS_FEATURE12(shaderSubgroupExtendedTypes);
            LABS_FEATURE12(storageBuffer8BitAccess);
        }
        if (props.apiVersion >= VK_API_VERSION_1_3) {
            LABS_FEATURE13(dynamicRendering);
            LABS_FEATURE13(synchronization2);
            LABS_FEATURE13(maintenance4);
            LABS_FEATURE13(robustImageAccess);
        }
#undef LABS_FEATURE11
#undef LABS_FEATURE12
#undef LABS_FEATURE13
    }
    for (uint32_t i = 0; i < extension_count; i++)
        labs_add(env, rows, &n, "extension", extensions[i].extensionName, "Available");

#define LABS_LIMIT(field) labs_number(env, rows, &n, "limit", #field, props.limits.field)
    LABS_LIMIT(maxImageDimension1D);
    LABS_LIMIT(maxImageDimension2D);
    LABS_LIMIT(maxImageDimension3D);
    LABS_LIMIT(maxImageDimensionCube);
    LABS_LIMIT(maxImageArrayLayers);
    LABS_LIMIT(maxTexelBufferElements);
    LABS_LIMIT(maxUniformBufferRange);
    LABS_LIMIT(maxStorageBufferRange);
    LABS_LIMIT(maxPushConstantsSize);
    LABS_LIMIT(maxMemoryAllocationCount);
    LABS_LIMIT(maxSamplerAllocationCount);
    LABS_LIMIT(maxBoundDescriptorSets);
    LABS_LIMIT(maxComputeSharedMemorySize);
    LABS_LIMIT(maxComputeWorkGroupInvocations);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupCount X", props.limits.maxComputeWorkGroupCount[0]);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupCount Y", props.limits.maxComputeWorkGroupCount[1]);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupCount Z", props.limits.maxComputeWorkGroupCount[2]);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupSize X", props.limits.maxComputeWorkGroupSize[0]);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupSize Y", props.limits.maxComputeWorkGroupSize[1]);
    labs_number(env, rows, &n, "limit", "maxComputeWorkGroupSize Z", props.limits.maxComputeWorkGroupSize[2]);
    LABS_LIMIT(maxPerStageResources);
    LABS_LIMIT(maxColorAttachments);
    LABS_LIMIT(maxFramebufferWidth);
    LABS_LIMIT(maxFramebufferHeight);
    labs_number(env, rows, &n, "limit", "maxViewportWidth", props.limits.maxViewportDimensions[0]);
    labs_number(env, rows, &n, "limit", "maxViewportHeight", props.limits.maxViewportDimensions[1]);
    LABS_LIMIT(maxVertexInputAttributes);
    LABS_LIMIT(maxFragmentInputComponents);
#undef LABS_LIMIT

#define LABS_FORMAT(name, vkformat) do { \
    VkFormatProperties fp = {0}; \
    get_format(physicalDevice, vkformat, &fp); \
    uint32_t bits = fp.optimalTilingFeatures; \
    const char *status = (bits & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? "Supported" : "Unsupported"; \
    labs_add(env, rows, &n, "format", name, status); \
} while (0)
    LABS_FORMAT("RGBA8", VK_FORMAT_R8G8B8A8_UNORM);
    LABS_FORMAT("BGRA8", VK_FORMAT_B8G8R8A8_UNORM);
    LABS_FORMAT("RGBA16F", VK_FORMAT_R16G16B16A16_SFLOAT);
    LABS_FORMAT("BC1", VK_FORMAT_BC1_RGBA_UNORM_BLOCK);
    LABS_FORMAT("BC2", VK_FORMAT_BC2_UNORM_BLOCK);
    LABS_FORMAT("BC3", VK_FORMAT_BC3_UNORM_BLOCK);
    LABS_FORMAT("BC4", VK_FORMAT_BC4_UNORM_BLOCK);
    LABS_FORMAT("BC5", VK_FORMAT_BC5_UNORM_BLOCK);
    LABS_FORMAT("BC6H UFLOAT", VK_FORMAT_BC6H_UFLOAT_BLOCK);
    LABS_FORMAT("BC6H SFLOAT", VK_FORMAT_BC6H_SFLOAT_BLOCK);
    LABS_FORMAT("BC7", VK_FORMAT_BC7_UNORM_BLOCK);
    LABS_FORMAT("ETC2", VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK);
    LABS_FORMAT("ASTC 4x4", VK_FORMAT_ASTC_4x4_UNORM_BLOCK);
    LABS_FORMAT("ASTC 8x8", VK_FORMAT_ASTC_8x8_UNORM_BLOCK);
#undef LABS_FORMAT

    free(extensions);
    // Return a correctly sized Java array rather than empty trailing entries.
    jobjectArray compact = (*env)->NewObjectArray(env, n, string_class, NULL);
    for (jint i = 0; i < n; ++i) {
        jobject item = (*env)->GetObjectArrayElement(env, rows, i);
        (*env)->SetObjectArrayElement(env, compact, i, item);
        (*env)->DeleteLocalRef(env, item);
    }
    if (destroyInstance) destroyInstance(instance, NULL);
    instance = VK_NULL_HANDLE;
    if (vulkan_handle) dlclose(vulkan_handle);
    vulkan_handle = NULL;
    return compact;

cleanup_error:
    if (destroyInstance && instance != VK_NULL_HANDLE) destroyInstance(instance, NULL);
    instance = VK_NULL_HANDLE;
    if (vulkan_handle) dlclose(vulkan_handle);
    vulkan_handle = NULL;
    return empty;
}


// Initial Winlator Labs GPU test: an offscreen, bounded Vulkan buffer-transfer
// correctness test and throughput measurement. Never called by game renderers.
// This JNI entry is used exclusively from the separate :labs_gpu_test process.
static uint32_t labs_choose_host_memory(uint32_t bits, const VkPhysicalDeviceMemoryProperties *memory) {
    for (uint32_t i = 0; i < memory->memoryTypeCount; ++i) {
        VkMemoryPropertyFlags flags = memory->memoryTypes[i].propertyFlags;
        if ((bits & (1u << i)) && (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
            return i;
    }
    for (uint32_t i = 0; i < memory->memoryTypeCount; ++i) {
        if ((bits & (1u << i)) &&
            (memory->memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
            return i;
    }
    return UINT32_MAX;
}

static double labs_elapsed_ms(struct timespec begin, struct timespec end) {
    return (double)(end.tv_sec - begin.tv_sec) * 1000.0 +
           (double)(end.tv_nsec - begin.tv_nsec) / 1000000.0;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_runGpuTransferTest(
        JNIEnv *env, jclass unused, jstring driverName, jobject context, jboolean preflight) {
    const char *stage = "Vulkan initialization";
    VkInstance lab_instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkBuffer buffers[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkDeviceMemory allocations[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkPhysicalDeviceMemoryProperties memory_props = {0};
    VkPhysicalDeviceProperties props = {0};
    VkQueueFamilyProperties *families = NULL;
    VkQueue queue = VK_NULL_HANDLE;
    VkResult result;
    void *mapped = NULL;
    int mapped_index = -1;
    PFN_vkGetDeviceProcAddr gdpa = NULL;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties pGetFamilies = NULL;
    PFN_vkGetPhysicalDeviceFormatProperties pGetFormats = NULL;
    PFN_vkGetPhysicalDeviceMemoryProperties pGetMemoryProps = NULL;
    PFN_vkCreateDevice pCreateDevice = NULL;
    PFN_vkDestroyDevice pDestroyDevice = NULL;
    PFN_vkGetDeviceQueue pGetQueue = NULL;
    PFN_vkCreateCommandPool pCreateCommandPool = NULL;
    PFN_vkDestroyCommandPool pDestroyCommandPool = NULL;
    PFN_vkAllocateCommandBuffers pAllocateCommandBuffers = NULL;
    PFN_vkBeginCommandBuffer pBeginCommandBuffer = NULL;
    PFN_vkEndCommandBuffer pEndCommandBuffer = NULL;
    PFN_vkCmdCopyBuffer pCmdCopyBuffer = NULL;
    PFN_vkQueueSubmit pQueueSubmit = NULL;
    PFN_vkQueueWaitIdle pQueueWaitIdle = NULL;
    PFN_vkCreateBuffer pCreateBuffer = NULL;
    PFN_vkDestroyBuffer pDestroyBuffer = NULL;
    PFN_vkGetBufferMemoryRequirements pGetBufferRequirements = NULL;
    PFN_vkAllocateMemory pAllocateMemory = NULL;
    PFN_vkFreeMemory pFreeMemory = NULL;
    PFN_vkBindBufferMemory pBindMemory = NULL;
    PFN_vkMapMemory pMapMemory = NULL;
    PFN_vkUnmapMemory pUnmapMemory = NULL;
    PFN_vkFlushMappedMemoryRanges pFlush = NULL;
    PFN_vkInvalidateMappedMemoryRanges pInvalidate = NULL;
    char response[512];
    const VkDeviceSize buffer_size = 4u * 1024u * 1024u;
    const uint32_t copies_per_submit = 8;
    const int warmups = 2;
    const int trials = 7;
    uint32_t family = UINT32_MAX;
    uint32_t family_count = 0;
    double times[7] = {0};
    bool ok = false;

    if (create_instance_impl(driverName, env, context, 1) != VK_SUCCESS) {
        stage = "Vulkan instance unavailable";
        goto done;
    }
    lab_instance = instance;
    stage = "No Vulkan device";
    if (enumerate_physical_devices() != VK_SUCCESS) goto done;

    PFN_vkGetInstanceProcAddr gip =
        (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    if (!gip) { stage = "Vulkan entry points unavailable"; goto done; }
    pGetFamilies = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)
        gip(lab_instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    pGetMemoryProps = (PFN_vkGetPhysicalDeviceMemoryProperties)
        gip(lab_instance, "vkGetPhysicalDeviceMemoryProperties");
    pGetFormats = (PFN_vkGetPhysicalDeviceFormatProperties)
        gip(lab_instance, "vkGetPhysicalDeviceFormatProperties");
    pCreateDevice = (PFN_vkCreateDevice)gip(lab_instance, "vkCreateDevice");
    gdpa = (PFN_vkGetDeviceProcAddr)gip(lab_instance, "vkGetDeviceProcAddr");
    if (!pGetFamilies || !pGetMemoryProps || !pGetFormats || !pCreateDevice || !gdpa) {
        stage = "Required Vulkan device functions unavailable";
        goto done;
    }
    getPhysicalDeviceProperties(physicalDevice, &props);
    if (preflight) {
        VkFormatProperties rgba = {0}, d16 = {0};
        pGetFormats(physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, &rgba);
        pGetFormats(physicalDevice, VK_FORMAT_D16_UNORM, &d16);
        if (!(rgba.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) ||
            !(rgba.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_SRC_BIT) ||
            !(d16.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)) {
            stage = "Driver lacks Vulkan cube render target or depth support";
            goto done;
        }
    }
    pGetFamilies(physicalDevice, &family_count, NULL);
    if (!family_count || family_count > 64) {
        stage = "No usable Vulkan queue family";
        goto done;
    }
    families = calloc(family_count, sizeof(VkQueueFamilyProperties));
    if (!families) { stage = "Out of memory"; goto done; }
    pGetFamilies(physicalDevice, &family_count, families);
    for (uint32_t i = 0; i < family_count; ++i) {
        if (families[i].queueCount &&
            ((preflight && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ||
             (!preflight && (families[i].queueFlags &
                (VK_QUEUE_TRANSFER_BIT | VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))))) {
            family = i;
            break;
        }
    }
    if (family == UINT32_MAX) {
        stage = preflight ? "Driver has no Vulkan graphics queue" :
                            "No queue supporting buffer transfers";
        goto done;
    }
    if (preflight && (!families[family].timestampValidBits ||
                      props.limits.timestampPeriod <= 0.0f)) {
        stage = "Driver cannot report reliable Vulkan GPU timestamps";
        goto done;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_info
    };
    stage = "vkCreateDevice failed (driver may be incompatible)";
    if (pCreateDevice(physicalDevice, &device_info, NULL, &device) != VK_SUCCESS) goto done;

#define LABS_LOAD_DEVICE(field, proc) do { \
    field = (PFN_vk##proc)gdpa(device, "vk" #proc); \
    if (!field) { stage = "Required Vulkan function missing: vk" #proc; goto done; } \
} while (0)
    LABS_LOAD_DEVICE(pDestroyDevice, DestroyDevice);
    LABS_LOAD_DEVICE(pGetQueue, GetDeviceQueue);
    LABS_LOAD_DEVICE(pCreateCommandPool, CreateCommandPool);
    LABS_LOAD_DEVICE(pDestroyCommandPool, DestroyCommandPool);
    LABS_LOAD_DEVICE(pAllocateCommandBuffers, AllocateCommandBuffers);
    LABS_LOAD_DEVICE(pBeginCommandBuffer, BeginCommandBuffer);
    LABS_LOAD_DEVICE(pEndCommandBuffer, EndCommandBuffer);
    LABS_LOAD_DEVICE(pCmdCopyBuffer, CmdCopyBuffer);
    LABS_LOAD_DEVICE(pQueueSubmit, QueueSubmit);
    LABS_LOAD_DEVICE(pQueueWaitIdle, QueueWaitIdle);
    LABS_LOAD_DEVICE(pCreateBuffer, CreateBuffer);
    LABS_LOAD_DEVICE(pDestroyBuffer, DestroyBuffer);
    LABS_LOAD_DEVICE(pGetBufferRequirements, GetBufferMemoryRequirements);
    LABS_LOAD_DEVICE(pAllocateMemory, AllocateMemory);
    LABS_LOAD_DEVICE(pFreeMemory, FreeMemory);
    LABS_LOAD_DEVICE(pBindMemory, BindBufferMemory);
    LABS_LOAD_DEVICE(pMapMemory, MapMemory);
    LABS_LOAD_DEVICE(pUnmapMemory, UnmapMemory);
    LABS_LOAD_DEVICE(pFlush, FlushMappedMemoryRanges);
    LABS_LOAD_DEVICE(pInvalidate, InvalidateMappedMemoryRanges);
#undef LABS_LOAD_DEVICE
    pGetQueue(device, family, 0, &queue);
    if (queue == VK_NULL_HANDLE) { stage = "Unable to acquire queue"; goto done; }
    pGetMemoryProps(physicalDevice, &memory_props);
    if (preflight) {
        // Only enable GPU Tests when a real graphics device and cube formats work.
        snprintf(response, sizeof(response), "READY\t%s\t%u.%u\t%s",
            props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
            VK_VERSION_MINOR(props.apiVersion), "Graphics device and cube formats verified");
        ok = true;
        goto done;
    }

    stage = "Creating Vulkan transfer command pool";
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .queueFamilyIndex = family
    };
    if (pCreateCommandPool(device, &pool_info, NULL, &pool) != VK_SUCCESS) goto done;
    VkCommandBufferAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1
    };
    stage = "Allocating Vulkan transfer command buffer";
    if (pAllocateCommandBuffers(device, &alloc_info, &cmd) != VK_SUCCESS) goto done;

    for (int i = 0; i < 2; ++i) {
        VkBufferCreateInfo buffer_info = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = buffer_size,
            .usage = i == 0 ? VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                            : VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
        };
        stage = "Creating transfer buffer";
        if (pCreateBuffer(device, &buffer_info, NULL, &buffers[i]) != VK_SUCCESS) goto done;
        VkMemoryRequirements requirements = {0};
        pGetBufferRequirements(device, buffers[i], &requirements);
        uint32_t type = labs_choose_host_memory(requirements.memoryTypeBits, &memory_props);
        if (type == UINT32_MAX) { stage = "Host-visible Vulkan memory unsupported"; goto done; }
        VkMemoryAllocateInfo allocation = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = requirements.size, .memoryTypeIndex = type
        };
        stage = "Allocating test buffer memory";
        if (pAllocateMemory(device, &allocation, NULL, &allocations[i]) != VK_SUCCESS)
            goto done;
        if (pBindMemory(device, buffers[i], allocations[i], 0) != VK_SUCCESS) {
            stage = "Binding test buffer memory";
            goto done;
        }
    }
    stage = "Mapping source memory";
    if (pMapMemory(device, allocations[0], 0, VK_WHOLE_SIZE, 0, &mapped) != VK_SUCCESS)
        goto done;
    mapped_index = 0;
    for (size_t i = 0; i < (size_t)buffer_size; ++i)
        ((uint8_t *)mapped)[i] = (uint8_t)(i * 13u + 17u);
    VkMappedMemoryRange source_range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = allocations[0], .offset = 0, .size = VK_WHOLE_SIZE
    };
    stage = "Flushing source memory";
    if (pFlush(device, 1, &source_range) != VK_SUCCESS) goto done;
    pUnmapMemory(device, allocations[0]);
    mapped = NULL;
    mapped_index = -1;

    VkCommandBufferBeginInfo begin = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    stage = "Recording GPU transfer commands";
    if (pBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) goto done;
    VkBufferCopy region = { .srcOffset = 0, .dstOffset = 0, .size = buffer_size };
    for (uint32_t i = 0; i < copies_per_submit; ++i)
        pCmdCopyBuffer(cmd, buffers[0], buffers[1], 1, &region);
    if (pEndCommandBuffer(cmd) != VK_SUCCESS) goto done;

    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1, .pCommandBuffers = &cmd
    };
    for (int i = -warmups; i < trials; ++i) {
        struct timespec t0 = {0}, t1 = {0};
        clock_gettime(CLOCK_MONOTONIC, &t0);
        stage = "Submitting Vulkan transfer";
        result = pQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
        if (result != VK_SUCCESS) goto done;
        stage = "Waiting for GPU completion";
        if (pQueueWaitIdle(queue) != VK_SUCCESS) goto done;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        if (i >= 0) times[i] = labs_elapsed_ms(t0, t1);
    }

    stage = "Mapping result for GPU memory verification";
    if (pMapMemory(device, allocations[1], 0, VK_WHOLE_SIZE, 0, &mapped) != VK_SUCCESS)
        goto done;
    mapped_index = 1;
    VkMappedMemoryRange dest_range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = allocations[1], .offset = 0, .size = VK_WHOLE_SIZE
    };
    stage = "Invalidating output memory";
    if (pInvalidate(device, 1, &dest_range) != VK_SUCCESS) goto done;
    stage = "GPU buffer verification failed";
    for (size_t i = 0; i < (size_t)buffer_size; ++i) {
        if (((uint8_t *)mapped)[i] != (uint8_t)(i * 13u + 17u)) goto done;
    }
    pUnmapMemory(device, allocations[1]);
    mapped = NULL;
    mapped_index = -1;
    for (int i = 0; i < trials; ++i)
        for (int j = i + 1; j < trials; ++j)
            if (times[j] < times[i]) {
                double tmp = times[i];
                times[i] = times[j];
                times[j] = tmp;
            }
    double median_ms = times[trials / 2];
    if (median_ms <= 0.0) { stage = "GPU timer measurement unavailable"; goto done; }
    // 32 MiB transferred per timed submission, measuring queue submit-to-idle wall time.
    snprintf(response, sizeof(response), "PASS\t%.4f\t%.1f\t%s",
        median_ms, (double)(copies_per_submit * buffer_size) /
        (1024.0 * 1024.0) * 1000.0 / median_ms, props.deviceName);
    ok = true;

done:
    // This entry runs only in the test worker process; never disrupt normal renderer state.
    if (mapped && pUnmapMemory && device && mapped_index >= 0)
        pUnmapMemory(device, allocations[mapped_index]);
    if (device && pQueueWaitIdle && queue) pQueueWaitIdle(queue);
    if (device && pDestroyBuffer)
        for (int i = 0; i < 2; ++i)
            if (buffers[i]) pDestroyBuffer(device, buffers[i], NULL);
    if (device && pFreeMemory)
        for (int i = 0; i < 2; ++i)
            if (allocations[i]) pFreeMemory(device, allocations[i], NULL);
    if (device && pDestroyCommandPool && pool) pDestroyCommandPool(device, pool, NULL);
    if (device && pDestroyDevice) pDestroyDevice(device, NULL);
    free(families);
    if (lab_instance && destroyInstance) destroyInstance(lab_instance, NULL);
    instance = VK_NULL_HANDLE;
    physicalDevice = VK_NULL_HANDLE;
    if (vulkan_handle) dlclose(vulkan_handle);
    vulkan_handle = NULL;
    if (!ok) snprintf(response, sizeof(response), "ERROR\t%s", stage);
    return (*env)->NewStringUTF(env, response);
}

// On-demand GPU benchmark, kept outside the gameplay presentation path.
#include "labs_cube.c"
