#include "ASurfaceRendererContext.h"
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <android/api-level.h>
#include <android/log.h>
#include <unordered_map>
#include <mutex>
#include <functional>

typedef void* (*pfn_SCCreateFromWindow)(ANativeWindow*, const char*);
typedef void  (*pfn_SCRelease)(void*);
typedef void* (*pfn_STCreate)();
typedef void  (*pfn_STDelete)(void*);
typedef void  (*pfn_STApply)(void*);
typedef void  (*pfn_STSetBuffer)(void*, void*, AHardwareBuffer*, int);
typedef void  (*pfn_STSetZOrder)(void*, void*, int32_t);
typedef void  (*pfn_STSetVisibility)(void*, void*, int8_t);
typedef void  (*pfn_STSetGeometry)(void*, void*, const ARect*, const ARect*, int32_t);
typedef void  (*pfn_STSetBufferTransparency)(void*, void*, int8_t);
typedef void  (*pfn_STSetBufferTransform)(void*, void*, int32_t);
typedef void  (*pfn_STSetOnComplete)(void* transaction, void* context,
                                    void (*callback)(void* context, void* stats));
typedef void  (*pfn_STReparent)(void*, void*, void*);

#define SC_CREATE(win, name)   ((pfn_SCCreateFromWindow)fnSCCreateFromWin)((win),(name))
#define SC_RELEASE(sc)         ((pfn_SCRelease)fnSCRelease)((sc))
#define ST_CREATE()            ((pfn_STCreate)fnSTCreate)()
#define ST_DELETE(t)           ((pfn_STDelete)fnSTDelete)((t))
#define ST_APPLY(t)            ((pfn_STApply)fnSTApply)((t))
#define ST_SETBUF(t,sc,b,f)    ((pfn_STSetBuffer)fnSTSetBuffer)((t),(sc),(b),(f))
#define ST_SETZORDER(t,sc,z)   if(fnSTSetZOrder) ((pfn_STSetZOrder)fnSTSetZOrder)((t),(sc),(z))
#define ST_SETVIS(t,sc,v)      ((pfn_STSetVisibility)fnSTSetVisibility)((t),(sc),(v))
#define ST_SETGEO(t,sc,s,d,r)  ((pfn_STSetGeometry)fnSTSetGeometry)((t),(sc),(s),(d),(r))
#define ST_SET_TRANSPARENCY(t,sc,tr) if(fnSTSetBufferTransparency) ((pfn_STSetBufferTransparency)fnSTSetBufferTransparency)((t),(sc),(tr))
#define ST_REPARENT(t,sc,p)    if(fnSTReparent) ((pfn_STReparent)fnSTReparent)((t),(sc),(p))

void ASurfaceRendererContext::oneShot(std::function<void(void*)> fill) {
    void* tx = ST_CREATE();
    fill(tx);
    ST_APPLY(tx);
    ST_DELETE(tx);
}

CallbackTarget::~CallbackTarget() {
    if (globalRef && vm) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            vm->AttachCurrentThreadAsDaemon(reinterpret_cast<JNIEnv**>(&env), nullptr);
            attached = true;
        }
        if (env) {
            env->DeleteGlobalRef(globalRef);
        }
    }
}

ASurfaceRendererContext::ASurfaceRendererContext(ANativeWindow* win, int cWidth, int cHeight)
        : window(win), surfaceWidth(cWidth), surfaceHeight(cHeight), containerWidth(cWidth), containerHeight(cHeight)
{
    loadScanoutApi();
    loadSfCallbackApi();
}

ASurfaceRendererContext::~ASurfaceRendererContext() {
    {
        std::lock_guard<std::mutex> lk(windowScMutex);
        if (!windowScMap.empty()) {
            void* tx = ST_CREATE();
            for (auto& pair : windowScMap) {
                void* sc = pair.second;
                ST_SETVIS(tx, sc, 0);
                ST_REPARENT(tx, sc, nullptr);
                SC_RELEASE(sc);
            }
            ST_APPLY(tx);
            ST_DELETE(tx);
            windowScMap.clear();
        }
    }

    destroyScanout();
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

bool ASurfaceRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (window) {
        ANativeWindow_release(window);
    }
    window = newWindow;
    return true;
}

bool ASurfaceRendererContext::loadScanoutApi() {
    if (scanoutApiLoaded) return fnSCCreateFromWin != nullptr;
    scanoutApiLoaded = true;
    if (android_get_device_api_level() < 29) {
        SCANOUT_LOG("loadScanoutApi: API < 29, unavailable");
        return false;
    }
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { SCANOUT_LOG("loadScanoutApi: dlopen failed: %s", dlerror()); return false; }
    fnSCCreateFromWin = dlsym(lib, "ASurfaceControl_createFromWindow");
    fnSCRelease       = dlsym(lib, "ASurfaceControl_release");
    fnSTCreate        = dlsym(lib, "ASurfaceTransaction_create");
    fnSTDelete        = dlsym(lib, "ASurfaceTransaction_delete");
    fnSTApply         = dlsym(lib, "ASurfaceTransaction_apply");
    fnSTSetBuffer     = dlsym(lib, "ASurfaceTransaction_setBuffer");
    fnSTSetZOrder     = dlsym(lib, "ASurfaceTransaction_setZOrder");
    fnSTSetVisibility = dlsym(lib, "ASurfaceTransaction_setVisibility");
    fnSTSetGeometry   = dlsym(lib, "ASurfaceTransaction_setGeometry");
    fnSTSetOnComplete = dlsym(lib, "ASurfaceTransaction_setOnComplete");
    fnSTSetBufferTransparency = dlsym(lib, "ASurfaceTransaction_setBufferTransparency");
    fnSTSetBufferTransform = dlsym(lib, "ASurfaceTransaction_setBufferTransform");
    fnSTReparent      = dlsym(lib, "ASurfaceTransaction_reparent");
    sfCallbackSupported = fnSTSetOnComplete != nullptr;
    bool coreOk = fnSCCreateFromWin && fnSCRelease && fnSTCreate && fnSTDelete && fnSTSetOnComplete && fnSTReparent &&
                  fnSTApply && fnSTSetBuffer && fnSTSetVisibility && fnSTSetGeometry && fnSTSetBufferTransparency && fnSTSetBufferTransform;
    if (!coreOk) {
        SCANOUT_LOG("loadScanoutApi: surface symbols missing");
        fnSCCreateFromWin = fnSCRelease = fnSTCreate = fnSTDelete = fnSTApply =
        fnSTSetBuffer = fnSTSetZOrder = fnSTSetVisibility = fnSTSetGeometry = nullptr;
        return false;
    }
    SCANOUT_LOG("loadScanoutApi: OK");
    return true;
}

void ASurfaceRendererContext::initScanout() {
    if (scanoutActive.load()) return;
    if (!window || !loadScanoutApi()) return;
    scanoutCursorSC = SC_CREATE(window, "(x11_cursor)");
    if (!scanoutCursorSC) {
        return;
    }
    oneShot([&](void* tx) {
        ST_SETZORDER(tx, scanoutCursorSC, INT_MAX);
        ST_SETVIS   (tx, scanoutCursorSC, 1);
    });
    scanoutCursorFence   = -1;
    scanoutActive.store(true);
}

void ASurfaceRendererContext::destroyScanout() {
    if (!scanoutActive.load()) return;
    scanoutActive.store(false);

    if (scanoutCursorSC) {
        oneShot([&](void* tx) {
            ST_SETVIS(tx, scanoutCursorSC, 0);
            ST_REPARENT(tx, scanoutCursorSC, nullptr);
        });
        SC_RELEASE(scanoutCursorSC);
        scanoutCursorSC = nullptr;
    }

    if (scanoutCursorBuf) {
        if (scanoutCursorFence >= 0) { close(scanoutCursorFence); scanoutCursorFence = -1; }
        AHardwareBuffer_release(scanoutCursorBuf);
        scanoutCursorBuf = nullptr;
    }
    scanoutCursorBufW  = 0;
    scanoutCursorBufH  = 0;
}

void ASurfaceRendererContext::scanoutSetDst(int x, int y, int w, int h) {
    scanoutDstXY.store((int64_t)(uint32_t)x << 32 | (uint32_t)y, std::memory_order_release);
    scanoutDstWH.store((int64_t)(uint32_t)w << 32 | (uint32_t)h, std::memory_order_release);
}

void ASurfaceRendererContext::scanoutSetCursorImage(void* pixels, short w, short h, short stride) {
    if (!scanoutActive.load() || !scanoutCursorSC || !pixels || w <= 0 || h <= 0) return;

    if (stride <= 0)
        stride = w;

    uint32_t srcStride = (uint32_t)w;
    if (scanoutCursorBuf && (scanoutCursorBufW != w || scanoutCursorBufH != h)) {
        if (scanoutCursorFence >= 0) { close(scanoutCursorFence); scanoutCursorFence = -1; }
        AHardwareBuffer_release(scanoutCursorBuf);
        scanoutCursorBuf = nullptr;
    }
    if (!scanoutCursorBuf) {
        AHardwareBuffer_Desc d{};
        d.width  = (uint32_t)w; d.height = (uint32_t)h; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage  = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (AHardwareBuffer_allocate(&d, &scanoutCursorBuf) != 0) return;
        scanoutCursorBufW = w; scanoutCursorBufH = h;
    }
    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(scanoutCursorBuf, &dstDesc);
    uint32_t dstStride = dstDesc.stride;
    void* dst = nullptr;
    if (AHardwareBuffer_lock(scanoutCursorBuf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dst) != 0) return;
    const uint32_t* src = reinterpret_cast<const uint32_t*>(pixels);
    auto* dstPx = reinterpret_cast<uint32_t*>(dst);
    if (dstStride == (uint32_t)w && srcStride == (uint32_t)w) {
        memcpy(dstPx, src, (size_t)w * h * 4);
    } else {
        for (int row = 0; row < h; ++row)
            memcpy(dstPx + (size_t)row * dstStride, src + (size_t)row * srcStride, (size_t)w * 4);
    }
    if (scanoutCursorFence >= 0) { close(scanoutCursorFence); scanoutCursorFence = -1; }
    AHardwareBuffer_unlock(scanoutCursorBuf, &scanoutCursorFence);
    void* tx = ST_CREATE();
    int fence = scanoutCursorFence;
    scanoutCursorFence = -1;
    ST_SETBUF(tx, scanoutCursorSC, scanoutCursorBuf, fence);
    ST_SETVIS(tx, scanoutCursorSC, 1);
    ST_APPLY(tx);
    ST_DELETE(tx);

    applyCursorGeometry(lastRawCursorX, lastRawCursorY, lastRawHotX, lastRawHotY, true);
}

void ASurfaceRendererContext::applyCursorGeometry(short x, short y, short hotX, short hotY, bool cursorVisible) {
    if (!scanoutActive.load() || !scanoutCursorSC || scanoutCursorBufW <= 0 || scanoutCursorBufH <= 0) return;
    int64_t xy = scanoutDstXY.load(std::memory_order_acquire);
    int64_t wh = scanoutDstWH.load(std::memory_order_acquire);
    int32_t rdw = (int32_t)(uint32_t)(wh >> 32);
    int32_t rdh = (int32_t)(uint32_t)(wh & 0xFFFFFFFF);
    int32_t cw  = containerWidth  > 0 ? containerWidth  : surfaceWidth;
    int32_t ch  = containerHeight > 0 ? containerHeight : surfaceHeight;
    int32_t dx  = rdw > 0 ? (int32_t)(uint32_t)(xy >> 32)       : 0;
    int32_t dy  = rdw > 0 ? (int32_t)(uint32_t)(xy & 0xFFFFFFFF): 0;
    int32_t dw  = rdw > 0 ? rdw : cw;
    int32_t dh  = rdw > 0 ? rdh : ch;
    float scaleW = (float)dw / (float)cw;
    float scaleH = (float)dh / (float)ch;
    float fx = dx + ((float)x / (float)cw) * dw;
    float fy = dy + ((float)y / (float)ch) * dh;
    int32_t curW = (int32_t)((float)scanoutCursorBufW * scaleW);
    int32_t curH = (int32_t)((float)scanoutCursorBufH * scaleH);
    int32_t px   = std::max(0, (int32_t)(fx - (float)hotX * scaleW));
    int32_t py   = std::max(0, (int32_t)(fy - (float)hotY * scaleH));
    ARect srcR{ 0, 0, scanoutCursorBufW, scanoutCursorBufH };
    ARect dstR{ px, py, px + curW, py + curH };
    void* tx = ST_CREATE();
    ST_SETGEO(tx, scanoutCursorSC, &srcR, &dstR, 0);
    ST_SETVIS(tx, scanoutCursorSC, cursorVisible);
    ST_APPLY(tx);
    ST_DELETE(tx);
}

void ASurfaceRendererContext::scanoutSetCursorPos(short x, short y, short hotX, short hotY, bool cursorVisible) {
    if (!scanoutActive.load() || !scanoutCursorSC || scanoutCursorBufW <= 0 || scanoutCursorBufH <= 0) return;
    if (x == lastRawCursorX && y == lastRawCursorY &&
        hotX == lastRawHotX  && hotY == lastRawHotY) return;
    lastRawCursorX = x; lastRawCursorY = y;
    lastRawHotX = hotX; lastRawHotY = hotY;
    applyCursorGeometry(x, y, hotX, hotY, cursorVisible);
}

void ASurfaceRendererContext::setWindowBuffer(int64_t contentId, AHardwareBuffer* ahb, int fenceFd, int64_t windowId, int64_t serial) {
    if (!ahb) {
        if (fenceFd >= 0)
            close(fenceFd);
        return;
    }

    void* sc = nullptr;
    {
        std::lock_guard<std::mutex> lk(windowScMutex);
        auto it = windowScMap.find(contentId);
        if (it == windowScMap.end()) {
            if (fenceFd >= 0) close(fenceFd);
            return;
        }
        sc = it->second;
    }
    void* tx = ST_CREATE();
    ST_SETBUF(tx, sc, ahb, fenceFd);
    ST_SET_TRANSPARENCY(tx, sc, 2);
    if (windowId != 0 || serial != 0) {
        attachOnCompleteCallback(tx, windowId, serial);
    }
    ST_APPLY(tx);
    ST_DELETE(tx);
}

void ASurfaceRendererContext::loadSfCallbackApi() {
    if (android_get_device_api_level() < 29) return;
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return;
    fnSTSetOnComplete     = dlsym(lib, "ASurfaceTransaction_setOnCompleteCallback");
    sfCallbackSupported = (fnSTSetOnComplete != nullptr);
}

void ASurfaceRendererContext::setSfCallbackTarget(JNIEnv* env, jobject rendererObj) {
    callbackTarget.reset();
    if (!rendererObj) return;

    auto target = std::make_shared<CallbackTarget>();
    target->vm = javaVm;
    target->globalRef = env->NewGlobalRef(rendererObj);

    jclass cls = env->GetObjectClass(rendererObj);
    if (cls) {
        target->methodId = env->GetMethodID(cls, "onScanoutFrameComplete", "(J)V");
        env->DeleteLocalRef(cls);
    }
    callbackTarget = target;
}

struct ScanoutCallbackCtx {
    std::shared_ptr<CallbackTarget> target;
    int64_t serial;
    int64_t windowId;
};

void ASurfaceRendererContext::scanoutFrameCompleteCallback(void* ctxPtr, void* stats) {
    auto* pCtx = reinterpret_cast<ScanoutCallbackCtx*>(ctxPtr);
    if (!pCtx) return;

    auto target = pCtx->target;
    if (target && target->globalRef && target->methodId) {
        JNIEnv* env = nullptr;
        if (target->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            target->vm->AttachCurrentThreadAsDaemon(reinterpret_cast<JNIEnv**>(&env), nullptr);
        }
        if (env) {
            int64_t packed = ((int64_t)(uint32_t)pCtx->windowId << 32) | ((int64_t)(uint32_t)pCtx->serial);
            env->CallVoidMethod(target->globalRef, target->methodId, (jlong)packed);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    delete pCtx;
}

void ASurfaceRendererContext::attachOnCompleteCallback(void* transaction, int64_t windowId, int64_t serial) {
    if (!sfCallbackSupported || !fnSTSetOnComplete || !callbackTarget) return;
    auto* ctx = new ScanoutCallbackCtx{ callbackTarget, serial, windowId };
    ((pfn_STSetOnComplete)fnSTSetOnComplete)(transaction, ctx, scanoutFrameCompleteCallback);
}

void ASurfaceRendererContext::beginTransaction() {
    if (!currentTx) {
        currentTx = ST_CREATE();
    }
}

void ASurfaceRendererContext::applyTransaction() {
    if (currentTx) {
        ST_APPLY(currentTx);
        ST_DELETE(currentTx);
        currentTx = nullptr;
    }
}

void ASurfaceRendererContext::scanoutSetCursorVisibility(bool visible) {
    if (!scanoutActive.load() || !scanoutCursorSC) return;
    oneShot([&](void* tx) {
        ST_SETVIS(tx, scanoutCursorSC, visible ? 1 : 0);
    });
}

void ASurfaceRendererContext::updateWindow(int64_t contentId, bool visible, int zOrder,
        int srcL, int srcT, int srcR, int srcB,
        int dstL, int dstT, int dstR, int dstB)
{
    void* sc = nullptr;
    {
        std::lock_guard<std::mutex> lk(windowScMutex);
        auto it = windowScMap.find(contentId);
        if (it != windowScMap.end()) sc = it->second;
    }
    if (!sc) return;

    void* tx = currentTx ? currentTx : ST_CREATE();

    ST_SETVIS(tx, sc, visible ? 1 : 0);
    if (visible) {
        ARect local_srcR{srcL, srcT, srcR, srcB};
        ARect local_dstR{dstL, dstT, dstR, dstB};
        ST_SETGEO(tx, sc, &local_srcR, &local_dstR, 0);
        ST_SETZORDER(tx, sc, zOrder);
    }

    if (!currentTx) {
        ST_APPLY(tx);
        ST_DELETE(tx);
    }
}

void ASurfaceRendererContext::registerWindowSC(int64_t contentId, const char* debugName) {
    if (!loadScanoutApi() || !this->window) return;
    void* sc = SC_CREATE(this->window, debugName ? debugName : "(x11_window)");
    if (!sc) return;

    void* oldSc = nullptr;
    {
        std::lock_guard<std::mutex> lk(windowScMutex);
        auto it = windowScMap.find(contentId);
        if (it != windowScMap.end()) oldSc = it->second;
        windowScMap[contentId] = sc;
    }
    if (oldSc) {
        oneShot([&](void* tx) {
            ST_SETVIS(tx, oldSc, 0);
            ST_REPARENT(tx, oldSc, nullptr);
        });
        SC_RELEASE(oldSc);
    }
}

void ASurfaceRendererContext::unregisterWindowSC(int64_t contentId) {
    void* sc = nullptr;
    {
        std::lock_guard<std::mutex> lk(windowScMutex);
        auto it = windowScMap.find(contentId);
        if (it != windowScMap.end()) {
            sc = it->second;
            windowScMap.erase(it);
        }
    }

    if (sc) {
        oneShot([&](void* tx) {
            ST_SETVIS(tx, sc, 0);
            ST_REPARENT(tx, sc, nullptr);
        });
        SC_RELEASE(sc);
    }
}
