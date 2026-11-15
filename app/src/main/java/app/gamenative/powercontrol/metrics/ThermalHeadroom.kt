package app.gamenative.powercontrol.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager as AndroidPowerManager
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single caller of [AndroidPowerManager.getThermalHeadroom] for the process. The platform
 * returns NaN when it is called again within about a second, so every reader (collector,
 * debug-report sampler, exit report) goes through here and gets the cached result of
 * the last call instead of triggering a second one.
 *
 * Also the single source of thermal status. Where the platform's thermal service reports
 * nothing (no finite headroom, status never above NONE), as on handhelds whose thermal HAL
 * never comes up, the status comes from the kernel's cooling devices ([KernelThermal]) instead.
 */
object ThermalHeadroom {
    private const val MIN_INTERVAL_MS = 1_100L
    private const val FORECAST_SECONDS = 10

    const val SOURCE_PLATFORM = "platform"
    const val SOURCE_KERNEL = "kernel"

    private val lock = Any()
    private var lastCallMs = 0L
    private var lastResult: Float? = null

    /** True once the platform gave a finite headroom or a status above NONE this session. */
    @Volatile
    private var platformReports = false

    /** Last kernel-derived status handed to [kernelListeners], to notify only on a change. */
    private var lastNotifiedKernelStatus: Int? = null

    private val kernelListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    /** Last finite headroom of the session; null until one is read. */
    @Volatile
    var latest: Float? = null
        private set

    /** Last thermal status read through [status]; null until one is read. */
    @Volatile
    var latestStatus: Int? = null
        private set

    /** Where [latestStatus] came from: [SOURCE_PLATFORM] or [SOURCE_KERNEL]; null until a status is read. */
    @Volatile
    var latestSource: String? = null
        private set

    fun reset() {
        synchronized(lock) {
            latest = null
            latestStatus = null
            latestSource = null
            lastResult = null
            lastCallMs = 0L
            platformReports = false
            lastNotifiedKernelStatus = null
        }
    }

    /**
     * Headroom from a fresh call, or the previous call's result when that was under a second ago; null for NaN.
     * With [publish] false the result does not update [latest].
     */
    fun read(context: Context, publish: Boolean = true): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (lastCallMs == 0L || now - lastCallMs >= MIN_INTERVAL_MS) {
                lastCallMs = now
                val pm = context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager ?: return null
                lastResult = runCatching { pm.getThermalHeadroom(FORECAST_SECONDS) }.getOrNull()?.takeIf { it.isFinite() }
                if (lastResult != null) platformReports = true
            }
            if (publish) lastResult?.let { latest = it }
            return lastResult
        }
    }

    /**
     * Current thermal status; with [publish] false it does not update [latestStatus] or [latestSource].
     * Kernel-derived readings reach the [addKernelStatusListener] listeners whenever they change, published or not.
     */
    fun status(context: Context, publish: Boolean = true): Int? {
        val platform = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager)
                ?.let { pm -> runCatching { pm.currentThermalStatus }.getOrNull() }
        } else {
            null
        }
        if (platform != null && platform > KernelThermal.STATUS_NONE) platformReports = true
        val kernel = if (platformReports) null else runCatching { KernelThermal.read() }.getOrNull()
        val (status, source) = combine(platform, kernel, platformReports)
        if (status != null && source == SOURCE_KERNEL) notifyKernel(status)
        if (publish && status != null) {
            latestStatus = status
            latestSource = source
        }
        return status
    }

    /**
     * The status to report and its source. The platform wins once it has shown it reports this session;
     * until then the kernel's status, when readable, stands in for (or raises) the platform's.
     */
    internal fun combine(platform: Int?, kernel: Int?, platformReports: Boolean): Pair<Int?, String?> = when {
        platformReports || kernel == null -> platform to (platform?.let { SOURCE_PLATFORM })
        else -> maxOf(platform ?: KernelThermal.STATUS_NONE, kernel) to SOURCE_KERNEL
    }

    /** Called with each new kernel-derived status, for consumers that would otherwise rely on the platform's listener. */
    fun addKernelStatusListener(listener: (Int) -> Unit) {
        kernelListeners += listener
    }

    fun removeKernelStatusListener(listener: (Int) -> Unit) {
        kernelListeners -= listener
    }

    private fun notifyKernel(status: Int) {
        val changed = synchronized(lock) {
            (lastNotifiedKernelStatus != status).also { if (it) lastNotifiedKernelStatus = status }
        }
        if (!changed) return
        for (listener in kernelListeners) runCatching { listener(status) }
    }
}
