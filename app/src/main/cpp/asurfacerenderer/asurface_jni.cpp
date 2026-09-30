#include <jni.h>
#include <android/native_window_jni.h>
#include <unistd.h>
#include "ASurfaceRendererContext.h"
#include <mutex>
#include <shared_mutex>

static JavaVM* g_javaVm = nullptr;
static ASurfaceRendererContext* g_ctx = nullptr;
static std::shared_mutex g_ctxMutex;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT bool JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeInit(
        JNIEnv* env, jobject, jobject surface, jint w, jint h)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return false;

    ASurfaceRendererContext* old;
    {
        std::unique_lock lk(g_ctxMutex);
        auto* ctx = new ASurfaceRendererContext(win, w, h);
        ctx->javaVm = g_javaVm;
        old = g_ctx;
        g_ctx = ctx;
    }
    delete old;

    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeDestroy(JNIEnv*, jobject) {
    std::unique_lock lk(g_ctxMutex);
    auto* ctx = g_ctx;
    if (!ctx) return;
    ctx->beginShutdown();
    g_ctx = nullptr;
    delete ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeInitScanout(JNIEnv*, jobject) {
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->initScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeDestroyScanout(JNIEnv*, jobject) {
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->destroyScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeSetSfCallbackTarget(
        JNIEnv* env, jobject, jobject rendererRef)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->setSfCallbackTarget(env, rendererRef);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorImage(
        JNIEnv* env, jobject, jobject buf, jshort w, jshort h, jshort stride)
{
    if (!buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf) >= (jlong)w * h * 4) {
        std::shared_lock lk(g_ctxMutex);
        if (auto* r = g_ctx) r->scanoutSetCursorImage(px, w, h, stride);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorPos(
        JNIEnv*, jobject, jshort x, jshort y, jshort hotX, jshort hotY, jboolean cursorVisible)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->scanoutSetCursorPos(x, y, hotX, hotY, cursorVisible);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetDst(
        JNIEnv*, jobject, jint x, jint y, jint w, jint h)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->scanoutSetDst(x, y, w, h);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeReattachSurface(JNIEnv* env, jobject, jobject surface) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = false;
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) {
        ok = r->reattachSurface(win);
        if (ok && r->scanoutActive.load()) {
            r->destroyScanout();
        }
    } else ANativeWindow_release(win);
    return (jboolean)ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeRegisterWindowSC(
        JNIEnv* env, jobject, jlong contentId, jstring debugName)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) {
        const char* name = nullptr;
        if (debugName) name = env->GetStringUTFChars(debugName, nullptr);
        r->registerWindowSC((int64_t)contentId, name ? name : "(x11_window)");
        if (name) env->ReleaseStringUTFChars(debugName, name);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeUnregisterWindowSC(
        JNIEnv*, jobject, jlong contentId)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->unregisterWindowSC((int64_t)contentId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorVisibility(
        JNIEnv*, jobject, jboolean visible)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->scanoutSetCursorVisibility(visible);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativePrepareCpuSourceBuffers(
        JNIEnv*, jclass, jlong buffer0, jlong buffer1, jlong buffer2)
{
    std::shared_lock lk(g_ctxMutex);
    auto* r = g_ctx;
    if (!r) return JNI_FALSE;

    const bool ok = r->prepareCpuSourceBuffers(
            reinterpret_cast<AHardwareBuffer*>(buffer0),
            reinterpret_cast<AHardwareBuffer*>(buffer1),
            reinterpret_cast<AHardwareBuffer*>(buffer2));
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeReleaseCpuSourceBuffers(
        JNIEnv*, jclass, jlong buffer0, jlong buffer1, jlong buffer2)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) {
        r->releaseCpuSourceBuffers(
                reinterpret_cast<AHardwareBuffer*>(buffer0),
                reinterpret_cast<AHardwareBuffer*>(buffer1),
                reinterpret_cast<AHardwareBuffer*>(buffer2));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeSetWindowBuffer(
        JNIEnv* env, jobject, jlong contentId,
        jlong ahbPtr, jint fenceFd, jlong windowId, jlong serial, jobject ahbImage, jint slot, jboolean sfCompatMode)
{
    std::shared_lock lk(g_ctxMutex);
    auto* r = g_ctx;
    if (!r || !ahbPtr) { if (fenceFd >= 0) close(fenceFd); return; }

    r->setWindowBuffer(
            env,
            (int64_t)contentId,
            reinterpret_cast<AHardwareBuffer*>(ahbPtr),
            (int)fenceFd,
            (int64_t)windowId,
            (int64_t)serial,
            ahbImage,
            (int)slot,
            (bool)sfCompatMode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeBeginTransaction(JNIEnv*, jobject) {
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->beginTransaction();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeApplyTransaction(JNIEnv*, jobject) {
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) r->applyTransaction();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeUpdateWindow(
        JNIEnv*, jobject, jlong contentId, jboolean visible, jint zOrder,
        jint srcL, jint srcT, jint srcR, jint srcB,
        jint dstL, jint dstT, jint dstR, jint dstB)
{
    std::shared_lock lk(g_ctxMutex);
    if (auto* r = g_ctx) {
        r->updateWindow((int64_t)contentId, visible, zOrder, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB);
    }
}
