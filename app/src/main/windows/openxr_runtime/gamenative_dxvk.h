#ifndef GAMENATIVE_DXVK_H
#define GAMENATIVE_DXVK_H

#include <windows.h>
#include <d3d11.h>
#include "gamenative_openxr_unix_abi.h"

typedef struct gamenative_dxvk_context gamenative_dxvk_context;

HRESULT gamenative_dxvk_open(ID3D11Device *device, gamenative_dxvk_context **context, gamenative_xr_vulkan_context *vulkan);
void gamenative_dxvk_close(gamenative_dxvk_context *context);
void gamenative_dxvk_flush(gamenative_dxvk_context *context);
void gamenative_dxvk_lock(gamenative_dxvk_context *context);
void gamenative_dxvk_unlock(gamenative_dxvk_context *context);
HRESULT gamenative_dxvk_get_image(ID3D11Texture2D *texture, uint64_t *image, uint32_t *layout);

#endif
