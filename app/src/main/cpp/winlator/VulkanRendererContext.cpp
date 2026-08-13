#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "VulkanRendererContext.h"
#include <unistd.h>
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <inttypes.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include "window_vert.h"
#include "window_frag.h"

// Runtime-toggleable diagnostic: instead of only reading the env var at startup, also check the
// debug.gamenative.libradiag system property on EVERY call, so adb can switch presentation modes
// live (no app restart / rebuild):
//   0/absent -> normal librashader atlas path
//   1        -> READBACK-OFF grid diagnostics (already on by default)
//   2        -> TEST MODE A: recordCmdBuf (game AHB -> swapchain) into filterCmdBuf. Proves the
//               swapchain present path works (game visible).
//   4        -> TEST MODE B: blit offscreenImage (the filter INPUT, in CAO after the compositor)
//               to the swapchain via an explicit CAO->SRO transition. Proves the offscreen
//               sampler read works after the 3.5 layout fix.
//   5        -> TEST MODE C: present filterOutputImage (applyFrame OUTPUT, in CAO) directly to
//               the swapchain via explicit CAO->SRO. Proves the filter output can be presented
//               without the atlas.
static int libraDiagMode() {
    char buf[8] = {0};
    if (__system_property_get("debug.gamenative.libradiag", buf) > 0) {
        return atoi(buf);
    }
    const char* v = getenv("GAMENATIVE_LIBRA_TEST_BLIT");
    if (v && v[0] != '\0' && v[0] != '0') return 2;
    return 0;
}
static bool libraDiagTestBlit() {
    int m = libraDiagMode();
    return m == 2 || m == 4 || m == 5;
}

static bool gLibraTestBlitOffscreen = []() {
    const char* v = getenv("GAMENATIVE_LIBRA_TEST_BLIT");
    if (!v || v[0] == '\0') return false;
    if (v[0] == '0' || strcmp(v, "false") == 0) return false;
    return true;
}();

// P3-PROBE (temporary diagnostic, off by default): samples processedImage in GENERAL layout
// instead of SHADER_READ_ONLY_OPTIMAL to test hypothesis B-H2 (GENERAL is the tolerated sample
// layout on Adreno; melonDS samples 100% in GENERAL). Enable with env GAMENATIVE_LIBRA_PROBE_GENERAL=1.
// REVERT this probe after measuring on-device. The blitImageToSwapchainLayout refactor is permanent.
static bool gLibraProbeGeneralPresent = []() {
    const char* v = getenv("GAMENATIVE_LIBRA_PROBE_GENERAL");
    if (!v || v[0] == '\0') return false;
    if (v[0] == '0' || strcmp(v, "false") == 0) return false;
    return true;
}();

// P4-PROBE (temporary diagnostic, off by default): blits processedImage into a DEDICATED
// diagDstImage using the melonDS transfer-wide barrier recipe (srcAccess =
// MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE, srcStage = ALL_COMMANDS — melonDS
// VulkanSurfacePresenter.cpp:2258/2270). This tests the TRANSFER path (3.8 tested the SAMPLER
// path; this is a different parameter combination), and blits to a dedicated image — never the
// swapchain. Enable with env GAMENATIVE_LIBRA_PROBE_TRANSFER_WIDE=1.
// REVERT this probe after measuring on-device. transferBarrierWide/diagDstImage are reused by
// the Task 6 atlas fix.
static bool gLibraProbeTransferWide = []() {
    const char* v = getenv("GAMENATIVE_LIBRA_PROBE_TRANSFER_WIDE");
    if (!v || v[0] == '\0') return false;
    if (v[0] == '0' || strcmp(v, "false") == 0) return false;
    return true;
}();

VulkanRendererContext::VulkanRendererContext(ANativeWindow* win, int cW, int cH, void* aHandle)
    : window(win), surfaceWidth(cW), surfaceHeight(cH), containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle)
{
    createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
    createSwapchain(); createRenderPass(); createOffscreenRenderPass(); createDSLayout();
    createPipeline(true, pipeline, renderPass);
    createPipeline(true, offscreenPipeline, offscreenRenderPass);
    libraShader.loadLibrary();
    createFramebuffers(); createCmdPool();
    {
        VkCommandPoolCreateInfo cpci{}; cpci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cpci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; cpci.queueFamilyIndex=graphicsQueueFamilyIndex;
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        if (vk_.CreateCommandPool(device,&cpci,nullptr,&filterCmdPool)!=VK_SUCCESS ||
            vk_.CreateFence(device,&fi,nullptr,&filterFence)!=VK_SUCCESS) throw std::runtime_error("filter sync");
        VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool=filterCmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=2;
        VkCommandBuffer cbs[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
        if (vk_.AllocateCommandBuffers(device,&ai,cbs)!=VK_SUCCESS) throw std::runtime_error("filter cmdbuf");
        filterCmdBuf = cbs[0]; presentCmdBuf = cbs[1];
    }
    createSampler();
    createWinTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; dirtyCV.notify_all();
    if (renderThread.joinable()) renderThread.join();
    std::lock_guard<std::mutex> lk(renderMutex);
    libraShader.destroyFilterChain();
    libraShader.unloadLibrary();
    vk_.DeviceWaitIdle(device);
    for (auto& [id, wt] : texMap) destroyWinTex(wt);
    texMap.clear();
    
    for (auto& wt : deleteQueue) {
        if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, winTexPool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        if (wt.stg  != VK_NULL_HANDLE) { vk_.DestroyBuffer(device, wt.stg, nullptr); vk_.FreeMemory(device, wt.stgMem, nullptr); }
    }
    deleteQueue.clear();
    cleanupSwapchain(); cleanupCursorTex();
    // I1: destroyOffscreenTargets -> destroyBlitPipeline frees blitDS from winTexPool, so it must run
    // BEFORE the descriptor pool is destroyed (freeing descriptor sets from a destroyed pool is UB).
    destroyOffscreenTargets();
    vk_.DestroySampler(device, sampler, nullptr);
    vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
    vk_.DestroyPipeline(device, pipeline, nullptr);
    vk_.DestroyPipeline(device, offscreenPipeline, nullptr);
    vk_.DestroyPipelineLayout(device, pipeLayout, nullptr);
    vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        vk_.DestroySemaphore(device, renderDoneSems[i], nullptr);
        vk_.DestroySemaphore(device, imgAvailSems[i], nullptr);
        vk_.DestroyFence(device, inFlightFences[i], nullptr);
    }
    if (filterCmdBuf != VK_NULL_HANDLE) {
        VkCommandBuffer cbs[2] = {filterCmdBuf, presentCmdBuf};
        vk_.FreeCommandBuffers(device, filterCmdPool, 2, cbs);
        filterCmdBuf = VK_NULL_HANDLE; presentCmdBuf = VK_NULL_HANDLE;
    }
    if (filterCmdPool != VK_NULL_HANDLE) { vk_.DestroyCommandPool(device, filterCmdPool, nullptr); filterCmdPool = VK_NULL_HANDLE; }
    if (filterFence != VK_NULL_HANDLE) { vk_.DestroyFence(device, filterFence, nullptr); filterFence = VK_NULL_HANDLE; }
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, offscreenRenderPass, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
    vk_.DestroyDevice(device, nullptr);
    vk_.DestroySurfaceKHR(instance, surface, nullptr);
    vk_.DestroyInstance(instance, nullptr);
    if (adrenotoolsHandle) { dlclose(adrenotoolsHandle); adrenotoolsHandle = nullptr; }
}

void VulkanRendererContext::loadInstanceDispatch() {
    auto i = [&](const char* name) { return gipa ? gipa(instance, name) : nullptr; };
#define LOAD_I2(fn) vk_.fn = (PFN_vk##fn)i("vk"#fn)
    LOAD_I2(DestroyInstance);
    LOAD_I2(EnumeratePhysicalDevices);
    LOAD_I2(GetPhysicalDeviceProperties);
    LOAD_I2(GetPhysicalDeviceMemoryProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_I2(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_I2(GetPhysicalDeviceSurfacePresentModesKHR);
    LOAD_I2(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_I2(CreateDevice);
    LOAD_I2(DestroySurfaceKHR);
    LOAD_I2(CreateAndroidSurfaceKHR);
    LOAD_I2(GetDeviceProcAddr);
}

void VulkanRendererContext::loadDeviceDispatch() {
    auto d = [&](const char* name) -> PFN_vkVoidFunction {
        return vk_.GetDeviceProcAddr ? vk_.GetDeviceProcAddr(device, name) : nullptr;
    };
#define LOAD_D2(fn) vk_.fn = (PFN_vk##fn)d("vk"#fn)
    LOAD_D2(DestroyDevice);
    LOAD_D2(GetDeviceQueue);
    LOAD_D2(DeviceWaitIdle);
    LOAD_D2(CreateSwapchainKHR);
    LOAD_D2(DestroySwapchainKHR);
    LOAD_D2(GetSwapchainImagesKHR);
    LOAD_D2(AcquireNextImageKHR);
    LOAD_D2(QueuePresentKHR);
    LOAD_D2(QueueSubmit);
    LOAD_D2(CreateRenderPass);
    LOAD_D2(DestroyRenderPass);
    LOAD_D2(CreateFramebuffer);
    LOAD_D2(DestroyFramebuffer);
    LOAD_D2(CreateImageView);
    LOAD_D2(DestroyImageView);
    LOAD_D2(CreateImage);
    LOAD_D2(DestroyImage);
    LOAD_D2(CreateBuffer);
    LOAD_D2(DestroyBuffer);
    LOAD_D2(AllocateMemory);
    LOAD_D2(FreeMemory);
    LOAD_D2(MapMemory);
    LOAD_D2(FlushMappedMemoryRanges);
    LOAD_D2(BindBufferMemory);
    LOAD_D2(BindImageMemory);
    LOAD_D2(GetBufferMemoryRequirements);
    LOAD_D2(GetImageMemoryRequirements);
    LOAD_D2(CreateDescriptorSetLayout);
    LOAD_D2(DestroyDescriptorSetLayout);
    LOAD_D2(CreateDescriptorPool);
    LOAD_D2(DestroyDescriptorPool);
    LOAD_D2(AllocateDescriptorSets);
    LOAD_D2(FreeDescriptorSets);
    LOAD_D2(UpdateDescriptorSets);
    LOAD_D2(CreatePipelineLayout);
    LOAD_D2(DestroyPipelineLayout);
    LOAD_D2(CreateShaderModule);
    LOAD_D2(DestroyShaderModule);
    LOAD_D2(CreateGraphicsPipelines);
    LOAD_D2(DestroyPipeline);
    LOAD_D2(CreateCommandPool);
    LOAD_D2(DestroyCommandPool);
    LOAD_D2(AllocateCommandBuffers);
    LOAD_D2(FreeCommandBuffers);
    LOAD_D2(BeginCommandBuffer);
    LOAD_D2(EndCommandBuffer);
    LOAD_D2(ResetCommandBuffer);
    LOAD_D2(CmdBeginRenderPass);
    LOAD_D2(CmdEndRenderPass);
    LOAD_D2(CmdBindPipeline);
    LOAD_D2(CmdBindDescriptorSets);
    LOAD_D2(CmdDraw);
    LOAD_D2(CmdPushConstants);
    LOAD_D2(CmdSetViewport);
    LOAD_D2(CmdSetScissor);
    LOAD_D2(CmdPipelineBarrier);
    LOAD_D2(CmdCopyImage);
    LOAD_D2(CmdCopyBufferToImage);
    LOAD_D2(CmdCopyImageToBuffer);
    LOAD_D2(CmdBlitImage);
    LOAD_D2(UnmapMemory);
    LOAD_D2(CreateSampler);
    LOAD_D2(DestroySampler);
    LOAD_D2(CreateSemaphore);
    LOAD_D2(DestroySemaphore);
    LOAD_D2(CreateFence);
    LOAD_D2(DestroyFence);
    LOAD_D2(WaitForFences);
    LOAD_D2(ResetFences);
    LOAD_D2(GetFenceStatus);

    vk_.GetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)d("vkGetAndroidHardwareBufferPropertiesANDROID");
}

void VulkanRendererContext::createInstance() {
    RLOG("createInstance: adrenotoolsHandle=%p (custom driver %s)",
        adrenotoolsHandle, adrenotoolsHandle?"ACTIVE":"NOT SET - using stock driver");

    if (adrenotoolsHandle) {
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(adrenotoolsHandle, "vkGetInstanceProcAddr");
    }
    if (!gipa) {
        void* loaderLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (loaderLib)
            gipa = (PFN_vkGetInstanceProcAddr)dlsym(loaderLib, "vkGetInstanceProcAddr");
    }

    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;
    if (vk_.CreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS) throw std::runtime_error("instance");

    loadInstanceDispatch();
}

void VulkanRendererContext::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window=window;
    if (vk_.CreateAndroidSurfaceKHR(instance,&ci,nullptr,&surface)!=VK_SUCCESS) throw std::runtime_error("surface");
}

void VulkanRendererContext::pickPhysicalDevice() {
    uint32_t n=0; vk_.EnumeratePhysicalDevices(instance,&n,nullptr);
    std::vector<VkPhysicalDevice> devs(n); vk_.EnumeratePhysicalDevices(instance,&n,devs.data());
    physicalDevice = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex = 0;
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physicalDevice = d;
                graphicsQueueFamilyIndex = i;
                return;
            }
        }
    }
    if (n > 0) physicalDevice = devs[0];
}

void VulkanRendererContext::createLogicalDevice() {
    float p=1.f;
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=1; qi.pQueuePriorities=&p;

    PFN_vkEnumerateDeviceExtensionProperties enumDevExts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    { uint32_t n=0; if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,nullptr);
      std::vector<VkExtensionProperties> av(n);
      if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,av.data());
      for (auto& e:av) {
          if (strcmp(e.extensionName,"VK_EXT_filter_cubic")==0
           || strcmp(e.extensionName,"VK_IMG_filter_cubic")==0) cubicSupported=true;
      } }
    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    if (cubicSupported) extList.push_back("VK_EXT_filter_cubic");
    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();
    if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
}

void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);
    swapchainExt=(caps.currentExtent.width!=0xFFFFFFFF)?caps.currentExtent:VkExtent2D{(uint32_t)surfaceWidth,(uint32_t)surfaceHeight};
    uint32_t fmtN=0; vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtN); vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,fmts.data());
    swapchainFmt = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t imgCount=caps.minImageCount+1;
    if (caps.maxImageCount>0&&imgCount>caps.maxImageCount) imgCount=caps.maxImageCount;

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}
    if(verboseLog){
        std::string pmList;
        for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
        RLOG("createSwapchain: %dx%d fmt=%d supportedPresentModes=[%s] chosen=%d req=%d",
            swapchainExt.width,swapchainExt.height,(int)swapchainFmt,pmList.c_str(),(int)presentMode,(int)requestedPresentMode);
    }

    VkSurfaceTransformFlagBitsKHR pre=
        (caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)?
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:caps.currentTransform;

    VkCompositeAlphaFlagBitsKHR compositeAlpha=
        (caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    VkSwapchainKHR oldSwapchain=swapchain;
    VkSwapchainCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface=surface; ci.minImageCount=imgCount; ci.imageFormat=swapchainFmt;
    ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; ci.imageExtent=swapchainExt;
    ci.imageArrayLayers=1; ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount);
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
    swapchainViews.resize(imgCount);
    for (size_t i=0;i<imgCount;i++) {
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=swapchainImages[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=swapchainFmt;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkComponentMapping mapping{};
        mapping.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components = mapping;
        if (vk_.CreateImageView(device,&vi,nullptr,&swapchainViews[i])!=VK_SUCCESS) throw std::runtime_error("imgview");
    }
}

void VulkanRendererContext::createRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&renderPass)!=VK_SUCCESS) throw std::runtime_error("renderpass");
}

void VulkanRendererContext::createOffscreenRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    // 3.5 fix (was lost when the WIP blob was restored): keep the offscreen attachment in
    // COLOR_ATTACHMENT_OPTIMAL after the render pass and let the CALLER do the explicit
    // CAO->SHADER_READ_ONLY_OPTIMAL transition before each sampler read. The automatic
    // finalLayout transition to SRO left the image unreadable by the sampler on Adreno
    // (transfer reads worked, sampler blits returned black).
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&offscreenRenderPass)!=VK_SUCCESS) throw std::runtime_error("offscreen renderpass");
}

void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}
 

VkShaderModule VulkanRendererContext::makeShader(const uint32_t* code, size_t sz) {
    VkShaderModuleCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize=sz; ci.pCode=code; VkShaderModule m;
    if (vk_.CreateShaderModule(device,&ci,nullptr,&m)!=VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}

void VulkanRendererContext::createPipeline(bool blend, VkPipeline& out, VkRenderPass rp) {
    if (pipeLayout==VK_NULL_HANDLE) {
        VkPushConstantRange pc{}; pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.size=sizeof(WindowPushConstants);
        VkPipelineLayoutCreateInfo li{}; li.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount=1; li.pSetLayouts=&dsLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&pc;
        if (vk_.CreatePipelineLayout(device,&li,nullptr,&pipeLayout)!=VK_SUCCESS) throw std::runtime_error("pipelayout");
    }
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_frag_code,sizeof(window_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=blend?VK_TRUE:VK_FALSE;
    if (blend){ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;ba.alphaBlendOp=VK_BLEND_OP_ADD;}
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=rp; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&out)!=VK_SUCCESS) throw std::runtime_error("pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
}


void VulkanRendererContext::createCursorPipeline() {  }
void VulkanRendererContext::createFramebuffers() {
    swapchainFBs.resize(swapchainViews.size());
    for (size_t i=0;i<swapchainViews.size();i++) {
        VkImageView att[]={swapchainViews[i]};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=renderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=swapchainExt.width; fi.height=swapchainExt.height; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&swapchainFBs[i])!=VK_SUCCESS) throw std::runtime_error("fb");
    }
}

void VulkanRendererContext::createCmdPool() {
    VkCommandPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; ci.queueFamilyIndex=graphicsQueueFamilyIndex;
    if (vk_.CreateCommandPool(device,&ci,nullptr,&cmdPool)!=VK_SUCCESS) throw std::runtime_error("cmdpool");
}

void VulkanRendererContext::createSampler() {
    bool useCubic = (filterMode == 2) && cubicSupported;
    VkFilter filter = (filterMode == 1) ? VK_FILTER_NEAREST
                    : (useCubic)         ? VK_FILTER_CUBIC_EXT
                    :                      VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d, cubicSupported=%d)",
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR_FALLBACK"):filterMode==1?"NEAREST":"LINEAR",
        filterMode, (int)cubicSupported);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    if (vk_.CreateSampler(device,&ci,nullptr,&sampler)!=VK_SUCCESS) throw std::runtime_error("sampler");
}

void VulkanRendererContext::createWinTexPool() {

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 129};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=129;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}


void VulkanRendererContext::createCursorDS() {
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=winTexPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}

void VulkanRendererContext::createCmdBufs() {
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=MAX_FRAMES_IN_FLIGHT;
    if (vk_.AllocateCommandBuffers(device,&ai,cmdBufs.data())!=VK_SUCCESS) throw std::runtime_error("cmdbuf");
}

void VulkanRendererContext::createSyncObjects() {
    imgAvailSems.resize(MAX_FRAMES_IN_FLIGHT); renderDoneSems.resize(MAX_FRAMES_IN_FLIGHT); inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
    for (uint32_t i=0;i<MAX_FRAMES_IN_FLIGHT;i++) {
        if (vk_.CreateSemaphore(device,&si,nullptr,&imgAvailSems[i])!=VK_SUCCESS||
            vk_.CreateSemaphore(device,&si,nullptr,&renderDoneSems[i])!=VK_SUCCESS||
            vk_.CreateFence(device,&fi,nullptr,&inFlightFences[i])!=VK_SUCCESS) throw std::runtime_error("sync");
    }
}

void VulkanRendererContext::cleanupSwapchain() {
    for (auto fb:swapchainFBs) vk_.DestroyFramebuffer(device,fb,nullptr); swapchainFBs.clear();
    for (auto iv:swapchainViews) vk_.DestroyImageView(device,iv,nullptr); swapchainViews.clear();
    if (!cmdBufs.empty()){vk_.FreeCommandBuffers(device,cmdPool,(uint32_t)cmdBufs.size(),cmdBufs.data());cmdBufs.clear();}
    if (swapchain!=VK_NULL_HANDLE) { vk_.DestroySwapchainKHR(device,swapchain,nullptr); swapchain=VK_NULL_HANDLE; }
}

uint32_t VulkanRendererContext::findMemType(uint32_t filter, VkMemoryPropertyFlags props) {
    for (uint32_t i=0;i<memProperties.memoryTypeCount;i++)
        if ((filter&(1u<<i))&&(memProperties.memoryTypes[i].propertyFlags&props)==props) return i;
    throw std::runtime_error("memtype");
}

void VulkanRendererContext::createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem)
{
    VkBufferCreateInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO; bi.size=sz; bi.usage=usage; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateBuffer(device,&bi,nullptr,&buf)!=VK_SUCCESS) throw std::runtime_error("buffer");
    VkMemoryRequirements req; vk_.GetBufferMemoryRequirements(device,buf,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,props);
    if (vk_.AllocateMemory(device,&ai,nullptr,&mem)!=VK_SUCCESS) throw std::runtime_error("bufmem");
    vk_.BindBufferMemory(device,buf,mem,0);
}

VkCommandBuffer VulkanRendererContext::beginOneTime() {
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandPool=cmdPool; ai.commandBufferCount=1;
    VkCommandBuffer cb; vk_.AllocateCommandBuffers(device,&ai,&cb);
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.BeginCommandBuffer(cb,&bi); return cb;
}

void VulkanRendererContext::endOneTime(VkCommandBuffer cb) {
    vk_.EndCommandBuffer(cb);
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount=1; si.pCommandBuffers=&cb;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; VkFence fence;
    vk_.CreateFence(device,&fi,nullptr,&fence);
    vk_.QueueSubmit(graphicsQueue,1,&si,fence); vk_.WaitForFences(device,1,&fence,VK_TRUE,UINT64_MAX);
    vk_.DestroyFence(device,fence,nullptr); vk_.FreeCommandBuffers(device,cmdPool,1,&cb);
}

void VulkanRendererContext::transition(VkCommandBuffer cb, VkImage img,
    VkImageLayout ol, VkImageLayout nl, VkAccessFlags sa, VkAccessFlags da,
    VkPipelineStageFlags ss, VkPipelineStageFlags ds)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=ol; b.newLayout=nl; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.srcAccessMask=sa; b.dstAccessMask=da;
    vk_.CmdPipelineBarrier(cb,ss,ds,0,0,nullptr,0,nullptr,1,&b);
}

// melonDS transfer-wide barrier (VulkanSurfacePresenter.cpp:2258/2270): fixed wide srcAccess and
// srcStage=ALL_COMMANDS. Used by the P4 probe and by the Task 6 atlas fix.
void VulkanRendererContext::transferBarrierWide(VkCommandBuffer cb, VkImage img,
    VkImageLayout oldLayout, VkImageLayout newLayout, VkAccessFlags dstAccess, VkPipelineStageFlags dstStage)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=oldLayout; b.newLayout=newLayout;
    b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    b.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.dstAccessMask=dstAccess;
    vk_.CmdPipelineBarrier(cb,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,dstStage,0,0,nullptr,0,nullptr,1,&b);
}

bool VulkanRendererContext::createWinTexResources(WinTex& wt, int w, int h) {

    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return false;
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,wt.img,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device,&ai,nullptr,&wt.mem)!=VK_SUCCESS){vk_.DestroyImage(device,wt.img,nullptr);wt.img=VK_NULL_HANDLE;return false;}
    vk_.BindImageMemory(device,wt.img,wt.mem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vi.components={swapRB?VK_COMPONENT_SWIZZLE_B:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,swapRB?VK_COMPONENT_SWIZZLE_R:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorSetAllocateInfo dsai{}; dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    if (vk_.AllocateDescriptorSets(device,&dsai,&wt.ds)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=wt.view; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=wt.ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    VkDeviceSize stgSz=(VkDeviceSize)w*h*4;
    createBuffer(stgSz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,wt.stg,wt.stgMem);
    vk_.MapMemory(device,wt.stgMem,0,stgSz,0,&wt.mapped);
    wt.cap=stgSz; wt.w=w; wt.h=h; wt.needsTransition=true;
    return true;
}

bool VulkanRendererContext::importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb) {
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID)
        return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext=&fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device,ahb,&props)!=VK_SUCCESS)
        return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);

    // Use the driver-reported Vulkan format for this AHB when available (e.g. BGRA_8888 ->
    // VK_FORMAT_B8G8R8A8_UNORM). Only when the format is opaque (VK_FORMAT_UNDEFINED) do we
    // need VkExternalFormatANDROID + VK_FORMAT_UNDEFINED; the previous code passed a VkFormat
    // enum value as the "external format" together with a real format, which is invalid usage
    // and made the Adreno driver sample the imported image as black (0,0,0,0).
    VkFormat ahbFmt = fmtP.format;
    if (ahbFmt == VK_FORMAT_UNDEFINED)
        ahbFmt = swapRB ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;
    RLOG("importAHBToWinTex: %dx%d desc.format=%u props.format=%d externalFormat=%llu swapRB=%d using=%d",
        (int)desc.width, (int)desc.height, (unsigned)desc.format, (int)fmtP.format,
        (unsigned long long)fmtP.externalFormat, (int)swapRB, (int)ahbFmt);

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ii{};
    ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext=&emi; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.format=ahbFmt;
    ii.extent={desc.width,desc.height,1};
    ii.mipLevels=1; ii.arrayLayers=1; ii.samples=VK_SAMPLE_COUNT_1_BIT;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS)
        return false;

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer=ahb;

    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext=&imp; ded.image=wt.img;

    VkMemoryAllocateInfo mai{};
    mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext=&ded; mai.allocationSize=props.allocationSize;
    mai.memoryTypeIndex=findMemType(props.memoryTypeBits,0);
    if (vk_.AllocateMemory(device,&mai,nullptr,&wt.mem)!=VK_SUCCESS){
        vk_.DestroyImage(device,wt.img,nullptr);
        wt.img=VK_NULL_HANDLE;
        return false;
    }
    vk_.BindImageMemory(device,wt.img,wt.mem,0);

    VkImageViewCreateInfo vi{};
    vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D;
    vi.format=ahbFmt;
    vi.components={VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,
                   VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){
        destroyWinTex(wt);
        return false;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    VkResult dsRes=vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY){
        RLOG_E("importAHBToWinTex: descriptor pool exhausted for AHB texture");
        destroyWinTex(wt);
        return false;
    }
    if (dsRes!=VK_SUCCESS){ destroyWinTex(wt); return false; }

    // Sampled in SHADER_READ_ONLY_OPTIMAL (verified working on Adreno: the compositor's
    // swapchain path draws the same AHB fine in SRO). The driver-reported format above is
    // what fixes the import (the old code passed a VkFormat enum value as the external
    // format, which is invalid usage).
    VkDescriptorImageInfo dii{};
    dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    dii.imageView=wt.view; dii.sampler=sampler;

    VkWriteDescriptorSet wr{};
    wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wr.dstSet=wt.ds; wr.dstBinding=0;
    wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);

    wt.needsTransition=true;
    wt.isAHB=true;
    wt.w=(int)desc.width;
    wt.h=(int)desc.height;
    return true;
}

void VulkanRendererContext::destroyWinTex(WinTex& wt) {
    if (wt.isAHB) {


        wt = {};
        return;
    }
    if (wt.img!=VK_NULL_HANDLE || wt.stg!=VK_NULL_HANDLE) {
        
        WinTex deferred = wt;
        deferred.isAHB = false;
        deleteQueue.push_back(deferred);
    }
    wt={};
}

void VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return;
    cleanupCursorTex();
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    vk_.CreateImage(device,&ii,nullptr,&cursorImg);
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vk_.AllocateMemory(device,&ai,nullptr,&cursorMem); vk_.BindImageMemory(device,cursorImg,cursorMem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vk_.CreateImageView(device,&vi,nullptr,&cursorView);
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);

    cursorTexW=w; cursorTexH=h;
}

void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);cursorStg=VK_NULL_HANDLE;cursorStgP=nullptr;cursorStgC=0;}
    cursorTexW=0; cursorTexH=0;
}

void VulkanRendererContext::ensureCursorStaging(VkDeviceSize sz) {
    if (cursorStgC>=sz) return;
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);}
    createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,cursorStg,cursorStgM);
    vk_.MapMemory(device,cursorStgM,0,sz,0,&cursorStgP); cursorStgC=sz;
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis)
{
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) throw std::runtime_error("begin cb");







    ahbTransitions.clear(); preUpload.clear(); postUpload.clear();

    for (auto& d : draws) {
        if (d.img==VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        } else if (!d.isAHB && (d.needsTransition || d.upload!=VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    if (!preUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)preUpload.size(), preUpload.data());


    for (auto& d : draws) {
        if (d.isAHB || d.upload==VK_NULL_HANDLE || d.img==VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset=0; r.bufferRowLength=0; r.bufferImageHeight=0;
        r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)d.w,(uint32_t)d.h,1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    bool cursorDrawn = curVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE;
    bool hasCursorCopy = hasCursorUpload && cursorImg!=VK_NULL_HANDLE && cursorUpload!=VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.image=cursorImg; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy r{}; r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)curW,(uint32_t)curH,1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)postUpload.size(), postUpload.data());


    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass=renderPass; rpi.framebuffer=swapchainFBs[imgIdx]; rpi.renderArea={{0,0},swapchainExt};
    VkClearValue clr={{{0.f,0.f,0.f,1.f}}}; rpi.clearValueCount=1; rpi.pClearValues=&clr;

    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,0,(float)swapchainExt.width,(float)swapchainExt.height,0,1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0,0},swapchainExt}; vk_.CmdSetScissor(cb, 0, 1, &sc);

    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    for (auto& d : draws) {
        if (d.ds==VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &d.ds, 0, nullptr);
        WindowPushConstants pc{};
        pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f;
        pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
        pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f;
        pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
        pc.useTexAlpha = 0;
        pc.effectId = activeEffectId;
        pc.sharpness = activeSharpness;
        pc.resW = (float)std::max(1, d.w);
        pc.resH = (float)std::max(1, d.h);
        pc.effectMask = activeEffectMask;
        pc.brightness = activeBrightness;
        pc.contrast = activeContrast;
        pc.gamma = activeGamma;
        pc.outW = std::max(1.0f, (float)d.w * sx / cw * (float)swapchainExt.width);
        pc.outH = std::max(1.0f, (float)d.h * sy / ch * (float)swapchainExt.height);
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {

        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &cursorDS, 0, nullptr);
        float cx=(float)std::max(0,(int)ptrX-curHotX), cy=(float)std::max(0,(int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.useTexAlpha = 1;
        cpc.effectId = 0;
        cpc.sharpness = 0.f;
        cpc.resW = (float)std::max(1, (int)curW);
        cpc.resH = (float)std::max(1, (int)curH);
        cpc.effectMask = 0;
        cpc.brightness = 0.0f;
        cpc.contrast = 0.0f;
        cpc.gamma = 1.0f;
        cpc.outW = (float)std::max(1, (int)curW);
        cpc.outH = (float)std::max(1, (int)curH);
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(cpc), &cpc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus!=VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed with status=%d (swapRB=%d draws=%zu imgIdx=%u)",
            (int)endStatus, (int)swapRB, draws.size(), imgIdx);
        throw std::runtime_error("end cb");
    }
}

void VulkanRendererContext::renderLoop() {

    while (isRunning) {
        { std::unique_lock<std::mutex> lk(dirtyMutex);
          dirtyCV.wait(lk,[this]{
              return !isRunning||(!surfaceDetached.load()&&(needsRender.load()||fbResized.load()))||cursorMoved.load(); }); }
        if (!isRunning) break;

        if (swapchain == VK_NULL_HANDLE || cmdBufs.empty()) continue;
        try {
            renderFrame();
        } catch (const std::exception& e) {
            RLOG_E("renderFrame threw: %s", e.what());
        } catch (...) {
            RLOG_E("renderFrame threw (unknown exception)");
        }
    }
}

void VulkanRendererContext::flushDeleteQueue() {


    std::lock_guard<std::mutex> lk(renderMutex);
    if (deleteQueue.empty()) return;
    vk_.DeviceWaitIdle(device);
    for (auto& wt:deleteQueue) {
        if (wt.ds  !=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
        if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
        if (wt.img !=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
        if (wt.mem !=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
        if (wt.stg !=VK_NULL_HANDLE){vk_.DestroyBuffer(device,wt.stg,nullptr);vk_.FreeMemory(device,wt.stgMem,nullptr);}
    }
    deleteQueue.clear();
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);

    if (surfaceDetached.load(std::memory_order_acquire)) return;
    if (scanoutActive.load()) {
        applyScanoutBuffer();

        if (!scanoutBlackFrameDone.load()) {
            scanoutBlackFrameDone.store(true);

            std::lock_guard<std::mutex> lk(renderMutex);
            renderList.clear();
        } else {
            return;
        }
    } else {
        scanoutBlackFrameDone.store(false);
    }
    if (surfaceWidth==0||surfaceHeight==0) return;

    if (fbResized.load()) {
        for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
        cleanupSwapchain();
        bool ok=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
ok=true;}catch(...){}
        // I3: offscreen/filter/atlas targets keep the old extents after a surface resize. All fences
        // are idle here, so drop the targets; the next libra frame recreates them via
        // createOffscreenTargets(surfaceWidth, surfaceHeight) at the new size (it destroys first).
        // Guarded so the non-libra path (offscreenImage never created) is unaffected.
        if (offscreenImage != VK_NULL_HANDLE) destroyOffscreenTargets();
        if (ok) fbResized.store(false);
        return;
    }

    if (currentFrame >= cmdBufs.size() || cmdBufs[currentFrame] == VK_NULL_HANDLE) return;
    bool currentFenceWaited = false;
    if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[currentFrame]) == VK_NOT_READY) {
        vk_.WaitForFences(device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX);
        currentFenceWaited = true;
    }

    uint32_t imgIdx;
    VkResult res=vk_.AcquireNextImageKHR(device,swapchain,UINT64_MAX,imgAvailSems[currentFrame],VK_NULL_HANDLE,&imgIdx);
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR){fbResized.store(true);return;}
    if (res!=VK_SUCCESS&&res!=VK_SUBOPTIMAL_KHR) return;
    if (imgIdx >= swapchainFBs.size() || imgIdx >= swapchainImages.size()) {
        RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
            imgIdx, swapchainFBs.size(), swapchainImages.size());
        return;
    }

    if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
    if (imgInFlight[imgIdx]!=VK_NULL_HANDLE &&
        (!currentFenceWaited || imgInFlight[imgIdx] != inFlightFences[currentFrame])) {
        if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, imgInFlight[imgIdx]) == VK_NOT_READY) {
            if (vk_.WaitForFences(device,1,&imgInFlight[imgIdx],VK_TRUE, 500000000ULL) != VK_SUCCESS) {
                // GPU wedge guard: never freeze the render loop forever on a stalled fence.
                // Log loudly and skip the frame; the loop stays alive for diagnosis/recovery.
                RLOG_E("renderFrame: imgInFlight fence timeout (GPU stalled?) - skipping frame");
                return;
            }
        }
    }
    imgInFlight[imgIdx]=inFlightFences[currentFrame];

    vk_.ResetCommandBuffer(cmdBufs[currentFrame],0);

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;
    // Preset requests (JNI deferred loads + the debug.gamenative.preset test hook) are applied
    // HERE on the render thread, BEFORE the libraPath check: the FIRST load must run while
    // libraShaderActive is still false (processing inside `if (libraPath)` deadlocked forever).
    // Running reloads on the render thread also prevents UI-thread queue races (driver crash).
    {
        std::string presetToLoad;
        bool clearRequested = false;
        {
            std::lock_guard<std::mutex> lk(presetReqMtx);
            if (hasPendingPreset) { presetToLoad = pendingPresetPath; hasPendingPreset = false; }
            if (hasPendingClear) { hasPendingClear = false; clearRequested = true; }
        }
        if (clearRequested) {
            // Per-shader toggle-off (spec 2026-08-11): destroy the chain so the frame
            // renders unshaded while librashader stays ENABLED. Render thread only;
            // destroyFilterChain takes the wrapper's mtx (serialized with applyFrame).
            RLOG("librashader: clearing preset chain (shader off, system enabled)");
            libraShader.destroyFilterChain();
            libraShaderActive.store(false);
            libraShaderPresetPath.clear();
            libraChainFailed = false;
        }
        static std::string sLastPresetOverride;
        char pbuf[512] = {0};
        if (__system_property_get("debug.gamenative.preset", pbuf) > 0 && pbuf[0] != '\0') {
            if (sLastPresetOverride != pbuf) {
                sLastPresetOverride = pbuf;
                // Only honor the override when it names an existing preset file: a stale
                // debug.gamenative.preset left over from a previous session (or a bad value)
                // must NOT hijack the UI's preset request and drop the chain to inactive.
                if (access(pbuf, F_OK) == 0) {
                    presetToLoad = pbuf;
                    RLOG("librashader: runtime preset override -> %s", pbuf);
                } else {
                    RLOG_E("librashader: ignoring runtime preset override (no such file): %s", pbuf);
                }
            }
        } else if (!sLastPresetOverride.empty()) {
            sLastPresetOverride.clear();
        }
        if (!presetToLoad.empty()) {
            RLOG("librashader: loading preset: %s", presetToLoad.c_str());
            libraShaderPresetPath = presetToLoad;
            if (libraShader.isLoaded()) {
                libraShader.init(instance, physicalDevice, device, graphicsQueue, gipa);
            }
            libraShader.reloadPreset(presetToLoad);
            libraShaderActive.store(libraShader.isActive());
            // New preset requested -> retry the chain (clear the failure latch).
            libraChainFailed = false;
            RLOG("librashader: preset chain active=%d", (int)libraShader.isActive());
            if (!libraShader.isActive()) RLOG_E("librashader: filter chain create failed: %s", libraShader.getLastError().c_str());
            libraNeedsHistoryClear = true;
        }
    }

    bool libraPath = libraShaderActive.load() && libraShaderEnabled.load();
    VkCommandBuffer presentCB = VK_NULL_HANDLE;
    bool cursorDrawnInPass = false;
    bool clearHistoryThisFrame = false;

    {
        std::lock_guard<std::mutex> lk(renderMutex);


        if (!deleteQueue.empty()) {
            for (auto& wt:deleteQueue) {
                if (wt.ds  !=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
                if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
                if (wt.img !=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
                if (wt.mem !=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
                if (wt.stg !=VK_NULL_HANDLE){vk_.DestroyBuffer(device,wt.stg,nullptr);vk_.FreeMemory(device,wt.stgMem,nullptr);}
            }
            deleteQueue.clear();
        }

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorTexW; curH=cursorTexH;
        curVis=cursorVisible.load();

        frameDraws.clear();
        for (auto& re:renderList) {
            auto it=texMap.find(re.id);
            if (it==texMap.end()) continue;
            WinTex& wt=it->second;
            if (wt.ds==VK_NULL_HANDLE) continue;
            DrawEntry de{wt.img,wt.ds,VK_NULL_HANDLE,re.x,re.y,wt.w,wt.h};
            de.isAHB=wt.isAHB;
            if (wt.needsTransition) { de.needsTransition=true; wt.needsTransition=false; }
            if (wt.dirty && !wt.isAHB && wt.stg!=VK_NULL_HANDLE) {
                de.upload=wt.stg;
                wt.dirty=false;
            } else if (wt.isAHB) {
                wt.dirty=false;
            }
            frameDraws.push_back(de);
        }

        if (libraPath) {
            clearHistoryThisFrame = libraNeedsHistoryClear;
            libraNeedsHistoryClear = false;
        }

        if (isCursorImageDirty.load() && cursorImg!=VK_NULL_HANDLE && !cursorPixels.empty()) {
            VkDeviceSize csz=(VkDeviceSize)cursorTexW*cursorTexH*4;
            ensureCursorStaging(csz);
            isCursorImageDirty.store(false); hasCurUpload=true; curUpload=cursorStg;

            cursorUploadSize = csz;
        }
    }


    if (hasCurUpload && cursorStgP && !cursorPixels.empty())
        memcpy(cursorStgP, cursorPixels.data(), cursorUploadSize);

    bool effectiveCurVis = curVis && !scanoutActive.load();

    if (libraPath) {
        presentCB = filterCmdBuf;  // default; the atlas path switches to presentCmdBuf
        if (offscreenImage == VK_NULL_HANDLE || offscreenView == VK_NULL_HANDLE) {
            createOffscreenTargets(surfaceWidth, surfaceHeight);
        }
        // DIAG: log how many draws the compositor will record into offscreen.
        // If 0, the native renderList was not repopulated after tearDownScanout.
        static uint64_t sLibraDiagCount = 0;
        if ((sLibraDiagCount++ & 0x3F) == 0)
            RLOG("librashader: libraPath draws=%zu renderList=%zu scanoutActive=%d",
                frameDraws.size(), renderList.size(), (int)scanoutActive.load());

        recordCompositorPass(cmdBufs[currentFrame], frameDraws,
            frameAhbTransitions, framePreUpload, framePostUpload,
            curUpload, hasCurUpload,
            ox, oy, sx, sy, cw, ch, curW, curH);

        vk_.ResetFences(device,1,&filterFence);
        VkSubmitInfo si1{}; si1.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si1.commandBufferCount=1; si1.pCommandBuffers=&cmdBufs[currentFrame];
        if (vk_.QueueSubmit(graphicsQueue,1,&si1,filterFence)!=VK_SUCCESS) {
            vk_.DestroyFence(device,filterFence,nullptr);
            VkFenceCreateInfo ffi{}; ffi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; ffi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
            vk_.CreateFence(device,&ffi,nullptr,&filterFence);
            return;
        }
        if (vk_.WaitForFences(device,1,&filterFence,VK_TRUE, 500000000ULL) != VK_SUCCESS) {
            // GPU wedge guard (same rationale as the imgInFlight wait above): a wedged queue
            // must not freeze the loop; skip this frame's present and keep diagnosing.
            RLOG_E("renderFrame: compositor fence timeout (GPU wedged?) - skipping frame");
            return;
        }

        // DIAG: confirm the filter's INPUT (offscreenImage) contains the game.
        readbackOffscreenDiag();
        {
            void* rb = nullptr;
            static uint64_t sReadbackOffLog = 0;
            const bool doLog = ((sReadbackOffLog++ & 0x3F) == 0);
            if (vk_.MapMemory(device, processedReadbackMem, 0, 26 * 4, 0, &rb) == VK_SUCCESS && rb) {
                uint32_t grid[25];
                memcpy(grid, rb, 25 * 4);
                uint32_t center = 0; memcpy(&center, (char*)rb + 25 * 4, 4);
                vk_.UnmapMemory(device, processedReadbackMem);
                if (doLog) {
                    RLOG("READBACK-OFF-GRID frame=%llu", (unsigned long long)libraFrameCount);
                    for (int r = 0; r < 5; ++r) {
                        RLOG("  row%d: %08x %08x %08x %08x %08x", r,
                            grid[r*5+0], grid[r*5+1], grid[r*5+2], grid[r*5+3], grid[r*5+4]);
                    }
                }
                RLOG("READBACK-OFF frame=%llu center=%08x tl=%08x mid=%08x bl=%08x",
                    (unsigned long long)libraFrameCount, center,
                    grid[0], grid[12], grid[22]);
            }
        }

        vk_.ResetCommandBuffer(filterCmdBuf,0);

        if (libraDiagTestBlit()) {
            int dm = libraDiagMode();
            if (dm == 4) {
                // TEST MODE B: blit offscreen (filter INPUT, CAO after compositor) -> swapchain.
                RLOG("librashader: TEST MODE B offscreen->swapchain (CAO->SRO explicit)");
                VkCommandBufferBeginInfo fbi{}; fbi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                if (vk_.BeginCommandBuffer(filterCmdBuf,&fbi)!=VK_SUCCESS) throw std::runtime_error("begin filter cb");
                // melonDS-wide barrier (srcAccess=MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE,
                // srcStage=ALL_COMMANDS) — the narrow COLOR_ATTACHMENT_OUTPUT transition may not
                // resolve GMEM->memory on Adreno for the sampler path.
                transferBarrierWide(filterCmdBuf, offscreenImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                blitImageToSwapchain(filterCmdBuf, imgIdx, offscreenView, sampler);
                if (vk_.EndCommandBuffer(filterCmdBuf)!=VK_SUCCESS) throw std::runtime_error("end filter cb");
            } else if (dm == 5) {
                // TEST MODE C: present filterOutputImage (applyFrame OUTPUT, CAO) directly.
                // Runs applyFrame into filterOutputImage, then CAO->SRO + blit to the swapchain.
                RLOG("librashader: TEST MODE C filterOutput->swapchain (no atlas)");
                VkCommandBufferBeginInfo fbi{}; fbi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                if (vk_.BeginCommandBuffer(filterCmdBuf,&fbi)!=VK_SUCCESS) throw std::runtime_error("begin filter cb");
                transferBarrierWide(filterCmdBuf, filterOutputImage,
                    filterOutputLayout, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                filterOutputLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                transferBarrierWide(filterCmdBuf, offscreenImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                libraShader.applyPendingParams();   // render-thread param application (generation)
                bool ok = libraShader.applyFrame(filterCmdBuf, libraFrameCount++,
                    offscreenImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                    filterOutputImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                    VkExtent2D{(uint32_t)surfaceWidth, (uint32_t)surfaceHeight}, clearHistoryThisFrame);
                if (!ok) RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());
                transition(filterCmdBuf, filterOutputImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                blitImageToSwapchain(filterCmdBuf, imgIdx, filterOutputView, blitSampler);
                // restore filterOutputImage to CAO so the tracked layout stays valid next frame
                transferBarrierWide(filterCmdBuf, filterOutputImage,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                if (vk_.EndCommandBuffer(filterCmdBuf)!=VK_SUCCESS) throw std::runtime_error("end filter cb");
            } else {
                // TEST MODE A: record EXACTLY what the proven-working brightness path records
                // (recordCmdBuf: AHB -> swapchain), but into filterCmdBuf.
                RLOG("librashader: TEST MODE A recordCmdBuf into filterCmdBuf (draws=%zu)", frameDraws.size());
                recordCmdBuf(filterCmdBuf, imgIdx, frameDraws,
                    frameAhbTransitions, framePreUpload, framePostUpload,
                    curUpload, hasCurUpload,
                    ox, oy, sx, sy, cw, ch, ptrX, ptrY, curHotX, curHotY, curW, curH, effectiveCurVis);
            }
        } else if (gLibraProbeGeneralPresent || gLibraProbeTransferWide) {
            // P3/P4-PROBE paths (unchanged intent): applyFrame -> processedImage, present in GENERAL.
            VkCommandBufferBeginInfo fbi{}; fbi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            if (vk_.BeginCommandBuffer(filterCmdBuf,&fbi)!=VK_SUCCESS) throw std::runtime_error("begin filter cb");
            // 3.5: explicit CAO->SRO for the librashader input (see recordFilterChainPass).
            transferBarrierWide(filterCmdBuf, offscreenImage,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            libraShader.applyPendingParams();   // render-thread param application (generation)
            bool libraOk = libraShader.applyFrame(filterCmdBuf, libraFrameCount++,
                offscreenImage, VK_FORMAT_R8G8B8A8_UNORM,
                (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                processedImage, VK_FORMAT_R8G8B8A8_UNORM,
                (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                VkExtent2D{(uint32_t)surfaceWidth, (uint32_t)surfaceHeight},
                clearHistoryThisFrame);
            if (!libraOk) RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());

            readbackProcessedInFrame(filterCmdBuf, processedImage, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            // P3-PROBE: temporary diagnostic (off by default, env GAMENATIVE_LIBRA_PROBE_GENERAL=1).
            // Samples processedImage in GENERAL instead of SHADER_READ_ONLY_OPTIMAL to test B-H2.
            // Revert after on-device measurement; blitImageToSwapchainLayout stays.
            {
                static bool sLibraProbeLogged = false;
                if (!sLibraProbeLogged) {
                    RLOG(gLibraProbeGeneralPresent ? "P3-PROBE GENERAL present" : "P3-PROBE INACTIVE");
                    sLibraProbeLogged = true;
                }
            }
            // P4-PROBE: temporary diagnostic (off by default, env GAMENATIVE_LIBRA_PROBE_TRANSFER_WIDE=1).
            // Blits processedImage -> DEDICATED diagDstImage with the melonDS transfer-wide barrier
            // (srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE, srcStage = ALL_COMMANDS),
            // readbacks diagDstImage (READBACK-D), and presents diagDstImage sampled in GENERAL.
            // The swapchain is only ever the present target via the sampler blit (imageUsage unchanged).
            // Revert after on-device measurement; transferBarrierWide/diagDstImage stay for Task 6.
            {
                static bool sLibraProbeTransferLogged = false;
                if (!sLibraProbeTransferLogged) {
                    RLOG(gLibraProbeTransferWide ? "P4-PROBE TRANSFER-WIDE present" : "P4-PROBE TRANSFER-WIDE INACTIVE");
                    sLibraProbeTransferLogged = true;
                }
            }
            if (gLibraProbeTransferWide) {
                blitProcessedToDedicated(filterCmdBuf);
                blitImageToSwapchainLayout(filterCmdBuf, imgIdx, diagDstView, blitSampler, VK_IMAGE_LAYOUT_GENERAL);
            } else {
                transition(filterCmdBuf, processedImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                blitImageToSwapchainLayout(filterCmdBuf, imgIdx, processedView, blitSampler, VK_IMAGE_LAYOUT_GENERAL);
            }
        } else {
            // DEFAULT path: EXACTLY the TEST MODE C structure that provably presents the filter
            // output on screen (applyFrame + present blit in the SAME command buffer, narrow
            // CAO->SRO transition, NEAREST blit sampler, no in-CB readback).
            //
            // LAYOUT INVARIANTS (ARMSX2 Task 3):
            //  - filterOutputImage is always COLOR_ATTACHMENT_OPTIMAL at the top of this block
            //    (tracked in filterOutputLayout; UNDEFINED on the 1st frame / after recreate,
            //    which transferBarrierWide handles). It is restored to CAO at the end of every
            //    present, so the tracked layout never drifts.
            //  - offscreenImage is always COLOR_ATTACHMENT_OPTIMAL when the compositor finishes
            //    (its render pass ends in CAO). The compositor itself transitions it from
            //    UNDEFINED every frame, so the librashader path leaving it in SRO is harmless.
            VkCommandBufferBeginInfo fbi{}; fbi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            if (vk_.BeginCommandBuffer(filterCmdBuf,&fbi)!=VK_SUCCESS) throw std::runtime_error("begin filter cb");
            if (libraChainFailed) {
                // Latch (ARMSX2 pattern): a chain that errored is not retried 60x/s (each retry
                // would rebuild descriptors on a half-dead chain). Present the UNSHADED offscreen
                // (the frame without shader) — never stale/garbage filterOutput. filterOutput
                // stays in CAO (its tracked layout is already CAO for the next frame).
                static uint64_t sLatchLog = 0;
                if ((sLatchLog++ & 0x3F) == 0)
                    RLOG("librashader: chain latched (failed) - presenting unshaded frame");
                transferBarrierWide(filterCmdBuf, offscreenImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                recordPresentPass(filterCmdBuf, imgIdx, offscreenView, sampler,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                presentCB = filterCmdBuf;
                cursorDrawnInPass = true;
            } else {
                transferBarrierWide(filterCmdBuf, filterOutputImage,
                    filterOutputLayout, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                filterOutputLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                transferBarrierWide(filterCmdBuf, offscreenImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                libraShader.applyPendingParams();   // render-thread param application (generation)
                bool ok = libraShader.applyFrame(filterCmdBuf, libraFrameCount++,
                    offscreenImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                    filterOutputImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
                    VkExtent2D{(uint32_t)surfaceWidth, (uint32_t)surfaceHeight}, clearHistoryThisFrame);
                if (!ok) {
                    // ARMSX2 pattern: a failed frame() degrades to the unshaded frame instead of
                    // presenting a stale/garbage filterOutput. offscreen is already SRO here, so
                    // present it; filterOutput stays CAO (never transitioned to SRO), which keeps
                    // the tracked filterOutputLayout correct for the next frame.
                    RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());
                    libraChainFailed = true;
                    recordPresentPass(filterCmdBuf, imgIdx, offscreenView, sampler,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                } else {
                    // narrow CAO->SRO (same as TEST MODE C)
                    transition(filterCmdBuf, filterOutputImage,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                    // Present the filter output + draw the cursor INSIDE the same render pass. The
                    // old separate cursor pass used the CLEAR-load swapchain render pass, which
                    // wiped the presented frame to black whenever the cursor was visible.
                    recordPresentPass(filterCmdBuf, imgIdx, filterOutputView, blitSampler,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    // restore filterOutputImage to CAO so the tracked layout stays valid next frame
                    transferBarrierWide(filterCmdBuf, filterOutputImage,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                }
                presentCB = filterCmdBuf;
                cursorDrawnInPass = true;
                static uint64_t sOkLog = 0;
                if ((sOkLog++ & 0x3F) == 0)
                    RLOG("librashader: DEFAULT path ok (shader present) frame=%llu", (unsigned long long)libraFrameCount);
            }
        }

        if (!cursorDrawnInPass && !libraDiagTestBlit() && effectiveCurVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE) {
            VkViewport ovp{0,0,(float)swapchainExt.width,(float)swapchainExt.height,0,1};
            VkRect2D osc{{0,0},swapchainExt};
            VkRenderPassBeginInfo rpi2{}; rpi2.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            rpi2.renderPass=renderPass; rpi2.framebuffer=swapchainFBs[imgIdx];
            rpi2.renderArea={{0,0},swapchainExt}; rpi2.clearValueCount=0;
            vk_.CmdBeginRenderPass(presentCB,&rpi2,VK_SUBPASS_CONTENTS_INLINE);
            vk_.CmdSetViewport(presentCB,0,1,&ovp);
            vk_.CmdSetScissor(presentCB,0,1,&osc);
            vk_.CmdBindDescriptorSets(presentCB,VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeLayout,0,1,&cursorDS,0,nullptr);
            float cx=(float)std::max(0,(int)ptrX-curHotX), cy=(float)std::max(0,(int)ptrY-curHotY);
            WindowPushConstants cpc{};
            cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
            cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
            cpc.useTexAlpha=1;
            cpc.effectId=0; cpc.sharpness=0.f;
            cpc.resW=(float)std::max(1,(int)curW); cpc.resH=(float)std::max(1,(int)curH);
            cpc.effectMask=0; cpc.brightness=0.f; cpc.contrast=0.f; cpc.gamma=1.f;
            cpc.outW=(float)std::max(1,(int)curW); cpc.outH=(float)std::max(1,(int)curH);
            vk_.CmdPushConstants(presentCB,pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(cpc),&cpc);
            vk_.CmdDraw(presentCB,4,1,0,0);
            vk_.CmdEndRenderPass(presentCB);
        }

        // TEST MODE: recordCmdBuf / TEST B / TEST C already began+ended their CB; a second
        // EndCommandBuffer crashes the Adreno driver (qglinternal::vkEndCommandBuffer null deref).
        if (!libraDiagTestBlit() && vk_.EndCommandBuffer(presentCB)!=VK_SUCCESS) {
            RLOG_E("renderFrame: present EndCommandBuffer failed");
            throw std::runtime_error("end present cb");
        }
    } else {
        static uint64_t sNoLibraLog = 0;
        if ((sNoLibraLog++ & 0xFF) == 0)
            RLOG("librashader: NON-LIBRA path (shader OFF/inactive) frame=%llu",
                 (unsigned long long)libraFrameCount);
        recordCmdBuf(cmdBufs[currentFrame], imgIdx, frameDraws,
            frameAhbTransitions, framePreUpload, framePostUpload,
            curUpload, hasCurUpload,
            ox, oy, sx, sy, cw, ch, ptrX, ptrY, curHotX, curHotY, curW, curH, effectiveCurVis);
    }

    VkSemaphore wSem[]={imgAvailSems[currentFrame]}, sSem[]={renderDoneSems[currentFrame]};
    VkPipelineStageFlags wStage[]={VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkCommandBuffer submitBuf = libraPath ? presentCB : cmdBufs[currentFrame];
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount=1; si.pWaitSemaphores=wSem; si.pWaitDstStageMask=wStage;
    si.commandBufferCount=1; si.pCommandBuffers=&submitBuf;
    si.signalSemaphoreCount=1; si.pSignalSemaphores=sSem;

    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    if (vk_.QueueSubmit(graphicsQueue,1,&si,inFlightFences[currentFrame])!=VK_SUCCESS) {
        vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
        return;
    }
    // (READBACK-P-INFRAME / READBACK-P diagnostics removed: the default path no longer
    // writes the filter output into the readback buffer; READBACK-OFF grid remains.)

    // P4-PROBE: read the dedicated diagDstImage readback (filled in-CB by blitProcessedToDedicated).
    if (gLibraProbeTransferWide && diagReadbackBuffer != VK_NULL_HANDLE) {
        void* rb = nullptr;
        if (vk_.MapMemory(device,diagReadbackMem,0,4,0,&rb)==VK_SUCCESS && rb) {
            uint32_t px = 0; memcpy(&px, rb, 4);
            vk_.UnmapMemory(device,diagReadbackMem);
            RLOG("READBACK-D px=%08x frame=%llu", px, (unsigned long long)libraFrameCount);
        }
    }
    VkSwapchainKHR scs[]={swapchain};
    VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount=1; pi.pWaitSemaphores=sSem; pi.swapchainCount=1; pi.pSwapchains=scs; pi.pImageIndices=&imgIdx;
    res=vk_.QueuePresentKHR(graphicsQueue,&pi);
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR) fbResized.store(true);
    currentFrame=(currentFrame+1)%MAX_FRAMES_IN_FLIGHT;
}

void VulkanRendererContext::onSurfaceResized(int w, int h) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (w==0||h==0) return;
    surfaceWidth=w; surfaceHeight=h; fbResized.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::detachSurface() {
    surfaceDetached.store(true, std::memory_order_release);
    dirtyCV.notify_all();

    { std::unique_lock<std::shared_mutex> frameLock(frameMutex); }

    vk_.DeviceWaitIdle(device);
    cleanupSwapchain();
    if (surface != VK_NULL_HANDLE) {
        vk_.DestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

bool VulkanRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (window) { ANativeWindow_release(window); window = nullptr; }
    window = newWindow;
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    if (vk_.CreateAndroidSurfaceKHR(instance, &ci, nullptr, &surface) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: CreateAndroidSurface failed");
        ANativeWindow_release(window); window = nullptr;
        return false;
    }
    {
        std::unique_lock<std::shared_mutex> frameLock(frameMutex);
        try {
            createSwapchain();
            createFramebuffers();
            createCmdBufs();
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: swapchain recreate failed");
            return false;
        }
        surfaceDetached.store(false, std::memory_order_release);
    }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_all();
    __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer", "reattachSurface: OK");
    return true;
}

void VulkanRendererContext::setTransform(float ox, float oy, float sx, float sy) {
    { std::lock_guard<std::mutex> lk(renderMutex); sceneOffsetX=ox;sceneOffsetY=oy;sceneScaleX=sx;sceneScaleY=sy; }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updatePointerPosition(short x, short y) {
    pointerX.store(x); pointerY.store(y);
    if (cursorVisible.load()) { cursorMoved.store(true); dirtyCV.notify_one(); }
}

void VulkanRendererContext::setCursorVisible(bool v) {
    cursorVisible.store(v); cursorMoved.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short hotX, short hotY) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    ensureCursorTex(w,h);
    cursorPixels.resize((size_t)w*h); memcpy(cursorPixels.data(),px,(size_t)w*h*4);
    cursorHotX=hotX; cursorHotY=hotY;
    isCursorImageDirty.store(true); needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateWindowContent(int64_t id, void* px, short w, short h, short stride, int, int) {
    if (!px||w<=0||h<=0) return;

    void* mapped=nullptr;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        WinTex& wt=texMap[id];
        if (wt.img==VK_NULL_HANDLE || wt.w!=w || wt.h!=h) {
            if (wt.img!=VK_NULL_HANDLE) destroyWinTex(wt);
            if (!createWinTexResources(wt,w,h)) { texMap.erase(id); return; }
        }
        mapped=wt.mapped;
    }

    if (!mapped) return;
    const size_t dstPitch=(size_t)w*4;
    const int32_t srcStride=stride>0?stride:w;
    uint32_t* src2=static_cast<uint32_t*>(px);
    uint8_t*  dst2=static_cast<uint8_t*>(mapped);
    for (int row=0;row<h;++row)
        memcpy(dst2+(size_t)row*dstPitch,
               &src2[(size_t)row*srcStride],(size_t)w*4);
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        auto it=texMap.find(id);
        if (it!=texMap.end()) it->second.dirty=true;
    }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short, short, int, int) {
    if (!ahb) return;
    std::lock_guard<std::mutex> lk(renderMutex);





    auto cit = ahbImportCache.find(ahb);
    if (cit == ahbImportCache.end()) {
        WinTex tmp{};
        if (!importAHBToWinTex(tmp, ahb)) {
            RLOG_E("updateWindowContentAHB: import failed for id=%" PRId64, id);
            return;
        }
        AHardwareBuffer_acquire(ahb);
        ahbImportCache[ahb] = tmp;
        windowAhbs[id].push_back(ahb);
        cit = ahbImportCache.find(ahb);
        RLOG("updateWindowContentAHB: imported new AHB %p for id=%" PRId64 " (%dx%d)",
            (void*)ahb, id, tmp.w, tmp.h);
    }


    WinTex& src = cit->second;
    WinTex& wt  = texMap[id];
    wt.img  = src.img;
    wt.mem  = src.mem;
    wt.view = src.view;
    wt.ds   = src.ds;
    wt.isAHB = true;
    wt.ahb  = ahb;
    wt.w    = src.w;
    wt.h    = src.h;

    if (src.needsTransition) {
        wt.needsTransition  = true;
        src.needsTransition = false;
    }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setRenderList(const int64_t* ids, const int* xs, const int* ys, int count) {
    std::lock_guard<std::mutex> lk(renderMutex);
    renderList.resize(count);
    for (int i=0;i<count;i++) renderList[i]={ids[i],xs[i],ys[i]};
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);



    auto it = texMap.find(id);
    if (it != texMap.end()) {
        if (!it->second.isAHB) destroyWinTex(it->second);
        else it->second = {};
        texMap.erase(it);
    }


    auto wit = windowAhbs.find(id);
    if (wit != windowAhbs.end()) {
        for (AHardwareBuffer* ahb : wit->second) {
            auto cit = ahbImportCache.find(ahb);
            if (cit != ahbImportCache.end()) {
                WinTex deferred = cit->second;
                deferred.isAHB  = false;
                deleteQueue.push_back(deferred);
                AHardwareBuffer_release(ahb);
                ahbImportCache.erase(cit);
            }
        }
        windowAhbs.erase(wit);
    }

    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::cleanupAllAHBCache() {
    for (auto& [ahb, wt] : ahbImportCache) {
        if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, winTexPool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        AHardwareBuffer_release(ahb);
    }
    ahbImportCache.clear();
    windowAhbs.clear();
}


void VulkanRendererContext::dumpRendererInfo() {
    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice,&props);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "=== RENDERER INFO ===");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "GPU: %s vendorID=0x%x driverVersion=0x%x apiVersion=%d.%d.%d",
        props.deviceName,props.vendorID,props.driverVersion,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Swapchain: %dx%d fmt=%d",swapchainExt.width,swapchainExt.height,(int)swapchainFmt);
    std::string pmList;
    for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "SupportedPresentModes: [%s] current=%d",pmList.c_str(),(int)requestedPresentMode);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Filter: mode=%d (%s)", filterMode, filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Scanout: active=%d gameFrameDelivered=%d scanoutGameSC=%p",
        (int)scanoutActive.load(),(int)gameFrameDelivered.load(),scanoutGameSC);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",
        surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
    RLOG("librashader: diagMode=%d testBlit=%d probeGeneral=%d probeTransferWide=%d",
         libraDiagMode(), (int)libraDiagTestBlit(), (int)gLibraProbeGeneralPresent, (int)gLibraProbeTransferWide);
}

void VulkanRendererContext::setFilterMode(int mode) {
    RLOG("setFilterMode: %d -> %d (%s->%s)", filterMode, mode,
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR", mode==2?(cubicSupported?"CUBIC":"LINEAR"):mode==1?"NEAREST":"LINEAR");
    if (filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }
    filterMode=mode;
    vk_.DeviceWaitIdle(device);
    if (sampler!=VK_NULL_HANDLE){vk_.DestroySampler(device,sampler,nullptr);sampler=VK_NULL_HANDLE;}
    createSampler();
    auto updateDS=[&](VkDescriptorSet ds, VkImageView view){
        if(ds==VK_NULL_HANDLE||view==VK_NULL_HANDLE) return;
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView=view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    };
    
    for (auto& [id,wt]:texMap) updateDS(wt.ds, wt.view);

    for (auto& [ahb,wt]:ahbImportCache) updateDS(wt.ds, wt.view);
    if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) updateDS(cursorDS, cursorView);
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setSwapRB(bool enabled) {
    if (swapRB == enabled) return;
    swapRB = enabled;
    RLOG("setSwapRB: %d", (int)swapRB);


}

void VulkanRendererContext::setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma) {
    activeEffectId = effectId;
    activeSharpness = std::max(0.0f, std::min(1.0f, sharpness));
    activeEffectMask = effectMask;
    activeBrightness = std::max(-1.0f, std::min(1.0f, brightness));
    activeContrast = std::max(-1.0f, std::min(1.0f, contrast));
    activeGamma = std::max(0.1f, std::min(4.0f, gamma));
    RLOG("setEffect: id=%d sharpness=%.3f mask=%d brightness=%.3f contrast=%.3f gamma=%.3f",
        activeEffectId, activeSharpness, activeEffectMask, activeBrightness, activeContrast, activeGamma);
    needsRender.store(true);
    dirtyCV.notify_one();
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    bool supported = false;
    for (auto pm : availablePresentModes) if (pm == mode) { supported = true; break; }
    VkPresentModeKHR target = supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    RLOG("setPresentMode: requested=%d supported=%d -> applying=%d",
        (int)mode, (int)supported, (int)target);
    if (requestedPresentMode==target) { RLOG("setPresentMode: already set, skipping"); return; }
    requestedPresentMode=target;
    fbResized.store(true); dirtyCV.notify_one();
}

std::vector<int> VulkanRendererContext::getSupportedPresentModes() const {
    std::vector<int> out;
    for (auto pm:availablePresentModes) out.push_back((int)pm);
    return out;
}

void VulkanRendererContext::initLibrashader() {
    if (!libraShader.isLoaded()) {
        libraShader.loadLibrary();
        libraShader.init(instance, physicalDevice, device, graphicsQueue, gipa);
    }
}

void VulkanRendererContext::loadLibrashaderPreset(const std::string& path) {
    // Deferred: reloadPreset() runs queue work (filter chain create does submits/waitIdle) and must
    // not race with the render thread's in-flight frame recording — a UI-thread reload while the
    // render thread was recording crashed the Adreno driver in vkEndCommandBuffer. The render
    // thread applies the request at the top of the next libra frame.
    requestLibrashaderPreset(path);
}

void VulkanRendererContext::requestLibrashaderPreset(const std::string& path) {
    std::lock_guard<std::mutex> lk(presetReqMtx);
    pendingPresetPath = path;
    hasPendingPreset = true;
    RLOG("librashader: preset load requested (deferred to render thread): %s", path.c_str());
}

void VulkanRendererContext::clearLibrashaderPreset() {
    std::lock_guard<std::mutex> lk(presetReqMtx);
    hasPendingClear = true;
    RLOG("librashader: preset clear requested (deferred to render thread)");
}

void VulkanRendererContext::setLibrashaderParam(const std::string& name, float value) {
    libraShader.setParam(name, value);
}

void VulkanRendererContext::enableLibrashader(bool enabled) {
    libraShaderEnabled.store(enabled);
    libraShaderActive.store(enabled && libraShader.isActive());
}

void VulkanRendererContext::createOffscreenTargets(int w, int h) {
    destroyOffscreenTargets();
    atlasLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    filterOutputLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.extent = {(uint32_t)w, (uint32_t)h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.format = VK_FORMAT_R8G8B8A8_UNORM;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device, &ii, nullptr, &offscreenImage) != VK_SUCCESS) {
        RLOG_E("createOffscreenTargets: CreateImage failed");
        return;
    }

    VkMemoryRequirements req;
    vk_.GetImageMemoryRequirements(device, offscreenImage, &req);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device, &ai, nullptr, &offscreenMem) != VK_SUCCESS) {
        vk_.DestroyImage(device, offscreenImage, nullptr);
        offscreenImage = VK_NULL_HANDLE;
        RLOG_E("createOffscreenTargets: AllocateMemory failed");
        return;
    }
    vk_.BindImageMemory(device, offscreenImage, offscreenMem, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = offscreenImage;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = VK_FORMAT_R8G8B8A8_UNORM;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vk_.CreateImageView(device, &vi, nullptr, &offscreenView) != VK_SUCCESS) {
        vk_.FreeMemory(device, offscreenMem, nullptr);
        vk_.DestroyImage(device, offscreenImage, nullptr);
        offscreenImage = VK_NULL_HANDLE;
        offscreenMem = VK_NULL_HANDLE;
        RLOG_E("createOffscreenTargets: ImageView failed");
        return;
    }

    VkFramebufferCreateInfo fbi{};
    fbi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbi.renderPass = offscreenRenderPass;
    fbi.attachmentCount = 1;
    fbi.pAttachments = &offscreenView;
    fbi.width = (uint32_t)w;
    fbi.height = (uint32_t)h;
    fbi.layers = 1;
    if (vk_.CreateFramebuffer(device, &fbi, nullptr, &offscreenFB) != VK_SUCCESS) {
        vk_.DestroyImageView(device, offscreenView, nullptr);
        vk_.FreeMemory(device, offscreenMem, nullptr);
        vk_.DestroyImage(device, offscreenImage, nullptr);
        offscreenView = VK_NULL_HANDLE;
        offscreenImage = VK_NULL_HANDLE;
        offscreenMem = VK_NULL_HANDLE;
        offscreenFB = VK_NULL_HANDLE;
        RLOG_E("createOffscreenTargets: Framebuffer failed");
        return;
    }

    ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (vk_.CreateImage(device, &ii, nullptr, &processedImage) != VK_SUCCESS) {
        RLOG_E("createOffscreenTargets: processedImage CreateImage failed");
        destroyOffscreenTargets();
        return;
    }

    vk_.GetImageMemoryRequirements(device, processedImage, &req);
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device, &ai, nullptr, &processedMem) != VK_SUCCESS) {
        vk_.DestroyImage(device, processedImage, nullptr);
        processedImage = VK_NULL_HANDLE;
        destroyOffscreenTargets();
        return;
    }
    vk_.BindImageMemory(device, processedImage, processedMem, 0);

    vi.image = processedImage;
    if (vk_.CreateImageView(device, &vi, nullptr, &processedView) != VK_SUCCESS) {
        vk_.FreeMemory(device, processedMem, nullptr);
        vk_.DestroyImage(device, processedImage, nullptr);
        processedImage = VK_NULL_HANDLE;
        processedMem = VK_NULL_HANDLE;
        destroyOffscreenTargets();
        return;
    }

    // P4-PROBE (temporary): dedicated diagnostic destination image (R8G8B8A8_UNORM, same dims as
    // processedImage). melonDS blits the filter output into an intermediate atlas and only later
    // presents it; this mirrors that pattern without ever touching the swapchain imageUsage.
    // TRANSFER_SRC is required for the in-CB readback copy (vkCmdCopyImageToBuffer).
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (vk_.CreateImage(device, &ii, nullptr, &diagDstImage) != VK_SUCCESS) {
        RLOG_E("createOffscreenTargets: diagDstImage CreateImage failed");
        destroyOffscreenTargets();
        return;
    }
    vk_.GetImageMemoryRequirements(device, diagDstImage, &req);
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device, &ai, nullptr, &diagDstMem) != VK_SUCCESS) {
        vk_.DestroyImage(device, diagDstImage, nullptr);
        diagDstImage = VK_NULL_HANDLE;
        destroyOffscreenTargets();
        return;
    }
    vk_.BindImageMemory(device, diagDstImage, diagDstMem, 0);

    vi.image = diagDstImage;
    if (vk_.CreateImageView(device, &vi, nullptr, &diagDstView) != VK_SUCCESS) {
        vk_.FreeMemory(device, diagDstMem, nullptr);
        vk_.DestroyImage(device, diagDstImage, nullptr);
        diagDstImage = VK_NULL_HANDLE;
        diagDstMem = VK_NULL_HANDLE;
        destroyOffscreenTargets();
        return;
    }

    // Task 6 atlas fix: filterOutputImage (applyFrame target) and atlasImage (copy destination).
    // R8G8B8A8_UNORM, OPTIMAL, usage TRANSFER_SRC|TRANSFER_DST|COLOR_ATTACHMENT|SAMPLED, matching
    // melonDS createRetroArchImage (VulkanSurfacePresenter.cpp:1980-2038).
    auto createAtlasTarget = [&](VkImage& img, VkDeviceMemory& mem, VkImageView& view,
                                 const char* name) -> bool {
        VkImageCreateInfo tii{};
        tii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        tii.imageType = VK_IMAGE_TYPE_2D;
        tii.extent = {(uint32_t)w, (uint32_t)h, 1};
        tii.mipLevels = 1;
        tii.arrayLayers = 1;
        tii.format = VK_FORMAT_R8G8B8A8_UNORM;
        tii.tiling = VK_IMAGE_TILING_OPTIMAL;
        tii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        tii.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        tii.samples = VK_SAMPLE_COUNT_1_BIT;
        tii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk_.CreateImage(device, &tii, nullptr, &img) != VK_SUCCESS) {
            RLOG_E("createOffscreenTargets: %s CreateImage failed", name);
            return false;
        }
        VkMemoryRequirements treq;
        vk_.GetImageMemoryRequirements(device, img, &treq);
        VkMemoryAllocateInfo tai{};
        tai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        tai.allocationSize = treq.size;
        tai.memoryTypeIndex = findMemType(treq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device, &tai, nullptr, &mem) != VK_SUCCESS) {
            vk_.DestroyImage(device, img, nullptr);
            img = VK_NULL_HANDLE;
            RLOG_E("createOffscreenTargets: %s AllocateMemory failed", name);
            return false;
        }
        vk_.BindImageMemory(device, img, mem, 0);
        VkImageViewCreateInfo tvi{};
        tvi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        tvi.image = img;
        tvi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        tvi.format = VK_FORMAT_R8G8B8A8_UNORM;
        tvi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vk_.CreateImageView(device, &tvi, nullptr, &view) != VK_SUCCESS) {
            vk_.FreeMemory(device, mem, nullptr);
            mem = VK_NULL_HANDLE;
            vk_.DestroyImage(device, img, nullptr);
            img = VK_NULL_HANDLE;
            RLOG_E("createOffscreenTargets: %s ImageView failed", name);
            return false;
        }
        return true;
    };
    if (!createAtlasTarget(filterOutputImage, filterOutputMem, filterOutputView, "filterOutputImage")) {
        destroyOffscreenTargets();
        return;
    }
    if (!createAtlasTarget(atlasImage, atlasMem, atlasView, "atlasImage")) {
        destroyOffscreenTargets();
        return;
    }

    try {
        VkDeviceSize rbSize = (VkDeviceSize)w * h * 4;
        createBuffer(rbSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            processedReadbackBuffer, processedReadbackMem);
        createBuffer(rbSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            diagReadbackBuffer, diagReadbackMem);
    } catch (...) {
        RLOG_E("createOffscreenTargets: processedReadbackBuffer create failed");
    }

    createBlitPipeline();
    RLOG("createOffscreenTargets: %dx%d created successfully", w, h);
}

void VulkanRendererContext::destroyOffscreenTargets() {
    destroyBlitPipeline();
    if (processedReadbackBuffer != VK_NULL_HANDLE) { vk_.DestroyBuffer(device, processedReadbackBuffer, nullptr); processedReadbackBuffer = VK_NULL_HANDLE; }
    if (processedReadbackMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, processedReadbackMem, nullptr); processedReadbackMem = VK_NULL_HANDLE; }
    if (diagReadbackBuffer != VK_NULL_HANDLE) { vk_.DestroyBuffer(device, diagReadbackBuffer, nullptr); diagReadbackBuffer = VK_NULL_HANDLE; }
    if (diagReadbackMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, diagReadbackMem, nullptr); diagReadbackMem = VK_NULL_HANDLE; }
    if (diagDstView != VK_NULL_HANDLE) { vk_.DestroyImageView(device, diagDstView, nullptr); diagDstView = VK_NULL_HANDLE; }
    if (diagDstImage != VK_NULL_HANDLE) { vk_.DestroyImage(device, diagDstImage, nullptr); diagDstImage = VK_NULL_HANDLE; }
    if (diagDstMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, diagDstMem, nullptr); diagDstMem = VK_NULL_HANDLE; }
    if (atlasView != VK_NULL_HANDLE) { vk_.DestroyImageView(device, atlasView, nullptr); atlasView = VK_NULL_HANDLE; }
    if (atlasImage != VK_NULL_HANDLE) { vk_.DestroyImage(device, atlasImage, nullptr); atlasImage = VK_NULL_HANDLE; }
    if (atlasMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, atlasMem, nullptr); atlasMem = VK_NULL_HANDLE; }
    if (filterOutputView != VK_NULL_HANDLE) { vk_.DestroyImageView(device, filterOutputView, nullptr); filterOutputView = VK_NULL_HANDLE; }
    if (filterOutputImage != VK_NULL_HANDLE) { vk_.DestroyImage(device, filterOutputImage, nullptr); filterOutputImage = VK_NULL_HANDLE; }
    if (filterOutputMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, filterOutputMem, nullptr); filterOutputMem = VK_NULL_HANDLE; }
    atlasLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    filterOutputLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (processedView != VK_NULL_HANDLE) { vk_.DestroyImageView(device, processedView, nullptr); processedView = VK_NULL_HANDLE; }
    if (processedImage != VK_NULL_HANDLE) { vk_.DestroyImage(device, processedImage, nullptr); processedImage = VK_NULL_HANDLE; }
    if (processedMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, processedMem, nullptr); processedMem = VK_NULL_HANDLE; }
    if (offscreenFB != VK_NULL_HANDLE) { vk_.DestroyFramebuffer(device, offscreenFB, nullptr); offscreenFB = VK_NULL_HANDLE; }
    if (offscreenView != VK_NULL_HANDLE) { vk_.DestroyImageView(device, offscreenView, nullptr); offscreenView = VK_NULL_HANDLE; }
    if (offscreenImage != VK_NULL_HANDLE) { vk_.DestroyImage(device, offscreenImage, nullptr); offscreenImage = VK_NULL_HANDLE; }
    if (offscreenMem != VK_NULL_HANDLE) { vk_.FreeMemory(device, offscreenMem, nullptr); offscreenMem = VK_NULL_HANDLE; }
}

void VulkanRendererContext::createBlitPipeline() {
    VkSamplerCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_NEAREST;
    sci.minFilter = VK_FILTER_NEAREST;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.minLod = 0.f; sci.maxLod = 0.f;
    if (vk_.CreateSampler(device, &sci, nullptr, &blitSampler) != VK_SUCCESS) {
        RLOG_E("createBlitPipeline: sampler failed");
        return;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = winTexPool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &dsLayout;
    VkResult res = vk_.AllocateDescriptorSets(device, &dsai, &blitDS);
    if (res != VK_SUCCESS) {
        RLOG_E("createBlitPipeline: descriptor allocation failed: %d", (int)res);
        vk_.DestroySampler(device, blitSampler, nullptr);
        blitSampler = VK_NULL_HANDLE;
        return;
    }
    RLOG("createBlitPipeline: done");
}

void VulkanRendererContext::destroyBlitPipeline() {
    if (blitDS != VK_NULL_HANDLE) {
        vk_.FreeDescriptorSets(device, winTexPool, 1, &blitDS);
        blitDS = VK_NULL_HANDLE;
    }
    if (blitSampler != VK_NULL_HANDLE) {
        vk_.DestroySampler(device, blitSampler, nullptr);
        blitSampler = VK_NULL_HANDLE;
    }
}

void VulkanRendererContext::blitProcessedToSwapchain(VkCommandBuffer cb, uint32_t imgIdx) {
    blitImageToSwapchain(cb, imgIdx, processedView, blitSampler);
}

// P4-PROBE (temporary): blit processedImage into the DEDICATED diagDstImage using the melonDS
// transfer-wide barrier recipe (srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE,
// srcStage = ALL_COMMANDS — VulkanSurfacePresenter.cpp:2258/2270), then copy diagDstImage to the
// host-visible diagReadbackBuffer (READBACK-D, read after the frame's fence wait). The swapchain is
// never a transfer target; it is only ever presented via the sampler blit. processedImage is left in
// SHADER_READ_ONLY_OPTIMAL (same end state as the normal path) so readbackProcessedP1 stays valid;
// diagDstImage is left in GENERAL for the present sampler blit.
void VulkanRendererContext::blitProcessedToDedicated(VkCommandBuffer cb) {
    if (processedImage == VK_NULL_HANDLE || diagDstImage == VK_NULL_HANDLE) return;
    transferBarrierWide(cb, processedImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transferBarrierWide(cb, diagDstImage,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit blit{};
    blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    blit.srcOffsets[0] = { 0, 0, 0 };
    blit.srcOffsets[1] = { (int32_t)surfaceWidth, (int32_t)surfaceHeight, 1 };
    blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    blit.dstOffsets[0] = { 0, 0, 0 };
    blit.dstOffsets[1] = { (int32_t)surfaceWidth, (int32_t)surfaceHeight, 1 };
    vk_.CmdBlitImage(cb, processedImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        diagDstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    // leave diagDstImage in GENERAL for the present sampler blit (melonDS samples atlasOutput in GENERAL)
    transferBarrierWide(cb, diagDstImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
        VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

    // readback diagDstImage -> dedicated host-visible buffer; logged as READBACK-D after the fence wait
    if (diagReadbackBuffer != VK_NULL_HANDLE) {
        transferBarrierWide(cb, diagDstImage,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy r{};
        r.bufferOffset = 0; r.bufferRowLength = 0; r.bufferImageHeight = 0;
        r.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
        r.imageExtent = { (uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1 };
        vk_.CmdCopyImageToBuffer(cb, diagDstImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            diagReadbackBuffer, 1, &r);
        // restore diagDstImage to GENERAL for the present sampler blit
        transferBarrierWide(cb, diagDstImage,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    // restore processedImage to SHADER_READ_ONLY_OPTIMAL (normal-path end state)
    transferBarrierWide(cb, processedImage,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
}

// Task 6 atlas fix (melonDS topology): filter chain writes the DEDICATED filterOutputImage, the copy
// engine (vkCmdCopyImage, primary path) moves it to the DEDICATED atlasImage in the SAME command
// buffer as applyFrame, then submit-and-wait (VulkanSurfacePresenter.cpp:2228-2243). atlasImage is
// left in GENERAL so a later submission can sample it; atlasLayout tracks its layout.
void VulkanRendererContext::recordFilterChainPass(VkCommandBuffer cb, uint64_t frameCount, bool clearHistory) {
    if (filterOutputImage == VK_NULL_HANDLE || atlasImage == VK_NULL_HANDLE) return;
    // I2: the recorded draws reference the chain's resources until QueueSubmit completes, so the whole
    // applyFrame..WaitForFences span is serialized against loadLibrashaderPreset's reloadPreset (UI/JNI
    // thread) via filterSubmitMtx. Lock order is always filterSubmitMtx -> librashader.mtx (no inversion).
    std::lock_guard<std::mutex> submitLk(filterSubmitMtx);
    VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) throw std::runtime_error("begin filter cb");

    // C1: applyFrame requires the output image in COLOR_ATTACHMENT_OPTIMAL and does NOT create a
    // barrier for the final pass (librashader.h:1705-1707/1719). Transition filterOutputImage from its
    // tracked layout (UNDEFINED on frame 0 / after recreate, COLOR_ATTACHMENT_OPTIMAL on later frames)
    // before applyFrame; the copy leaves it in TRANSFER_SRC_OPTIMAL, so we restore it to CAO below.
    transferBarrierWide(cb, filterOutputImage,
        filterOutputLayout, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    filterOutputLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    // 3.5: librashader requires the INPUT (offscreenImage) in SHADER_READ_ONLY_OPTIMAL
    // (filter_chain.rs:285-286); the compositor leaves it in COLOR_ATTACHMENT_OPTIMAL, so
    // transition explicitly before applyFrame (the automatic finalLayout transition to SRO
    // left it unreadable by the sampler on Adreno).
    transferBarrierWide(cb, offscreenImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

    // applyFrame: offscreenImage -> filterOutputImage
    bool ok = libraShader.applyFrame(cb, frameCount,
        offscreenImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
        filterOutputImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
        VkExtent2D{(uint32_t)surfaceWidth, (uint32_t)surfaceHeight}, clearHistory);
    if (!ok) RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());

    // PRIMARY PATH: copy engine filterOutput -> atlas (same CB, melonDS 2353-2383)
    transferBarrierWide(cb, filterOutputImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transferBarrierWide(cb, atlasImage,
        atlasLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkImageCopy ic{};
    ic.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    ic.srcOffset = {0, 0, 0};
    ic.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    ic.dstOffset = {0, 0, 0};
    ic.extent = {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1};
    vk_.CmdCopyImage(cb, filterOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &ic);

    // C1: restore filterOutputImage TSRC -> CAO so the next applyFrame (and the in-frame P2 readback)
    // start from a valid layout (melonDS VulkanSurfacePresenter.cpp:2384).
    transferBarrierWide(cb, filterOutputImage,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    filterOutputLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    // atlas -> SHADER_READ_ONLY_OPTIMAL for later sampling. On this Adreno the sampler only
    // reads images left in SRO (GENERAL sampling returned black); melonDS's GENERAL choice
    // was inferred from the wrong symptom (the black was a command-buffer deadlock).
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = atlasImage;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &b);
    atlasLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    if (vk_.EndCommandBuffer(cb) != VK_SUCCESS) throw std::runtime_error("end filter cb");

    // submit-and-wait (melonDS 2228-2243); held under filterSubmitMtx so reloadPreset cannot free the
    // chain while the recorded commands are in flight.
    if (vk_.ResetFences(device, 1, &filterFence) != VK_SUCCESS) throw std::runtime_error("reset filter fence");
    VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1; si.pCommandBuffers = &cb;
    if (vk_.QueueSubmit(graphicsQueue, 1, &si, filterFence) != VK_SUCCESS) {
        RLOG_E("filter submit failed"); return;
    }
    if (vk_.WaitForFences(device, 1, &filterFence, VK_TRUE, UINT64_MAX) != VK_SUCCESS)
        RLOG_E("filter wait failed");
}

// Task 6 atlas fix: present the atlas sampled in GENERAL. cb must already be in recording state
// (Option A: recorded into the SAME filterCmdBuf that the final submit presents, after re-begin).
// GENERAL->GENERAL barrier matches melonDS VulkanSurfacePresenter.cpp:3094-3142.
void VulkanRendererContext::presentAtlasToSwapchain(VkCommandBuffer cb, uint32_t imgIdx) {
    if (atlasImage == VK_NULL_HANDLE || atlasView == VK_NULL_HANDLE) return;
    {
        static bool sAtlasPresentLogged = false;
        if (!sAtlasPresentLogged) {
            RLOG("ATLAS-PRESENT: presenting atlas in SHADER_READ_ONLY_OPTIMAL (sampler works in SRO)");
            sAtlasPresentLogged = true;
        }
    }

    // SRO->SRO barrier for memory visibility of the transfer copy (matches the offscreen path
    // that is proven to sample correctly on this Adreno).
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = atlasImage;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &b);

    // blit of the atlas in SRO with the LINEAR sampler (same combo as the working offscreen path)
    blitImageToSwapchainLayout(cb, imgIdx, atlasView, sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}

// P2 in-frame readback of the applyFrame output. img is assumed to be in curLayout:
// - processedImage after applyFrame: COLOR_ATTACHMENT_OPTIMAL (probe paths)
// - filterOutputImage after recordFilterChainPass: COLOR_ATTACHMENT_OPTIMAL (default atlas path; C1
//   restores it after the copy, and this function's CAO branch restores it back to CAO after the copy).
// In the CAO case the image is left in COLOR_ATTACHMENT_OPTIMAL so applyFrame can be called directly
// next frame (librashader requires CAO and does not barrier the final pass).
void VulkanRendererContext::readbackProcessedInFrame(VkCommandBuffer cb, VkImage img, VkImageLayout curLayout) {
    if (img == VK_NULL_HANDLE || processedReadbackBuffer == VK_NULL_HANDLE) return;
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (curLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
    } else {
        b.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    }
    VkBufferImageCopy r{};
    r.bufferOffset = 0; r.bufferRowLength = 0; r.bufferImageHeight = 0;
    r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.imageExtent = {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1};
    vk_.CmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        processedReadbackBuffer, 1, &r);
    if (curLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
    } else {
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    }
}

void VulkanRendererContext::readbackProcessedP1() {
    if (processedImage == VK_NULL_HANDLE || processedReadbackBuffer == VK_NULL_HANDLE) return;
    VkCommandBuffer cb = beginOneTime();
    if (cb == VK_NULL_HANDLE) return;
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = processedImage;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy r{};
    r.bufferOffset = 0; r.bufferRowLength = 0; r.bufferImageHeight = 0;
    r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.imageExtent = {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1};
    vk_.CmdCopyImageToBuffer(cb, processedImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        processedReadbackBuffer, 1, &r);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    endOneTime(cb);
}

// DIAG: read back the offscreenImage (the librashader filter's INPUT) into the
// processed readback buffer so the log can show whether the game actually reaches
// the compositor. offscreen is left in SHADER_READ_ONLY_OPTIMAL by the compositor.
void VulkanRendererContext::readbackOffscreenDiag() {
    // Diagnostic only: sample the filter INPUT (offscreen) once every 64 frames.
    static uint64_t sOffDiagCount = 0;
    if ((sOffDiagCount++ & 0x3F) != 0) return;
    if (offscreenImage == VK_NULL_HANDLE || processedReadbackBuffer == VK_NULL_HANDLE) return;
    VkCommandBuffer cb = beginOneTime();
    if (cb == VK_NULL_HANDLE) return;
    // melonDS-wide barrier recipe: srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE,
    // srcStage = ALL_COMMANDS, so the transition synchronizes whatever the compositor's render
    // pass wrote (color attachment) before the transfer read. offscreen is left in
    // COLOR_ATTACHMENT_OPTIMAL by the compositor (3.5 fix).
    transferBarrierWide(cb, offscreenImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    // 5x5 grid of samples so we can see WHERE content is (letterbox vs game area) instead
    // of guessing from 3 points. Buffer layout: rows 0..4, cols 0..4, pixel (r,c) at
    // offset (r*5+c)*4; plus a final sample at the very center for the legacy log.
    uint32_t cw = (uint32_t)std::max(1, surfaceWidth);
    uint32_t ch = (uint32_t)std::max(1, surfaceHeight);
    static uint32_t sGridLogThrottle = 0;
    const bool logGrid = ((sGridLogThrottle++ & 0x3F) == 0);
    int32_t xs[5] = {0, (int32_t)(cw/4), (int32_t)(cw/2), (int32_t)(3*cw/4), (int32_t)(cw-1)};
    int32_t ys[5] = {0, (int32_t)(ch/4), (int32_t)(ch/2), (int32_t)(3*ch/4), (int32_t)(ch-1)};
    uint32_t nSamples = 0;
    for (int r = 0; r < 5; ++r) {
        for (int c = 0; c < 5; ++c) {
            VkBufferImageCopy cp{};
            cp.bufferOffset = (VkDeviceSize)nSamples * 4;
            cp.bufferRowLength = 0; cp.bufferImageHeight = 0;
            cp.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            cp.imageOffset = { xs[c], ys[r], 0 };
            cp.imageExtent = {1, 1, 1};
            vk_.CmdCopyImageToBuffer(cb, offscreenImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                processedReadbackBuffer, 1, &cp);
            nSamples++;
        }
    }
    // center pixel at offset 25*4 for the compact legacy log
    {
        VkBufferImageCopy cp{};
        cp.bufferOffset = (VkDeviceSize)25 * 4;
        cp.bufferRowLength = 0; cp.bufferImageHeight = 0;
        cp.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        cp.imageOffset = { (int32_t)(cw/2), (int32_t)(ch/2), 0 };
        cp.imageExtent = {1, 1, 1};
        vk_.CmdCopyImageToBuffer(cb, offscreenImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            processedReadbackBuffer, 1, &cp);
    }
    if (logGrid) sGridLogThrottle = sGridLogThrottle; // (throttle handled above)
    transferBarrierWide(cb, offscreenImage,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    endOneTime(cb);
}

void VulkanRendererContext::blitImageToSwapchain(VkCommandBuffer cb, uint32_t imgIdx,
                                                 VkImageView srcView, VkSampler srcSampler) {
    blitImageToSwapchainLayout(cb, imgIdx, srcView, srcSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}

void VulkanRendererContext::blitImageToSwapchainLayout(VkCommandBuffer cb, uint32_t imgIdx,
    VkImageView srcView, VkSampler srcSampler, VkImageLayout imageLayout)
{
    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = swapchainFBs[imgIdx];
    rpi.renderArea = {{0, 0}, swapchainExt};
    VkClearValue clr = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clr;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport vp{0, 0, (float)swapchainExt.width, (float)swapchainExt.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0, 0}, swapchainExt};
    vk_.CmdSetScissor(cb, 0, 1, &sc);

    VkDescriptorImageInfo dii{};
    dii.imageLayout = imageLayout;
    dii.imageView = srcView;
    dii.sampler = srcSampler;
    VkWriteDescriptorSet wr{};
    wr.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wr.dstSet = blitDS;
    wr.dstBinding = 0;
    wr.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.descriptorCount = 1;
    wr.pImageInfo = &dii;
    vk_.UpdateDescriptorSets(device, 1, &wr, 0, nullptr);

    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
        pipeLayout, 0, 1, &blitDS, 0, nullptr);

    WindowPushConstants pc{};
    pc.ndcX0 = -1.0f; pc.ndcY0 = -1.0f;
    pc.ndcX1 =  1.0f; pc.ndcY1 =  1.0f;
    pc.useTexAlpha = 0;
    pc.effectId = 0;
    pc.sharpness = 0.0f;
    pc.resW = (float)swapchainExt.width;
    pc.resH = (float)swapchainExt.height;
    pc.effectMask = 0;
    pc.brightness = 0.0f;
    pc.contrast = 0.0f;
    pc.gamma = 1.0f;
    pc.outW = (float)swapchainExt.width;
    pc.outH = (float)swapchainExt.height;
    vk_.CmdPushConstants(cb, pipeLayout,
        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
        0, sizeof(pc), &pc);
    vk_.CmdDraw(cb, 4, 1, 0, 0);

    vk_.CmdEndRenderPass(cb);
}
// Full-screen present of srcView (in srcLayout) into swapchain image imgIdx, drawing the cursor
// in the SAME render pass (a separate cursor pass with loadOp=CLEAR wiped the presented frame —
// bug-fix 5). Equivalent to the inline pass previously in the default librashader path; used by
// that path, its failure fallback, and the latched-chain fallback.
void VulkanRendererContext::recordPresentPass(VkCommandBuffer cb, uint32_t imgIdx,
    VkImageView srcView, VkSampler srcSampler, VkImageLayout srcLayout)
{
    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = swapchainFBs[imgIdx];
    rpi.renderArea = {{0, 0}, swapchainExt};
    VkClearValue clr = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1; rpi.pClearValues = &clr;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0, 0, (float)swapchainExt.width, (float)swapchainExt.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0, 0}, swapchainExt};
    vk_.CmdSetScissor(cb, 0, 1, &sc);
    VkDescriptorImageInfo dii{};
    dii.imageLayout = srcLayout;
    dii.imageView = srcView;
    dii.sampler = srcSampler;
    VkWriteDescriptorSet wr{};
    wr.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wr.dstSet = blitDS; wr.dstBinding = 0;
    wr.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.descriptorCount = 1; wr.pImageInfo = &dii;
    vk_.UpdateDescriptorSets(device, 1, &wr, 0, nullptr);
    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
        pipeLayout, 0, 1, &blitDS, 0, nullptr);
    WindowPushConstants pc{};
    pc.ndcX0 = -1.0f; pc.ndcY0 = -1.0f;
    pc.ndcX1 =  1.0f; pc.ndcY1 =  1.0f;
    pc.useTexAlpha = 0; pc.effectId = 0; pc.sharpness = 0.0f;
    pc.resW = (float)swapchainExt.width; pc.resH = (float)swapchainExt.height;
    pc.effectMask = 0; pc.brightness = 0.0f; pc.contrast = 0.0f; pc.gamma = 1.0f;
    pc.outW = (float)swapchainExt.width; pc.outH = (float)swapchainExt.height;
    vk_.CmdPushConstants(cb, pipeLayout,
        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
    vk_.CmdDraw(cb, 4, 1, 0, 0);
    // cursor inside the same pass (no clear) — values read fresh from members (same source the
    // render thread copies into locals at the top of renderFrame).
    bool effCurVis = cursorVisible.load() && !scanoutActive.load();
    if (effCurVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE) {
        float ox = sceneOffsetX, oy = sceneOffsetY, sx = sceneScaleX, sy = sceneScaleY;
        float cw = (float)containerWidth, ch = (float)containerHeight;
        short ptrX = (short)pointerX.load(), ptrY = (short)pointerY.load();
        short curHotX = cursorHotX, curHotY = cursorHotY, curW = cursorTexW, curH = cursorTexH;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipeLayout, 0, 1, &cursorDS, 0, nullptr);
        float cx=(float)std::max(0,(int)ptrX-curHotX), cy=(float)std::max(0,(int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.useTexAlpha=1;
        cpc.effectId=0; cpc.sharpness=0.f;
        cpc.resW=(float)std::max(1,(int)curW); cpc.resH=(float)std::max(1,(int)curH);
        cpc.effectMask=0; cpc.brightness=0.f; cpc.contrast=0.f; cpc.gamma=1.f;
        cpc.outW=(float)std::max(1,(int)curW); cpc.outH=(float)std::max(1,(int)curH);
        vk_.CmdPushConstants(cb, pipeLayout,
            VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(cpc),&cpc);
        vk_.CmdDraw(cb,4,1,0,0);
    }
    vk_.CmdEndRenderPass(cb);
}


void VulkanRendererContext::recordCompositorPass(VkCommandBuffer cb,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short curW, short curH)
{
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) throw std::runtime_error("begin cb");

    ahbTransitions.clear(); preUpload.clear(); postUpload.clear();

    for (auto& d : draws) {
        if (d.img == VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = d.img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.srcAccessMask = 0; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        } else if (!d.isAHB && (d.needsTransition || d.upload != VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = d.img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.srcAccessMask = 0; b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);
            b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    if (!preUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)preUpload.size(), preUpload.data());

    for (auto& d : draws) {
        if (d.isAHB || d.upload == VK_NULL_HANDLE || d.img == VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset = 0; r.bufferRowLength = 0; r.bufferImageHeight = 0;
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)d.w, (uint32_t)d.h, 1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    bool hasCursorCopy = hasCursorUpload && cursorImg != VK_NULL_HANDLE && cursorUpload != VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = cursorImg; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy r{}; r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)curW, (uint32_t)curH, 1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)postUpload.size(), postUpload.data());

    {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        barrier.image = offscreenImage;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = offscreenRenderPass;
    rpi.framebuffer = offscreenFB;
    rpi.renderArea = {{0, 0}, {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight}};
    VkClearValue clr = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clr;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport vp{0, 0, (float)surfaceWidth, (float)surfaceHeight, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0, 0}, {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight}};
    vk_.CmdSetScissor(cb, 0, 1, &sc);

    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, offscreenPipeline != VK_NULL_HANDLE ? offscreenPipeline : pipeline);
    static uint64_t sCompositorDrawLog = 0;
    if ((sCompositorDrawLog++ & 0x3F) == 0)
        RLOG("librashader: recordCompositorPass draws=%zu (offscreen %dx%d)",
            draws.size(), surfaceWidth, surfaceHeight);
    for (auto& d : draws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        static uint64_t sDrawDetailLog = 0;
        if ((sDrawDetailLog++ & 0x3F) == 0)
            RLOG("librashader: draw img=%p ds=%p isAHB=%d x=%d y=%d w=%d h=%d ox=%f oy=%f sx=%f sy=%f cw=%f ch=%f",
                (void*)d.img, (void*)d.ds, (int)d.isAHB, d.x, d.y, d.w, d.h,
                ox, oy, sx, sy, cw, ch);
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipeLayout, 0, 1, &d.ds, 0, nullptr);
        WindowPushConstants pc{};
        pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
        pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
        pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
        pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
        pc.useTexAlpha = 0;
        pc.effectId = 0;
        pc.sharpness = 0.0f;
        pc.resW = (float)std::max(1, d.w);
        pc.resH = (float)std::max(1, d.h);
        pc.effectMask = 0;
        pc.brightness = 0.0f;
        pc.contrast = 0.0f;
        pc.gamma = 1.0f;
        pc.outW = std::max(1.0f, (float)d.w * sx / cw * (float)surfaceWidth);
        pc.outH = std::max(1.0f, (float)d.h * sy / ch * (float)surfaceHeight);
        vk_.CmdPushConstants(cb, pipeLayout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);

    {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        barrier.image = processedImage;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("recordCompositorPass: EndCommandBuffer failed: %d", (int)endStatus);
        throw std::runtime_error("end cb");
    }
}

#pragma GCC diagnostic pop
