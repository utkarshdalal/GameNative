#include "stdafx.h"

#include "DrvGameNative.h"

#include "DrvOpenXR.h"
#include "XrBackend.h"
#include "logging.h"

#include "gamenative_control.h"
#include "gamenative_openxr_unix.h"

#include <d3d11.h>
#include <dxgi.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>

namespace {

using WineUnixCall = int(__stdcall*)(unsigned int, void*);

struct DxgiVkInteropDevice;
struct DxgiVkInteropDevice1;

struct DxgiVkInteropDeviceVtable {
    HRESULT(__stdcall* QueryInterface)(DxgiVkInteropDevice*, REFIID, void**);
    ULONG(__stdcall* AddRef)(DxgiVkInteropDevice*);
    ULONG(__stdcall* Release)(DxgiVkInteropDevice*);
    void(__stdcall* GetVulkanHandles)(DxgiVkInteropDevice*, uint64_t*, uint64_t*, uint64_t*);
    void(__stdcall* GetSubmissionQueue)(DxgiVkInteropDevice*, uint64_t*, uint32_t*);
    void(__stdcall* TransitionSurfaceLayout)(DxgiVkInteropDevice*, void*, const void*, uint32_t, uint32_t);
    void(__stdcall* FlushRenderingCommands)(DxgiVkInteropDevice*);
    void(__stdcall* LockSubmissionQueue)(DxgiVkInteropDevice*);
    void(__stdcall* ReleaseSubmissionQueue)(DxgiVkInteropDevice*);
};

struct DxgiVkInteropDevice1Vtable : DxgiVkInteropDeviceVtable {
    void(__stdcall* GetSubmissionQueue1)(DxgiVkInteropDevice*, uint64_t*, uint32_t*, uint32_t*);
    HRESULT(__stdcall* CreateTexture2DFromVkImage)(DxgiVkInteropDevice*, const void*, uint64_t, ID3D11Texture2D**);
};

struct DxgiVkInteropDevice {
    const DxgiVkInteropDeviceVtable* lpVtbl;
};

struct D3D11Texture2DDesc1 {
    UINT Width;
    UINT Height;
    UINT MipLevels;
    UINT ArraySize;
    DXGI_FORMAT Format;
    DXGI_SAMPLE_DESC SampleDesc;
    D3D11_USAGE Usage;
    UINT BindFlags;
    UINT CPUAccessFlags;
    UINT MiscFlags;
    UINT TextureLayout;
};

constexpr GUID kIidDxgiVkInteropDevice1 = {
    0xe2ef5fa5, 0xdc21, 0x4af7, {0x90, 0xc4, 0xf6, 0x7e, 0xf6, 0xa0, 0x93, 0x24}
};

int64_t dxgiToVulkan(DXGI_FORMAT format)
{
    switch (format) {
    case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
        return 43; // VK_FORMAT_R8G8B8A8_SRGB
    case DXGI_FORMAT_R8G8B8A8_UNORM:
        return 37; // VK_FORMAT_R8G8B8A8_UNORM
    case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
        return 50; // VK_FORMAT_B8G8R8A8_SRGB
    case DXGI_FORMAT_B8G8R8A8_UNORM:
        return 44; // VK_FORMAT_B8G8R8A8_UNORM
    default:
        return 0;
    }
}

int64_t toMicro(float value)
{
    return static_cast<int64_t>(std::llround(static_cast<double>(value) * 1000000.0));
}

class DirectD3D11Transport {
public:
    DirectD3D11Transport() = default;
    ~DirectD3D11Transport() { shutdown(); }

    bool store(vr::EVREye eye, const vr::Texture_t* texture, const vr::VRTextureBounds_t* bounds)
    {
        if (!texture || !texture->handle || texture->eType != vr::TextureType_DirectX) {
            OOVR_LOGF("DrvGameNative: unsupported texture type %d", texture ? texture->eType : -1);
            return false;
        }

        auto* source = static_cast<ID3D11Texture2D*>(texture->handle);
        D3D11_TEXTURE2D_DESC sourceDesc{};
        source->GetDesc(&sourceDesc);
        if (!ensureDevice(source)) return false;

        const unsigned eyeIndex = eye == vr::Eye_Right ? 1u : 0u;
        D3D11_BOX sourceBox{};
        sourceBox.left = 0;
        sourceBox.top = 0;
        sourceBox.front = 0;
        sourceBox.right = sourceDesc.Width;
        sourceBox.bottom = sourceDesc.Height;
        sourceBox.back = 1;
        bool verticallyInverted = false;
        if (bounds) {
            const float uMin = std::clamp(std::min(bounds->uMin, bounds->uMax), 0.0f, 1.0f);
            const float uMax = std::clamp(std::max(bounds->uMin, bounds->uMax), 0.0f, 1.0f);
            const float vMin = std::clamp(std::min(bounds->vMin, bounds->vMax), 0.0f, 1.0f);
            const float vMax = std::clamp(std::max(bounds->vMin, bounds->vMax), 0.0f, 1.0f);
            sourceBox.left = static_cast<UINT>(std::floor(uMin * sourceDesc.Width));
            sourceBox.right = static_cast<UINT>(std::ceil(uMax * sourceDesc.Width));
            sourceBox.top = static_cast<UINT>(std::floor(vMin * sourceDesc.Height));
            sourceBox.bottom = static_cast<UINT>(std::ceil(vMax * sourceDesc.Height));
            verticallyInverted = bounds->vMin > bounds->vMax;
        }
        if (sourceBox.right <= sourceBox.left || sourceBox.bottom <= sourceBox.top) return false;

        const UINT width = sourceBox.right - sourceBox.left;
        const UINT height = sourceBox.bottom - sourceBox.top;
        EyeChain& chain = eyes_[eyeIndex];
        if (!ensureChain(chain, eyeIndex, width, height, sourceDesc.Format)) return false;
        if (!acquire(chain)) return false;

        const UINT sourceArrayIndex = sourceDesc.ArraySize > 1 ? eyeIndex : 0;
        const UINT sourceSubresource = D3D11CalcSubresource(0, sourceArrayIndex, sourceDesc.MipLevels);
        ID3D11Texture2D* copySource = source;
        UINT copySubresource = sourceSubresource;
        if (sourceDesc.SampleDesc.Count > 1) {
            if (!ensureResolve(chain, sourceDesc)) return false;
            context_->ResolveSubresource(chain.resolve, sourceSubresource, source, sourceSubresource, sourceDesc.Format);
            copySource = chain.resolve;
        }

        context_->CopySubresourceRegion(
            chain.images[chain.current], 0, 0, 0, 0, copySource, copySubresource, &sourceBox);
        chain.ready = true;
        chain.verticallyInverted = verticallyInverted;
        if (verticallyInverted && !loggedInverted_) {
            loggedInverted_ = true;
            OOVR_LOG("DrvGameNative: vertically inverted bounds use the compatibility copy path");
        }
        return true;
    }

    bool submit(const gamenative_control_view views[2])
    {
        if (!ready_ || !eyes_[0].ready || !eyes_[1].ready) return false;

        interop_->lpVtbl->FlushRenderingCommands(interop_);
        interop_->lpVtbl->LockSubmissionQueue(interop_);

        gn_unix_submit_stereo_args args{};
        args.view_count = 2;
        for (unsigned eye = 0; eye < 2; ++eye) {
            EyeChain& chain = eyes_[eye];
            gn_unix_submit_view_args& view = args.views[eye];
            view.slot = chain.slot;
            view.image_index = chain.current;
            view.eye = eye;
            view.array_index = 0;
            view.rect_x = 0;
            view.rect_y = 0;
            view.rect_width = chain.width;
            view.rect_height = chain.height;
            for (unsigned i = 0; i < 4; ++i) {
                view.orientation_micro[i] = views[eye].orientation[i];
                view.fov_micro[i] = views[eye].fov[i];
            }
            for (unsigned i = 0; i < 3; ++i) view.position_micro[i] = views[eye].position[i];
        }
        args.result = GN_UNIX_ERROR_UNAVAILABLE;
        const bool ok = unixCall_(GN_UNIX_SUBMIT_STEREO, &args) == 0 && args.result == GN_UNIX_SUCCESS;
        interop_->lpVtbl->ReleaseSubmissionQueue(interop_);
        if (!ok) {
            OOVR_LOGF("DrvGameNative: stereo submit failed result=%d", args.result);
            return false;
        }
        for (EyeChain& chain : eyes_) {
            chain.submitted[chain.current] = true;
            chain.ready = false;
            chain.next = (chain.current + 1) % chain.imageCount;
        }
        ++submittedFrames_;
        if (submittedFrames_ == 1) {
            OOVR_LOG("DrvGameNative: first device-local stereo frame submitted");
        }
        return true;
    }

    bool configured() const { return ready_; }

    bool locateViews(gamenative_control_view views[2], uint32_t* flags)
    {
        if (!views || !flags || !ensureBridge()) return false;
        gn_unix_control_transact_args control{};
        strcpy_s(control.request, sizeof(control.request), "LOCATE_VIEWS");
        control.response_lines = 1;
        control.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (unixCall_(GN_UNIX_CONTROL_TRANSACT, &control) != 0 || control.result != GN_UNIX_SUCCESS) {
            OOVR_LOGF("DrvGameNative: fast view request failed result=%d", control.result);
            return false;
        }
        int consumed = 0;
        if (sscanf_s(control.response, "OK flags=%u %n", flags, &consumed) < 1 || consumed <= 0) return false;
        const char* cursor = control.response + consumed;
        for (uint32_t eye = 0; eye < 2; ++eye) {
            int32_t* values[] = {
                &views[eye].orientation[0], &views[eye].orientation[1],
                &views[eye].orientation[2], &views[eye].orientation[3],
                &views[eye].position[0], &views[eye].position[1], &views[eye].position[2],
                &views[eye].fov[0], &views[eye].fov[1], &views[eye].fov[2], &views[eye].fov[3],
            };
            for (int32_t* value : values) {
                char* end = nullptr;
                const long parsed = strtol(cursor, &end, 10);
                if (end == cursor) return false;
                *value = static_cast<int32_t>(parsed);
                cursor = end;
                while (*cursor == ' ') ++cursor;
            }
        }
        return true;
    }

private:
    struct EyeChain {
        uint32_t slot = 0;
        uint32_t width = 0;
        uint32_t height = 0;
        DXGI_FORMAT format = DXGI_FORMAT_UNKNOWN;
        uint32_t imageCount = 0;
        uint32_t current = 0;
        uint32_t next = 0;
        std::array<ID3D11Texture2D*, GN_UNIX_MAX_IMAGES> images{};
        std::array<bool, GN_UNIX_MAX_IMAGES> submitted{};
        ID3D11Texture2D* resolve = nullptr;
        bool ready = false;
        bool verticallyInverted = false;
    };

    bool ensureBridge()
    {
        if (unixCall_) return true;
        bridge_ = LoadLibraryA("gamenative_xr_unixbridge.dll");
        unixCall_ = bridge_ ? reinterpret_cast<WineUnixCall>(GetProcAddress(bridge_, "gnWineUnixCall")) : nullptr;
        if (!unixCall_) {
            OOVR_LOGF("DrvGameNative: Wine unix bridge unavailable error=%lu", GetLastError());
            if (bridge_) FreeLibrary(bridge_);
            bridge_ = nullptr;
            return false;
        }
        gn_unix_init_args init{};
        init.abi_version = GN_UNIX_ABI_VERSION;
        init.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (unixCall_(GN_UNIX_INIT, &init) != 0 || init.result != GN_UNIX_SUCCESS) {
            OOVR_LOGF("DrvGameNative: unix ABI handshake failed abi=%u result=%d", GN_UNIX_ABI_VERSION, init.result);
            FreeLibrary(bridge_);
            bridge_ = nullptr;
            unixCall_ = nullptr;
            return false;
        }
        OOVR_LOGF("DrvGameNative: unix bridge ready ABI=%u control=unix-fast", GN_UNIX_ABI_VERSION);
        return true;
    }

    bool ensureDevice(ID3D11Texture2D* source)
    {
        ID3D11Device* sourceDevice = nullptr;
        source->GetDevice(&sourceDevice);
        if (!sourceDevice) return false;
        if (device_ == sourceDevice && ready_) {
            sourceDevice->Release();
            return true;
        }
        if (device_ && device_ != sourceDevice) shutdown();
        device_ = sourceDevice;
        device_->GetImmediateContext(&context_);

        void* rawInterop = nullptr;
        HRESULT hr = device_->QueryInterface(kIidDxgiVkInteropDevice1, &rawInterop);
        if (FAILED(hr) || !rawInterop) {
            OOVR_LOGF("DrvGameNative: IDXGIVkInteropDevice1 unavailable hr=0x%08x", static_cast<unsigned>(hr));
            shutdown();
            return false;
        }
        interop_ = static_cast<DxgiVkInteropDevice*>(rawInterop);
        const auto* interop1 = reinterpret_cast<const DxgiVkInteropDevice1Vtable*>(interop_->lpVtbl);

        uint64_t instance = 0;
        uint64_t physicalDevice = 0;
        uint64_t device = 0;
        uint64_t queue = 0;
        uint32_t queueFamily = 0;
        uint32_t queueIndex = 0;
        interop_->lpVtbl->GetVulkanHandles(interop_, &instance, &physicalDevice, &device);
        interop1->GetSubmissionQueue1(interop_, &queue, &queueIndex, &queueFamily);

        if (!ensureBridge()) {
            shutdown();
            return false;
        }

        gn_unix_vulkan_context_args vk{};
        vk.client_physical_device = physicalDevice;
        vk.client_device = device;
        vk.client_queue = queue;
        vk.queue_family_index = queueFamily;
        vk.queue_index = queueIndex;
        vk.handles_are_host = 0;
        vk.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (unixCall_(GN_UNIX_SET_VULKAN_CONTEXT, &vk) != 0 || vk.result != GN_UNIX_SUCCESS) {
            OOVR_LOGF("DrvGameNative: Vulkan context rejected result=%d flags=0x%x", vk.result, vk.diagnostic_flags);
            shutdown();
            return false;
        }
        ready_ = true;
        OOVR_LOGF("DrvGameNative: direct transport ready ABI=%u queue=%u:%u", GN_UNIX_ABI_VERSION, queueFamily, queueIndex);
        return true;
    }

    bool ensureChain(EyeChain& chain, uint32_t eye, UINT width, UINT height, DXGI_FORMAT format)
    {
        if (chain.imageCount && chain.width == width && chain.height == height && chain.format == format) return true;
        destroyChain(chain);
        const int64_t vkFormat = dxgiToVulkan(format);
        if (!vkFormat) {
            OOVR_LOGF("DrvGameNative: unsupported DXGI format %d", format);
            return false;
        }

        chain.slot = 28u + eye;
        gn_unix_create_swapchain_args create{};
        create.slot = chain.slot;
        create.width = width;
        create.height = height;
        create.array_size = 1;
        create.mip_count = 1;
        create.sample_count = 1;
        create.format = vkFormat;
        // XrSwapchainUsageFlags: color attachment, transfer destination and sampled.
        create.usage = 0x1u | 0x10u | 0x20u;
        create.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (unixCall_(GN_UNIX_CREATE_SWAPCHAIN, &create) != 0 ||
            create.result != GN_UNIX_SUCCESS || create.image_count < 2 || create.image_count > GN_UNIX_MAX_IMAGES) {
            OOVR_LOGF("DrvGameNative: eye %u transport swapchain failed result=%d", eye, create.result);
            return false;
        }
        chain.imageCount = create.image_count;

        const auto* interop1 = reinterpret_cast<const DxgiVkInteropDevice1Vtable*>(interop_->lpVtbl);
        D3D11Texture2DDesc1 desc{};
        desc.Width = width;
        desc.Height = height;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = format;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
        for (uint32_t i = 0; i < create.image_count; ++i) {
            HRESULT hr = interop1->CreateTexture2DFromVkImage(interop_, &desc, create.images[i], &chain.images[i]);
            if (FAILED(hr) || !chain.images[i]) {
                OOVR_LOGF("DrvGameNative: wrapping eye %u image %u failed hr=0x%08x", eye, i, static_cast<unsigned>(hr));
                destroyChain(chain);
                return false;
            }
        }
        chain.width = width;
        chain.height = height;
        chain.format = format;
        OOVR_LOGF("DrvGameNative: registered eye=%u images=%u size=%ux%u format=%d path=device-local-gpu-copy",
            eye, chain.imageCount, width, height, format);
        return true;
    }

    bool ensureResolve(EyeChain& chain, const D3D11_TEXTURE2D_DESC& sourceDesc)
    {
        if (chain.resolve) return true;
        D3D11_TEXTURE2D_DESC desc = sourceDesc;
        desc.SampleDesc.Count = 1;
        desc.SampleDesc.Quality = 0;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.CPUAccessFlags = 0;
        desc.MiscFlags = 0;
        return SUCCEEDED(device_->CreateTexture2D(&desc, nullptr, &chain.resolve)) && chain.resolve;
    }

    bool acquire(EyeChain& chain)
    {
        chain.current = chain.next;
        if (!chain.submitted[chain.current]) return true;
        gn_unix_acquire_image_args acquire{};
        acquire.slot = chain.slot;
        acquire.image_index = chain.current;
        acquire.timeout_ns = 500000000;
        acquire.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (unixCall_(GN_UNIX_ACQUIRE_IMAGE, &acquire) != 0 || acquire.result != GN_UNIX_SUCCESS) {
            OOVR_LOGF("DrvGameNative: acquire timeout/failure slot=%u image=%u result=%d",
                chain.slot, chain.current, acquire.result);
            return false;
        }
        chain.submitted[chain.current] = false;
        return true;
    }

    void destroyChain(EyeChain& chain)
    {
        if (chain.resolve) chain.resolve->Release();
        chain.resolve = nullptr;
        for (ID3D11Texture2D*& image : chain.images) {
            if (image) image->Release();
            image = nullptr;
        }
        if (chain.imageCount && unixCall_) {
            gn_unix_destroy_swapchain_args destroy{};
            destroy.slot = chain.slot;
            destroy.result = GN_UNIX_ERROR_UNAVAILABLE;
            unixCall_(GN_UNIX_DESTROY_SWAPCHAIN, &destroy);
        }
        chain = EyeChain{};
    }

    void shutdown()
    {
        for (EyeChain& chain : eyes_) destroyChain(chain);
        if (interop_) interop_->lpVtbl->Release(interop_);
        interop_ = nullptr;
        if (context_) context_->Release();
        context_ = nullptr;
        if (device_) device_->Release();
        device_ = nullptr;
        if (bridge_) FreeLibrary(bridge_);
        bridge_ = nullptr;
        unixCall_ = nullptr;
        ready_ = false;
    }

    ID3D11Device* device_ = nullptr;
    ID3D11DeviceContext* context_ = nullptr;
    DxgiVkInteropDevice* interop_ = nullptr;
    HMODULE bridge_ = nullptr;
    WineUnixCall unixCall_ = nullptr;
    std::array<EyeChain, 2> eyes_{};
    uint64_t submittedFrames_ = 0;
    bool ready_ = false;
    bool loggedInverted_ = false;
};

class Backend final : public IBackend {
public:
    explicit Backend(XrBackend* compatibility)
        : compatibility_(compatibility)
    {
        OOVR_LOGF("DrvGameNative: selected backend protocol=%u graphics=direct-unixlib input=openxr-compat", GN_UNIX_ABI_VERSION);
    }

    ~Backend() override { delete compatibility_; }

    std::shared_ptr<IHMD> GetPrimaryHMD() override { return compatibility_->GetPrimaryHMD(); }
    std::shared_ptr<ITrackedDevice> GetDevice(vr::TrackedDeviceIndex_t index) override { return compatibility_->GetDevice(index); }
    std::shared_ptr<ITrackedDevice> GetDeviceByHand(ITrackedDevice::TrackedDeviceType hand) override { return compatibility_->GetDeviceByHand(hand); }
    void GetDeviceToAbsoluteTrackingPose(vr::ETrackingUniverseOrigin origin, float prediction, vr::TrackedDevicePose_t* poses, uint32_t count) override
    {
        compatibility_->GetDeviceToAbsoluteTrackingPose(origin, prediction, poses, count);
    }

    void WaitForTrackingData() override
    {
        compatibility_->WaitForTrackingData();
        uint32_t flags = 0;
        if (!transport_.locateViews(views_.data(), &flags)) {
            OOVR_LOG("DrvGameNative: view snapshot unavailable after frame wait");
        } else {
            viewFlags_ = flags;
        }
    }

    void StoreEyeTexture(vr::EVREye eye, const vr::Texture_t* texture, const vr::VRTextureBounds_t* bounds,
        vr::EVRSubmitFlags submitFlags, bool isFirstEye) override
    {
        (void)submitFlags;
        (void)isFirstEye;
        submitted_[eye == vr::Eye_Right ? 1 : 0] = transport_.store(eye, texture, bounds);
    }

    void SubmitFrames(bool showSkybox, bool postPresent) override
    {
        (void)showSkybox;
        if (!postPresent && submitted_[0] && submitted_[1] && viewFlags_ != 0) {
            transport_.submit(views_.data());
        }
        submitted_[0] = submitted_[1] = false;
        // End the control-only OpenXR compatibility frame with no graphics layers.
        compatibility_->SubmitFrames(false, postPresent);
    }

    openvr_enum_t SetSkyboxOverride(const vr::Texture_t* textures, uint32_t count) override
    {
        return compatibility_->SetSkyboxOverride(textures, count);
    }
    void ClearSkyboxOverride() override { compatibility_->ClearSkyboxOverride(); }
    bool GetFrameTiming(OOVR_Compositor_FrameTiming* timing, uint32_t framesAgo) override
    {
        return compatibility_->GetFrameTiming(timing, framesAgo);
    }
    openvr_enum_t GetMirrorTextureD3D11(vr::EVREye eye, void* device, void** view) override
    {
        return compatibility_->GetMirrorTextureD3D11(eye, device, view);
    }
    void ReleaseMirrorTextureD3D11(void* view) override { compatibility_->ReleaseMirrorTextureD3D11(view); }
    bool GetPlayAreaPoints(vr::HmdVector3_t* points, int* count) override { return compatibility_->GetPlayAreaPoints(points, count); }
    bool AreBoundsVisible() override { return compatibility_->AreBoundsVisible(); }
    void ForceBoundsVisible(bool visible) override { compatibility_->ForceBoundsVisible(visible); }
    void PumpEvents() override { compatibility_->PumpEvents(); }
    bool IsInputAvailable() override { return compatibility_->IsInputAvailable(); }
    bool IsGraphicsConfigured() override { return transport_.configured(); }
    void OnOverlayTexture(const vr::Texture_t* texture) override { compatibility_->OnOverlayTexture(texture); }

private:
    XrBackend* compatibility_;
    DirectD3D11Transport transport_;
    std::array<gamenative_control_view, 2> views_{};
    std::array<bool, 2> submitted_{};
    uint32_t viewFlags_ = 0;
};

} // namespace

IBackend* DrvGameNative::CreateGameNativeBackend(const char* startupInfo)
{
    auto* compatibility = static_cast<XrBackend*>(DrvOpenXR::CreateOpenXRBackend(startupInfo));
    if (!compatibility) {
        OOVR_LOG("DrvGameNative: failed to initialize OpenXR input compatibility layer");
        return nullptr;
    }
    return new Backend(compatibility);
}
