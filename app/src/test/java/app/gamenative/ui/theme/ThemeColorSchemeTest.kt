package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.gamenative.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /** Blue share of a colour, to tell a page-tinted panel from a neutral grey one. */
    private fun Color.blueLead(): Float = blue - red

    @Test
    fun noThemeKeepsTheStockScheme() {
        assertEquals(buildColorScheme(ThemeSpec.EMPTY), buildColorScheme(ThemeSpec()))
    }

    @Test
    fun pageColourAloneTintsPanelsMenusAndDialogs() {
        val page = Color(0xFF203040)
        val scheme = buildColorScheme(ThemeSpec(background = 0xFF203040.toInt()))
        for (c in listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)) {
            assertTrue("$c is not tinted like the page", c.blueLead() > 0.05f)
            assertTrue("$c has no edge on the page", contrast(c, page) >= 1.1f)
        }
    }

    @Test
    fun whitePageGetsNeutralPanelsNotLavender() {
        val scheme = buildColorScheme(ThemeSpec(background = 0xFFFFFFFF.toInt()))
        val s = scheme.surfaceContainer
        assertTrue("$s is tinted", maxOf(s.red, s.green, s.blue) - minOf(s.red, s.green, s.blue) < 0.01f)
        assertTrue(contrast(s, Color.White) >= 1.1f)
    }

    @Test
    fun accentAndRedActionsStayReadableOnAThemedMenu() {
        val menu = Color(0xFF2A5A46)
        val scheme = buildColorScheme(ThemeSpec(menu = 0xFF2A5A46.toInt()))
        assertTrue(contrast(scheme.primary, menu) >= 3f)
        assertTrue(contrast(scheme.error, menu) >= 3f)
    }
}
