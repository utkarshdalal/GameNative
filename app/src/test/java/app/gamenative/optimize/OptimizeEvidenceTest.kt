package app.gamenative.optimize

import app.gamenative.optimize.OptimizeEvidence.Line
import app.gamenative.optimize.OptimizeEvidence.Offer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimizeEvidenceTest {

    private val throttled = Metrics(
        gameplaySec = 1800, steadyFps = 51.0, throttleOnsetSec = 730, fpsBy5Min = listOf(40, 58, 55, 50, 46), refreshHz = 60.0,
    )
    private val steady = Metrics(gameplaySec = 1800, steadyFps = 44.4, fpsBy5Min = listOf(41, 44, 45, 44), gpuBusyP95 = 97, hotThreadP95 = 99)

    @Test
    fun `a throttled session names the minutes and the fall in frame rate`() {
        assertEquals(12, OptimizeEvidence.droppedAfterMin("thermal_gpu", throttled))
        assertEquals(12, OptimizeEvidence.droppedAfterMin("gpu", throttled))
        // The first window holds loading and menus, so the fall is read from the second.
        assertEquals(Line.Drop(58, 46), OptimizeEvidence.line("thermal_gpu", throttled))
        assertEquals(Line.Drop(58, 46), OptimizeEvidence.line("gpu", throttled))
        assertEquals(1, OptimizeEvidence.droppedAfterMin("thermal_gpu", throttled.copy(throttleOnsetSec = 20)))
    }

    @Test
    fun `a GPU diagnosis only says frames dropped when the frame rate fell`() {
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", steady))
        val flat = steady.copy(throttleOnsetSec = 600)
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", flat))
        // A heat diagnosis already says so; its title keeps the minutes.
        assertEquals(10, OptimizeEvidence.droppedAfterMin("thermal_gpu", flat))
        assertNull(OptimizeEvidence.droppedAfterMin("cpu_single", throttled))
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", null))
    }

    @Test
    fun `the line says what the player saw, never what caused it`() {
        assertEquals(Line.Fps(44), OptimizeEvidence.line("gpu", steady))
        assertEquals(Line.Fps(44), OptimizeEvidence.line("cpu_single", steady))
        assertEquals(Line.Fps(44), OptimizeEvidence.line("cpu_multi", steady))
        assertEquals(Line.Fps(44, dipsTo = 31), OptimizeEvidence.line("gpu", steady.copy(fpsP10 = 31.2)))
        // Slow moments close to the average are not worth a mention.
        assertEquals(Line.Fps(44), OptimizeEvidence.line("gpu", steady.copy(fpsP10 = 40.0)))
        val stutters = Metrics(gameplaySec = 900, steadyFps = 30.0, stutterShare = 0.043)
        assertEquals(Line.Stutter(4), OptimizeEvidence.line("memory", stutters))
        assertEquals(Line.Stutter(4), OptimizeEvidence.line("present_locked", stutters))
        assertEquals(Line.Fps(30), OptimizeEvidence.line("present_locked", stutters.copy(stutterShare = 0.001)))
    }

    @Test
    fun `a session without the numbers shows no line`() {
        assertNull(OptimizeEvidence.line("gpu", null))
        assertNull(OptimizeEvidence.line("gpu", Metrics(gameplaySec = 900)))
        assertNull(OptimizeEvidence.line("memory", Metrics(gameplaySec = 900, steadyFps = 50.0)))
        assertNull(OptimizeEvidence.line(OptimizeCards.SYNC_SPIN, steady))
        // One window is no fall, and a rise is none either.
        assertNull(OptimizeEvidence.drop(steady.copy(fpsBy5Min = listOf(44))))
        assertNull(OptimizeEvidence.drop(steady.copy(fpsBy5Min = listOf(30, 40, 52))))
    }

    @Test
    fun `the offer is an outcome that follows the diagnosis`() {
        assertEquals(Offer.Heat, OptimizeEvidence.offer("thermal_gpu", steady, null))
        assertEquals(Offer.Smoother, OptimizeEvidence.offer("gpu", steady, null))
        assertEquals(Offer.Smoother, OptimizeEvidence.offer("cpu_single", null, null))
        assertEquals(Offer.Stutter, OptimizeEvidence.offer("memory", null, null))
        assertEquals(Offer.Stutter, OptimizeEvidence.offer("present_locked", throttled, 60))
    }

    @Test
    fun `a frame rate is offered only when the session held it or the server expects it`() {
        // 58 fps before the fall on a 60 Hz screen: the game can hold 60.
        assertEquals(60, OptimizeEvidence.reachedFps(throttled))
        assertEquals(Offer.Steady(60, lessHeat = true), OptimizeEvidence.offer("thermal_gpu", throttled, null))
        assertEquals(Offer.Steady(60, lessHeat = true), OptimizeEvidence.offer("gpu", throttled, null))
        // A game that fell from 48 never held the screen's rate.
        val slow = throttled.copy(fpsBy5Min = listOf(40, 48, 44, 40))
        assertNull(OptimizeEvidence.reachedFps(slow))
        assertEquals(Offer.Heat, OptimizeEvidence.offer("thermal_gpu", slow, null))
        assertNull(OptimizeEvidence.reachedFps(steady))
        assertNull(OptimizeEvidence.reachedFps(null))
        // The server's number wins.
        assertEquals(Offer.Steady(45, lessHeat = true), OptimizeEvidence.offer("thermal_gpu", throttled, 45))
        assertEquals(Offer.Steady(60, lessHeat = false), OptimizeEvidence.offer("gpu", steady, 60))
    }

    @Test
    fun `the expected frame rate is read from the answer and bounded`() {
        val answer = JSONObject("""{"status":"rung","expected":{"steady_fps":60}}""")
        assertEquals(OptimizeEvidence.Expected(60), NextRungResponse.parse(answer).expected)
        assertNull(NextRungResponse.parse(JSONObject("""{"status":"rung"}""")).expected)
        assertNull(OptimizeEvidence.Expected.parse(JSONObject("""{"steady_fps":5}""")))
        assertNull(OptimizeEvidence.Expected.parse(JSONObject("""{"steady_fps":900}""")))
        val card = OptimizeCard(OptimizeCard.FIRST, 1L, "Game", cls = "gpu", expectedFps = 60)
        assertEquals(60, OptimizeCard.fromJson(card.toJson())!!.expectedFps)
    }
}
