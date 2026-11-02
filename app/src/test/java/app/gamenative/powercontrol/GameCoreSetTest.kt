package app.gamenative.powercontrol

import app.gamenative.powercontrol.PowerManager.ChainThreads
import app.gamenative.powercontrol.PowerManager.CoreCluster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Core placement with the cluster layouts PServerDriver reports on real devices, lowest to
 * highest frequency, each with its cpu_capacity.
 */
class GameCoreSetTest {

    // Retroid Pocket 6 (Snapdragon 8 Gen 2): c0-2 little at about a third of the prime core,
    // c3-6 big, c7 prime.
    private val rp6 = listOf(
        CoreCluster("efficiency", listOf(0, 1, 2), 325),
        CoreCluster("performance", listOf(3, 4, 5, 6), 825),
        CoreCluster("prime", listOf(7), 1024),
    )

    // The same RP6 when the kernel reports no cpu_capacity: max frequency in kHz. The little
    // cores run at 63 % of the prime core's frequency, above the 0.6 line.
    private val rp6ByFrequency = listOf(
        CoreCluster("efficiency", listOf(0, 1, 2), 2_016_000),
        CoreCluster("performance", listOf(3, 4, 5, 6), 2_803_200),
        CoreCluster("prime", listOf(7), 3_187_200),
    )

    // AYN Odin 3 (Snapdragon 8 Elite class 'sun'): 6 cores at capacity 792, 2 at 1024. PServer
    // groups a dual-cluster part as performance + prime.
    private val odin3 = listOf(
        CoreCluster("efficiency", emptyList(), 0),
        CoreCluster("performance", listOf(0, 1, 2, 3, 4, 5), 792),
        CoreCluster("prime", listOf(6, 7), 1024),
    )

    // A 4+4 phone: 4 little cores at about 400, 4 big at 1024.
    private val fourFour = listOf(
        CoreCluster("efficiency", emptyList(), 0),
        CoreCluster("performance", listOf(0, 1, 2, 3), 400),
        CoreCluster("prime", listOf(4, 5, 6, 7), 1024),
    )

    // A 6+2 part whose 6 cores are little (about 400 of 1024).
    private val sixLittleTwoBig = listOf(
        CoreCluster("efficiency", emptyList(), 0),
        CoreCluster("performance", listOf(0, 1, 2, 3, 4, 5), 400),
        CoreCluster("prime", listOf(6, 7), 1024),
    )

    private val chain = ChainThreads(gamescopePid = 4242, xwaylandPid = 4243, daemonTids = listOf(100, 101), tids = listOf(4242, 4250, 4243, 100, 101))

    private val calls = mutableListOf<Pair<Int, Int>>()
    private val recordAffinity: (Int, Int) -> Int = { tid, mask -> calls.add(tid to mask); 0 }
    private val noAffinity: (Int, Int) -> Int = { tid, _ -> fail("unexpected sched_setaffinity on $tid"); 0 }
    private val noThreads: () -> ChainThreads = { fail("unexpected chain lookup"); chain }

    @Before
    @After
    fun clearPlacement() {
        PowerManager.compositorRestoreCores = null
        calls.clear()
    }

    // ---- game core set

    @Test
    fun `RP6 gamescope game runs outside the little cluster`() {
        val set = PowerManager.clusterGameCores(rp6, gamescope = true)
        assertEquals(listOf(3, 4, 5, 6, 7), set.cores)
        assertEquals("low-capacity [0, 1, 2] left out", set.rule)
    }

    @Test
    fun `x11 keeps all cores but the two slowest`() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7), PowerManager.clusterGameCores(rp6, gamescope = false).cores)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), PowerManager.clusterGameCores(fourFour, gamescope = false).cores)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), PowerManager.clusterGameCores(odin3, gamescope = false).cores)
        assertEquals("(unchanged rule)", PowerManager.clusterGameCores(rp6, gamescope = false).rule)
    }

    @Test
    fun `Odin 3 gamescope game keeps the unchanged rule`() {
        val set = PowerManager.clusterGameCores(odin3, gamescope = true)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), set.cores)
        assertTrue(set.rule, set.rule.startsWith("(unchanged rule)"))
    }

    @Test
    fun `four-four gamescope game runs on the big cluster`() {
        val set = PowerManager.clusterGameCores(fourFour, gamescope = true)
        assertEquals(listOf(4, 5, 6, 7), set.cores)
    }

    @Test
    fun `six little two big does not pin the chain`() {
        val placement = PowerManager.compositorCoreSet(sixLittleTwoBig)
        assertEquals(emptyList<Int>(), placement.cores)
        assertTrue(placement.skipReason!!, placement.skipReason!!.startsWith("only 2 core(s) outside the low-capacity cluster"))
        PowerManager.applyCompositorAffinity("gamescope session start", sixLittleTwoBig, true, noAffinity, noThreads)
        assertNull(PowerManager.compositorRestoreCores)
    }

    @Test
    fun `fewer than four cores outside the low-capacity cluster keeps the unchanged rule`() {
        val set = PowerManager.clusterGameCores(sixLittleTwoBig, gamescope = true)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), set.cores)
        assertTrue(set.rule, set.rule.startsWith("(unchanged rule) only 2 cores"))
    }

    @Test
    fun `single cluster keeps the unchanged rule`() {
        val single = listOf(
            CoreCluster("efficiency", emptyList(), 0),
            CoreCluster("performance", listOf(0, 1, 2, 3), 1024),
            CoreCluster("prime", emptyList(), 0),
        )
        assertEquals(listOf(2, 3), PowerManager.clusterGameCores(single, gamescope = true).cores)
    }

    @Test
    fun `unknown capacities keep the unchanged rule`() {
        val unknown = rp6.map { it.copy(capacity = 0) }
        assertEquals(listOf(2, 3, 4, 5, 6, 7), PowerManager.clusterGameCores(unknown, gamescope = true).cores)
    }

    // ---- low-capacity criterion

    @Test
    fun `low capacity is below 0_6 of the device max`() {
        assertEquals(listOf(0, 1, 2), PowerManager.lowCapacityCores(rp6))
        assertEquals(emptyList<Int>(), PowerManager.lowCapacityCores(odin3))
        assertEquals(listOf(0, 1, 2, 3), PowerManager.lowCapacityCores(fourFour))
        // RP6 by max frequency: 2016 / 3187 MHz = 0.63, not low.
        assertEquals(emptyList<Int>(), PowerManager.lowCapacityCores(rp6ByFrequency))
    }

    // ---- compositor chain set

    @Test
    fun `RP6 chain goes outside the little cluster`() {
        val placement = PowerManager.compositorCoreSet(rp6)
        assertEquals(listOf(3, 4, 5, 6, 7), placement.cores)
        assertNull(placement.skipReason)
    }

    @Test
    fun `Odin 3 chain is not pinned`() {
        val placement = PowerManager.compositorCoreSet(odin3)
        assertEquals(emptyList<Int>(), placement.cores)
        assertEquals("no low-capacity cluster; gamescope chain left on all cores", placement.skipReason)
    }

    @Test
    fun `four-four chain goes on the big cluster`() {
        assertEquals(listOf(4, 5, 6, 7), PowerManager.compositorCoreSet(fourFour).cores)
    }

    // ---- placement through the gate

    @Test
    fun `a device without a PServer driver makes no affinity call and does not look up the chain`() {
        PowerManager.applyCompositorAffinity("gamescope session start", null, true, noAffinity, noThreads)
        PowerManager.applyCompositorAffinity("activity resume", null, false, noAffinity, noThreads)
        PowerManager.applyCompositorAffinity("game start", null, true, noAffinity, noThreads)
        assertNull(PowerManager.compositorRestoreCores)
        PowerManager.releaseCompositorAffinity("gamescope session end", noAffinity, noThreads)
    }

    @Test
    fun `RP6 places every chain thread on 3-7 and releases to all cores`() {
        PowerManager.applyCompositorAffinity("gamescope session start", rp6, true, recordAffinity) { chain }
        assertEquals(chain.tids.map { it to 0xF8 }, calls)
        assertNotNull(PowerManager.compositorRestoreCores)

        calls.clear()
        PowerManager.releaseCompositorAffinity("gamescope session end", recordAffinity) { chain }
        assertEquals(chain.tids.map { it to 0xFF }, calls)
        assertNull(PowerManager.compositorRestoreCores)

        // A second release has nothing to hand back.
        PowerManager.releaseCompositorAffinity("gamescope session end", noAffinity, noThreads)
    }

    @Test
    fun `Odin 3 makes no affinity call at session start or end`() {
        PowerManager.applyCompositorAffinity("gamescope session start", odin3, true, noAffinity, noThreads)
        PowerManager.releaseCompositorAffinity("gamescope session end", noAffinity, noThreads)
    }

    @Test
    fun `four-four places the chain on 4-7`() {
        PowerManager.applyCompositorAffinity("game start", fourFour, true, recordAffinity) { chain }
        assertEquals(chain.tids.map { it to 0xF0 }, calls)
    }

    @Test
    fun `RP6 with power control off in the profile still places the chain on 3-7`() {
        PowerManager.applyCompositorAffinity("gamescope session start", rp6, false, recordAffinity) { chain }
        assertEquals(chain.tids.map { it to 0xF8 }, calls)
        assertEquals((0..7).toList(), PowerManager.compositorRestoreCores)
        assertEquals("3-7", PowerManager.formatRanges(listOf(3, 4, 5, 6, 7)))
        assertEquals("0-2,4,6-7", PowerManager.formatRanges(listOf(7, 0, 1, 2, 4, 6)))
    }

    // ---- capacity source

    @Test
    fun `max frequency capacities never pin the chain nor change the game rule`() {
        val byFrequency = PowerManager.knownCapacities(rp6ByFrequency, "max_freq")
        assertTrue(byFrequency.all { it.capacity == 0L })
        val placement = PowerManager.compositorCoreSet(byFrequency)
        assertEquals(emptyList<Int>(), placement.cores)
        assertEquals("cluster capacity unknown (max_freq only); placement skipped", placement.skipReason)
        val game = PowerManager.clusterGameCores(byFrequency, gamescope = true)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), game.cores)
        assertEquals("(unchanged rule) cluster capacity unknown (max_freq only); placement skipped", game.rule)
        PowerManager.applyCompositorAffinity("gamescope session start", byFrequency, true, noAffinity, noThreads)
        // Even a frequency spread that would cross the 0.6 line is ignored.
        val wideSpread = PowerManager.knownCapacities(fourFour, "max_freq")
        assertEquals(emptyList<Int>(), PowerManager.compositorCoreSet(wideSpread).cores)
        assertEquals(fourFour, PowerManager.knownCapacities(fourFour, "cpu_capacity"))
    }

    // ---- gamescope-only fences

    @Test
    fun `tuner aims for the panel rate only on the gamescope route`() {
        assertEquals(60, PowerManager.tunerTargetFps(120, gamescope = true, panelHz = 60))
        assertEquals(60, PowerManager.tunerTargetFps(60, gamescope = true, panelHz = 120))
        assertEquals(45, PowerManager.tunerTargetFps(45, gamescope = true, panelHz = 60))
        assertEquals(120, PowerManager.tunerTargetFps(120, gamescope = true, panelHz = 0))
        assertEquals(0, PowerManager.tunerTargetFps(0, gamescope = true, panelHz = 60))
    }

    @Test
    fun `x11 tuner target is the cap whatever the panel rate`() {
        for (cap in listOf(0, 30, 45, 60, 90, 120, 144)) {
            for (panel in listOf(0, 60, 90, 120)) {
                assertEquals(cap, PowerManager.tunerTargetFps(cap, gamescope = false, panelHz = panel))
            }
        }
    }
}
