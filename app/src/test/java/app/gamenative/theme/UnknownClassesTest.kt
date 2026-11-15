package app.gamenative.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownClassesTest {

    private val opaque = "_3Yf8b2Kx9ZqR4tLmN0p"

    private fun unknown(css: String, map: SteamClassMap = SteamClassMap.EMPTY) =
        ThemeTranslator.namesUnknownClasses(listOf(CssParser.parse(css)), map)

    @Test
    fun readableNamesNeedNoTable() {
        assertFalse(unknown(":root { --gn-accent: #ff0000; }"))
        assertFalse(unknown(".gamepaddialog_Field_1a2b3 { color: red; }"))
        assertFalse(unknown(".Focusable, .appdetailsplaysection_PlayBar_3wR9v div { color: red; }"))
        assertFalse(unknown("[class*=\"gamepaddialog_Field_\"] { color: red; }"))
        assertFalse(unknown("div:not(.$opaque) .Panel { color: red; }"))
    }

    @Test
    fun opaqueHashesNeedTheTable() {
        assertTrue(unknown(".$opaque { color: red; }"))
        assertTrue(unknown(".Panel3xY9kLmN0pQrStU > .Focusable { color: red; }"))
        assertTrue(unknown("[class=\"$opaque\"] { color: red; }"))
        assertTrue(unknown("@media (min-width: 1px) { .x { .$opaque { color: red; } } }"))
    }

    @Test
    fun aTableThatKnowsTheHashIsEnough() {
        val map = SteamClassMap(mapOf(opaque to "gamepaddialog_Field"))
        assertFalse(unknown(".$opaque { color: red; }", map))
        assertTrue(unknown(".$opaque, .Panel3xY9kLmN0pQrStU { color: red; }", map))
    }

    @Test
    fun noSheetsNeedNothing() {
        assertFalse(ThemeTranslator.namesUnknownClasses(emptyList(), SteamClassMap.EMPTY))
    }
}
