package app.gamenative.powercontrol.metrics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.winlator.core.GPUInformation
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * GPU usage from Arm Mali / Immortalis hardware counters (libgncounters over Arm libGPUCounters),
 * for devices whose sysfs GPU nodes are unreadable to apps (Tensor, MediaTek).
 *
 * Used by [GpuUsageSampler] as a fallback after the sysfs nodes. Lives only while the metrics
 * collector runs: [attach] at collector start, [close] at collector stop; [sample] returns null
 * outside that window, so the HUD and debug samplers never open the counters on their own.
 *
 * Utilization, see [MaliUtilEstimator]:
 * - kernel reports GPU cycles (vinstr on Pixel 7): 100 * active cycles / GPU cycles, i.e. busy
 *   share of clocked time; the clock is GPU cycles per second.
 * - no cycle count (kinstr_prfcnt on MT6878): active cycles per second over the session's peak
 *   active rate (floor [MaliUtilEstimator.CLOCK_FLOOR_MHZ]), an estimate relative to the top clock.
 * The library's *Util counters are shares of GPU-active time (~98 % on an idle GPU) and are not used.
 */
object MaliGpuCounters {
    private const val TAG = "GNCounters"
    private const val SOURCE = "mali/counters"
    private const val MIN_NATIVE_INTERVAL_MS = 400L
    private const val LOG_EVERY_N = 20
    private const val OPEN_MARKER = "gncounters.opening"
    private const val PREFS = "gncounters"
    private const val PREF_DISABLED = "disabled_after_crash"

    @Volatile
    private var context: Context? = null

    /** null until decided, then cached for the process: gate passed, library loaded and counters opened once. */
    @Volatile
    private var available: Boolean? = null

    private var open = false
    private var lastNativeMs = 0L
    private var lastReading: GpuUsageReading? = null
    private var sampleCount = 0
    private val estimator = MaliUtilEstimator()

    /** Counter backend of the last opened sampler, e.g. `vinstr` or `kinstr_prfcnt`; null when never opened. */
    @Volatile
    var backend: String? = null
        private set

    /** GPU clock of the latest sample in MHz, null when the backend reports no cycle count. */
    @Volatile
    var lastClockMhz: Float? = null
        private set

    @Synchronized
    fun attach(context: Context) {
        this.context = context.applicationContext
        estimator.reset()
        lastReading = null
        lastClockMhz = null
        lastNativeMs = 0L
        sampleCount = 0
    }

    @Synchronized
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ctx = context ?: return false
        val renderer = runCatching { GPUInformation.getRenderer(ctx) }.getOrNull()
        val allowed = gateAllows(renderer, Build.HARDWARE, File("/dev/mali0").exists())
        val decided = allowed && !crashGuardTripped(ctx) && loadLibrary() && openNative()
        Timber.tag(TAG).i(
            "available=%s renderer=%s hardware=%s backend=%s",
            decided, renderer ?: "?", Build.HARDWARE, backend ?: "none",
        )
        available = decided
        return decided
    }

    @Synchronized
    fun sample(): GpuUsageReading? {
        if (context == null || !isAvailable()) return null
        if (!open && !openNative()) return null
        val now = SystemClock.elapsedRealtime()
        if (lastNativeMs != 0L && now - lastNativeMs < MIN_NATIVE_INTERVAL_MS) return lastReading
        lastNativeMs = now
        val raw = try {
            nativeSample()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "nativeSample failed")
            null
        } ?: return lastReading
        val pct = estimator.update(raw) ?: return lastReading
        lastClockMhz = raw.getOrNull(1)?.takeIf { it.isFinite() && it > 0f }
        val reading = GpuUsageReading(pct, SOURCE)
        lastReading = reading
        if (sampleCount++ % LOG_EVERY_N == 0) {
            Timber.tag(TAG).i(
                "util=%d%% clk=%s active=%.0fMHz peak=%.0fMHz backend=%s",
                pct,
                lastClockMhz?.let { String.format(Locale.US, "%.0fMHz", it) } ?: "-",
                raw[2] / 1e6f,
                estimator.peakActiveMhz,
                backend ?: "?",
            )
        }
        return reading
    }

    /** Stops the native sampler; called when the metrics collector stops. */
    @Synchronized
    fun close() {
        if (open) {
            try {
                nativeClose()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "nativeClose failed")
            }
            open = false
        }
        context = null
        lastReading = null
        lastClockMhz = null
    }

    private fun openNative(): Boolean {
        val marker = context?.let { File(it.filesDir, OPEN_MARKER) }
        runCatching { marker?.createNewFile() }
        open = try {
            nativeOpen().also { backend = nativeBackend().takeIf { b -> b.isNotEmpty() && b != "none" } }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "nativeOpen failed")
            false
        }
        runCatching { marker?.delete() }
        return open
    }

    /**
     * Crash guard: [openNative] writes [OPEN_MARKER] before nativeOpen and deletes it after the call
     * returns. A marker left over from an earlier process means that open took the process down, so
     * the counters are disabled for good on this device and libgncounters is never loaded again.
     */
    private fun crashGuardTripped(ctx: Context): Boolean {
        val prefs = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        if (prefs?.getBoolean(PREF_DISABLED, false) == true) {
            Timber.tag(TAG).i("disabled: an earlier nativeOpen crashed the process")
            return true
        }
        val marker = File(ctx.filesDir, OPEN_MARKER)
        if (!marker.exists()) return false
        Timber.tag(TAG).w("previous nativeOpen did not return; disabling Mali counters on this device")
        runCatching { prefs?.edit()?.putBoolean(PREF_DISABLED, true)?.commit() }
        runCatching { marker.delete() }
        return true
    }

    private var libraryLoaded: Boolean? = null

    private fun loadLibrary(): Boolean {
        libraryLoaded?.let { return it }
        val loaded = try {
            System.loadLibrary("gncounters")
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "libgncounters unavailable")
            false
        }
        libraryLoaded = loaded
        return loaded
    }

    /**
     * Counters are only touched on Mali / Immortalis with a /dev/mali0 node, never on Adreno,
     * PowerVR or Xclipse. Build.HARDWARE is a fallback for an unknown renderer (it names the SoC
     * on most devices, e.g. `gs201`, `mt6878`).
     */
    internal fun gateAllows(renderer: String?, hardware: String?, devMaliExists: Boolean): Boolean {
        if (!devMaliExists) return false
        val r = renderer.orEmpty().lowercase(Locale.US)
        if (listOf("adreno", "powervr", "xclipse").any { r.contains(it) }) return false
        if (r.contains("mali") || r.contains("immortalis")) return true
        return r.isBlank() && hardware.orEmpty().lowercase(Locale.US).contains("mali")
    }

    @JvmStatic private external fun nativeOpen(): Boolean

    /** [utilPct or NaN, gpuClkMhz or NaN, activeCyclesPerSec, intervalSec]; null on the first call or failure. */
    @JvmStatic private external fun nativeSample(): FloatArray?

    @JvmStatic private external fun nativeBackend(): String

    @JvmStatic private external fun nativeClose()
}

/**
 * Turns libgncounters samples into a GPU usage percentage.
 *
 * With a GPU cycle count the native utilization (active / GPU cycles) is used as is. Without one,
 * active cycles per second are divided by the highest active rate seen this session, which tracks
 * the top GPU clock once the game has loaded the GPU fully; [CLOCK_FLOOR_MHZ] keeps early, light
 * samples from reading as 100 %.
 */
internal class MaliUtilEstimator {
    var peakActiveMhz = 0f
        private set

    fun reset() {
        peakActiveMhz = 0f
    }

    fun update(raw: FloatArray): Int? {
        if (raw.size < 3) return null
        val dt = raw.getOrNull(3)
        // Intervals spanning a collector pause average over the pause; drop them.
        if (dt != null && dt.isFinite() && (dt < MIN_INTERVAL_SEC || dt > MAX_INTERVAL_SEC)) return null
        val activeMhz = raw[2] / 1e6f
        if (!activeMhz.isFinite() || activeMhz < 0f) return null
        if (activeMhz <= MAX_PLAUSIBLE_MHZ) peakActiveMhz = maxOf(peakActiveMhz, activeMhz)
        val util = raw[0]
        if (util.isFinite()) return util.coerceIn(0f, 100f).roundToInt()
        val reference = maxOf(peakActiveMhz, CLOCK_FLOOR_MHZ)
        return (activeMhz * 100f / reference).coerceIn(0f, 100f).roundToInt()
    }

    companion object {
        const val CLOCK_FLOOR_MHZ = 500f
        const val MAX_PLAUSIBLE_MHZ = 3000f
        const val MIN_INTERVAL_SEC = 0.2f
        const val MAX_INTERVAL_SEC = 2.5f
    }
}

/**
 * Current GPU clock for telemetry: the Mali counter clock while counters feed GPU usage,
 * else Exynos `/sys/kernel/gpu/gpu_clock` (kHz on recent kernels, MHz on older ones).
 */
internal object GpuClock {
    private const val EXYNOS_GPU_CLOCK = "/sys/kernel/gpu/gpu_clock"

    @Volatile
    private var exynosReadable: Boolean? = null

    fun currentMhz(): Int? {
        MaliGpuCounters.lastClockMhz?.let { return it.roundToInt() }
        if (exynosReadable == false) return null
        val raw = SystemMetricsSources.readFirstLine(EXYNOS_GPU_CLOCK)
        if (exynosReadable == null) exynosReadable = raw != null
        return raw?.let(::parseExynosClockMhz)
    }

    fun parseExynosClockMhz(raw: String): Int? {
        val value = raw.trim().substringBefore(' ').toLongOrNull() ?: return null
        val mhz = if (value > 10_000L) value / 1000L else value
        return mhz.toInt().takeIf { it in 1..5000 }
    }
}
