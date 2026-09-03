#ifndef GAMENATIVE_OPENXR_UNIX_ABI_H
#define GAMENATIVE_OPENXR_UNIX_ABI_H

#include <stdint.h>

#define GAMENATIVE_XR_UNIX_ABI_VERSION 2u
#define GAMENATIVE_XR_MAX_SWAPCHAINS 32u
#define GAMENATIVE_XR_MAX_IMAGES 4u
#define GAMENATIVE_XR_MAX_VIEWS 2u

#pragma pack(push, 1)
typedef struct gamenative_xr_unix_header {
    uint32_t abi_version;
    uint32_t operation;
    uint32_t payload_size;
    int32_t result;
} gamenative_xr_unix_header;

typedef struct gamenative_xr_vulkan_context {
    gamenative_xr_unix_header header;
    uint64_t physical_device;
    uint64_t device;
    uint64_t queue;
    uint32_t queue_family;
    uint32_t queue_index;
    uint32_t host_handles;
    uint32_t reserved;
} gamenative_xr_vulkan_context;

typedef struct gamenative_xr_swapchain_create {
    gamenative_xr_unix_header header;
    uint32_t swapchain;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t image_count;
    uint32_t array_size;
    uint32_t usage;
    uint32_t reserved;
} gamenative_xr_swapchain_create;

typedef struct gamenative_xr_swapchain_destroy {
    gamenative_xr_unix_header header;
    uint32_t swapchain;
    uint32_t reserved;
} gamenative_xr_swapchain_destroy;

typedef struct gamenative_xr_image_acquire {
    gamenative_xr_unix_header header;
    uint32_t swapchain;
    uint32_t image_index;
    uint32_t slot;
    uint32_t timeout_ms;
    uint64_t image_handle;
} gamenative_xr_image_acquire;

typedef struct gamenative_xr_image_copy {
    gamenative_xr_unix_header header;
    uint32_t swapchain;
    uint32_t image_index;
    uint64_t source_image;
    uint32_t source_layout;
    uint32_t array_index;
    int32_t crop_x;
    int32_t crop_y;
    int32_t crop_width;
    int32_t crop_height;
} gamenative_xr_image_copy;

typedef struct gamenative_xr_view_submission {
    uint32_t swapchain;
    uint32_t slot;
    uint32_t image_index;
    uint32_t eye;
    uint32_t array_index;
    int32_t crop_x;
    int32_t crop_y;
    int32_t crop_width;
    int32_t crop_height;
    int32_t orientation[4];
    int32_t position[3];
    int32_t fov[4];
} gamenative_xr_view_submission;

typedef struct gamenative_xr_stereo_submission {
    gamenative_xr_unix_header header;
    uint64_t frame_id;
    uint32_t view_count;
    uint32_t reserved;
    gamenative_xr_view_submission views[GAMENATIVE_XR_MAX_VIEWS];
} gamenative_xr_stereo_submission;
#pragma pack(pop)

enum gamenative_xr_unix_operation {
    GAMENATIVE_XR_UNIX_INITIALIZE = 1,
    GAMENATIVE_XR_UNIX_SET_VULKAN_CONTEXT = 2,
    GAMENATIVE_XR_UNIX_CREATE_SWAPCHAIN = 3,
    GAMENATIVE_XR_UNIX_DESTROY_SWAPCHAIN = 4,
    GAMENATIVE_XR_UNIX_ACQUIRE_IMAGE = 5,
    GAMENATIVE_XR_UNIX_SUBMIT_VIEW = 6,
    GAMENATIVE_XR_UNIX_SUBMIT_STEREO = 7,
    GAMENATIVE_XR_UNIX_COPY_IMAGE = 8
};

enum gamenative_xr_unix_result {
    GAMENATIVE_XR_UNIX_OK = 0,
    GAMENATIVE_XR_UNIX_INVALID_ARGUMENT = -1,
    GAMENATIVE_XR_UNIX_UNAVAILABLE = -2,
    GAMENATIVE_XR_UNIX_VULKAN_ERROR = -3,
    GAMENATIVE_XR_UNIX_TRANSPORT_ERROR = -4,
    GAMENATIVE_XR_UNIX_TIMEOUT = -5
};

#endif
