package app.gamenative.ui.screen.xserver

import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitGameHostHeightTest {

    @Test
    fun parseScreenSize_acceptsValidValues() {
        assertEquals(800 to 600, parseScreenSize("800x600"))
        assertEquals(1920 to 1080, parseScreenSize(" 1920 X 1080 "))
    }

    @Test
    fun parseScreenSize_rejectsInvalidValues() {
        assertNull(parseScreenSize(""))
        assertNull(parseScreenSize("800"))
        assertNull(parseScreenSize("800x"))
        assertNull(parseScreenSize("0x600"))
        assertNull(parseScreenSize("800x-600"))
    }

    @Test
    fun portraitGameHostHeight_usesSelectedAspectRatioInPortraitMode() {
        assertEquals(900, portraitGameHostHeight(
            isPortrait = true,
            screenWidth = 1200,
            availableHeight = 0,
            screenSize = "800x600",
        ))
    }

    @Test
    fun portraitGameHostHeight_clampsToAvailableTopArea() {
        assertEquals(700, portraitGameHostHeight(
            isPortrait = true,
            screenWidth = 1200,
            availableHeight = 700,
            screenSize = "800x600",
        ))
    }

    @Test
    fun portraitGameHostHeight_fallsBackOutsidePortraitAspectLayout() {
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, portraitGameHostHeight(
            isPortrait = false,
            screenWidth = 1200,
            availableHeight = 0,
            screenSize = "800x600",
        ))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, portraitGameHostHeight(
            isPortrait = true,
            screenWidth = 1200,
            availableHeight = 0,
            screenSize = "bad",
        ))
    }
}
