#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <unistd.h>
#include <stdint.h>
#include <string.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#define LOG_TAG "AHBImage"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,  LOG_TAG, __VA_ARGS__)

#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5

EGLImageKHR createImageKHR(AHardwareBuffer* hardwareBuffer, int textureId) {
    if (!hardwareBuffer) {
        LOGE("createImageKHR: Invalid AHardwareBuffer pointer\n");
        return NULL;
    }

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    AHardwareBuffer_acquire(hardwareBuffer);

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        LOGE("Failed to get native client buffer\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        LOGE("Invalid EGLDisplay\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) {
        LOGE("Failed to create EGLImageKHR\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("Failed to bind texture\n");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imageKHR);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("Failed to bind EGLImage to texture\n");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, 0);

    return imageKHR;
}

static void dump_ahb_usage(uint64_t usage)
{
    LOGD("AHB usage=0x%016" PRIx64 "\n", usage);

    if ((usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK) ==
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY)
        LOGD("  CPU_READ_RARELY\n");

    if ((usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK) ==
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN)
        LOGD("  CPU_READ_OFTEN\n");

    if ((usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK) ==
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY)
        LOGD("  CPU_WRITE_RARELY\n");

    if ((usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK) ==
        AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN)
        LOGD("  CPU_WRITE_OFTEN\n");

    if (usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)
        LOGD("  GPU_SAMPLED_IMAGE\n");

    if (usage & AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER)
        LOGD("  GPU_FRAMEBUFFER\n");

    if (usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY)
        LOGD("  COMPOSER_OVERLAY\n");

    if (usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT)
        LOGD("  PROTECTED_CONTENT\n");

    if (usage & AHARDWAREBUFFER_USAGE_VIDEO_ENCODE)
        LOGD("  VIDEO_ENCODE\n");

    if (usage & AHARDWAREBUFFER_USAGE_SENSOR_DIRECT_DATA)
        LOGD("  SENSOR_DIRECT_DATA\n");

    if (usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER)
        LOGD("  GPU_DATA_BUFFER\n");

    if (usage & AHARDWAREBUFFER_USAGE_GPU_CUBE_MAP)
        LOGD("  GPU_CUBE_MAP\n");

    if (usage & AHARDWAREBUFFER_USAGE_GPU_MIPMAP_COMPLETE)
        LOGD("  GPU_MIPMAP_COMPLETE\n");

    if (usage & AHARDWAREBUFFER_USAGE_FRONT_BUFFER)
        LOGD("  FRONT_BUFFER\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_0)
        LOGD("  VENDOR_0\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_1)
        LOGD("  VENDOR_1\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_2)
        LOGD("  VENDOR_2\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_3)
        LOGD("  VENDOR_3\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_4)
        LOGD("  VENDOR_4\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_5)
        LOGD("  VENDOR_5\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_6)
        LOGD("  VENDOR_6\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_7)
        LOGD("  VENDOR_7\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_8)
        LOGD("  VENDOR_8\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_9)
        LOGD("  VENDOR_9\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_10)
        LOGD("  VENDOR_10\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_11)
        LOGD("  VENDOR_11\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_12)
        LOGD("  VENDOR_12\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_13)
        LOGD("  VENDOR_13\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_14)
        LOGD("  VENDOR_14\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_15)
        LOGD("  VENDOR_15\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_16)
        LOGD("  VENDOR_16\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_17)
        LOGD("  VENDOR_17\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_18)
        LOGD("  VENDOR_18\n");

    if (usage & AHARDWAREBUFFER_USAGE_VENDOR_19)
        LOGD("  VENDOR_19\n");

    LOGD("===");
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_hardwareBufferFromSocket(
        JNIEnv *env, jobject obj, jint fd)
{
    uint8_t ready = 1;
    if (write((int)fd, &ready, 1) != 1) {
        LOGE("nativeHardwareBufferFromSocket: write handshake failed");
        return 0;
    }
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_recvHandleFromUnixSocket((int)fd, &ahb) != 0) {
        LOGE("nativeHardwareBufferFromSocket: recvHandle failed");
        return 0;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);

    LOGD("RemoteAHB: format=%u width=%u height=%u layers=%u\n",
         desc.format,
         desc.width,
         desc.height,
         desc.layers);

    dump_ahb_usage(desc.usage);

    return (jlong)(uintptr_t)ahb;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_copyHardwareBuffer(
        JNIEnv *env, jobject obj, jobject srcBuffer, jlong dstPtr, jshort width, jshort height, jshort srcStride)
{
    uint32_t* srcAddr = (uint32_t*)(*env)->GetDirectBufferAddress(env, srcBuffer);
    AHardwareBuffer *dstAhb = (AHardwareBuffer *)(uintptr_t)dstPtr;
    if (!srcAddr || !dstAhb) return;

    void *dstAddrVoid = NULL;
    if (AHardwareBuffer_lock(dstAhb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &dstAddrVoid) == 0) {
        uint32_t* dstAddr = (uint32_t*)dstAddrVoid;
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(dstAhb, &desc);
        uint32_t dstStride = desc.stride;

        if (dstStride == (uint32_t)srcStride) {
            memcpy(dstAddr, srcAddr, (size_t)dstStride * height * 4);
        } else {
            for (int y = 0; y < height; y++) {
                memcpy(dstAddr + y * dstStride, srcAddr + y * srcStride, width * 4);
            }
        }

        AHardwareBuffer_unlock(dstAhb, NULL);
    } else {
        LOGE("nativeCopyHardwareBuffer: lock failed");
    }
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createHardwareBuffer(
        JNIEnv *env, jobject obj, jshort width, jshort height)
{
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    desc.width  = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
    desc.usage  = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                  | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;

    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0) {
        LOGE("nativeCreateHardwareBuffer: alloc failed (%u x %u)", desc.width, desc.height);
        return 0;
    }
    LOGD("nativeCreateHardwareBuffer: %u x %u -> %p", desc.width, desc.height, (void*)ahb);

    AHardwareBuffer_Desc rdesc;
    AHardwareBuffer_describe(ahb, &rdesc);

    LOGD("LocalAHB: format=%u width=%u height=%u layers=%u\n",
         rdesc.format,
         rdesc.width,
         rdesc.height,
         rdesc.layers);

    dump_ahb_usage(rdesc.usage);
    return (jlong)(uintptr_t)ahb;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyHardwareBuffer(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    if (ahb) {
        LOGD("nativeDestroyHardwareBuffer: %p", (void*)ahb);
        AHardwareBuffer_release(ahb);
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_GPUImage_unlockHardwareBuffer(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    if (!ahb) return -1;

    int fence_fd = -1;
    int result = AHardwareBuffer_unlock(ahb, &fence_fd);
    if (result != 0) {
        LOGE("nativeUnlockHardwareBuffer: unlock failed: %d", result);
        return -1;
    }
    return (jint)fence_fd;
}

JNIEXPORT jobject JNICALL
Java_com_winlator_renderer_GPUImage_lockHardwareBuffer(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)ptr;
    if (!hardwareBuffer) {
        LOGE("Invalid AHardwareBuffer pointer\n");
        return NULL;
    }

    void *virtualAddr;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &virtualAddr) != 0) {
        LOGE("Failed to lock AHardwareBuffer\n");
        return NULL;
    }

    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        LOGE("Failed to get Java class reference\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        LOGE("Failed to get setStride method ID\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);

    jlong size = buffDesc.stride * buffDesc.height * 4;
    jobject buffer = (*env)->NewDirectByteBuffer(env, virtualAddr, size);
    if (buffer == NULL) {
        LOGE("Failed to create Java ByteBuffer\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }

    return buffer;
}

JNIEXPORT jshort JNICALL
Java_com_winlator_renderer_GPUImage_getStride(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    if (!ahb) return -1;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);
    return (jshort)desc.stride;
}

JNIEXPORT jshort JNICALL
Java_com_winlator_renderer_GPUImage_nativeGetWidth(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    if (!ahb) return -1;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);
    return (jshort)desc.width;
}

JNIEXPORT jshort JNICALL
Java_com_winlator_renderer_GPUImage_nativeGetHeight(
        JNIEnv *env, jobject obj, jlong ptr)
{
    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    if (!ahb) return -1;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);
    return (jshort)desc.height;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyImageKHR(JNIEnv *env, jclass obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}

// JNI method to create an EGL image
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createImageKHR(JNIEnv *env, jclass obj, jlong hardwareBufferPtr, jint textureId) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) {
        LOGE("Invalid AHardwareBuffer pointer\n");
        return 0;
    }
    return (jlong)createImageKHR(hardwareBuffer, textureId);
}
