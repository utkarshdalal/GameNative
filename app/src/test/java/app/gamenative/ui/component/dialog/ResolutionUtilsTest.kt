package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionUtilsTest {

    @Test
    fun evenRound_roundsToNearestEven() {
        assertEquals(1280, evenRound(1280.0f))
        assertEquals(1280, evenRound(1280.4f))
        assertEquals(1282, evenRound(1281.0f))
        assertEquals(1282, evenRound(1281.6f))
        assertEquals(720, evenRound(720.0f))
        assertEquals(720, evenRound(719.1f))
        assertEquals(0, evenRound(0.0f))
        assertEquals(2, evenRound(1.0f))
    }

    @Test
    fun gcd_calculatesCorrectly() {
        assertEquals(80, gcd(1280, 720))
        assertEquals(120, gcd(1920, 1080))
        assertEquals(1, gcd(13, 7))
        assertEquals(10, gcd(100, 10))
    }

    @Test
    fun calculateAspectRatio_returnsCorrectRatios() {
        // Standard 16:9
        assertEquals("16:9", calculateAspectRatio(1920, 1080))
        assertEquals("16:9", calculateAspectRatio(1280, 720))

        // Standard 4:3
        assertEquals("4:3", calculateAspectRatio(1024, 768))
        assertEquals("4:3", calculateAspectRatio(800, 600))

        // Special Mobile Ratios
        // 19.5:9 (e.g. 2340x1080) -> simplified is 13:6
        assertEquals("19.5:9", calculateAspectRatio(2340, 1080))

        // 21.5:9 (e.g. 2580x1080) -> simplified is 43:18
        assertEquals("21.5:9", calculateAspectRatio(2580, 1080))

        // 20:9 (e.g. 2400x1080) -> simplified is 20:9
        assertEquals("20:9", calculateAspectRatio(2400, 1080))

        // Custom non-standard
        assertEquals("5:2", calculateAspectRatio(1000, 400))
    }
}
