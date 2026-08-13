#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <vulkan/vulkan_android.h>
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCmdCopyImageToBuffer CmdCopyImageToBuffer;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkUnmapMemory UnmapMemory;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <unordered_map>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>

#include "VulkanLibrashader.h"

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;
static constexpr int EFFECT_LIBRASHADER = 6;

struct WindowPushConstants {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int   useTexAlpha;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
    int   effectMask;
    float brightness;
    float contrast;
    float gamma;
    float outW;   // on-screen quad width  in pixels (for FSR/EASU upscale ratio)
    float outH;   // on-screen quad height in pixels
};

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight, void* adrenotoolsHandle = nullptr);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    std::atomic<bool> scanoutActive{false};
    std::atomic<bool> gameFrameDelivered{false};
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setSwapRB(bool enabled);
    void setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;

    void initLibrashader();
    void loadLibrashaderPreset(const std::string& presetPath);
    // Deferred preset load: stores the request; the RENDER thread applies it (reloadPreset does
    // queue work that must not race with in-flight frame recording on the render thread).
    void requestLibrashaderPreset(const std::string& presetPath);
    // Deferred preset CLEAR (per-shader toggle-off, spec 2026-08-11): destroys the filter
    // chain so the frame renders unshaded while librashader stays ENABLED (the main toggle's
    // job is the on/off of the whole system; this only clears the selected preset).
    void clearLibrashaderPreset();
    std::mutex presetReqMtx;
    std::string pendingPresetPath;
    bool hasPendingPreset = false;
    bool hasPendingClear = false;
    void setLibrashaderParam(const std::string& name, float value);
    void enableLibrashader(bool enabled);
    bool isLibrashaderLoaded() const { return libraShader.isLoaded(); }
    bool isLibrashaderActive() const { return libraShaderActive.load(); }
    const std::string& getLibrashaderError() const { return libraShader.getLastError(); }

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode = 0;
    bool swapRB = false;
    int activeEffectId = 0;
    float activeSharpness = 1.0f;
    int activeEffectMask = 0;
    float activeBrightness = 0.0f;
    float activeContrast = 0.0f;
    float activeGamma = 1.0f;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;

    std::vector<WinTex>    deleteQueue;
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    void*  scanoutGameSC      = nullptr;
    void*  scanoutCursorSC    = nullptr;
    void*  scanoutCursorBuf   = nullptr;
    int32_t scanoutCursorBufW = 0;
    int32_t scanoutCursorBufH = 0;

    void*  scanoutTx          = nullptr;
    void*  scanoutGameTx      = nullptr;

    ARect  scanoutLastSrc{}, scanoutLastDst{};
    bool   scanoutGeoDirty    = true;
    bool   scanoutVisShown    = false;
    bool   scanoutApiLoaded   = false;
    void*  fnSCCreateFromWin  = nullptr;
    void*  fnSCRelease        = nullptr;
    void*  fnSTCreate         = nullptr;
    void*  fnSTDelete         = nullptr;
    void*  fnSTApply          = nullptr;
    void*  fnSTSetBuffer      = nullptr;
    void*  fnSTSetZOrder      = nullptr;
    void*  fnSTSetVisibility  = nullptr;
    void*  fnSTSetGeometry    = nullptr;
    void*  fnSTSetBackPressure = nullptr;
    bool   loadScanoutApi();

    int32_t scanoutDstX=0, scanoutDstY=0, scanoutDstW=0, scanoutDstH=0;

    int32_t lastDstX=0, lastDstY=0, lastDstW=0, lastDstH=0;
    bool    gameScVisible      = false;

    struct ScanoutPending { AHardwareBuffer* ahb=nullptr; int x=0,y=0,w=0,h=0; int fenceFd=-1; };
    std::mutex        scanoutMutex;
    ScanoutPending    scanoutPending{};
    std::atomic<bool> scanoutPendingDirty{false};

    short  pendingCursorX=0, pendingCursorY=0, pendingCursorHotX=0, pendingCursorHotY=0;
    bool   cursorPosDirty=false;
    bool   cursorImageDirty=false;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;
    VkDeviceSize     cursorUploadSize = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkRenderPass          offscreenRenderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;
    VkPipeline            offscreenPipeline = VK_NULL_HANDLE;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    VkCommandPool                filterCmdPool = VK_NULL_HANDLE;
    VkCommandBuffer              filterCmdBuf = VK_NULL_HANDLE;
    VkCommandBuffer              presentCmdBuf = VK_NULL_HANDLE;
    VkFence                      filterFence = VK_NULL_HANDLE;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    std::shared_mutex frameMutex;
    // I2: serializes the filter-chain submit/waits (render thread, recordFilterChainPass) against
    // reloadPreset (UI/JNI thread, loadLibrashaderPreset). The recorded command buffer references the
    // chain's Vulkan resources until QueueSubmit completes, so reload must not free the chain while a
    // submit referencing it is in flight. Always acquire renderMutex/filterSubmitMtx in the same order
    // (filterSubmitMtx -> librashader.mtx) to avoid deadlock.
    std::mutex        filterSubmitMtx;

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createOffscreenRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out, VkRenderPass rp);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    // melonDS wide-barrier recipe (VulkanSurfacePresenter.cpp:2258/2270): srcAccess =
    // MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE, srcStage = ALL_COMMANDS.
    void            transferBarrierWide(VkCommandBuffer cb, VkImage img,
                                        VkImageLayout oldLayout, VkImageLayout newLayout,
                                        VkAccessFlags dstAccess, VkPipelineStageFlags dstStage);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);

    VulkanLibrashader libraShader;
    std::atomic<bool> libraShaderEnabled{false};
    std::atomic<bool> libraShaderActive{false};
    std::string libraShaderPresetPath;
    bool libraNeedsHistoryClear = true;
    // Latch: a chain that failed applyFrame (or failed to compile) is not retried 60x/s.
    // While latched, the default path presents the unshaded offscreen (ARMSX2 pattern:
    // a broken preset degrades to the frame without shader, never to a black/garbage frame).
    // Reset whenever a new preset is requested.
    bool libraChainFailed = false;

    VkImage         offscreenImage = VK_NULL_HANDLE;
    VkDeviceMemory  offscreenMem = VK_NULL_HANDLE;
    VkImageView     offscreenView = VK_NULL_HANDLE;
    VkFramebuffer   offscreenFB = VK_NULL_HANDLE;

    VkImage         processedImage = VK_NULL_HANDLE;
    VkDeviceMemory  processedMem = VK_NULL_HANDLE;
    VkImageView     processedView = VK_NULL_HANDLE;

    VkBuffer         processedReadbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory   processedReadbackMem = VK_NULL_HANDLE;

    // P4-PROBE (temporary): dedicated diagnostic destination image. melonDS blits the filter
    // output into an intermediate image (atlasOutput) and only later presents it; diagDstImage is
    // that dedicated destination for the P4 probe (never the swapchain). Reused by Task 6 (atlas).
    VkImage         diagDstImage = VK_NULL_HANDLE;
    VkDeviceMemory  diagDstMem = VK_NULL_HANDLE;
    VkImageView     diagDstView = VK_NULL_HANDLE;
    VkBuffer        diagReadbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory  diagReadbackMem = VK_NULL_HANDLE;

    // Task 6 atlas fix (melonDS topology): filterOutputImage is the applyFrame target; the copy
    // engine moves it to the dedicated atlasImage (presented sampled in GENERAL). atlasLayout tracks
    // atlasImage's layout across frames; reset to UNDEFINED on recreate.
    VkImage         filterOutputImage = VK_NULL_HANDLE;
    VkDeviceMemory  filterOutputMem = VK_NULL_HANDLE;
    VkImageView     filterOutputView = VK_NULL_HANDLE;
    VkImage         atlasImage = VK_NULL_HANDLE;
    VkDeviceMemory  atlasMem = VK_NULL_HANDLE;
    VkImageView     atlasView = VK_NULL_HANDLE;
    VkImageLayout   atlasLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    // C1: tracks filterOutputImage's layout across frames. applyFrame requires the output image in
    // COLOR_ATTACHMENT_OPTIMAL and does not create a barrier for the final pass; the copy engine
    // leaves it in TRANSFER_SRC_OPTIMAL, so we restore it to CAO each frame. UNDEFINED on first use
    // / after recreate; reset in createOffscreenTargets/destroyOffscreenTargets.
    VkImageLayout   filterOutputLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    uint64_t         libraFrameCount = 0;

    VkPipeline      blitPipeline = VK_NULL_HANDLE;
    VkSampler       blitSampler = VK_NULL_HANDLE;
    VkDescriptorSet blitDS = VK_NULL_HANDLE;

    void createOffscreenTargets(int w, int h);
    void destroyOffscreenTargets();
    void createBlitPipeline();
    void destroyBlitPipeline();

    void recordCompositorPass(VkCommandBuffer cb,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short curW, short curH);

    void blitProcessedToSwapchain(VkCommandBuffer cb, uint32_t imgIdx);
    void blitImageToSwapchain(VkCommandBuffer cb, uint32_t imgIdx, VkImageView srcView, VkSampler srcSampler);
    void blitImageToSwapchainLayout(VkCommandBuffer cb, uint32_t imgIdx, VkImageView srcView, VkSampler srcSampler, VkImageLayout imageLayout);
    // Full-screen present of srcView (in srcLayout) into swapchain image imgIdx, drawing the
    // cursor in the SAME render pass (a separate cursor pass with loadOp=CLEAR wiped the
    // presented frame — bug-fix 5). Used by the default librashader path and its fallback.
    void recordPresentPass(VkCommandBuffer cb, uint32_t imgIdx,
                           VkImageView srcView, VkSampler srcSampler, VkImageLayout srcLayout);
    // P4-PROBE (temporary): blit processedImage -> dedicated diagDstImage with the melonDS wide
    // transfer barrier, read back diagDstImage (READBACK-D), leave both images in presentable state.
    void blitProcessedToDedicated(VkCommandBuffer cb);

    // Task 6 atlas fix: applyFrame writes filterOutputImage, the copy engine moves it to atlasImage
    // in the same CB, then submit-and-wait. presentAtlasToSwapchain records (CB must be already
    // begun) the GENERAL->GENERAL barrier + atlas sampler blit into the swapchain.
    void recordFilterChainPass(VkCommandBuffer cb, uint64_t frameCount, bool clearHistory);
    void presentAtlasToSwapchain(VkCommandBuffer cb, uint32_t imgIdx);

    // P2 in-frame readback of the applyFrame output. img is assumed to be in curLayout
    // (COLOR_ATTACHMENT_OPTIMAL for processedImage and for filterOutputImage after the C1 restore;
    // the default-path readback restores it to COLOR_ATTACHMENT_OPTIMAL to keep the invariant).
    void readbackProcessedInFrame(VkCommandBuffer cb, VkImage img, VkImageLayout curLayout);
    void readbackProcessedP1();
    void readbackOffscreenDiag();
};
