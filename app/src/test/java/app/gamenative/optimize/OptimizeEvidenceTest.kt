package app.gamenative.optimize

import app.gamenative.optimize.OptimizeEvidence.Line
import app.gamenative.optimize.OptimizeEvidence.Offer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimizeEvidenceTest {

    private val throttled = Metrics(
        gameplaySec = 1800, steadyFps = 51.0, throttleOnsetSec = 730, fpsBy5Min = listOf(40, 58, 55, 50, 46), gpuBusyP95 = 98,
    )
    private val steadyGpu = Metrics(gameplaySec = 1800, steadyFps = 44.4, fpsBy5Min = listOf(41, 44, 45, 44), gpuBusyP95 = 97)

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
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", steadyGpu))
        val flat = steadyGpu.copy(throttleOnsetSec = 600)
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", flat))
        assertEquals(Line.Gpu(44, 97), OptimizeEvidence.line("gpu", flat))
        // A heat diagnosis already says so; its title keeps the minutes.
        assertEquals(10, OptimizeEvidence.droppedAfterMin("thermal_gpu", flat))
        assertNull(OptimizeEvidence.droppedAfterMin("cpu_single", throttled))
        assertNull(OptimizeEvidence.droppedAfterMin("gpu", null))
    }

    @Test
    fun `each diagnosis shows the number that backs it`() {
        assertEquals(Line.Gpu(44, 97), OptimizeEvidence.line("gpu", steadyGpu))
        assertEquals(Line.Fps(44), OptimizeEvidence.line("gpu", steadyGpu.copy(gpuBusyP95 = 70)))
        assertEquals(Line.Fps(44), OptimizeEvidence.line("gpu", steadyGpu.copy(gpuBusyP95 = null)))
        val cpu = Metrics(gameplaySec = 900, steadyFps = 37.6, hotThreadP95 = 99)
        assertEquals(Line.Core(38), OptimizeEvidence.line("cpu_single", cpu))
        assertEquals(Line.Fps(38), OptimizeEvidence.line("cpu_single", cpu.copy(hotThreadP95 = 60)))
        assertEquals(Line.Fps(38), OptimizeEvidence.line("cpu_multi", cpu))
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
        assertNull(OptimizeEvidence.line(OptimizeCards.SYNC_SPIN, steadyGpu))
        // One window is no fall, and a rise is none either.
        assertNull(OptimizeEvidence.drop(steadyGpu.copy(fpsBy5Min = listOf(44))))
        assertNull(OptimizeEvidence.drop(steadyGpu.copy(fpsBy5Min = listOf(30, 40, 52))))
    }

    @Test
    fun `the offer follows the diagnosis`() {
        assertEquals(Offer.Heat, OptimizeEvidence.offer("thermal_gpu", steadyGpu, null, null))
        assertEquals(Offer.Heat, OptimizeEvidence.offer("gpu", throttled, null, null))
        assertEquals(Offer.Fps, OptimizeEvidence.offer("gpu", steadyGpu, null, null))
        assertEquals(Offer.Fps, OptimizeEvidence.offer("cpu_single", null, null, null))
        assertEquals(Offer.Stutter, OptimizeEvidence.offer("memory", null, null, null))
        assertEquals(Offer.Stutter, OptimizeEvidence.offer("present_locked", null, null, null))
    }

    @Test
    fun `an expected gain is named with its number`() {
        assertEquals(Offer.FpsGain(15), OptimizeEvidence.offer("thermal_gpu", throttled, 15, null))
        assertEquals(Offer.FpsGain(15), OptimizeEvidence.offer("gpu", steadyGpu, 15, 30))
        assertEquals(Offer.StutterDrop(30), OptimizeEvidence.offer("memory", null, 15, 30))
        assertEquals(Offer.StutterDrop(30), OptimizeEvidence.offer("cpu_single", null, null, 30))
        assertEquals(Offer.Fps, OptimizeEvidence.offer("gpu", steadyGpu, 0, null))
    }

    @Test
    fun `the expected gain is read from the answer and bounded`() {
        val answer = JSONObject("""{"status":"rung","expected":{"fps_gain_pct":15,"stutter_drop_pct":40}}""")
        assertEquals(OptimizeEvidence.Expected(15, 40), NextRungResponse.parse(answer).expected)
        assertNull(NextRungResponse.parse(JSONObject("""{"status":"rung"}""")).expected)
        assertNull(OptimizeEvidence.Expected.parse(JSONObject("""{"fps_gain_pct":0,"stutter_drop_pct":140}""")))
        assertEquals(OptimizeEvidence.Expected(null, 20), OptimizeEvidence.Expected.parse(JSONObject("""{"fps_gain_pct":900,"stutter_drop_pct":20}""")))
    }
}
