package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDialogBlocksTest {
    @Test
    fun `stepped sliders use their configured interval for controller input`() {
        assertEquals(
            0.1f,
            settingsSliderAdjustmentStep(valueRange = 0.2f..8.0f, steps = 77),
            0.0001f,
        )
    }

    @Test
    fun `continuous sliders use a useful display-sized controller interval`() {
        assertEquals(0.01f, settingsSliderAdjustmentStep(0.1f..0.9f, steps = 0), 0.0001f)
        assertEquals(0.1f, settingsSliderAdjustmentStep(0.1f..10.0f, steps = 0), 0.0001f)
    }

    @Test
    fun `controller adjustment snaps to the interval and clamps to the range`() {
        assertEquals(
            0.4f,
            adjustSettingsSliderValue(0.33f, 0f..1f, adjustmentStep = 0.1f, direction = 1),
            0.0001f,
        )
        assertEquals(
            0.3f,
            adjustSettingsSliderValue(0.33f, 0f..1f, adjustmentStep = 0.1f, direction = -1),
            0.0001f,
        )
        assertEquals(
            0f,
            adjustSettingsSliderValue(0f, 0f..1f, adjustmentStep = 0.1f, direction = -1),
            0.0001f,
        )
    }
}
