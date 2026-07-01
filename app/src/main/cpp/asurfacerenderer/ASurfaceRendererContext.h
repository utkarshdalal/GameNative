#pragma once

#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

class BlitConverter;

#define WLOG_TAG "asr_renderer"
#define RLOG(...)   __android_log_print(ANDROID_LOG_INFO,  WLOG_TAG, __VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR, WLOG_TAG, __VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_INFO, "asr_scanout", __VA_ARGS__)

struct CallbackTarget {
    JavaVM*    vm        = nullptr;
    jobject    globalRef = nullptr;
    jmethodID  methodId  = nullptr;
    ~CallbackTarget();
};

class ASurfaceRendererContext {
public:
    ASurfaceRendererContext(ANativeWindow* window, int cWidth, int cHeight);
    ~ASurfaceRendererContext();

    bool reattachSurface(ANativeWindow* newWindow);

    void initScanout();
    void destroyScanout();

    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY,
                             bool cursorVisible);
    void scanoutSetCursorVisibility(bool visible);
    void applyCursorGeometry(short x, short y, short hotX, short hotY,
                             bool cursorVisible);

    void registerWindowSC(int64_t contentId,
                          const char* debugName = "(x11_window)");
    void unregisterWindowSC(int64_t contentId);
    void setWindowBuffer(JNIEnv* env,
                         int64_t contentId,
                         AHardwareBuffer* ahb,
                         int fenceFd,
                         int64_t windowId  = 0,
                         int64_t serial    = 0,
                         jobject gpuImage  = nullptr,
                         int     slot      = -1,
                         bool    sfCompatMode = true);

    // Pre-imports the stable three-buffer CPU scanout swapchain. Repeated calls
    // are cheap and make surface/context recreation safe.
    bool prepareCpuSourceBuffers(AHardwareBuffer* buffer0,
                                 AHardwareBuffer* buffer1,
                                 AHardwareBuffer* buffer2);
    void releaseCpuSourceBuffers(AHardwareBuffer* buffer0,
                                 AHardwareBuffer* buffer1,
                                 AHardwareBuffer* buffer2);

    void beginTransaction();
    void applyTransaction();
    void updateWindow(int64_t contentId,
                      bool    visible,
                      int     zOrder,
                      int srcL, int srcT, int srcR, int srcB,
                      int dstL, int dstT, int dstR, int dstB);

    void setSfCallbackTarget(JNIEnv* env, jobject rendererObj);

    void beginShutdown();

    std::atomic<bool> scanoutActive{false};
    JavaVM* javaVm = nullptr;

private:
    // ---- Buffer pool --------------------------------------------------------
    struct ConvertedBufferSlot {
        AHardwareBuffer* buffer      = nullptr;
        int64_t          contentId   = 0;
        uint32_t         width       = 0;
        uint32_t         height      = 0;
        bool             inUse       = false;
        int              releaseFenceFd = -1;
        ~ConvertedBufferSlot();
    };

    struct ConvertedPoolState {
        std::mutex              mutex;
        std::condition_variable condition;
        bool                    stopping         = false;
        size_t                  pendingCallbacks = 0;
        std::vector<std::unique_ptr<ConvertedBufferSlot>> slots;
    };

    // ---- SurfaceFlinger callbacks -------------------------------------------
    struct PendingSurfaceRelease {
        void*                surfaceControl        = nullptr;
        ConvertedBufferSlot* slot                  = nullptr;
        bool                 releaseSurfaceControl = false;
    };

    struct TransactionCompleteCtx {
        std::shared_ptr<ConvertedPoolState> pool;
        std::vector<PendingSurfaceRelease>  releases;
        std::shared_ptr<CallbackTarget>     callbackTarget;
        int64_t windowId                    = 0;
        int64_t serial                      = 0;
        void*   fnGetPreviousReleaseFenceFd = nullptr;
        void*   fnSurfaceControlRelease     = nullptr;
    };

    static constexpr size_t kMaximumConvertedBuffersPerWindow = 8;
    static constexpr int    kPoolWaitMs                        = 3;

    // ---- SurfaceControl map -------------------------------------------------
    std::mutex windowScMutex;
    std::unordered_map<int64_t, void*>                windowScMap;
    std::unordered_map<int64_t, ConvertedBufferSlot*> currentConvertedSlotMap;

    void* currentTx = nullptr;

    // ---- Display geometry ---------------------------------------------------
    ANativeWindow* window;
    int surfaceWidth;
    int surfaceHeight;
    int containerWidth;
    int containerHeight;

    // ---- Cursor state -------------------------------------------------------
    short lastRawCursorX = -1;
    short lastRawCursorY = -1;
    short lastRawHotX   = 0;
    short lastRawHotY   = 0;

    void*            scanoutCursorSC  = nullptr;
    AHardwareBuffer* scanoutCursorBuf = nullptr;
    int32_t          scanoutCursorBufW = 0;
    int32_t          scanoutCursorBufH = 0;
    int              scanoutCursorFence = -1;

    std::atomic<int64_t> scanoutDstXY{0};
    std::atomic<int64_t> scanoutDstWH{0};

    // ---- Dynamically-loaded scanout API -------------------------------------
    bool loadScanoutApi();
    bool scanoutApiLoaded      = false;
    void* fnSCCreateFromWin    = nullptr;
    void* fnSCRelease          = nullptr;
    void* fnSTCreate           = nullptr;
    void* fnSTDelete           = nullptr;
    void* fnSTApply            = nullptr;
    void* fnSTSetBuffer        = nullptr;
    void* fnSTSetZOrder        = nullptr;
    void* fnSTSetVisibility    = nullptr;
    void* fnSTSetGeometry      = nullptr;
    void* fnSTSetBufferTransparency = nullptr;
    void* fnSTSetBufferTransform    = nullptr;
    void* fnSTReparent         = nullptr;
    void* fnSTSetOnComplete    = nullptr;
    void* fnSTStatsGetPreviousReleaseFenceFd = nullptr;

    void oneShot(std::function<void(void*)> fill);

    // ---- SF callback wiring -------------------------------------------------
    void loadSfCallbackApi();
    static void transactionCompleteCallback(void* context, void* stats);
    bool attachTransactionCompleteCallback(
            void* transaction,
            std::vector<PendingSurfaceRelease> releases,
            int64_t windowId,
            int64_t serial);

    std::shared_ptr<CallbackTarget> callbackTarget;

    // ---- GPU converter ------------------------------------------------------
    void initGPUConverter();
    void cleanupGPUConverter();

    int convertBufferGPU(AHardwareBuffer* source,
                         AHardwareBuffer* destination,
                         int sourceAcquireFenceFd,
                         int destinationAcquireFenceFd);
    ConvertedBufferSlot* acquireConvertedBuffer(int64_t contentId,
                                                uint32_t width,
                                                uint32_t height,
                                                int& destinationAcquireFenceFd);

    static void recycleConvertedBuffer(
            const std::shared_ptr<ConvertedPoolState>& state,
            ConvertedBufferSlot* slot,
            int releaseFenceFd);

    void destroyConvertedBufferPool();
    bool waitForConvertedCallbacks(std::chrono::milliseconds timeout);

    void retireSurfaceControl(void* surfaceControl,
                              ConvertedBufferSlot* currentSlot);
    void returnSourceFence(JNIEnv* env, jobject ahbImage,
                           int sourceSlot, int fenceFd);

    bool ensureCpuSourceBufferRegistered(AHardwareBuffer* buffer);

    BlitConverter*                      blitConverter = nullptr;
    std::shared_ptr<ConvertedPoolState> convertedPoolState;
    std::atomic<bool>                   acceptingConvertedFrames{true};
    std::atomic<bool>                   shutdownStarted{false};
    std::atomic<int64_t>                lastPoolWarningNs{0};

    std::mutex cpuSourceMutex;
    std::unordered_set<AHardwareBuffer*> registeredCpuSourceBuffers;
};
