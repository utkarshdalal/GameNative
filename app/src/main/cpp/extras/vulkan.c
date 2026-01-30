#include <vulkan/vulkan.h>

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include "adrenotools/include/adrenotools/driver.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

VkInstance instance;
VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;


static char *get_native_library_dir(JNIEnv *env, jobject context) {
    char *native_libdir = NULL;

    if (context != NULL) {
        jclass class_ = (*env)->FindClass(env,"com/winlator/core/AppUtils");
        jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(env, class_, "getNativeLibDir",
                                                                  "(Landroid/content/Context;)Ljava/lang/String;");
        jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(env, class_,
                                                                       getNativeLibraryDir,
                                                                       context);
        if (nativeLibDir) {
            const char *native_libdir_chars = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);
            if (native_libdir_chars) {
                native_libdir = strdup(native_libdir_chars);
                (*env)->ReleaseStringUTFChars(env, nativeLibDir, native_libdir_chars);
            }
        }
    }

    return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context, const char *driver_name) {
    char *driver_path = NULL;
    const char *absolute_path_chars = NULL;

    jclass contextWrapperClass = (*env)->FindClass(env, "android/content/ContextWrapper");
    jmethodID  getFilesDir = (*env)->GetMethodID(env, contextWrapperClass, "getFilesDir", "()Ljava/io/File;");
    jobject  filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
    jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
    jmethodID getAbsolutePath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring absolutePath = (jstring)(*env)->CallObjectMethod(env,filesDirObj,
                                                             getAbsolutePath);

    if (absolutePath) {
        absolute_path_chars = (*env)->GetStringUTFChars(env,absolutePath, NULL);
        if (absolute_path_chars) {
            if (asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path_chars, driver_name) == -1)
                driver_path = NULL;
            (*env)->ReleaseStringUTFChars(env,absolutePath, absolute_path_chars);
        }
    }

    return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context, const char *driver_name) {
    char *library_name = NULL;

    jclass adrenotoolsManager = (*env)->FindClass(env, "com/winlator/contents/AdrenotoolsManager");
    jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>", "(Landroid/content/Context;)V");
    jobject  adrenotoolsManagerObj = (*env)->NewObject(env, adrenotoolsManager, constructor, context);
    jmethodID getLibraryName = (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName","(Ljava/lang/String;)Ljava/lang/String;");
    jstring driverName = (*env)->NewStringUTF(env, driver_name);
    jstring libraryName = (jstring)(*env)->CallObjectMethod(env, adrenotoolsManagerObj,getLibraryName, driverName);

    if (libraryName) {
        const char *library_name_chars = (*env)->GetStringUTFChars(env, libraryName, NULL);
        if (library_name_chars) {
            library_name = strdup(library_name_chars);
            (*env)->ReleaseStringUTFChars(env, libraryName, library_name_chars);
        }
    }

    return library_name;
}

static void init_original_vulkan() {
    vulkan_handle = dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
}

static void init_vulkan(JNIEnv  *env, jobject context, const char *driver_name) {
    char *tmpdir = NULL;
    char *library_name = NULL;
    char *native_library_dir = NULL;

    char *driver_path = get_driver_path(env, context, driver_name);

    if (driver_path && (access(driver_path, F_OK) == 0)) {
        library_name = get_library_name(env, context, driver_name);
        native_library_dir = get_native_library_dir(env, context);
        char *tmpdir_buffer = NULL;
        if (asprintf(&tmpdir_buffer, "%s%s", driver_path, "temp") != -1) {
            tmpdir = tmpdir_buffer;
            mkdir(tmpdir, S_IRWXU | S_IRWXG);
        }
    }

    vulkan_handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpdir, native_library_dir, driver_path, library_name, NULL, NULL);

    free(tmpdir);
    free(library_name);
    free(native_library_dir);
    free(driver_path);
}

static VkResult create_instance(jstring driverName, JNIEnv *env, jobject context) {
    VkResult result;
    VkInstanceCreateInfo create_info = {};
    const char *driver_name = NULL;
    VkResult status = VK_SUCCESS;

    if (driverName != NULL)
        driver_name = (*env)->GetStringUTFChars(env, driverName, NULL);

    if (driver_name && strcmp(driver_name, "System"))
        init_vulkan(env, context, driver_name);
    else
        init_original_vulkan();

    if (!vulkan_handle) {
        status = VK_ERROR_INITIALIZATION_FAILED;
        goto cleanup;
    }

    PFN_vkGetInstanceProcAddr gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");

    if (!gip || !createInstance) {
        status = VK_ERROR_INITIALIZATION_FAILED;
        goto cleanup;
    }

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Winlator";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "Winlator";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;

    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pNext = NULL;
    create_info.flags = 0;
    create_info.pApplicationInfo = &app_info;
    create_info.enabledLayerCount = 0;
    create_info.enabledExtensionCount = 0;

    result = createInstance(&create_info, NULL, &instance);

    if (result != VK_SUCCESS) {
        status = result;
        goto cleanup;
    }

    getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
    destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
    enumerateDeviceExtensionProperties = (PFN_vkEnumerateDeviceExtensionProperties)gip(instance, "vkEnumerateDeviceExtensionProperties");
    enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");

    if (!getPhysicalDeviceProperties || !destroyInstance || !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices) {
        status = VK_ERROR_INITIALIZATION_FAILED;
        goto cleanup;
    }

    status = VK_SUCCESS;

cleanup:
    if (driver_name)
        (*env)->ReleaseStringUTFChars(env, driverName, driver_name);

    return status;
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
Java_com_winlator_core_GPUInformation_getVulkanVersion(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *driverVersion = NULL;
    jstring versionString = NULL;
    const char *unknown = "Unknown";

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        versionString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        versionString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    uint32_t api_version_major = VK_VERSION_MAJOR(props.apiVersion);
    uint32_t api_version_minor = VK_VERSION_MINOR(props.apiVersion);
    uint32_t api_version_patch = VK_VERSION_PATCH(props.apiVersion);
    if (asprintf(&driverVersion, "%d.%d.%d", api_version_major, api_version_minor, api_version_patch) == -1) {
        printf("Failed to build version string");
        versionString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    versionString = (*env)->NewStringUTF(env, driverVersion);

cleanup:
    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
        instance = VK_NULL_HANDLE;
    }
    physicalDevice = VK_NULL_HANDLE;

    if (vulkan_handle) {
        dlclose(vulkan_handle);
        vulkan_handle = NULL;
    }

    if (driverVersion)
        free(driverVersion);

    if (!versionString)
        versionString = (*env)->NewStringUTF(env, unknown);

    return versionString;
}

JNIEXPORT jint JNICALL
Java_com_winlator_core_GPUInformation_getVendorID(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    uint32_t vendorID = 0;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        goto cleanup;
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        goto cleanup;
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    vendorID = props.vendorID;

cleanup:
    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
        instance = VK_NULL_HANDLE;
    }
    physicalDevice = VK_NULL_HANDLE;

    if (vulkan_handle) {
        dlclose(vulkan_handle);
        vulkan_handle = NULL;
    }

    return vendorID;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_core_GPUInformation_getRenderer(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *renderer = NULL;
    jstring rendererString = NULL;
    const char *unknown = "Unknown";


    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        rendererString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        rendererString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    renderer = strdup(props.deviceName);
    if (!renderer) {
        printf("Failed to copy renderer name");
        rendererString = (*env)->NewStringUTF(env, unknown);
        goto cleanup;
    }

    rendererString = (*env)->NewStringUTF(env, renderer);

cleanup:
    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
        instance = VK_NULL_HANDLE;
    }
    physicalDevice = VK_NULL_HANDLE;

    if (vulkan_handle) {
        dlclose(vulkan_handle);
        vulkan_handle = NULL;
    }

    if (renderer)
        free(renderer);

    if (!rendererString)
        rendererString = (*env)->NewStringUTF(env, unknown);

    return rendererString;
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_core_GPUInformation_enumerateExtensions(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    jobjectArray extensions = NULL;
    VkResult result;
    uint32_t extensionCount;
    VkExtensionProperties *extensionProperties = NULL;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");

    if (!stringClass)
        return NULL;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        goto cleanup;
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        goto cleanup;
    }

    result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL);

    if (result != VK_SUCCESS || extensionCount < 1) {
        printf("Failed to query extension count");
        goto cleanup;
    }

    extensionProperties = malloc(sizeof(VkExtensionProperties) * extensionCount);
    if (!extensionProperties) {
        printf("Failed to allocate extension properties buffer");
        goto cleanup;
    }

    result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount,
                                                extensionProperties);

    if (result != VK_SUCCESS) {
        printf("Failed to query extensions");
        goto cleanup;
    }

    extensions = (jobjectArray) (*env)->NewObjectArray(env, extensionCount,
                                                       stringClass,
                                                       NULL);
    for (int i = 0; i < extensionCount; i++) {
        (*env)->SetObjectArrayElement(env, extensions, i,
                                      (*env)->NewStringUTF(env, extensionProperties[i].extensionName));
    }

cleanup:
    if (extensionProperties)
        free(extensionProperties);

    if (destroyInstance && instance != VK_NULL_HANDLE) {
        destroyInstance(instance, NULL);
        instance = VK_NULL_HANDLE;
    }

    if (vulkan_handle) {
        dlclose(vulkan_handle);
        vulkan_handle = NULL;
    }

    physicalDevice = VK_NULL_HANDLE;

    if (!extensions)
        extensions = (*env)->NewObjectArray(env, 0, stringClass, NULL);

    return extensions;
}
