package app.gamenative.ui.screen.xr.windows

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsVrControlServerTest {
    @Test
    fun `left controller buttons map to OpenVR semantics`() {
        val xboxButtons = (1 shl 2) or (1 shl 3) or (1 shl 8) or (1 shl 7)

        assertEquals(0b1111, openVrControllerButtons(xboxButtons, hand = 0))
    }

    @Test
    fun `right controller buttons map independently`() {
        val xboxButtons = (1 shl 0) or (1 shl 1) or (1 shl 9) or (1 shl 7)

        assertEquals(0b0111, openVrControllerButtons(xboxButtons, hand = 1))
    }

    @Test
    fun `buttons from the other hand do not leak`() {
        val leftOnly = (1 shl 2) or (1 shl 3) or (1 shl 8)
        val rightOnly = (1 shl 0) or (1 shl 1) or (1 shl 9)

        assertEquals(0, openVrControllerButtons(leftOnly, hand = 1))
        assertEquals(0, openVrControllerButtons(rightOnly, hand = 0))
    }
}
