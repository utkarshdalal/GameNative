package app.gamenative.powercontrol.metrics

import app.gamenative.utils.ProcStatParser
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskWalkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pid = 4100
    private val clusters = listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7))

    /** A /proc/<pid>/task/<tid>/stat line with [utime], [stime] and the last-run [cpu] in fields 14, 15 and 39. */
    private fun statLine(tid: Int, comm: String, utime: Long, stime: Long, cpu: Int): String {
        val fields = MutableList(52) { "0" }
        fields[0] = tid.toString()
        fields[1] = "($comm)"
        fields[2] = "R"
        fields[3] = "1"
        fields[13] = utime.toString()
        fields[14] = stime.toString()
        fields[17] = "-2"
        fields[19] = "40"
        fields[38] = cpu.toString()
        return fields.joinToString(" ") + "\n"
    }

    private class Thread(val tid: Int, val comm: String, var utime: Long, var stime: Long, var cpu: Int)

    private fun write(dir: File, threads: List<Thread>) {
        threads.forEach { t ->
            File(dir, "${t.tid}").apply { mkdirs() }.resolve("stat").writeText(statLine(t.tid, t.comm, t.utime, t.stime, t.cpu))
        }
    }

    @Test
    fun `the busiest game thread of each cluster is kept per pass`() {
        val dir = tmp.newFolder("task")
        val threads = listOf(
            Thread(pid, "Game.exe", 1000, 100, 7),
            Thread(4101, "UnityGfxDevice", 500, 50, 4),
            Thread(4102, "Job.Worker 0", 300, 10, 3),
            Thread(4103, "wineserver", 0, 0, 5),
            Thread(4104, "Audio (mixer)", 10, 0, 1),
            Thread(4105, "Idle", 5, 0, 2),
        )
        write(dir, threads)
        val walk = TaskWalk(clusters, 100)
        val first = walk.pass(dir, pid, 4101, 10_000L)!!
        assertNull(first.clusterBusiestPct)
        assertEquals(550L, first.tracked!!.utime + first.tracked!!.stime)

        // One second later: the main thread 90 ticks on prime, the render thread 45 and a worker 30 on big, a
        // wineserver thread 99 on big that does not count, the audio thread 4 on little; the idle thread moved to
        // the prime cluster without running.
        threads[0].utime += 85
        threads[0].stime += 5
        threads[1].utime += 45
        threads[2].utime += 30
        threads[3].utime += 99
        threads[4].utime += 4
        threads[5].cpu = 7
        write(dir, threads)
        val second = walk.pass(dir, pid, 4101, 11_000L)!!
        assertArrayEquals(intArrayOf(4, 45, 90), second.clusterBusiestPct)
        assertEquals(595L, second.tracked!!.utime + second.tracked!!.stime)
        assertEquals(4, second.tracked!!.processor)
    }

    @Test
    fun `a cluster no game thread ran on reads -1 and a new game starts over`() {
        val dir = tmp.newFolder("task")
        val threads = listOf(Thread(pid, "Game.exe", 0, 0, 7), Thread(4101, "Render", 0, 0, 7))
        write(dir, threads)
        val walk = TaskWalk(clusters, 100)
        walk.pass(dir, pid, pid, 1_000L)
        threads[0].utime += 50
        threads[1].utime += 100
        write(dir, threads)
        // Two seconds since the last pass: 100 ticks is 50 %.
        assertArrayEquals(intArrayOf(-1, -1, 50), walk.pass(dir, pid, pid, 3_000L)!!.clusterBusiestPct)
        assertNull(walk.pass(dir, pid + 1, pid, 4_000L)!!.clusterBusiestPct)
        assertNull(walk.pass(File(dir, "gone"), pid, pid, 5_000L))
    }

    @Test
    fun `the tracked thread's stat matches the parser's`() {
        val dir = tmp.newFolder("task")
        write(dir, listOf(Thread(4107, "Render (x) 2", 1234, 56, 6)))
        val walked = TaskWalk(clusters, 100).pass(dir, pid, 4107, 0L)!!.tracked!!
        val parsed = ProcStatParser.parse(File(dir, "4107/stat").readText())!!
        assertEquals(parsed.comm, walked.comm)
        assertEquals(parsed.utime, walked.utime)
        assertEquals(parsed.stime, walked.stime)
        assertEquals(parsed.processor, walked.processor)
        assertEquals(parsed.state, walked.state)
    }

    @Test
    fun `cost of one pass over 100 threads against the single hot-thread read`() {
        val dir = tmp.newFolder("task")
        val threads = (0 until 100).map { Thread(pid + it, if (it % 10 == 0) "wine-thread" else "Worker $it", 1000L + it, 10L, it % 8) }
        write(dir, threads)
        val walk = TaskWalk(clusters, 100)
        val hotStat = File(dir, "${pid + 5}/stat")
        repeat(500) { walk.pass(dir, pid, pid + 5, (1 + it) * 1000L) }
        repeat(500) { ProcStatParser.parse(hotStat.readText()) }

        val rounds = 2000
        var t0 = System.nanoTime()
        repeat(rounds) { ProcStatParser.parse(hotStat.readText()) }
        val beforeUs = (System.nanoTime() - t0) / 1000.0 / rounds
        t0 = System.nanoTime()
        var last: TaskWalk.Pass? = null
        repeat(rounds) { last = walk.pass(dir, pid, pid + 5, (501 + it) * 1000L) }
        val afterUs = (System.nanoTime() - t0) / 1000.0 / rounds
        assertNotNull(last?.clusterBusiestPct)
        println("TaskWalk cost per sample: before (hot thread stat only) %.1f us, after (100-thread pass) %.1f us".format(beforeUs, afterUs))
    }
}
