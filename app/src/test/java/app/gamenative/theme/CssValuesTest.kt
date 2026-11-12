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

    @Test
    fun calcOnTheReferenceViewport() {
        assertEquals(648.0, CssValues.lengthPx("calc(100vh - 80px - 14px - 58px)")!!, 1e-6)
        assertEquals(336.0, CssValues.lengthPx("calc(80px + 20vw)")!!, 1e-6)
        assertEquals(32.0, CssValues.lengthPx("2em")!!, 1e-6)
        assertEquals(0.0, CssValues.lengthPx("0")!!, 1e-6)
        assertEquals(5.0, CssValues.quantity("calc(-1*-5deg)")!!.value, 1e-6)
        assertEquals(90.0, CssValues.quantity("0.25turn")!!.value, 1e-6)
        assertEquals(CssValues.Dim.NUMBER, CssValues.quantity("calc(2 * (0.5 + 0.25))")!!.dim)
        assertNull(CssValues.lengthPx("50%"))
        assertNull(CssValues.lengthPx("calc(100px + 2deg)"))
        assertNull(CssValues.lengthPx("var(--x)"))
        assertNull(CssValues.lengthPx("fit-content"))
    }

    @Test
    fun transformsAndShadows() {
        assertEquals(0.7, CssValues.transformScale("translateX(4px) scale(0.7)")!!, 1e-6)
        assertNull(CssValues.transformScale("scale(1, 2)"))
        assertEquals(-5.0, CssValues.transformTilt("skew(-5deg) scale(1.05)")!!, 1e-6)
        assertNull(CssValues.transformTilt("translateX(40%)"))
        assertEquals(24f, CssValues.glowDp("0px 0px 24px 0px rgba(0, 255, 255, 0.8)", "box-shadow"))
        assertNull(CssValues.glowDp("inset 0 0 24px #fff", "box-shadow"))
        val stroke = CssValues.textShadowStroke("2px 2px 0 #111, -2px 2px 0 #111, 2px -2px 0 #111, -2px -2px 0 #111")!!
        assertEquals(2f, stroke.first)
        assertEquals("#111111", stroke.second.toHex())
    }
}
