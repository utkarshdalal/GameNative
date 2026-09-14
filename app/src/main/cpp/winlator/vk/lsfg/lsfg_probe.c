#include "lsfg_probe.h"

#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

static void* winlator_open_vulkan(JNIEnv* env, jobject context, const char* driver_name) {
    (void)env;
    (void)context;
    (void)driver_name;
    void* handle = dlopen("libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    if (!handle) {
        handle = dlopen("libvulkan.so", RTLD_NOW);
    }
    return handle;
}

#define LOG_TAG "LsfgProbe"
#define PROBE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define PROBE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define REQUIRED_FEATURES \
    (VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)

#define MAX_PROBE_DEVICES 8
#define MAX_PROBE_QUEUE_FAMILIES 16

typedef struct ProbeApi {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkCreateInstance CreateInstance;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
} ProbeApi;

static const VkFormat kRequiredFormats[] = {
    VK_FORMAT_R8G8B8A8_UNORM,
    VK_FORMAT_R8_UNORM,
    VK_FORMAT_R16G16B16A16_SFLOAT,
};

static bool load_probe_api(void* library, ProbeApi* api) {
    memset(api, 0, sizeof(*api));

    api->GetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
    if (!api->GetInstanceProcAddr) return false;

    api->CreateInstance =
        (PFN_vkCreateInstance)api->GetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    return api->CreateInstance != NULL;
}

static bool load_instance_api(ProbeApi* api, VkInstance instance) {
    api->DestroyInstance =
        (PFN_vkDestroyInstance)api->GetInstanceProcAddr(instance, "vkDestroyInstance");
    api->EnumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)api->GetInstanceProcAddr(
        instance, "vkEnumeratePhysicalDevices");
    api->GetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)api->GetInstanceProcAddr(
        instance, "vkGetPhysicalDeviceProperties");
    api->GetPhysicalDeviceFormatProperties =
        (PFN_vkGetPhysicalDeviceFormatProperties)api->GetInstanceProcAddr(
            instance, "vkGetPhysicalDeviceFormatProperties");
    api->GetPhysicalDeviceQueueFamilyProperties =
        (PFN_vkGetPhysicalDeviceQueueFamilyProperties)api->GetInstanceProcAddr(
            instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    api->GetPhysicalDeviceFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)api->GetInstanceProcAddr(
        instance, "vkGetPhysicalDeviceFeatures2");

    return api->DestroyInstance && api->EnumeratePhysicalDevices &&
           api->GetPhysicalDeviceProperties && api->GetPhysicalDeviceFormatProperties &&
           api->GetPhysicalDeviceQueueFamilyProperties && api->GetPhysicalDeviceFeatures2;
}

static bool has_compute_queue(const ProbeApi* api, VkPhysicalDevice device) {
    uint32_t count = 0;
    api->GetPhysicalDeviceQueueFamilyProperties(device, &count, NULL);
    if (count == 0) return false;
    if (count > MAX_PROBE_QUEUE_FAMILIES) count = MAX_PROBE_QUEUE_FAMILIES;

    VkQueueFamilyProperties families[MAX_PROBE_QUEUE_FAMILIES];
    api->GetPhysicalDeviceQueueFamilyProperties(device, &count, families);

    for (uint32_t i = 0; i < count; i++) {
        if (families[i].queueCount > 0 && (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            return true;
        }
    }
    return false;
}

static bool has_required_formats(const ProbeApi* api, VkPhysicalDevice device) {
    const size_t format_count = sizeof(kRequiredFormats) / sizeof(kRequiredFormats[0]);
    for (size_t i = 0; i < format_count; i++) {
        VkFormatProperties properties;
        memset(&properties, 0, sizeof(properties));
        api->GetPhysicalDeviceFormatProperties(device, kRequiredFormats[i], &properties);
        if ((properties.optimalTilingFeatures & REQUIRED_FEATURES) != REQUIRED_FEATURES) {
            PROBE_LOGW("Format %d lacks storage or sampled support", (int)kRequiredFormats[i]);
            return false;
        }
    }
    return true;
}

static bool has_required_features(const ProbeApi* api, VkPhysicalDevice device) {
    VkPhysicalDeviceVulkanMemoryModelFeatures memory_model;
    memset(&memory_model, 0, sizeof(memory_model));
    memory_model.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES;

    VkPhysicalDeviceFeatures2 features;
    memset(&features, 0, sizeof(features));
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &memory_model;

    api->GetPhysicalDeviceFeatures2(device, &features);

    if (!memory_model.vulkanMemoryModel) {
        PROBE_LOGW("vulkanMemoryModel unsupported; translated LSFG shaders require it");
        return false;
    }
    if (!features.features.shaderStorageImageWriteWithoutFormat) {
        PROBE_LOGW("shaderStorageImageWriteWithoutFormat unsupported");
        return false;
    }
    if (!features.features.shaderStorageImageExtendedFormats) {
        PROBE_LOGW("shaderStorageImageExtendedFormats unsupported");
        return false;
    }
    return true;
}

static bool probe_instance(ProbeApi* api, VkInstance instance) {
    uint32_t device_count = 0;
    if (api->EnumeratePhysicalDevices(instance, &device_count, NULL) != VK_SUCCESS ||
        device_count == 0) {
        return false;
    }
    if (device_count > MAX_PROBE_DEVICES) device_count = MAX_PROBE_DEVICES;

    VkPhysicalDevice devices[MAX_PROBE_DEVICES];
    if (api->EnumeratePhysicalDevices(instance, &device_count, devices) != VK_SUCCESS) {
        return false;
    }

    for (uint32_t i = 0; i < device_count; i++) {
        VkPhysicalDeviceProperties properties;
        memset(&properties, 0, sizeof(properties));
        api->GetPhysicalDeviceProperties(devices[i], &properties);

        if (properties.apiVersion < VK_API_VERSION_1_3) {
            PROBE_LOGW("%s reports Vulkan %u.%u; SPIR-V 1.6 modules need 1.3",
                       properties.deviceName, VK_API_VERSION_MAJOR(properties.apiVersion),
                       VK_API_VERSION_MINOR(properties.apiVersion));
            continue;
        }
        if (!has_compute_queue(api, devices[i])) continue;
        if (!has_required_formats(api, devices[i])) continue;
        if (!has_required_features(api, devices[i])) continue;

        PROBE_LOGI("Frame generation supported on %s (Vulkan %u.%u)", properties.deviceName,
                   VK_API_VERSION_MAJOR(properties.apiVersion),
                   VK_API_VERSION_MINOR(properties.apiVersion));
        return true;
    }
    return false;
}

bool lsfg_probe_support(JNIEnv* env, jobject context, const char* driver_name) {
    void* library = winlator_open_vulkan(env, context, driver_name);
    if (!library) {
        PROBE_LOGW("Vulkan driver could not be opened for probing");
        return false;
    }

    ProbeApi api;
    if (!load_probe_api(library, &api)) {
        PROBE_LOGW("vkGetInstanceProcAddr unavailable in the selected driver");
        dlclose(library);
        return false;
    }

    VkApplicationInfo app_info;
    memset(&app_info, 0, sizeof(app_info));
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "GameNative";
    app_info.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo create_info;
    memset(&create_info, 0, sizeof(create_info));
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    if (api.CreateInstance(&create_info, NULL, &instance) != VK_SUCCESS) {
        app_info.apiVersion = VK_API_VERSION_1_1;
        if (api.CreateInstance(&create_info, NULL, &instance) != VK_SUCCESS) {
            PROBE_LOGW("vkCreateInstance failed during probe");
            dlclose(library);
            return false;
        }
    }

    bool supported = false;
    if (load_instance_api(&api, instance)) {
        supported = probe_instance(&api, instance);
    }

    if (api.DestroyInstance) api.DestroyInstance(instance, NULL);
    dlclose(library);

    if (!supported) PROBE_LOGI("Frame generation unsupported on this device");
    return supported;
}