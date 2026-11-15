// libgncounters: GPU active-cycle sampling on Arm Mali / Immortalis through libGPUCounters,
// exposed to app.gamenative.powercontrol.metrics.MaliGpuCounters over JNI.
//
// Built without exceptions or RTTI; every entry point returns a neutral value on failure
// (false / null / "none") and never throws into the JVM.
//
// nativeSample() returns [utilPct, gpuClkMhz, activeCyclesPerSec, intervalSec] for the
// interval since the previous call (the first call after nativeOpen() returns null):
//   gpuClkMhz  = sample_metadata.gpu_cycle / interval, when the kernel reports GPU cycles
//                (vinstr on Pixel 7 does, kinstr_prfcnt on MT6878 reports 0); NaN otherwise.
//   utilPct    = 100 * MaliGPUActiveCy / gpu_cycle when gpuClkMhz is known; NaN otherwise
//                (the Kotlin side then divides by a peak-clock estimate).
// The library's *Util counters (MaliFragUtil, MaliCoreUtil, ...) are shares of GPU-active
// time, not GPU busy (they read ~98 % on an idle GPU), so they are not used.
#include <jni.h>
#include <android/log.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <time.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <hwcpipe/counter_database.hpp>
#include <hwcpipe/gpu.hpp>
#include <hwcpipe/sampler.hpp>

#include <device/handle.hpp>
#include <device/instance.hpp>
#include <device/hwcnt/backend_type.hpp>
#include <device/hwcnt/reader.hpp>
#include <device/hwcnt/sample.hpp>
#include <device/hwcnt/sampler/manual.hpp>

#define TAG "GNCounters"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace hd = hwcpipe::device;

static std::atomic<int> g_lib_log_budget{40};

extern "C" void gn_hwc_log(int level, const char *fmt, ...) {
    if (g_lib_log_budget.fetch_sub(1) <= 0) return;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_print(level ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, TAG, "lib: %s", buf);
}

namespace {

// Metadata of the last sample read by the hwcpipe sampler (the frontend drops it).
struct last_meta {
    bool valid = false;
    uint64_t begin_ns = 0;
    uint64_t end_ns = 0;
    uint64_t gpu_cycle = 0;
};
last_meta g_meta;

class gn_sample : public hd::hwcnt::sample {
  public:
    gn_sample(hd::hwcnt::reader &reader, std::error_code &ec)
        : hd::hwcnt::sample(reader, ec) {
        if (!ec) {
            const auto &m = get_metadata();
            g_meta.valid = true;
            g_meta.begin_ns = m.timestamp_ns_begin;
            g_meta.end_ns = m.timestamp_ns_end;
            g_meta.gpu_cycle = m.gpu_cycle;
        }
    }
};

struct gn_policy {
    using handle_type = hd::handle;
    using instance_type = hd::instance;
    using sampler_type = hd::hwcnt::sampler::manual;
    using sample_type = gn_sample;
};

struct state {
    hwcpipe::gpu gpu;
    std::unique_ptr<hwcpipe::sampler_config> cfg;
    std::unique_ptr<hwcpipe::sampler<gn_policy>> smp;
    bool primed = false;
    uint64_t last_wall_ns = 0;
    int samples = 0;
};

std::mutex g_lock;
state *g_state = nullptr;
std::string g_backend = "none";

uint64_t now_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

void close_locked() {
    if (!g_state) return;
    if (g_state->smp) {
        auto ec = g_state->smp->stop_sampling();
        (void)ec;
    }
    delete g_state;
    g_state = nullptr;
}

// Backend name from a short-lived device instance (vinstr, kinstr_prfcnt, ...).
std::string probe_backend() {
    auto handle = hd::handle::create(0u);
    if (!handle) return "none";
    std::error_code ec;
    auto inst = hd::instance::create(*handle, ec);
    if (ec || !inst) return "none";
    auto clocks = inst->get_hwcnt_clock_extents();
    std::string name = hd::hwcnt::backend_type_name(hd::hwcnt::get_backend_type(*inst));
    LOGI("device backend=%s gpu_cycle_feature=%d", name.c_str(), clocks.has_gpu_cycle() ? 1 : 0);
    return name;
}

bool open_locked() {
    close_locked();
    struct stat st;
    if (stat("/dev/mali0", &st) != 0) {
        LOGI("open: /dev/mali0 absent");
        return false;
    }
    g_backend = probe_backend();
    if (g_backend == "none") {
        LOGW("open: device instance unavailable");
        return false;
    }
    std::unique_ptr<state> s(new state());
    s->gpu = hwcpipe::gpu(0);
    if (!s->gpu) {
        LOGW("open: hwcpipe gpu(0) invalid");
        return false;
    }
    s->cfg.reset(new hwcpipe::sampler_config(s->gpu));
    auto ec = s->cfg->add_counter(MaliGPUActiveCy);
    if (ec) {
        LOGW("open: MaliGPUActiveCy unsupported (%s)", ec.message().c_str());
        return false;
    }
    s->smp.reset(new hwcpipe::sampler<gn_policy>(*s->cfg));
    if (!*s->smp) {
        LOGW("open: sampler creation failed backend=%s", g_backend.c_str());
        return false;
    }
    ec = s->smp->start_sampling();
    if (ec) {
        LOGW("open: start_sampling failed (%s)", ec.message().c_str());
        return false;
    }
    LOGI("open: ok backend=%s product=%s cores=%llu", g_backend.c_str(),
         hd::product_id_name(s->gpu.get_product_id()).c_str(), (unsigned long long)s->gpu.num_shader_cores());
    g_state = s.release();
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_nativeOpen(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g_lock);
    return open_locked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_nativeSample(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lk(g_lock);
    state *s = g_state;
    if (!s || !s->smp) return nullptr;

    uint64_t wall = now_ns();
    g_meta.valid = false;
    auto ec = s->smp->sample_now();
    double wall_dt = s->last_wall_ns ? (wall - s->last_wall_ns) / 1e9 : 0.0;
    s->last_wall_ns = wall;
    if (ec) {
        if (s->samples < 3) LOGW("sample failed (%s)", ec.message().c_str());
        return nullptr;
    }
    if (!s->primed) {
        s->primed = true;
        return nullptr;
    }

    hwcpipe::counter_sample cs;
    if (s->smp->get_counter_value(MaliGPUActiveCy, cs)) return nullptr;
    double active = cs.type == hwcpipe::counter_sample::type::uint64 ? (double)cs.value.uint64 : cs.value.float64;
    if (!(active >= 0.0)) return nullptr;

    double dt = wall_dt;
    if (g_meta.valid && g_meta.end_ns > g_meta.begin_ns) dt = (g_meta.end_ns - g_meta.begin_ns) / 1e9;
    if (!(dt > 0.0)) return nullptr;

    float util = NAN;
    float clk = NAN;
    if (g_meta.valid && g_meta.gpu_cycle > 0) {
        double gpu_cycle = (double)g_meta.gpu_cycle;
        double mhz = gpu_cycle / dt / 1e6;
        // Reject undefined values on kernels that do not implement the cycle count.
        if (mhz >= 20.0 && mhz <= 3000.0 && active <= gpu_cycle * 1.05) {
            clk = (float)mhz;
            double pct = 100.0 * active / gpu_cycle;
            util = (float)(pct < 0.0 ? 0.0 : (pct > 100.0 ? 100.0 : pct));
        }
    }
    float out[4] = {util, clk, (float)(active / dt), (float)dt};
    if (s->samples++ == 0) {
        LOGI("first sample util=%.1f clk_mhz=%.0f active_per_s=%.0f dt=%.3f", util, clk, active / dt, dt);
    }
    jfloatArray arr = env->NewFloatArray(4);
    if (!arr) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetFloatArrayRegion(arr, 0, 4, out);
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_nativeBackend(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lk(g_lock);
    jstring s = env->NewStringUTF(g_backend.c_str());
    if (!s) env->ExceptionClear();
    return s;
}

extern "C" JNIEXPORT void JNICALL
Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_nativeClose(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (g_state) LOGI("close: %d samples", g_state->samples);
    close_locked();
}
