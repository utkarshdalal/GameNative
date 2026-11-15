package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeContrastTest {

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Test
    fun ratioFollowsWcag() {
        assertEquals(21.0, ThemeContrast.ratio(ThemeContrast.BLACK, ThemeContrast.WHITE), 0.01)
        assertEquals(1.0, ThemeContrast.ratio(argb("#777777"), argb("#777777")), 0.001)
        assertEquals(4.48, ThemeContrast.ratio(argb("#777777"), ThemeContrast.WHITE), 0.01)
    }

    @Test
    fun theThemeColorIsKeptWhenReadable() {
        val fg = argb("#dddddd")
        assertEquals(fg, ThemeContrast.onFill(argb("#202020"), ThemeContrast.TEXT, listOf(fg)))
    }

    @Test
    fun theFirstReadableAlternativeWinsOverBlackAndWhite() {
        val alt = argb("#2de2e6")
        assertEquals(alt, ThemeContrast.onFill(argb("#3d4450"), ThemeContrast.TEXT, listOf(argb("#f6019d"), null, alt)))
    }

    @Test
    fun withoutAReadableThemeColorTheFillGetsBlackOrWhite() {
        assertEquals(ThemeContrast.BLACK, ThemeContrast.onFill(argb("#e9fafe"), ThemeContrast.TEXT, listOf(ThemeContrast.WHITE)))
        assertEquals(ThemeContrast.WHITE, ThemeContrast.onFill(argb("#141414"), ThemeContrast.TEXT, listOf(argb("#202020"))))
        assertEquals("a light mint accent takes black, never a mid gray", ThemeContrast.BLACK, ThemeContrast.onFill(argb("#a8f0c8"), ThemeContrast.TEXT, listOf(argb("#fafafa"))))
    }

    @Test
    fun aHandleOnlyNeedsToStandOut() {
        assertEquals("white on Steam-like orange is enough for a handle", ThemeContrast.WHITE, ThemeContrast.onFill(argb("#ffa500"), ThemeContrast.HANDLE, listOf(ThemeContrast.WHITE)))
        assertEquals(ThemeContrast.BLACK, ThemeContrast.onFill(argb("#f0fff0"), ThemeContrast.HANDLE, listOf(ThemeContrast.WHITE)))
    }

    @Test
    fun translucentColorsAreCompositedOverWhatIsUnder() {
        assertEquals(argb("#808080"), ThemeContrast.over(argb("#ffffff80"), ThemeContrast.BLACK))
        assertEquals(argb("#123456"), ThemeContrast.over(argb("#123456"), ThemeContrast.WHITE))
    }
}
