package app.gamenative.utils

import android.content.Intent
import android.os.Process
import app.gamenative.powercontrol.loop.ThreadControl
import app.gamenative.powercontrol.loop.ThreadInfo
import app.gamenative.powercontrol.loop.ThreadPlacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Debug-only hooks for the OEM tuning harness: thread census + one-shot placement for the
 * running game process. Both take ~2s snapshots off the main thread and log results to logcat.
 *
 * Intent: action app.gamenative.DUMP_THREADS, extras:
 *   "pid" = int (optional, overrides game-pid auto-detection)
 *
 * Intent: action app.gamenative.SET_PLACEMENT, extras:
 *   "pid"   = int (optional, as above)
 *   "reset" = boolean (restore all threads to all-cpus affinity and original nice)
 *   "rules" = semicolon-separated ordered rules, each <selector>:<cpus>:<nice>
 *     selector = comm regex, or hotN = top N threads by CPU delta
 *     cpus     = "4-7", "7", or "all"
 *     nice     = absolute nice to set, or "-" to leave untouched
 *   First matching rule wins per thread; unmatched threads are untouched.
 */
object ThreadIntentManager {

    const val ACTION_DUMP_THREADS = "app.gamenative.DUMP_THREADS"
    const val ACTION_SET_PLACEMENT = "app.gamenative.SET_PLACEMENT"
    private const val EXTRA_PID = "pid"
    private const val EXTRA_RULES = "rules"
    private const val EXTRA_RESET = "reset"

    private const val TAG = "ThreadIntentManager"
    private const val SNAPSHOT_GAP_MS = 2000L
    private const val NICE_MIN = -20
    private const val NICE_MAX = 19

    private val SERVICE_EXES = listOf(
        "services.exe", "winedevice.exe", "explorer.exe", "plugplay.exe", "svchost.exe",
        "conhost.exe", "start.exe", "winhandler.exe", "rpcss.exe", "wineboot.exe", "tabtip.exe",
    )

    private val HOT_SELECTOR = Regex("^hot(\\d+)$")

    private data class Rule(val hotN: Int?, val regex: Regex?, val cpus: List<Int>, val nice: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val originalNice = mutableMapOf<Int, Int>()

    fun isDumpThreadsIntent(intent: Intent): Boolean = intent.action == ACTION_DUMP_THREADS

    fun isSetPlacementIntent(intent: Intent): Boolean = intent.action == ACTION_SET_PLACEMENT

    fun handleDumpThreads(intent: Intent): String {
        val pid = targetPid(intent) ?: return "DUMP_THREADS: no game process found"
        scope.launch {
            try {
                dumpThreads(pid)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "DUMP_THREADS failed for pid $pid")
            }
        }
        return "DUMP_THREADS started pid=$pid window=${SNAPSHOT_GAP_MS}ms"
    }

    fun handleSetPlacement(intent: Intent): String {
        val pid = targetPid(intent) ?: return "SET_PLACEMENT: no game process found"
        if (intent.getBooleanExtra(EXTRA_RESET, false)) {
            scope.launch {
                try {
                    reset(pid)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "SET_PLACEMENT reset failed for pid $pid")
                }
            }
            return "SET_PLACEMENT reset started pid=$pid"
        }
        val raw = intent.getStringExtra(EXTRA_RULES) ?: return "SET_PLACEMENT: missing rules extra"
        val rules = parseRules(raw) ?: return "SET_PLACEMENT: bad rules '$raw'"
        scope.launch {
            try {
                applyRules(pid, rules)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "SET_PLACEMENT failed for pid $pid")
            }
        }
        return "SET_PLACEMENT started pid=$pid rules=${rules.size}"
    }

    private suspend fun dumpThreads(pid: Int) {
        val before = ThreadPlacer.listTasks(pid)
        delay(SNAPSHOT_GAP_MS)
        val after = ThreadPlacer.listTasks(pid)
        val ranked = ThreadControl.rankByCpuDelta(before, after)
        Timber.tag(TAG).i("DUMP_THREADS pid=$pid threads=${ranked.size} window=${SNAPSHOT_GAP_MS}ms")
        for ((t, delta) in ranked) {
            Timber.tag(TAG).i(
                "tid=${t.tid} comm=${t.comm} delta=$delta affinity=${readCpusAllowed(pid, t.tid)} nice=${readNice(t.tid)}",
            )
        }
    }

    private suspend fun applyRules(pid: Int, rules: List<Rule>) {
        val before = ThreadPlacer.listTasks(pid)
        val after = if (rules.any { it.hotN != null }) {
            delay(SNAPSHOT_GAP_MS)
            ThreadPlacer.listTasks(pid)
        } else {
            before
        }
        val ranked = ThreadControl.rankByCpuDelta(before, after)
        val threads = ranked.map { it.first }
        snapshotNice(threads)
        val assigned = mutableSetOf<Int>()
        var applied = 0
        for ((index, rule) in rules.withIndex()) {
            val matched = if (rule.hotN != null) {
                ranked.take(rule.hotN).map { it.first }
            } else {
                threads.filter { rule.regex!!.containsMatchIn(it.comm) }
            }
            for (t in matched) {
                if (!assigned.add(t.tid)) continue
                val affinityOk = ThreadPlacer.setAffinity(t.tid, ThreadPlacer.buildMask(rule.cpus))
                val niceOk = rule.nice?.let { setNice(t.tid, it) }
                Timber.tag(TAG).i(
                    "SET_PLACEMENT rule=$index tid=${t.tid} comm=${t.comm} cpus=${rule.cpus} affinityOk=$affinityOk" +
                        (rule.nice?.let { " nice=$it niceOk=$niceOk" } ?: ""),
                )
                applied++
            }
        }
        Timber.tag(TAG).i("SET_PLACEMENT done pid=$pid threads=${threads.size} applied=$applied")
    }

    private fun reset(pid: Int) {
        val mask = ThreadPlacer.buildMask(onlineCpus())
        val threads = ThreadPlacer.listTasks(pid)
        synchronized(originalNice) {
            for (t in threads) {
                ThreadPlacer.setAffinity(t.tid, mask)
                setNice(t.tid, originalNice[t.tid] ?: 0)
            }
            originalNice.clear()
        }
        Timber.tag(TAG).i("SET_PLACEMENT reset pid=$pid threads=${threads.size}")
    }

    private fun snapshotNice(threads: List<ThreadInfo>) {
        synchronized(originalNice) {
            for (t in threads) {
                if (originalNice.containsKey(t.tid)) continue
                try {
                    originalNice[t.tid] = Process.getThreadPriority(t.tid)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun setNice(tid: Int, nice: Int): Boolean {
        return try {
            Process.setThreadPriority(tid, nice.coerceIn(NICE_MIN, NICE_MAX))
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "setThreadPriority(tid=$tid, nice=$nice) failed")
            false
        }
    }

    private fun parseRules(raw: String): List<Rule>? {
        val rules = mutableListOf<Rule>()
        for (part in raw.split(';')) {
            val s = part.trim()
            if (s.isEmpty()) continue
            val niceSep = s.lastIndexOf(':')
            val cpusSep = if (niceSep > 0) s.lastIndexOf(':', niceSep - 1) else -1
            if (cpusSep <= 0) return null
            val selector = s.substring(0, cpusSep)
            val cpusSpec = s.substring(cpusSep + 1, niceSep)
            val niceSpec = s.substring(niceSep + 1)
            val cpus = if (cpusSpec == "all") onlineCpus() else parseCpuList(cpusSpec) ?: return null
            val nice = if (niceSpec == "-") null else niceSpec.toIntOrNull() ?: return null
            val hotN = HOT_SELECTOR.find(selector)?.groupValues?.get(1)?.toIntOrNull()
            val regex = if (hotN == null) {
                try {
                    Regex(selector)
                } catch (e: Exception) {
                    return null
                }
            } else {
                null
            }
            rules += Rule(hotN, regex, cpus, nice)
        }
        return rules.ifEmpty { null }
    }

    private fun parseCpuList(spec: String): List<Int>? {
        val cpus = sortedSetOf<Int>()
        for (token in spec.split(',')) {
            val bounds = token.trim().split('-')
            when (bounds.size) {
                1 -> cpus += bounds[0].toIntOrNull() ?: return null
                2 -> {
                    val lo = bounds[0].toIntOrNull() ?: return null
                    val hi = bounds[1].toIntOrNull() ?: return null
                    if (lo > hi) return null
                    cpus += lo..hi
                }
                else -> return null
            }
        }
        return cpus.toList().ifEmpty { null }
    }

    private fun onlineCpus(): List<Int> {
        val spec = try {
            File("/sys/devices/system/cpu/online").readText().trim()
        } catch (e: Exception) {
            ""
        }
        return parseCpuList(spec) ?: (0 until Runtime.getRuntime().availableProcessors()).toList()
    }

    private fun targetPid(intent: Intent): Int? {
        val explicit = intent.getIntExtra(EXTRA_PID, -1)
        if (explicit > 0) return explicit
        return findGamePid()
    }

    /**
     * The game is the wine process with the most accumulated CPU whose cmdline names an .exe
     * that is not a wine service. Override with the "pid" extra when this guesses wrong.
     */
    private fun findGamePid(): Int? {
        val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?: return null
        var bestPid: Int? = null
        var bestJiffies = -1L
        var bestCmdline = ""
        for (dir in procDirs) {
            val pid = dir.name.toInt()
            if (pid == Process.myPid()) continue
            val cmdline = try {
                File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
            } catch (e: Exception) {
                continue
            }
            val lower = cmdline.lowercase()
            if (!lower.contains(".exe")) continue
            if (SERVICE_EXES.any { lower.contains(it) }) continue
            val jiffies = ThreadPlacer.listTasks(pid).sumOf { it.cpuJiffies }
            if (jiffies > bestJiffies) {
                bestJiffies = jiffies
                bestPid = pid
                bestCmdline = cmdline
            }
        }
        if (bestPid != null) {
            Timber.tag(TAG).i("Game pid=$bestPid jiffies=$bestJiffies cmdline=$bestCmdline")
        }
        return bestPid
    }

    private fun readCpusAllowed(pid: Int, tid: Int): String {
        return try {
            File("/proc/$pid/task/$tid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Cpus_allowed:") }
                    ?.substringAfter(':')?.trim() ?: "?"
            }
        } catch (e: Exception) {
            "?"
        }
    }

    private fun readNice(tid: Int): String {
        return try {
            Process.getThreadPriority(tid).toString()
        } catch (e: Exception) {
            "?"
        }
    }
}
