package app.gamenative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * getNativeDisplaySize() feeds gamescope's -W/-H and GNDRM_SCANOUT, so it must report the panel's
 * real pixels (landscape-ordered). getDefaultScreenSize() stays an aspect-ratio preset and must not
 * be confused with it - that mix-up is what made gamescope present at 720p.
 */
@RunWith(AndroidJUnit4::class)
class NativeDisplaySizeAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeSizeMatchesThePanelReportedByThePlatform() {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        assertNotNull("no default display", display)
        val mode = display!!.mode
        val expected = if (mode.physicalWidth >= mode.physicalHeight) {
            "${mode.physicalWidth}x${mode.physicalHeight}"
        } else {
            "${mode.physicalHeight}x${mode.physicalWidth}"
        }

        assertEquals(expected, PluviaApp.getNativeDisplaySize())
    }

    @Test
    fun nativeSizeIsLandscapeOriented() {
        val size = PluviaApp.getNativeDisplaySize()
        assertNotNull(size)
        val (w, h) = size!!.split("x").map { it.toInt() }
        assertTrue("expected landscape $size", w > h)
    }

    @Test
    fun defaultScreenSizeStillAnswersInPresets() {
        assertTrue(
            PluviaApp.getDefaultScreenSize() in setOf(
                Container.DEFAULT_SCREEN_SIZE_4_3,
                Container.DEFAULT_SCREEN_SIZE_16_10,
                Container.DEFAULT_SCREEN_SIZE_16_9,
            ),
        )
    }
}
