#include "vk_dispatch.h"
#include <string.h>

VkDispatch vkd;

bool vkd_load(VkInstance instance, VkDevice device, PFN_vkGetInstanceProcAddr gipa) {
    memset(&vkd, 0, sizeof(vkd));
    if (!gipa) return false;
    vkd.GetInstanceProcAddr = gipa;

#define LOAD_I(fn) vkd.fn = (PFN_vk##fn)gipa(instance, "vk"#fn)
    LOAD_I(GetDeviceProcAddr);
    LOAD_I(CreateInstance);
    LOAD_I(DestroyInstance);
    LOAD_I(EnumeratePhysicalDevices);
    LOAD_I(GetPhysicalDeviceProperties);
    LOAD_I(GetPhysicalDeviceMemoryProperties);
    LOAD_I(GetPhysicalDeviceFeatures2);
    LOAD_I(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I(GetPhysicalDeviceFormatProperties);
    LOAD_I(GetPhysicalDeviceImageFormatProperties);
    LOAD_I(CreateDevice);
#undef LOAD_I

    PFN_vkGetDeviceProcAddr gdpa = vkd.GetDeviceProcAddr ? vkd.GetDeviceProcAddr : (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
#define LOAD_D(fn) vkd.fn = (PFN_vk##fn)(gdpa ? gdpa(device, "vk"#fn) : gipa(instance, "vk"#fn))
    LOAD_D(DestroyDevice);
    LOAD_D(GetDeviceQueue);
    LOAD_D(DeviceWaitIdle);
    LOAD_D(CreateBuffer);
    LOAD_D(DestroyBuffer);
    LOAD_D(GetBufferMemoryRequirements);
    LOAD_D(AllocateMemory);
    LOAD_D(FreeMemory);
    LOAD_D(BindBufferMemory);
    LOAD_D(MapMemory);
    LOAD_D(UnmapMemory);
    LOAD_D(CreateImage);
    LOAD_D(DestroyImage);
    LOAD_D(GetImageMemoryRequirements);
    LOAD_D(BindImageMemory);
    LOAD_D(CreateImageView);
    LOAD_D(DestroyImageView);
    LOAD_D(CreateSampler);
    LOAD_D(DestroySampler);
    LOAD_D(CreateDescriptorSetLayout);
    LOAD_D(DestroyDescriptorSetLayout);
    LOAD_D(CreateDescriptorPool);
    LOAD_D(DestroyDescriptorPool);
    LOAD_D(AllocateDescriptorSets);
    LOAD_D(UpdateDescriptorSets);
    LOAD_D(CreatePipelineLayout);
    LOAD_D(DestroyPipelineLayout);
    LOAD_D(CreateGraphicsPipelines);
    LOAD_D(CreateComputePipelines);
    LOAD_D(DestroyPipeline);
    LOAD_D(CreateShaderModule);
    LOAD_D(DestroyShaderModule);
    LOAD_D(CmdBindPipeline);
    LOAD_D(CmdBindDescriptorSets);
    LOAD_D(CmdPipelineBarrier);
    LOAD_D(CmdCopyBufferToImage);
    LOAD_D(CmdBlitImage);
    LOAD_D(CmdCopyImage);
    LOAD_D(CmdDispatch);
    LOAD_D(QueueSubmit);
    LOAD_D(QueueWaitIdle);
#undef LOAD_D

    if (!vkd.AllocateMemory || !vkd.FreeMemory ||
        !vkd.CreateBuffer || !vkd.DestroyBuffer ||
        !vkd.CreateImage || !vkd.DestroyImage ||
        !vkd.CreateImageView || !vkd.DestroyImageView ||
        !vkd.CreateSampler || !vkd.DestroySampler ||
        !vkd.CreateDescriptorSetLayout || !vkd.CreateDescriptorPool ||
        !vkd.AllocateDescriptorSets || !vkd.UpdateDescriptorSets ||
        !vkd.CreatePipelineLayout || !vkd.CreateComputePipelines ||
        !vkd.DestroyPipeline || !vkd.CreateShaderModule ||
        !vkd.CmdBindPipeline || !vkd.CmdBindDescriptorSets ||
        !vkd.CmdPipelineBarrier || !vkd.CmdDispatch ||
        !vkd.QueueSubmit) {
        memset(&vkd, 0, sizeof(vkd));
        return false;
    }

    return true;
}

void vkd_unload(void) {
    memset(&vkd, 0, sizeof(vkd));
}
