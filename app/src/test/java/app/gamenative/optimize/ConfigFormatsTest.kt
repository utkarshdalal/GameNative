package app.gamenative.optimize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigFormatsTest {

    private fun set(section: String, key: String, value: String) = FileOp("set", section, key, value)

    private fun delete(section: String, key: String) = FileOp("delete", section, key, null)

    @Test
    fun iniReplacesInPlaceAndKeepsEverythingElse() {
        val text = "; comment\n[Rendering]\nGrassDensity = 2400\nShadows=High\n\n[Audio]\nVolume=1\n"
        val out = IniFormat.apply(text, listOf(set("Rendering", "GrassDensity", "800")))
        assertEquals("; comment\n[Rendering]\nGrassDensity = 800\nShadows=High\n\n[Audio]\nVolume=1\n", out)
    }

    @Test
    fun iniAppendsToSectionBeforeTrailingBlankLines() {
        val text = "[Rendering]\nShadows=High\n\n[Audio]\nVolume=1\n"
        val out = IniFormat.apply(text, listOf(set("rendering", "Grass", "0")))
        assertEquals("[Rendering]\nShadows=High\nGrass=0\n\n[Audio]\nVolume=1\n", out)
    }

    @Test
    fun iniCreatesMissingSectionAtTheEnd() {
        val out = IniFormat.apply("[A]\nx=1\n", listOf(set("B", "y", "2")))
        assertEquals("[A]\nx=1\n\n[B]\ny=2\n", out)
    }

    @Test
    fun iniKeysBeforeTheFirstSectionHaveAnEmptySection() {
        val text = "top=1\n[A]\ntop=2\n"
        assertEquals("1", IniFormat.read(text, set("", "top", "")))
        assertEquals("top=9\n[A]\ntop=2\n", IniFormat.apply(text, listOf(set("", "top", "9"))))
    }

    @Test
    fun iniDeleteRemovesTheLine() {
        val out = IniFormat.apply("[A]\nx=1\ny=2\n", listOf(delete("A", "x")))
        assertEquals("[A]\ny=2\n", out)
    }

    @Test
    fun iniKeepsCrlf() {
        val out = IniFormat.apply("[A]\r\nx=1\r\n", listOf(set("A", "x", "5"), set("A", "z", "6")))
        assertEquals("[A]\r\nx=5\r\nz=6\r\n", out)
    }

    @Test
    fun jsonSetsNestedAndArrayPathsKeepingOrder() {
        val text = "{\n  \"b\": 1,\n  \"a\": {\n    \"c\": \"low\"\n  },\n  \"_settings\": [\n    {\n      \"Name\": \"x\"\n    }\n  ]\n}\n"
        val out = JsonFormat.apply(
            text,
            listOf(set("", "a.c", "high"), set("", "_settings[0].Name", "y"), set("", "b", "2.50"), set("", "d.e", "true")),
        )
        assertEquals("high", JsonFormat.read(out, set("", "a.c", "")))
        assertEquals("y", JsonFormat.read(out, set("", "_settings[0].Name", "")))
        assertEquals("2.50", JsonFormat.read(out, set("", "b", "")))
        assertEquals("true", JsonFormat.read(out, set("", "d.e", "")))
        assertTrue(out.indexOf("\"b\"") < out.indexOf("\"a\""))
        assertTrue(out.contains("\"b\": 2.50"))
        assertTrue(out.contains("\"e\": true"))
        assertTrue(out.endsWith("}\n"))
    }

    @Test
    fun jsonKeepsStringTypeOfExistingValue() {
        val out = JsonFormat.apply("{\"q\":\"3\"}", listOf(set("", "q", "1")))
        assertEquals("{\"q\":\"1\"}", out)
    }

    @Test
    fun jsonDeleteRemovesKey() {
        val out = JsonFormat.apply("{\"a\":1,\"b\":2}", listOf(delete("", "a")))
        assertEquals("{\"b\":2}", out)
        assertNull(JsonFormat.read(out, delete("", "a")))
    }

    @Test
    fun jsonRefusesOutOfRangeIndex() {
        try {
            JsonFormat.apply("{\"a\":[]}", listOf(set("", "a[2]", "1")))
            fail("expected a FormatException")
        } catch (e: FormatException) {
            // expected
        }
    }

    @Test
    fun xmlSetsAttributeOnNamedChildAndKeepsDeclaration() {
        val text = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Settings><Setting Name=\"Grass\" Value=\"3\"/><Setting Name=\"Fog\" Value=\"1\"/></Settings>\n"
        val out = XmlFormat.apply(text, listOf(set("", "Settings/Setting[Grass]@Value", "1")))
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Settings>"))
        assertEquals("1", XmlFormat.read(out, set("", "Settings/Setting[Grass]@Value", "")))
        assertEquals("1", XmlFormat.read(out, set("", "Settings/Setting[Fog]@Value", "")))
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun xmlSetsElementTextAndDeletesAttribute() {
        val text = "<Root><Video quality=\"high\">60</Video></Root>"
        val out = XmlFormat.apply(text, listOf(set("", "Video", "30"), delete("", "Video@quality")))
        assertEquals("30", XmlFormat.read(out, set("", "Root/Video", "")))
        assertNull(XmlFormat.read(out, delete("", "Video@quality")))
    }

    @Test
    fun cfgPatchesEachLineStyle() {
        val text = "seta r_mode \"4\"\nfps_max 120\nvsync = 1\n// note\n"
        val out = CfgFormat.apply(text, listOf(set("", "r_mode", "2"), set("", "fps_max", "60"), set("", "vsync", "0")))
        assertEquals("seta r_mode \"2\"\nfps_max 60\nvsync = 0\n// note\n", out)
    }

    @Test
    fun cfgSelectsRepeatsCountingFromZero() {
        val text = "bind w\nbind s\n"
        val out = CfgFormat.apply(text, listOf(set("", "bind#1", "x")))
        assertEquals("bind w\nbind x\n", out)
        assertEquals("w", CfgFormat.read(out, set("", "bind#0", "")))
    }

    @Test
    fun cfgAppendsMissingKeyInTheFileStyle() {
        val out = CfgFormat.apply("seta a \"1\"\n", listOf(set("", "b", "2")))
        assertEquals("seta a \"1\"\nseta b \"2\"\n", out)
    }

    @Test
    fun cfgRefusesLineKeysAndMissingKeysInNestedFiles() {
        try {
            CfgFormat.apply("a 1\n", listOf(set("", "_line_3", "x")))
            fail("expected a FormatException")
        } catch (e: FormatException) {
            // expected
        }
        try {
            CfgFormat.apply("block {\n  a 1\n}\n", listOf(set("", "b", "2")))
            fail("expected a FormatException")
        } catch (e: FormatException) {
            // expected
        }
        assertEquals("block {\n  a 5\n}\n", CfgFormat.apply("block {\n  a 1\n}\n", listOf(set("", "a", "5"))))
    }

    @Test
    fun applyVerifiedReadsEverySetKeyBack() {
        val out = ConfigFormats.applyVerified(IniFormat, "[A]\nx=1\n", listOf(set("A", "x", "2"), set("A", "y", "3")))
        assertEquals("[A]\nx=2\ny=3\n", out)
    }

    @Test
    fun utf16BomRoundTrips() {
        val original = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[A]\r\nx=1\r\n".toByteArray(Charsets.UTF_16LE)
        val codec = TextCodecs.codecFor(original, "default")
        val text = TextCodecs.decode(original, codec)
        assertEquals("[A]\r\nx=1\r\n", text)
        val back = TextCodecs.encode(text, codec)
        assertTrue(back.contentEquals(original))
    }

    @Test
    fun unixLfEncodingDropsCarriageReturns() {
        val codec = TextCodecs.codecFor("a=1\r\n".toByteArray(), "UTF8UnixLF")
        assertEquals("a=1\n", String(TextCodecs.encode("a=1\r\n", codec), Charsets.UTF_8))
    }

    @Test
    fun unknownFormatAndEncodingAreRefused() {
        assertNull(ConfigFormats.forName("yaml"))
        try {
            TextCodecs.codecFor(ByteArray(0), "latin1")
            fail("expected a FormatException")
        } catch (e: FormatException) {
            // expected
        }
    }
}
