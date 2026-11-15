package app.gamenative.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeTypographyStyleTest {

    @Test
    fun scaledMultipliesSizeAndLineHeight() {
        val s = PluviaTypography.bodyMedium.scaled(1.25f)
        assertEquals(17.5f, s.fontSize.value, 1e-4f)
        assertEquals(25f, s.lineHeight.value, 1e-4f)
        assertSame(PluviaTypography.bodyMedium, PluviaTypography.bodyMedium.scaled(1f))
    }

    @Test
    fun themedScalesEveryStyleAndAddsLetterSpacing() {
        val t = PluviaTypography.themed(0.9f, 0.05f)
        assertEquals(14.4f, t.bodyLarge.fontSize.value, 1e-4f)
        assertEquals(51.3f, t.displayLarge.fontSize.value, 1e-3f)
        // own 0.5sp plus 0.05em of the scaled 14.4sp
        assertEquals(0.5f + 14.4f * 0.05f, t.bodyLarge.letterSpacing.value, 1e-4f)
        assertEquals(PluviaTypography.labelSmall.fontWeight, t.labelSmall.fontWeight)
        assertEquals(PluviaTypography.titleMedium.letterSpacing, PluviaTypography.themed(1.1f, 0f).titleMedium.letterSpacing)
        assertEquals(16.sp * 1.1f, PluviaTypography.themed(1.1f, 0f).titleMedium.fontSize)
    }
}
