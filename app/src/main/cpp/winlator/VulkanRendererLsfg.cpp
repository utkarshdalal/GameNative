#include "VulkanRendererContext.h"
#include "../lsfg/vk_dispatch.h"

#include <cstring>
#include <string>

void VulkanRendererContext::createCompositePass() {
    VkAttachmentDescription att{};
    att.format = swapchainFmt;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

    VkSubpassDescription sp{};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1;
    sp.pColorAttachments = &ref;

    VkSubpassDependency deps[2]{};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;

    VkRenderPassCreateInfo rci{};
    rci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rci.attachmentCount = 1;
    rci.pAttachments = &att;
    rci.subpassCount = 1;
    rci.pSubpasses = &sp;
    rci.dependencyCount = 2;
    rci.pDependencies = deps;

    if (vk_.CreateRenderPass(device, &rci, nullptr, &compositePass) != VK_SUCCESS) {
        RLOG_E("Failed to create composite pass");
    }
}

void VulkanRendererContext::destroyOneComposite(VkCompositeTarget& c) {
    if (c.framebuffer != VK_NULL_HANDLE) { vk_.DestroyFramebuffer(device, c.framebuffer, nullptr); c.framebuffer = VK_NULL_HANDLE; }
    if (c.view != VK_NULL_HANDLE)        { vk_.DestroyImageView(device, c.view, nullptr); c.view = VK_NULL_HANDLE; }
    if (c.image != VK_NULL_HANDLE)       { vk_.DestroyImage(device, c.image, nullptr); c.image = VK_NULL_HANDLE; }
    if (c.memory != VK_NULL_HANDLE)      { vk_.FreeMemory(device, c.memory, nullptr); c.memory = VK_NULL_HANDLE; }
    c.width = c.height = 0;
}

void VulkanRendererContext::destroyCompositeTargets() {
    for (uint32_t i = 0; i < VK_MAX_COMPOSITE_TARGETS; i++) {
        destroyOneComposite(composite[i]);
    }
    compositeCount = 0;
    compositeBuilt = false;
}

bool VulkanRendererContext::createOneComposite(VkCompositeTarget& c, uint32_t w, uint32_t h) {
    c.width = w;
    c.height = h;

    VkImageCreateInfo ic{};
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = swapchainFmt;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
             | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ic, nullptr, &c.image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vk_.GetImageMemoryRequirements(device, c.image, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    try {
        ai.memoryTypeIndex = findMemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    } catch (...) {
        vk_.DestroyImage(device, c.image, nullptr);
        c.image = VK_NULL_HANDLE;
        return false;
    }
    if (vk_.AllocateMemory(device, &ai, nullptr, &c.memory) != VK_SUCCESS) {
        vk_.DestroyImage(device, c.image, nullptr);
        c.image = VK_NULL_HANDLE;
        return false;
    }
    vk_.BindImageMemory(device, c.image, c.memory, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = c.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ic.format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vk_.CreateImageView(device, &vi, nullptr, &c.view) != VK_SUCCESS) {
        vk_.FreeMemory(device, c.memory, nullptr);
        vk_.DestroyImage(device, c.image, nullptr);
        c.memory = VK_NULL_HANDLE;
        c.image = VK_NULL_HANDLE;
        return false;
    }

    if (compositePass == VK_NULL_HANDLE) {
        createCompositePass();
    }

    VkFramebufferCreateInfo fbci{};
    fbci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbci.renderPass = compositePass;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &c.view;
    fbci.width = w;
    fbci.height = h;
    fbci.layers = 1;
    if (vk_.CreateFramebuffer(device, &fbci, nullptr, &c.framebuffer) != VK_SUCCESS) {
        vk_.DestroyImageView(device, c.view, nullptr);
        vk_.FreeMemory(device, c.memory, nullptr);
        vk_.DestroyImage(device, c.image, nullptr);
        c.view = VK_NULL_HANDLE;
        c.memory = VK_NULL_HANDLE;
        c.image = VK_NULL_HANDLE;
        return false;
    }

    return true;
}

bool VulkanRendererContext::createCompositeTargets(uint32_t w, uint32_t h, uint32_t count) {
    if (count == 0 || count > VK_MAX_COMPOSITE_TARGETS) return false;
    if (compositeBuilt && compositeCount == count
        && composite[0].width == w && composite[0].height == h) {
        return true;
    }

    destroyCompositeTargets();
    for (uint32_t i = 0; i < count; i++) {
        if (!createOneComposite(composite[i], w, h)) {
            destroyCompositeTargets();
            return false;
        }
    }
    compositeCount = count;
    compositeBuilt = true;
    return true;
}

void VulkanRendererContext::destroyLsfg() {
    if (!lsfg) return;
    vkr_lsfg_destroy(lsfg);
    lsfg = nullptr;
    framegenRealFrames = 0;
    framegenMadeFrames = 0;
}

void VulkanRendererContext::createLsfg() {
    if (lsfg || lsfgCachePath.empty() || !device || !physicalDevice || !vkd.CreateComputePipelines) return;

    lsfg = vkr_lsfg_create(device, physicalDevice, lsfgCachePath.c_str());
    if (!lsfg) {
        RLOG_E("LSFG shaders unavailable at %s; frame generation stays off", lsfgCachePath.c_str());
        return;
    }
    vkr_lsfg_configure(lsfg, framegenMultiplier ? framegenMultiplier : 2u,
                       framegenTargetRate,
                       framegenFlowScale > 0.0f ? framegenFlowScale : 0.7f,
                       framegenRefreshRate);
}

uint32_t VulkanRendererContext::framegenExtraImages() const {
    if (!framegenRequested) return 0;
    if (framegenTargetRate != 0) return VKR_LSFG_MAX_GENERATIONS;

    uint32_t generations = framegenMultiplier > 1 ? framegenMultiplier - 1 : 1;
    return generations > VKR_LSFG_MAX_GENERATIONS ? VKR_LSFG_MAX_GENERATIONS : generations;
}

bool VulkanRendererContext::compositeFormatSupported() {
    if (swapchainFmt == VK_FORMAT_UNDEFINED) return false;

    VkFormatProperties props{};
    vk_.GetPhysicalDeviceFormatProperties(physicalDevice, swapchainFmt, &props);

    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                                        | VK_FORMAT_FEATURE_BLIT_SRC_BIT
                                        | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    return (props.optimalTilingFeatures & required) == required;
}

void VulkanRendererContext::blitCompositeToSwapchain(VkCommandBuffer cmd, const VkCompositeTarget& src, VkImage dst) {
    transition(cmd, dst,
               VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               0, VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit blit{};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src.width;
    blit.srcOffsets[1].y = (int32_t)src.height;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)swapchainExt.width;
    blit.dstOffsets[1].y = (int32_t)swapchainExt.height;
    blit.dstOffsets[1].z = 1;
    vk_.CmdBlitImage(cmd, src.image, VK_IMAGE_LAYOUT_GENERAL,
                     dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    transition(cmd, dst,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_ACCESS_TRANSFER_WRITE_BIT, 0,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
}

void VulkanRendererContext::setFrameGenerationEnabled(bool enabled) {
    if (!framegenArmed) {
        if (enabled && !framegenArmWarned) {
            framegenArmWarned = true;
            RLOG("Frame generation requested but renderer was not armed at launch; ignoring");
        }
        return;
    }
    if (framegenRequested == enabled) return;
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    framegenRequested = enabled;
    if (!enabled) {
        if (device) vk_.DeviceWaitIdle(device);
        destroyLsfg();
    } else if (device && !lsfgCachePath.empty()) {
        createLsfg();
    }
    fbResized.store(true);
    dirtyCV.notify_one();
    RLOG("Frame generation composite path %s (supported=%d)",
         enabled ? "enabled" : "disabled", (int)framegenSupported);
}

bool VulkanRendererContext::isFrameGenerationSupported() const {
    return framegenArmed && framegenSupported;
}

void VulkanRendererContext::setFrameGenerationShaders(const std::string& cachePath) {
    if (!framegenArmed) return;
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (device) vk_.DeviceWaitIdle(device);
    destroyLsfg();
    lsfgCachePath = cachePath;
    if (framegenRequested && device && !lsfgCachePath.empty()) {
        createLsfg();
    }
    fbResized.store(true);
    dirtyCV.notify_one();
}

void VulkanRendererContext::setSourceFrameCount(uint64_t count) {
    framegenSourceFrames.store(count, std::memory_order_relaxed);
}

void VulkanRendererContext::setFrameGenerationRefreshRate(float hz) {
    const int32_t mhz = hz > 0.0f ? (int32_t)(hz * 1000.0f + 0.5f) : 0;
    framegenRefreshMhz.store(mhz, std::memory_order_relaxed);
}

void VulkanRendererContext::setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct) {
    if (!framegenArmed) return;
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    const uint32_t previous_images = framegenExtraImages();
    framegenMultiplier = multiplier < 2 ? 2u : (uint32_t)multiplier;
    framegenTargetRate = targetRate < 0 ? 0u : (uint32_t)targetRate;
    framegenFlowScale = flowScalePct <= 0 ? 0.7f : (float)flowScalePct / 100.0f;
    if (lsfg) {
        vkr_lsfg_configure(lsfg, framegenMultiplier, framegenTargetRate,
                           framegenFlowScale, framegenRefreshRate);
    }
    if (framegenExtraImages() != previous_images) {
        fbResized.store(true);
        dirtyCV.notify_one();
    }
}

uint64_t VulkanRendererContext::getGeneratedFrameCount() const {
    return framegenMadeFrames;
}

uint64_t VulkanRendererContext::getPresentedFrameCount() const {
    return presentedFrames.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getRealFrameCount() const {
    return framegenRealFrames;
}

uint64_t VulkanRendererContext::getSourceFrameCount() const {
    return framegenSourceFrames.load(std::memory_order_relaxed);
}
