#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <limits.h>
#include <pthread.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/futex.h>
#include <sys/syscall.h>
#include <jni.h>
#include <SDL2/SDL.h>
#include <android/log.h>
#include <sys/stat.h>
#include <time.h>

static int g_debug_enabled = 0;
#define LOGI(...) dprintf(STDOUT_FILENO, __VA_ARGS__)
#define LOGE(...) dprintf(STDERR_FILENO, __VA_ARGS__)
#define LOGD(...) do { if (g_debug_enabled) dprintf(STDOUT_FILENO, __VA_ARGS__); } while (0)

#define LOG_TAG "evshim"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) do { if (g_debug_enabled) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)

/* -- Shared memory layout --
 *
 *  offset  size  field
 *  ------  ----  -----
 *       0     4  seq              — futex word
 *       4     2  lx
 *       6     2  ly
 *       8     2  rx
 *      10     2  ry
 *      12     2  lt
 *      14     2  rt
 *      16    15  btn[15]
 *      31     1  hat
 *      32     2  low_freq_rumble
 *      34     2  high_freq_rumble
 *      36     4  rumble_seq       — futex word (rumble: Wine -> Java)
 *                                   total: 40 bytes
 */

#define SHM_DATA_SIZE  64
#define MAX_GAMEPADS    4

struct gamepad_state {
    int16_t  lx, ly, rx, ry, lt, rt;
    uint8_t  btn[15];
    uint8_t  hat;
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};

struct gamepad_io {
    atomic_uint       seq;
    struct gamepad_state state;
    atomic_uint       rumble_seq;
};

_Static_assert(sizeof(struct gamepad_io) <= SHM_DATA_SIZE, "gamepad_io exceeds SHM_DATA_SIZE");

static struct gamepad_io *shm [MAX_GAMEPADS];
static int vjoy_ids[MAX_GAMEPADS];
static size_t g_shm_map_size = 0;
static int g_is_wine = 0;

static void build_gamepad_dir(char *out, size_t size)
{
    const char *base = getenv("EVSHIM_BASE_PATH");

    // fallback
    if (!base || !*base) {
        base = "/data/data/app.gamenative/files";
    }

    snprintf(out, size, "%s/gamepad_shm", base);
}

static int mkdir_gameshm(const char *path)
{
    struct stat st;

    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            return 0;
        }

        errno = ENOTDIR;
        return -1;
    }

    if (mkdir(path, 0777) < 0 && errno != EEXIST) {
        return -1;
    }

    return 0;
}

// mmap setup
// java side still need to get the shm here to get the futex word address
static void setup_shm(int players)
{
    g_shm_map_size = (size_t)sysconf(_SC_PAGESIZE);

    char gamepad_dir[PATH_MAX];
    build_gamepad_dir(gamepad_dir, sizeof(gamepad_dir));

    if (mkdir_gameshm(gamepad_dir) < 0) {
        LOGE("evshim: failed to create/check dir '%s': %s\n",
             gamepad_dir,
             strerror(errno));
        return;
    }

    for (int i = 0; i < players; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path),
                 "%s/gamepad%s.mem",
                 gamepad_dir,
                 (i == 0) ? "" : (char[2]){'0' + i, '\0'});

        int fd = open(path, O_RDWR | O_CREAT, 0666);
        if (fd < 0) {
            LOGE("evshim: P%d open '%s' failed: %s\n", i, path, strerror(errno));
            continue;
        }

        if (ftruncate(fd, SHM_DATA_SIZE) < 0) {
            LOGE("evshim: P%d ftruncate failed: %s\n", i, strerror(errno));
            close(fd);
            continue;
        }

        shm[i] = mmap(NULL, g_shm_map_size,
                      PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        struct stat st;
        fstat(fd, &st);
        ALOGI("evshim: java P%d mmap'd inode=%lu dev=%lu addr=%p\n",
             i, (unsigned long)st.st_ino,
             (unsigned long)st.st_dev,
             (void *)shm[i]);
        LOGI("evshim: P%d mmap'd inode=%lu dev=%lu addr=%p\n",
             i, (unsigned long)st.st_ino,
             (unsigned long)st.st_dev,
             (void *)shm[i]);
        close(fd);

        if (shm[i] == MAP_FAILED) {
            LOGE("evshim: P%d mmap failed: %s\n", i, strerror(errno));
            shm[i] = NULL;
            continue;
        }

        if (!g_is_wine) {
            ALOGI("evshim: resetting controller state");
            memset(shm[i], 0, sizeof(struct gamepad_io));
        }
    }
}

static void        *sdl_handle = NULL;

static int          (*p_SDL_Init)                   (uint32_t);
static const char  *(*p_SDL_GetError)               (void);
static SDL_Joystick*(*p_SDL_JoystickOpen)           (int);
static int          (*p_SDL_JoystickAttachVirtualEx) (const SDL_VirtualJoystickDesc *);
static int          (*p_SDL_JoystickSetVirtualAxis)  (SDL_Joystick *, int, int16_t);
static int          (*p_SDL_JoystickSetVirtualButton)(SDL_Joystick *, int, uint8_t);
static int          (*p_SDL_JoystickSetVirtualHat)   (SDL_Joystick *, int, uint8_t);
static void         (*p_SDL_GetVersion)              (SDL_version *);

#define GETFUNCPTR(name) \
    do { \
        if (!(p_##name = (typeof(p_##name))dlsym(sdl_handle, #name))) \
            LOGE("evshim: failed to load SDL symbol: %s\n", #name); \
    } while (0)

static int OnRumble(void *userdata, uint16_t low, uint16_t high)
{
    int idx = (int)(intptr_t)userdata;
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return -1;

    shm[idx]->state.low_freq_rumble  = low;
    shm[idx]->state.high_freq_rumble = high;

    // Wake up the Java thread waiting for rumble updates
    atomic_thread_fence(memory_order_seq_cst);
    atomic_fetch_add_explicit(&shm[idx]->rumble_seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAKE, 1, NULL, NULL, 0);

    LOGD("evshim: rumble P%d low=%u high=%u\n", idx, low, high);
    return 0;
}

static bool try_read_state(struct gamepad_io *s, uint32_t *last_seq, struct gamepad_io *out)
{
    uint32_t seq1 = atomic_load_explicit(&s->seq, memory_order_acquire);
    if (seq1 == *last_seq) {
        return false;
    }

    // copy controller state
    out->state = s->state;

    uint32_t seq2 = atomic_load_explicit(&s->seq, memory_order_acquire);
    if (seq2 != seq1) {
        // android side updates at the screen refresh rate
        // which is at a much slower pace than the unbounded loop in the wine processes
        // this should never happen under normal circumstances
        LOGD("evshim: seq race in try_read_state, retrying\n");
        return false;
    }

    *last_seq = seq2;
    return true;
}

static void *vjoy_updater(void *arg)
{
    int idx = (int)(intptr_t)arg;
    struct gamepad_io *s = shm[idx];

    SDL_Joystick *js = p_SDL_JoystickOpen(vjoy_ids[idx]);
    if (!js) {
        LOGE("evshim: P%d SDL_JoystickOpen failed\n", idx);
        return NULL;
    }

    LOGI("evshim: vjoy_updater P%d running (PID %d)\n", idx, getpid());

    uint32_t last_seq = atomic_load_explicit(&s->seq, memory_order_acquire);

    struct timespec ts;
    struct timespec *tsp = NULL;
    const char *to = getenv("EVSHIM_FUTEX_TIMEOUT_MS");
    if (to && *to) {
        long ms = atol(to);
        if (ms > 0) {
            ts.tv_sec  = ms / 1000;
            ts.tv_nsec = (ms % 1000) * 1000000L;
            tsp = &ts;
        }
    }

    for (;;) {
        struct gamepad_io snap;

        if (try_read_state(s, &last_seq, &snap)) {
            p_SDL_JoystickSetVirtualAxis(js, 0, snap.state.lx);
            p_SDL_JoystickSetVirtualAxis(js, 1, snap.state.ly);
            p_SDL_JoystickSetVirtualAxis(js, 2, snap.state.rx);
            p_SDL_JoystickSetVirtualAxis(js, 3, snap.state.ry);
            p_SDL_JoystickSetVirtualAxis(js, 4, snap.state.lt);
            p_SDL_JoystickSetVirtualAxis(js, 5, snap.state.rt);
            for (int i = 0; i < 15; i++) {
                p_SDL_JoystickSetVirtualButton(js, i, snap.state.btn[i]);
            }
            p_SDL_JoystickSetVirtualHat(js, 0, snap.state.hat);
            continue;
        }

        syscall(SYS_futex, &s->seq, FUTEX_WAIT, last_seq, tsp, NULL, 0);
    }

    return NULL;
}

static void initialize_wine(int players)
{
    sdl_handle = dlopen("libSDL2-2.0.so.0", RTLD_LAZY | RTLD_GLOBAL);
    if (!sdl_handle) { LOGE("dlopen SDL failed: %s\n", dlerror()); return; }

    GETFUNCPTR(SDL_Init);  GETFUNCPTR(SDL_GetError);
    GETFUNCPTR(SDL_JoystickOpen);  GETFUNCPTR(SDL_JoystickAttachVirtualEx);
    GETFUNCPTR(SDL_JoystickSetVirtualAxis);  GETFUNCPTR(SDL_JoystickSetVirtualButton);
    GETFUNCPTR(SDL_JoystickSetVirtualHat);
    GETFUNCPTR(SDL_GetVersion);
    if (!p_SDL_Init || !p_SDL_GetError || !p_SDL_JoystickOpen ||
                !p_SDL_JoystickAttachVirtualEx || !p_SDL_JoystickSetVirtualAxis ||
                !p_SDL_JoystickSetVirtualButton || !p_SDL_JoystickSetVirtualHat ||
                !p_SDL_GetVersion) {
        LOGE("evshim: SDL symbol resolution incomplete; aborting init\n");
        dlclose(sdl_handle);
        sdl_handle = NULL;
        return;
    }

    p_SDL_Init(SDL_INIT_JOYSTICK);

    SDL_version v;
    p_SDL_GetVersion(&v);
    LOGI("evshim: SDL %d.%d.%d bound\n", v.major, v.minor, v.patch);

    for (int i = 0; i < players; i++) {
        if (!shm[i]) continue;

        SDL_VirtualJoystickDesc d = {0};
        d.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        d.type    = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        d.naxes   = 6; d.nbuttons = 15; d.nhats = 1;
        d.Rumble  = &OnRumble;  d.userdata = (void*)(intptr_t)i;
        d.vendor_id = 0x045E;  // Microsoft
        d.product_id = 0x028E; // Xbox 360 Controller
        d.button_mask = 0xFFFF;
        d.axis_mask = 0x3F;

        char name[64];
        snprintf(name, sizeof name, "Xbox 360 Controller");
        d.name = strdup(name);

        vjoy_ids[i] = p_SDL_JoystickAttachVirtualEx(&d);
        if (vjoy_ids[i] < 0) {
            LOGE("evshim: P%d SDL attach failed: %s\n", i, p_SDL_GetError());
            munmap(shm[i], g_shm_map_size);
            shm[i] = NULL;
            continue;
        }

        LOGD("evshim: P%d virtual joystick id=%d ready\n", i, vjoy_ids[i]);

        pthread_t tid;
        pthread_create(&tid, NULL, vjoy_updater, (void *)(intptr_t)i);
        pthread_detach(tid);
    }
}

__attribute__((constructor))
static void initialize_all_pads(void)
{
    g_is_wine = getenv("EVSHIM_WINE") != NULL;

    const char *dbg = getenv("EVSHIM_DEBUG");
    g_debug_enabled = dbg && strchr("1yY", *dbg);

    int players = 1;
    const char *ep = getenv("EVSHIM_MAX_PLAYERS");
    if (ep) players = atoi(ep);
    if (players > MAX_GAMEPADS) players = MAX_GAMEPADS;

    setup_shm(players);

    if (g_is_wine) {
        LOGI("evshim: Wine process init (%d player(s))\n", players);
        initialize_wine(players);
    } else {
        ALOGI("evshim: Java process init (%d player(s))\n", players);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_notifyStateChanged(JNIEnv *env, jclass cls, jint idx)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return;

    atomic_thread_fence(memory_order_seq_cst); // not sure if necessary
    atomic_fetch_add_explicit(&shm[idx]->seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->seq, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jint JNICALL
Java_com_winlator_winhandler_WinHandler_waitForRumble(JNIEnv *env, jclass cls, jint idx, jint last_seq)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return last_seq;

    uint32_t current_seq = atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);

    if (current_seq != (uint32_t)last_seq) {
        return current_seq;
    }

    // sleep until Wine triggers OnRumble or teardown signaled
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAIT, current_seq, NULL, NULL, 0);

    return atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_rumbleTeardown(JNIEnv *env, jclass cls, jint idx)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return;
    atomic_fetch_add_explicit(&shm[idx]->rumble_seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}
