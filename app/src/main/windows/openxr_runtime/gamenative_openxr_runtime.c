



#define XR_NO_PROTOTYPES
#define XR_EXTENSION_PROTOTYPES

typedef void* VkInstance;
typedef void* VkPhysicalDevice;
typedef void* VkDevice;
typedef void* VkImage;
typedef void* VkQueue;
typedef int VkResult;
typedef struct ID3D11Device ID3D11Device;
typedef struct ID3D11Texture2D ID3D11Texture2D;
typedef struct ID3D12Device ID3D12Device;
typedef struct ID3D12CommandQueue ID3D12CommandQueue;
typedef struct ID3D12Resource ID3D12Resource;
typedef struct LUID {
    unsigned long LowPart;
    long HighPart;
} LUID;
typedef enum D3D_FEATURE_LEVEL {
    D3D_FEATURE_LEVEL_11_0 = 0xb000
} D3D_FEATURE_LEVEL;

#include <openxr/openxr.h>
#include <openxr/openxr_loader_negotiation.h>
#include "gamenative_openxr_unix.h"

#define XR_KHR_D3D11_enable_SPEC_VERSION 11
#define XR_KHR_D3D11_ENABLE_EXTENSION_NAME "XR_KHR_D3D11_enable"
#define XR_KHR_vulkan_enable_SPEC_VERSION 10
#define XR_KHR_VULKAN_ENABLE_EXTENSION_NAME "XR_KHR_vulkan_enable"
#define XR_KHR_vulkan_enable2_SPEC_VERSION 4
#define XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME "XR_KHR_vulkan_enable2"
#define XR_KHR_D3D12_enable_SPEC_VERSION 9
#define XR_KHR_D3D12_ENABLE_EXTENSION_NAME "XR_KHR_D3D12_enable"

typedef struct XrGraphicsRequirementsD3D11KHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    LUID adapterLuid;
    D3D_FEATURE_LEVEL minFeatureLevel;
} XrGraphicsRequirementsD3D11KHR;

typedef struct XrGraphicsRequirementsVulkanKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    XrVersion minApiVersionSupported;
    XrVersion maxApiVersionSupported;
} XrGraphicsRequirementsVulkanKHR;

typedef struct XrVulkanGraphicsDeviceGetInfoKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    XrSystemId systemId;
    VkInstance vulkanInstance;
} XrVulkanGraphicsDeviceGetInfoKHR;

typedef struct XrGraphicsBindingVulkanKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    unsigned int queueFamilyIndex;
    unsigned int queueIndex;
} XrGraphicsBindingVulkanKHR;

typedef struct XrSwapchainImageVulkanKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    VkImage image;
} XrSwapchainImageVulkanKHR;

typedef struct XrGraphicsBindingD3D11KHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    ID3D11Device* device;
} XrGraphicsBindingD3D11KHR;

typedef struct XrGraphicsBindingD3D12KHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    ID3D12Device* device;
    ID3D12CommandQueue* queue;
} XrGraphicsBindingD3D12KHR;

typedef struct XrGraphicsRequirementsD3D12KHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    LUID adapterLuid;
    D3D_FEATURE_LEVEL minFeatureLevel;
} XrGraphicsRequirementsD3D12KHR;

typedef struct XrSwapchainImageD3D11KHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    ID3D11Texture2D* texture;
} XrSwapchainImageD3D11KHR;

typedef struct XrSwapchainImageD3D12KHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    ID3D12Resource* texture;
} XrSwapchainImageD3D12KHR;

typedef struct GnGuid {
    unsigned long Data1;
    unsigned short Data2;
    unsigned short Data3;
    unsigned char Data4[8];
} GnGuid;

typedef struct GnDxgiSampleDesc {
    unsigned int Count;
    unsigned int Quality;
} GnDxgiSampleDesc;

typedef struct GnD3D11Texture2DDesc1 {
    unsigned int Width;
    unsigned int Height;
    unsigned int MipLevels;
    unsigned int ArraySize;
    int Format;
    GnDxgiSampleDesc SampleDesc;
    int Usage;
    unsigned int BindFlags;
    unsigned int CPUAccessFlags;
    unsigned int MiscFlags;
    int TextureLayout;
} GnD3D11Texture2DDesc1;

typedef struct GnD3D12ResourceDesc1 {
    int Dimension;
    unsigned long long Alignment;
    unsigned long long Width;
    unsigned int Height;
    unsigned short DepthOrArraySize;
    unsigned short MipLevels;
    int Format;
    GnDxgiSampleDesc SampleDesc;
    int Layout;
    unsigned int Flags;
    unsigned int SamplerFeedbackMipRegion[3];
} GnD3D12ResourceDesc1;

typedef void* (__attribute__((stdcall)) *PFN_vkGetInstanceProcAddr)(VkInstance, const char*);
typedef struct XrVulkanInstanceCreateInfoKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    XrSystemId systemId;
    XrFlags64 createFlags;
    PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
    const void* vulkanCreateInfo;
    const void* vulkanAllocator;
} XrVulkanInstanceCreateInfoKHR;

typedef struct XrVulkanDeviceCreateInfoKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    XrSystemId systemId;
    XrFlags64 createFlags;
    PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
    VkPhysicalDevice vulkanPhysicalDevice;
    const void* vulkanCreateInfo;
    const void* vulkanAllocator;
} XrVulkanDeviceCreateInfoKHR;

#ifndef NULL
#define NULL ((void*)0)
#endif

#define GN_EXPORT __declspec(dllexport)
#define GN_IMPORT __declspec(dllimport)
#define GN_STDCALL __attribute__((stdcall))

typedef unsigned int gn_uint32;
typedef unsigned long long gn_uint64;
typedef unsigned short gn_uint16;
typedef unsigned long gn_ulong;
#ifdef _WIN64
typedef unsigned long long gn_size;
typedef unsigned long long gn_socket;
#else
typedef unsigned int gn_size;
typedef unsigned int gn_socket;
#endif

#define GN_AF_INET 2
#define GN_SOCK_STREAM 1
#define GN_IPPROTO_TCP 6
#define GN_INVALID_SOCKET ((gn_socket)~0ULL)
#define GN_SOCKET_ERROR (-1)


void* memcpy(void* destination, const void* source, gn_size size) {
    unsigned char* dst = (unsigned char*)destination;
    const unsigned char* src = (const unsigned char*)source;
    for (gn_size i = 0; i < size; ++i) dst[i] = src[i];
    return destination;
}

void* memset(void* destination, int value, gn_size size) {
    unsigned char* dst = (unsigned char*)destination;
    for (gn_size i = 0; i < size; ++i) dst[i] = (unsigned char)value;
    return destination;
}

static void gn_zero_memory(void* destination, gn_size size) {
    unsigned char* dst = (unsigned char*)destination;
    for (gn_size i = 0; i < size; ++i) dst[i] = 0;
}

struct gn_in_addr {
    gn_ulong s_addr;
};

struct gn_sockaddr_in {
    short sin_family;
    gn_uint16 sin_port;
    struct gn_in_addr sin_addr;
    char sin_zero[8];
};

struct gn_wsadata {
    unsigned char bytes[512];
};

GN_IMPORT int GN_STDCALL WSAStartup(gn_uint16 version, struct gn_wsadata* data);
GN_IMPORT gn_socket GN_STDCALL socket(int af, int type, int protocol);
GN_IMPORT int GN_STDCALL connect(gn_socket s, const void* name, int namelen);
GN_IMPORT int GN_STDCALL send(gn_socket s, const char* buf, int len, int flags);
GN_IMPORT int GN_STDCALL recv(gn_socket s, char* buf, int len, int flags);
GN_IMPORT int GN_STDCALL closesocket(gn_socket s);
GN_IMPORT gn_uint16 GN_STDCALL htons(gn_uint16 hostshort);
GN_IMPORT gn_ulong GN_STDCALL inet_addr(const char* cp);

GN_IMPORT void* GN_STDCALL CreateFileA(const char* name, gn_ulong access, gn_ulong share, void* sec, gn_ulong disposition, gn_ulong flags, void* template_);
GN_IMPORT int GN_STDCALL WriteFile(void* handle, const void* buf, gn_ulong len, gn_ulong* written, void* overlapped);
GN_IMPORT int GN_STDCALL CloseHandle(void* handle);
GN_IMPORT gn_ulong GN_STDCALL GetEnvironmentVariableA(const char* name, char* buffer, gn_ulong size);
GN_IMPORT void GN_STDCALL OutputDebugStringA(const char* text);
GN_IMPORT void* GN_STDCALL LoadLibraryA(const char* name);
GN_IMPORT void* GN_STDCALL GetProcAddress(void* module, const char* name);
GN_IMPORT unsigned long GN_STDCALL GetLastError(void);
GN_IMPORT long GN_STDCALL CreateDXGIFactory(const void* iid, void** factory);

typedef int gn_ntstatus;
typedef struct {
    gn_uint16 Length;
    gn_uint16 MaximumLength;
    gn_uint16* Buffer;
} GnUnicodeString;
GN_IMPORT gn_ntstatus GN_STDCALL NtQueryVirtualMemory(
    void* process, const void* address, int informationClass,
    void* buffer, gn_size length, gn_size* resultLength);
GN_IMPORT gn_ntstatus (GN_STDCALL *__wine_unix_call_dispatcher)(
    gn_uint64 handle, unsigned int code, void* args);





static gn_size gn_strlen(const char* s) {
    gn_size n = 0;
    if (!s) return 0;
    while (s[n]) ++n;
    return n;
}

static int gn_streq(const char* a, const char* b) {
    gn_size i = 0;
    if (!a || !b) return 0;
    while (a[i] && b[i] && a[i] == b[i]) ++i;
    return a[i] == b[i];
}

static int gn_starts_with(const char* a, const char* prefix) {
    gn_size i = 0;
    if (!a || !prefix) return 0;
    while (prefix[i]) {
        if (a[i] != prefix[i]) return 0;
        ++i;
    }
    return 1;
}

static int gn_contains(const char* haystack, const char* needle) {
    gn_size i = 0;
    if (!haystack || !needle || !needle[0]) return 0;
    while (haystack[i]) {
        gn_size j = 0;
        while (needle[j] && haystack[i + j] == needle[j]) ++j;
        if (!needle[j]) return 1;
        ++i;
    }
    return 0;
}

static void gn_copy(char* dst, gn_size dst_size, const char* src) {
    gn_size i = 0;
    if (!dst || dst_size == 0) return;
    if (!src) src = "";
    while (i + 1 < dst_size && src[i]) {
        dst[i] = src[i];
        ++i;
    }
    dst[i] = 0;
}

static void gn_u64_to_dec(gn_uint64 value, char* out) {
    char tmp[24];
    int i = 0;
    if (value == 0) {
        out[0] = '0';
        out[1] = 0;
        return;
    }
    while (value > 0 && i < 23) {
        tmp[i++] = (char)('0' + (value % 10));
        value /= 10;
    }
    int j = 0;
    while (i > 0) out[j++] = tmp[--i];
    out[j] = 0;
}

static void gn_i64_to_dec(long long value, char* out) {
    if (value < 0) {
        out[0] = '-';
        gn_u64_to_dec((gn_uint64)(-value), out + 1);
    } else {
        gn_u64_to_dec((gn_uint64)value, out);
    }
}

static gn_size gn_append(char* dst, gn_size dst_size, gn_size offset, const char* src) {
    if (!dst || dst_size == 0 || !src) return offset;
    while (offset + 1 < dst_size && *src) dst[offset++] = *src++;
    dst[offset] = 0;
    return offset;
}

static gn_size gn_append_i64(char* dst, gn_size dst_size, gn_size offset, long long value) {
    char num[24];
    gn_i64_to_dec(value, num);
    return gn_append(dst, dst_size, offset, num);
}



#define GN_PARSE_MISSING 0x7fffffffffffffffLL

static long long gn_parse_i64(const char* text, const char* key, long long fallback) {
    gn_size i = 0;
    gn_size key_len = gn_strlen(key);
    if (!text || !key) return fallback;
    while (text[i]) {
        if (i == 0 || text[i - 1] == ' ') {
            gn_size j = 0;
            while (j < key_len && text[i + j] == key[j]) ++j;
            if (j == key_len && text[i + j] == '=') {
                long long sign = 1;
                long long value = 0;
                i += j + 1;
                if (text[i] == '-') {
                    sign = -1;
                    ++i;
                }
                while (text[i] >= '0' && text[i] <= '9') {
                    value = value * 10 + (text[i] - '0');
                    ++i;
                }
                return value * sign;
            }
        }
        ++i;
    }
    return fallback;
}


static float gn_parse_micro(const char* text, const char* key, float fallback) {
    long long raw = gn_parse_i64(text, key, GN_PARSE_MISSING);
    if (raw == GN_PARSE_MISSING) return fallback;
    return (float)raw * 1e-6f;
}

static long long gn_float_to_micro(float value) {
    float scaled = value * 1e6f;
    if (scaled >= 0.0f) return (long long)(scaled + 0.5f);
    return (long long)(scaled - 0.5f);
}





#define GN_GENERIC_APPEND 0x0004UL
#define GN_FILE_SHARE_RW 0x3UL
#define GN_OPEN_ALWAYS 4UL
#define GN_FILE_ATTRIBUTE_NORMAL 0x80UL
#define GN_INVALID_HANDLE ((void*)(gn_size)~0ULL)

static void* gn_log_handle = NULL;
static int gn_log_state = 0;

static void gn_log_line(const char* line) {
    char buf[400];
    gn_size n;

    if (gn_log_state == 0) {
        char env[8];
        gn_ulong got = GetEnvironmentVariableA("GAMENATIVE_XR_LOG", env, sizeof(env));
        gn_log_state = (got > 0 && env[0] != '0') ? 1 : -1;
        if (gn_log_state == 1) {
            gn_log_handle = CreateFileA(
                "C:\\gamenative-xr\\runtime.log",
                GN_GENERIC_APPEND,
                GN_FILE_SHARE_RW,
                NULL,
                GN_OPEN_ALWAYS,
                GN_FILE_ATTRIBUTE_NORMAL,
                NULL);
            if (gn_log_handle == GN_INVALID_HANDLE) gn_log_handle = NULL;
        }
    }

    n = gn_append(buf, sizeof(buf), 0, "gamenative-xr: ");
    n = gn_append(buf, sizeof(buf), n, line);
    OutputDebugStringA(buf);
    if (gn_log_handle != NULL) {
        gn_ulong written = 0;
        n = gn_append(buf, sizeof(buf), n, "\r\n");
        WriteFile(gn_log_handle, buf, (gn_ulong)n, &written, NULL);
    }
}

static void gn_log2(const char* a, const char* b) {
    char buf[360];
    gn_size n = gn_append(buf, sizeof(buf), 0, a);
    n = gn_append(buf, sizeof(buf), n, b ? b : "(null)");
    gn_log_line(buf);
}

static void gn_log_num(const char* a, long long value) {
    char buf[360];
    gn_size n = gn_append(buf, sizeof(buf), 0, a);
    n = gn_append_i64(buf, sizeof(buf), n, value);
    gn_log_line(buf);
}





#define GN_MAX_PATHS 512
#define GN_PATH_LEN 160
#define GN_MAX_ACTION_SETS 32
#define GN_MAX_ACTIONS 256
#define GN_MAX_REF_SPACES 32
#define GN_MAX_ACTION_SPACES 64

#ifdef _WIN64
#define GN_ACTIONSET_BASE 0x4753455400000ULL
#define GN_ACTION_BASE 0x4741435400000ULL
#define GN_REFSPACE_BASE 0x4752455300000ULL
#define GN_ACTSPACE_BASE 0x4741535000000ULL
#define GN_SWAPCHAIN_BASE 0x4753575000000ULL
#else


#define GN_ACTIONSET_BASE 0x47500000ULL
#define GN_ACTION_BASE 0x47400000ULL
#define GN_REFSPACE_BASE 0x47600000ULL
#define GN_ACTSPACE_BASE 0x47700000ULL
#define GN_SWAPCHAIN_BASE 0x47800000ULL
#endif
#define GN_HANDLE_IDX_MASK 0xFFFFULL
#define GN_MAX_SWAPCHAINS 32


enum {
    GN_COMP_NONE = 0,
    GN_COMP_PRIMARY,
    GN_COMP_SECONDARY,
    GN_COMP_STICK_CLICK,
    GN_COMP_MENU,
    GN_COMP_TRIGGER,
    GN_COMP_SQUEEZE,
    GN_COMP_STICK,
    GN_COMP_STICK_X,
    GN_COMP_STICK_Y,
    GN_COMP_GRIP_POSE,
    GN_COMP_AIM_POSE,
    GN_COMP_HAPTIC,
    GN_COMP_COUNT
};

typedef struct {
    int used;
    int attached;
    char name[64];
} GnActionSet;

typedef struct {
    int used;
    int action_set_idx;
    XrActionType type;
    unsigned char component[2];
    unsigned char binding_priority[2];
    unsigned char active_hands;
    char name[64];
} GnAction;

typedef struct {
    int used;
    XrReferenceSpaceType type;
    XrPosef pose_in_reference_space;
} GnRefSpace;

typedef struct {
    int used;
    int action_idx;
    int hand;
    XrPosef pose_in_action_space;
} GnActionSpace;

typedef struct {
    int active;
    unsigned buttons;
    float trigger, squeeze, sx, sy;
    float grip[7];
    float aim[7];
} GnHandState;

typedef struct {
    float quat[4];
    float pos[3];
    float fov[4];
} GnEyeView;

enum {
    GN_IMAGE_AVAILABLE = 0,
    GN_IMAGE_ACQUIRED,
    GN_IMAGE_WAITED
};

typedef struct {
    int used;
    gn_uint32 image_count;
    gn_uint32 next_image;
    gn_uint32 acquire_queue[GN_UNIX_MAX_IMAGES];
    gn_uint32 acquire_head;
    gn_uint32 acquire_count;
    gn_uint32 last_released_image;
    int last_released_valid;
    unsigned char image_state[GN_UNIX_MAX_IMAGES];
    unsigned char submitted[GN_UNIX_MAX_IMAGES];
    gn_uint64 images[GN_UNIX_MAX_IMAGES];
    void* d3d_images[GN_UNIX_MAX_IMAGES];
    XrSwapchainCreateInfo create_info;
} GnSwapchain;

static XrInstance gn_instance = (XrInstance)0x474e5852494e5354ULL;
static XrSession gn_session = (XrSession)0x474e585253455353ULL;
static XrSystemId gn_system_id = 1;
static XrTime gn_next_display_time = 1;
static int gn_session_running = 0;
static int gn_stopping_pushed = 0;
static int gn_winsock_started = 0;
static gn_socket gn_bridge_socket = GN_INVALID_SOCKET;
static int gn_bridge_ever_connected = 0;
static char gn_bridge_rxbuf[1024];
static gn_size gn_bridge_rxlen = 0;
static gn_size gn_bridge_rxoff = 0;

static int gn_bridge_recv_byte(char* value) {
    if (gn_bridge_rxoff >= gn_bridge_rxlen) {
        int got = recv(gn_bridge_socket, gn_bridge_rxbuf, (int)sizeof(gn_bridge_rxbuf), 0);
        if (got <= 0) return 0;
        gn_bridge_rxlen = (gn_size)got;
        gn_bridge_rxoff = 0;
    }
    *value = gn_bridge_rxbuf[gn_bridge_rxoff++];
    return 1;
}
static int gn_frame_milestone_sent = 0;
static XrSessionState gn_event_states[16];
static gn_uint32 gn_event_head = 0;
static gn_uint32 gn_event_tail = 0;

static char gn_paths[GN_MAX_PATHS][GN_PATH_LEN];
static gn_uint32 gn_path_count = 0;

static GnActionSet gn_action_sets[GN_MAX_ACTION_SETS];
static GnAction gn_actions[GN_MAX_ACTIONS];
static gn_uint32 gn_action_count = 0;
static gn_uint32 gn_action_set_count = 0;
static GnRefSpace gn_ref_spaces[GN_MAX_REF_SPACES];
static gn_uint32 gn_ref_space_count = 0;
static GnActionSpace gn_action_spaces[GN_MAX_ACTION_SPACES];
static gn_uint32 gn_action_space_count = 0;

static GnHandState gn_hands[2];
static float gn_comp_prev[2][GN_COMP_COUNT];
static XrTime gn_comp_change_time[2][GN_COMP_COUNT];
static unsigned char gn_comp_changed[2][GN_COMP_COUNT];
static XrTime gn_last_sync_time = 0;

static GnEyeView gn_eye_views[2];
static int gn_eye_views_valid = 0;
static XrPosef gn_local_origin;
static int gn_local_origin_valid = 0;
static long long gn_last_recenter_serial = -1;
static int gn_recenter_serial_supported = 0;
static XrPosef gn_last_head_pose;
static XrTime gn_last_head_time = 0;
static float gn_head_linear_velocity[3];
static float gn_head_angular_velocity[3];
static XrPosef gn_last_hand_pose[2];
static XrTime gn_last_hand_time[2];
static float gn_hand_linear_velocity[2][3];
static float gn_hand_angular_velocity[2][3];

static gn_uint32 gn_view_width = 1440;
static gn_uint32 gn_view_height = 1584;
static int gn_stage_supported = 1;

static XrPath gn_path_hand_left = 0;
static XrPath gn_path_hand_right = 0;


enum { GN_GFX_UNKNOWN = 0, GN_GFX_D3D11, GN_GFX_D3D12, GN_GFX_VULKAN };
static int gn_gfx_api = GN_GFX_UNKNOWN;
static int gn_action_sets_attached = 0;
static int gn_exit_requested = 0;
static gn_uint32 gn_events_lost = 0;
static int gn_interaction_profile_event_pending = 0;
static int gn_reference_space_event_pending = 0;
static int gn_instance_loss_event_pending = 0;
static GnSwapchain gn_swapchains[GN_MAX_SWAPCHAINS];
static gn_uint32 gn_swapchain_count = 0;
static gn_uint32 gn_transport_eye_mask = 0;
static int gn_transport_failure_logged = 0;
static gn_uint64 gn_unix_handle = 0;
static int gn_unix_load_attempted = 0;
typedef gn_ntstatus (GN_STDCALL *GnWineUnixCall)(gn_uint32 code, void* args);
static GnWineUnixCall gn_wine_unix_call = NULL;
static VkInstance gn_vk_instance = NULL;
static VkPhysicalDevice gn_vk_physical_device = NULL;
static VkDevice gn_vk_device = NULL;
static VkQueue gn_vk_queue = NULL;
static void* gn_dxvk_interop = NULL;
static void* gn_vkd3d_interop = NULL;
static void* gn_vkd3d_device_ext = NULL;
static ID3D12CommandQueue* gn_d3d12_queue = NULL;

static int gn_bridge_call(const char* command, char* response, gn_size response_size);
static void gn_identity_pose(XrPosef* pose);
static void gn_level_pose(XrPosef* pose);
static XrQuaternionf gn_quat_multiply(XrQuaternionf a, XrQuaternionf b);

static const char* GN_VULKAN_INSTANCE_EXTENSIONS = "";



static const char* GN_VULKAN_DEVICE_EXTENSIONS = "";



static volatile int gn_lock = 0;

static void gn_lock_acquire(void) {
    while (__atomic_exchange_n(&gn_lock, 1, __ATOMIC_ACQUIRE) != 0) {
#if defined(__x86_64__) || defined(__i386__)
        __builtin_ia32_pause();
#endif
    }
}

static void gn_lock_release(void) {
    __atomic_store_n(&gn_lock, 0, __ATOMIC_RELEASE);
}

static int gn_load_unixlib(void) {
    if (gn_unix_handle) return 1;
    if (gn_unix_load_attempted) return 0;
    gn_unix_load_attempted = 1;




    {
        static gn_uint16 unixlib_name[] = {
            'g','a','m','e','n','a','t','i','v','e','_','x','r','_',
            'u','n','i','x','b','r','i','d','g','e','.','d','l','l',0
        };
        GnUnicodeString name;
        gn_uint64 result[2] = {0, 0};
        gn_size result_length = 0;
        name.Length = (gn_uint16)(sizeof(unixlib_name) - sizeof(gn_uint16));
        name.MaximumLength = (gn_uint16)sizeof(unixlib_name);
        name.Buffer = unixlib_name;


        gn_ntstatus status = NtQueryVirtualMemory(
            (void*)(gn_size)-1,
            &name,
            1002,
            result,
            sizeof(result),
            &result_length);
        if (status == 0 && result[1]) {
            gn_unix_handle = result[1];
            gn_log_line("Wine unixlib loaded directly by name");
        } else {
            gn_log_num("Wine direct unixlib load unavailable, status=", status);
        }
    }

    if (!gn_unix_handle) {


        void* module = LoadLibraryA("gamenative_xr_unixbridge.dll");
        if (!module) {
            gn_log_num("Wine unix bridge module load failed, error=", GetLastError());
            return 0;
        }
        gn_wine_unix_call = (GnWineUnixCall)GetProcAddress(module, "gnWineUnixCall");
        if (!gn_wine_unix_call) {
            gn_log_line("Wine unix bridge entry point missing");
            return 0;
        }
        gn_unix_handle = (gn_uint64)(gn_size)module;
    }

    {
        struct gn_unix_init_args args;
        args.abi_version = GN_UNIX_ABI_VERSION;
        args.result = GN_UNIX_ERROR_UNAVAILABLE;
        gn_ntstatus status = gn_wine_unix_call ?
            gn_wine_unix_call(GN_UNIX_INIT, &args) :
            __wine_unix_call_dispatcher(gn_unix_handle, GN_UNIX_INIT, &args);
        if (status != 0 || args.result != GN_UNIX_SUCCESS) {
            gn_log_num("Wine unixlib init failed status=", status);
            gn_log_num("Wine unixlib init result=", args.result);
            gn_unix_handle = 0;
            gn_wine_unix_call = NULL;
            return 0;
        }
    }
    gn_log_line("Wine unixlib ready");
    return 1;
}

static int gn_unix_call(unsigned int code, void* args) {
    if (!gn_load_unixlib()) return 0;
    gn_ntstatus status = gn_wine_unix_call ?
        gn_wine_unix_call(code, args) :
        __wine_unix_call_dispatcher(gn_unix_handle, code, args);
    if (status != 0) {
        gn_log_num("Wine unix call failed status=", status);
        return 0;
    }
    return 1;
}

static int gn_swapchain_index(XrSwapchain swapchain) {
    gn_uint64 handle = (gn_uint64)swapchain;
    if ((handle & ~GN_HANDLE_IDX_MASK) != GN_SWAPCHAIN_BASE) return -1;
    gn_uint64 index = handle & GN_HANDLE_IDX_MASK;
    if (index >= gn_swapchain_count || !gn_swapchains[index].used) return -1;
    return (int)index;
}

typedef VkResult (GN_STDCALL *GnVkEnumeratePhysicalDevices)(
    VkInstance instance, gn_uint32* count, VkPhysicalDevice* devices);
typedef void (GN_STDCALL *GnVkGetDeviceQueue)(
    VkDevice device, gn_uint32 family, gn_uint32 index, VkQueue* queue);

static void* gn_vulkan_proc(const char* name) {
    static void* module = NULL;
    if (!module) module = LoadLibraryA("vulkan-1.dll");
    return module ? GetProcAddress(module, name) : NULL;
}

static VkPhysicalDevice gn_first_vulkan_physical_device(VkInstance instance) {
    GnVkEnumeratePhysicalDevices enumerate =
        (GnVkEnumeratePhysicalDevices)gn_vulkan_proc("vkEnumeratePhysicalDevices");
    VkPhysicalDevice devices[16];
    gn_uint32 count = 16;
    if (!enumerate || enumerate(instance, &count, devices) != 0 || count == 0) return NULL;
    return devices[0];
}

static int gn_set_vulkan_context(const XrGraphicsBindingVulkanKHR* binding) {
    GnVkGetDeviceQueue get_queue =
        (GnVkGetDeviceQueue)gn_vulkan_proc("vkGetDeviceQueue");
    if (!binding || !get_queue) return 0;
    gn_vk_physical_device = binding->physicalDevice;
    gn_vk_device = binding->device;
    get_queue(binding->device, binding->queueFamilyIndex, binding->queueIndex, &gn_vk_queue);
    if (!gn_vk_physical_device || !gn_vk_device || !gn_vk_queue) return 0;

    struct gn_unix_vulkan_context_args args;
    args.client_physical_device = (gn_u64)gn_vk_physical_device;
    args.client_device = (gn_u64)gn_vk_device;
    args.client_queue = (gn_u64)gn_vk_queue;
    args.queue_family_index = binding->queueFamilyIndex;
    args.queue_index = binding->queueIndex;
    args.handles_are_host = 0;
    args.diagnostic_flags = 0;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_SET_VULKAN_CONTEXT, &args) ||
        args.result != GN_UNIX_SUCCESS) {
        gn_log_num("Wine Vulkan context result=", args.result);
        gn_log_num("Wine Vulkan context flags=", args.diagnostic_flags);
        return 0;
    }
    return 1;
}

typedef long (GN_STDCALL *GnComQueryInterface)(void*, const GnGuid*, void**);
typedef unsigned long (GN_STDCALL *GnComRelease)(void*);
typedef void (GN_STDCALL *GnDxvkGetVulkanHandles)(
    void*, VkInstance*, VkPhysicalDevice*, VkDevice*);
typedef void (GN_STDCALL *GnDxvkGetSubmissionQueue1)(
    void*, VkQueue*, gn_uint32*, gn_uint32*);
typedef void (GN_STDCALL *GnDxvkVoidCall)(void*);
typedef long (GN_STDCALL *GnDxvkCreateTexture)(
    void*, const GnD3D11Texture2DDesc1*, VkImage, ID3D11Texture2D**);

static void* gn_com_method(void* object, int index) {
    if (!object) return NULL;
    return (*(void***)object)[index];
}

static void gn_com_release(void* object) {
    GnComRelease release = (GnComRelease)gn_com_method(object, 2);
    if (release) release(object);
}

typedef struct GnDxgiAdapterDesc {
    gn_uint16 Description[128];
    gn_uint32 VendorId;
    gn_uint32 DeviceId;
    gn_uint32 SubSysId;
    gn_uint32 Revision;
    gn_size DedicatedVideoMemory;
    gn_size DedicatedSystemMemory;
    gn_size SharedSystemMemory;
    LUID AdapterLuid;
} GnDxgiAdapterDesc;

typedef long (GN_STDCALL *GnDxgiEnumAdapters)(void*, gn_uint32, void**);
typedef long (GN_STDCALL *GnDxgiGetDesc)(void*, GnDxgiAdapterDesc*);

static LUID gn_primary_adapter_luid(void) {
    static int queried = 0;
    static LUID cached = {1, 0};
    if (queried) return cached;
    queried = 1;
    static const GnGuid iid_factory = {
        0x7b7166ec, 0x21c7, 0x44ae,
        {0xb2,0x1a,0xc9,0xae,0x32,0x1a,0xe3,0x69}
    };
    void* factory = NULL;
    void* adapter = NULL;
    if (CreateDXGIFactory(&iid_factory, &factory) >= 0 && factory) {
        GnDxgiEnumAdapters enumerate =
            (GnDxgiEnumAdapters)gn_com_method(factory, 7);
        if (enumerate && enumerate(factory, 0, &adapter) >= 0 && adapter) {
            GnDxgiAdapterDesc desc;
            GnDxgiGetDesc get_desc =
                (GnDxgiGetDesc)gn_com_method(adapter, 8);
            if (get_desc && get_desc(adapter, &desc) >= 0) {
                cached = desc.AdapterLuid;
                if (!cached.LowPart && !cached.HighPart) cached.LowPart = 1;
            }
            gn_com_release(adapter);
        }
        gn_com_release(factory);
    }
    return cached;
}

static int gn_set_d3d11_context(ID3D11Device* device11) {
    static const GnGuid iid_interop1 = {
        0xe2ef5fa5, 0xdc21, 0x4af7,
        {0x90,0xc4,0xf6,0x7e,0xf6,0xa0,0x93,0x24}
    };
    GnComQueryInterface query = (GnComQueryInterface)gn_com_method(device11, 0);
    if (!query || query(device11, &iid_interop1, &gn_dxvk_interop) < 0 ||
        !gn_dxvk_interop) {
        gn_log_line("DXVK IDXGIVkInteropDevice1 unavailable");
        return 0;
    }

    VkInstance instance = NULL;
    gn_uint32 queue_family = 0;
    gn_uint32 queue_index = 0;
    GnDxvkGetVulkanHandles get_handles =
        (GnDxvkGetVulkanHandles)gn_com_method(gn_dxvk_interop, 3);
    GnDxvkGetSubmissionQueue1 get_queue =
        (GnDxvkGetSubmissionQueue1)gn_com_method(gn_dxvk_interop, 9);
    if (!get_handles || !get_queue) return 0;
    get_handles(gn_dxvk_interop, &instance, &gn_vk_physical_device, &gn_vk_device);
    get_queue(gn_dxvk_interop, &gn_vk_queue, &queue_index, &queue_family);
    if (!gn_vk_physical_device || !gn_vk_device || !gn_vk_queue) return 0;

    struct gn_unix_vulkan_context_args args;
    args.client_physical_device = (gn_u64)gn_vk_physical_device;
    args.client_device = (gn_u64)gn_vk_device;
    args.client_queue = (gn_u64)gn_vk_queue;
    args.queue_family_index = queue_family;
    args.queue_index = queue_index;



    args.handles_are_host = 0;
    args.diagnostic_flags = 0;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_SET_VULKAN_CONTEXT, &args) ||
        args.result != GN_UNIX_SUCCESS) {
        gn_log_num("Wine DXVK Vulkan context result=", args.result);
        gn_log_num("Wine DXVK Vulkan context flags=", args.diagnostic_flags);
        return 0;
    }
    return 1;
}

static void gn_dxvk_flush_and_lock(void) {
    GnDxvkVoidCall flush =
        (GnDxvkVoidCall)gn_com_method(gn_dxvk_interop, 6);
    GnDxvkVoidCall lock =
        (GnDxvkVoidCall)gn_com_method(gn_dxvk_interop, 7);
    if (flush) flush(gn_dxvk_interop);
    if (lock) lock(gn_dxvk_interop);
}

static void gn_dxvk_unlock(void) {
    GnDxvkVoidCall unlock =
        (GnDxvkVoidCall)gn_com_method(gn_dxvk_interop, 8);
    if (unlock) unlock(gn_dxvk_interop);
}

typedef long (GN_STDCALL *GnVkd3dGetVulkanHandles)(
    void*, VkInstance*, VkPhysicalDevice*, VkDevice*);
typedef long (GN_STDCALL *GnVkd3dGetQueueInfoEx)(
    void*, ID3D12CommandQueue*, VkQueue*, gn_uint32*, gn_uint32*, gn_uint32*);
typedef long (GN_STDCALL *GnVkd3dQueueCall)(
    void*, ID3D12CommandQueue*);
typedef long (GN_STDCALL *GnVkd3dCreateResource)(
    void*, const GnD3D12ResourceDesc1*, gn_uint64, ID3D12Resource**);

static int gn_set_d3d12_context(
    ID3D12Device* device12, ID3D12CommandQueue* queue12) {
    static const GnGuid iid_interop = {
        0x39da4e09, 0xbd1c, 0x4198,
        {0x9f,0xae,0x86,0xbb,0xe3,0xbe,0x41,0xfd}
    };
    static const GnGuid iid_device_ext1 = {
        0x099a73fd, 0x2199, 0x4f45,
        {0xbf,0x48,0x0e,0xb8,0x6f,0x6f,0xdb,0x65}
    };
    GnComQueryInterface query =
        (GnComQueryInterface)gn_com_method(device12, 0);
    if (!query ||
        query(device12, &iid_interop, &gn_vkd3d_interop) < 0 ||
        query(device12, &iid_device_ext1, &gn_vkd3d_device_ext) < 0 ||
        !gn_vkd3d_interop || !gn_vkd3d_device_ext) {
        gn_log_line("vkd3d-proton native interop unavailable");
        return 0;
    }

    VkInstance instance = NULL;
    gn_uint32 queue_index = 0;
    gn_uint32 queue_flags = 0;
    gn_uint32 queue_family = 0;
    GnVkd3dGetVulkanHandles get_handles =
        (GnVkd3dGetVulkanHandles)gn_com_method(gn_vkd3d_interop, 7);
    GnVkd3dGetQueueInfoEx get_queue =
        (GnVkd3dGetQueueInfoEx)gn_com_method(gn_vkd3d_device_ext, 11);
    if (!get_handles || !get_queue ||
        get_handles(gn_vkd3d_interop, &instance,
                    &gn_vk_physical_device, &gn_vk_device) < 0 ||
        get_queue(gn_vkd3d_device_ext, queue12, &gn_vk_queue,
                  &queue_index, &queue_flags, &queue_family) < 0) {
        return 0;
    }
    gn_d3d12_queue = queue12;
    struct gn_unix_vulkan_context_args args;
    args.client_physical_device = (gn_u64)gn_vk_physical_device;
    args.client_device = (gn_u64)gn_vk_device;
    args.client_queue = (gn_u64)gn_vk_queue;
    args.queue_family_index = queue_family;
    args.queue_index = queue_index;

    args.handles_are_host = 0;
    args.diagnostic_flags = 0;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_SET_VULKAN_CONTEXT, &args) ||
        args.result != GN_UNIX_SUCCESS) {
        gn_log_num("Wine vkd3d Vulkan context result=", args.result);
        gn_log_num("Wine vkd3d Vulkan context flags=", args.diagnostic_flags);
        return 0;
    }
    return 1;
}

static void gn_vkd3d_lock(void) {
    GnVkd3dQueueCall lock =
        (GnVkd3dQueueCall)gn_com_method(gn_vkd3d_interop, 11);
    if (lock) lock(gn_vkd3d_interop, gn_d3d12_queue);
}

static void gn_vkd3d_unlock(void) {
    GnVkd3dQueueCall unlock =
        (GnVkd3dQueueCall)gn_com_method(gn_vkd3d_interop, 12);
    if (unlock) unlock(gn_vkd3d_interop, gn_d3d12_queue);
}

static int64_t gn_d3d_format_to_vk(int64_t format) {
    switch (format) {
        case 29: return 43;
        case 28: return 37;
        case 91: return 50;
        case 87: return 44;
        default: return 0;
    }
}





static void gn_push_session_event(XrSessionState state) {
    gn_uint32 next_tail = (gn_event_tail + 1) % 16;
    if (next_tail == gn_event_head) {
        gn_event_head = (gn_event_head + 1) % 16;
        ++gn_events_lost;
    }
    gn_event_states[gn_event_tail] = state;
    gn_event_tail = next_tail;
}

static int gn_pop_session_event(XrSessionState* state) {
    if (gn_event_head == gn_event_tail || state == NULL) return 0;
    *state = gn_event_states[gn_event_head];
    gn_event_head = (gn_event_head + 1) % 16;
    return 1;
}

static void gn_clear_events(void) {
    gn_event_head = 0;
    gn_event_tail = 0;
    gn_events_lost = 0;
    gn_interaction_profile_event_pending = 0;
    gn_reference_space_event_pending = 0;
    gn_instance_loss_event_pending = 0;
}





static XrResult gn_fill_string(const char* src, gn_uint32 capacity, gn_uint32* count, char* dst) {
    gn_uint32 needed = (gn_uint32)gn_strlen(src) + 1;
    if (count) *count = needed;
    if (capacity == 0) return XR_SUCCESS;
    if (!dst || capacity < needed) return XR_ERROR_SIZE_INSUFFICIENT;
    gn_copy(dst, capacity, src);
    return XR_SUCCESS;
}

static XrResult gn_copy_props(gn_uint32 available, gn_uint32 capacity, gn_uint32* count) {
    if (count) *count = available;
    if (capacity != 0 && capacity < available) return XR_ERROR_SIZE_INSUFFICIENT;
    return XR_SUCCESS;
}

static void gn_write_extension(XrExtensionProperties* property, const char* name, gn_uint32 version) {
    property->type = XR_TYPE_EXTENSION_PROPERTIES;
    property->next = NULL;
    gn_copy(property->extensionName, XR_MAX_EXTENSION_NAME_SIZE, name);
    property->extensionVersion = version;
}





static void gn_bridge_close(void) {
    if (gn_bridge_socket != GN_INVALID_SOCKET) {
        closesocket(gn_bridge_socket);
        gn_bridge_socket = GN_INVALID_SOCKET;
    }
}

static int gn_bridge_connect_locked(void) {
    struct gn_wsadata wsa;
    struct gn_sockaddr_in addr;

    if (gn_bridge_socket != GN_INVALID_SOCKET) return 1;
    if (!gn_winsock_started) {
        if (WSAStartup(0x0202, &wsa) != 0) {
            gn_log_line("WSAStartup failed");
            return 0;
        }
        gn_winsock_started = 1;
    }

    gn_bridge_socket = socket(GN_AF_INET, GN_SOCK_STREAM, GN_IPPROTO_TCP);
    if (gn_bridge_socket == GN_INVALID_SOCKET) {
        gn_log_line("socket() failed");
        return 0;
    }

    addr.sin_family = GN_AF_INET;
    addr.sin_port = htons(38476);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    for (gn_size i = 0; i < sizeof(addr.sin_zero); ++i) addr.sin_zero[i] = 0;

    if (connect(gn_bridge_socket, &addr, sizeof(addr)) == GN_SOCKET_ERROR) {
        gn_bridge_close();
        gn_log_line("connect to 127.0.0.1:38476 failed (is XrBridgeServer running?)");
        return 0;
    }
    return 1;
}


static int gn_bridge_call_locked(const char* command, char* response, gn_size response_size) {
    char local_response[192];
    char* out = response;
    gn_size out_size = response_size;
    char ch = 0;
    gn_size offset = 0;
    gn_size len = gn_strlen(command);
    int got = 0;

    if (out == NULL || out_size == 0) {
        out = local_response;
        out_size = sizeof(local_response);
    }
    out[0] = 0;
    if (gn_bridge_socket == GN_INVALID_SOCKET) {
        if (!gn_bridge_connect_locked()) return 0;
        gn_bridge_rxlen = 0;
        gn_bridge_rxoff = 0;

        {
            char hello[64];
            const char* h = "HELLO\n";
            if (send(gn_bridge_socket, h, 6, 0) == GN_SOCKET_ERROR) {
                gn_bridge_close();
                return 0;
            }
            gn_size hoff = 0;
            while (hoff + 1 < sizeof(hello)) {
                if (!gn_bridge_recv_byte(&ch)) {
                    gn_bridge_close();
                    return 0;
                }
                if (ch == '\n') break;
                hello[hoff++] = ch;
            }
            hello[hoff] = 0;
            if (!gn_starts_with(hello, "OK")) {
                gn_bridge_close();
                gn_log2("bridge HELLO rejected: ", hello);
                return 0;
            }
            if (!gn_bridge_ever_connected) {
                gn_bridge_ever_connected = 1;
                gn_log2("bridge connected: ", hello);
            }
        }
        if (gn_streq(command, "HELLO")) {
            gn_copy(out, out_size, "OK GameNativeVR");
            return 1;
        }
    }
    if (send(gn_bridge_socket, command, (int)len, 0) == GN_SOCKET_ERROR ||
        send(gn_bridge_socket, "\n", 1, 0) == GN_SOCKET_ERROR) {
        gn_bridge_close();
        gn_log2("bridge send failed for: ", command);
        return 0;
    }

    for (;;) {
        if (!gn_bridge_recv_byte(&ch)) {
            gn_bridge_close();
            gn_log2("bridge recv failed for: ", command);
            return 0;
        }
        if (ch == '\n') break;
        if (offset + 1 < out_size) out[offset++] = ch;
    }
    out[offset] = 0;
    return gn_starts_with(out, "OK");
}

static int gn_unix_control_state; /* 0 untried, 1 works, -1 unavailable */

static int gn_unix_control_transact(const char* command, gn_uint32 lines,
                                    char* response, gn_size response_size) {
    struct gn_unix_control_transact_args args;
    gn_size len = 0;
    if (gn_unix_control_state < 0) return 0;
    while (command[len] && len + 1 < sizeof(args.request)) {
        args.request[len] = command[len];
        ++len;
    }
    if (command[len]) return 0;
    args.request[len] = 0;
    args.response[0] = 0;
    args.response_lines = lines;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_CONTROL_TRANSACT, &args) ||
        args.result != GN_UNIX_SUCCESS) {
        /* Transient failures (the server may briefly refuse connections while stale
         * clients age out) must not disable the fast path forever. */
        static int consecutive_failures;
        if (++consecutive_failures >= 300 && gn_unix_control_state <= 0) {
            gn_unix_control_state = -1;
            gn_log_line("unix control fast path unavailable; staying on winsock");
        }
        return 0;
    }
    if (gn_unix_control_state <= 0) {
        gn_unix_control_state = 1;
        gn_log_line("unix control fast path active");
    }
    gn_copy(response, response_size, args.response);
    return 1;
}

static int gn_bridge_read_line_locked(char* out, gn_size out_size) {
    gn_size offset = 0;
    char ch = 0;
    for (;;) {
        if (!gn_bridge_recv_byte(&ch)) {
            gn_bridge_close();
            return 0;
        }
        if (ch == '\n') break;
        if (offset + 1 < out_size) out[offset++] = ch;
    }
    out[offset] = 0;
    return gn_starts_with(out, "OK");
}


static int gn_bridge_call(const char* command, char* response, gn_size response_size) {
    int ok;
    char local[1024];
    gn_lock_acquire();
    if (gn_unix_control_state >= 0 &&
        gn_unix_control_transact(command, 1, local, sizeof(local))) {
        if (response && response_size) gn_copy(response, response_size, local);
        ok = gn_starts_with(local, "OK");
        gn_lock_release();
        return ok;
    }
    ok = gn_bridge_call_locked(command, response, response_size);
    gn_lock_release();
    return ok;
}

static char gn_cached_views[1024];
static char gn_cached_input[2][1024];
static int gn_cache_valid = 0;
static int gn_frame_sync_supported = 1;
static int gn_split_line(const char** cursor, char* out, gn_size out_size) {
    const char* p = *cursor;
    gn_size n = 0;
    if (!*p) return 0;
    while (*p && *p != '\n') {
        if (n + 1 < out_size) out[n++] = *p;
        ++p;
    }
    out[n] = 0;
    if (*p == '\n') ++p;
    *cursor = p;
    return 1;
}

static int gn_bridge_frame_sync(char* frame_out, gn_size frame_size) {
    int ok;
    gn_lock_acquire();
    gn_cache_valid = 0;
    if (gn_unix_control_state >= 0) {
        char bundle[2048];
        if (gn_unix_control_transact("FRAME_SYNC", 4, bundle, sizeof(bundle))) {
            const char* cursor = bundle;
            char first[192];
            if (gn_split_line(&cursor, first, sizeof(first)) &&
                gn_starts_with(first, "OK")) {
                gn_copy(frame_out, frame_size, first);
                gn_cache_valid =
                    gn_split_line(&cursor, gn_cached_views, sizeof(gn_cached_views)) &&
                    gn_split_line(&cursor, gn_cached_input[0], sizeof(gn_cached_input[0])) &&
                    gn_split_line(&cursor, gn_cached_input[1], sizeof(gn_cached_input[1]));
                gn_lock_release();
                return 1;
            }
            gn_copy(frame_out, frame_size, first);
            if (gn_starts_with(first, "ERROR")) {
                gn_frame_sync_supported = 0;
                gn_log_line("FRAME_SYNC unsupported by bridge; using separate per-frame requests");
            }
            gn_lock_release();
            return 0;
        }
    }
    ok = gn_bridge_call_locked("FRAME_SYNC", frame_out, frame_size);
    if (ok) {
        gn_cache_valid =
            gn_bridge_read_line_locked(gn_cached_views, sizeof(gn_cached_views)) &&
            gn_bridge_read_line_locked(gn_cached_input[0], sizeof(gn_cached_input[0])) &&
            gn_bridge_read_line_locked(gn_cached_input[1], sizeof(gn_cached_input[1]));
    } else if (gn_starts_with(frame_out, "ERROR")) {
        gn_frame_sync_supported = 0;
        gn_log_line("FRAME_SYNC unsupported by bridge; using separate per-frame requests");
    }
    gn_lock_release();
    return ok;
}

static int gn_cached_line(const char* which, int hand, char* out, gn_size out_size) {
    int ok = 0;
    gn_lock_acquire();
    if (gn_cache_valid) {
        if (which[0] == 'v') gn_copy(out, out_size, gn_cached_views);
        else gn_copy(out, out_size, gn_cached_input[hand]);
        ok = 1;
    }
    gn_lock_release();
    return ok;
}





static XrPath gn_path_get_or_create(const char* str) {
    gn_uint32 i;
    if (!str || !str[0]) return XR_NULL_PATH;
    for (i = 0; i < gn_path_count; ++i) {
        if (gn_streq(gn_paths[i], str)) return (XrPath)(i + 1);
    }
    if (gn_path_count >= GN_MAX_PATHS) return XR_NULL_PATH;
    gn_copy(gn_paths[gn_path_count], GN_PATH_LEN, str);
    ++gn_path_count;
    return (XrPath)gn_path_count;
}

static const char* gn_path_string(XrPath path) {
    gn_uint64 idx = (gn_uint64)path;
    if (idx == 0 || idx > gn_path_count) return NULL;
    return gn_paths[idx - 1];
}

static void gn_paths_init(void) {
    if (gn_path_hand_left == 0) {
        gn_path_hand_left = gn_path_get_or_create("/user/hand/left");
        gn_path_hand_right = gn_path_get_or_create("/user/hand/right");
    }
}


static int gn_hand_from_path(XrPath path) {
    const char* str;
    if (path == XR_NULL_PATH) return -1;
    if (path == gn_path_hand_left) return 0;
    if (path == gn_path_hand_right) return 1;
    str = gn_path_string(path);
    if (!str) return -1;
    if (gn_starts_with(str, "/user/hand/left")) return 0;
    if (gn_starts_with(str, "/user/hand/right")) return 1;
    return -1;
}


static int gn_component_from_binding(const char* str) {
    if (!str) return GN_COMP_NONE;
    if (gn_contains(str, "/output/haptic")) return GN_COMP_HAPTIC;
    if (gn_contains(str, "/input/grip")) return GN_COMP_GRIP_POSE;
    if (gn_contains(str, "/input/aim")) return GN_COMP_AIM_POSE;
    if (gn_contains(str, "/input/trigger")) return GN_COMP_TRIGGER;
    if (gn_contains(str, "/input/squeeze")) return GN_COMP_SQUEEZE;
    if (gn_contains(str, "/input/thumbstick/click")) return GN_COMP_STICK_CLICK;
    if (gn_contains(str, "/input/thumbstick/x")) return GN_COMP_STICK_X;
    if (gn_contains(str, "/input/thumbstick/y")) return GN_COMP_STICK_Y;
    if (gn_contains(str, "/input/thumbstick")) return GN_COMP_STICK;
    if (gn_contains(str, "/input/joystick")) return GN_COMP_STICK;
    if (gn_contains(str, "/input/a/")) return GN_COMP_PRIMARY;
    if (gn_contains(str, "/input/x/")) return GN_COMP_PRIMARY;
    if (gn_contains(str, "/input/b/")) return GN_COMP_SECONDARY;
    if (gn_contains(str, "/input/y/")) return GN_COMP_SECONDARY;
    if (gn_contains(str, "/input/menu")) return GN_COMP_MENU;
    if (gn_contains(str, "/input/system")) return GN_COMP_MENU;
    if (gn_contains(str, "/input/select")) return GN_COMP_TRIGGER;
    return GN_COMP_NONE;
}



static int gn_interaction_profile_priority(const char* profile) {
    if (!profile) return 0;
    if (gn_streq(profile, "/interaction_profiles/oculus/touch_controller")) return 100;
    if (gn_streq(profile, "/interaction_profiles/meta/touch_controller_plus")) return 100;
    if (gn_streq(profile, "/interaction_profiles/khr/simple_controller")) return 10;
    return 1;
}





static int gn_action_index(XrAction action) {
    gn_uint64 h = (gn_uint64)action;
    if ((h & ~GN_HANDLE_IDX_MASK) != GN_ACTION_BASE) return -1;
    gn_uint64 idx = h & GN_HANDLE_IDX_MASK;
    if (idx >= gn_action_count || !gn_actions[idx].used) return -1;
    return (int)idx;
}

static int gn_action_set_index(XrActionSet action_set) {
    gn_uint64 h = (gn_uint64)action_set;
    if ((h & ~GN_HANDLE_IDX_MASK) != GN_ACTIONSET_BASE) return -1;
    gn_uint64 idx = h & GN_HANDLE_IDX_MASK;
    if (idx >= gn_action_set_count || !gn_action_sets[idx].used) return -1;
    return (int)idx;
}

static int gn_ref_space_index(XrSpace space) {
    gn_uint64 h = (gn_uint64)space;
    if ((h & ~GN_HANDLE_IDX_MASK) != GN_REFSPACE_BASE) return -1;
    gn_uint64 idx = h & GN_HANDLE_IDX_MASK;
    if (idx >= gn_ref_space_count || !gn_ref_spaces[idx].used) return -1;
    return (int)idx;
}

static int gn_action_space_index(XrSpace space) {
    gn_uint64 h = (gn_uint64)space;
    if ((h & ~GN_HANDLE_IDX_MASK) != GN_ACTSPACE_BASE) return -1;
    gn_uint64 idx = h & GN_HANDLE_IDX_MASK;
    if (idx >= gn_action_space_count || !gn_action_spaces[idx].used) return -1;
    return (int)idx;
}


static int gn_action_default_hand(const GnAction* action) {
    if (action->component[0] != GN_COMP_NONE) return 0;
    if (action->component[1] != GN_COMP_NONE) return 1;
    return 0;
}





static float gn_comp_value(int hand, int comp) {
    const GnHandState* h = &gn_hands[hand];
    switch (comp) {
        case GN_COMP_PRIMARY: return (h->buttons & 1u) ? 1.0f : 0.0f;
        case GN_COMP_SECONDARY: return (h->buttons & 2u) ? 1.0f : 0.0f;
        case GN_COMP_STICK_CLICK: return (h->buttons & 4u) ? 1.0f : 0.0f;
        case GN_COMP_MENU: return (h->buttons & 8u) ? 1.0f : 0.0f;
        case GN_COMP_TRIGGER: return h->trigger;
        case GN_COMP_SQUEEZE: return h->squeeze;
        case GN_COMP_STICK_X: return h->sx;
        case GN_COMP_STICK_Y: return h->sy;
        case GN_COMP_STICK: return h->sx;
        default: return 0.0f;
    }
}

static void gn_refresh_hand(int hand) {
    char cmd[32];
    char response[512];
    gn_size n;
    GnHandState* h = &gn_hands[hand];

    if (!gn_cached_line("input", hand, response, sizeof(response))) {
        n = gn_append(cmd, sizeof(cmd), 0, "GET_INPUT hand=");
        n = gn_append_i64(cmd, sizeof(cmd), n, hand);
        if (!gn_bridge_call(cmd, response, sizeof(response))) {
            h->active = 0;
            return;
        }
    }

    h->active = (int)gn_parse_i64(response, "active", 0);
    h->buttons = (unsigned)gn_parse_i64(response, "buttons", 0);
    h->trigger = gn_parse_micro(response, "tr", 0.0f);
    h->squeeze = gn_parse_micro(response, "sq", 0.0f);
    h->sx = gn_parse_micro(response, "sx", 0.0f);
    h->sy = gn_parse_micro(response, "sy", 0.0f);

    h->grip[0] = gn_parse_micro(response, "gqx", 0.0f);
    h->grip[1] = gn_parse_micro(response, "gqy", 0.0f);
    h->grip[2] = gn_parse_micro(response, "gqz", 0.0f);
    h->grip[3] = gn_parse_micro(response, "gqw", 1.0f);
    h->grip[4] = gn_parse_micro(response, "gpx", 0.0f);
    h->grip[5] = gn_parse_micro(response, "gpy", 0.0f);
    h->grip[6] = gn_parse_micro(response, "gpz", 0.0f);

    h->aim[0] = gn_parse_micro(response, "aqx", 0.0f);
    h->aim[1] = gn_parse_micro(response, "aqy", 0.0f);
    h->aim[2] = gn_parse_micro(response, "aqz", 0.0f);
    h->aim[3] = gn_parse_micro(response, "aqw", 1.0f);
    h->aim[4] = gn_parse_micro(response, "apx", 0.0f);
    h->aim[5] = gn_parse_micro(response, "apy", 0.0f);
    h->aim[6] = gn_parse_micro(response, "apz", 0.0f);
}

static void gn_update_change_tracking(XrTime sync_time) {
    for (int hand = 0; hand < 2; ++hand) {
        for (int comp = 1; comp < GN_COMP_COUNT; ++comp) {
            float value = gn_comp_value(hand, comp);
            float prev = gn_comp_prev[hand][comp];
            float delta = value - prev;
            if (delta < 0.0f) delta = -delta;
            if (delta > 0.0001f) {
                gn_comp_changed[hand][comp] = 1;
                gn_comp_change_time[hand][comp] = sync_time;
            } else {
                gn_comp_changed[hand][comp] = 0;
            }
            gn_comp_prev[hand][comp] = value;
        }
    }
    gn_last_sync_time = sync_time;
}

static void gn_write_pose(XrPosef* pose, const float* data) {
    pose->orientation.x = data[0];
    pose->orientation.y = data[1];
    pose->orientation.z = data[2];
    pose->orientation.w = data[3];
    pose->position.x = data[4];
    pose->position.y = data[5];
    pose->position.z = data[6];
}

static void gn_update_pose_velocity(
    const XrPosef* pose,
    XrTime time,
    XrPosef* previous,
    XrTime* previous_time,
    float linear[3],
    float angular[3],
    int detect_recenter) {
    for (int i = 0; i < 3; ++i) {
        linear[i] = 0.0f;
        angular[i] = 0.0f;
    }
    if (*previous_time > 0 && time > *previous_time) {
        float dt = (float)(time - *previous_time) * 1e-9f;
        float dx = pose->position.x - previous->position.x;
        float dy = pose->position.y - previous->position.y;
        float dz = pose->position.z - previous->position.z;
        float orientation_dot =
            pose->orientation.x * previous->orientation.x +
            pose->orientation.y * previous->orientation.y +
            pose->orientation.z * previous->orientation.z +
            pose->orientation.w * previous->orientation.w;
        if (orientation_dot < 0.0f) orientation_dot = -orientation_dot;
        if (detect_recenter &&
            (dx * dx + dy * dy + dz * dz > 0.64f ||
             orientation_dot < 0.9238795f)) {
            gn_reference_space_event_pending = 1;
            gn_local_origin = *pose;
            gn_level_pose(&gn_local_origin);
            *previous = *pose;
            *previous_time = time;
            return;
        }
        if (dt > 0.00001f && dt < 0.5f) {
            XrQuaternionf previous_inverse = {
                -previous->orientation.x,
                -previous->orientation.y,
                -previous->orientation.z,
                previous->orientation.w,
            };
            XrQuaternionf delta = gn_quat_multiply(pose->orientation, previous_inverse);
            if (delta.w < 0.0f) {
                delta.x = -delta.x;
                delta.y = -delta.y;
                delta.z = -delta.z;
            }
            linear[0] = dx / dt;
            linear[1] = dy / dt;
            linear[2] = dz / dt;
            angular[0] = 2.0f * delta.x / dt;
            angular[1] = 2.0f * delta.y / dt;
            angular[2] = 2.0f * delta.z / dt;
        }
    }
    *previous = *pose;
    *previous_time = time;
}





static XrResult XRAPI_CALL gn_xrEnumerateInstanceExtensionProperties(
    const char* layerName,
    gn_uint32 propertyCapacityInput,
    gn_uint32* propertyCountOutput,
    XrExtensionProperties* properties) {
    (void)layerName;
    const gn_uint32 available = 4;
    if (!propertyCountOutput) return XR_ERROR_VALIDATION_FAILURE;
    if (propertyCapacityInput != 0 && properties == NULL) return XR_ERROR_VALIDATION_FAILURE;
    XrResult r = gn_copy_props(available, propertyCapacityInput, propertyCountOutput);
    if (XR_FAILED(r) || propertyCapacityInput == 0) return r;
    gn_write_extension(&properties[0], XR_KHR_D3D11_ENABLE_EXTENSION_NAME, XR_KHR_D3D11_enable_SPEC_VERSION);
    if (propertyCapacityInput > 1) {
        gn_write_extension(&properties[1], XR_KHR_VULKAN_ENABLE_EXTENSION_NAME, XR_KHR_vulkan_enable_SPEC_VERSION);
    }
    if (propertyCapacityInput > 2) {
        gn_write_extension(&properties[2], XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME, XR_KHR_vulkan_enable2_SPEC_VERSION);
    }
    if (propertyCapacityInput > 3) {
        gn_write_extension(&properties[3], XR_KHR_D3D12_ENABLE_EXTENSION_NAME, XR_KHR_D3D12_enable_SPEC_VERSION);
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrCreateInstance(const XrInstanceCreateInfo* createInfo, XrInstance* instance) {
    if (!instance) return XR_ERROR_VALIDATION_FAILURE;
    gn_clear_events();
    gn_session_running = 0;
    gn_stopping_pushed = 0;
    gn_action_sets_attached = 0;
    gn_exit_requested = 0;
    gn_local_origin_valid = 0;
    gn_last_recenter_serial = -1;
    gn_recenter_serial_supported = 0;
    gn_vk_instance = NULL;
    gn_vk_physical_device = NULL;
    gn_vk_device = NULL;
    gn_vk_queue = NULL;
    gn_paths_init();
    if (createInfo) {
        gn_log2("xrCreateInstance app=", createInfo->applicationInfo.applicationName);
        for (gn_uint32 i = 0; i < createInfo->enabledExtensionCount; ++i) {
            gn_log2("  enabled extension: ", createInfo->enabledExtensionNames[i]);
        }
    }
    gn_bridge_call("HELLO", NULL, 0);
    *instance = gn_instance;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroyInstance(XrInstance instance) {
    (void)instance;
    gn_log_line("xrDestroyInstance");
    gn_bridge_call("BYE", NULL, 0);
    gn_lock_acquire();
    gn_bridge_close();
    gn_lock_release();
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetInstanceProperties(XrInstance instance, XrInstanceProperties* properties) {
    (void)instance;
    if (!properties) return XR_ERROR_VALIDATION_FAILURE;
    properties->runtimeVersion = XR_MAKE_VERSION(0, 2, 0);
    gn_copy(properties->runtimeName, XR_MAX_RUNTIME_NAME_SIZE, "GameNativeVR OpenXR Runtime");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrPollEvent(XrInstance instance, XrEventDataBuffer* eventData) {
    XrSessionState state;
    if (instance != gn_instance) return XR_ERROR_HANDLE_INVALID;
    if (!eventData) return XR_ERROR_VALIDATION_FAILURE;
    if (gn_events_lost != 0) {
        XrEventDataEventsLost* lost = (XrEventDataEventsLost*)eventData;
        lost->type = XR_TYPE_EVENT_DATA_EVENTS_LOST;
        lost->next = NULL;
        lost->lostEventCount = gn_events_lost;
        gn_events_lost = 0;
        return XR_SUCCESS;
    }
    if (gn_instance_loss_event_pending) {
        XrEventDataInstanceLossPending* loss = (XrEventDataInstanceLossPending*)eventData;
        loss->type = XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING;
        loss->next = NULL;
        loss->lossTime = gn_next_display_time;
        gn_instance_loss_event_pending = 0;
        return XR_SUCCESS;
    }
    if (gn_reference_space_event_pending) {
        XrEventDataReferenceSpaceChangePending* change =
            (XrEventDataReferenceSpaceChangePending*)eventData;
        change->type = XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING;
        change->next = NULL;
        change->session = gn_session;
        change->referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        change->changeTime = gn_next_display_time;
        change->poseValid = XR_FALSE;
        gn_identity_pose(&change->poseInPreviousSpace);
        gn_reference_space_event_pending = 0;
        return XR_SUCCESS;
    }
    if (gn_interaction_profile_event_pending) {
        XrEventDataInteractionProfileChanged* changed =
            (XrEventDataInteractionProfileChanged*)eventData;
        changed->type = XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED;
        changed->next = NULL;
        changed->session = gn_session;
        gn_interaction_profile_event_pending = 0;
        return XR_SUCCESS;
    }
    if (gn_pop_session_event(&state)) {
        XrEventDataSessionStateChanged* changed = (XrEventDataSessionStateChanged*)eventData;
        changed->type = XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED;
        changed->next = NULL;
        changed->session = gn_session;
        changed->state = state;
        changed->time = gn_next_display_time;
        gn_log_num("session state event -> ", (long long)state);
        return XR_SUCCESS;
    }
    return XR_EVENT_UNAVAILABLE;
}

static XrResult XRAPI_CALL gn_xrResultToString(XrInstance instance, XrResult value, char buffer[XR_MAX_RESULT_STRING_SIZE]) {
    (void)instance;
    char out[XR_MAX_RESULT_STRING_SIZE];
    gn_size n = gn_append(out, sizeof(out), 0, "XR_RESULT_");
    gn_append_i64(out, sizeof(out), n, (long long)value);
    gn_copy(buffer, XR_MAX_RESULT_STRING_SIZE, out);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrStructureTypeToString(XrInstance instance, XrStructureType value, char buffer[XR_MAX_STRUCTURE_NAME_SIZE]) {
    (void)instance;
    (void)value;
    gn_copy(buffer, XR_MAX_STRUCTURE_NAME_SIZE, "XR_STRUCTURE_TYPE");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetSystem(XrInstance instance, const XrSystemGetInfo* getInfo, XrSystemId* systemId) {
    (void)instance;
    if (!getInfo || !systemId) return XR_ERROR_VALIDATION_FAILURE;
    if (getInfo->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) return XR_ERROR_FORM_FACTOR_UNAVAILABLE;
    *systemId = gn_system_id;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetSystemProperties(XrInstance instance, XrSystemId systemId, XrSystemProperties* properties) {
    (void)instance;
    char response[128];
    if (systemId != gn_system_id || !properties) return XR_ERROR_VALIDATION_FAILURE;
    gn_bridge_call("GET_SYSTEM", response, sizeof(response));
    properties->systemId = gn_system_id;
    properties->vendorId = 0x474e;
    gn_copy(properties->systemName, XR_MAX_SYSTEM_NAME_SIZE, "Meta Quest via GameNativeVR");
    properties->graphicsProperties.maxSwapchainImageHeight = 4096;
    properties->graphicsProperties.maxSwapchainImageWidth = 4096;
    properties->graphicsProperties.maxLayerCount = 16;
    properties->trackingProperties.orientationTracking = XR_TRUE;
    properties->trackingProperties.positionTracking = XR_TRUE;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEnumerateEnvironmentBlendModes(
    XrInstance instance,
    XrSystemId systemId,
    XrViewConfigurationType viewConfigurationType,
    gn_uint32 capacity,
    gn_uint32* count,
    XrEnvironmentBlendMode* modes) {
    (void)instance;
    if (systemId != gn_system_id || viewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    XrResult r = gn_copy_props(1, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    modes[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEnumerateViewConfigurations(
    XrInstance instance,
    XrSystemId systemId,
    gn_uint32 capacity,
    gn_uint32* count,
    XrViewConfigurationType* types) {
    (void)instance;
    if (systemId != gn_system_id) return XR_ERROR_SYSTEM_INVALID;
    XrResult r = gn_copy_props(1, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    types[0] = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetViewConfigurationProperties(
    XrInstance instance,
    XrSystemId systemId,
    XrViewConfigurationType type,
    XrViewConfigurationProperties* properties) {
    (void)instance;
    if (systemId != gn_system_id || type != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO || !properties) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->viewConfigurationType = type;
    properties->fovMutable = XR_FALSE;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEnumerateViewConfigurationViews(
    XrInstance instance,
    XrSystemId systemId,
    XrViewConfigurationType type,
    gn_uint32 capacity,
    gn_uint32* count,
    XrViewConfigurationView* views) {
    (void)instance;
    char response[128];
    if (systemId != gn_system_id || type != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (gn_bridge_call("GET_VIEWS", response, sizeof(response))) {
        long long w = gn_parse_i64(response, "width", 0);
        long long h = gn_parse_i64(response, "height", 0);
        if (w > 0 && h > 0 && w <= 16384 && h <= 16384) {
            gn_view_width = (gn_uint32)w;
            gn_view_height = (gn_uint32)h;
        }
    }
    XrResult r = gn_copy_props(2, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    for (gn_uint32 i = 0; i < 2; ++i) {
        views[i].recommendedImageRectWidth = gn_view_width;
        views[i].maxImageRectWidth = gn_view_width > 2048 ? gn_view_width : 2048;
        views[i].recommendedImageRectHeight = gn_view_height;
        views[i].maxImageRectHeight = gn_view_height > 2048 ? gn_view_height : 2048;
        views[i].recommendedSwapchainSampleCount = 1;
        views[i].maxSwapchainSampleCount = 1;
    }
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrCreateSession(XrInstance instance, const XrSessionCreateInfo* createInfo, XrSession* session) {
    (void)instance;
    if (!createInfo || createInfo->systemId != gn_system_id || !session) return XR_ERROR_VALIDATION_FAILURE;
    {
        typedef struct GnNextHeader {
            XrStructureType type;
            const struct GnNextHeader* next;
        } GnNextHeader;
        const GnNextHeader* next = (const GnNextHeader*)createInfo->next;
        int binding_found = 0;
        while (next) {
            if (next->type == XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR) {
                const XrGraphicsBindingVulkanKHR* binding =
                    (const XrGraphicsBindingVulkanKHR*)next;
                gn_gfx_api = GN_GFX_VULKAN;
                binding_found = 1;
                if (!gn_set_vulkan_context(binding)) {
                    gn_log_line("xrCreateSession: Vulkan unix context failed");
                    return XR_ERROR_GRAPHICS_DEVICE_INVALID;
                }
                break;
            }
            if (next->type == XR_TYPE_GRAPHICS_BINDING_D3D11_KHR) {
                const XrGraphicsBindingD3D11KHR* binding =
                    (const XrGraphicsBindingD3D11KHR*)next;
                gn_gfx_api = GN_GFX_D3D11;
                binding_found = 1;
                if (!gn_set_d3d11_context(binding->device)) {
                    gn_log_line("xrCreateSession: DXVK native interop failed");
                    return XR_ERROR_GRAPHICS_DEVICE_INVALID;
                }
                break;
            }
            if (next->type == XR_TYPE_GRAPHICS_BINDING_D3D12_KHR) {
                const XrGraphicsBindingD3D12KHR* binding =
                    (const XrGraphicsBindingD3D12KHR*)next;
                gn_gfx_api = GN_GFX_D3D12;
                binding_found = 1;
                if (!gn_set_d3d12_context(binding->device, binding->queue)) {
                    gn_log_line("xrCreateSession: vkd3d native interop failed");
                    return XR_ERROR_GRAPHICS_DEVICE_INVALID;
                }
                break;
            }
            next = next->next;
        }
        if (!binding_found) {
            gn_log_line("xrCreateSession: no supported graphics binding");
            return XR_ERROR_GRAPHICS_DEVICE_INVALID;
        }
    }
    *session = gn_session;
    gn_clear_events();
    gn_stopping_pushed = 0;
    gn_exit_requested = 0;
    gn_action_sets_attached = 0;
    gn_push_session_event(XR_SESSION_STATE_IDLE);
    gn_push_session_event(XR_SESSION_STATE_READY);
    gn_log_line("xrCreateSession");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroySession(XrSession session) {
    (void)session;
    for (gn_uint32 i = 0; i < gn_swapchain_count; ++i) {
        if (gn_swapchains[i].used && gn_unix_handle) {
            for (gn_uint32 image = 0; image < gn_swapchains[i].image_count; ++image) {
                if (gn_swapchains[i].d3d_images[image])
                    gn_com_release(gn_swapchains[i].d3d_images[image]);
            }
            struct gn_unix_destroy_swapchain_args args;
            args.slot = i;
            args.result = GN_UNIX_ERROR_UNAVAILABLE;
            gn_unix_call(GN_UNIX_DESTROY_SWAPCHAIN, &args);
        }
        gn_swapchains[i].used = 0;
    }
    gn_swapchain_count = 0;
    gn_bridge_call("SWAPCHAIN_RESET", NULL, 0);
    if (gn_dxvk_interop) {
        gn_com_release(gn_dxvk_interop);
        gn_dxvk_interop = NULL;
    }
    if (gn_vkd3d_device_ext) {
        gn_com_release(gn_vkd3d_device_ext);
        gn_vkd3d_device_ext = NULL;
    }
    if (gn_vkd3d_interop) {
        gn_com_release(gn_vkd3d_interop);
        gn_vkd3d_interop = NULL;
    }
    gn_d3d12_queue = NULL;
    gn_session_running = 0;
    gn_clear_events();
    gn_log_line("xrDestroySession");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrBeginSession(XrSession session, const XrSessionBeginInfo* beginInfo) {
    (void)beginInfo;
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    gn_bridge_call("BEGIN_SESSION", NULL, 0);
    gn_session_running = 1;
    gn_stopping_pushed = 0;
    gn_push_session_event(XR_SESSION_STATE_SYNCHRONIZED);
    gn_push_session_event(XR_SESSION_STATE_VISIBLE);
    gn_push_session_event(XR_SESSION_STATE_FOCUSED);
    gn_log_line("xrBeginSession");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEndSession(XrSession session) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    gn_bridge_call("END_SESSION", NULL, 0);
    gn_session_running = 0;
    gn_push_session_event(gn_exit_requested ? XR_SESSION_STATE_EXITING : XR_SESSION_STATE_IDLE);
    gn_log_line("xrEndSession");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrRequestExitSession(XrSession session) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    gn_exit_requested = 1;
    gn_bridge_call("REQUEST_EXIT", NULL, 0);
    if (!gn_stopping_pushed) {
        gn_stopping_pushed = 1;
        gn_push_session_event(XR_SESSION_STATE_STOPPING);
    }
    gn_log_line("xrRequestExitSession -> STOPPING");
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrEnumerateReferenceSpaces(XrSession session, gn_uint32 capacity, gn_uint32* count, XrReferenceSpaceType* spaces) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    {
        char response[128];
        if (gn_bridge_call("GET_BOUNDS", response, sizeof(response)))
            gn_stage_supported = gn_parse_i64(response, "supported", 1) != 0;
    }
    XrResult r = gn_copy_props(gn_stage_supported ? 3 : 2, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    spaces[0] = XR_REFERENCE_SPACE_TYPE_VIEW;
    if (capacity > 1) spaces[1] = XR_REFERENCE_SPACE_TYPE_LOCAL;
    if (gn_stage_supported && capacity > 2) spaces[2] = XR_REFERENCE_SPACE_TYPE_STAGE;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrCreateReferenceSpace(XrSession session, const XrReferenceSpaceCreateInfo* createInfo, XrSpace* space) {
    if (createInfo) gn_log_num("xrCreateReferenceSpace type=", (long long)createInfo->referenceSpaceType);
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!space || !createInfo ||
        createInfo->type != XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
        return XR_ERROR_VALIDATION_FAILURE;
    if (createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_VIEW &&
        createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_LOCAL &&
        createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_STAGE)
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    if (createInfo->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_STAGE && !gn_stage_supported)
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    if (gn_ref_space_count >= GN_MAX_REF_SPACES) return XR_ERROR_LIMIT_REACHED;
    gn_ref_spaces[gn_ref_space_count].used = 1;
    gn_ref_spaces[gn_ref_space_count].type = createInfo->referenceSpaceType;
    gn_ref_spaces[gn_ref_space_count].pose_in_reference_space = createInfo->poseInReferenceSpace;
    *space = (XrSpace)(GN_REFSPACE_BASE | gn_ref_space_count);
    ++gn_ref_space_count;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetReferenceSpaceBoundsRect(
    XrSession session,
    XrReferenceSpaceType referenceSpaceType,
    XrExtent2Df* bounds) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!bounds) return XR_ERROR_VALIDATION_FAILURE;
    bounds->width = 0.0f;
    bounds->height = 0.0f;
    if (referenceSpaceType != XR_REFERENCE_SPACE_TYPE_STAGE || !gn_stage_supported)
        return XR_SPACE_BOUNDS_UNAVAILABLE;
    {
        char response[128];
        if (gn_bridge_call("GET_BOUNDS", response, sizeof(response)) &&
            gn_parse_i64(response, "available", 0) != 0) {
            bounds->width = gn_parse_micro(response, "width", 0.0f);
            bounds->height = gn_parse_micro(response, "height", 0.0f);
            if (bounds->width > 0.0f && bounds->height > 0.0f)
                return XR_SUCCESS;
        }
    }
    return XR_SPACE_BOUNDS_UNAVAILABLE;
}

static XrResult XRAPI_CALL gn_xrCreateActionSpace(XrSession session, const XrActionSpaceCreateInfo* createInfo, XrSpace* space) {
    if (session != gn_session || !space || !createInfo) return XR_ERROR_HANDLE_INVALID;
    int action_idx = gn_action_index(createInfo->action);
    if (action_idx < 0) return XR_ERROR_HANDLE_INVALID;
    if (gn_action_space_count >= GN_MAX_ACTION_SPACES) return XR_ERROR_LIMIT_REACHED;
    gn_action_spaces[gn_action_space_count].used = 1;
    gn_action_spaces[gn_action_space_count].action_idx = action_idx;
    gn_action_spaces[gn_action_space_count].hand = gn_hand_from_path(createInfo->subactionPath);
    gn_action_spaces[gn_action_space_count].pose_in_action_space = createInfo->poseInActionSpace;
    *space = (XrSpace)(GN_ACTSPACE_BASE | gn_action_space_count);
    ++gn_action_space_count;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroySpace(XrSpace space) {
    int idx = gn_ref_space_index(space);
    if (idx >= 0) {
        gn_ref_spaces[idx].used = 0;
        return XR_SUCCESS;
    }
    idx = gn_action_space_index(space);
    if (idx >= 0) {
        gn_action_spaces[idx].used = 0;
        return XR_SUCCESS;
    }
    return XR_ERROR_HANDLE_INVALID;
}

static float gn_sqrt(float v) {
    float x = v > 1.0f ? v : 1.0f;
    int i;
    if (v <= 0.0f) return 0.0f;
    for (i = 0; i < 24; ++i) x = 0.5f * (x + v / x);
    return x;
}

static void gn_level_pose(XrPosef* pose) {
    float y = pose->orientation.y;
    float w = pose->orientation.w;
    float n = gn_sqrt(y * y + w * w);
    pose->orientation.x = 0.0f;
    pose->orientation.z = 0.0f;
    if (n < 1e-4f) {
        pose->orientation.y = 0.0f;
        pose->orientation.w = 1.0f;
    } else {
        pose->orientation.y = y / n;
        pose->orientation.w = w / n;
    }
}

static void gn_identity_pose(XrPosef* pose) {
    pose->orientation.x = 0.0f;
    pose->orientation.y = 0.0f;
    pose->orientation.z = 0.0f;
    pose->orientation.w = 1.0f;
    pose->position.x = 0.0f;
    pose->position.y = 0.0f;
    pose->position.z = 0.0f;
}

static XrQuaternionf gn_quat_multiply(XrQuaternionf a, XrQuaternionf b) {
    XrQuaternionf q;
    q.x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
    q.y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
    q.z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
    q.w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
    return q;
}

static XrVector3f gn_quat_rotate(XrQuaternionf q, XrVector3f v) {
    XrVector3f out;

    float tx = 2.0f * (q.y * v.z - q.z * v.y);
    float ty = 2.0f * (q.z * v.x - q.x * v.z);
    float tz = 2.0f * (q.x * v.y - q.y * v.x);
    out.x = v.x + q.w * tx + (q.y * tz - q.z * ty);
    out.y = v.y + q.w * ty + (q.z * tx - q.x * tz);
    out.z = v.z + q.w * tz + (q.x * ty - q.y * tx);
    return out;
}

static XrPosef gn_pose_multiply(XrPosef a, XrPosef b) {
    XrPosef out;
    XrVector3f rotated = gn_quat_rotate(a.orientation, b.position);
    out.orientation = gn_quat_multiply(a.orientation, b.orientation);
    out.position.x = a.position.x + rotated.x;
    out.position.y = a.position.y + rotated.y;
    out.position.z = a.position.z + rotated.z;
    return out;
}

static XrPosef gn_pose_inverse(XrPosef pose) {
    XrPosef out;
    XrVector3f negative;
    out.orientation.x = -pose.orientation.x;
    out.orientation.y = -pose.orientation.y;
    out.orientation.z = -pose.orientation.z;
    out.orientation.w = pose.orientation.w;
    negative.x = -pose.position.x;
    negative.y = -pose.position.y;
    negative.z = -pose.position.z;
    out.position = gn_quat_rotate(out.orientation, negative);
    return out;
}

static void gn_get_head_pose(XrPosef* pose) {
    gn_identity_pose(pose);
    if (!gn_eye_views_valid) return;
    pose->orientation.x = gn_eye_views[0].quat[0];
    pose->orientation.y = gn_eye_views[0].quat[1];
    pose->orientation.z = gn_eye_views[0].quat[2];
    pose->orientation.w = gn_eye_views[0].quat[3];
    pose->position.x = (gn_eye_views[0].pos[0] + gn_eye_views[1].pos[0]) * 0.5f;
    pose->position.y = (gn_eye_views[0].pos[1] + gn_eye_views[1].pos[1]) * 0.5f;
    pose->position.z = (gn_eye_views[0].pos[2] + gn_eye_views[1].pos[2]) * 0.5f;
}


static int gn_space_absolute_pose(
    XrSpace space,
    XrPosef* pose,
    float linear_velocity[3],
    float angular_velocity[3]) {
    int ref_idx = gn_ref_space_index(space);
    gn_identity_pose(pose);
    for (int i = 0; i < 3; ++i) {
        linear_velocity[i] = 0.0f;
        angular_velocity[i] = 0.0f;
    }
    if (ref_idx >= 0) {
        XrPosef natural;
        gn_identity_pose(&natural);
        if (gn_ref_spaces[ref_idx].type == XR_REFERENCE_SPACE_TYPE_VIEW) {
            if (!gn_eye_views_valid) return 0;
            gn_get_head_pose(&natural);
            for (int i = 0; i < 3; ++i) {
                linear_velocity[i] = gn_head_linear_velocity[i];
                angular_velocity[i] = gn_head_angular_velocity[i];
            }
        } else if (gn_ref_spaces[ref_idx].type == XR_REFERENCE_SPACE_TYPE_LOCAL) {
            if (gn_local_origin_valid) natural = gn_local_origin;
        } else if (gn_ref_spaces[ref_idx].type != XR_REFERENCE_SPACE_TYPE_STAGE) {
            return -1;
        }
        *pose = gn_pose_multiply(natural, gn_ref_spaces[ref_idx].pose_in_reference_space);
        return 1;
    }

    int action_space_idx = gn_action_space_index(space);
    if (action_space_idx >= 0) {
        const GnActionSpace* action_space = &gn_action_spaces[action_space_idx];
        const GnAction* action = &gn_actions[action_space->action_idx];
        int hand = action_space->hand >= 0 ? action_space->hand : gn_action_default_hand(action);
        int comp = action->component[hand];
        XrPosef tracked;
        if (!gn_hands[hand].active || (action->active_hands & (1u << hand)) == 0) return 0;
        gn_write_pose(&tracked, comp == GN_COMP_AIM_POSE ? gn_hands[hand].aim : gn_hands[hand].grip);
        *pose = gn_pose_multiply(tracked, action_space->pose_in_action_space);
        for (int i = 0; i < 3; ++i) {
            linear_velocity[i] = gn_hand_linear_velocity[hand][i];
            angular_velocity[i] = gn_hand_angular_velocity[hand][i];
        }
        return 1;
    }
    return -1;
}

static XrResult XRAPI_CALL gn_xrLocateSpace(XrSpace space, XrSpace baseSpace, XrTime time, XrSpaceLocation* location) {
    (void)time;
    XrPosef absolute_pose, base_pose;
    float linear[3], angular[3], base_linear[3], base_angular[3];
    if (!location) return XR_ERROR_VALIDATION_FAILURE;

    int space_status = gn_space_absolute_pose(space, &absolute_pose, linear, angular);
    int base_status = gn_space_absolute_pose(baseSpace, &base_pose, base_linear, base_angular);
    if (space_status < 0 || base_status < 0) return XR_ERROR_HANDLE_INVALID;

    if (location->next) {
        XrSpaceVelocity* velocity = (XrSpaceVelocity*)location->next;
        if (velocity->type == XR_TYPE_SPACE_VELOCITY) {
            velocity->velocityFlags = 0;
            velocity->linearVelocity.x = 0.0f;
            velocity->linearVelocity.y = 0.0f;
            velocity->linearVelocity.z = 0.0f;
            velocity->angularVelocity.x = 0.0f;
            velocity->angularVelocity.y = 0.0f;
            velocity->angularVelocity.z = 0.0f;
            if (space_status > 0 && base_status > 0) {
                XrQuaternionf base_inverse = gn_pose_inverse(base_pose).orientation;
                XrVector3f relative_linear = {
                    linear[0] - base_linear[0],
                    linear[1] - base_linear[1],
                    linear[2] - base_linear[2],
                };
                XrVector3f relative_angular = {
                    angular[0] - base_angular[0],
                    angular[1] - base_angular[1],
                    angular[2] - base_angular[2],
                };
                velocity->linearVelocity = gn_quat_rotate(base_inverse, relative_linear);
                velocity->angularVelocity = gn_quat_rotate(base_inverse, relative_angular);
                velocity->velocityFlags =
                    XR_SPACE_VELOCITY_LINEAR_VALID_BIT | XR_SPACE_VELOCITY_ANGULAR_VALID_BIT;
            }
        }
    }

    location->locationFlags = 0;
    gn_identity_pose(&location->pose);
    if (space_status == 0 || base_status == 0) return XR_SUCCESS;
    location->pose = gn_pose_multiply(gn_pose_inverse(base_pose), absolute_pose);
    location->locationFlags =
        XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
        XR_SPACE_LOCATION_POSITION_VALID_BIT |
        XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT |
        XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrLocateSpaces(
    XrSession session,
    const XrSpacesLocateInfo* locateInfo,
    XrSpaceLocations* spaceLocations) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!locateInfo || locateInfo->type != XR_TYPE_SPACES_LOCATE_INFO ||
        !spaceLocations || spaceLocations->type != XR_TYPE_SPACE_LOCATIONS ||
        locateInfo->spaceCount != spaceLocations->locationCount ||
        (locateInfo->spaceCount && (!locateInfo->spaces || !spaceLocations->locations))) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    XrSpaceVelocities* space_velocities = NULL;
    if (spaceLocations->next) {
        typedef struct GnOutHeader {
            XrStructureType type;
            void* next;
        } GnOutHeader;
        GnOutHeader* next = (GnOutHeader*)spaceLocations->next;
        while (next) {
            if (next->type == XR_TYPE_SPACE_VELOCITIES) {
                space_velocities = (XrSpaceVelocities*)next;
                break;
            }
            next = (GnOutHeader*)next->next;
        }
    }
    if (space_velocities &&
        (space_velocities->velocityCount != locateInfo->spaceCount ||
         (space_velocities->velocityCount && !space_velocities->velocities))) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    for (uint32_t i = 0; i < locateInfo->spaceCount; ++i) {
        XrSpaceVelocity velocity = {
            XR_TYPE_SPACE_VELOCITY,
            NULL,
            0,
            {0.0f, 0.0f, 0.0f},
            {0.0f, 0.0f, 0.0f},
        };
        XrSpaceLocation location = {
            XR_TYPE_SPACE_LOCATION,
            space_velocities ? &velocity : NULL,
            0,
            {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}},
        };
        XrResult result = gn_xrLocateSpace(
            locateInfo->spaces[i],
            locateInfo->baseSpace,
            locateInfo->time,
            &location);
        if (XR_FAILED(result)) return result;

        spaceLocations->locations[i].locationFlags = location.locationFlags;
        spaceLocations->locations[i].pose = location.pose;
        if (space_velocities) {
            space_velocities->velocities[i].velocityFlags = velocity.velocityFlags;
            space_velocities->velocities[i].linearVelocity = velocity.linearVelocity;
            space_velocities->velocities[i].angularVelocity = velocity.angularVelocity;
        }
    }
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrWaitFrame(XrSession session, const XrFrameWaitInfo* waitInfo, XrFrameState* frameState) {
    (void)waitInfo;
    char response[160];
    int synced;
    if (session != gn_session || !frameState) return XR_ERROR_HANDLE_INVALID;
    synced = gn_frame_sync_supported && gn_bridge_frame_sync(response, sizeof(response));
    if (!synced && !gn_frame_sync_supported)
        synced = gn_bridge_call("WAIT_FRAME", response, sizeof(response));
    if (synced) {
        frameState->predictedDisplayTime = gn_parse_i64(response, "time", gn_next_display_time);
        frameState->predictedDisplayPeriod = gn_parse_i64(response, "period", 11111111);
        frameState->shouldRender = gn_parse_i64(response, "render", gn_session_running ? 1 : 0) != 0 ? XR_TRUE : XR_FALSE;
        gn_next_display_time = frameState->predictedDisplayTime + frameState->predictedDisplayPeriod;

        long long recenter_serial = gn_parse_i64(response, "recenter", -1);
        if (recenter_serial >= 0) {
            gn_recenter_serial_supported = 1;
            if (gn_last_recenter_serial >= 0 && recenter_serial != gn_last_recenter_serial) {
                gn_reference_space_event_pending = 1;
                gn_local_origin_valid = 0;
                gn_last_head_time = 0;
                gn_log_num("headset recenter serial=", recenter_serial);
            }
            gn_last_recenter_serial = recenter_serial;
        }
        long long quest_state = gn_parse_i64(response, "state", 0);
        if (quest_state == XR_SESSION_STATE_LOSS_PENDING) {
            gn_instance_loss_event_pending = 1;
        }
        if (quest_state >= XR_SESSION_STATE_STOPPING && gn_session_running && !gn_stopping_pushed) {
            gn_stopping_pushed = 1;
            gn_push_session_event(XR_SESSION_STATE_STOPPING);
            gn_log_num("quest session stopping, state=", quest_state);
        }
    } else {
        frameState->predictedDisplayTime = gn_next_display_time;
        frameState->predictedDisplayPeriod = 11111111;
        frameState->shouldRender = gn_session_running ? XR_TRUE : XR_FALSE;
        gn_next_display_time += frameState->predictedDisplayPeriod;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrBeginFrame(XrSession session, const XrFrameBeginInfo* frameBeginInfo) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!frameBeginInfo || frameBeginInfo->type != XR_TYPE_FRAME_BEGIN_INFO)
        return XR_ERROR_VALIDATION_FAILURE;


    return XR_SUCCESS;
}

static XrRect2Di gn_normalize_rect(XrRect2Di rect, int* flip_y) {
    if (flip_y) *flip_y = 0;
    if (rect.extent.height < 0) {
        rect.offset.y += rect.extent.height;
        rect.extent.height = -rect.extent.height;
        if (flip_y) *flip_y = 1;
    }
    return rect;
}

static XrResult XRAPI_CALL gn_xrEndFrame(XrSession session, const XrFrameEndInfo* frameEndInfo) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!frameEndInfo || frameEndInfo->type != XR_TYPE_FRAME_END_INFO ||
        (frameEndInfo->layerCount && !frameEndInfo->layers))
        return XR_ERROR_VALIDATION_FAILURE;

    for (gn_uint32 layer_index = 0; layer_index < frameEndInfo->layerCount; ++layer_index) {
        const XrCompositionLayerBaseHeader* base = frameEndInfo->layers[layer_index];
        if (!base) return XR_ERROR_LAYER_INVALID;
        if (base->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
            const XrCompositionLayerProjection* projection =
                (const XrCompositionLayerProjection*)base;
            if (projection->viewCount != 2 || !projection->views)
                return XR_ERROR_VALIDATION_FAILURE;
            for (gn_uint32 eye = 0; eye < projection->viewCount; ++eye) {
                if (projection->views[eye].type !=
                    XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                    return XR_ERROR_VALIDATION_FAILURE;
                const XrSwapchainSubImage* sub_image =
                    &projection->views[eye].subImage;
                int slot = gn_swapchain_index(sub_image->swapchain);
                if (slot < 0) return XR_ERROR_LAYER_INVALID;
                const GnSwapchain* state = &gn_swapchains[slot];
                XrRect2Di rect = gn_normalize_rect(sub_image->imageRect, NULL);
                if (sub_image->imageArrayIndex >= state->create_info.arraySize ||
                    rect.offset.x < 0 ||
                    rect.offset.y < 0 ||
                    rect.extent.width <= 0 ||
                    rect.extent.height <= 0 ||
                    rect.extent.width >
                        (int32_t)state->create_info.width - rect.offset.x ||
                    rect.extent.height >
                        (int32_t)state->create_info.height - rect.offset.y)
                    return XR_ERROR_SWAPCHAIN_RECT_INVALID;
            }
        }
    }

    int submission_failed = 0;
    if (gn_gfx_api == GN_GFX_D3D11) gn_dxvk_flush_and_lock();
    if (gn_gfx_api == GN_GFX_D3D12) gn_vkd3d_lock();
    for (gn_uint32 layer_index = 0;
         layer_index < frameEndInfo->layerCount; ++layer_index) {
        const XrCompositionLayerBaseHeader* base =
            frameEndInfo->layers[layer_index];
        if (base->type != XR_TYPE_COMPOSITION_LAYER_PROJECTION) continue;
        const XrCompositionLayerProjection* projection =
            (const XrCompositionLayerProjection*)base;
        XrPosef layer_space_pose;
        float layer_linear[3], layer_angular[3];
        int layer_space_status = gn_space_absolute_pose(
            projection->space, &layer_space_pose,
            layer_linear, layer_angular);
        if (layer_space_status <= 0) {
            submission_failed = 1;
            continue;
        }
        struct gn_unix_submit_stereo_args args;
        args.view_count = projection->viewCount;
        int projection_ready = 1;
        for (gn_uint32 eye = 0; eye < projection->viewCount; ++eye) {
            int slot = gn_swapchain_index(
                projection->views[eye].subImage.swapchain);
            GnSwapchain* state = &gn_swapchains[slot];
            if (!state->last_released_valid) {
                submission_failed = 1;
                projection_ready = 0;
                break;
            }
            struct gn_unix_submit_view_args* view = &args.views[eye];
            view->slot = (gn_u32)slot;
            view->image_index = state->last_released_image;
            view->eye = eye;
            view->array_index =
                projection->views[eye].subImage.imageArrayIndex;
            {
                int flip_y = 0;
                XrRect2Di rect = gn_normalize_rect(
                    projection->views[eye].subImage.imageRect, &flip_y);
                view->rect_x = rect.offset.x;
                view->rect_y = rect.offset.y;
                view->rect_width = (gn_u32)rect.extent.width;
                view->rect_height = (gn_u32)rect.extent.height;
                view->flip_y = (gn_u32)flip_y;
            }
            {
                XrPosef absolute_view = gn_pose_multiply(
                    layer_space_pose, projection->views[eye].pose);
                view->orientation_micro[0] =
                    gn_float_to_micro(absolute_view.orientation.x);
                view->orientation_micro[1] =
                    gn_float_to_micro(absolute_view.orientation.y);
                view->orientation_micro[2] =
                    gn_float_to_micro(absolute_view.orientation.z);
                view->orientation_micro[3] =
                    gn_float_to_micro(absolute_view.orientation.w);
                view->position_micro[0] =
                    gn_float_to_micro(absolute_view.position.x);
                view->position_micro[1] =
                    gn_float_to_micro(absolute_view.position.y);
                view->position_micro[2] =
                    gn_float_to_micro(absolute_view.position.z);
                view->fov_micro[0] = gn_float_to_micro(
                    projection->views[eye].fov.angleLeft);
                view->fov_micro[1] = gn_float_to_micro(
                    projection->views[eye].fov.angleRight);
                view->fov_micro[2] = gn_float_to_micro(
                    projection->views[eye].fov.angleUp);
                view->fov_micro[3] = gn_float_to_micro(
                    projection->views[eye].fov.angleDown);
            }
        }
        if (!projection_ready) continue;
        args.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (!gn_unix_call(GN_UNIX_SUBMIT_STEREO, &args) ||
            args.result != GN_UNIX_SUCCESS) {
            submission_failed = 1;
            if (!gn_transport_failure_logged) {
                gn_log_num("xrEndFrame stereo transport failed result=", args.result);
                gn_transport_failure_logged = 1;
            }
        } else {
            for (gn_uint32 eye = 0; eye < projection->viewCount; ++eye) {
                int slot = (int)args.views[eye].slot;
                GnSwapchain* state = &gn_swapchains[slot];
                state->submitted[state->last_released_image] = 1;
                gn_transport_eye_mask |= (1u << eye);
            }
            if (gn_transport_eye_mask == 3u) {
                gn_log_line("xrEndFrame batched image transport accepted both eyes");
                gn_transport_eye_mask |= 4u;
            }
        }
    }
    for (gn_uint32 i = 0; i < gn_swapchain_count; ++i)
        gn_swapchains[i].last_released_valid = 0;
    if (gn_gfx_api == GN_GFX_D3D11) gn_dxvk_unlock();
    if (gn_gfx_api == GN_GFX_D3D12) gn_vkd3d_unlock();



    if (!gn_frame_milestone_sent && frameEndInfo->layerCount > 0) {
        char cmd[64];
        gn_size n = gn_append(cmd, sizeof(cmd), 0, "END_FRAME layers=");
        n = gn_append_i64(cmd, sizeof(cmd), n, (long long)frameEndInfo->layerCount);
        if (gn_bridge_call(cmd, NULL, 0)) gn_frame_milestone_sent = 1;
    }
    return submission_failed ? XR_ERROR_RUNTIME_FAILURE : XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrLocateViews(
    XrSession session,
    const XrViewLocateInfo* viewLocateInfo,
    XrViewState* viewState,
    gn_uint32 capacity,
    gn_uint32* count,
    XrView* views) {
    char response[1024];
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!viewLocateInfo || viewLocateInfo->type != XR_TYPE_VIEW_LOCATE_INFO ||
        !viewState || viewState->type != XR_TYPE_VIEW_STATE || !count ||
        (capacity && !views)) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (viewLocateInfo->viewConfigurationType !=
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }

    XrResult r = gn_copy_props(2, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    for (gn_uint32 i = 0; i < 2; ++i) {
        if (views[i].type != XR_TYPE_VIEW)
            return XR_ERROR_VALIDATION_FAILURE;
    }

    if (gn_cached_line("views", 0, response, sizeof(response)) ||
        gn_bridge_call("LOCATE_VIEWS", response, sizeof(response))) {
        static const char* qk[2][4] = {{"lqx", "lqy", "lqz", "lqw"}, {"rqx", "rqy", "rqz", "rqw"}};
        static const char* pk[2][3] = {{"lpx", "lpy", "lpz"}, {"rpx", "rpy", "rpz"}};
        static const char* fk[2][4] = {{"lfl", "lfr", "lfu", "lfd"}, {"rfl", "rfr", "rfu", "rfd"}};
        for (int eye = 0; eye < 2; ++eye) {
            GnEyeView* v = &gn_eye_views[eye];
            for (int i = 0; i < 4; ++i) v->quat[i] = gn_parse_micro(response, qk[eye][i], i == 3 ? 1.0f : 0.0f);
            for (int i = 0; i < 3; ++i) v->pos[i] = gn_parse_micro(response, pk[eye][i], 0.0f);
            v->fov[0] = gn_parse_micro(response, fk[eye][0], -0.75f);
            v->fov[1] = gn_parse_micro(response, fk[eye][1], 0.75f);
            v->fov[2] = gn_parse_micro(response, fk[eye][2], 0.75f);
            v->fov[3] = gn_parse_micro(response, fk[eye][3], -0.75f);
        }
        gn_eye_views_valid = 1;
        {
            XrPosef head_pose;
            gn_get_head_pose(&head_pose);
            if (!gn_local_origin_valid) {
                gn_local_origin = head_pose;
                gn_level_pose(&gn_local_origin);
                gn_local_origin_valid = 1;
            }
            gn_update_pose_velocity(
                &head_pose,
                viewLocateInfo ? viewLocateInfo->displayTime : gn_next_display_time,
                &gn_last_head_pose,
                &gn_last_head_time,
                gn_head_linear_velocity,
                gn_head_angular_velocity,
                gn_recenter_serial_supported ? 0 : 1);
        }
    }

    XrPosef base_pose;
    float base_linear[3], base_angular[3];
    int base_status = gn_space_absolute_pose(
        viewLocateInfo->space, &base_pose, base_linear, base_angular);
    if (base_status < 0) return XR_ERROR_HANDLE_INVALID;
    XrPosef base_inverse = gn_pose_inverse(base_pose);

    viewState->viewStateFlags = 0;
    if (gn_eye_views_valid && base_status > 0) {
        viewState->viewStateFlags =
            XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT |
            XR_VIEW_STATE_ORIENTATION_TRACKED_BIT | XR_VIEW_STATE_POSITION_TRACKED_BIT;
    }
    for (gn_uint32 i = 0; i < 2; ++i) {
        if (gn_eye_views_valid) {
            const GnEyeView* v = &gn_eye_views[i];
            XrPosef absolute_eye;
            absolute_eye.orientation.x = v->quat[0];
            absolute_eye.orientation.y = v->quat[1];
            absolute_eye.orientation.z = v->quat[2];
            absolute_eye.orientation.w = v->quat[3];
            absolute_eye.position.x = v->pos[0];
            absolute_eye.position.y = v->pos[1];
            absolute_eye.position.z = v->pos[2];
            views[i].pose = base_status > 0
                ? gn_pose_multiply(base_inverse, absolute_eye)
                : absolute_eye;
            views[i].fov.angleLeft = v->fov[0];
            views[i].fov.angleRight = v->fov[1];
            views[i].fov.angleUp = v->fov[2];
            views[i].fov.angleDown = v->fov[3];
        } else {
            gn_identity_pose(&views[i].pose);
            views[i].pose.position.x = i == 0 ? -0.032f : 0.032f;
            views[i].fov.angleLeft = -0.75f;
            views[i].fov.angleRight = 0.75f;
            views[i].fov.angleUp = 0.75f;
            views[i].fov.angleDown = -0.75f;
        }
    }
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrEnumerateSwapchainFormats(XrSession session, gn_uint32 capacity, gn_uint32* count, int64_t* formats) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;




    static const int64_t vk_formats[] = {50                  , 44                   , 43                  , 37                   };
    static const int64_t d3d_formats[] = {91                             , 87                   , 29                        , 28                   };
    const int64_t* list =
        (gn_gfx_api == GN_GFX_D3D11 || gn_gfx_api == GN_GFX_D3D12) ?
        d3d_formats : vk_formats;
    XrResult r = gn_copy_props(4, capacity, count);
    if (XR_FAILED(r) || capacity == 0) return r;
    for (gn_uint32 i = 0; i < 4 && i < capacity; ++i) formats[i] = list[i];
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetD3D11GraphicsRequirementsKHR(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsD3D11KHR* graphicsRequirements) {
    (void)instance;
    if (systemId != gn_system_id || !graphicsRequirements) return XR_ERROR_VALIDATION_FAILURE;
    gn_gfx_api = GN_GFX_D3D11;
    gn_log_line("graphics binding requested: d3d11");
    gn_bridge_call("GFX_API d3d11", NULL, 0);
    graphicsRequirements->adapterLuid = gn_primary_adapter_luid();
    graphicsRequirements->minFeatureLevel = D3D_FEATURE_LEVEL_11_0;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetD3D12GraphicsRequirementsKHR(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsD3D12KHR* graphicsRequirements) {
    (void)instance;
    if (systemId != gn_system_id || !graphicsRequirements) return XR_ERROR_VALIDATION_FAILURE;
    gn_gfx_api = GN_GFX_D3D12;
    gn_log_line("graphics binding requested: d3d12");
    gn_bridge_call("GFX_API d3d12", NULL, 0);
    graphicsRequirements->adapterLuid = gn_primary_adapter_luid();
    graphicsRequirements->minFeatureLevel = D3D_FEATURE_LEVEL_11_0;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetVulkanInstanceExtensionsKHR(
    XrInstance instance,
    XrSystemId systemId,
    gn_uint32 bufferCapacityInput,
    gn_uint32* bufferCountOutput,
    char* buffer) {
    (void)instance;
    if (systemId != gn_system_id) return XR_ERROR_SYSTEM_INVALID;
    return gn_fill_string(GN_VULKAN_INSTANCE_EXTENSIONS, bufferCapacityInput, bufferCountOutput, buffer);
}

static XrResult XRAPI_CALL gn_xrGetVulkanDeviceExtensionsKHR(
    XrInstance instance,
    XrSystemId systemId,
    gn_uint32 bufferCapacityInput,
    gn_uint32* bufferCountOutput,
    char* buffer) {
    (void)instance;
    if (systemId != gn_system_id) return XR_ERROR_SYSTEM_INVALID;
    return gn_fill_string(GN_VULKAN_DEVICE_EXTENSIONS, bufferCapacityInput, bufferCountOutput, buffer);
}

static XrResult XRAPI_CALL gn_xrGetVulkanGraphicsDeviceKHR(
    XrInstance instance,
    XrSystemId systemId,
    VkInstance vkInstance,
    VkPhysicalDevice* vkPhysicalDevice) {
    (void)instance;
    if (systemId != gn_system_id || !vkPhysicalDevice) return XR_ERROR_VALIDATION_FAILURE;
    *vkPhysicalDevice = gn_first_vulkan_physical_device(vkInstance);
    if (!*vkPhysicalDevice) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    gn_vk_instance = vkInstance;
    gn_vk_physical_device = *vkPhysicalDevice;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetVulkanGraphicsRequirementsKHR(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsVulkanKHR* graphicsRequirements) {
    (void)instance;
    if (systemId != gn_system_id || !graphicsRequirements) return XR_ERROR_VALIDATION_FAILURE;
    gn_gfx_api = GN_GFX_VULKAN;
    gn_log_line("graphics binding requested: vulkan");
    gn_bridge_call("GFX_API vulkan", NULL, 0);
    graphicsRequirements->minApiVersionSupported = XR_MAKE_VERSION(1, 1, 0);
    graphicsRequirements->maxApiVersionSupported = XR_MAKE_VERSION(1, 3, 0);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetVulkanGraphicsDevice2KHR(
    XrInstance instance,
    const XrVulkanGraphicsDeviceGetInfoKHR* getInfo,
    VkPhysicalDevice* vulkanPhysicalDevice) {
    (void)instance;
    if (!getInfo || !vulkanPhysicalDevice) return XR_ERROR_VALIDATION_FAILURE;
    if (getInfo->systemId != gn_system_id) return XR_ERROR_SYSTEM_INVALID;
    *vulkanPhysicalDevice = gn_first_vulkan_physical_device(getInfo->vulkanInstance);
    if (!*vulkanPhysicalDevice) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    gn_vk_instance = getInfo->vulkanInstance;
    gn_vk_physical_device = *vulkanPhysicalDevice;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetVulkanGraphicsRequirements2KHR(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsVulkanKHR* graphicsRequirements) {
    return gn_xrGetVulkanGraphicsRequirementsKHR(instance, systemId, graphicsRequirements);
}

typedef VkResult (GN_STDCALL *GnVkCreateInstance)(
    const void* createInfo, const void* allocator, VkInstance* instance);
typedef VkResult (GN_STDCALL *GnVkCreateDevice)(
    VkPhysicalDevice physicalDevice, const void* createInfo,
    const void* allocator, VkDevice* device);

static XrResult XRAPI_CALL gn_xrCreateVulkanInstanceKHR(
    XrInstance instance,
    const XrVulkanInstanceCreateInfoKHR* createInfo,
    VkInstance* vulkanInstance,
    VkResult* vulkanResult) {
    (void)instance;
    if (!createInfo || createInfo->systemId != gn_system_id ||
        !createInfo->pfnGetInstanceProcAddr || !createInfo->vulkanCreateInfo ||
        !vulkanInstance || !vulkanResult) return XR_ERROR_VALIDATION_FAILURE;
    GnVkCreateInstance create =
        (GnVkCreateInstance)createInfo->pfnGetInstanceProcAddr(NULL, "vkCreateInstance");
    if (!create) return XR_ERROR_FUNCTION_UNSUPPORTED;
    *vulkanResult = create(
        createInfo->vulkanCreateInfo, createInfo->vulkanAllocator, vulkanInstance);
    if (*vulkanResult == 0) gn_vk_instance = *vulkanInstance;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrCreateVulkanDeviceKHR(
    XrInstance instance,
    const XrVulkanDeviceCreateInfoKHR* createInfo,
    VkDevice* vulkanDevice,
    VkResult* vulkanResult) {
    (void)instance;
    if (!createInfo || createInfo->systemId != gn_system_id ||
        !createInfo->pfnGetInstanceProcAddr || !createInfo->vulkanCreateInfo ||
        !createInfo->vulkanPhysicalDevice || !vulkanDevice || !vulkanResult) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    GnVkCreateDevice create =
        (GnVkCreateDevice)createInfo->pfnGetInstanceProcAddr(
            gn_vk_instance, "vkCreateDevice");
    if (!create) return XR_ERROR_FUNCTION_UNSUPPORTED;

    *vulkanResult = create(
        createInfo->vulkanPhysicalDevice, createInfo->vulkanCreateInfo,
        createInfo->vulkanAllocator, vulkanDevice);
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrCreateSwapchain(XrSession session, const XrSwapchainCreateInfo* createInfo, XrSwapchain* swapchain) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!createInfo || !swapchain || !createInfo->width || !createInfo->height ||
        !createInfo->arraySize || createInfo->faceCount != 1 ||
        createInfo->width > 16384 || createInfo->height > 16384 ||
        createInfo->arraySize > 65535 || createInfo->mipCount > 65535) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (gn_gfx_api != GN_GFX_VULKAN &&
        gn_gfx_api != GN_GFX_D3D11 &&
        gn_gfx_api != GN_GFX_D3D12) {
        gn_log_line("xrCreateSwapchain: graphics native interop is unavailable");
        return XR_ERROR_FEATURE_UNSUPPORTED;
    }

    gn_uint32 slot = GN_MAX_SWAPCHAINS;
    for (gn_uint32 i = 0; i < gn_swapchain_count; ++i) {
        if (!gn_swapchains[i].used) { slot = i; break; }
    }
    if (slot == GN_MAX_SWAPCHAINS) {
        if (gn_swapchain_count >= GN_MAX_SWAPCHAINS) return XR_ERROR_LIMIT_REACHED;
        slot = gn_swapchain_count++;
    }

    struct gn_unix_create_swapchain_args args;
    gn_zero_memory(&args, sizeof(args));
    args.slot = slot;
    args.width = createInfo->width;
    args.height = createInfo->height;
    args.array_size = createInfo->arraySize;
    args.mip_count = createInfo->mipCount;
    args.sample_count = createInfo->sampleCount;
    args.format = (gn_gfx_api == GN_GFX_D3D11 || gn_gfx_api == GN_GFX_D3D12) ?
        gn_d3d_format_to_vk(createInfo->format) : createInfo->format;
    if (!args.format) return XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
    gn_log_num("xrCreateSwapchain client format=", createInfo->format);
    gn_log_num("xrCreateSwapchain Vulkan format=", args.format);
    args.usage = createInfo->usageFlags;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_CREATE_SWAPCHAIN, &args) ||
        args.result != GN_UNIX_SUCCESS || args.image_count < 2 ||
        args.image_count > GN_UNIX_MAX_IMAGES) {
        gn_log_num("xrCreateSwapchain unix result=", args.result);
        return XR_ERROR_RUNTIME_FAILURE;
    }

    GnSwapchain* state = &gn_swapchains[slot];
    gn_zero_memory(state, sizeof(*state));
    state->used = 1;
    state->image_count = args.image_count;
    state->create_info = *createInfo;
    for (gn_uint32 i = 0; i < args.image_count; ++i) {
        state->images[i] = args.images[i];
        state->image_state[i] = GN_IMAGE_AVAILABLE;
    }
    if (gn_gfx_api == GN_GFX_D3D11) {
        GnDxvkCreateTexture create_texture =
            (GnDxvkCreateTexture)gn_com_method(gn_dxvk_interop, 10);
        GnD3D11Texture2DDesc1 desc;
        gn_zero_memory(&desc, sizeof(desc));
        desc.Width = createInfo->width;
        desc.Height = createInfo->height;
        desc.MipLevels = createInfo->mipCount;
        desc.ArraySize = createInfo->arraySize;
        desc.Format = (int)createInfo->format;
        desc.SampleDesc.Count = createInfo->sampleCount;
        desc.Usage = 0;
        desc.BindFlags = 0x8;
        if (createInfo->usageFlags & XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT)
            desc.BindFlags |= 0x20;
        if (createInfo->usageFlags & XR_SWAPCHAIN_USAGE_UNORDERED_ACCESS_BIT)
            desc.BindFlags |= 0x80;
        for (gn_uint32 i = 0; i < args.image_count; ++i) {
            ID3D11Texture2D* texture = NULL;
            if (!create_texture ||
                create_texture(gn_dxvk_interop, &desc,
                               (VkImage)state->images[i], &texture) < 0 ||
                !texture) {
                for (gn_uint32 j = 0; j < i; ++j)
                    gn_com_release(state->d3d_images[j]);
                struct gn_unix_destroy_swapchain_args destroy_args;
                destroy_args.slot = slot;
                destroy_args.result = GN_UNIX_ERROR_UNAVAILABLE;
                gn_unix_call(GN_UNIX_DESTROY_SWAPCHAIN, &destroy_args);
                gn_zero_memory(state, sizeof(*state));
                return XR_ERROR_RUNTIME_FAILURE;
            }
            state->d3d_images[i] = texture;
        }
    } else if (gn_gfx_api == GN_GFX_D3D12) {
        GnVkd3dCreateResource create_resource =
            (GnVkd3dCreateResource)gn_com_method(gn_vkd3d_device_ext, 10);
        GnD3D12ResourceDesc1 desc;
        gn_zero_memory(&desc, sizeof(desc));
        desc.Dimension = 3;
        desc.Width = createInfo->width;
        desc.Height = createInfo->height;
        desc.DepthOrArraySize = (unsigned short)createInfo->arraySize;
        desc.MipLevels = (unsigned short)createInfo->mipCount;
        desc.Format = (int)createInfo->format;
        desc.SampleDesc.Count = createInfo->sampleCount;
        if (createInfo->usageFlags & XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT)
            desc.Flags |= 0x1;
        if (createInfo->usageFlags & XR_SWAPCHAIN_USAGE_UNORDERED_ACCESS_BIT)
            desc.Flags |= 0x4;
        for (gn_uint32 i = 0; i < args.image_count; ++i) {
            ID3D12Resource* resource = NULL;
            if (!create_resource ||
                create_resource(gn_vkd3d_device_ext, &desc,
                                state->images[i], &resource) < 0 ||
                !resource) {
                for (gn_uint32 j = 0; j < i; ++j)
                    gn_com_release(state->d3d_images[j]);
                struct gn_unix_destroy_swapchain_args destroy_args;
                destroy_args.slot = slot;
                destroy_args.result = GN_UNIX_ERROR_UNAVAILABLE;
                gn_unix_call(GN_UNIX_DESTROY_SWAPCHAIN, &destroy_args);
                gn_zero_memory(state, sizeof(*state));
                return XR_ERROR_RUNTIME_FAILURE;
            }
            state->d3d_images[i] = resource;
        }
    }
    *swapchain = (XrSwapchain)(GN_SWAPCHAIN_BASE | slot);
    {
        char command[64];
        gn_size n = gn_append(
            command, sizeof(command), 0, "SWAPCHAIN_CREATE images=");
        gn_append_i64(
            command, sizeof(command), n, (long long)args.image_count);
        gn_bridge_call(command, NULL, 0);
    }
    gn_log_num("xrCreateSwapchain images=", args.image_count);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroySwapchain(XrSwapchain swapchain) {
    int slot = gn_swapchain_index(swapchain);
    if (slot < 0) return XR_ERROR_HANDLE_INVALID;
    for (gn_uint32 i = 0; i < gn_swapchains[slot].image_count; ++i) {
        if (gn_swapchains[slot].d3d_images[i])
            gn_com_release(gn_swapchains[slot].d3d_images[i]);
    }
    struct gn_unix_destroy_swapchain_args args;
    args.slot = (gn_u32)slot;
    args.result = GN_UNIX_ERROR_UNAVAILABLE;
    if (!gn_unix_call(GN_UNIX_DESTROY_SWAPCHAIN, &args) ||
        args.result != GN_UNIX_SUCCESS) return XR_ERROR_RUNTIME_FAILURE;
    gn_zero_memory(&gn_swapchains[slot], sizeof(gn_swapchains[slot]));
    gn_bridge_call("SWAPCHAIN_DESTROY", NULL, 0);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEnumerateSwapchainImages(XrSwapchain swapchain, gn_uint32 capacity, gn_uint32* count, XrSwapchainImageBaseHeader* images) {
    int slot = gn_swapchain_index(swapchain);
    if (slot < 0) return XR_ERROR_HANDLE_INVALID;
    if (!count || (capacity && !images)) return XR_ERROR_VALIDATION_FAILURE;
    GnSwapchain* state = &gn_swapchains[slot];
    *count = state->image_count;
    if (!capacity) return XR_SUCCESS;
    if (capacity < state->image_count) return XR_ERROR_SIZE_INSUFFICIENT;
    if (gn_gfx_api == GN_GFX_VULKAN) {
        for (gn_uint32 i = 0; i < state->image_count; ++i) {
            XrSwapchainImageVulkanKHR* image =
                (XrSwapchainImageVulkanKHR*)((unsigned char*)images +
                    i * sizeof(XrSwapchainImageVulkanKHR));
            if (image->type != XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR) {
                return XR_ERROR_VALIDATION_FAILURE;
            }
            image->image = (VkImage)state->images[i];
        }
    } else if (gn_gfx_api == GN_GFX_D3D11) {
        for (gn_uint32 i = 0; i < state->image_count; ++i) {
            XrSwapchainImageD3D11KHR* image =
                (XrSwapchainImageD3D11KHR*)((unsigned char*)images +
                    i * sizeof(XrSwapchainImageD3D11KHR));
            if (image->type != XR_TYPE_SWAPCHAIN_IMAGE_D3D11_KHR)
                return XR_ERROR_VALIDATION_FAILURE;
            image->texture = (ID3D11Texture2D*)state->d3d_images[i];
        }
    } else if (gn_gfx_api == GN_GFX_D3D12) {
        for (gn_uint32 i = 0; i < state->image_count; ++i) {
            XrSwapchainImageD3D12KHR* image =
                (XrSwapchainImageD3D12KHR*)((unsigned char*)images +
                    i * sizeof(XrSwapchainImageD3D12KHR));
            if (image->type != XR_TYPE_SWAPCHAIN_IMAGE_D3D12_KHR)
                return XR_ERROR_VALIDATION_FAILURE;
            image->texture = (ID3D12Resource*)state->d3d_images[i];
        }
    } else {
        return XR_ERROR_FEATURE_UNSUPPORTED;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrAcquireSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageAcquireInfo* acquireInfo, gn_uint32* index) {
    int slot = gn_swapchain_index(swapchain);
    if (slot < 0) return XR_ERROR_HANDLE_INVALID;
    if (!acquireInfo || acquireInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO || !index)
        return XR_ERROR_VALIDATION_FAILURE;
    GnSwapchain* state = &gn_swapchains[slot];
    if (state->acquire_count >= state->image_count)
        return XR_ERROR_CALL_ORDER_INVALID;
    for (gn_uint32 n = 0; n < state->image_count; ++n) {
        gn_uint32 candidate = (state->next_image + n) % state->image_count;
        if (state->image_state[candidate] == GN_IMAGE_AVAILABLE) {
            state->image_state[candidate] = GN_IMAGE_ACQUIRED;
            state->acquire_queue[
                (state->acquire_head + state->acquire_count) % GN_UNIX_MAX_IMAGES] =
                candidate;
            ++state->acquire_count;
            state->next_image = (candidate + 1) % state->image_count;
            *index = candidate;
            return XR_SUCCESS;
        }
    }
    return XR_ERROR_CALL_ORDER_INVALID;
}

static XrResult XRAPI_CALL gn_xrWaitSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageWaitInfo* waitInfo) {
    int slot = gn_swapchain_index(swapchain);
    if (slot < 0) return XR_ERROR_HANDLE_INVALID;
    if (!waitInfo || waitInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO ||
        (waitInfo->timeout < 0 && waitInfo->timeout != XR_INFINITE_DURATION))
        return XR_ERROR_VALIDATION_FAILURE;
    GnSwapchain* state = &gn_swapchains[slot];
    if (!state->acquire_count) return XR_ERROR_CALL_ORDER_INVALID;
    gn_uint32 index = state->acquire_queue[state->acquire_head];
    if (index >= state->image_count || state->image_state[index] != GN_IMAGE_ACQUIRED) {
        return XR_ERROR_CALL_ORDER_INVALID;
    }
    if (state->submitted[index]) {
        struct gn_unix_acquire_image_args args;
        args.slot = (gn_u32)slot;
        args.image_index = index;
        args.timeout_ns = waitInfo->timeout;
        args.result = GN_UNIX_ERROR_UNAVAILABLE;
        if (!gn_unix_call(GN_UNIX_ACQUIRE_IMAGE, &args)) return XR_ERROR_RUNTIME_FAILURE;
        if (args.result == GN_UNIX_ERROR_TIMEOUT) return XR_TIMEOUT_EXPIRED;
        if (args.result != GN_UNIX_SUCCESS) return XR_ERROR_RUNTIME_FAILURE;
        state->submitted[index] = 0;
    }
    state->image_state[index] = GN_IMAGE_WAITED;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrReleaseSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageReleaseInfo* releaseInfo) {
    int slot = gn_swapchain_index(swapchain);
    if (slot < 0) return XR_ERROR_HANDLE_INVALID;
    if (!releaseInfo || releaseInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
        return XR_ERROR_VALIDATION_FAILURE;
    GnSwapchain* state = &gn_swapchains[slot];
    if (!state->acquire_count) return XR_ERROR_CALL_ORDER_INVALID;
    gn_uint32 index = state->acquire_queue[state->acquire_head];
    if (index >= state->image_count || state->image_state[index] != GN_IMAGE_WAITED) {
        return XR_ERROR_CALL_ORDER_INVALID;
    }
    state->image_state[index] = GN_IMAGE_AVAILABLE;
    state->acquire_head = (state->acquire_head + 1) % GN_UNIX_MAX_IMAGES;
    --state->acquire_count;
    state->last_released_image = index;
    state->last_released_valid = 1;
    return XR_SUCCESS;
}





static XrResult XRAPI_CALL gn_xrStringToPath(XrInstance instance, const char* pathString, XrPath* path) {
    (void)instance;
    if (!pathString || !path) return XR_ERROR_VALIDATION_FAILURE;
    gn_paths_init();
    *path = gn_path_get_or_create(pathString);
    return *path != XR_NULL_PATH ? XR_SUCCESS : XR_ERROR_PATH_FORMAT_INVALID;
}

static XrResult XRAPI_CALL gn_xrPathToString(XrInstance instance, XrPath path, gn_uint32 capacity, gn_uint32* count, char* buffer) {
    (void)instance;
    const char* str = gn_path_string(path);
    if (!str) return XR_ERROR_PATH_INVALID;
    return gn_fill_string(str, capacity, count, buffer);
}

static XrResult XRAPI_CALL gn_xrCreateActionSet(XrInstance instance, const XrActionSetCreateInfo* createInfo, XrActionSet* actionSet) {
    if (instance != gn_instance || !actionSet || !createInfo) return XR_ERROR_HANDLE_INVALID;
    if (gn_action_set_count >= GN_MAX_ACTION_SETS) return XR_ERROR_LIMIT_REACHED;
    GnActionSet* record = &gn_action_sets[gn_action_set_count];
    record->used = 1;
    record->attached = 0;
    gn_copy(record->name, sizeof(record->name), createInfo->actionSetName);
    *actionSet = (XrActionSet)(GN_ACTIONSET_BASE | gn_action_set_count);
    ++gn_action_set_count;
    gn_log2("xrCreateActionSet: ", createInfo->actionSetName);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroyActionSet(XrActionSet actionSet) {
    int idx = gn_action_set_index(actionSet);
    if (idx < 0) return XR_ERROR_HANDLE_INVALID;
    gn_action_sets[idx].used = 0;
    for (gn_uint32 i = 0; i < gn_action_count; ++i) {
        if (gn_actions[i].used && gn_actions[i].action_set_idx == idx) gn_actions[i].used = 0;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrCreateAction(XrActionSet actionSet, const XrActionCreateInfo* createInfo, XrAction* action) {
    int set_idx = gn_action_set_index(actionSet);
    if (!action || !createInfo) return XR_ERROR_VALIDATION_FAILURE;
    if (set_idx < 0) return XR_ERROR_HANDLE_INVALID;
    if (gn_action_sets[set_idx].attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (gn_action_count >= GN_MAX_ACTIONS) return XR_ERROR_LIMIT_REACHED;
    GnAction* record = &gn_actions[gn_action_count];
    record->used = 1;
    record->action_set_idx = set_idx;
    record->type = createInfo->actionType;
    record->component[0] = GN_COMP_NONE;
    record->component[1] = GN_COMP_NONE;
    record->binding_priority[0] = 0;
    record->binding_priority[1] = 0;
    record->active_hands = 0;
    gn_copy(record->name, sizeof(record->name), createInfo->actionName);
    *action = (XrAction)(GN_ACTION_BASE | gn_action_count);
    ++gn_action_count;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrDestroyAction(XrAction action) {
    int idx = gn_action_index(action);
    if (idx >= 0) gn_actions[idx].used = 0;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrSuggestInteractionProfileBindings(
    XrInstance instance,
    const XrInteractionProfileSuggestedBinding* suggestedBindings) {
    if (instance != gn_instance) return XR_ERROR_HANDLE_INVALID;
    if (!suggestedBindings) return XR_ERROR_VALIDATION_FAILURE;

    const char* profile = gn_path_string(suggestedBindings->interactionProfile);
    const int priority = gn_interaction_profile_priority(profile);
    gn_log2("xrSuggestInteractionProfileBindings: ", profile ? profile : "?");

    for (gn_uint32 i = 0; i < suggestedBindings->countSuggestedBindings; ++i) {
        const XrActionSuggestedBinding* binding = &suggestedBindings->suggestedBindings[i];
        int action_idx = gn_action_index(binding->action);
        const char* path = gn_path_string(binding->binding);
        if (action_idx < 0 || !path) continue;
        int hand = -1;
        if (gn_starts_with(path, "/user/hand/left")) hand = 0;
        else if (gn_starts_with(path, "/user/hand/right")) hand = 1;
        int comp = gn_component_from_binding(path);
        if (comp == GN_COMP_NONE) {
            gn_log2("  unmapped binding: ", path);
            continue;
        }
        if (hand >= 0) {
            if (priority >= gn_actions[action_idx].binding_priority[hand]) {
                gn_actions[action_idx].component[hand] = (unsigned char)comp;
                gn_actions[action_idx].binding_priority[hand] = (unsigned char)priority;
            }
        } else {
            for (int candidate = 0; candidate < 2; ++candidate) {
                if (priority >= gn_actions[action_idx].binding_priority[candidate]) {
                    gn_actions[action_idx].component[candidate] = (unsigned char)comp;
                    gn_actions[action_idx].binding_priority[candidate] = (unsigned char)priority;
                }
            }
        }
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrAttachSessionActionSets(XrSession session, const XrSessionActionSetsAttachInfo* attachInfo) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!attachInfo || (attachInfo->countActionSets != 0 && !attachInfo->actionSets)) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (gn_action_sets_attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    for (gn_uint32 i = 0; i < attachInfo->countActionSets; ++i) {
        if (gn_action_set_index(attachInfo->actionSets[i]) < 0) return XR_ERROR_HANDLE_INVALID;
    }
    for (gn_uint32 i = 0; i < attachInfo->countActionSets; ++i) {
        gn_action_sets[gn_action_set_index(attachInfo->actionSets[i])].attached = 1;
    }
    gn_action_sets_attached = 1;
    gn_interaction_profile_event_pending = 1;
    gn_log_line("xrAttachSessionActionSets");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrSyncActions(XrSession session, const XrActionsSyncInfo* syncInfo) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!syncInfo || (syncInfo->countActiveActionSets != 0 && !syncInfo->activeActionSets)) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!gn_action_sets_attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    for (gn_uint32 i = 0; i < gn_action_count; ++i) gn_actions[i].active_hands = 0;
    for (gn_uint32 i = 0; i < syncInfo->countActiveActionSets; ++i) {
        const XrActiveActionSet* active = &syncInfo->activeActionSets[i];
        int set_idx = gn_action_set_index(active->actionSet);
        if (set_idx < 0) return XR_ERROR_HANDLE_INVALID;
        if (!gn_action_sets[set_idx].attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
        int hand = gn_hand_from_path(active->subactionPath);
        unsigned char hand_mask = hand < 0 ? 3u : (unsigned char)(1u << hand);
        for (gn_uint32 action_idx = 0; action_idx < gn_action_count; ++action_idx) {
            if (gn_actions[action_idx].used && gn_actions[action_idx].action_set_idx == set_idx) {
                gn_actions[action_idx].active_hands |= hand_mask;
            }
        }
    }
    gn_refresh_hand(0);
    gn_refresh_hand(1);
    for (int hand = 0; hand < 2; ++hand) {
        XrPosef pose;
        gn_write_pose(&pose, gn_hands[hand].grip);
        gn_update_pose_velocity(
            &pose,
            gn_next_display_time,
            &gn_last_hand_pose[hand],
            &gn_last_hand_time[hand],
            gn_hand_linear_velocity[hand],
            gn_hand_angular_velocity[hand],
            0);
    }
    gn_update_change_tracking(gn_next_display_time);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetCurrentInteractionProfile(
    XrSession session,
    XrPath topLevelUserPath,
    XrInteractionProfileState* interactionProfile) {
    (void)topLevelUserPath;
    if (session != gn_session || !interactionProfile) return XR_ERROR_HANDLE_INVALID;
    interactionProfile->interactionProfile = gn_path_get_or_create("/interaction_profiles/oculus/touch_controller");
    return XR_SUCCESS;
}


static int gn_resolve_action(const XrActionStateGetInfo* getInfo, int* out_hand, int* out_comp) {
    int action_idx = gn_action_index(getInfo->action);
    if (action_idx < 0) return 0;
    const GnAction* action = &gn_actions[action_idx];
    int hand = gn_hand_from_path(getInfo->subactionPath);
    if (getInfo->subactionPath != XR_NULL_PATH && hand < 0) return 0;
    if (hand < 0) hand = gn_action_default_hand(action);
    *out_hand = hand;
    *out_comp = action->component[hand];
    return 1;
}

static int gn_action_is_active(XrAction action, int hand, int comp) {
    int action_idx = gn_action_index(action);
    if (action_idx < 0 || hand < 0 || hand > 1 || comp == GN_COMP_NONE) return 0;
    return (gn_actions[action_idx].active_hands & (1u << hand)) != 0 && gn_hands[hand].active;
}

static XrResult XRAPI_CALL gn_xrGetActionStateBoolean(
    XrSession session,
    const XrActionStateGetInfo* getInfo,
    XrActionStateBoolean* state) {
    int hand = 0, comp = GN_COMP_NONE;
    if (session != gn_session || !state || !getInfo) return XR_ERROR_HANDLE_INVALID;
    if (!gn_resolve_action(getInfo, &hand, &comp)) return XR_ERROR_HANDLE_INVALID;
    float value = gn_comp_value(hand, comp);
    state->currentState = value > 0.7f ? XR_TRUE : XR_FALSE;
    state->changedSinceLastSync = gn_comp_changed[hand][comp] ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = gn_comp_change_time[hand][comp] ? gn_comp_change_time[hand][comp] : gn_last_sync_time;
    state->isActive = gn_action_is_active(getInfo->action, hand, comp) ? XR_TRUE : XR_FALSE;
    if (!state->isActive) {
        state->currentState = XR_FALSE;
        state->changedSinceLastSync = XR_FALSE;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetActionStateFloat(
    XrSession session,
    const XrActionStateGetInfo* getInfo,
    XrActionStateFloat* state) {
    int hand = 0, comp = GN_COMP_NONE;
    if (session != gn_session || !state || !getInfo) return XR_ERROR_HANDLE_INVALID;
    if (!gn_resolve_action(getInfo, &hand, &comp)) return XR_ERROR_HANDLE_INVALID;
    state->currentState = gn_comp_value(hand, comp);
    state->changedSinceLastSync = gn_comp_changed[hand][comp] ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = gn_comp_change_time[hand][comp] ? gn_comp_change_time[hand][comp] : gn_last_sync_time;
    state->isActive = gn_action_is_active(getInfo->action, hand, comp) ? XR_TRUE : XR_FALSE;
    if (!state->isActive) {
        state->currentState = 0.0f;
        state->changedSinceLastSync = XR_FALSE;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetActionStateVector2f(
    XrSession session,
    const XrActionStateGetInfo* getInfo,
    XrActionStateVector2f* state) {
    int hand = 0, comp = GN_COMP_NONE;
    if (session != gn_session || !state || !getInfo) return XR_ERROR_HANDLE_INVALID;
    if (!gn_resolve_action(getInfo, &hand, &comp)) return XR_ERROR_HANDLE_INVALID;
    state->currentState.x = gn_hands[hand].sx;
    state->currentState.y = gn_hands[hand].sy;
    state->changedSinceLastSync =
        (gn_comp_changed[hand][GN_COMP_STICK_X] || gn_comp_changed[hand][GN_COMP_STICK_Y] || gn_comp_changed[hand][GN_COMP_STICK])
            ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = gn_last_sync_time;
    state->isActive = gn_action_is_active(getInfo->action, hand, comp) ? XR_TRUE : XR_FALSE;
    if (!state->isActive) {
        state->currentState.x = 0.0f;
        state->currentState.y = 0.0f;
        state->changedSinceLastSync = XR_FALSE;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrGetActionStatePose(
    XrSession session,
    const XrActionStateGetInfo* getInfo,
    XrActionStatePose* state) {
    int hand = 0, comp = GN_COMP_NONE;
    if (session != gn_session || !state || !getInfo) return XR_ERROR_HANDLE_INVALID;
    if (!gn_resolve_action(getInfo, &hand, &comp)) return XR_ERROR_HANDLE_INVALID;
    state->isActive = gn_action_is_active(getInfo->action, hand, comp) ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrEnumerateBoundSourcesForAction(
    XrSession session,
    const XrBoundSourcesForActionEnumerateInfo* enumerateInfo,
    gn_uint32 capacity,
    gn_uint32* count,
    XrPath* sources) {
    XrPath resolved[2];
    gn_uint32 resolved_count = 0;
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!enumerateInfo) return XR_ERROR_VALIDATION_FAILURE;
    int action_idx = gn_action_index(enumerateInfo->action);
    if (action_idx < 0) return XR_ERROR_HANDLE_INVALID;
    for (int hand = 0; hand < 2; ++hand) {
        int comp = gn_actions[action_idx].component[hand];
        const char* suffix = NULL;
        switch (comp) {
            case GN_COMP_PRIMARY: suffix = hand == 0 ? "/input/x/click" : "/input/a/click"; break;
            case GN_COMP_SECONDARY: suffix = hand == 0 ? "/input/y/click" : "/input/b/click"; break;
            case GN_COMP_STICK_CLICK: suffix = "/input/thumbstick/click"; break;
            case GN_COMP_MENU: suffix = "/input/menu/click"; break;
            case GN_COMP_TRIGGER: suffix = "/input/trigger/value"; break;
            case GN_COMP_SQUEEZE: suffix = "/input/squeeze/value"; break;
            case GN_COMP_STICK: suffix = "/input/thumbstick"; break;
            case GN_COMP_STICK_X: suffix = "/input/thumbstick/x"; break;
            case GN_COMP_STICK_Y: suffix = "/input/thumbstick/y"; break;
            case GN_COMP_GRIP_POSE: suffix = "/input/grip/pose"; break;
            case GN_COMP_AIM_POSE: suffix = "/input/aim/pose"; break;
            case GN_COMP_HAPTIC: suffix = "/output/haptic"; break;
            default: break;
        }
        if (suffix) {
            char path[GN_PATH_LEN];
            gn_size n = gn_append(path, sizeof(path), 0, hand == 0 ? "/user/hand/left" : "/user/hand/right");
            gn_append(path, sizeof(path), n, suffix);
            resolved[resolved_count++] = gn_path_get_or_create(path);
        }
    }
    XrResult r = gn_copy_props(resolved_count, capacity, count);
    if (XR_SUCCEEDED(r) && capacity != 0) {
        if (!sources) return XR_ERROR_VALIDATION_FAILURE;
        for (gn_uint32 i = 0; i < resolved_count; ++i) sources[i] = resolved[i];
    }
    return r;
}

static XrResult XRAPI_CALL gn_xrGetInputSourceLocalizedName(
    XrSession session,
    const XrInputSourceLocalizedNameGetInfo* getInfo,
    gn_uint32 capacity,
    gn_uint32* count,
    char* buffer) {
    (void)getInfo;
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    return gn_fill_string("GameNativeVR Touch", capacity, count, buffer);
}

static XrResult XRAPI_CALL gn_xrApplyHapticFeedback(
    XrSession session,
    const XrHapticActionInfo* hapticActionInfo,
    const XrHapticBaseHeader* hapticFeedback) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (!hapticActionInfo || !hapticFeedback) return XR_ERROR_VALIDATION_FAILURE;

    int hand = gn_hand_from_path(hapticActionInfo->subactionPath);
    if (hand < 0) {
        int action_idx = gn_action_index(hapticActionInfo->action);
        hand = action_idx >= 0 ? gn_action_default_hand(&gn_actions[action_idx]) : 0;
    }

    if (hapticFeedback->type == XR_TYPE_HAPTIC_VIBRATION) {
        const XrHapticVibration* vibration = (const XrHapticVibration*)hapticFeedback;
        char cmd[160];
        gn_size n = gn_append(cmd, sizeof(cmd), 0, "HAPTIC hand=");
        n = gn_append_i64(cmd, sizeof(cmd), n, hand);
        n = gn_append(cmd, sizeof(cmd), n, " amp=");
        n = gn_append_i64(cmd, sizeof(cmd), n, gn_float_to_micro(vibration->amplitude));
        n = gn_append(cmd, sizeof(cmd), n, " dur=");
        n = gn_append_i64(cmd, sizeof(cmd), n, vibration->duration);
        n = gn_append(cmd, sizeof(cmd), n, " freq=");
        n = gn_append_i64(cmd, sizeof(cmd), n, gn_float_to_micro(vibration->frequency));
        gn_bridge_call(cmd, NULL, 0);
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL gn_xrStopHapticFeedback(XrSession session, const XrHapticActionInfo* hapticActionInfo) {
    if (session != gn_session) return XR_ERROR_HANDLE_INVALID;
    if (hapticActionInfo) {
        int hand = gn_hand_from_path(hapticActionInfo->subactionPath);
        char cmd[64];
        gn_size n = gn_append(cmd, sizeof(cmd), 0, "HAPTIC hand=");
        n = gn_append_i64(cmd, sizeof(cmd), n, hand < 0 ? 0 : hand);
        n = gn_append(cmd, sizeof(cmd), n, " amp=0 dur=0 freq=0");
        gn_bridge_call(cmd, NULL, 0);
    }
    return XR_SUCCESS;
}





GN_EXPORT XrResult XRAPI_CALL xrGetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function) {
    (void)instance;
    if (!name || !function) return XR_ERROR_VALIDATION_FAILURE;
    *function = NULL;
#define GN_PROC(n) if (gn_streq(name, #n)) { *function = (PFN_xrVoidFunction)gn_##n; return XR_SUCCESS; }
    GN_PROC(xrEnumerateInstanceExtensionProperties)
    GN_PROC(xrCreateInstance)
    GN_PROC(xrDestroyInstance)
    GN_PROC(xrGetInstanceProperties)
    GN_PROC(xrPollEvent)
    GN_PROC(xrResultToString)
    GN_PROC(xrStructureTypeToString)
    GN_PROC(xrGetSystem)
    GN_PROC(xrGetSystemProperties)
    GN_PROC(xrEnumerateEnvironmentBlendModes)
    GN_PROC(xrEnumerateViewConfigurations)
    GN_PROC(xrGetViewConfigurationProperties)
    GN_PROC(xrEnumerateViewConfigurationViews)
    GN_PROC(xrCreateSession)
    GN_PROC(xrDestroySession)
    GN_PROC(xrBeginSession)
    GN_PROC(xrEndSession)
    GN_PROC(xrRequestExitSession)
    GN_PROC(xrEnumerateReferenceSpaces)
    GN_PROC(xrCreateReferenceSpace)
    GN_PROC(xrGetReferenceSpaceBoundsRect)
    GN_PROC(xrDestroySpace)
    GN_PROC(xrCreateActionSpace)
    GN_PROC(xrLocateSpace)
    GN_PROC(xrLocateSpaces)
    GN_PROC(xrWaitFrame)
    GN_PROC(xrBeginFrame)
    GN_PROC(xrEndFrame)
    GN_PROC(xrLocateViews)
    GN_PROC(xrEnumerateSwapchainFormats)
    GN_PROC(xrGetD3D11GraphicsRequirementsKHR)
    GN_PROC(xrGetD3D12GraphicsRequirementsKHR)
    GN_PROC(xrGetVulkanInstanceExtensionsKHR)
    GN_PROC(xrGetVulkanDeviceExtensionsKHR)
    GN_PROC(xrGetVulkanGraphicsDeviceKHR)
    GN_PROC(xrGetVulkanGraphicsRequirementsKHR)
    GN_PROC(xrGetVulkanGraphicsDevice2KHR)
    GN_PROC(xrGetVulkanGraphicsRequirements2KHR)
    GN_PROC(xrCreateVulkanInstanceKHR)
    GN_PROC(xrCreateVulkanDeviceKHR)
    GN_PROC(xrCreateSwapchain)
    GN_PROC(xrDestroySwapchain)
    GN_PROC(xrEnumerateSwapchainImages)
    GN_PROC(xrAcquireSwapchainImage)
    GN_PROC(xrWaitSwapchainImage)
    GN_PROC(xrReleaseSwapchainImage)
    GN_PROC(xrStringToPath)
    GN_PROC(xrPathToString)
    GN_PROC(xrCreateActionSet)
    GN_PROC(xrDestroyActionSet)
    GN_PROC(xrCreateAction)
    GN_PROC(xrDestroyAction)
    GN_PROC(xrSuggestInteractionProfileBindings)
    GN_PROC(xrAttachSessionActionSets)
    GN_PROC(xrSyncActions)
    GN_PROC(xrGetCurrentInteractionProfile)
    GN_PROC(xrGetActionStateBoolean)
    GN_PROC(xrGetActionStateFloat)
    GN_PROC(xrGetActionStateVector2f)
    GN_PROC(xrGetActionStatePose)
    GN_PROC(xrEnumerateBoundSourcesForAction)
    GN_PROC(xrGetInputSourceLocalizedName)
    GN_PROC(xrApplyHapticFeedback)
    GN_PROC(xrStopHapticFeedback)
#undef GN_PROC
    if (gn_streq(name, "xrLocateSpacesKHR")) {
        *function = (PFN_xrVoidFunction)gn_xrLocateSpaces;
        return XR_SUCCESS;
    }
    if (gn_streq(name, "xrGetInstanceProcAddr")) {
        *function = (PFN_xrVoidFunction)xrGetInstanceProcAddr;
        return XR_SUCCESS;
    }
    gn_log2("unsupported function requested: ", name);
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}

GN_EXPORT XrResult XRAPI_CALL xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest) {
    if (!loaderInfo || !runtimeRequest) return XR_ERROR_VALIDATION_FAILURE;
    if (loaderInfo->minInterfaceVersion > XR_CURRENT_LOADER_RUNTIME_VERSION ||
        loaderInfo->maxInterfaceVersion < XR_CURRENT_LOADER_RUNTIME_VERSION) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    runtimeRequest->structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtimeRequest->structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtimeRequest->structSize = sizeof(*runtimeRequest);
    runtimeRequest->runtimeInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    runtimeRequest->runtimeApiVersion = XR_MAKE_VERSION(1, 0, 0);
    runtimeRequest->getInstanceProcAddr = xrGetInstanceProcAddr;
    gn_log_line("runtime negotiated with loader");
    return XR_SUCCESS;
}

GN_EXPORT int DllMain(void* module, unsigned long reason, void* reserved) {
    (void)module;
    (void)reason;
    (void)reserved;
    return 1;
}
