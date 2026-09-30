package app.gamenative.powercontrol.autotuning

import android.os.SystemClock
import timber.log.Timber
import kotlin.math.abs

/**
 * Proportional-Integral-Derivative (PID) controller for automatic performance tuning.
 *
 * This controller adjusts CPU/GPU frequencies based on the error between target and actual FPS,
 * providing smooth and responsive performance optimization.
 *
 * @param kp Proportional gain - determines immediate response to error
 * @param ki Integral gain - eliminates steady-state error over time
 * @param kd Derivative gain - dampens oscillations and improves stability
 * @param outputMin Minimum output value (e.g., minimum CPU frequency)
 * @param outputMax Maximum output value (e.g., maximum CPU frequency)
 * @param integralLimit Maximum absolute value for integral term to prevent windup
 * @param tag Tag for logging
 * @param enableLogging Enable verbose logging of PID calculations
 */
class PidController(
    private val kp: Double = 0.5,
    private val ki: Double = 0.1,
    private val kd: Double = 0.05,
    private val outputMin: Double,
    private val outputMax: Double,
    private val integralLimit: Double = 100.0,
    private val tag: String = "PidController",
    private val enableLogging: Boolean = false
) {
    private var integral: Double = 0.0
    private var previousError: Double = 0.0
    private var lastUpdateTime: Long = 0L
    private var isInitialized: Boolean = false

    /**
     * Calculate the control output based on current error.
     *
     * @param setpoint Target value (e.g., target FPS)
     * @param processVariable Current value (e.g., current FPS)
     * @return Control output value clamped between outputMin and outputMax
     */
    fun calculate(setpoint: Double, processVariable: Double): Double {
        val currentTime = SystemClock.elapsedRealtime()

        // Calculate time delta in seconds
        val dt = if (isInitialized && lastUpdateTime > 0) {
            (currentTime - lastUpdateTime) / 1000.0
        } else {
            0.0
        }

        // Calculate error
        val error = setpoint - processVariable

        // Proportional term
        val proportional = kp * error

        // Integral term (with anti-windup)
        if (dt > 0) {
            integral += error * dt
            // Clamp integral to prevent windup
            integral = integral.coerceIn(-integralLimit, integralLimit)
        }
        val integralTerm = ki * integral

        // Derivative term
        val derivative = if (dt > 0 && isInitialized) {
            (error - previousError) / dt
        } else {
            0.0
        }
        val derivativeTerm = kd * derivative

        // Calculate total output
        val output = proportional + integralTerm + derivativeTerm

        // Clamp output to valid range
        val clampedOutput = output.coerceIn(outputMin, outputMax)

        // Update state
        previousError = error
        lastUpdateTime = currentTime
        isInitialized = true

        if (enableLogging) {
            Timber.tag(tag).v(
                "PID: error=%.2f, P=%.2f, I=%.2f, D=%.2f, output=%.2f",
                error, proportional, integralTerm, derivativeTerm, clampedOutput
            )
        }

        return clampedOutput
    }

    /**
     * Reset the controller state.
     * Call this when starting a new tuning session or when the system state changes significantly.
     */
    fun reset() {
        integral = 0.0
        previousError = 0.0
        lastUpdateTime = 0L
        isInitialized = false
        if (enableLogging) {
            Timber.tag(tag).d("PID controller reset")
        }
    }
}
