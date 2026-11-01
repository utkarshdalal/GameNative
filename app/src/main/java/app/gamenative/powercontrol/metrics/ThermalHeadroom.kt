package app.gamenative.powercontrol.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager as AndroidPowerManager
import android.os.SystemClock

/**
 * Single caller of [AndroidPowerManager.getThermalHeadroom] for the process. The platform
 * returns NaN when it is called again within about a second, so every reader (collector,
 * debug-report sampler, exit report) goes through here and gets the cached result of
 * the last call instead of triggering a second one.
 */
object ThermalHeadroom {
    private const val MIN_INTERVAL_MS = 1_100L
    private const val FORECAST_SECONDS = 10

    private val lock = Any()
    private var lastCallMs = 0L
    private var lastResult: Float? = null

    /** Last finite headroom of the session; null until one is read. */
    @Volatile
    var latest: Float? = null
        private set

    /** Last thermal status read through [status]; null until one is read. */
    @Volatile
    var latestStatus: Int? = null
        private set

    fun reset() {
        synchronized(lock) {
            latest = null
            latestStatus = null
            lastResult = null
            lastCallMs = 0L
        }
    }

    /** Headroom from a fresh call, or the previous call's result when that was under a second ago; null for NaN. */
    fun read(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (lastCallMs != 0L && now - lastCallMs < MIN_INTERVAL_MS) return lastResult
            lastCallMs = now
            val pm = context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager ?: return null
            lastResult = runCatching { pm.getThermalHeadroom(FORECAST_SECONDS) }.getOrNull()?.takeIf { it.isFinite() }
            lastResult?.let { latest = it }
            return lastResult
        }
    }

    fun status(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager ?: return null
        return runCatching { pm.currentThermalStatus }.getOrNull()?.also { latestStatus = it }
    }
}
