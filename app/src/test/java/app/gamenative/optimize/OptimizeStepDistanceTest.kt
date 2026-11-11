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
    fun `a step goes the same distance from wherever the game starts`() {
        // From the top, three levels down is the old absolute step.
        assertEquals("0.5", step("1", grass, OptimizeRelative.LESS, 3))
        // From the middle the same step still moves the key, instead of writing nothing.
        assertEquals("0.2", step("0.35", grass, OptimizeRelative.LESS, 3))
        assertEquals("0.1", step("0.35", grass, OptimizeRelative.LESS, 8))
        assertEquals("0.1", step("0.15", grass, OptimizeRelative.LESS, 3))
    }

    @Test
    fun `a level the key already holds is counted from its lowest step, so every step changes the value`() {
        assertEquals("Medium", step("High", quality, "enum", 3))
        assertEquals("Low", step("Ultra", quality, "enum", 8))
        assertEquals("Low", step("Medium", quality, "enum", 3))
        assertEquals("Medium", step(" high ", quality, "enum", 1))
    }

    @Test
    fun `lower quality may be the bigger number`() {
        assertEquals("2", step("0", cascade, OptimizeRelative.MORE, 3))
        assertEquals("5", step("3", cascade, OptimizeRelative.MORE, 5))
    }

    @Test
    fun `a value between two steps counts from the step under it`() {
        assertEquals("0.25", step("0.42", grass, OptimizeRelative.LESS, 3))
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
    fun `a level without a value gives way to the nearest one that still lowers the key`() {
        val gaps = listOf(null, "1", null, null, "2", "3", "3", "3", "4", "4", "4", "4")
        assertEquals("1", step("4", gaps, OptimizeRelative.LESS, 5))
        assertEquals("1", step("2", gaps, OptimizeRelative.LESS, 4))
        assertEquals("2", step("3", listOf(null, null, null, null, "2", "3", "3", "3", "4", "4", "4", "4"), OptimizeRelative.LESS, 5))
    }

    @Test
    fun `a negative distance goes back up, never past the top`() {
        assertEquals("High", step("Medium", quality, "enum", -1))
        assertEquals("0.5", step("0.3", grass, OptimizeRelative.LESS, -4))
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
