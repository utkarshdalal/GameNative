#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include "../extras/adrenotools/include/adrenotools/driver.h"
#include "VulkanRendererContext.h"

static void* openAdrenotoolsDriver(const char* driverPath, const char* libraryName, const char* nativeLibDir) {
    if (!driverPath || !libraryName || !nativeLibDir) return nullptr;
    if (access(driverPath, F_OK) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: driverPath not accessible: %s", driverPath);
        return nullptr;
    }
    char tmpdir[512];
    snprintf(tmpdir, sizeof(tmpdir), "%stemp", driverPath);
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
        "openAdrenotoolsDriver: driverPath=%s lib=%s nativeLibDir=%s tmp=%s",
        driverPath, libraryName, nativeLibDir, tmpdir);
    setenv("ADRENOTOOLS_DRIVER_PATH", driverPath, 1);
    setenv("ADRENOTOOLS_DRIVER_NAME", libraryName, 1);
    setenv("ADRENOTOOLS_HOOKS_PATH", nativeLibDir, 1);
    const char* redirectDir = getenv("ADRENOTOOLS_REDIRECT_DIR");
    int featureFlags = ADRENOTOOLS_DRIVER_CUSTOM;
    if (redirectDir && redirectDir[0] != '\0') {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    } else {
        unsetenv("ADRENOTOOLS_DRIVER_FILE_REDIRECT");
    }
    void* handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW,
        featureFlags,
        tmpdir,
        nativeLibDir,
        driverPath,
        libraryName,
        (redirectDir && redirectDir[0] != '\0') ? redirectDir : nullptr,
        nullptr);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: adrenotools_open_libvulkan failed");
    } else {
        __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
            "openAdrenotoolsDriver: SUCCESS handle=%p", handle);
    }
    return handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeInit(
    JNIEnv* env, jobject, jobject surface, jint w, jint h,
    jstring jDriverPath, jstring jLibraryName, jstring jNativeLibDir)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;
    void* adrenotoolsHandle = nullptr;
    if (jDriverPath && jLibraryName && jNativeLibDir) {
        const char* dp  = env->GetStringUTFChars(jDriverPath,   nullptr);
        const char* lib = env->GetStringUTFChars(jLibraryName,  nullptr);
        const char* nld = env->GetStringUTFChars(jNativeLibDir, nullptr);
        adrenotoolsHandle = openAdrenotoolsDriver(dp, lib, nld);
        env->ReleaseStringUTFChars(jDriverPath,   dp);
        env->ReleaseStringUTFChars(jLibraryName,  lib);
        env->ReleaseStringUTFChars(jNativeLibDir, nld);
    }
    try { return reinterpret_cast<jlong>(new VulkanRendererContext(win, w, h, adrenotoolsHandle)); }
    catch (...) {
        ANativeWindow_release(win);
        if (adrenotoolsHandle) dlclose(adrenotoolsHandle);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeResize(JNIEnv*, jobject, jlong h, jint w, jint ht) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(h); if (r) r->onSurfaceResized(w,ht);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<VulkanRendererContext*>(h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateWindowContent(
    JNIEnv* env, jobject, jlong handle, jlong id, jobject buf, jshort w, jshort h, jshort stride, jint x, jint y)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||!buf) return;
    void* px=env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf)>=(jlong)w*h*4)
        r->updateWindowContent(id,px,w,h,stride,x,y);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateWindowContentAHB(
    JNIEnv*, jobject, jlong handle, jlong id, jlong ahbPtr, jshort w, jshort h, jint x, jint y)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (r&&ahbPtr) r->updateWindowContentAHB(id,reinterpret_cast<AHardwareBuffer*>(ahbPtr),w,h,x,y);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetTransform(
    JNIEnv*, jobject, jlong handle, jfloat ox, jfloat oy, jfloat sx, jfloat sy)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->setTransform(ox,oy,sx,sy);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetPointerPos(JNIEnv*, jobject, jlong handle, jshort x, jshort y) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->updatePointerPosition(x,y);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetCursorVisible(JNIEnv*, jobject, jlong handle, jboolean v) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->setCursorVisible(v);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf, jshort w, jshort h, jshort hotX, jshort hotY)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||!buf) return;
    void* px=env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf)>=(jlong)w*h*4)
        r->updateCursorImage(px,w,h,hotX,hotY);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetRenderList(
    JNIEnv* env, jobject, jlong handle, jlongArray jids, jintArray jxs, jintArray jys, jint count)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||count<=0) return;

    jlong* ids=(jlong*)env->GetPrimitiveArrayCritical(jids,nullptr);
    jint*  xs =(jint*) env->GetPrimitiveArrayCritical(jxs, nullptr);
    jint*  ys =(jint*) env->GetPrimitiveArrayCritical(jys, nullptr);
    r->setRenderList(reinterpret_cast<const int64_t*>(ids),xs,ys,count);
    env->ReleasePrimitiveArrayCritical(jys, ys,  JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jxs, xs,  JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jids,ids, JNI_ABORT);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeRemoveWindow(JNIEnv*, jobject, jlong handle, jlong id) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->removeWindow(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeInitScanout(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->initScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDestroyScanout(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->destroyScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetBuffer(
    JNIEnv*, jobject, jlong handle, jlong ahbPtr, jint x, jint y, jint w, jint h, jint fenceFd)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r && ahbPtr) r->scanoutSetBuffer(reinterpret_cast<AHardwareBuffer*>(ahbPtr), x, y, w, h, (int)fenceFd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf, jshort w, jshort h, jshort stride)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf) >= (jlong)w*h*4)
        r->scanoutSetCursorImage(px, w, h, stride);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetCursorPos(
    JNIEnv*, jobject, jlong handle, jshort x, jshort y, jshort hotX, jshort hotY)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->scanoutSetCursorPos(x, y, hotX, hotY);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeIsScanoutActive(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return r ? (jboolean)r->scanoutActive.load() : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetDst(
    JNIEnv*, jobject, jlong handle, jint x, jint y, jint w, jint h)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->scanoutSetDst(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetScanoutWindow(
    JNIEnv* env, jobject, jlong handle, jobject gameSurface, jobject cursorSurface)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    ANativeWindow* gw = ANativeWindow_fromSurface(env, gameSurface);
    ANativeWindow* cw = ANativeWindow_fromSurface(env, cursorSurface);
    if (!gw || !cw) {
        if (gw) ANativeWindow_release(gw);
        if (cw) ANativeWindow_release(cw);
        r->initScanout();
        return;
    }
    r->initScanoutFromWindows(gw, cw);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetVerboseLog(JNIEnv*, jobject, jlong handle, jboolean v) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setVerboseLog((bool)v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDumpRendererInfo(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->dumpRendererInfo();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeIsGameFrameDelivered(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return r ? (jboolean)r->gameFrameDelivered.load() : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetFilterMode(JNIEnv*, jobject, jlong handle, jint mode) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setFilterMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetSwapRB(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setSwapRB(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetPresentMode(JNIEnv*, jobject, jlong handle, jint mode) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPresentMode((VkPresentModeKHR)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetEffect(
    JNIEnv*, jobject, jlong handle, jint effectId, jfloat sharpness,
    jint effectMask, jfloat brightness, jfloat contrast, jfloat gamma) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setEffect(
        (int)effectId,
        (float)sharpness,
        (int)effectMask,
        (float)brightness,
        (float)contrast,
        (float)gamma
    );
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeGetSupportedPresentModes(JNIEnv* env, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return env->NewIntArray(0);
    auto modes = r->getSupportedPresentModes();
    jintArray arr = env->NewIntArray((jsize)modes.size());
    if (!modes.empty()) env->SetIntArrayRegion(arr,0,(jsize)modes.size(),modes.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDetachSurface(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->detachSurface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeReattachSurface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = r->reattachSurface(win);
    if (ok && r->scanoutActive.load()) {
        r->destroyScanout();
    }
    return (jboolean)ok;
}
