package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriconfTest {

    private val existing = """
        <?xml version="1.0" standalone="yes"?>
        <driconf>
            <device driver="radeonsi">
                <application name="Other game" executable="other.exe">
                    <option name="radeonsi_zerovram" value="true" />
                </application>
            </device>
            <device>
                <application name="Everything">
                    <option name="vk_dont_care_as_load" value="false" />
                </application>
            </device>
        </driconf>
    """.trimIndent() + "\n"

    @Test
    fun emptyFileGetsOnlyOurElement() {
        val merged = Driconf.merge("", "Game.exe", listOf("vk_dont_care_as_load" to "true"))
        assertEquals(mapOf("vk_dont_care_as_load" to "true"), Driconf.read(merged.text, "Game.exe"))
        assertEquals(1, Driconf.applicationCount(merged.text))
        assertEquals(listOf("vk_dont_care_as_load" to null), merged.priors)
        assertTrue(merged.text.contains("<application name=\"GameNative optimize\" executable=\"Game.exe\">"))
    }

    @Test
    fun noExecutableLeavesTheAttributeOut() {
        val merged = Driconf.merge("", null, listOf("a" to "1"))
        assertTrue(merged.text.contains("<application name=\"GameNative optimize\">"))
        assertEquals(mapOf("a" to "1"), Driconf.read(merged.text, null))
        assertNull(Driconf.read(merged.text, "Game.exe"))
    }

    @Test
    fun otherElementsStayByteForByte() {
        val merged = Driconf.merge(existing, "Game.exe", listOf("tu_x" to "1"))
        val inserted = merged.text.substringAfter("    <device>").substringBefore("\n        <application name=\"Everything\">")
        assertTrue(inserted.contains("GameNative optimize"))
        assertEquals(existing, merged.text.replaceFirst(inserted, ""))
        assertEquals(3, Driconf.applicationCount(merged.text))
        assertEquals(mapOf("tu_x" to "1"), Driconf.read(merged.text, "Game.exe"))
    }

    @Test
    fun secondMergeReplacesOneOptionAndKeepsTheOther() {
        val first = Driconf.merge(existing, "Game.exe", listOf("a" to "1", "b" to "2")).text
        val second = Driconf.merge(first, "Game.exe", listOf("b" to "3"))
        assertEquals(listOf("b" to "2"), second.priors)
        assertEquals(mapOf("a" to "1", "b" to "3"), Driconf.read(second.text, "Game.exe"))
        assertEquals(3, Driconf.applicationCount(second.text))
    }

    @Test
    fun restoringPriorsGivesTheFileBack() {
        val merged = Driconf.merge(existing, "Game.exe", listOf("a" to "1"))
        assertEquals(existing, Driconf.merge(merged.text, "Game.exe", merged.priors).text)
        val fresh = Driconf.merge("", null, listOf("a" to "1"))
        assertEquals(0, Driconf.applicationCount(Driconf.merge(fresh.text, null, fresh.priors).text))
    }

    @Test
    fun executablesGetSeparateElements() {
        val one = Driconf.merge("", "A.exe", listOf("x" to "1")).text
        val two = Driconf.merge(one, "B.exe", listOf("x" to "2")).text
        assertEquals(mapOf("x" to "1"), Driconf.read(two, "A.exe"))
        assertEquals(mapOf("x" to "2"), Driconf.read(two, "B.exe"))
        assertNull(Driconf.read(two, null))
    }

    @Test
    fun valuesAreEscaped() {
        val exe = "R&D \"x\".exe"
        val first = Driconf.merge("", exe, listOf("s" to "a<b>&c")).text
        assertEquals(mapOf("s" to "a<b>&c"), Driconf.read(first, exe))
        assertEquals(mapOf("s" to "a<b>&c", "t" to "1"), Driconf.read(Driconf.merge(first, exe, listOf("t" to "1")).text, exe))
    }

    @Test(expected = FormatException::class)
    fun notADriconfFileIsRefused() {
        Driconf.merge("<foo/>", null, listOf("a" to "1"))
    }

    @Test
    fun drircLeverParses() {
        val lever = Lever.parse(
            JSONObject("""{"lever_type":"drirc","payload":{"options":{"vk_dont_care_as_load":"true","tu_y":1},"executable":"Game.exe"}}"""),
        ) as Lever.Drirc
        assertEquals(setOf("vk_dont_care_as_load" to "true", "tu_y" to "1"), lever.options.toSet())
        assertEquals("Game.exe", lever.executable)
        val global = Lever.parse(JSONObject("""{"lever_type":"drirc","payload":{"options":{"a":"1"}}}""")) as Lever.Drirc
        assertNull(global.executable)
    }

    @Test
    fun badDrircLeversAreUnknown() {
        listOf(
            """{"options":{}}""",
            """{"options":{"bad name":"1"}}""",
            """{"options":{"a":"1"},"executable":"bin/Game.exe"}""",
            """{"options":{"a":"1"},"executable":"bin\\Game.exe"}""",
        ).forEach { payload ->
            val lever = Lever.parse(JSONObject("""{"lever_type":"drirc","payload":$payload}"""))
            assertTrue(payload, lever is Lever.Unknown)
        }
    }
}
