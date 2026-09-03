#define COBJMACROS
#include "gamenative_dxvk.h"

#include <stdlib.h>
#include <string.h>

typedef struct IDXGIVkInteropDevice IDXGIVkInteropDevice;
typedef struct IDXGIVkInteropSurface IDXGIVkInteropSurface;

typedef struct IDXGIVkInteropDeviceVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(IDXGIVkInteropDevice *, REFIID, void **);
    ULONG (STDMETHODCALLTYPE *AddRef)(IDXGIVkInteropDevice *);
    ULONG (STDMETHODCALLTYPE *Release)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *GetVulkanHandles)(IDXGIVkInteropDevice *, uint64_t *, uint64_t *, uint64_t *);
    void (STDMETHODCALLTYPE *GetSubmissionQueue)(IDXGIVkInteropDevice *, uint64_t *, uint32_t *);
    void (STDMETHODCALLTYPE *TransitionSurfaceLayout)(IDXGIVkInteropDevice *, void *, const void *, uint32_t, uint32_t);
    void (STDMETHODCALLTYPE *FlushRenderingCommands)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *LockSubmissionQueue)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *ReleaseSubmissionQueue)(IDXGIVkInteropDevice *);
} IDXGIVkInteropDeviceVtbl;

struct IDXGIVkInteropDevice { const IDXGIVkInteropDeviceVtbl *lpVtbl; };
typedef struct IDXGIVkInteropSurfaceVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(IDXGIVkInteropSurface *, REFIID, void **);
    ULONG (STDMETHODCALLTYPE *AddRef)(IDXGIVkInteropSurface *);
    ULONG (STDMETHODCALLTYPE *Release)(IDXGIVkInteropSurface *);
    HRESULT (STDMETHODCALLTYPE *GetDevice)(IDXGIVkInteropSurface *, IDXGIVkInteropDevice **);
    HRESULT (STDMETHODCALLTYPE *GetVulkanImageInfo)(IDXGIVkInteropSurface *, uint64_t *, uint32_t *, void *);
} IDXGIVkInteropSurfaceVtbl;
struct IDXGIVkInteropSurface { const IDXGIVkInteropSurfaceVtbl *lpVtbl; };
typedef struct IDXGIVkInteropDevice1Vtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(IDXGIVkInteropDevice *, REFIID, void **);
    ULONG (STDMETHODCALLTYPE *AddRef)(IDXGIVkInteropDevice *);
    ULONG (STDMETHODCALLTYPE *Release)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *GetVulkanHandles)(IDXGIVkInteropDevice *, uint64_t *, uint64_t *, uint64_t *);
    void (STDMETHODCALLTYPE *GetSubmissionQueue)(IDXGIVkInteropDevice *, uint64_t *, uint32_t *);
    void (STDMETHODCALLTYPE *TransitionSurfaceLayout)(IDXGIVkInteropDevice *, void *, const void *, uint32_t, uint32_t);
    void (STDMETHODCALLTYPE *FlushRenderingCommands)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *LockSubmissionQueue)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *ReleaseSubmissionQueue)(IDXGIVkInteropDevice *);
    void (STDMETHODCALLTYPE *GetSubmissionQueue1)(IDXGIVkInteropDevice *, uint64_t *, uint32_t *, uint32_t *);
    HRESULT (STDMETHODCALLTYPE *CreateTexture2DFromVkImage)(IDXGIVkInteropDevice *, const void *, ID3D11Texture2D **);
} IDXGIVkInteropDevice1Vtbl;

struct gamenative_dxvk_context { IDXGIVkInteropDevice *interop; uint32_t device1; };

static const GUID iid_dxgi_vk_interop_device = {0xe2ef5fa5, 0xdc21, 0x4af7, {0x90, 0xc4, 0xf6, 0x7e, 0xf6, 0xa0, 0x93, 0x23}};
static const GUID iid_dxgi_vk_interop_device1 = {0xe2ef5fa5, 0xdc21, 0x4af7, {0x90, 0xc4, 0xf6, 0x7e, 0xf6, 0xa0, 0x93, 0x24}};
static const GUID iid_dxgi_vk_interop_surface = {0x5546cf8c, 0x77e7, 0x4341, {0xb0, 0x5d, 0x8d, 0x4d, 0x50, 0x00, 0xe7, 0x7d}};

HRESULT gamenative_dxvk_open(ID3D11Device *device, gamenative_dxvk_context **output, gamenative_xr_vulkan_context *vulkan) {
    if (!device || !output || !vulkan) return E_INVALIDARG;
    *output = NULL;
    IDXGIVkInteropDevice *interop = NULL;
    HRESULT result = ID3D11Device_QueryInterface(device, &iid_dxgi_vk_interop_device1, (void **)&interop);
    uint32_t device1 = SUCCEEDED(result) && interop != NULL;
    if (!device1) result = ID3D11Device_QueryInterface(device, &iid_dxgi_vk_interop_device, (void **)&interop);
    if (FAILED(result) || !interop) return E_NOINTERFACE;
    gamenative_dxvk_context *context = calloc(1, sizeof(*context));
    if (!context) { interop->lpVtbl->Release(interop); return E_OUTOFMEMORY; }
    uint64_t instance = 0;
    memset(vulkan, 0, sizeof(*vulkan));
    interop->lpVtbl->GetVulkanHandles(interop, &instance, &vulkan->physical_device, &vulkan->device);
    if (device1) {
        const IDXGIVkInteropDevice1Vtbl *vtable = (const IDXGIVkInteropDevice1Vtbl *)interop->lpVtbl;
        vtable->GetSubmissionQueue1(interop, &vulkan->queue, &vulkan->queue_index, &vulkan->queue_family);
    } else {
        interop->lpVtbl->GetSubmissionQueue(interop, &vulkan->queue, &vulkan->queue_family);
        vulkan->queue_index = 0;
    }
    if (!vulkan->physical_device || !vulkan->device || !vulkan->queue) { interop->lpVtbl->Release(interop); free(context); return E_FAIL; }
    vulkan->header.abi_version = GAMENATIVE_XR_UNIX_ABI_VERSION;
    vulkan->header.operation = GAMENATIVE_XR_UNIX_SET_VULKAN_CONTEXT;
    vulkan->header.payload_size = sizeof(*vulkan);
    vulkan->host_handles = 0;
    context->interop = interop;
    context->device1 = device1;
    *output = context;
    return S_OK;
}

void gamenative_dxvk_close(gamenative_dxvk_context *context) {
    if (!context) return;
    context->interop->lpVtbl->Release(context->interop);
    free(context);
}

void gamenative_dxvk_flush(gamenative_dxvk_context *context) {
    if (context) context->interop->lpVtbl->FlushRenderingCommands(context->interop);
}

void gamenative_dxvk_lock(gamenative_dxvk_context *context) {
    if (context) context->interop->lpVtbl->LockSubmissionQueue(context->interop);
}

void gamenative_dxvk_unlock(gamenative_dxvk_context *context) {
    if (context) context->interop->lpVtbl->ReleaseSubmissionQueue(context->interop);
}

HRESULT gamenative_dxvk_get_image(ID3D11Texture2D *texture, uint64_t *image, uint32_t *layout) {
    if (!texture || !image || !layout) return E_INVALIDARG;
    IDXGIVkInteropSurface *surface = NULL;
    HRESULT result = ID3D11Texture2D_QueryInterface(texture, &iid_dxgi_vk_interop_surface, (void **)&surface);
    if (FAILED(result) || !surface) return E_NOINTERFACE;
    result = surface->lpVtbl->GetVulkanImageInfo(surface, image, layout, NULL);
    surface->lpVtbl->Release(surface);
    return result;
}
