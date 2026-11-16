package app.gamenative.powercontrol.metrics

import app.gamenative.utils.HotThreadFilter
import app.gamenative.utils.ProcStatFields
import app.gamenative.utils.ProcStatParser
import java.io.File
import java.io.FileInputStream

/**
 * One pass a second over the game process's threads. Each thread's utime + stime since the last pass goes to the
 * cluster of the core it last ran on, and the busiest game thread of each cluster is kept, in the order of
 * [clusterCores]. The pass also returns the stat of the thread the sampler follows, so that thread needs no read of
 * its own.
 */
internal class TaskWalk(clusterCores: List<List<Int>>, private val clkTck: Long) {
    /** [clusterBusiestPct]: per cluster, the busiest thread's busy percent, -1 where no game thread ran; null on a first pass. */
    class Pass(val tracked: ProcStatFields?, val clusterBusiestPct: IntArray?)

    private val clusterCount = clusterCores.size
    private val clusterOfCore = IntArray((clusterCores.flatten().maxOrNull() ?: -1) + 1) { -1 }.also { map ->
        clusterCores.forEachIndexed { k, cores -> cores.forEach { if (it >= 0) map[it] = k } }
    }
    private val buf = ByteArray(STAT_BYTES)
    private var prevTicks = HashMap<Int, Long>()
    private var nextTicks = HashMap<Int, Long>()
    private var pid = -1
    private var prevMs = 0L
    private val best = LongArray(clusterCount)
    private var statTicks = 0L
    private var statCpu = -1

    fun reset() {
        pid = -1
        prevMs = 0L
        prevTicks.clear()
    }

    fun pass(taskDir: File, gamePid: Int, trackedTid: Int, nowMs: Long): Pass? {
        val names = taskDir.list()
        if (names == null) {
            reset()
            return null
        }
        if (gamePid != pid) {
            reset()
            pid = gamePid
        }
        val dtSec = if (prevMs > 0L) (nowMs - prevMs) / 1000.0 else 0.0
        best.fill(-1L)
        nextTicks.clear()
        var tracked: ProcStatFields? = null
        val base = taskDir.path
        for (name in names) {
            val tid = name.toIntOrNull() ?: continue
            val n = read("$base/$name/stat")
            if (n <= 0) continue
            if (tid == trackedTid) tracked = ProcStatParser.parse(String(buf, 0, n))
            val open = indexOf('('.code.toByte(), n, fromEnd = false)
            val close = indexOf(')'.code.toByte(), n, fromEnd = true)
            if (open < 0 || close < open) continue
            if (!fieldsAfter(close, n)) continue
            val total = statTicks
            nextTicks[tid] = total
            val prev = prevTicks[tid] ?: continue
            val k = clusterOfCore.getOrElse(statCpu) { -1 }
            if (k < 0) continue
            val delta = (total - prev).coerceAtLeast(0L)
            if (delta <= best[k]) continue
            val comm = String(buf, open + 1, close - open - 1)
            if (HotThreadFilter.excluded(HotThreadFilter.Candidate(gamePid, tid, comm, delta), gamePid)) continue
            best[k] = delta
        }
        val swap = prevTicks
        prevTicks = nextTicks
        nextTicks = swap
        prevMs = nowMs
        val pct = if (dtSec > 0.0) IntArray(clusterCount) { k -> if (best[k] < 0L) -1 else ProcStatParser.cpuPercent(best[k], dtSec, clkTck) } else null
        return Pass(tracked, pct)
    }

    private fun read(path: String): Int = try {
        FileInputStream(path).use { input ->
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r <= 0) break
                total += r
            }
            total
        }
    } catch (_: Exception) {
        -1
    }

    private fun indexOf(b: Byte, n: Int, fromEnd: Boolean): Int {
        if (fromEnd) {
            for (i in n - 1 downTo 0) if (buf[i] == b) return i
        } else {
            for (i in 0 until n) if (buf[i] == b) return i
        }
        return -1
    }

    /** Reads utime + stime and the last-run processor from the fields after the comm's closing parenthesis at [close]. */
    private fun fieldsAfter(close: Int, n: Int): Boolean {
        var field = 2
        var value = 0L
        var digits = false
        var utime = -1L
        var stime = -1L
        var i = close + 1
        while (i <= n) {
            val c = if (i < n) buf[i].toInt() else ' '.code
            if (c == ' '.code || c == '\n'.code) {
                if (digits) {
                    when (field) {
                        UTIME -> utime = value
                        STIME -> stime = value
                        PROCESSOR -> {
                            if (utime < 0L || stime < 0L) return false
                            statTicks = utime + stime
                            statCpu = value.toInt()
                            return true
                        }
                    }
                }
                field++
                value = 0L
                digits = false
            } else if (c in '0'.code..'9'.code) {
                value = value * 10 + (c - '0'.code)
                digits = true
            }
            i++
        }
        return false
    }

    private companion object {
        const val STAT_BYTES = 1024
        const val UTIME = 14
        const val STIME = 15
        const val PROCESSOR = 39
    }
}
