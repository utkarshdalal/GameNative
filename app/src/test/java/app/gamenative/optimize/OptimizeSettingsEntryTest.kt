package app.gamenative.optimize

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizeSettingsEntryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val text = """
        |{
        |    "version": 3,
        |    "groups": [
        |        {
        |            "group": "/video/quality",
        |            "settings": [
        |                {
        |                    "name": "Shadows",
        |                    "kind": "list",
        |                    "value": "High",
        |                    "index": 2,
        |                    "default_index": 1,
        |                    "values": [
        |                        "Off",
        |                        "Low",
        |                        "High"
        |                    ]
        |                },
        |                {
        |                    "name": "Grain",
        |                    "kind": "bool",
        |                    "value": true,
        |                    "default_value": true
        |                },
        |                {
        |                    "name": "Filtering",
        |                    "kind": "numbers",
        |                    "value": 16,
        |                    "index": 3,
        |                    "values": [1, 4, 8, 16]
        |                },
        |                {
        |                    "name": "Upscaler",
        |                    "kind": "list",
        |                    "value": "Auto",
        |                    "index": 0
        |                },
        |                {
        |                    "name": "MinScale",
        |                    "kind": "int",
        |                    "value": 65,
        |                    "min_value": 50
        |                }
        |            ]
        |        },
        |        {
        |            "group": "/game/quality",
        |            "settings": [
        |                {
        |                    "name": "Shadows",
        |                    "kind": "list",
        |                    "value": "High",
        |                    "index": 2,
        |                    "values": ["Off", "Low", "High"]
        |                }
        |            ]
        |        }
        |    ]
        |}
        |
    """.trimMargin().replace("\n", "\r\n")

    private val GAP = "\r\n" + " ".repeat(20)

    private fun entry(name: String) = "**[name=$name].value|values|index"

    private fun set(key: String, value: String) = FileOp("set", "", key, value)

    @Test
    fun aListEntryReadsAndWritesItsPositionAndKeepsEveryOtherByte() {
        assertEquals("2", JsonFormat.read(text, set(entry("Shadows"), "")))
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set(entry("Shadows"), "1")))
        val first = text.indexOf("\"value\": \"High\"")
        val expected = text.substring(0, first) +
            text.substring(first).replaceFirst("\"value\": \"High\",$GAP\"index\": 2", "\"value\": \"Low\",$GAP\"index\": 1")
        assertEquals(expected, out)
        assertEquals("1", JsonFormat.read(out, set(entry("Shadows"), "")))
    }

    @Test
    fun aNameInTwoPlacesIsWrittenWhereItFirstAppears() {
        val out = JsonFormat.apply(text, listOf(set(entry("Shadows"), "0")))
        assertEquals(1, Regex("\"value\": \"Off\"").findAll(out).count())
        assertTrue(out.substring(out.indexOf("/game/quality")).contains("\"value\": \"High\""))
    }

    @Test
    fun aBooleanEntryIsZeroOrOne() {
        assertEquals("1", JsonFormat.read(text, set(entry("Grain"), "")))
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set(entry("Grain"), "0")))
        assertEquals(text.replace("\"value\": true", "\"value\": false"), out)
        assertFalse(JsonFormat.canApply(text, set(entry("Grain"), "2")))
        assertFalse(JsonFormat.canApply(text, set(entry("Grain"), "false")))
    }

    @Test
    fun aNumberListIsWrittenAsTheNumberAtThePosition() {
        assertEquals("3", JsonFormat.read(text, set(entry("Filtering"), "")))
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set(entry("Filtering"), "1")))
        assertEquals(text.replace("\"value\": 16,$GAP\"index\": 3", "\"value\": 4,$GAP\"index\": 1"), out)
    }

    @Test
    fun aPlainNumberIsWrittenAsItIs() {
        assertEquals("65", JsonFormat.read(text, set(entry("MinScale"), "")))
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set(entry("MinScale"), "50")))
        assertEquals(text.replace("\"value\": 65", "\"value\": 50"), out)
    }

    @Test
    fun whatTheFileCannotPlaceIsNotWritten() {
        // a text value with no list of allowed values
        assertNull(JsonFormat.read(text, set(entry("Upscaler"), "")))
        assertFalse(JsonFormat.canApply(text, set(entry("Upscaler"), "1")))
        // a position the list does not have
        assertFalse(JsonFormat.canApply(text, set(entry("Shadows"), "3")))
        assertFalse(JsonFormat.canApply(text, set(entry("Shadows"), "-1")))
        // an entry the file does not have is never added
        assertNull(JsonFormat.read(text, set(entry("Fog"), "")))
        assertFalse(JsonFormat.canApply(text, set(entry("Fog"), "0")))
        assertEquals(text, JsonFormat.apply(text, listOf(set(entry("Fog"), "0"))))
    }

    @Test
    fun puttingThePriorBackRestoresTheBytes() {
        val ops = listOf(set(entry("Shadows"), "0"), set(entry("Grain"), "0"), set(entry("Filtering"), "0"))
        val out = ConfigFormats.applyVerified(JsonFormat, text, ops)
        val priors = ops.map { set(it.key, JsonFormat.read(text, it)!!) }
        assertEquals(text, ConfigFormats.applyVerified(JsonFormat, out, priors))
    }

    @Test
    fun aSelectorPathAddressesAPlainValue() {
        val key = "groups[group=/video/quality].settings[name=MinScale].min_value"
        assertEquals("50", JsonFormat.read(text, set(key, "")))
        val out = ConfigFormats.applyVerified(JsonFormat, text, listOf(set(key, "40")))
        assertEquals(text.replace("\"min_value\": 50", "\"min_value\": 40"), out)
        assertEquals("Shadows", JsonFormat.read(text, set("groups[*].settings[0].name", "")))
        assertFalse(JsonFormat.canApply(text, set("groups[group=/video/quality].settings[name=Fog].value", "1")))
    }

    private fun applyRelative(body: String, vararg ops: FileOp): String {
        val file = File(tmp.root, "settings.json").apply { writeText(body) }
        val lever = Lever.EngineFile("{documentsPath}/settings.json", "json", "default", false, ops.toList())
        val home = tmp.newFolder()
        File(home, "Documents").mkdirs()
        file.copyTo(File(home, "Documents/settings.json"))
        OptimizeApplier.applyFile(0, lever, PathRoots(null, null, home), tmp.newFolder(), true, OptimizeApplier.OpCount())
        return File(home, "Documents/settings.json").readText()
    }

    private fun step(key: String, value: String, vararg ladder: String) = FileOp("set", "", key, value, ladder.toList(), "less")

    @Test
    fun aRelativeStepMovesAnEntryOnlyDown() {
        val out = applyRelative(text, step(entry("Shadows"), "1", "0", "1", "2"), step(entry("Filtering"), "3", "0", "1", "2", "3"))
        assertEquals("1", JsonFormat.read(out, set(entry("Shadows"), "")))
        assertEquals("3", JsonFormat.read(out, set(entry("Filtering"), "")))
        assertEquals(text.length - 1, out.length)
    }

    @Test
    fun aRelativeStepNeverAddsAKeyToAJsonFile() {
        val body = "{\r\n    \"video\": {\r\n        \"shadows\": 3\r\n    }\r\n}\r\n"
        assertEquals(body, applyRelative(body, step("Shadows", "1", "0", "1", "2", "3")))
        assertEquals(body.replace("3", "1"), applyRelative(body, step("video.shadows", "1", "0", "1", "2", "3")))
        val created = applyRelative(body, step("Shadows", "1", "0", "1", "2", "3").copy(create = true))
        assertEquals("1", JsonFormat.read(created, set("Shadows", "")))
    }

    @Test
    fun anIniKeyInAnySectionIsWrittenEverywhereAndNeverAdded() {
        val ini = "[options]\r\nshadows = 3\r\nfog = 1\r\n[profile]\r\nshadows = 3\r\n"
        val op = FileOp("set", IniFormat.ANY_SECTION, "shadows", "1")
        assertEquals("3", IniFormat.read(ini, op))
        assertEquals(ini.replace("shadows = 3", "shadows = 1"), ConfigFormats.applyVerified(IniFormat, ini, listOf(op)))
        assertFalse(IniFormat.canApply(ini, FileOp("set", IniFormat.ANY_SECTION, "grass", "1")))
        assertEquals(ini, IniFormat.apply(ini, listOf(FileOp("set", IniFormat.ANY_SECTION, "grass", "1"))))
    }

    @Test
    fun aStructuredTextFileWithSectionLinesGetsNoNewKey() {
        val cfg = "[Display]\r\nVSync=1\r\n[Quality]\r\nShadows=3\r\n"
        assertEquals(cfg.replace("Shadows=3", "Shadows=1"), ConfigFormats.applyVerified(CfgFormat, cfg, listOf(set("Shadows", "1"))))
        assertFalse(CfgFormat.canApply(cfg, set("Grass", "0")))
        assertTrue(CfgFormat.canApply("Shadows 3\r\n", set("Grass", "0")))
    }
}
