#include "VulkanLibrashader.h"
#define LIBRA_RUNTIME_VULKAN
#include <librashader.h>
#include <android/log.h>
#include <cstring>
#define LLOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Librashader",__VA_ARGS__)
#define LLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,"Winlator_Librashader",__VA_ARGS__)

VulkanLibrashader::VulkanLibrashader() = default;
VulkanLibrashader::~VulkanLibrashader() {
    unloadLibrary();
}

bool VulkanLibrashader::loadLibrary() {
    if (handle) return true;
    handle = dlopen("liblibrashader.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        lastError = "dlopen failed: " + std::string(dlerror());
        LLOG_E("%s", lastError.c_str());
        return false;
    }

    auto sym = [&](const char* n) { return dlsym(handle, n); };
    fnPresetCreateWithOptions = (decltype(fnPresetCreateWithOptions))sym("libra_preset_create_with_options");
    fnPresetFree              = (decltype(fnPresetFree))sym("libra_preset_free");
    fnPresetCtxCreate         = (decltype(fnPresetCtxCreate))sym("libra_preset_ctx_create");
    fnPresetCtxFree           = (decltype(fnPresetCtxFree))sym("libra_preset_ctx_free");
    fnPresetCtxSetAllowRotation = (decltype(fnPresetCtxSetAllowRotation))sym("libra_preset_ctx_set_allow_rotation");
    fnVkFilterChainCreate     = (decltype(fnVkFilterChainCreate))sym("libra_vk_filter_chain_create");
    fnVkFilterChainFrame      = (decltype(fnVkFilterChainFrame))sym("libra_vk_filter_chain_frame");
    fnVkFilterChainFree       = (decltype(fnVkFilterChainFree))sym("libra_vk_filter_chain_free");
    fnVkFilterChainSetParam   = (decltype(fnVkFilterChainSetParam))sym("libra_vk_filter_chain_set_param");

    if (!fnPresetCreateWithOptions || !fnPresetFree || !fnPresetCtxCreate || !fnPresetCtxFree ||
        !fnVkFilterChainCreate || !fnVkFilterChainFrame || !fnVkFilterChainFree) {
        lastError = "dlsym: missing required librashader symbols";
        LLOG_E("%s", lastError.c_str());
        dlclose(handle);
        handle = nullptr;
        return false;
    }
    if (fnPresetCtxSetAllowRotation) LLOG("librashader: allow_rotation symbol present");
    LLOG("librashader: library loaded");
    return true;
}

bool VulkanLibrashader::init(VkInstance inst, VkPhysicalDevice pdev, VkDevice dev, VkQueue q, PFN_vkGetInstanceProcAddr g) {
    instance = inst; physicalDevice = pdev; device = dev; queue = q; gipa = g;
    return true;
}

void VulkanLibrashader::reloadPreset(const std::string& path) {
    std::lock_guard<std::mutex> lk(mtx);
    presetPath = path;
    // A fresh chain starts with the preset's initial values; force the consumer to
    // re-apply the latest pending params on its next applyPendingParams() call.
    appliedGeneration = 0;
    if (!handle) { lastError = "library not loaded"; return; }

    if (path.empty()) { lastError.clear(); return; }

    // CREATE-FIRST swap (ARMSX2 spirit, hardened): build the new chain completely BEFORE
    // releasing the old one. If the new create fails, the previous chain stays active and the
    // frame degrades gracefully (old shader keeps running) instead of dropping to no-shader or
    // black. Also, the old chain is only freed after the new create's internal vkQueueWaitIdle
    // has guaranteed the previous present CB finished with its frame objects (a free-first
    // sequence could free objects still referenced by an in-flight present CB on Adreno).
    libra_shader_preset_t newPreset = nullptr;
    libra_preset_ctx_t newCtx = nullptr;
    libra_vk_filter_chain_t newChain = nullptr;
    std::string errMsg;
    bool ok = false;
    do {
        if (libra_error_t e = fnPresetCtxCreate(&newCtx)) { errMsg = "preset_ctx_create failed"; break; }
        if (fnPresetCtxSetAllowRotation) fnPresetCtxSetAllowRotation(&newCtx, false);
        libra_preset_opt_t presetOpt{}; presetOpt.version = 2;
        if (libra_error_t e = fnPresetCreateWithOptions(path.c_str(), &newCtx, &presetOpt, &newPreset)) {
            errMsg = "preset_create_with_options failed"; break;
        }
        newCtx = nullptr;   // preset owns the ctx from here on

        libra_device_vk_t vkDev{};
        vkDev.physical_device = physicalDevice;
        vkDev.instance = instance;
        vkDev.device = device;
        vkDev.queue = queue;
        vkDev.entry = gipa;

        filter_chain_vk_opt_t opt{};
        opt.version = 2;
        opt.frames_in_flight = 3;
        opt.force_no_mipmaps = false;
        opt.use_dynamic_rendering = false;
        opt.disable_cache = false;

        if (libra_error_t e = fnVkFilterChainCreate(&newPreset, vkDev, &opt, &newChain)) {
            errMsg = "vk_filter_chain_create failed"; break;
        }
        newPreset = nullptr;   // chain owns the preset from here on
        ok = true;
    } while (false);

    if (!ok) {
        // Failed create: keep the old chain/preset untouched and running.
        lastError = errMsg;
        LLOG_E("librashader: %s (keeping previous chain active)", errMsg.c_str());
        if (newPreset) { fnPresetFree(&newPreset); newPreset = nullptr; }
        if (newCtx) { fnPresetCtxFree(&newCtx); newCtx = nullptr; }
        if (newChain) { fnVkFilterChainFree(&newChain); newChain = nullptr; }
        return;
    }

    // Swap: release the old chain/preset now that the new one is ready (GPU idle).
    if (chain) { fnVkFilterChainFree(&chain); chain = nullptr; }
    if (preset) { fnPresetFree(&preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(&presetCtx); presetCtx = nullptr; }
    chain = newChain;
    preset = newPreset;

    lastError.clear();
    LLOG("librashader: filter chain created for %s", path.c_str());
}

void VulkanLibrashader::setParam(const std::string& name, float value) {
    // Store-only, safe from any thread (UI). Never touches the chain here: the chain is
    // single-threaded and may be mid-frame on the render thread.
    std::lock_guard<std::mutex> lk(paramStoreMtx);
    bool found = false;
    for (auto& p : pendingParams) {
        if (p.first == name) { p.second = value; found = true; break; }
    }
    if (!found) pendingParams.emplace_back(name, value);
    paramGeneration.fetch_add(1, std::memory_order_release);
}

void VulkanLibrashader::applyPendingParams() {
    // Render-thread only. Serializes with applyFrame/reloadPreset via mtx; the chain
    // itself is never touched from any other thread.
    std::lock_guard<std::mutex> lk(mtx);
    if (!chain || !fnVkFilterChainSetParam) return;
    std::vector<std::pair<std::string, float>> params;
    {
        std::lock_guard<std::mutex> lk2(paramStoreMtx);
        const uint64_t gen = paramGeneration.load(std::memory_order_acquire);
        if (gen == appliedGeneration) return;   // fast path: 1 atomic load per frame
        params = pendingParams;
        appliedGeneration = gen;
    }
    for (auto& [name, value] : params) {
        libra_error_t err = fnVkFilterChainSetParam(&chain, name.c_str(), value);
        if (err != 0) LLOG_E("librashader: set_param %s failed", name.c_str());
    }
}

bool VulkanLibrashader::applyFrame(VkCommandBuffer cb, uint64_t frameCount,
    VkImage srcImage, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
    VkImage dstImage, VkFormat dstFormat, uint32_t dstW, uint32_t dstH,
    VkExtent2D viewportExtent, bool clearHistory)
{
    std::lock_guard<std::mutex> lk(mtx);
    if (!chain) { lastError = "no active filter chain"; return false; }

    libra_image_vk_t src{};
    src.handle = srcImage; src.format = srcFormat; src.width = srcW; src.height = srcH;
    libra_image_vk_t out{};
    out.handle = dstImage; out.format = dstFormat; out.width = dstW; out.height = dstH;

    libra_viewport_t vp{};
    vp.x = 0.f; vp.y = 0.f; vp.width = viewportExtent.width; vp.height = viewportExtent.height;

    frame_vk_opt_t fopt{};
    fopt.version = 2;
    fopt.clear_history = clearHistory;
    fopt.aspect_ratio = 0.f;

    libra_error_t err = fnVkFilterChainFrame(&chain, cb, (size_t)frameCount, src, out, &vp, nullptr, &fopt);
    if (err != 0) {
        lastError = "filter_chain_frame failed";
        LLOG_E("%s", lastError.c_str());
        return false;
    }
    return true;
}

void VulkanLibrashader::destroyFilterChain() {
    std::lock_guard<std::mutex> lk(mtx);
    if (!handle) return;
    if (chain) { fnVkFilterChainFree(&chain); chain = nullptr; }
    if (preset) { fnPresetFree(&preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(&presetCtx); presetCtx = nullptr; }
}

void VulkanLibrashader::unloadLibrary() {
    destroyFilterChain();
    std::lock_guard<std::mutex> lk(mtx);
    if (handle) { dlclose(handle); handle = nullptr; }
}
