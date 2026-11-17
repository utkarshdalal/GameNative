package app.gamenative.powercontrol

/**
 * Thermal-aware frame ceiling for MediaTek Helio G99/G100.
 *
 * Android thermal status: NONE=0, LIGHT=1, MODERATE=2, SEVERE=3,
 * CRITICAL=4, EMERGENCY=5, SHUTDOWN=6.
 * KernelThermal reports cooling-device levels as NONE..SEVERE (0..3).
 *
 * Downward changes are immediate. Recovery is intentionally gradual (5 FPS per 10 seconds)
 * to reduce the chance of repeatedly hitting the thermal limit. Missing telemetry does not
 * claim that the device is cool and therefore does not raise a previously reduced cap.
 *
 * Only the game's FPS cap changes. This never writes kernel nodes, modifies voltage, overclocks,
 * or disables Android's thermal framework.
 */
object HelioThermalPolicy {
    const val MODERATE_FPS_LIMIT = 30
    const val SEVERE_FPS_LIMIT = 24
    const val CRITICAL_FPS_LIMIT = 20
    private const val RECOVERY_STEP_FPS = 5
    private const val SAFE_CYCLES_PER_STEP = 10

    private var currentCap: Int? = null
    private var safeCycles = 0

    @Synchronized
    fun reset() {
        currentCap = null
        safeCycles = 0
    }

    @Synchronized
    fun capForStatus(status: Int?, requestedFps: Int): Int {
        if (requestedFps <= 0) return requestedFps
        val current = currentCap?.coerceAtMost(requestedFps) ?: requestedFps
        if (status == null) {
            currentCap = current
            return current
        }

        val thermalCeiling = when {
            status >= 4 -> CRITICAL_FPS_LIMIT
            status == 3 -> SEVERE_FPS_LIMIT
            status == 2 -> MODERATE_FPS_LIMIT
            else -> requestedFps
        }.coerceAtMost(requestedFps)

        if (thermalCeiling < current) {
            currentCap = thermalCeiling
            safeCycles = 0
            return thermalCeiling
        }

        if (current < thermalCeiling) {
            safeCycles++
            if (safeCycles >= SAFE_CYCLES_PER_STEP) {
                currentCap = minOf(current + RECOVERY_STEP_FPS, thermalCeiling)
                safeCycles = 0
            } else {
                currentCap = current
            }
        } else {
            currentCap = thermalCeiling
            safeCycles = 0
        }
        return currentCap ?: thermalCeiling
    }
}
