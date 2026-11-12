package app.gamenative.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.gamenative.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** The scheme the app shipped before themes existed, rebuilt from the same literals. */
    private val stockScheme = darkColorScheme(
        primary = PluviaPrimary,
        onPrimary = PluviaForeground,
        primaryContainer = PluviaPrimary.copy(alpha = 0.2f),
        onPrimaryContainer = PluviaForeground,
        secondary = PluviaSecondary,
        onSecondary = PluviaForeground,
        secondaryContainer = PluviaSecondary.copy(alpha = 0.8f),
        onSecondaryContainer = PluviaForeground,
        tertiary = PluviaCyan,
        onTertiary = PluviaForeground,
        tertiaryContainer = PluviaCyan.copy(alpha = 0.2f),
        onTertiaryContainer = PluviaForeground,
        background = PluviaBackground,
        onBackground = PluviaForeground,
        surface = PluviaCard,
        onSurface = PluviaForeground,
        surfaceVariant = PluviaSecondary,
        onSurfaceVariant = PluviaForegroundMuted,
        surfaceTint = PluviaPrimary,
        inverseSurface = PluviaForeground,
        inverseOnSurface = PluviaBackground,
        inversePrimary = PluviaPrimary,
        error = PluviaDestructive,
        onError = PluviaForeground,
        errorContainer = PluviaDestructive.copy(alpha = 0.2f),
        onErrorContainer = PluviaForeground,
        outline = PluviaForegroundMuted,
        outlineVariant = PluviaSecondary,
        scrim = Color.Black.copy(alpha = 0.5f),
        surfaceBright = PluviaSecondary,
        surfaceDim = PluviaBackground,
        surfaceContainer = PluviaCard,
        surfaceContainerHigh = PluviaSecondary,
        surfaceContainerHighest = PluviaSecondary.copy(alpha = 0.9f),
        surfaceContainerLow = PluviaBackground,
        surfaceContainerLowest = PluviaBackground,
    )

    /** Every colour slot of [scheme] by getter name; Color is an inline class, so its getters return a long. */
    private fun slots(scheme: ColorScheme): Map<String, Long> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            .associate { it.name to it.invoke(scheme) as Long }

    @Test
    fun noThemeIsTheStockSchemeObjectWithEveryStockSlot() {
        val scheme = buildColorScheme(ThemeSpec.EMPTY)
        assertSame(scheme, buildColorScheme(ThemeSpec.EMPTY))
        val expected = slots(stockScheme)
        assertTrue(expected.size >= 36)
        assertEquals(expected, slots(scheme))
    }

    @Test
    fun noThemeIsTheStockPluviaColorsWithNoComponentOverrides() {
        val scheme = buildColorScheme(ThemeSpec.EMPTY)
        val colors = buildPluviaColors(ThemeSpec.EMPTY, scheme)
        assertSame(colors, buildPluviaColors(ThemeSpec.EMPTY, scheme))
        assertEquals(PluviaCyan, colors.accentCyan)
        assertEquals(PluviaPurple, colors.accentPurple)
        assertEquals(PluviaSurface, colors.surfacePanel)
        assertEquals(PluviaSurfaceElevated, colors.surfaceElevated)
        assertEquals(PluviaBorder, colors.borderDefault)
        assertEquals(PluviaForegroundMuted, colors.textMuted)
        // the focus ring drew primary and tertiary, the footer bar drew surface
        assertEquals(stockScheme.primary, colors.focus)
        assertEquals(stockScheme.tertiary, colors.focusAlt)
        assertEquals(stockScheme.surface, colors.bar)
        assertEquals(1f, colors.bar.alpha)
        assertEquals(listOf(PluviaCyan, PluviaPurple, PluviaPink), colors.brandGradient)
        assertNull(colors.card)
        assertNull(colors.onCard)
        assertNull(colors.button)
        assertNull(colors.onButton)
        assertNull(colors.toggle)
        assertNull(colors.menu)
        assertNull(colors.onMenu)
        assertNull(colors.onMenuMuted)
        assertNull(colors.accentBrush)
    }

    @Test
    fun noThemeKeepsMaterialShapesAndNoLayoutOverrides() {
        val spec = ThemeSpec.EMPTY
        assertNull(spec.cornerRadiusDp)
        assertNull(buildShapes(null))
        assertNull(spec.layout)
        assertNull(spec.fontFile)
        assertNull(spec.panelOpacity)
        assertNull(spec.focusGlowDp)
        assertNull(spec.backgroundImage)
        assertNull(spec.backgroundGradient)
        assertTrue(spec.isEmpty)
    }

    @Test
    fun overlayColoursWithoutAThemeAreTheDefaults() {
        for (c in listOf(0, 0xFFFFFFFF.toInt(), 0xD2282C34.toInt(), 0x80000000.toInt(), 0xFFFFC107.toInt())) {
            assertEquals(c, ThemeViewColors.panel(c))
            assertEquals(c, ThemeViewColors.darkPanel(c))
            assertEquals(c, ThemeViewColors.accent(c))
            assertEquals(c, ThemeViewColors.text(c))
            assertEquals(c, ThemeViewColors.focus(c))
        }
    }
}
