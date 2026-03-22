#include <jni.h>
#include <vulkan/vulkan.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "GPUHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to create an empty String array
static jobjectArray make_empty_array(JNIEnv *env) {
    jclass stringCls = (*env)->FindClass(env, "java/lang/String");
    if (!stringCls) return NULL;
    return (*env)->NewObjectArray(env, 0, stringCls, NULL);
}

// Typedef for vkEnumerateInstanceVersion (Vulkan 1.1+)
typedef VkResult (VKAPI_PTR *PFN_vkEnumerateInstanceVersion)(uint32_t* pApiVersion);

/**
 * Queries the highest Vulkan API version supported by the device.
 * Falls back to VK_API_VERSION_1_0 if vkEnumerateInstanceVersion is unavailable (Vulkan 1.0 only devices).
 */
static uint32_t query_instance_api_version(void *vulkan_handle, PFN_vkGetInstanceProcAddr gip) {
    // Try to get vkEnumerateInstanceVersion (available in Vulkan 1.1+)
    // For pre-instance calls, we pass NULL as the instance
    PFN_vkEnumerateInstanceVersion enumInstVersion = NULL;

    // First try via vkGetInstanceProcAddr with null instance
    if (gip) {
        enumInstVersion = (PFN_vkEnumerateInstanceVersion)gip(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    }

    // Fallback: try direct dlsym (some drivers export it directly)
    if (!enumInstVersion && vulkan_handle) {
        enumInstVersion = (PFN_vkEnumerateInstanceVersion)dlsym(vulkan_handle, "vkEnumerateInstanceVersion");
    }

    if (enumInstVersion) {
        uint32_t apiVersion = VK_API_VERSION_1_0;
        VkResult res = enumInstVersion(&apiVersion);
        if (res == VK_SUCCESS) {
            LOGI("Detected Vulkan instance API version: %u.%u.%u",
                 VK_VERSION_MAJOR(apiVersion),
                 VK_VERSION_MINOR(apiVersion),
                 VK_VERSION_PATCH(apiVersion));
            return apiVersion;
        }
    }

    // Vulkan 1.0 device - vkEnumerateInstanceVersion doesn't exist
    LOGI("vkEnumerateInstanceVersion not available, assuming Vulkan 1.0");
    return VK_API_VERSION_1_0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_core_GPUHelper_vkGetDeviceExtensions(JNIEnv *env, jclass clazz)
{
    (void)clazz;

    void *vulkan_handle = NULL;
    PFN_vkGetInstanceProcAddr gip = NULL;
    PFN_vkCreateInstance createInstance = NULL;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = NULL;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = NULL;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = NULL;
    PFN_vkDestroyInstance destroyInstance = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice *pds = NULL;
    VkExtensionProperties *ext = NULL;
    jobjectArray arr = NULL;
    VkResult res;

    // ============================================================
    // Step 1: Dynamic load Vulkan for maximum compatibility
    // This is critical for Android API 29 and below where static
    // linking to Vulkan may fail or behave unexpectedly
    // ============================================================
    vulkan_handle = dlopen("libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    if (!vulkan_handle) {
        LOGE("Failed to load libvulkan.so: %s", dlerror());
        return make_empty_array(env);
    }

    gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
    if (!gip || !createInstance) {
        LOGE("Failed to get core Vulkan functions");
        goto fail;
    }

    // ============================================================
    // Step 2: Query the maximum supported API version
    // This ensures we create an instance that can expose all
    // extensions the device supports
    // ============================================================
    uint32_t instanceApiVersion = query_instance_api_version(vulkan_handle, gip);

    // ============================================================
    // Step 3: Create Vulkan instance with detected API version
    // ============================================================
    VkApplicationInfo app_info;
    memset(&app_info, 0, sizeof(app_info));
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "GameNative";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "GameNative";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = instanceApiVersion;  // Use detected version

    VkInstanceCreateInfo ci;
    memset(&ci, 0, sizeof(ci));
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app_info;
    // Note: We don't enable any instance extensions here as we only need
    // to enumerate device extensions. Surface extensions would be needed
    // for a complete rendering setup but are handled elsewhere.

    res = createInstance(&ci, NULL, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGE("vkCreateInstance failed: %d", res);
        goto fail;
    }

    // ============================================================
    // Step 4: Get instance-level function pointers
    // ============================================================
    enumeratePhysicalDevices =
            (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");
    enumerateDeviceExtensionProperties =
            (PFN_vkEnumerateDeviceExtensionProperties)gip(instance, "vkEnumerateDeviceExtensionProperties");
    getPhysicalDeviceProperties =
            (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
    destroyInstance =
            (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");

    if (!enumeratePhysicalDevices || !enumerateDeviceExtensionProperties || !destroyInstance) {
        LOGE("Failed to get required Vulkan instance functions");
        goto fail;
    }

    // ============================================================
    // Step 5: Enumerate physical devices
    // ============================================================
    uint32_t pdCount = 0;
    res = enumeratePhysicalDevices(instance, &pdCount, NULL);
    if (res != VK_SUCCESS || pdCount == 0) {
        LOGE("No physical devices found or enumeration failed: %d, count: %u", res, pdCount);
        goto fail;
    }

    pds = (VkPhysicalDevice *)calloc(pdCount, sizeof(VkPhysicalDevice));
    if (!pds) {
        LOGE("Failed to allocate memory for physical devices");
        goto fail;
    }

    res = enumeratePhysicalDevices(instance, &pdCount, pds);
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) {
        LOGE("Failed to enumerate physical devices: %d", res);
        goto fail;
    }

    // ============================================================
    // Step 6: Select the best physical device
    // On Android, typically there's only one GPU, but we try to
    // pick a discrete GPU if multiple exist
    // ============================================================
    VkPhysicalDevice pd = pds[0];
    if (getPhysicalDeviceProperties && pdCount > 1) {
        VkPhysicalDeviceProperties props;
        for (uint32_t i = 0; i < pdCount; ++i) {
            getPhysicalDeviceProperties(pds[i], &props);
            LOGI("Physical device %u: %s (type: %d, API: %u.%u.%u)", i, props.deviceName,
                 props.deviceType,
                 VK_VERSION_MAJOR(props.apiVersion),
                 VK_VERSION_MINOR(props.apiVersion),
                 VK_VERSION_PATCH(props.apiVersion));
            // Prefer discrete GPU if available
            if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                pd = pds[i];
                break;
            }
        }
    } else if (getPhysicalDeviceProperties) {
        VkPhysicalDeviceProperties props;
        getPhysicalDeviceProperties(pd, &props);
        LOGI("Using physical device: %s (API: %u.%u.%u)", props.deviceName,
             VK_VERSION_MAJOR(props.apiVersion),
             VK_VERSION_MINOR(props.apiVersion),
             VK_VERSION_PATCH(props.apiVersion));
    }

    free(pds);
    pds = NULL;

    // ============================================================
    // Step 7: Enumerate device extensions
    // We use a loop to handle VK_INCOMPLETE properly
    // ============================================================
    uint32_t extCount = 0;
    res = enumerateDeviceExtensionProperties(pd, NULL, &extCount, NULL);
    if (res != VK_SUCCESS) {
        LOGE("Failed to get extension count: %d", res);
        goto fail;
    }

    if (extCount == 0) {
        LOGW("No device extensions found");
        goto fail;
    }

    // Allocate with some extra space in case count changes between calls
    uint32_t allocCount = extCount + 16;
    ext = (VkExtensionProperties *)calloc(allocCount, sizeof(VkExtensionProperties));
    if (!ext) {
        LOGE("Failed to allocate memory for extensions");
        goto fail;
    }

    // Query extensions - handle VK_INCOMPLETE by re-querying
    uint32_t actualCount = allocCount;
    res = enumerateDeviceExtensionProperties(pd, NULL, &actualCount, ext);
    if (res == VK_INCOMPLETE) {
        // More extensions than expected, reallocate and retry
        free(ext);
        extCount = actualCount + 32;
        ext = (VkExtensionProperties *)calloc(extCount, sizeof(VkExtensionProperties));
        if (!ext) {
            LOGE("Failed to reallocate memory for extensions");
            goto fail;
        }
        actualCount = extCount;
        res = enumerateDeviceExtensionProperties(pd, NULL, &actualCount, ext);
    }

    if (res != VK_SUCCESS) {
        LOGE("Failed to enumerate device extensions: %d", res);
        goto fail;
    }

    LOGI("Found %u device extensions", actualCount);

    // ============================================================
    // Step 8: Create Java String array and populate
    // ============================================================
    jclass stringCls = (*env)->FindClass(env, "java/lang/String");
    if (!stringCls) {
        LOGE("Failed to find String class");
        goto fail;
    }

    arr = (*env)->NewObjectArray(env, (jsize)actualCount, stringCls, NULL);
    if (!arr) {
        LOGE("Failed to create String array");
        goto fail;
    }

    for (jsize i = 0; i < (jsize)actualCount; ++i) {
        jstring js = (*env)->NewStringUTF(env, ext[i].extensionName);
        if (!js) {
            LOGW("Failed to create string for extension %d", i);
            continue;
        }
        (*env)->SetObjectArrayElement(env, arr, i, js);
        (*env)->DeleteLocalRef(env, js);  // Prevent local reference table overflow
    }

    // ============================================================
    // Step 9: Cleanup and return
    // ============================================================
    free(ext);
    destroyInstance(instance, NULL);
    dlclose(vulkan_handle);

    return arr;

fail:
    if (pds) {
        free(pds);
    }
    if (ext) {
        free(ext);
    }
    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
    }
    if (vulkan_handle) {
        dlclose(vulkan_handle);
    }
    return make_empty_array(env);
}

/**
 * Returns the highest Vulkan API version supported by the physical device.
 * This is used to determine which Vulkan features the device supports.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_core_GPUHelper_vkGetApiVersion(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;

    void *vulkan_handle = NULL;
    PFN_vkGetInstanceProcAddr gip = NULL;
    PFN_vkCreateInstance createInstance = NULL;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = NULL;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = NULL;
    PFN_vkDestroyInstance destroyInstance = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice *pds = NULL;
    VkResult res;
    jint result = VK_MAKE_VERSION(1, 0, 0);  // Default fallback

    // Dynamic load
    vulkan_handle = dlopen("libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    if (!vulkan_handle) {
        LOGE("vkGetApiVersion: Failed to load libvulkan.so");
        return result;
    }

    gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
    if (!gip || !createInstance) {
        LOGE("vkGetApiVersion: Failed to get core functions");
        goto cleanup;
    }

    // Get instance API version first
    uint32_t instanceApiVersion = query_instance_api_version(vulkan_handle, gip);

    // Create minimal instance
    VkApplicationInfo app_info;
    memset(&app_info, 0, sizeof(app_info));
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.apiVersion = instanceApiVersion;

    VkInstanceCreateInfo ci;
    memset(&ci, 0, sizeof(ci));
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app_info;

    res = createInstance(&ci, NULL, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGE("vkGetApiVersion: vkCreateInstance failed");
        goto cleanup;
    }

    enumeratePhysicalDevices =
            (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");
    getPhysicalDeviceProperties =
            (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
    destroyInstance =
            (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");

    if (!enumeratePhysicalDevices || !getPhysicalDeviceProperties || !destroyInstance) {
        LOGE("vkGetApiVersion: Failed to get instance functions");
        goto cleanup;
    }

    uint32_t pdCount = 0;
    res = enumeratePhysicalDevices(instance, &pdCount, NULL);
    if (res != VK_SUCCESS || pdCount == 0) {
        LOGE("vkGetApiVersion: No physical devices");
        goto cleanup;
    }

    pds = (VkPhysicalDevice *)calloc(pdCount, sizeof(VkPhysicalDevice));
    if (!pds) {
        goto cleanup;
    }

    res = enumeratePhysicalDevices(instance, &pdCount, pds);
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) {
        goto cleanup;
    }

    // Get the API version from the first (or best) physical device
    VkPhysicalDeviceProperties props;
    getPhysicalDeviceProperties(pds[0], &props);
    result = (jint)props.apiVersion;

    LOGI("vkGetApiVersion: Device %s supports Vulkan %u.%u.%u",
         props.deviceName,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion));

cleanup:
    if (pds) {
        free(pds);
    }
    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
    }
    if (vulkan_handle) {
        dlclose(vulkan_handle);
    }
    return result;
}

