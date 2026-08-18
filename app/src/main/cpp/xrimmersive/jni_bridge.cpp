#include <jni.h>
#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <mutex>
#include <unordered_set>

#include "xr_immersive.h"

namespace {

struct NativeHandle {
    xrimmersive::XrImmersiveSession *session;
    jobject activityGlobalRef;
};

// Lock ordering: gHandleMutex is the outermost lock — it may be held while calling into
// XrImmersiveSession (which takes its own inner mutexes), never the other way round, and it is
// never held across XrImmersiveSession::join() or AndroidBitmap_lockPixels/unlockPixels. Every
// entry point must hold it for the WHOLE call into the session, not just the lookup.
std::mutex gHandleMutex;
std::unordered_set<NativeHandle *> gLiveHandles;

NativeHandle *LiveHandle(jlong handlePtr) {
    auto *handle = reinterpret_cast<NativeHandle *>(handlePtr);
    if (handle == nullptr) return nullptr;
    return gLiveHandles.count(handle) != 0 ? handle : nullptr;
}

#define LOG_TAG "xrimmersive_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeCreate(JNIEnv *env, jclass, jobject activity) {
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);

    auto *handle = new NativeHandle();
    handle->activityGlobalRef = env->NewGlobalRef(activity);
    handle->session = new xrimmersive::XrImmersiveSession();
    handle->session->initialize(vm, handle->activityGlobalRef);
    {
        std::lock_guard<std::mutex> lock(gHandleMutex);
        gLiveHandles.insert(handle);
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeRequestStop(JNIEnv *, jclass, jlong handlePtr) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto *handle = LiveHandle(handlePtr);
    if (handle != nullptr) handle->session->requestStop();
}

JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeJoinAndDestroy(JNIEnv *env, jclass, jlong handlePtr) {
    auto *handle = reinterpret_cast<NativeHandle *>(handlePtr);
    if (handle == nullptr) return;
    {
        // Retiring the handle under the lock means every in-flight caller has already left its
        // critical section, and no new one can enter, before anything is destroyed.
        std::lock_guard<std::mutex> lock(gHandleMutex);
        if (gLiveHandles.erase(handle) == 0) return;
    }
    handle->session->join();
    delete handle->session;
    env->DeleteGlobalRef(handle->activityGlobalRef);
    delete handle;
}

// outButtons[0] receives the Xbox-layout button bitmask (see XrGamepadBridge's BUTTON_*);
// outAxes receives [leftX, leftY, rightX, rightY, triggerL, triggerR]. outHandPoses receives
// [leftX,leftY,leftZ, leftFwdX,leftFwdY,leftFwdZ, rightX,rightY,rightZ, rightFwdX,rightFwdY,
// rightFwdZ] (aim-pose ray per hand, in the quad's local space) — only meaningful when
// outFlags[0] (handPosesValid) is true. outFlags[1] is whether the XR-pointer-mode toggle
// (double-click of either thumbstick) fired since the last poll. outFlags[2] is the level state
// of the Menu/Start button — true for its entire physical hold, not just the rising edge — so
// Kotlin can suppress menu-navigation reads while the same hand may still be resting on/near the
// thumbstick. Returns whether the quick-menu trigger (Menu/Start held 600ms) fired since the last
// poll — a short press of the same button is still forwarded as a normal gamepad Start press,
// see xr_immersive.cpp's syncControllerInputs().
JNIEXPORT jboolean JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativePollSnapshot(JNIEnv *env, jclass, jlong handlePtr,
                                                              jintArray outButtons,
                                                              jfloatArray outAxes,
                                                              jfloatArray outHandPoses,
                                                              jbooleanArray outFlags) {
    xrimmersive::InputSnapshot snapshot;
    {
        std::lock_guard<std::mutex> lock(gHandleMutex);
        auto *handle = LiveHandle(handlePtr);
        if (handle == nullptr) return JNI_FALSE;
        snapshot = handle->session->pollSnapshot();
    }

    jint buttons[1] = {static_cast<jint>(snapshot.buttons)};
    env->SetIntArrayRegion(outButtons, 0, 1, buttons);

    jfloat axes[6] = {snapshot.leftX,   snapshot.leftY,    snapshot.rightX,
                       snapshot.rightY, snapshot.triggerL, snapshot.triggerR};
    env->SetFloatArrayRegion(outAxes, 0, 6, axes);

    jfloat poses[12] = {
        snapshot.leftHandPosX, snapshot.leftHandPosY, snapshot.leftHandPosZ,
        snapshot.leftHandFwdX, snapshot.leftHandFwdY, snapshot.leftHandFwdZ,
        snapshot.rightHandPosX, snapshot.rightHandPosY, snapshot.rightHandPosZ,
        snapshot.rightHandFwdX, snapshot.rightHandFwdY, snapshot.rightHandFwdZ,
    };
    env->SetFloatArrayRegion(outHandPoses, 0, 12, poses);

    jboolean flags[3] = {
        static_cast<jboolean>(snapshot.handPosesValid ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(snapshot.pointerModeToggled ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(snapshot.menuButtonHeld ? JNI_TRUE : JNI_FALSE),
    };
    env->SetBooleanArrayRegion(outFlags, 0, 3, flags);

    return snapshot.quickMenuClicked ? JNI_TRUE : JNI_FALSE;
}

// Called from ImmersiveXrActivity's PixelCopy capture loop with the game's actual rendered
// frame (ARGB_8888 bitmap). Copies the pixels into the session's pending-frame buffer; the
// render thread uploads them to the GPU and draws them into the quad layer on its own.
JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeSubmitFrame(JNIEnv *env, jclass, jlong handlePtr,
                                                             jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;

    {
        std::lock_guard<std::mutex> lock(gHandleMutex);
        auto *handle = LiveHandle(handlePtr);
        if (handle != nullptr) {
            handle->session->submitFrame(static_cast<const uint8_t *>(pixels),
                                          static_cast<int32_t>(info.width),
                                          static_cast<int32_t>(info.height),
                                          static_cast<int32_t>(info.stride));
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeSetQuadTransform(JNIEnv *, jclass, jlong handlePtr,
                                                                  jfloat x, jfloat y, jfloat z,
                                                                  jfloat width, jfloat height,
                                                                  jfloat contentScaleX,
                                                                  jfloat contentScaleY) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto *handle = LiveHandle(handlePtr);
    if (handle == nullptr) return;
    handle->session->setQuadTransform(x, y, z, width, height, contentScaleX, contentScaleY);
}

JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeSetPassthroughEnabled(JNIEnv *, jclass,
                                                                       jlong handlePtr,
                                                                       jboolean enabled) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto *handle = LiveHandle(handlePtr);
    if (handle == nullptr) return;
    handle->session->setPassthroughEnabled(enabled == JNI_TRUE);
}

// See DirectGLBridge.kt / GLRenderer.XrFrameBridge — hardwareBuffer is the same
// android.hardware.HardwareBuffer GLRenderer is writing into on its own thread/context.
JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeSetSharedGameBuffer(JNIEnv *env, jclass,
                                                                     jlong handlePtr,
                                                                     jobject hardwareBuffer) {
    if (hardwareBuffer == nullptr) return;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buffer == nullptr) return;
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto *handle = LiveHandle(handlePtr);
    if (handle == nullptr) return;
    handle->session->setSharedGameBuffer(buffer);
}

// VulkanRenderer's zero-copy scanout path (DirectVulkanBridge.kt / VulkanXrFrameBridge) already
// has the game frame as a raw AHardwareBuffer* pointer (no android.hardware.HardwareBuffer Java
// object involved at all — VulkanRenderer.onUpdateWindowContent gets it straight from native
// GPUImage.getHardwareBufferPtr()), so this variant skips the Java-object-unwrapping step and
// reinterprets the pointer directly. Same underlying session method as the GL path above —
// setSharedGameBuffer()/importSharedBufferIfNeeded() don't care which renderer produced the
// buffer.
JNIEXPORT void JNICALL
Java_app_gamenative_ui_screen_xr_XrNative_nativeSetSharedGameBufferPtr(JNIEnv *, jclass,
                                                                        jlong handlePtr,
                                                                        jlong ahbPtr) {
    if (ahbPtr == 0) return;
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto *handle = LiveHandle(handlePtr);
    if (handle == nullptr) return;
    handle->session->setSharedGameBuffer(reinterpret_cast<AHardwareBuffer *>(ahbPtr));
}

// GLRenderer's direct-render path (DirectGLBridge.kt) needs to import the same HardwareBuffer as
// a GL texture in ITS OWN thread/context (a different context than the XR session's own thread
// above) — the public Android SDK has no Java binding for eglGetNativeClientBufferANDROID /
// eglCreateImageKHR (GLES11Ext's Java binding still takes a raw java.nio.Buffer, a legacy
// pre-HardwareBuffer calling convention), so this stateless helper does the same native EGLImage
// import dance and hands back a plain GL texture name the Java side can use normally. Must be
// called with an EGL context already current on the calling thread.
JNIEXPORT jint JNICALL
Java_com_winlator_renderer_GLHardwareBufferImporter_importAsTexture(JNIEnv *env, jclass,
                                                                      jobject hardwareBuffer) {
    if (hardwareBuffer == nullptr) return 0;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buffer == nullptr) return 0;

    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) {
        LOGE("GLHardwareBufferImporter: no current EGL display on this thread");
        return 0;
    }

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
    if (clientBuffer == nullptr) {
        LOGE("GLHardwareBufferImporter: eglGetNativeClientBufferANDROID failed");
        return 0;
    }

    const EGLint attrs[] = {EGL_NONE};
    EGLImageKHR image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                           clientBuffer, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("GLHardwareBufferImporter: eglCreateImageKHR failed (0x%x)", eglGetError());
        return 0;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    // The texture keeps the buffer's contents alive on the driver side; the EGLImage itself can
    // be destroyed right after the glEGLImageTargetTexture2DOES call, same pattern used by
    // Camera2/MediaCodec consumers.
    eglDestroyImageKHR(display, image);

    LOGI("GLHardwareBufferImporter: imported HardwareBuffer as GL texture %u", texture);
    return static_cast<jint>(texture);
}

}  // extern "C"
