#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include "ASurfaceRendererContext.h"
#include <unordered_map>

static JavaVM* g_javaVm = nullptr;
struct PresentedCallback {
    JavaVM*  jvm;
    jobject  rendererObj; // global ref to ASurfaceRenderer instance
    jlong    contentId;
    jlong    serial;
};
static std::atomic<ASurfaceRendererContext*> g_ctx{nullptr};

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

    auto* ctx = new ASurfaceRendererContext(win, w, h);
    ctx->javaVm = g_javaVm;
    auto* old = g_ctx.exchange(ctx, std::memory_order_acq_rel);
    delete old;
    g_ctx.store(ctx, std::memory_order_release);

    return true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeDestroy(JNIEnv*, jobject) {
    auto* ctx = g_ctx.exchange(nullptr, std::memory_order_acq_rel);
    delete ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeInitScanout(JNIEnv*, jobject) {
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->initScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeDestroyScanout(JNIEnv*, jobject) {
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->destroyScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeSetSfCallbackTarget(
        JNIEnv* env, jobject, jobject rendererRef)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->setSfCallbackTarget(env, rendererRef);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorImage(
        JNIEnv* env, jobject, jobject buf, jshort w, jshort h, jshort stride)
{
    if (!buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf) >= (jlong)w*h*4)
        if (auto* r = g_ctx.load(std::memory_order_acquire)) r->scanoutSetCursorImage(px, w, h, stride);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorPos(
        JNIEnv*, jobject, jshort x, jshort y, jshort hotX, jshort hotY)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->scanoutSetCursorPos(x, y, hotX, hotY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetDst(
        JNIEnv*, jobject, jint x, jint y, jint w, jint h)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->scanoutSetDst(x, y, w, h);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeReattachSurface(JNIEnv* env, jobject, jobject surface) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = false;
    if (auto* r = g_ctx.load(std::memory_order_acquire)) {
        ok = r->reattachSurface(win);
        if (ok && r->scanoutActive.load()) {
            r->destroyScanout();
        }
    }
    return (jboolean)ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeRegisterWindowSC(
        JNIEnv* env, jobject, jlong contentId)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->registerWindowSC((int64_t)contentId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeUnregisterWindowSC(
        JNIEnv*, jobject, jlong contentId)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->unregisterWindowSC((int64_t)contentId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeScanoutSetCursorVisibility(
        JNIEnv*, jobject, jboolean visible)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->scanoutSetCursorVisibility(visible);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeSetWindowBuffer(
        JNIEnv*, jobject, jlong contentId,
        jlong ahbPtr, jint fenceFd, jlong windowId, jlong serial)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) {
        if (ahbPtr) {
            r->setWindowBuffer(
                    (int64_t) contentId,
                    reinterpret_cast<AHardwareBuffer *>(ahbPtr),
                    (int) fenceFd,
                    (int64_t) windowId,
                    (int64_t) serial);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeBeginTransaction(JNIEnv*, jobject) {
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->beginTransaction();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeApplyTransaction(JNIEnv*, jobject) {
    if (auto* r = g_ctx.load(std::memory_order_acquire)) r->applyTransaction();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_ASurfaceRenderer_nativeUpdateWindow(
        JNIEnv*, jobject, jlong contentId, jboolean visible, jint zOrder,
        jint srcL, jint srcT, jint srcR, jint srcB,
        jint dstL, jint dstT, jint dstR, jint dstB)
{
    if (auto* r = g_ctx.load(std::memory_order_acquire)) {
        r->updateWindow((int64_t)contentId, visible, zOrder, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB);
    }
}
