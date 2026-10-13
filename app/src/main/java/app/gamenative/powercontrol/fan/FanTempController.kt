package app.gamenative.powercontrol.fan

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tuning constants of the closed-loop fan controller.
 */
object FanTuning {
    const val KI_PERCENT_PER_C_SECOND = 0.25
    const val TARGET_TEMP_C = 78.0
    const val FLOOR_PERCENT = 20.0
    const val CEILING_PERCENT = 100.0
    const val OVERRIDE_TEMP_C = 90.0

    /**
     * Temperature at which the feedforward ramp leaves the floor. Below it the fan idles.
     */
    const val RAMP_START_TEMP_C = 60.0

    /**
     * Duty the feedforward ramp reaches at [TARGET_TEMP_C]. The PI term only trims around it.
     */
    const val TARGET_PERCENT = 60.0

    const val SLEW_UP_PERCENT_PER_SECOND = 3.0
    const val SLEW_DOWN_PERCENT_PER_SECOND = 1.5

    /**
     * The hottest sensor reads in whole degrees, so a raw sample moves the output in
     * visible jumps. Samples are low-passed before they reach the controller.
     */
    const val TEMP_EMA_ALPHA = 0.25

    /**
     * Applied duty is emitted on this grid, and only after the underlying value has moved
     * clearly past the midpoint of the current step, so a hovering temperature cannot make
     * the fan hunt between neighbouring speeds.
     */
    const val STEP_PERCENT = 5
    const val STEP_HYSTERESIS_FRACTION = 0.75
}

/**
 * Last value the fan loop applied, published for logging by other components.
 */
data class FanSample(
    val appliedPercent: Int,
    val tempC: Int,
)

/**
 * Temperature-scheduled fan curve with an integral trim, driven by the hottest sensor reading.
 *
 * The duty a temperature deserves is known up front, so it comes from a piecewise-linear
 * ramp: floor below [FanTuning.RAMP_START_TEMP_C], [FanTuning.TARGET_PERCENT] at the
 * target, ceiling at [FanTuning.OVERRIDE_TEMP_C]. The fan therefore starts moving long
 * before the target instead of sitting at the floor until the target is crossed. The ramp
 * slope is the proportional action, so there is no separate `Kp` to fight it; the integral
 * adds the extra duty the ramp cannot hold and only builds while the temperature is over
 * the target, releasing as it cools.
 *
 * Sensor samples are low-passed and the applied duty is emitted on a coarse grid with
 * hysteresis, because a raw whole-degree reading that hovers around one value would
 * otherwise make the fan hunt every tick. The [FanTuning.OVERRIDE_TEMP_C] cutout reads the
 * unsmoothed sample so that filtering cannot delay it, snapping to full speed and dropping
 * the integral. Has no Android or sysfs dependency, so it can be exercised on the JVM.
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

    /**
     * Low-passed sensor temperature the controller is acting on, or null before the first sample.
     */
    var smoothedTempC: Double? = null
        private set

    private var emittedPercent: Int = FanTuning.FLOOR_PERCENT.roundToInt()

    val rawIntegerPercent: Int
        get() = rawPercent.roundToInt()

    val appliedIntegerPercent: Int
        get() = emittedPercent

    fun reset() {
        integralCSeconds = 0.0
        rawPercent = FanTuning.FLOOR_PERCENT
        appliedPercent = FanTuning.FLOOR_PERCENT
        overrideEngaged = false
        smoothedTempC = null
        emittedPercent = FanTuning.FLOOR_PERCENT.roundToInt()
    }

    /**
     * @return the fan percentage to apply after this sample
     */
    fun update(tempC: Double, dtSeconds: Double): Int {
        val smoothed = smoothedTempC
            ?.let { it + FanTuning.TEMP_EMA_ALPHA * (tempC - it) }
            ?: tempC
        smoothedTempC = smoothed

        if (tempC >= FanTuning.OVERRIDE_TEMP_C || smoothed >= FanTuning.OVERRIDE_TEMP_C) {
            integralCSeconds = 0.0
            overrideEngaged = true
            rawPercent = FanTuning.CEILING_PERCENT
            appliedPercent = FanTuning.CEILING_PERCENT
            emittedPercent = FanTuning.CEILING_PERCENT.roundToInt()
            return emittedPercent
        }
        overrideEngaged = false

        val error = smoothed - FanTuning.TARGET_TEMP_C
        val feedForward = feedForward(smoothed)

        val provisionalIntegral = (integralCSeconds + error * dtSeconds).coerceAtLeast(0.0)
        if (output(feedForward, provisionalIntegral) <= FanTuning.CEILING_PERCENT || error < 0.0) {
            integralCSeconds = provisionalIntegral
        }

        rawPercent = output(feedForward, integralCSeconds)
            .coerceIn(FanTuning.FLOOR_PERCENT, FanTuning.CEILING_PERCENT)

        val slewPerSecond = if (rawPercent >= appliedPercent) {
            FanTuning.SLEW_UP_PERCENT_PER_SECOND
        } else {
            FanTuning.SLEW_DOWN_PERCENT_PER_SECOND
        }
        val maxDelta = slewPerSecond * dtSeconds
        appliedPercent = (appliedPercent + (rawPercent - appliedPercent).coerceIn(-maxDelta, maxDelta))
            .coerceIn(FanTuning.FLOOR_PERCENT, FanTuning.CEILING_PERCENT)

        emittedPercent = quantize(appliedPercent, emittedPercent)
        return emittedPercent
    }

    /**
     * Piecewise-linear duty for a temperature: floor up to the ramp start, rising to
     * [FanTuning.TARGET_PERCENT] at the target, then to the ceiling at the override point.
     */
    private fun feedForward(tempC: Double): Double = when {
        tempC <= FanTuning.RAMP_START_TEMP_C -> FanTuning.FLOOR_PERCENT
        tempC <= FanTuning.TARGET_TEMP_C -> interpolate(
            tempC,
            FanTuning.RAMP_START_TEMP_C,
            FanTuning.TARGET_TEMP_C,
            FanTuning.FLOOR_PERCENT,
            FanTuning.TARGET_PERCENT,
        )
        tempC < FanTuning.OVERRIDE_TEMP_C -> interpolate(
            tempC,
            FanTuning.TARGET_TEMP_C,
            FanTuning.OVERRIDE_TEMP_C,
            FanTuning.TARGET_PERCENT,
            FanTuning.CEILING_PERCENT,
        )
        else -> FanTuning.CEILING_PERCENT
    }

    private fun interpolate(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Double {
        return y0 + (x - x0) / (x1 - x0) * (y1 - y0)
    }

    /**
     * Snaps to the [FanTuning.STEP_PERCENT] grid, but keeps [current] until the value has
     * moved most of a step away from it.
     */
    private fun quantize(value: Double, current: Int): Int {
        if (abs(value - current) < FanTuning.STEP_PERCENT * FanTuning.STEP_HYSTERESIS_FRACTION) {
            return current
        }
        val step = FanTuning.STEP_PERCENT
        return ((value / step).roundToInt() * step)
            .coerceIn(FanTuning.FLOOR_PERCENT.roundToInt(), FanTuning.CEILING_PERCENT.roundToInt())
    }

    private fun output(feedForward: Double, integral: Double): Double {
        return feedForward + FanTuning.KI_PERCENT_PER_C_SECOND * integral
    }
}
