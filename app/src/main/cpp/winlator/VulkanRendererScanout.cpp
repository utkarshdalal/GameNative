#include "VulkanRendererContext.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <android/api-level.h>
#include <android/log.h>

typedef void* (*pfn_SCCreateFromWindow)(ANativeWindow*, const char*);
typedef void  (*pfn_SCRelease)(void*);
typedef void* (*pfn_STCreate)();
typedef void  (*pfn_STDelete)(void*);
typedef void  (*pfn_STApply)(void*);
typedef void  (*pfn_STSetBuffer)(void*, void*, AHardwareBuffer*, int);
typedef void  (*pfn_STSetZOrder)(void*, void*, int32_t);
typedef void  (*pfn_STSetVisibility)(void*, void*, int8_t);
typedef void  (*pfn_STSetGeometry)(void*, void*, const ARect*, const ARect*, int32_t);

bool VulkanRendererContext::loadScanoutApi() {
    if (scanoutApiLoaded) return fnSCCreateFromWin != nullptr;
    scanoutApiLoaded = true;
    int apiLevel = android_get_device_api_level();
    if (apiLevel < 29) { SCANOUT_LOG("loadScanoutApi: API < 29, unavailable"); return false; }

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

    bool coreOk = fnSCCreateFromWin && fnSCRelease &&
                  fnSTCreate && fnSTDelete && fnSTApply &&
                  fnSTSetBuffer && fnSTSetVisibility && fnSTSetGeometry;
    if (!coreOk) {
        SCANOUT_LOG("loadScanoutApi: core symbols missing");
        fnSCCreateFromWin = fnSCRelease = fnSTCreate = fnSTDelete = fnSTApply =
        fnSTSetBuffer = fnSTSetZOrder = fnSTSetVisibility = fnSTSetGeometry = nullptr;
        return false;
    }
    SCANOUT_LOG("loadScanoutApi: OK");
    return true;
}

#define SC_CREATE(win, name)   ((pfn_SCCreateFromWindow)fnSCCreateFromWin)((win),(name))
#define SC_RELEASE(sc)         ((pfn_SCRelease)fnSCRelease)((sc))
#define ST_CREATE()            ((pfn_STCreate)fnSTCreate)()
#define ST_DELETE(t)           ((pfn_STDelete)fnSTDelete)((t))
#define ST_APPLY(t)            ((pfn_STApply)fnSTApply)((t))
#define ST_SETBUF(t,sc,b,f)    ((pfn_STSetBuffer)fnSTSetBuffer)((t),(sc),(b),(f))
#define ST_SETZORDER(t,sc,z)   if(fnSTSetZOrder) ((pfn_STSetZOrder)fnSTSetZOrder)((t),(sc),(z))
#define ST_SETVIS(t,sc,v)      ((pfn_STSetVisibility)fnSTSetVisibility)((t),(sc),(v))
#define ST_SETGEO(t,sc,s,d,r)  ((pfn_STSetGeometry)fnSTSetGeometry)((t),(sc),(s),(d),(r))

static inline bool arectEq(const ARect& a, const ARect& b) {
    return a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom;
}

void VulkanRendererContext::initScanout() {
    if (scanoutActive.load()) return;
    if (!window || !loadScanoutApi()) {
        SCANOUT_LOG("initScanout: loadApi failed");
        return;
    }

    scanoutGameSC   = SC_CREATE(window, "winlator_game");
    scanoutCursorSC = SC_CREATE(window, "winlator_cursor");
    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanout: SC creation failed");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        return;
    }

    void* setupTx = ST_CREATE();
    ST_SETZORDER(setupTx, scanoutGameSC,   0); ST_SETVIS(setupTx, scanoutGameSC,   0);
    ST_SETZORDER(setupTx, scanoutCursorSC, 1); ST_SETVIS(setupTx, scanoutCursorSC, 0);
    ST_APPLY(setupTx);
    ST_DELETE(setupTx);

    scanoutGameTx = ST_CREATE();
    scanoutTx     = ST_CREATE();

    scanoutVisShown = false;
    scanoutGeoDirty = true;
    scanoutLastSrc  = {}; scanoutLastDst = {};
    gameScVisible   = false;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    SCANOUT_LOG("initScanout: OK");
}

void VulkanRendererContext::initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin) {
    if (scanoutActive.load()) destroyScanout();
    if (!loadScanoutApi()) {
        ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);
        initScanout(); return;
    }

    scanoutGameSC   = SC_CREATE(gameWin,   "winlator_game_buf");
    scanoutCursorSC = SC_CREATE(cursorWin, "winlator_cursor_buf");
    ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);

    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanoutFromWindows: SC creation failed, fallback");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        initScanout(); return;
    }

    void* setupTx = ST_CREATE();
    ST_SETZORDER(setupTx, scanoutGameSC,   0); ST_SETVIS(setupTx, scanoutGameSC,   0);
    ST_SETZORDER(setupTx, scanoutCursorSC, 1); ST_SETVIS(setupTx, scanoutCursorSC, 0);
    ST_APPLY(setupTx);
    ST_DELETE(setupTx);

    scanoutGameTx = ST_CREATE();
    scanoutTx     = ST_CREATE();

    scanoutVisShown = false;
    scanoutGeoDirty = true;
    scanoutLastSrc  = {}; scanoutLastDst = {};
    gameScVisible   = false;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    SCANOUT_LOG("initScanoutFromWindows: OK (sibling path)");
}

void VulkanRendererContext::destroyScanout() {
    if (!scanoutActive.load()) return;
    scanoutActive.store(false);

    if (scanoutGameSC || scanoutCursorSC) {
        void* t = ST_CREATE();
        if (scanoutGameSC)   ST_SETVIS(t, scanoutGameSC,   0);
        if (scanoutCursorSC) ST_SETVIS(t, scanoutCursorSC, 0);
        ST_APPLY(t);
        ST_DELETE(t);
    }

    if (scanoutGameTx) { ST_DELETE(scanoutGameTx); scanoutGameTx = nullptr; }
    if (scanoutTx)     { ST_DELETE(scanoutTx);     scanoutTx     = nullptr; }

    if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
    if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }

    if (scanoutCursorBuf) {
        AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(scanoutCursorBuf));
        scanoutCursorBuf=nullptr;
    }
    scanoutCursorBufW = scanoutCursorBufH = 0;
    scanoutVisShown   = false;
    scanoutGeoDirty   = true;
}

void VulkanRendererContext::scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd) {
    if (!scanoutActive.load() || !scanoutGameSC || !ahb || !scanoutGameTx) {
        RLOG("scanoutSetBuffer: SKIPPED active=%d sc=%p ahb=%p tx=%p",
            (int)scanoutActive.load(), scanoutGameSC, (void*)ahb, scanoutGameTx);
        return;
    }

    AHardwareBuffer_acquire(ahb);

    void* t = scanoutGameTx;

    int32_t cw = containerWidth  > 0 ? containerWidth  : w;
    int32_t ch = containerHeight > 0 ? containerHeight : h;
    ARect src{0, 0, cw, ch};
    ARect dst = (scanoutDstW > 0)
        ? ARect{scanoutDstX, scanoutDstY, scanoutDstX+scanoutDstW, scanoutDstY+scanoutDstH}
        : ARect{0, 0, cw, ch};

    ST_SETBUF(t, scanoutGameSC, ahb, fenceFd);

    { std::lock_guard<std::mutex> lk(scanoutMutex);
      if (scanoutGeoDirty || !arectEq(src, scanoutLastSrc) || !arectEq(dst, scanoutLastDst)) {
          ST_SETGEO(t, scanoutGameSC, &src, &dst, 0);
          scanoutLastSrc = src;
          scanoutLastDst = dst;
          scanoutGeoDirty = false;
      }
      if (!scanoutVisShown) {
          ST_SETVIS(t, scanoutGameSC, 1);
          scanoutVisShown = true;
      }
    }

    ST_APPLY(t);

    gameFrameDelivered.store(true, std::memory_order_release);
    AHardwareBuffer_release(ahb);
}

void VulkanRendererContext::applyScanoutBuffer() {

    bool hasImage=false, hasPos=false;
    short cx=0, cy=0, chx=0, chy=0;

    { std::lock_guard<std::mutex> lk(scanoutMutex);
      hasImage = cursorImageDirty; cursorImageDirty = false;
      hasPos   = cursorPosDirty;   cursorPosDirty   = false;
      cx=pendingCursorX; cy=pendingCursorY;
      chx=pendingCursorHotX; chy=pendingCursorHotY;
    }

    if (!hasImage && !hasPos) return;

    void* t = scanoutTx;
    if (!t) return;

    auto* curBuf = reinterpret_cast<AHardwareBuffer*>(scanoutCursorBuf);
    if (hasImage && scanoutCursorSC && curBuf) {
        ST_SETBUF(t, scanoutCursorSC, curBuf, -1);
        if (!gameScVisible) {
            ST_SETVIS(t, scanoutCursorSC, 1);
            gameScVisible = true;
        }
    }

    if (hasPos && scanoutCursorSC && scanoutCursorBufW > 0) {
        int32_t cw = containerWidth  > 0 ? containerWidth  : surfaceWidth;
        int32_t ch = containerHeight > 0 ? containerHeight : surfaceHeight;
        int32_t dx = scanoutDstW > 0 ? scanoutDstX : 0;
        int32_t dy = scanoutDstW > 0 ? scanoutDstY : 0;
        int32_t dw = scanoutDstW > 0 ? scanoutDstW : cw;
        int32_t dh = scanoutDstW > 0 ? scanoutDstH : ch;
        float scaleW = (float)dw / cw;
        float scaleH = (float)dh / ch;
        float fx = dx + ((float)cx / cw) * dw;
        float fy = dy + ((float)cy / ch) * dh;
        int32_t curW = (int32_t)(scanoutCursorBufW * scaleW);
        int32_t curH = (int32_t)(scanoutCursorBufH * scaleH);
        int32_t px = std::max(0, (int32_t)(fx - chx * scaleW));
        int32_t py = std::max(0, (int32_t)(fy - chy * scaleH));
        ARect src{0, 0, scanoutCursorBufW, scanoutCursorBufH};
        ARect dst{px, py, px+curW, py+curH};
        ST_SETGEO(t, scanoutCursorSC, &src, &dst, 0);
    }

    ST_APPLY(t);

}

void VulkanRendererContext::scanoutSetDst(int x, int y, int w, int h) {
    scanoutDstX=x; scanoutDstY=y; scanoutDstW=w; scanoutDstH=h;
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      scanoutGeoDirty = true; }
}

void VulkanRendererContext::scanoutSetCursorImage(void* pixels, short w, short h, short stride) {
    if (!scanoutActive.load() || !scanoutCursorSC || !pixels || w<=0 || h<=0) return;
    if (stride <= 0) stride = w;

    auto* curBuf = reinterpret_cast<AHardwareBuffer**>(&scanoutCursorBuf);
    if (*curBuf && (scanoutCursorBufW != w || scanoutCursorBufH != h)) {
        AHardwareBuffer_release(*curBuf);
        *curBuf = nullptr;
    }
    if (!*curBuf) {
        AHardwareBuffer_Desc d{};
        d.width  = (uint32_t)w; d.height = (uint32_t)h; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage  = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                   AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                   AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (AHardwareBuffer_allocate(&d, curBuf) != 0) return;
        scanoutCursorBufW = w; scanoutCursorBufH = h;
    }

    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(*curBuf, &dstDesc);
    uint32_t dstStride = dstDesc.stride;

    void* dst = nullptr;
    if (AHardwareBuffer_lock(*curBuf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1, nullptr, &dst) != 0) return;
    const uint32_t* src = reinterpret_cast<const uint32_t*>(pixels);
    auto* dstPx = reinterpret_cast<uint32_t*>(dst);
    for (int row = 0; row < h; ++row)
        memcpy(dstPx + (size_t)row * dstStride,
               src   + (size_t)row * (uint32_t)stride,
               (size_t)w * 4);
    AHardwareBuffer_unlock(*curBuf, nullptr);

    { std::lock_guard<std::mutex> lk(scanoutMutex);
      cursorImageDirty = true; }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_one();
}

void VulkanRendererContext::scanoutSetCursorPos(short x, short y, short hotX, short hotY) {
    if (!scanoutActive.load() || !scanoutCursorSC) return;
    if (scanoutCursorBufW <= 0 || scanoutCursorBufH <= 0) return;
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      pendingCursorX=x; pendingCursorY=y; pendingCursorHotX=hotX; pendingCursorHotY=hotY;
      cursorPosDirty=true; }
    cursorMoved.store(true, std::memory_order_relaxed);
    dirtyCV.notify_one();
}
