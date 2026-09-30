package app.gamenative.data

import com.winlator.container.Container

/** Per-container gyro aiming configuration. */
data class GyroSettings(
    val mode: Int = MODE_DISABLED,
    val lastTarget: Int = MODE_RIGHT_STICK,
    val activationMode: Int = ACTIVATION_ALWAYS,
    val sensitivity: Float = 1f,
    val verticalScale: Float = 1f,
    val steadyingDegreesPerSecond: Float = 1f,
    val smoothingMilliseconds: Float = 0f,
    val stickAntiDeadzone: Float = 0f,
    val tiltSteeringEnabled: Boolean = false,
    val tiltFullScaleDegrees: Float = DEFAULT_TILT_FULL_SCALE_DEGREES,
    val tiltDeadzoneDegrees: Float = DEFAULT_TILT_DEADZONE_DEGREES,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    fun normalized(): GyroSettings {
        val normalizedMode = mode.takeIf { it in MODE_DISABLED..MODE_MOUSE } ?: MODE_DISABLED
        val normalizedLastTarget = lastTarget.takeIf { it in MODE_LEFT_STICK..MODE_MOUSE } ?: MODE_RIGHT_STICK
        val normalizedTiltFullScale = tiltFullScaleDegrees
            .finiteOr(DEFAULT_TILT_FULL_SCALE_DEGREES)
            .coerceIn(MIN_TILT_FULL_SCALE_DEGREES, MAX_TILT_FULL_SCALE_DEGREES)
        val maximumTiltDeadzone = minOf(
            MAX_TILT_DEADZONE_DEGREES,
            normalizedTiltFullScale - MIN_TILT_ACTIVE_RANGE_DEGREES,
        )
        return copy(
            mode = normalizedMode,
            lastTarget = if (normalizedMode == MODE_DISABLED) normalizedLastTarget else normalizedMode,
            activationMode = activationMode.takeIf { it in ACTIVATION_ALWAYS..ACTIVATION_RATCHET }
                ?: ACTIVATION_ALWAYS,
            sensitivity = sensitivity.finiteOr(1f).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
            verticalScale = verticalScale.finiteOr(1f).coerceIn(MIN_VERTICAL_SCALE, MAX_VERTICAL_SCALE),
            steadyingDegreesPerSecond = steadyingDegreesPerSecond.finiteOr(1f).coerceIn(0f, MAX_STEADYING_DPS),
            smoothingMilliseconds = smoothingMilliseconds.finiteOr(0f).coerceIn(0f, MAX_SMOOTHING_MS),
            stickAntiDeadzone = stickAntiDeadzone.finiteOr(0f).coerceIn(0f, MAX_STICK_ANTI_DEADZONE),
            tiltFullScaleDegrees = normalizedTiltFullScale,
            tiltDeadzoneDegrees = tiltDeadzoneDegrees
                .finiteOr(DEFAULT_TILT_DEADZONE_DEGREES)
                .coerceIn(0f, maximumTiltDeadzone),
        )
    }

    fun saveTo(container: Container) {
        val value = normalized()
        container.putExtra(EXTRA_MODE, value.mode)
        container.putExtra(EXTRA_LAST_TARGET, value.lastTarget)
        container.putExtra(EXTRA_ACTIVATION, value.activationMode)
        container.putExtra(EXTRA_SENSITIVITY, value.sensitivity)
        container.putExtra(EXTRA_VERTICAL_SCALE, value.verticalScale)
        container.putExtra(EXTRA_STEADYING, value.steadyingDegreesPerSecond)
        container.putExtra(EXTRA_SMOOTHING, value.smoothingMilliseconds)
        container.putExtra(EXTRA_STICK_ANTI_DEADZONE, value.stickAntiDeadzone)
        container.putExtra(EXTRA_TILT_STEERING, value.tiltSteeringEnabled)
        container.putExtra(EXTRA_TILT_FULL_SCALE, value.tiltFullScaleDegrees)
        container.putExtra(EXTRA_TILT_DEADZONE, value.tiltDeadzoneDegrees)
        container.putExtra(EXTRA_INVERT_X, value.invertX)
        container.putExtra(EXTRA_INVERT_Y, value.invertY)
        container.saveData()
    }

    companion object {
        const val MODE_DISABLED = 0
        const val MODE_LEFT_STICK = 1
        const val MODE_RIGHT_STICK = 2
        const val MODE_MOUSE = 3

        const val ACTIVATION_ALWAYS = 0
        const val ACTIVATION_HOLD = 1
        const val ACTIVATION_TOGGLE = 2
        const val ACTIVATION_RATCHET = 3

        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 4f
        const val MIN_VERTICAL_SCALE = 0.1f
        const val MAX_VERTICAL_SCALE = 2f
        const val MAX_STEADYING_DPS = 10f
        const val MAX_SMOOTHING_MS = 200f
        const val MAX_STICK_ANTI_DEADZONE = 0.5f
        const val MIN_TILT_FULL_SCALE_DEGREES = 10f
        const val MAX_TILT_FULL_SCALE_DEGREES = 60f
        const val DEFAULT_TILT_FULL_SCALE_DEGREES = 30f
        const val MAX_TILT_DEADZONE_DEGREES = 10f
        const val DEFAULT_TILT_DEADZONE_DEGREES = 2f
        const val MIN_TILT_ACTIVE_RANGE_DEGREES = 1f

        private const val EXTRA_MODE = "gyroMode"
        private const val EXTRA_LAST_TARGET = "gyroLastTarget"
        private const val EXTRA_ACTIVATION = "gyroActivation"
        private const val EXTRA_SENSITIVITY = "gyroSensitivity"
        private const val EXTRA_VERTICAL_SCALE = "gyroVerticalScale"
        private const val EXTRA_STEADYING = "gyroSteadyingDps"
        private const val EXTRA_SMOOTHING = "gyroSmoothingMs"
        private const val EXTRA_STICK_ANTI_DEADZONE = "gyroStickAntiDeadzone"
        private const val EXTRA_TILT_STEERING = "gyroTiltSteering"
        private const val EXTRA_TILT_FULL_SCALE = "gyroTiltFullScaleDegrees"
        private const val EXTRA_TILT_DEADZONE = "gyroTiltDeadzoneDegrees"
        private const val EXTRA_INVERT_X = "gyroInvertX"
        private const val EXTRA_INVERT_Y = "gyroInvertY"

        @JvmStatic
        fun fromContainer(container: Container): GyroSettings {
            fun intExtra(name: String, fallback: Int) =
                container.getExtra(name, fallback.toString()).toIntOrNull() ?: fallback
            fun floatExtra(name: String, fallback: Float) =
                container.getExtra(name, fallback.toString()).toFloatOrNull() ?: fallback
            fun booleanExtra(name: String, fallback: Boolean) =
                container.getExtra(name, fallback.toString()).toBooleanStrictOrNull() ?: fallback

            val mode = intExtra(EXTRA_MODE, MODE_DISABLED)
            val sensitivity = floatExtra(EXTRA_SENSITIVITY, 1f)
            val tiltSteeringEnabled = booleanExtra(EXTRA_TILT_STEERING, false)
            return GyroSettings(
                mode = mode,
                lastTarget = intExtra(
                    EXTRA_LAST_TARGET,
                    mode.takeIf { it in MODE_LEFT_STICK..MODE_MOUSE } ?: MODE_RIGHT_STICK,
                ),
                activationMode = intExtra(EXTRA_ACTIVATION, ACTIVATION_ALWAYS),
                sensitivity = sensitivity,
                verticalScale = floatExtra(EXTRA_VERTICAL_SCALE, 1f),
                steadyingDegreesPerSecond = floatExtra(EXTRA_STEADYING, 1f),
                smoothingMilliseconds = floatExtra(EXTRA_SMOOTHING, 0f),
                stickAntiDeadzone = floatExtra(EXTRA_STICK_ANTI_DEADZONE, 0f),
                tiltSteeringEnabled = tiltSteeringEnabled,
                tiltFullScaleDegrees = floatExtra(EXTRA_TILT_FULL_SCALE, DEFAULT_TILT_FULL_SCALE_DEGREES),
                tiltDeadzoneDegrees = floatExtra(EXTRA_TILT_DEADZONE, DEFAULT_TILT_DEADZONE_DEGREES),
                invertX = booleanExtra(EXTRA_INVERT_X, false),
                invertY = booleanExtra(EXTRA_INVERT_Y, false),
            ).normalized()
        }

        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
    }
}
