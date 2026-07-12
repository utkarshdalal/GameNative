package app.gamenative.data

import org.json.JSONObject
import com.winlator.inputcontrols.Binding
import kotlin.math.max
import kotlin.math.min

/**
 * Per-game shooter mode configuration.
 */
data class ShooterModeConfig(
    val movementType: String = MOVEMENT_GAMEPAD_LEFT_STICK,
    val lookType: String = LOOK_GAMEPAD_RIGHT_STICK,
    val lookSensitivityX: Float = 1.0f,
    val lookSensitivityY: Float = 1.0f,
    val invertLookY: Boolean = false,
    val lookSmoothing: Float = 0.0f,
    val lookDeadzone: Float = 0.0f,
    val mouseAccelerationEnabled: Boolean = false,
    val mouseAccelerationStrength: Float = 1.0f,
    val mouseAccelerationMaxMultiplier: Float = 3.0f,
    val movementJoystickSize: Float = 1.0f,
    val lookJoystickSize: Float = 1.0f,
    val movementJoystickDeadzone: Float = DEFAULT_ANALOG_STICK_DEADZONE,
    val lookJoystickDeadzone: Float = DEFAULT_ANALOG_STICK_DEADZONE,
    val movementStickSensitivity: Float = 1.0f,
    val lookStickSensitivity: Float = 1.0f,
    val movementJoystickBehavior: String = JOYSTICK_ANCHORED,
    val lookJoystickBehavior: String = JOYSTICK_ANCHORED,
    val joystickOpacity: Float = JOYSTICK_OPACITY_UNSET,
    val showRuntimeToggle: Boolean = true,
    val movementZoneSplit: Float = 0.5f,
    val buttonLookThroughEnabled: Boolean = true,
    val buttonLookThroughDragThreshold: Int = DEFAULT_BUTTON_DRAG_THRESHOLD,
    val outerRingSprintEnabled: Boolean = false,
    val outerRingSprintThreshold: Float = 0.85f,
    val outerRingSprintPressMode: Boolean = false,
    val sprintBinding: String = SPRINT_BINDING_SHIFT,
) {
    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_MOVEMENT_TYPE, movementType)
            put(KEY_LOOK_TYPE, lookType)
            put(KEY_LOOK_SENSITIVITY_X, lookSensitivityX.toDouble())
            put(KEY_LOOK_SENSITIVITY_Y, lookSensitivityY.toDouble())
            put(KEY_INVERT_LOOK_Y, invertLookY)
            put(KEY_LOOK_SMOOTHING, lookSmoothing.toDouble())
            put(KEY_LOOK_DEADZONE, lookDeadzone.toDouble())
            put(KEY_MOUSE_ACCELERATION_ENABLED, mouseAccelerationEnabled)
            put(KEY_MOUSE_ACCELERATION_STRENGTH, mouseAccelerationStrength.toDouble())
            put(KEY_MOUSE_ACCELERATION_MAX_MULTIPLIER, mouseAccelerationMaxMultiplier.toDouble())
            put(KEY_MOVEMENT_JOYSTICK_SIZE, movementJoystickSize.toDouble())
            put(KEY_LOOK_JOYSTICK_SIZE, lookJoystickSize.toDouble())
            put(KEY_MOVEMENT_JOYSTICK_DEADZONE, movementJoystickDeadzone.toDouble())
            put(KEY_LOOK_JOYSTICK_DEADZONE, lookJoystickDeadzone.toDouble())
            put(KEY_MOVEMENT_STICK_SENSITIVITY, movementStickSensitivity.toDouble())
            put(KEY_LOOK_STICK_SENSITIVITY, lookStickSensitivity.toDouble())
            put(KEY_MOVEMENT_JOYSTICK_BEHAVIOR, movementJoystickBehavior)
            put(KEY_LOOK_JOYSTICK_BEHAVIOR, lookJoystickBehavior)
            if (joystickOpacity > 0) {
                put(KEY_JOYSTICK_OPACITY, joystickOpacity.toDouble())
            }
            put(KEY_SHOW_RUNTIME_TOGGLE, showRuntimeToggle)
            put(KEY_JOYSTICK_SIZE, movementJoystickSize.toDouble())
            put(KEY_JOYSTICK_BEHAVIOR, movementJoystickBehavior)
            put(KEY_MOVEMENT_ZONE_SPLIT, movementZoneSplit.toDouble())
            put(KEY_BUTTON_LOOK_THROUGH_ENABLED, buttonLookThroughEnabled)
            put(KEY_BUTTON_LOOK_THROUGH_DRAG_THRESHOLD, buttonLookThroughDragThreshold)
            put(KEY_OUTER_RING_SPRINT_ENABLED, outerRingSprintEnabled)
            put(KEY_OUTER_RING_SPRINT_THRESHOLD, outerRingSprintThreshold.toDouble())
            put(KEY_OUTER_RING_SPRINT_PRESS_MODE, outerRingSprintPressMode)
            put(KEY_SPRINT_BINDING, sprintBinding)
        }.toString()
    }

    companion object {
        const val MOVEMENT_WASD = "wasd"
        const val MOVEMENT_ARROW_KEYS = "arrow_keys"
        const val MOVEMENT_GAMEPAD_LEFT_STICK = "gamepad_left_stick"

        const val LOOK_MOUSE = "mouse"
        const val LOOK_GAMEPAD_RIGHT_STICK = "gamepad_right_stick"

        const val JOYSTICK_ANCHORED = "anchored"
        const val JOYSTICK_FLOATING = "floating"

        const val SPRINT_BINDING_SHIFT = "KEY_SHIFT_L"

        const val DEFAULT_BUTTON_DRAG_THRESHOLD = 12
        const val DEFAULT_ANALOG_STICK_DEADZONE = 0.01f
        const val JOYSTICK_OPACITY_UNSET = -1.0f

        val MOVEMENT_TYPES = listOf(
            MOVEMENT_WASD,
            MOVEMENT_ARROW_KEYS,
            MOVEMENT_GAMEPAD_LEFT_STICK,
        )

        val LOOK_TYPES = listOf(
            LOOK_MOUSE,
            LOOK_GAMEPAD_RIGHT_STICK,
        )

        val JOYSTICK_BEHAVIORS = listOf(
            JOYSTICK_ANCHORED,
            JOYSTICK_FLOATING,
        )

        val SPRINT_BINDINGS: List<String> =
            Binding.keyboardBindingValues()
                .filter { it != Binding.NONE }
                .map { it.name } +
                Binding.gamepadBindingValues()
                    .filter { it.isSprintGamepadBinding() }
                    .map { it.name }

        private const val KEY_MOVEMENT_TYPE = "movementType"
        private const val KEY_LOOK_TYPE = "lookType"
        private const val KEY_LOOK_SENSITIVITY_X = "lookSensitivityX"
        private const val KEY_LOOK_SENSITIVITY_Y = "lookSensitivityY"
        private const val KEY_INVERT_LOOK_Y = "invertLookY"
        private const val KEY_LOOK_SMOOTHING = "lookSmoothing"
        private const val KEY_LOOK_DEADZONE = "lookDeadzone"
        private const val KEY_JOYSTICK_SIZE = "joystickSize"
        private const val KEY_MOVEMENT_JOYSTICK_SIZE = "movementJoystickSize"
        private const val KEY_LOOK_JOYSTICK_SIZE = "lookJoystickSize"
        private const val KEY_MOVEMENT_JOYSTICK_DEADZONE = "movementJoystickDeadzone"
        private const val KEY_LOOK_JOYSTICK_DEADZONE = "lookJoystickDeadzone"
        private const val KEY_MOVEMENT_STICK_SENSITIVITY = "movementStickSensitivity"
        private const val KEY_LOOK_STICK_SENSITIVITY = "lookStickSensitivity"
        private const val KEY_MOVEMENT_JOYSTICK_BEHAVIOR = "movementJoystickBehavior"
        private const val KEY_LOOK_JOYSTICK_BEHAVIOR = "lookJoystickBehavior"
        private const val KEY_JOYSTICK_OPACITY = "joystickOpacity"
        private const val KEY_SHOW_RUNTIME_TOGGLE = "showRuntimeToggle"
        private const val KEY_MOUSE_ACCELERATION_ENABLED = "mouseAccelerationEnabled"
        private const val KEY_MOUSE_ACCELERATION_STRENGTH = "mouseAccelerationStrength"
        private const val KEY_MOUSE_ACCELERATION_MAX_MULTIPLIER = "mouseAccelerationMaxMultiplier"
        private const val KEY_MOVEMENT_ZONE_SPLIT = "movementZoneSplit"
        private const val KEY_BUTTON_LOOK_THROUGH_ENABLED = "buttonLookThroughEnabled"
        private const val KEY_BUTTON_LOOK_THROUGH_DRAG_THRESHOLD = "buttonLookThroughDragThreshold"
        private const val KEY_JOYSTICK_BEHAVIOR = "joystickBehavior"
        private const val KEY_OUTER_RING_SPRINT_ENABLED = "outerRingSprintEnabled"
        private const val KEY_OUTER_RING_SPRINT_THRESHOLD = "outerRingSprintThreshold"
        private const val KEY_OUTER_RING_SPRINT_PRESS_MODE = "outerRingSprintPressMode"
        private const val KEY_SPRINT_BINDING = "sprintBinding"

        @JvmStatic
        fun fromJson(json: String?): ShooterModeConfig {
            if (json.isNullOrBlank()) return ShooterModeConfig()
            return try {
                val obj = JSONObject(json)
                val legacyJoystickSize = clampFloat(
                    obj.optDouble(KEY_JOYSTICK_SIZE, 1.0).toFloat(),
                    0.5f,
                    3.0f,
                )
                val legacyJoystickBehavior = normalizeJoystickBehavior(
                    obj.optString(KEY_JOYSTICK_BEHAVIOR, JOYSTICK_ANCHORED),
                )
                ShooterModeConfig(
                    movementType = normalizeMovementType(
                        obj.optString(KEY_MOVEMENT_TYPE, MOVEMENT_GAMEPAD_LEFT_STICK),
                    ),
                    lookType = normalizeLookType(
                        obj.optString(KEY_LOOK_TYPE, LOOK_GAMEPAD_RIGHT_STICK),
                    ),
                    lookSensitivityX = clampFloat(
                        obj.optDouble(KEY_LOOK_SENSITIVITY_X, 1.0).toFloat(),
                        0.1f,
                        10.0f,
                    ),
                    lookSensitivityY = clampFloat(
                        obj.optDouble(KEY_LOOK_SENSITIVITY_Y, 1.0).toFloat(),
                        0.1f,
                        10.0f,
                    ),
                    invertLookY = obj.optBoolean(KEY_INVERT_LOOK_Y, false),
                    lookSmoothing = clampFloat(
                        obj.optDouble(KEY_LOOK_SMOOTHING, 0.0).toFloat(),
                        0.0f,
                        0.95f,
                    ),
                    lookDeadzone = clampFloat(
                        obj.optDouble(KEY_LOOK_DEADZONE, 0.0).toFloat(),
                        0.0f,
                        8.0f,
                    ),
                    mouseAccelerationEnabled = obj.optBoolean(KEY_MOUSE_ACCELERATION_ENABLED, false),
                    mouseAccelerationStrength = clampFloat(
                        obj.optDouble(KEY_MOUSE_ACCELERATION_STRENGTH, 1.0).toFloat(),
                        0.1f,
                        5.0f,
                    ),
                    mouseAccelerationMaxMultiplier = clampFloat(
                        obj.optDouble(KEY_MOUSE_ACCELERATION_MAX_MULTIPLIER, 3.0).toFloat(),
                        1.0f,
                        8.0f,
                    ),
                    movementJoystickSize = clampFloat(
                        obj.optDouble(KEY_MOVEMENT_JOYSTICK_SIZE, legacyJoystickSize.toDouble()).toFloat(),
                        0.5f,
                        3.0f,
                    ),
                    lookJoystickSize = clampFloat(
                        obj.optDouble(KEY_LOOK_JOYSTICK_SIZE, legacyJoystickSize.toDouble()).toFloat(),
                        0.5f,
                        3.0f,
                    ),
                    movementJoystickDeadzone = clampFloat(
                        obj.optDouble(KEY_MOVEMENT_JOYSTICK_DEADZONE, DEFAULT_ANALOG_STICK_DEADZONE.toDouble()).toFloat(),
                        0.0f,
                        0.75f,
                    ),
                    lookJoystickDeadzone = clampFloat(
                        obj.optDouble(KEY_LOOK_JOYSTICK_DEADZONE, DEFAULT_ANALOG_STICK_DEADZONE.toDouble()).toFloat(),
                        0.0f,
                        0.75f,
                    ),
                    movementStickSensitivity = clampFloat(
                        obj.optDouble(KEY_MOVEMENT_STICK_SENSITIVITY, 1.0).toFloat(),
                        0.1f,
                        5.0f,
                    ),
                    lookStickSensitivity = clampFloat(
                        obj.optDouble(KEY_LOOK_STICK_SENSITIVITY, 1.0).toFloat(),
                        0.1f,
                        5.0f,
                    ),
                    movementJoystickBehavior = normalizeJoystickBehavior(
                        obj.optString(KEY_MOVEMENT_JOYSTICK_BEHAVIOR, legacyJoystickBehavior),
                    ),
                    lookJoystickBehavior = normalizeJoystickBehavior(
                        obj.optString(KEY_LOOK_JOYSTICK_BEHAVIOR, legacyJoystickBehavior),
                    ),
                    joystickOpacity = if (obj.has(KEY_JOYSTICK_OPACITY)) {
                        clampFloat(
                            obj.optDouble(KEY_JOYSTICK_OPACITY, 1.0).toFloat(),
                            0.01f,
                            1.0f,
                        )
                    } else {
                        JOYSTICK_OPACITY_UNSET
                    },
                    showRuntimeToggle = obj.optBoolean(KEY_SHOW_RUNTIME_TOGGLE, true),
                    movementZoneSplit = clampFloat(
                        obj.optDouble(KEY_MOVEMENT_ZONE_SPLIT, 0.5).toFloat(),
                        0.10f,
                        0.90f,
                    ),
                    buttonLookThroughEnabled = obj.optBoolean(KEY_BUTTON_LOOK_THROUGH_ENABLED, true),
                    buttonLookThroughDragThreshold = obj.optInt(
                        KEY_BUTTON_LOOK_THROUGH_DRAG_THRESHOLD,
                        DEFAULT_BUTTON_DRAG_THRESHOLD,
                    ).coerceIn(0, 64),
                    outerRingSprintEnabled = obj.optBoolean(KEY_OUTER_RING_SPRINT_ENABLED, false),
                    outerRingSprintThreshold = clampFloat(
                        obj.optDouble(KEY_OUTER_RING_SPRINT_THRESHOLD, 0.85).toFloat(),
                        0.5f,
                        1.0f,
                    ),
                    outerRingSprintPressMode = obj.optBoolean(KEY_OUTER_RING_SPRINT_PRESS_MODE, false),
                    sprintBinding = normalizeSprintBinding(
                        obj.optString(KEY_SPRINT_BINDING, SPRINT_BINDING_SHIFT),
                    ),
                )
            } catch (_: Exception) {
                ShooterModeConfig()
            }
        }

        private fun normalizeMovementType(value: String): String {
            return if (value in MOVEMENT_TYPES) value else MOVEMENT_GAMEPAD_LEFT_STICK
        }

        private fun normalizeLookType(value: String): String {
            return if (value in LOOK_TYPES) value else LOOK_GAMEPAD_RIGHT_STICK
        }

        private fun normalizeJoystickBehavior(value: String): String {
            return if (value in JOYSTICK_BEHAVIORS) value else JOYSTICK_ANCHORED
        }

        private fun normalizeSprintBinding(value: String): String {
            val binding = Binding.fromString(value)
            return if (binding != Binding.NONE && (binding.isKeyboard || binding.isSprintGamepadBinding())) {
                binding.name
            } else {
                SPRINT_BINDING_SHIFT
            }
        }

        private fun Binding.isSprintGamepadBinding(): Boolean {
            return name.startsWith("GAMEPAD_BUTTON_") || name.startsWith("GAMEPAD_DPAD_")
        }

        private fun clampFloat(value: Float, minValue: Float, maxValue: Float): Float {
            return max(minValue, min(value, maxValue))
        }
    }
}
