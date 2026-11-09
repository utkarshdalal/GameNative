package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotThreadFilterTest {
    private val game = 4000

    private fun t(tid: Int, name: String, total: Long, pid: Int = game) = HotThreadFilter.Candidate(pid, tid, name, total)

    @Test
    fun androidUiThreadsCountOnlyInsideTheGameProcess() {
        assertFalse(HotThreadFilter.excluded(t(4010, "RenderThread", 90), game))
        assertTrue(HotThreadFilter.excluded(t(1210, "RenderThread", 90, pid = 1200), game))
        assertTrue(HotThreadFilter.excluded(t(1211, "hwuiTask1", 90, pid = 1200), game))
        assertTrue(HotThreadFilter.excluded(t(1212, "GLThread 12", 90, pid = 1200), game))
    }

    @Test
    fun wineAndDxvkHelpersNeverCount() {
        assertTrue(HotThreadFilter.excluded(t(4011, "wineserver", 90), game))
        assertTrue(HotThreadFilter.excluded(t(4012, "wine_sechost_se", 90), game))
        assertTrue(HotThreadFilter.excluded(t(4013, "dxvk-submit", 90), game))
        assertTrue(HotThreadFilter.excluded(t(4014, "AudioTrack", 90), game))
        assertFalse(HotThreadFilter.excluded(t(game, "wine64-preload", 90), game))
    }

    @Test
    fun kernelHeavySpinnerStaysTheHotThread() {
        // 100 ticks, 74 of them in the kernel (a yield spinner), still outranks a 70-tick user-time thread.
        val pick = HotThreadFilter.pick(listOf(t(4030, "DispatchThread", 100), t(4020, "UnityGfxDevice", 70)), game)
        assertEquals(4030, pick!!.tid)
    }

    @Test
    fun picksTheGameThreadWithTheMostCpuTime() {
        val pick = HotThreadFilter.pick(
            listOf(t(4013, "dxvk-cs", 100), t(4020, "UnityGfxDevice", 70), t(4021, "Job.Worker 0", 40), t(1210, "RenderThread", 95, pid = 1200)),
            game,
        )
        assertEquals(4020, pick!!.tid)
    }

    @Test
    fun prefersTheMainThreadWithinTenPercentOfTheTopThread() {
        assertEquals(game, HotThreadFilter.pick(listOf(t(game, "TheForest.exe", 72), t(4020, "UnityGfxDevice", 80)), game)!!.tid)
        assertEquals(4020, HotThreadFilter.pick(listOf(t(game, "TheForest.exe", 71), t(4020, "UnityGfxDevice", 80)), game)!!.tid)
    }

    @Test
    fun nothingEligibleIsNoPick() {
        assertNull(HotThreadFilter.pick(listOf(t(4013, "dxvk-cs", 100), t(1210, "RenderThread", 95, pid = 1200)), game))
    }
}
