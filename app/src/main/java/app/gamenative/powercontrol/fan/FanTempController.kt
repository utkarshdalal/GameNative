package app.gamenative.powercontrol.fan

import kotlin.math.roundToInt

/**
 * Tuning constants of the closed-loop fan controller.
 */
object FanTuning {
    const val KP_PERCENT_PER_C = 4.0
    const val KI_PERCENT_PER_C_SECOND = 0.15
    const val TARGET_TEMP_C = 78.0
    const val FLOOR_PERCENT = 20.0
    const val CEILING_PERCENT = 100.0
    const val OVERRIDE_TEMP_C = 90.0
    const val SLEW_PERCENT_PER_SECOND = 10.0
}

/**
 * Last value the fan loop applied, published for logging by other components.
 */
data class FanSample(
    val appliedPercent: Int,
    val tempC: Int,
)

/**
 * PI controller driving the fan duty from the hottest sensor reading.
 *
 * Output is `floor + Kp * error + Ki * integral`, with conditional integration so the
 * integral only accumulates while the output is not pushed further into a rail, and a
 * slew limit on the applied percentage. At or above [FanTuning.OVERRIDE_TEMP_C] the
 * controller snaps to full speed and drops the integral. Has no Android or sysfs
 * dependency, so it can be exercised on the JVM.
 */
class FanTempController {

    var integralCSeconds: Double = 0.0
        private set

    var rawPercent: Double = FanTuning.FLOOR_PERCENT
        private set

    var appliedPercent: Double = FanTuning.FLOOR_PERCENT
        private set

    var overrideEngaged: Boolean = false
        private set

    val rawIntegerPercent: Int
        get() = rawPercent.roundToInt()

    val appliedIntegerPercent: Int
        get() = appliedPercent.roundToInt()

    fun reset() {
        integralCSeconds = 0.0
        rawPercent = FanTuning.FLOOR_PERCENT
        appliedPercent = FanTuning.FLOOR_PERCENT
        overrideEngaged = false
    }

    /**
     * @return the fan percentage to apply after this sample
     */
    fun update(tempC: Double, dtSeconds: Double): Int {
        if (tempC >= FanTuning.OVERRIDE_TEMP_C) {
            integralCSeconds = 0.0
            overrideEngaged = true
            rawPercent = FanTuning.CEILING_PERCENT
            appliedPercent = FanTuning.CEILING_PERCENT
            return appliedIntegerPercent
        }
        overrideEngaged = false

        val error = tempC - FanTuning.TARGET_TEMP_C
        val provisionalIntegral = integralCSeconds + error * dtSeconds
        val provisionalOutput = output(error, provisionalIntegral)

        val windsUp = (provisionalOutput > FanTuning.CEILING_PERCENT && error > 0.0) ||
            (provisionalOutput < FanTuning.FLOOR_PERCENT && error < 0.0)
        if (!windsUp) {
            integralCSeconds = provisionalIntegral
        }

        rawPercent = output(error, integralCSeconds)
            .coerceIn(FanTuning.FLOOR_PERCENT, FanTuning.CEILING_PERCENT)

        val maxDelta = FanTuning.SLEW_PERCENT_PER_SECOND * dtSeconds
        appliedPercent = (appliedPercent + (rawPercent - appliedPercent).coerceIn(-maxDelta, maxDelta))
            .coerceIn(FanTuning.FLOOR_PERCENT, FanTuning.CEILING_PERCENT)

        return appliedIntegerPercent
    }

    private fun output(error: Double, integral: Double): Double {
        return FanTuning.FLOOR_PERCENT +
            FanTuning.KP_PERCENT_PER_C * error +
            FanTuning.KI_PERCENT_PER_C_SECOND * integral
    }
}
