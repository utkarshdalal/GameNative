#include "vkr_lsfg.h"

#include "lsfg_chain.hpp"
#include "lsfg_pacer.hpp"
#include "lsfg_shaders.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <string>

#include <android/log.h>

#define LSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VkrLsfg", __VA_ARGS__)
#define LSFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VkrLsfg", __VA_ARGS__)

namespace {

constexpr uint64_t LSFG_REQUIRED_FRAMES = 3;
constexpr uint64_t LSFG_RECURRENCE_FRAMES = 1;
constexpr uint64_t LSFG_TELEMETRY_INTERVAL = 120;

constexpr float LSFG_FLOW_SCALE_MIN = 0.25f;
constexpr float LSFG_FLOW_SCALE_MAX = 1.0f;
constexpr float LSFG_FLOW_SCALE_STEPS = 20.0f;

VkImageMemoryBarrier MakeTransitionBarrier(VkImage image, VkAccessFlags src_access,
                                           VkAccessFlags dst_access, VkImageLayout old_layout,
                                           VkImageLayout new_layout) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    return barrier;
}

void CopyPresentedFrame(VkCommandBuffer cmd, VkImage source, lsfg::LsfgImage& destination,
                        VkExtent2D extent) {
    const VkImageMemoryBarrier before[] = {
        MakeTransitionBarrier(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
        MakeTransitionBarrier(destination.Handle(),
                              destination.Layout() == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT, destination.Layout(),
                              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    vkd.CmdPipelineBarrier(cmd,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, before);

    VkImageCopy region{};
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.dstSubresource.layerCount = 1;
    region.extent = {extent.width, extent.height, 1};
    vkd.CmdCopyImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.Handle(),
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    const VkImageMemoryBarrier after[] = {
        MakeTransitionBarrier(source, VK_ACCESS_TRANSFER_READ_BIT,
                              VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        MakeTransitionBarrier(destination.Handle(), VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              VK_IMAGE_LAYOUT_GENERAL),
    };
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, nullptr, 0, nullptr, 2, after);

    destination.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
}

}

struct VkrLsfg {
    lsfg::Device device;
    std::string cache_path;
    std::unique_ptr<lsfg::LsfgShaders> shaders;
    std::unique_ptr<lsfg::LsfgChain> chain;
    lsfg::LsfgPacer pacer;
    lsfg::LsfgPlan plan{};

    VkExtent2D built_extent{};
    VkExtent2D peak_guest_extent{};
    VkFormat built_format{VK_FORMAT_UNDEFINED};
    float built_flow_scale{};
    float flow_scale{1.0f};

    uint64_t frame_count{};
    uint64_t last_count{};
    size_t last_generations{};
    uint64_t plan_calls{};
    uint32_t warm_streak{};
    bool warm{};
    bool generated{};
    bool unavailable{};
};

static float lsfg_effective_flow_scale(const VkrLsfg* lsfg, uint32_t width) {
    if (width == 0 || lsfg->peak_guest_extent.width == 0) return lsfg->flow_scale;

    const float ratio =
        static_cast<float>(lsfg->peak_guest_extent.width) / static_cast<float>(width);
    const float stepped = std::ceil(ratio * LSFG_FLOW_SCALE_STEPS) / LSFG_FLOW_SCALE_STEPS;
    return std::clamp(std::min(stepped, lsfg->flow_scale), LSFG_FLOW_SCALE_MIN,
                      LSFG_FLOW_SCALE_MAX);
}

VkrLsfg* vkr_lsfg_create(VkDevice device, VkPhysicalDevice physical_device,
                         const char* cache_path) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE || cache_path == nullptr) {
        return nullptr;
    }

    auto* lsfg = new VkrLsfg();
    lsfg->device = lsfg::Device(device, physical_device);
    lsfg->cache_path = cache_path;

    lsfg->shaders = std::make_unique<lsfg::LsfgShaders>(lsfg->device, lsfg->cache_path.c_str());
    if (!lsfg->shaders->IsValid()) {
        LSFG_LOGW("shader cache at %s did not yield all modules", cache_path);
        delete lsfg;
        return nullptr;
    }

    LSFG_LOGI("frame generation shaders ready");
    return lsfg;
}

void vkr_lsfg_destroy(VkrLsfg* lsfg) {
    delete lsfg;
}

void vkr_lsfg_configure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, float refresh_rate) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    config.multiplier = multiplier;
    config.target_rate = target_rate;
    config.refresh_rate = refresh_rate;
    lsfg->pacer.SetConfig(config);
    lsfg->flow_scale = std::clamp(flow_scale, LSFG_FLOW_SCALE_MIN, LSFG_FLOW_SCALE_MAX);
}

void vkr_lsfg_set_guest_extent(VkrLsfg* lsfg, uint32_t width, uint32_t height) {
    if (!lsfg || width == 0 || height == 0) return;
    lsfg->peak_guest_extent.width = std::max(lsfg->peak_guest_extent.width, width);
    lsfg->peak_guest_extent.height = std::max(lsfg->peak_guest_extent.height, height);
}

void vkr_lsfg_set_refresh_rate(VkrLsfg* lsfg, float refresh_rate) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    if (config.refresh_rate == refresh_rate) return;
    config.refresh_rate = refresh_rate;
    lsfg->pacer.SetConfig(config);
}

bool vkr_lsfg_needs_rebuild(const VkrLsfg* lsfg, uint32_t width, uint32_t height,
                            VkFormat format) {
    if (!lsfg || lsfg->unavailable) return false;
    return !lsfg->chain || lsfg->built_extent.width != width
        || lsfg->built_extent.height != height || lsfg->built_format != format
        || lsfg->built_flow_scale != lsfg_effective_flow_scale(lsfg, width);
}

bool vkr_lsfg_prepare(VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format) {
    if (!lsfg || lsfg->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    if (!vkr_lsfg_needs_rebuild(lsfg, width, height, format)) {
        return lsfg->chain && lsfg->chain->Valid();
    }

    const float scale = lsfg_effective_flow_scale(lsfg, width);

    lsfg->chain.reset();
    lsfg->chain = std::make_unique<lsfg::LsfgChain>(
        lsfg->device, *lsfg->shaders, VkExtent2D{width, height}, format, scale);
    if (!lsfg->chain->Valid()) {
        LSFG_LOGW("chain build failed at %ux%u; frame generation unavailable", width, height);
        lsfg->chain.reset();
        lsfg->unavailable = true;
        return false;
    }

    lsfg->built_extent = VkExtent2D{width, height};
    lsfg->built_format = format;
    lsfg->built_flow_scale = scale;
    lsfg->frame_count = 0;
    lsfg->plan_calls = 0;
    lsfg->warm_streak = 0;
    lsfg->warm = false;
    lsfg->generated = false;
    lsfg->pacer.Reset();
    LSFG_LOGI("chain built at %ux%u, flow %ux%u scale %.2f (preset %.2f, guest %ux%u)", width,
              height, (unsigned)(width * lsfg->built_flow_scale),
              (unsigned)(height * lsfg->built_flow_scale), (double)lsfg->built_flow_scale,
              (double)lsfg->flow_scale, lsfg->peak_guest_extent.width,
              lsfg->peak_guest_extent.height);
    return true;
}

uint32_t vkr_lsfg_plan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames) {
    if (!lsfg || lsfg->unavailable) return 0;

    lsfg->plan = lsfg->pacer.Plan(std::min<size_t>(capacity, VKR_LSFG_MAX_GENERATIONS),
                                  source_frames);

    lsfg->warm = lsfg->plan.warm && lsfg->frame_count + 1 >= LSFG_REQUIRED_FRAMES;
    lsfg->warm_streak = lsfg->warm ? lsfg->warm_streak + 1 : 0;
    lsfg->generated =
        lsfg->warm && lsfg->warm_streak >= LSFG_RECURRENCE_FRAMES && lsfg->plan.generations > 0;

    if ((lsfg->plan_calls++ % LSFG_TELEMETRY_INTERVAL) == 0) {
        const lsfg::LsfgPacerStats stats = lsfg->pacer.Stats();
        const float wanted =
            stats.source_rate * static_cast<float>(lsfg->plan.generations + 1);
        LSFG_LOGI("pace gen=%zu max=%zu cap=%u guest=%.1f loop=%.1f refresh=%.1f target=%.0f "
                  "slots=%.2f drawn=%llu needs=%.1fHz%s%s",
                  lsfg->plan.generations, lsfg->pacer.MaxGenerations(), capacity,
                  (double)stats.source_rate, (double)stats.loop_rate,
                  (double)stats.refresh_rate, (double)stats.target_rate, (double)stats.slots,
                  (unsigned long long)stats.last_drawn, (double)wanted,
                  (stats.refresh_rate > 0.0f && wanted > stats.refresh_rate + 1.0f)
                      ? " PANEL-BOUND"
                      : "",
                  stats.rates_settled ? (lsfg->warm ? "" : " cold") : " sampling");
    }

    return lsfg->generated ? static_cast<uint32_t>(lsfg->plan.generations) : 0;
}

void vkr_lsfg_process(VkrLsfg* lsfg, VkCommandBuffer cmd, VkImage source, uint32_t width,
                      uint32_t height, uint32_t generations) {
    if (!lsfg || !lsfg->chain || !lsfg->chain->Valid()) return;

    const uint64_t count = lsfg->frame_count++;
    lsfg->last_count = count;
    lsfg->last_generations = generations;

    CopyPresentedFrame(cmd, source, lsfg->chain->Input(count), VkExtent2D{width, height});
    if (lsfg->warm) {
        lsfg->chain->DispatchShared(cmd, count);
    }
}

void vkr_lsfg_generate_into(VkrLsfg* lsfg, VkCommandBuffer cmd, uint32_t generation,
                            uint32_t target_index, VkImage target_image, VkImageView target_view,
                            uint32_t width, uint32_t height) {
    if (!lsfg || !lsfg->chain || !lsfg->chain->Valid()) return;
    if (target_index >= lsfg::LSFG_MAX_TARGETS) return;

    lsfg->chain->SetTarget(lsfg->device, lsfg->last_generations, generation, target_index,
                           target_view);
    lsfg->chain->DispatchGeneration(cmd, lsfg->last_count, lsfg->last_generations, generation,
                                    target_index, target_image, VkExtent2D{width, height});
}

void vkr_lsfg_forget_targets(VkrLsfg* lsfg) {
    if (!lsfg || !lsfg->chain) return;
    lsfg->chain->ForgetTargets();
}

void vkr_lsfg_reset(VkrLsfg* lsfg) {
    if (!lsfg) return;
    lsfg->pacer.Reset();
    lsfg->peak_guest_extent = VkExtent2D{};
    lsfg->warm_streak = 0;
    lsfg->warm = false;
    lsfg->generated = false;
    lsfg->plan = {};
}