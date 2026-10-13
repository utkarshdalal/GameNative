#include "ASurfaceRendererContext.h"
#include "blit_converter.h"
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <android/api-level.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <unordered_map>
#include <mutex>
#include <functional>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <poll.h>
#include <vector>

#define LOG_TAG "ASurfaceRenderer"

using namespace std::chrono;

#if ENABLE_ASR_LOGGING
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) ((void)0)
#endif

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
typedef int   (*pfn_STStatsGetPreviousReleaseFenceFd)(void* stats, void* surface_control);
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

#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5

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
        if (env) env->DeleteGlobalRef(globalRef);
    }
}

ASurfaceRendererContext::ASurfaceRendererContext(ANativeWindow* win, int cWidth, int cHeight)
        : window(win), surfaceWidth(cWidth), surfaceHeight(cHeight), containerWidth(cWidth), containerHeight(cHeight)
{
    loadScanoutApi();
    initGPUConverter();
}

ASurfaceRendererContext::~ASurfaceRendererContext() {
    // Stop accepting new frames and drain the pool's condition waiters.
    beginShutdown();

    // Retire every live SurfaceControl via OnComplete callbacks so their slots
    // are released before we tear down the pool.
    std::vector<int64_t> contentIds;
    {
        std::lock_guard<std::mutex> lock(windowScMutex);
        contentIds.reserve(windowScMap.size());
        for (const auto& pair : windowScMap)
            contentIds.push_back(pair.first);
    }
    for (int64_t id : contentIds)
        unregisterWindowSC(id);

    destroyScanout();

    // Wait for in-flight SF callbacks to return their buffer slots.
    if (!waitForConvertedCallbacks(std::chrono::milliseconds(2000)))
        LOGW("Timed out waiting for converted-buffer transaction callbacks");

    cleanupGPUConverter();

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
    fnSTStatsGetPreviousReleaseFenceFd = dlsym(lib, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");

    bool coreOk = fnSCCreateFromWin && fnSCRelease && fnSTCreate && fnSTDelete && fnSTApply && fnSTSetBuffer && fnSTSetVisibility &&
                  fnSTSetGeometry && fnSTSetOnComplete && fnSTSetBufferTransparency && fnSTSetBufferTransform && fnSTReparent &&
                  fnSTStatsGetPreviousReleaseFenceFd;
    if (!coreOk) {
        SCANOUT_LOG("loadScanoutApi: surface symbols missing");
        fnSCCreateFromWin = fnSCRelease = fnSTCreate = fnSTDelete = fnSTApply =
        fnSTStatsGetPreviousReleaseFenceFd = fnSTSetBufferTransparency =
        fnSTSetBuffer = fnSTSetZOrder = fnSTSetVisibility = fnSTSetOnComplete =
        fnSTSetGeometry = fnSTReparent = fnSTSetBufferTransform = nullptr;
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

void ASurfaceRendererContext::scanoutSetCursorVisibility(bool visible) {
    if (!scanoutActive.load() || !scanoutCursorSC) return;
    oneShot([&](void* tx) {
        ST_SETVIS(tx, scanoutCursorSC, visible ? 1 : 0);
    });
}

void ASurfaceRendererContext::returnSourceFence(JNIEnv* env, jobject ahbImage, int sourceSlot, int fenceFd) {
    if (!env || !ahbImage || sourceSlot < 0) {
        if (fenceFd >= 0) close(fenceFd);
        return;
    }
    jclass cls = env->GetObjectClass(ahbImage);
    if (!cls) { if (fenceFd >= 0) close(fenceFd); return; }
    const jmethodID method = env->GetMethodID(cls, "setSwapchainFence", "(II)V");
    env->DeleteLocalRef(cls);
    if (!method) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (fenceFd >= 0) close(fenceFd);
        return;
    }
    env->CallVoidMethod(ahbImage, method, sourceSlot, fenceFd);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (fenceFd >= 0) close(fenceFd);
    }
    // On success Java owns fenceFd.
}

void ASurfaceRendererContext::setWindowBuffer(JNIEnv* env, int64_t contentId, AHardwareBuffer* sourceAhb, int sourceAcquireFenceFd,
                                              int64_t windowId, int64_t serial, jobject ahbImage, int sourceSlot, bool sfCompatMode) {
    if (!sourceAhb) { if (sourceAcquireFenceFd >= 0) close(sourceAcquireFenceFd); return; }
    if (!acceptingConvertedFrames.load(std::memory_order_acquire)) {
        returnSourceFence(env, ahbImage, sourceSlot, sourceAcquireFenceFd);
        return;
    }

    // CPU-upload frames come from AHBImage's stable 3-buffer swapchain.
    // GPU-path frames still use transient imports.
    if (ahbImage && sourceSlot >= 0 && !ensureCpuSourceBufferRegistered(sourceAhb)) {
        LOGE("Could not register CPU source AHB");
        returnSourceFence(env, ahbImage, sourceSlot, sourceAcquireFenceFd);
        return;
    }

    // Resolve the SurfaceControl under lock.
    void* surfaceControl = nullptr;
    {
        std::lock_guard<std::mutex> lock(windowScMutex);
        const auto it = windowScMap.find(contentId);
        if (it != windowScMap.end()) surfaceControl = it->second;
    }
    if (!surfaceControl) {
        returnSourceFence(env, ahbImage, sourceSlot, sourceAcquireFenceFd);
        return;
    }

    if (sfCompatMode) {
        AHardwareBuffer_Desc sourceDescriptor{};
        AHardwareBuffer_describe(sourceAhb, &sourceDescriptor);

        int destinationAcquireFenceFd = -1;
        ConvertedBufferSlot* destinationSlot = acquireConvertedBuffer(contentId, sourceDescriptor.width, sourceDescriptor.height, destinationAcquireFenceFd);

        if (!destinationSlot) {
            const int64_t nowNs = duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
            int64_t prior = lastPoolWarningNs.load(std::memory_order_relaxed);
            if (nowNs - prior > 1000000000LL &&
                lastPoolWarningNs.compare_exchange_strong(prior, nowNs, std::memory_order_relaxed)) {
                LOGW("Converted destination pool exhausted; dropping frame");
            }
            returnSourceFence(env, ahbImage, sourceSlot, sourceAcquireFenceFd);
            return;
        }

        // Post the conversion to BlitConverter's worker thread and block until the release fence is ready
        int conversionFenceFd = convertBufferGPU(sourceAhb, destinationSlot->buffer, sourceAcquireFenceFd, destinationAcquireFenceFd);

        // Both input FDs consumed by convertBufferGPU on every path.
        sourceAcquireFenceFd = -1;
        destinationAcquireFenceFd = -1;

        if (conversionFenceFd < 0) {
            LOGE("BGRA→RGBA conversion failed");
            recycleConvertedBuffer(convertedPoolState, destinationSlot, -1);
            returnSourceFence(env, ahbImage, sourceSlot, -1);
            return;
        }

        // Dup the release fence for the source swapchain return.
        const int sourceReleaseFenceFd = fcntl(conversionFenceFd, F_DUPFD_CLOEXEC, 0);
        if (sourceReleaseFenceFd >= 0) {
            returnSourceFence(env, ahbImage, sourceSlot, sourceReleaseFenceFd);
        } else {
            LOGW("FD exhaustion, waiting for conversion");
            // FD exhaustion fallback: wait for conversion so the source can be returned without a fence
            pollfd descriptor{};
            descriptor.fd = conversionFenceFd;
            descriptor.events = POLLIN;
            int result;
            do { result = poll(&descriptor, 1, -1); } while (result < 0 && errno == EINTR);
            if (result < 0) LOGE("poll(conversion fence) failed: %s", strerror(errno));
            returnSourceFence(env, ahbImage, sourceSlot, -1);
        }

        // Swap the current slot under lock, verifying the SC is still valid.
        ConvertedBufferSlot *previousSlot = nullptr;
        {
            std::lock_guard<std::mutex> lock(windowScMutex);
            const auto it = windowScMap.find(contentId);
            if (it == windowScMap.end() ||
                it->second != surfaceControl ||
                !acceptingConvertedFrames.load(std::memory_order_relaxed)) {
                recycleConvertedBuffer(convertedPoolState, destinationSlot,
                                       conversionFenceFd);
                return;
            }
            const auto cur = currentConvertedSlotMap.find(contentId);
            if (cur != currentConvertedSlotMap.end()) previousSlot = cur->second;
            currentConvertedSlotMap[contentId] = destinationSlot;
        }

        void *transaction = ST_CREATE();
        if (!transaction) {
            {
                std::lock_guard<std::mutex> lock(windowScMutex);
                currentConvertedSlotMap[contentId] = previousSlot;
            }
            recycleConvertedBuffer(convertedPoolState, destinationSlot,
                                   conversionFenceFd);
            return;
        }

        ST_SETBUF(transaction, surfaceControl, destinationSlot->buffer, conversionFenceFd);
        ST_SET_TRANSPARENCY(transaction, surfaceControl, 2);

        std::vector<PendingSurfaceRelease> releases;
        if (previousSlot) {
            releases.push_back(PendingSurfaceRelease{surfaceControl, previousSlot, false});
        }

        if (!attachTransactionCompleteCallback(transaction, std::move(releases), windowId, serial)) {
            LOGE("Could not attach SurfaceControl OnComplete callback");
        }

        ST_APPLY(transaction);
        ST_DELETE(transaction);
    } else {
        // Return the source fence back to the source
        returnSourceFence(env, ahbImage, sourceSlot, sourceAcquireFenceFd);

        // Use the source buffer directly on the SurfaceControl
        void* transaction = ST_CREATE();
        if (!transaction) {
            return;
        }

        ST_SETBUF(transaction, surfaceControl, sourceAhb, sourceAcquireFenceFd);
        ST_SET_TRANSPARENCY(transaction, surfaceControl, 2);

        // No callback needed since we're not tracking converted buffers
        ST_APPLY(transaction);
        ST_DELETE(transaction);
    }
}

void ASurfaceRendererContext::loadSfCallbackApi() {
    loadScanoutApi();
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

void ASurfaceRendererContext::recycleConvertedBuffer(const std::shared_ptr<ConvertedPoolState>& state, ConvertedBufferSlot* slot, int releaseFenceFd) {
    if (!slot || !state) { if (releaseFenceFd >= 0) close(releaseFenceFd); return; }
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (slot->releaseFenceFd >= 0) close(slot->releaseFenceFd);
        slot->releaseFenceFd = releaseFenceFd;
        slot->inUse = false;
    }
    state->condition.notify_all();
}

void ASurfaceRendererContext::transactionCompleteCallback(void* context, void* stats) {
    std::unique_ptr<TransactionCompleteCtx> callback(
            static_cast<TransactionCompleteCtx*>(context));
    if (!callback) return;

    auto getPreviousReleaseFence =
            reinterpret_cast<pfn_STStatsGetPreviousReleaseFenceFd>(
                    callback->fnGetPreviousReleaseFenceFd);
    auto releaseSurfaceControl =
            reinterpret_cast<pfn_SCRelease>(callback->fnSurfaceControlRelease);

    for (const PendingSurfaceRelease& release : callback->releases) {
        int releaseFenceFd = -1;
        if (release.slot && stats && getPreviousReleaseFence &&
            release.surfaceControl) {
            releaseFenceFd = getPreviousReleaseFence(stats, release.surfaceControl);
        }
        if (release.slot) {
            recycleConvertedBuffer(callback->pool, release.slot, releaseFenceFd);
        } else if (releaseFenceFd >= 0) {
            close(releaseFenceFd);
        }
        if (release.releaseSurfaceControl &&
            release.surfaceControl && releaseSurfaceControl) {
            releaseSurfaceControl(release.surfaceControl);
        }
    }

    const auto target = callback->callbackTarget;
    if (target && target->globalRef && target->methodId) {
        JNIEnv* env = nullptr;
        if (target->vm->GetEnv(reinterpret_cast<void**>(&env),
                               JNI_VERSION_1_6) == JNI_EDETACHED) {
            target->vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<JNIEnv**>(&env), nullptr);
        }
        if (env) {
            const int64_t packed =
                    (static_cast<int64_t>(static_cast<uint32_t>(callback->windowId)) << 32) |
                    static_cast<uint32_t>(callback->serial);
            env->CallVoidMethod(target->globalRef, target->methodId,
                                static_cast<jlong>(packed));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    if (callback->pool) {
        {
            std::lock_guard<std::mutex> lock(callback->pool->mutex);
            if (callback->pool->pendingCallbacks > 0)
                --callback->pool->pendingCallbacks;
        }
        callback->pool->condition.notify_all();
    }
}

bool ASurfaceRendererContext::attachTransactionCompleteCallback(void* transaction, std::vector<PendingSurfaceRelease> releases, int64_t windowId, int64_t serial) {
    if (!transaction || !fnSTSetOnComplete) return false;
    auto state = convertedPoolState;
    if (!state) return false;

    auto* ctx = new TransactionCompleteCtx();
    ctx->pool            = state;
    ctx->releases        = std::move(releases);
    ctx->callbackTarget  = (windowId != 0 || serial != 0) ? callbackTarget : nullptr;
    ctx->windowId        = windowId;
    ctx->serial          = serial;
    ctx->fnGetPreviousReleaseFenceFd = fnSTStatsGetPreviousReleaseFenceFd;
    ctx->fnSurfaceControlRelease     = fnSCRelease;

    {
        std::lock_guard<std::mutex> lock(state->mutex);
        ++state->pendingCallbacks;
    }

    reinterpret_cast<pfn_STSetOnComplete>(fnSTSetOnComplete)(transaction, ctx, transactionCompleteCallback);
    return true;
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

void ASurfaceRendererContext::updateWindow(int64_t contentId, bool visible, int zOrder,
                                           int srcL, int srcT, int srcR, int srcB,
                                           int dstL, int dstT, int dstR, int dstB) {
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

void ASurfaceRendererContext::retireSurfaceControl(void* surfaceControl, ConvertedBufferSlot* currentSlot) {
    if (!surfaceControl) return;
    void* transaction = ST_CREATE();
    if (!transaction) {
        SC_RELEASE(surfaceControl);
        return;
    }
    ST_SETVIS(transaction, surfaceControl, 0);
    ST_REPARENT(transaction, surfaceControl, nullptr);

    std::vector<PendingSurfaceRelease> releases;
    releases.push_back(PendingSurfaceRelease{surfaceControl, currentSlot, true});

    if (!attachTransactionCompleteCallback(transaction, std::move(releases), 0, 0)) {
        LOGE("Could not attach retirement OnComplete callback");
        ST_APPLY(transaction);
        ST_DELETE(transaction);
        SC_RELEASE(surfaceControl);
        return;
    }
    ST_APPLY(transaction);
    ST_DELETE(transaction);
}

void ASurfaceRendererContext::registerWindowSC(int64_t contentId, const char* debugName) {
    if (!loadScanoutApi() || !window) return;
    void* surfaceControl = SC_CREATE(window, debugName ? debugName : "(x11_window)");
    if (!surfaceControl) return;

    void*                oldSurfaceControl = nullptr;
    ConvertedBufferSlot* oldCurrentSlot    = nullptr;
    {
        std::lock_guard<std::mutex> lock(windowScMutex);
        const auto oldIt = windowScMap.find(contentId);
        if (oldIt != windowScMap.end()) oldSurfaceControl = oldIt->second;
        const auto slotIt = currentConvertedSlotMap.find(contentId);
        if (slotIt != currentConvertedSlotMap.end()) {
            oldCurrentSlot = slotIt->second;
            currentConvertedSlotMap.erase(slotIt);
        }
        windowScMap[contentId] = surfaceControl;
    }
    if (oldSurfaceControl) retireSurfaceControl(oldSurfaceControl, oldCurrentSlot);
}

void ASurfaceRendererContext::unregisterWindowSC(int64_t contentId) {
    void*                surfaceControl = nullptr;
    ConvertedBufferSlot* currentSlot    = nullptr;
    {
        std::lock_guard<std::mutex> lock(windowScMutex);
        const auto it = windowScMap.find(contentId);
        if (it != windowScMap.end()) {
            surfaceControl = it->second;
            windowScMap.erase(it);
        }
        const auto slotIt = currentConvertedSlotMap.find(contentId);
        if (slotIt != currentConvertedSlotMap.end()) {
            currentSlot = slotIt->second;
            currentConvertedSlotMap.erase(slotIt);
        }
    }
    if (surfaceControl) retireSurfaceControl(surfaceControl, currentSlot);
}

bool ASurfaceRendererContext::ensureCpuSourceBufferRegistered(
        AHardwareBuffer* buffer) {
    if (!buffer || !blitConverter ||
        shutdownStarted.load(std::memory_order_acquire)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(cpuSourceMutex);
    if (registeredCpuSourceBuffers.find(buffer) !=
        registeredCpuSourceBuffers.end()) {
        return true;
    }

    if (!blitConverter->registerBuffer(buffer)) {
        return false;
    }

    registeredCpuSourceBuffers.insert(buffer);
    return true;
}

bool ASurfaceRendererContext::prepareCpuSourceBuffers(
        AHardwareBuffer* buffer0,
        AHardwareBuffer* buffer1,
        AHardwareBuffer* buffer2) {
    AHardwareBuffer* buffers[] = {buffer0, buffer1, buffer2};
    bool ok = true;
    for (AHardwareBuffer* buffer : buffers) {
        if (!buffer || !ensureCpuSourceBufferRegistered(buffer)) {
            ok = false;
        }
    }
    return ok;
}

void ASurfaceRendererContext::releaseCpuSourceBuffers(
        AHardwareBuffer* buffer0,
        AHardwareBuffer* buffer1,
        AHardwareBuffer* buffer2) {
    AHardwareBuffer* buffers[] = {buffer0, buffer1, buffer2};
    std::vector<AHardwareBuffer*> toUnregister;

    {
        std::lock_guard<std::mutex> lock(cpuSourceMutex);
        for (AHardwareBuffer* buffer : buffers) {
            if (!buffer) continue;
            const auto it = registeredCpuSourceBuffers.find(buffer);
            if (it != registeredCpuSourceBuffers.end()) {
                registeredCpuSourceBuffers.erase(it);
                toUnregister.push_back(buffer);
            }
        }
    }

    // unregisterBuffer() is synchronous and is ordered after already queued
    // conversions on BlitConverter's single worker thread.
    if (blitConverter) {
        for (AHardwareBuffer* buffer : toUnregister) {
            blitConverter->unregisterBuffer(buffer);
        }
    }
}

void ASurfaceRendererContext::initGPUConverter() {
    convertedPoolState = std::make_shared<ConvertedPoolState>();
    blitConverter      = new BlitConverter(32);

    // Create the EGL context, compile/link the shader and create the private
    // GL objects during nativeInit rather than stalling the first real frame.
    if (!blitConverter->initialize()) {
        LOGE("BlitConverter EGL/GL pre-initialization failed");
    }

    acceptingConvertedFrames.store(true,  std::memory_order_release);
    shutdownStarted.store(false, std::memory_order_release);
    LOGI("BlitConverter initialized");
}

void ASurfaceRendererContext::beginShutdown() {
    bool expected = false;
    if (!shutdownStarted.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {return; }
    acceptingConvertedFrames.store(false, std::memory_order_release);
    const auto state = convertedPoolState;
    if (state) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->stopping = true;
        }
        state->condition.notify_all();
    }
}

bool ASurfaceRendererContext::waitForConvertedCallbacks(std::chrono::milliseconds timeout) {
    const auto state = convertedPoolState;
    if (!state) return true;
    std::unique_lock<std::mutex> lock(state->mutex);
    return state->condition.wait_for(lock, timeout, [&] { return state->pendingCallbacks == 0; });
}

void ASurfaceRendererContext::cleanupGPUConverter() {
    if (blitConverter) {
        // The worker drains queued conversions before destroying all persistent
        // source and destination EGLImage imports.
        blitConverter->shutdown();
        delete blitConverter;
        blitConverter = nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(cpuSourceMutex);
        registeredCpuSourceBuffers.clear();
    }

    destroyConvertedBufferPool();
    convertedPoolState.reset();
}

void ASurfaceRendererContext::destroyConvertedBufferPool() {
    const auto state = convertedPoolState;
    if (!state) return;

    std::vector<std::unique_ptr<ConvertedBufferSlot>> slots;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->pendingCallbacks != 0) {
            LOGW("Destroying converted pool with %zu callbacks still pending",
                 state->pendingCallbacks);
        }
        slots.swap(state->slots);
    }

    for (auto& slot : slots) {
        if (slot->releaseFenceFd >= 0) {
            close(slot->releaseFenceFd);
            slot->releaseFenceFd = -1;
        }
        // AHardwareBuffer refs are released by ~ConvertedBufferSlot.
        // BlitConverter already unregistered them during its shutdown.
        if (slot->buffer) {
            AHardwareBuffer_release(slot->buffer);
            slot->buffer = nullptr;
        }
    }
}

int ASurfaceRendererContext::convertBufferGPU(AHardwareBuffer* source, AHardwareBuffer* destination, int sourceAcquireFenceFd, int destinationAcquireFenceFd) {
    if (!blitConverter) {
        if (sourceAcquireFenceFd >= 0)      close(sourceAcquireFenceFd);
        if (destinationAcquireFenceFd >= 0) close(destinationAcquireFenceFd);
        return -1;
    }
    // Post and block until the worker thread has submitted the draw and produced the release fence
    std::future<int> future = blitConverter->convertBGRAtoRGBA(source, destination, sourceAcquireFenceFd, destinationAcquireFenceFd);
    return future.get();
}

ASurfaceRendererContext::ConvertedBufferSlot*
ASurfaceRendererContext::acquireConvertedBuffer(int64_t contentId, uint32_t width, uint32_t height, int& destinationAcquireFenceFd) {
    destinationAcquireFenceFd = -1;
    const auto state = convertedPoolState;
    if (!state || !blitConverter) return nullptr;

    auto findReusable = [&]() -> ConvertedBufferSlot* {
        for (const auto& slot : state->slots) {
            if (slot->contentId == contentId &&
                slot->width  == width  &&
                slot->height == height &&
                !slot->inUse) {
                slot->inUse = true;
                destinationAcquireFenceFd = slot->releaseFenceFd;
                slot->releaseFenceFd = -1;
                return slot.get();
            }
        }
        return nullptr;
    };

    {
        std::unique_lock<std::mutex> lock(state->mutex);
        if (state->stopping) return nullptr;
        if (ConvertedBufferSlot* reusable = findReusable()) return reusable;

        size_t matchingCount = 0;
        for (const auto& slot : state->slots) {
            if (slot->contentId == contentId &&
                slot->width  == width  &&
                slot->height == height) {
                ++matchingCount;
            }
        }

        if (matchingCount >= kMaximumConvertedBuffersPerWindow) {
            state->condition.wait_for(lock, std::chrono::milliseconds(kPoolWaitMs),[&] {
                if (state->stopping) return true;
                for (const auto& slot : state->slots) {
                    if (slot->contentId == contentId &&
                        slot->width  == width  &&
                        slot->height == height &&
                        !slot->inUse) return true;
                }
                return false;
            });
            if (state->stopping) return nullptr;
            return findReusable();
        }
    }

    // Allocate a new slot outside the lock.
    AHardwareBuffer_Desc desc{};
    desc.width  = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage  = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                  AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;

    AHardwareBuffer* buffer = nullptr;
    int allocResult = AHardwareBuffer_allocate(&desc, &buffer);
    if (allocResult != 0 || !buffer) {
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        allocResult = AHardwareBuffer_allocate(&desc, &buffer);
    }
    if (allocResult != 0 || !buffer) {
        LOGE("Converted destination allocation failed: %d", allocResult);
        return nullptr;
    }

    // registerBuffer posts to the worker thread and blocks until the EGLImage is created
    if (!blitConverter->registerBuffer(buffer)) {
        LOGE("Could not register converted destination AHB");
        AHardwareBuffer_release(buffer);
        return nullptr;
    }

    auto slot = std::make_unique<ConvertedBufferSlot>();
    slot->buffer    = buffer;
    slot->contentId = contentId;
    slot->width     = width;
    slot->height    = height;
    slot->inUse     = true;

    ConvertedBufferSlot* result = slot.get();
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->stopping) {
            blitConverter->unregisterBuffer(buffer);
            AHardwareBuffer_release(buffer);
            return nullptr;
        }
        state->slots.emplace_back(std::move(slot));
    }
    return result;
}

ASurfaceRendererContext::ConvertedBufferSlot::~ConvertedBufferSlot() {
    if (releaseFenceFd >= 0) {
        close(releaseFenceFd);
        releaseFenceFd = -1;
    }
    if (buffer) {
        AHardwareBuffer_release(buffer);
        buffer = nullptr;
    }
}
