package app.gamenative.optimize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(JsonFormat.canApply("{\"a\":[]}", set("", "a[2]", "1")))
        assertEquals("{\"a\":[]}", JsonFormat.apply("{\"a\":[]}", listOf(set("", "a[2]", "1"))))
    }

    @Test
    fun jsonEditKeepsFormattingAndAddsMembersInTheSiblingsStyle() {
        val text = "{\r\n    \"video\" : {\r\n        \"shadows\" : 3,\r\n        \"name\" : \"hi\"\r\n    }\r\n}"
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set("", "video.shadows", "1"), set("", "video.grass", "0")))
        assertEquals("{\r\n    \"video\" : {\r\n        \"shadows\" : 1,\r\n        \"name\" : \"hi\",\r\n        \"grass\" : 0\r\n    }\r\n}", out)
        assertEquals(text, JsonFormat.apply(out, listOf(set("", "video.shadows", "3"), delete("", "video.grass"))))
    }

    @Test
    fun jsonStringsAreEscapedAndDuplicateMembersUseTheLast() {
        val out = JsonFormat.apply("{\"a\":\"x\",\"a\":\"y\"}", listOf(set("", "a", "say \"hi\"")))
        assertEquals("{\"a\":\"x\",\"a\":\"say \\\"hi\\\"\"}", out)
        assertEquals("say \"hi\"", JsonFormat.read(out, set("", "a", "")))
    }

    @Test
    fun jsonThatIsNotStrictIsAddressedAsStructuredText() {
        val text = "{\n  \"gfx\": {\n    \"tex\": 2,\n  },\n}\n"
        assertEquals("2", JsonFormat.read(text, set("", "gfx.tex", "")))
        assertEquals("{\n  \"gfx\": {\n    \"tex\": 0,\n  },\n}\n", ConfigFormats.applyVerified(JsonFormat, text, listOf(set("", "gfx.tex", "0"))))
    }

    @Test
    fun xmlChildIsKeyedByTheFirstKeyingAttributeItHas() {
        val text = "<P><Q><quality V=\"high\" id=\"custom\"/><quality V=\"mid\" Key=\"low\"/></Q></P>\n"
        val out = ConfigFormats.applyVerified(XmlFormat, text, listOf(set("", "P/Q/quality[custom]", "low"), set("", "P/Q/quality[low]", "1")))
        assertEquals("<P><Q><quality V=\"low\" id=\"custom\"/><quality V=\"1\" Key=\"low\"/></Q></P>\n", out)
    }

    @Test
    fun xmlUnkeyedSiblingsAreCountedPerTag() {
        val text = "<R><s v=\"1\"/><t v=\"2\"/><s v=\"3\"/><s name=\"n\" v=\"4\"/><s v=\"5\"/></R>"
        assertEquals("1", XmlFormat.read(text, set("", "R/s@v", "")))
        assertEquals("3", XmlFormat.read(text, set("", "R/s[1]@v", "")))
        assertEquals("5", XmlFormat.read(text, set("", "R/s[2]@v", "")))
        assertEquals("4", XmlFormat.read(text, set("", "R/s[n]", "")))
        assertEquals(
            "<R><s v=\"1\"/><t v=\"2\"/><s v=\"3\"/><s name=\"n\" v=\"4\"/><s v=\"9\"/></R>",
            ConfigFormats.applyVerified(XmlFormat, text, listOf(set("", "R/s[2]@v", "9"))),
        )
    }

    @Test
    fun xmlKeyWithoutAttributeIsTheValueAttributeOrTheText() {
        val text = "<R>\n  <o name=\"a\" value=\"1\" min=\"0\"/>\n  <o name=\"b\" x=\"1\" y=\"2\">\n    7\n  </o>\n  <o name=\"c\" only='3'/>\n</R>\n"
        val out = ConfigFormats.applyVerified(XmlFormat, text, listOf(set("", "R/o[a]", "2"), set("", "R/o[b]", "8"), set("", "R/o[c]", "4")))
        assertEquals("<R>\n  <o name=\"a\" value=\"2\" min=\"0\"/>\n  <o name=\"b\" x=\"1\" y=\"2\">\n    8\n  </o>\n  <o name=\"c\" only='4'/>\n</R>\n", out)
        assertEquals("0", XmlFormat.read(out, set("", "R/o[a]@min", "")))
        assertEquals("1", XmlFormat.read(out, set("", "R/o[b]@x", "")))
    }

    @Test
    fun xmlKeepsDeclarationCommentsAndQuotes() {
        val text = "﻿<?xml version='1.0'?>\r\n<!-- user file -->\r\n<Settings>\r\n  <Setting Name='Grass' Value = '3' />\r\n</Settings>"
        val out = ConfigFormats.applyVerified(XmlFormat, text, listOf(set("", "Settings/Setting[Grass]", "1")))
        assertEquals(text.replace("'3'", "'1'"), out)
    }

    @Test
    fun xmlAddsAMissingAttributeToItsElement() {
        val text = "<R><a x=\"1\"/></R>"
        val out = ConfigFormats.applyVerified(XmlFormat, text, listOf(set("", "R/a@y", "2")))
        assertEquals("<R><a x=\"1\" y=\"2\"/></R>", out)
        assertEquals(text, XmlFormat.apply(out, listOf(delete("", "R/a@y"))))
    }

    @Test
    fun xmlAddsMissingElementsKeyedLikeTheFilesOthers() {
        val text = "<P>\n\t<A>\n\t\t<q id=\"low\" x=\"1\" y=\"2\" />\n\t</A>\n</P>\n"
        val ops = listOf(set("", "P/B/q[low]@x", "0"), set("", "P/B/q[low]@y", "3"), set("", "P/A/q[high]@x", "5"), set("", "P/A/q[high]@y", "6"))
        assertEquals(ops, ConfigFormats.writableOps(XmlFormat, text, ops))
        val out = ConfigFormats.applyVerified(XmlFormat, text, ops)
        assertEquals(
            "<P>\n\t<A>\n\t\t<q id=\"low\" x=\"1\" y=\"2\" />\n\t\t<q id=\"high\" x=\"5\" y=\"6\" />\n\t</A>\n" +
                "\t<B>\n\t\t<q id=\"low\" x=\"0\" y=\"3\" />\n\t</B>\n</P>\n",
            out,
        )
    }

    @Test
    fun xmlNeverAddsAnElementItCannotKeyOrWouldReadBackDifferently() {
        val text = "<R><a x=\"1\"/><q name=\"low\" v=\"1\" w=\"1\"/></R>"
        assertTrue(XmlFormat.canApply(text, set("", "R/b@y", "2")))
        assertFalse(XmlFormat.canApply(text, set("", "R/a[1]@y", "2")))
        assertFalse(XmlFormat.canApply(text, set("", "R/c[custom]@y", "2")))
        assertFalse(XmlFormat.canApply(text, set("", "R/q[custom]", "2")))
        // One attribute besides the key would read back as the element's value, not as the attribute.
        assertFalse(XmlFormat.canApply(text, set("", "R/q[custom]@v", "2")))
        assertEquals(emptyList<FileOp>(), ConfigFormats.writableOps(XmlFormat, text, listOf(set("", "R/q[custom]@v", "2"))))
    }

    @Test
    fun xmlRefusesWhatWouldChangeOtherKeys() {
        val one = "<R><o name=\"a\" v=\"1\"/></R>"
        assertFalse(XmlFormat.canApply(one, set("", "R/o[a]@w", "2")))
        assertFalse(XmlFormat.canApply(one, set("", "R/o[a]", "say \"x\"")))
        assertFalse(XmlFormat.canApply("<R><t>a<b/>c</t></R>", set("", "R/t", "z")))
        assertFalse(XmlFormat.canApply("<R><t>5</t></R>", set("", "R/t", "<b/>")))
        assertTrue(XmlFormat.canApply("<R><t>5</t></R>", set("", "R/t", "6")))
    }

    @Test
    fun iniWritesTheLastOfRepeatedKeysAndAddressesBySectionAndKey() {
        val text = "[A]\nx = 1\n[B.C]\ny=2\n[A]\nx = 3\n"
        val out = ConfigFormats.applyVerified(IniFormat, text, listOf(set("A", "x", "5"), set("B", "C.y", "4")))
        assertEquals("[A]\nx = 1\n[B.C]\ny=4\n[A]\nx = 5\n", out)
    }

    @Test
    fun iniDoesNotAddAKeyAboveTheFirstSectionOfAFileWithoutOne() {
        assertFalse(IniFormat.canApply("[SystemSettings]\nShadows=1\n", set("", "Decals", "0")))
        assertTrue(IniFormat.canApply("top=1\n[S]\na=1\n", set("", "Decals", "0")))
        assertEquals("a=1\nb = 2\nc = 3", IniFormat.apply("a=1\nb = 2", listOf(set("", "c", "3"))))
    }

    @Test
    fun iniKeepsLoneCarriageReturns() {
        assertEquals("[A]\rx=2\ry=3", IniFormat.apply("[A]\rx=1", listOf(set("A", "x", "2"), set("A", "y", "3"))))
    }

    @Test
    fun cfgPatchesEachLineStyle() {
        val text = "seta r_mode \"4\"\nfps_max 120\nvsync = 1\n// note\n\"mat_picmip\"  \"2\"\nScale(0.5)\n"
        val ops = listOf(set("", "r_mode", "2"), set("", "fps_max", "60"), set("", "vsync", "0"), set("", "mat_picmip", "1"), set("", "Scale", "1"))
        val out = ConfigFormats.applyVerified(CfgFormat, text, ops)
        assertEquals("seta r_mode \"2\"\nfps_max 60\nvsync = 0\n// note\n\"mat_picmip\"  \"1\"\nScale(1)\n", out)
    }

    @Test
    fun cfgRepeatedNamesCountFromTheSecondOccurrence() {
        val text = "Shadow\n{\n  Enabled = 1\n}\nWater\n{\n  Enabled = 1\n}\n"
        assertEquals("1", CfgFormat.read(text, set("", "_1.Enabled", "")))
        val out = ConfigFormats.applyVerified(CfgFormat, text, listOf(set("", "_3.Enabled", "0")))
        assertEquals("Shadow\n{\n  Enabled = 1\n}\nWater\n{\n  Enabled = 0\n}\n", out)
        val flat = "Enabled = 1\nEnabled = 1\n"
        assertEquals("Enabled = 1\nEnabled = 0\n", ConfigFormats.applyVerified(CfgFormat, flat, listOf(set("", "Enabled#2", "0"))))
    }

    @Test
    fun cfgNamedBlocksPrefixTheirEntriesAndALeafNameFindsTheOnlyMatch() {
        val text = "\"VideoConfig\"\n{\n\t\"setting.mat_picmip\"\t\t\"2\"\n}\n"
        assertEquals("2", CfgFormat.read(text, set("", "VideoConfig.setting.mat_picmip", "")))
        assertEquals(text.replace("\"2\"", "\"0\""), ConfigFormats.applyVerified(CfgFormat, text, listOf(set("", "setting.mat_picmip", "0"))))
    }

    @Test
    fun cfgAppendsMissingKeyInTheFileStyle() {
        val out = ConfigFormats.applyVerified(CfgFormat, "seta a \"1\" // first\n", listOf(set("", "b", "2")))
        assertEquals("seta a \"1\" // first\nseta b \"2\"\n", out)
    }

    @Test
    fun cfgRefusesLineKeysFlagsWithoutValuesAndMissingKeysInNestedFiles() {
        assertFalse(CfgFormat.canApply("a 1\nsome words here\n", set("", "_line_0", "x")))
        assertFalse(CfgFormat.canApply("-novid -high\n", set("", "-high", "1")))
        assertFalse(CfgFormat.canApply("block {\n  a 1\n}\n", set("", "b", "2")))
        assertEquals("block {\n  a 5\n}\n", CfgFormat.apply("block {\n  a 1\n}\n", listOf(set("", "a", "5"))))
        assertFalse(CfgFormat.canApply("name \"x\"\n", set("", "name", "say \"hi\"")))
    }

    @Test
    fun cfgFlagValuesAndBindsAreAddressedByName() {
        val text = "-width 1920 -fullscreen\nbind \"F1\" \"screenshot\"\n"
        assertEquals("present", CfgFormat.read(text, set("", "-fullscreen", "")))
        val out = ConfigFormats.applyVerified(CfgFormat, text, listOf(set("", "-width", "1280"), set("", "bind F1", "quit")))
        assertEquals("-width 1280 -fullscreen\nbind \"F1\" \"quit\"\n", out)
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
