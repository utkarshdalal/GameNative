package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.gamenative.theme.ThemeBar
import app.gamenative.theme.ThemeControls
import app.gamenative.theme.ThemeFill
import app.gamenative.theme.ThemeFocus
import app.gamenative.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFillContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.copy(alpha = 1f).luminance()
        val lb = b.copy(alpha = 1f).luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun colors(spec: ThemeSpec) = buildPluviaColors(spec, buildColorScheme(spec), 1f)

    @Test
    fun textOnALightAccentIsBlackNotGray() {
        val mint = 0xFFA8F0C8.toInt()
        val scheme = buildColorScheme(ThemeSpec(primary = mint, text = 0xFFFAFAFA.toInt()))
        assertEquals(Color.Black, scheme.onPrimary)
    }

    @Test
    fun theThemesOwnTextOnItsFillIsKeptWhenReadable() {
        val spec = ThemeSpec(button = 0xFF202020.toInt(), onButton = 0xFFDDDDDD.toInt(), menu = 0xFF000000.toInt(), onMenu = 0xFFF6019D.toInt())
        val c = colors(spec)
        assertEquals(Color(0xFFDDDDDD), c.onButton)
        assertEquals(Color(0xFFF6019D), c.onMenu)
    }

    @Test
    fun focusedRowTextFollowsItsFill() {
        val spec = ThemeSpec(focusStyle = ThemeFocus(fill = ThemeFill(color = 0xFFE9FAFE.toInt()), onFill = 0xFFFFFFFF.toInt()))
        val onFill = colors(spec).styles!!.focus.onFill!!
        assertTrue(contrast(onFill, Color(0xFFE9FAFE)) >= 4.5f)
        assertNull("no fill, no focused text color", colors(ThemeSpec(focus = 0xFFFFFFFF.toInt())).styles!!.focus.onFill)
    }

    @Test
    fun switchHandleDefaultsToWhiteLikeSteam() {
        val orange = 0xFFFFA500.toInt()
        val sw = colors(ThemeSpec(primary = orange, onPrimary = 0xFF000000.toInt(), toggle = orange)).styles!!.switch!!
        assertEquals(Color.White, sw.thumbOn)
        val mint = colors(ThemeSpec(toggle = 0xFFF0FFF0.toInt())).styles!!.switch!!
        assertTrue("white vanishes on a near-white track", mint.thumbOn != Color.White)
        val set = colors(ThemeSpec(toggle = 0xFFCCDB76.toInt(), controls = ThemeControls(thumbOn = 0xFFFFFFFF.toInt()))).styles!!.switch!!
        assertEquals("a handle color the theme sets is kept", Color.White, set.thumbOn)
    }

    @Test
    fun offSwitchTrackShowsAgainstTheRows() {
        val spec = ThemeSpec(toggle = 0xFFCCDB76.toInt(), controls = ThemeControls(toggleOff = 0xFF292929.toInt(), thumbOff = 0xFF363636.toInt()))
        val scheme = buildColorScheme(spec)
        val sw = buildPluviaColors(spec, scheme, 1f).styles!!.switch!!
        for (row in listOf(scheme.background, scheme.surface, scheme.surfaceVariant)) assertTrue(contrast(sw.trackOff, row) >= 1.5f)
        assertTrue(contrast(sw.thumbOff, sw.trackOff) >= 1.5f)
    }

    @Test
    fun destructiveActionsStayRed() {
        val scheme = buildColorScheme(ThemeSpec(primary = 0xFFF6019D.toInt(), menu = 0xFF000000.toInt()))
        assertTrue(scheme.error.red > scheme.error.green * 2 && scheme.error.red > scheme.error.blue * 2)
        assertTrue(contrast(scheme.error, Color.Black) >= 3f)
    }

    @Test
    fun raisedSurfacesAreNotTintedByTheAccent() {
        val scheme = buildColorScheme(ThemeSpec(primary = 0xFFF6019D.toInt()))
        assertEquals(scheme.surface, scheme.surfaceTint)
    }

    @Test
    fun barTextOnAnOpaqueThemeBarIsReadable() {
        val spec = ThemeSpec(header = ThemeBar(color = 0xFFEEEEEE.toInt(), textColor = 0xFFFFFFFF.toInt()))
        val header = colors(spec).styles!!.header
        assertTrue(contrast(header.contentColor, Color(0xFFEEEEEE)) >= 4.5f)
        val overArt = colors(ThemeSpec(header = ThemeBar(color = 0x30000000, textColor = 0xFF2DE2E6.toInt()))).styles!!.header
        assertEquals("text over a see-through bar is kept as set", Color(0xFF2DE2E6), overArt.contentColor)
    }
}
