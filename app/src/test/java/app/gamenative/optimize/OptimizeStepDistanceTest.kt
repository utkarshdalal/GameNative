package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimizeStepDistanceTest {

    private val quality = listOf("Low", "Low", "Low", "Medium", "Medium", "Medium", "High", "High", "High", "Ultra", "Ultra", "Ultra")
    private val grass = listOf("0.1", "0.15", "0.2", "0.25", "0.3", "0.35", "0.4", "0.45", "0.5", "0.6", "0.8", "1")
    private val cascade = listOf("5", "5", "4", "4", "3", "3", "2", "2", "1", "1", "0", "0")

    private fun step(current: String?, ladder: List<String?>, lowerIs: String?, down: Int, target: String = "fallback") =
        OptimizeRelative.retarget(current, target, ladder, lowerIs, down)

    @Test
    fun `a key at the top moves the distance asked, a key lower down in proportion to its level`() {
        assertEquals("0.5", step("1", grass, OptimizeRelative.LESS, 3))
        assertEquals("0.25", step("1", grass, OptimizeRelative.LESS, 8))
        // From level 5 of 11: three levels become two, eight become four.
        assertEquals("0.25", step("0.35", grass, OptimizeRelative.LESS, 3))
        assertEquals("0.15", step("0.35", grass, OptimizeRelative.LESS, 8))
        // Near the bottom a step is still one level.
        assertEquals("0.1", step("0.15", grass, OptimizeRelative.LESS, 3))
    }

    @Test
    fun `a big step from the middle of The Witcher 3's list stops short of the bottom`() {
        val decals = listOf("4", "8", "8", "9", "9", "13", "15", "15", "30", "40", "80", "80")
        assertEquals("8", step("15", decals, OptimizeRelative.LESS, 8))
        val density = listOf("200", "600", "1200", "1400", "1400", "1800", "1800", "1800", "1800", "2000", "2200", "2400")
        assertEquals("600", step("1400", density, OptimizeRelative.LESS, 8))
        assertEquals("1200", step("1400", density, OptimizeRelative.LESS, 3))
    }

    @Test
    fun `a value that spans several levels is counted from its highest one, so a step never goes further than asked`() {
        assertEquals("Medium", step("High", quality, "enum", 3))
        assertEquals("Medium", step("Ultra", quality, "enum", 8))
        assertEquals("Low", step("Medium", quality, "enum", 8))
        // A distance that stays inside the value's own levels changes nothing.
        assertNull(step(" high ", quality, "enum", 1))
        assertNull(step("Ultra", quality, "enum", 2))
        assertNull(step("Medium", quality, "enum", 3))
        // A key that holds one value from level 1 up is not thrown to the bottom by a big step from the top.
        val flat = listOf("0.2", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1")
        assertNull(step("1", flat, OptimizeRelative.LESS, 8))
        assertEquals("0.2", step("1", flat, OptimizeRelative.LESS, 11))
    }

    @Test
    fun `lower quality may be the bigger number`() {
        assertEquals("1", step("0", cascade, OptimizeRelative.MORE, 3))
        assertEquals("4", step("3", cascade, OptimizeRelative.MORE, 5))
        assertEquals("5", step("3", cascade, OptimizeRelative.MORE, 11))
    }

    @Test
    fun `a value between two steps counts from the step under it`() {
        assertEquals("0.3", step("0.42", grass, OptimizeRelative.LESS, 3))
        // Above the whole ladder is the top.
        assertEquals("0.5", step("2", grass, OptimizeRelative.LESS, 3))
    }

    @Test
    fun `nothing is written at the bottom, for no distance, or below the ladder`() {
        assertNull(step("Low", quality, "enum", 3))
        assertNull(step("0.1", grass, OptimizeRelative.LESS, 5))
        assertNull(step("High", quality, "enum", 0))
        assertNull(step("0.05", grass, OptimizeRelative.LESS, 3, target = "0.5"))
    }

    @Test
    fun `a level without a value gives way to the nearest one toward the current value`() {
        val gaps = listOf(null, "1", null, null, "2", "3", "3", "3", "4", "4", "4", "4")
        assertEquals("3", step("4", gaps, OptimizeRelative.LESS, 5))
        assertEquals("2", step("4", gaps, OptimizeRelative.LESS, 9))
        assertEquals("1", step("2", gaps, OptimizeRelative.LESS, 11))
        // Nothing between the level reached and the current one: no move.
        assertNull(step("2", gaps, OptimizeRelative.LESS, 4))
    }

    @Test
    fun `a negative distance goes back up in the same proportion, never past the top`() {
        assertEquals("High", step("Medium", quality, "enum", -1))
        assertEquals("0.4", step("0.3", grass, OptimizeRelative.LESS, -4))
        assertEquals("1", step("0.8", grass, OptimizeRelative.LESS, -6))
        assertNull(step("Ultra", quality, "enum", -2))
    }

    @Test
    fun `a key the game does not have, or a value the ladder cannot place, takes the step's own value`() {
        assertEquals("fallback", step(null, quality, "enum", 3))
        assertNull(step(null, quality, "enum", -1))
        assertEquals("Medium", step("Epic", quality, "enum", 3, target = "Medium"))
    }

    @Test
    fun `the distance is read from file ops and registry values`() {
        val op = FileOp.fromJson(JSONObject("""{"op":"set","section":"R","key":"Grass","value":"0.5","lower_is":"less","down":3,"ladder":["0.1","0.5","1"]}"""))!!
        assertEquals(3, op.down)
        assertNull(FileOp.fromJson(JSONObject("""{"op":"set","section":"R","key":"Grass","value":"0.5"}"""))!!.down)
        val lever = Lever.parse(
            JSONObject("""{"lever_type":"registry","payload":{"hive":"HKCU","key":"Software\\A","values":[{"name":"Q","type":"dword","data":"1","down":-2,"ladder":["0","1","2"]}]}}"""),
        ) as Lever.Registry
        assertEquals(-2, lever.values.single().down)
    }
}
