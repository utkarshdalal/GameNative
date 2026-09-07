package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class OnScreenControllerSettingsDialogTest {
    @Test
    fun `invalid stored speeds fall back to default`() {
        assertEquals(DEFAULT_MOUSE_SPEED, mouseSpeedOrDefault(Float.NaN), 0f)
        assertEquals(DEFAULT_MOUSE_SPEED, mouseSpeedOrDefault(-1f), 0f)
        assertEquals(DEFAULT_MOUSE_SPEED, mouseSpeedOrDefault(0f), 0f)
    }

    @Test
    fun `valid stored speeds remain unchanged before slider clamping`() {
        assertEquals(4f, mouseSpeedOrDefault(4f), 0f)
    }

    @Test
    fun `slider speed is constrained to the supported range`() {
        assertEquals(MIN_MOUSE_SPEED, mouseSpeedForSlider(0.01f), 0f)
        assertEquals(MAX_MOUSE_SPEED, mouseSpeedForSlider(4f), 0f)
    }
}
