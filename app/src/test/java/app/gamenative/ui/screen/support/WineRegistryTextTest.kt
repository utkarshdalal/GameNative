package app.gamenative.ui.screen.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WineRegistryTextTest {

    private val userReg = listOf(
        "WINE REGISTRY Version 2",
        ";; All keys relative to REGISTRY\\\\User\\\\S-1-5-21-0-0-0-1000",
        "",
        "#arch=win64",
        "",
        "[Control Panel\\\\Desktop] 1751446770",
        "#time=1dbeb2f9e1d3dfa",
        "\"UserPreferencesMask\"=hex:30,00,02,80,12,00,00,00",
        "\"WheelScrollLines\"=\"3\"",
        "",
        "[Control Panel\\\\Desktop\\\\WindowMetrics] 1751446770",
        "#time=1dbeb2f9e1d3dfa",
        "\"CaptionFont\"=hex:f5,ff,ff,ff,00,00,00,00,00,00,00,00,00,00,00,00,bc,02,00,00,\\",
        "  00,00,00,00,00,00,00,22,54,00,61,00",
        "\"CaptionHeight\"=\"-270\"",
        "",
        "[Control Panel\\\\International] 1751446770",
        "#time=1dbeb2f9e1d3dfa",
        "\"sCurrency\"=\"\\x20b9\"",
        "",
    ).joinToString("\n")

    private val key = "Software\\IO Interactive\\007 First Light"

    @Test
    fun encodesValuesLikeWine() {
        assertEquals("dword:00000064", WineRegistryText.encodeValue("dword", "64"))
        assertEquals("dword:ffffffff", WineRegistryText.encodeValue("dword", "FFFFFFFF"))
        assertEquals("hex(b):ff,ff,ff,ff,01,00,00,00", WineRegistryText.encodeValue("qword", "1ffffffff"))
        assertEquals("hex:0a,ff,10", WineRegistryText.encodeValue("binary", "0aFF10"))
        assertEquals("hex:", WineRegistryText.encodeValue("binary", ""))
        assertEquals("\"C:\\\\users\\\\xuser \\\"x\\\"\"", WineRegistryText.encodeValue("string", "C:\\users\\xuser \"x\""))
        assertEquals("\"\\x20b9\"", WineRegistryText.encodeValue("string", "\u20b9"))
        assertEquals("\"a\\xe4z\"", WineRegistryText.encodeValue("string", "a\u00e4z"))
        assertEquals("\"\\x00e4a\"", WineRegistryText.encodeValue("string", "\u00e4a"))
        assertEquals("\\xd83c\\xdf0e", WineRegistryText.escape("\ud83c\udf0e", "[]"))
        assertEquals("@", WineRegistryText.encodeName(""))
        assertEquals("\"a\\\"b\"", WineRegistryText.encodeName("a\"b"))
    }

    @Test
    fun createsAMissingSectionAndRestoresTheFileExactly() {
        val changes = listOf(
            WineRegistryText.Change("ResolutionScale", WineRegistryText.encodeValue("dword", "64")),
            WineRegistryText.Change("", WineRegistryText.encodeValue("string", "def")),
            WineRegistryText.Change("Old", null),
        )
        val merged = WineRegistryText.merge(userReg, key, changes, 1_759_640_387_123L)
        assertFalse(merged.sectionExisted)
        assertTrue(merged.priors.all { it.lines == null })
        val tail = merged.text.removePrefix(userReg.removeSuffix("\n"))
        assertEquals(
            "\n\n[Software\\\\IO Interactive\\\\007 First Light] 1759640387\n#time=1dc35b4df502830\n\"ResolutionScale\"=dword:00000064\n@=\"def\"\n",
            tail,
        )
        assertEquals(userReg, WineRegistryText.restore(merged.text, key, false, merged.priors, 0L))
    }

    @Test
    fun replacesExistingValuesInPlaceIgnoringCase() {
        val merged = WineRegistryText.merge(
            userReg,
            "control panel\\desktop",
            listOf(
                WineRegistryText.Change("userpreferencesmask", WineRegistryText.encodeValue("binary", "01")),
                WineRegistryText.Change("WheelScrollLines", null),
                WineRegistryText.Change("New", WineRegistryText.encodeValue("dword", "1")),
            ),
            0L,
        )
        assertTrue(merged.sectionExisted)
        assertEquals(listOf("\"UserPreferencesMask\"=hex:30,00,02,80,12,00,00,00"), merged.priors[0].lines)
        assertEquals(listOf("\"WheelScrollLines\"=\"3\""), merged.priors[1].lines)
        assertNull(merged.priors[2].lines)
        assertTrue(merged.text.contains("#time=1dbeb2f9e1d3dfa\n\"UserPreferencesMask\"=hex:01\n\"New\"=dword:00000001\n\n[Control Panel\\\\Desktop\\\\WindowMetrics]"))
        val read = WineRegistryText.read(merged.text, "Control Panel\\Desktop", listOf("UserPreferencesMask", "WheelScrollLines"))
        assertEquals("hex:01", read["UserPreferencesMask"])
        assertNull(read["WheelScrollLines"])
        assertEquals(userReg, WineRegistryText.restore(merged.text, "Control Panel\\Desktop", true, merged.priors, 0L))
    }

    @Test
    fun capturesMultiLineHexValues() {
        val merged = WineRegistryText.merge(userReg, "Control Panel\\Desktop\\WindowMetrics", listOf(WineRegistryText.Change("CaptionFont", null)), 0L)
        assertEquals(2, merged.priors[0].lines?.size)
        assertTrue(merged.text.contains("#time=1dbeb2f9e1d3dfa\n\"CaptionHeight\"=\"-270\""))
        val restored = WineRegistryText.restore(merged.text, "Control Panel\\Desktop\\WindowMetrics", true, merged.priors, 0L)
        assertEquals(userReg.split('\n').sorted(), restored.split('\n').sorted())
        assertTrue(restored.contains("\"CaptionHeight\"=\"-270\"\n\"CaptionFont\"=hex:f5,ff,ff,ff,00,00,00,00,00,00,00,00,00,00,00,00,bc,02,00,00,\\\n  00,"))
    }

    @Test
    fun canonicalFormIgnoresWineReformatting() {
        assertEquals(
            WineRegistryText.canonical("hex:f5,ff,\\\n  00,01"),
            WineRegistryText.canonical(WineRegistryText.encodeValue("binary", "f5ff0001")),
        )
        assertEquals(WineRegistryText.canonical("dword:00000064"), WineRegistryText.canonical(WineRegistryText.encodeValue("dword", "064")))
        assertEquals("sz:\u20b9", WineRegistryText.canonical("\"\\x20b9\""))
        assertEquals("sz:C:\\a \"b\"", WineRegistryText.canonical("\"C:\\\\a \\\"b\\\"\""))
    }
}
