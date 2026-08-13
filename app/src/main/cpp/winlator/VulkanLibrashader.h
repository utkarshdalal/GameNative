#pragma once
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <string>
#include <mutex>
#include <vector>
#include <atomic>

struct _shader_preset;
typedef struct _shader_preset *libra_shader_preset_t;
struct _filter_chain_vk;
typedef struct _filter_chain_vk *libra_vk_filter_chain_t;
struct _preset_ctx;
typedef struct _preset_ctx *libra_preset_ctx_t;
struct _libra_error;
typedef struct _libra_error *libra_error_t;
struct libra_device_vk_t;
struct libra_preset_opt_t;
struct filter_chain_vk_opt_t;
struct libra_image_vk_t;
struct libra_viewport_t;
struct frame_vk_opt_t;

class VulkanLibrashader {
public:
    VulkanLibrashader();
    ~VulkanLibrashader();

    bool loadLibrary();
    bool isLoaded() const { return handle != nullptr; }

    bool init(VkInstance instance, VkPhysicalDevice physicalDevice,
              VkDevice device, VkQueue queue, PFN_vkGetInstanceProcAddr gipa);

    void reloadPreset(const std::string& presetPath);
    bool isActive() const { return chain != nullptr; }
    // Store-only: NEVER touches the chain from the caller's thread. The chain is
    // single-threaded (render thread); pending values are applied by applyPendingParams()
    // on the render thread just before applyFrame (ARMSX2 generation pattern).
    void setParam(const std::string& name, float value);
    // Render-thread only: applies any pending params to the live chain (fast path: one
    // atomic load per frame when nothing changed). Called before applyFrame.
    void applyPendingParams();

    bool applyFrame(VkCommandBuffer cb, uint64_t frameCount,
                    VkImage srcImage, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
                    VkImage dstImage, VkFormat dstFormat, uint32_t dstW, uint32_t dstH,
                    VkExtent2D viewportExtent, bool clearHistory);

    void destroyFilterChain();
    void unloadLibrary();

    const std::string& getLastError() const { return lastError; }

private:
    void* handle = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;

    libra_shader_preset_t preset = nullptr;
    libra_preset_ctx_t presetCtx = nullptr;
    libra_vk_filter_chain_t chain = nullptr;

    std::string presetPath;
    std::string lastError;
    std::mutex mtx;

    // Deferred param store (ARMSX2 pattern): UI writes here under paramStoreMtx and bumps
    // paramGeneration (release); the render thread snapshots when generation != applied
    // and applies to the chain under mtx (which serializes with applyFrame/reloadPreset).
    std::mutex paramStoreMtx;
    std::vector<std::pair<std::string, float>> pendingParams;
    std::atomic<uint64_t> paramGeneration{0};
    uint64_t appliedGeneration = 0;

    // C API function pointers (dlopened from liblibrashader.so)
    libra_error_t (*fnPresetCreateWithOptions)(const char*, libra_preset_ctx_t*,
        struct libra_preset_opt_t*, libra_shader_preset_t*) = nullptr;
    libra_error_t (*fnPresetFree)(libra_shader_preset_t*) = nullptr;
    libra_error_t (*fnPresetCtxCreate)(libra_preset_ctx_t*) = nullptr;
    libra_error_t (*fnPresetCtxFree)(libra_preset_ctx_t*) = nullptr;
    libra_error_t (*fnPresetCtxSetAllowRotation)(libra_preset_ctx_t*, bool) = nullptr;
    libra_error_t (*fnVkFilterChainCreate)(libra_shader_preset_t*,
        struct libra_device_vk_t, const struct filter_chain_vk_opt_t*,
        libra_vk_filter_chain_t*) = nullptr;
    libra_error_t (*fnVkFilterChainFrame)(libra_vk_filter_chain_t*, VkCommandBuffer,
        size_t, struct libra_image_vk_t, struct libra_image_vk_t,
        const struct libra_viewport_t*, const float*, const struct frame_vk_opt_t*) = nullptr;
    libra_error_t (*fnVkFilterChainFree)(libra_vk_filter_chain_t*) = nullptr;
    libra_error_t (*fnVkFilterChainSetParam)(libra_vk_filter_chain_t*, const char*, float) = nullptr;
};
