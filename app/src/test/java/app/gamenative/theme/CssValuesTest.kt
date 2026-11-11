package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CssValuesTest {

    private fun hex(s: String) = CssValues.parseColor(s)?.toHex()

    @Test
    fun colourFormats() {
        assertEquals("#ffaa00", hex("#fa0"))
        assertEquals("#ffaa0088", hex("#fa08"))
        assertEquals("#12abef", hex("#12ABEF"))
        assertEquals("#12abef80", hex("#12abef80"))
        assertEquals("#ff8000", hex("rgb(255, 128, 0)"))
        assertEquals("#ff800080", hex("rgba(255,128,0,0.5)"))
        assertEquals("#ff800080", hex("rgb(255 128 0 / 50%)"))
        assertEquals("#ff0000", hex("hsl(0, 100%, 50%)"))
        assertEquals("#00ff0080", hex("hsla(120deg, 100%, 50%, 0.5)"))
        assertEquals("#4169e1", hex("RoyalBlue"))
        assertEquals("#00000000", hex("transparent"))
        assertEquals("#808080", hex("color-mix(in srgb, black, white)"))
        assertNull(hex("#12345"))
        assertNull(hex("notacolour"))
    }

    @Test
    fun findColorsInValues() {
        val found = CssValues.findColors("0 0 4px rgba(0,0,0,.5), inset 0 0 0 2px #00ff00 solid red")
        assertEquals(listOf("#00000080", "#00ff00", "#ff0000"), found.map { it.toHex() })
    }

    @Test
    fun varResolutionWithFallbackAndNesting() {
        val vars = mapOf("--a" to "var(--b)", "--b" to "#010203", "--loop" to "var(--loop)")
        val lookup: (String) -> String? = { vars[it] }
        assertEquals("#010203", CssValues.resolveVars("var(--a)", lookup))
        assertEquals("1px solid #fff", CssValues.resolveVars("1px solid var(--missing, #fff)", lookup))
        assertEquals("rgb(1, 2, 3)", CssValues.resolveVars("var(--x, var(--y, rgb(1, 2, 3)))", lookup))
        val missing = HashSet<String>()
        assertEquals("var(--nope)", CssValues.resolveVars("var(--nope)", lookup, missing))
        assertEquals(setOf("--nope"), missing)
        assertNotNull(CssValues.resolveVars("var(--loop)", lookup))
    }

    @Test
    fun gradients() {
        val g = CssValues.parseGradient("linear-gradient(90deg, #ff0000 0%, rgba(0,0,255,1) 100%)")!!
        assertEquals(90f, g.angleDeg)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), g.colors)
        assertEquals(listOf(0f, 1f), g.stops)

        val dir = CssValues.parseGradient("linear-gradient(to top right, red, blue, lime)")!!
        assertEquals(45f, dir.angleDeg)
        assertEquals(3, dir.colors.size)
        assertEquals(emptyList<Float>(), dir.stops)

        assertEquals(180f, CssValues.parseGradient("linear-gradient(#000, #fff)")!!.angleDeg)
        assertEquals(90f, CssValues.parseGradient("linear-gradient(0.25turn, #000, #fff)")!!.angleDeg)
        assertNull(CssValues.parseGradient("#000"))
    }

    @Test
    fun lengthsAndUrls() {
        assertEquals(12f, CssValues.lengthDp("12px"))
        assertEquals(8f, CssValues.lengthDp("0.5rem"))
        assertNull(CssValues.lengthDp("50%"))
        assertEquals(6f, CssValues.blurDp("blur(6px) saturate(2)"))
        assertEquals("a b.png", CssValues.firstUrl("url('a b.png') no-repeat"))
        assertEquals("x.png", CssValues.firstUrl("url(x.png)"))
    }
}
