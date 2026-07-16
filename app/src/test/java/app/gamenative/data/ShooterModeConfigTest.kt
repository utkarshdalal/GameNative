package app.gamenative.data

import com.winlator.inputcontrols.Binding
import org.junit.Assert.assertEquals
import org.junit.Test

class ShooterModeConfigTest {
    @Test
    fun `invalid json falls back to default config`() {
        assertEquals(ShooterModeConfig(), ShooterModeConfig.fromJson("{not-json"))
    }

    @Test
    fun `legacy joystick keys apply to movement and look joysticks`() {
        val config = ShooterModeConfig.fromJson(
            """
            {
                "joystickSize": 2.25,
                "joystickBehavior": "floating"
            }
            """.trimIndent(),
        )

        assertEquals(2.25f, config.movementJoystickSize, 0.001f)
        assertEquals(2.25f, config.lookJoystickSize, 0.001f)
        assertEquals(ShooterModeConfig.JOYSTICK_FLOATING, config.movementJoystickBehavior)
        assertEquals(ShooterModeConfig.JOYSTICK_FLOATING, config.lookJoystickBehavior)
    }

    @Test
    fun `out of range numeric values are clamped`() {
        val config = ShooterModeConfig.fromJson(
            """
            {
                "lookSensitivityX": 20.0,
                "lookSensitivityY": 0.01,
                "lookSmoothing": 2.0,
                "lookDeadzone": -1.0,
                "movementJoystickSize": 9.0,
                "lookJoystickSize": 0.1,
                "movementJoystickDeadzone": -1.0,
                "lookJoystickDeadzone": 9.0,
                "movementStickSensitivity": 10.0,
                "lookStickSensitivity": 0.01,
                "joystickOpacity": 0.0,
                "movementZoneSplit": 2.0,
                "buttonLookThroughDragThreshold": 999,
                "outerRingSprintThreshold": 0.0
            }
            """.trimIndent(),
        )

        assertEquals(10.0f, config.lookSensitivityX, 0.001f)
        assertEquals(0.1f, config.lookSensitivityY, 0.001f)
        assertEquals(0.95f, config.lookSmoothing, 0.001f)
        assertEquals(0.0f, config.lookDeadzone, 0.001f)
        assertEquals(3.0f, config.movementJoystickSize, 0.001f)
        assertEquals(0.5f, config.lookJoystickSize, 0.001f)
        assertEquals(0.0f, config.movementJoystickDeadzone, 0.001f)
        assertEquals(0.75f, config.lookJoystickDeadzone, 0.001f)
        assertEquals(5.0f, config.movementStickSensitivity, 0.001f)
        assertEquals(0.1f, config.lookStickSensitivity, 0.001f)
        assertEquals(0.01f, config.joystickOpacity, 0.001f)
        assertEquals(0.90f, config.movementZoneSplit, 0.001f)
        assertEquals(64, config.buttonLookThroughDragThreshold)
        assertEquals(0.5f, config.outerRingSprintThreshold, 0.001f)
    }

    @Test
    fun `sprint binding accepts keyboard and sprint gamepad bindings`() {
        val keyboardConfig = ShooterModeConfig.fromJson("""{"sprintBinding":"${Binding.KEY_R.name}"}""")
        val gamepadConfig = ShooterModeConfig.fromJson("""{"sprintBinding":"${Binding.GAMEPAD_BUTTON_A.name}"}""")

        assertEquals(Binding.KEY_R.name, keyboardConfig.sprintBinding)
        assertEquals(Binding.GAMEPAD_BUTTON_A.name, gamepadConfig.sprintBinding)
    }

    @Test
    fun `sprint binding rejects non sprint gamepad bindings`() {
        val config = ShooterModeConfig.fromJson("""{"sprintBinding":"${Binding.GAMEPAD_LEFT_THUMB_UP.name}"}""")

        assertEquals(ShooterModeConfig.SPRINT_BINDING_SHIFT, config.sprintBinding)
    }
}
