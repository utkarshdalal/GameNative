package app.gamenative.powercontrol

import app.gamenative.powercontrol.FrameThreadPlacement.FrameChoice
import app.gamenative.powercontrol.FrameThreadPlacement.ProcessKind
import app.gamenative.powercontrol.FrameThreadPlacement.ThreadSample
import app.gamenative.powercontrol.PowerManager.CoreCluster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrameThreadPlacementTest {

    // Retroid Pocket 6: c0-2 little, c3-6 big, c7 prime.
    private val rp6 = listOf(
        CoreCluster("efficiency", listOf(0, 1, 2), 325),
        CoreCluster("performance", listOf(3, 4, 5, 6), 825),
        CoreCluster("prime", listOf(7), 1024),
    )

    // AYN Odin 3: 6 cores at 792, 2 at 1024.
    private val odin3 = listOf(
        CoreCluster("efficiency", emptyList(), 0),
        CoreCluster("performance", listOf(0, 1, 2, 3, 4, 5), 792),
        CoreCluster("prime", listOf(6, 7), 1024),
    )

    private val unknownCapacity = rp6.map { it.copy(capacity = 0L) }

    private val calls = mutableListOf<Pair<Int, Int>>()
    private val recordAffinity: (Int, Int) -> Int = { tid, mask -> calls.add(tid to mask); 0 }
    private val noAllowedRead: (Int, Int) -> List<Int>? = { _, _ -> null }

    private fun game(tid: Int, cpu: Int, wakeups: Int?, comm: String = "t$tid") =
        ThreadSample(pid = 1000, tid = tid, comm = comm, kind = ProcessKind.GAME, cpu = cpu, voluntaryPerSec = wakeups)

    private fun wineserver(tid: Int, cpu: Int) =
        ThreadSample(pid = 2000, tid = tid, comm = "wineserver", kind = ProcessKind.WINESERVER, cpu = cpu, voluntaryPerSec = null)

    private fun steam(tid: Int, cpu: Int, comm: String) =
        ThreadSample(pid = 3000, tid = tid, comm = comm, kind = ProcessKind.STEAM, cpu = cpu, voluntaryPerSec = null)

    @Before
    @After
    fun clear() {
        FrameThreadPlacement.reset()
        calls.clear()
    }

    // ---- roles

    @Test
    fun `always runnable thread is a spinner`() {
        assertTrue(FrameThreadPlacement.isSpinner(game(1, cpu = 98, wakeups = 3)))
        assertFalse(FrameThreadPlacement.isSpinner(game(1, cpu = 98, wakeups = 400)))
        assertFalse(FrameThreadPlacement.isSpinner(game(1, cpu = 70, wakeups = 3)))
        assertFalse(FrameThreadPlacement.isSpinner(game(1, cpu = 98, wakeups = null)))
    }

    @Test
    fun `frame thread is the busiest thread that wakes per frame`() {
        val threads = listOf(
            game(1, cpu = 99, wakeups = 2),
            game(2, cpu = 72, wakeups = 160, comm = "Main"),
            game(3, cpu = 40, wakeups = 900),
            game(4, cpu = 80, wakeups = 9000),
        )
        val roles = FrameThreadPlacement.classify(threads, null)
        assertEquals(2, roles.frame?.tid)
        assertEquals("Main", roles.frame?.comm)
        assertEquals(listOf(1), roles.spinners.map { it.tid })
    }

    @Test
    fun `no frame thread without a per-frame wakeup rate`() {
        val roles = FrameThreadPlacement.classify(listOf(game(1, cpu = 60, wakeups = null), game(2, cpu = 10, wakeups = 100)), null)
        assertNull(roles.frame)
    }

    @Test
    fun `frame thread is kept until another beats it by a fifth for two windows`() {
        var current: FrameChoice? = FrameChoice(2, "Main")
        val challenged = listOf(game(2, cpu = 50, wakeups = 120), game(3, cpu = 65, wakeups = 120))
        current = FrameThreadPlacement.chooseFrame(challenged, current)
        assertEquals(2, current?.tid)
        assertEquals(3, current?.challengerTid)
        current = FrameThreadPlacement.chooseFrame(challenged, current)
        assertEquals(3, current?.tid)
    }

    @Test
    fun `a close challenger does not move the frame thread`() {
        var current: FrameChoice? = FrameChoice(2, "Main")
        val threads = listOf(game(2, cpu = 50, wakeups = 120), game(3, cpu = 58, wakeups = 120))
        repeat(3) { current = FrameThreadPlacement.chooseFrame(threads, current) }
        assertEquals(2, current?.tid)
    }

    @Test
    fun `one window of a challenger then a quiet window resets the count`() {
        var current: FrameChoice? = FrameChoice(2, "Main")
        current = FrameThreadPlacement.chooseFrame(listOf(game(2, 50, 120), game(3, 70, 120)), current)
        current = FrameThreadPlacement.chooseFrame(listOf(game(2, 50, 120), game(3, 30, 120)), current)
        current = FrameThreadPlacement.chooseFrame(listOf(game(2, 50, 120), game(3, 70, 120)), current)
        assertEquals(2, current?.tid)
        assertEquals(1, current?.challengerWindows)
    }

    @Test
    fun `frame thread that exits or starts spinning is replaced at once`() {
        val gone = FrameThreadPlacement.chooseFrame(listOf(game(3, cpu = 40, wakeups = 120)), FrameChoice(2, "Main"))
        assertEquals(3, gone?.tid)
        val spinning = FrameThreadPlacement.chooseFrame(
            listOf(game(2, cpu = 99, wakeups = 1), game(3, cpu = 40, wakeups = 120)),
            FrameChoice(2, "Main"),
        )
        assertEquals(3, spinning?.tid)
    }

    @Test
    fun `IPC set is the wineserver main thread, the Steam engine thread and busy wineserver or Steam threads`() {
        val threads = listOf(
            wineserver(2000, cpu = 4),
            wineserver(2001, cpu = 2),
            wineserver(2002, cpu = 15),
            steam(3001, cpu = 1, comm = "IPC:CSteamEngin"),
            steam(3002, cpu = 30, comm = "SteamThread"),
            steam(3003, cpu = 5, comm = "idle"),
            game(1, cpu = 20, wakeups = 100),
        )
        val roles = FrameThreadPlacement.classify(threads, null)
        assertEquals(listOf(2000, 2002, 3001, 3002), roles.ipc.map { it.tid })
    }

    // ---- masks

    @Test
    fun `RP6 frame thread on the prime core, spinners off it, IPC off the little cores`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = emptyList())
        assertEquals(listOf(7), plan.frameCores)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), plan.spinnerCores)
        assertEquals(listOf(3, 4, 5, 6, 7), plan.ipcCores)
    }

    @Test
    fun `RP6 gamescope game pin narrows every set`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = listOf(3, 4, 5, 6, 7))
        assertEquals(listOf(7), plan.frameCores)
        assertEquals(listOf(3, 4, 5, 6), plan.spinnerCores)
        assertEquals(listOf(3, 4, 5, 6, 7), plan.ipcCores)
    }

    @Test
    fun `Odin 3 frame thread on the two prime cores`() {
        val plan = FrameThreadPlacement.placementPlan(odin3, gamePin = listOf(2, 3, 4, 5, 6, 7))
        assertEquals(listOf(6, 7), plan.frameCores)
        assertEquals(listOf(2, 3, 4, 5), plan.spinnerCores)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), plan.ipcCores)
    }

    @Test
    fun `game pin without the prime core leaves the frame thread alone`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = listOf(3, 4, 5, 6))
        assertNull(plan.frameCores)
        assertEquals(listOf(3, 4, 5, 6), plan.spinnerCores)
        assertTrue(plan.notes.any { it.contains("outside the game pin") })
    }

    @Test
    fun `unknown capacities place nothing`() {
        val plan = FrameThreadPlacement.placementPlan(unknownCapacity, gamePin = emptyList())
        assertNull(plan.frameCores)
        assertNull(plan.spinnerCores)
        assertNull(plan.ipcCores)
    }

    @Test
    fun `spinners never go to the little cores only`() {
        val littleAndPrime = listOf(
            CoreCluster("efficiency", listOf(0, 1, 2, 3), 400),
            CoreCluster("performance", emptyList(), 0),
            CoreCluster("prime", listOf(4, 5, 6, 7), 1024),
        )
        val plan = FrameThreadPlacement.placementPlan(littleAndPrime, gamePin = emptyList())
        assertEquals(listOf(4, 5, 6, 7), plan.frameCores)
        assertNull(plan.spinnerCores)
        assertEquals(listOf(4, 5, 6, 7), plan.ipcCores)
    }

    @Test
    fun `one capacity tier leaves the frame thread alone`() {
        val flat = listOf(CoreCluster("performance", listOf(0, 1, 2, 3, 4, 5, 6, 7), 1024))
        val plan = FrameThreadPlacement.placementPlan(flat, gamePin = emptyList())
        assertNull(plan.frameCores)
        assertNull(plan.spinnerCores)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), plan.ipcCores)
    }

    @Test
    fun `mask is one bit per core`() {
        assertEquals(0x80, FrameThreadPlacement.mask(listOf(7)))
        assertEquals(0xF8, FrameThreadPlacement.mask(listOf(3, 4, 5, 6, 7)))
    }

    // ---- windows

    @Test
    fun `window applies each role and hands a thread that lost its role the game pin back`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = listOf(3, 4, 5, 6, 7))
        val pin = listOf(3, 4, 5, 6, 7)
        val first = listOf(game(1, cpu = 99, wakeups = 2), game(2, cpu = 72, wakeups = 160), wineserver(2000, cpu = 5))
        FrameThreadPlacement.applyWindow(first, plan, pin, "", recordAffinity, { _, _ -> listOf(0, 1) })
        assertEquals(setOf(2 to 0x80, 1 to 0x78, 2000 to 0xF8), calls.toSet())

        calls.clear()
        val second = listOf(game(1, cpu = 40, wakeups = 300), game(2, cpu = 72, wakeups = 160), wineserver(2000, cpu = 5))
        FrameThreadPlacement.applyWindow(second, plan, pin, "", recordAffinity, noAllowedRead)
        assertEquals(setOf(1 to 0xF8, 2 to 0x80, 2000 to 0xF8), calls.toSet())

        calls.clear()
        FrameThreadPlacement.release("session end", recordAffinity, pin)
        assertEquals(setOf(2 to 0xF8, 2000 to 0x03), calls.toSet())
    }

    @Test
    fun `a thread created while a game thread is placed gets the game pin`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = listOf(3, 4, 5, 6, 7))
        val pin = listOf(3, 4, 5, 6, 7)
        val window = listOf(game(2, cpu = 72, wakeups = 160))
        FrameThreadPlacement.applyWindow(window, plan, pin, "", recordAffinity, noAllowedRead, listOf(9))
        assertEquals(listOf(2 to 0x80), calls)

        calls.clear()
        FrameThreadPlacement.applyWindow(window, plan, pin, "", recordAffinity, noAllowedRead, listOf(9))
        assertEquals(setOf(2 to 0x80, 9 to 0xF8), calls.toSet())
    }

    @Test
    fun `nothing is placed when the plan leaves every role alone`() {
        val plan = FrameThreadPlacement.placementPlan(unknownCapacity, gamePin = emptyList())
        FrameThreadPlacement.applyWindow(
            listOf(game(1, cpu = 99, wakeups = 2), game(2, cpu = 72, wakeups = 160), wineserver(2000, cpu = 5)),
            plan, emptyList(), "", recordAffinity, noAllowedRead,
        )
        assertTrue(calls.isEmpty())
        assertEquals(false, FrameThreadPlacement.properties()["frame_placement_applied"])
    }

    @Test
    fun `session summary carries the frame thread and the spinners`() {
        val plan = FrameThreadPlacement.placementPlan(rp6, gamePin = emptyList())
        FrameThreadPlacement.applyWindow(
            listOf(game(1, cpu = 99, wakeups = 2), game(2, cpu = 72, wakeups = 160, comm = "Main")),
            plan, emptyList(), "", recordAffinity, noAllowedRead,
        )
        val props = FrameThreadPlacement.properties()
        assertEquals(2, props["frame_thread_tid"])
        assertEquals("Main", props["frame_thread_comm"])
        assertEquals(listOf(1), props["spinner_tids"])
        assertEquals(true, props["frame_placement_applied"])
    }
}
