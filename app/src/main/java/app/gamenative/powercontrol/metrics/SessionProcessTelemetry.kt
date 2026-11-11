package app.gamenative.powercontrol.metrics

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import app.gamenative.utils.HotThreadFilter
import app.gamenative.utils.ProcStatParser
import java.io.File
import java.util.Locale
import kotlin.math.ceil

object SessionProcessTelemetry {
    private const val SCAN_EVERY_TICKS = 20
    private const val MB = 1024L * 1024L

    private const val FIRST_PICK_DELAY_MS = 60_000L

    private class ThreadHot(val name: String, val pct: Int)

    /** One thread's name and its utime + stime ticks. */
    private class ThreadTicks(val name: String, val total: Long)

    /** The game thread the per-second sampler follows. */
    class HotPick(val pid: Int, val tid: Int, val name: String)

    private val lock = Any()
    private val selfPid = Process.myPid()
    private val uid = Process.myUid()
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L).coerceAtLeast(1L)

    private var appContext: Context? = null
    private var activityManager: ActivityManager? = null
    private val memoryInfo = ActivityManager.MemoryInfo()

    private var ticks = 0L
    private var lastBucket = -1
    private var prevProcTicks = HashMap<Int, Long>()
    private var prevProcMs = 0L
    private val procNames = HashMap<Int, Pair<String, String>>()

    private var gamePid = -1
    private var packageName: String? = null
    private var threadBaseline = HashMap<Int, ThreadTicks>()

    /**
     * The busiest process by the older rule (the app's own processes not left out), whose raw busiest thread is
     * game_exited's hot_thread_name and whose RSS is game_rss_p95_mb, so those keep their meaning.
     */
    private var legacyPid = -1
    private var legacyBaseline = HashMap<Int, ThreadTicks>()
    private var legacyBaselineMs = 0L
    private val legacyHotThreads = ArrayList<ThreadHot>()
    private var threadBaselineMs = 0L

    private val wineserverPct = ArrayList<Int>()
    private val gameRssMb = ArrayList<Int>()
    private val appPssMb = ArrayList<Int>()
    private val hotThreads = ArrayList<ThreadHot>()
    private var availMinMb = -1L
    private var lastLowMemory = false
    private var lowMemoryEvents = 0
    private var gamePidSeenMs = 0L
    private var firstPickDone = false

    @Volatile
    private var pickRequested = false

    @Volatile
    var hotPick: HotPick? = null
        private set

    @Volatile
    var latestWineserverPct: Int? = null
        private set

    @Volatile
    var latestGamePct: Int? = null
        private set

    @Volatile
    var latestAvailMb: Long? = null
        private set

    @Volatile
    var latestLowMemory: Boolean = false
        private set

    val lowMemoryEventCount: Int get() = synchronized(lock) { lowMemoryEvents }

    /** Re-picks the hot thread on the next tick, from the thread ticks since the last full pass. */
    fun requestHotPick() {
        pickRequested = true
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Only the levels sent to a running app; the background levels say the process may be reclaimed, not that it is short of memory.
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                synchronized(lock) { lowMemoryEvents++ }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    fun start(context: Context) {
        stop()
        synchronized(lock) {
            ticks = 0L
            lastBucket = -1
            prevProcTicks = HashMap()
            prevProcMs = 0L
            procNames.clear()
            gamePid = -1
            threadBaseline = HashMap()
            threadBaselineMs = 0L
            legacyPid = -1
            legacyBaseline = HashMap()
            legacyBaselineMs = 0L
            legacyHotThreads.clear()
            wineserverPct.clear()
            gameRssMb.clear()
            appPssMb.clear()
            hotThreads.clear()
            availMinMb = -1L
            lastLowMemory = false
            lowMemoryEvents = 0
            gamePidSeenMs = 0L
            firstPickDone = false
            pickRequested = false
            hotPick = null
            latestWineserverPct = null
            latestGamePct = null
            latestAvailMb = null
            latestLowMemory = false
            appContext = context.applicationContext
            packageName = context.packageName
            activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }
        runCatching { context.applicationContext.registerComponentCallbacks(trimCallbacks) }
    }

    fun stop() {
        val context = synchronized(lock) { appContext.also { appContext = null } } ?: return
        runCatching { context.unregisterComponentCallbacks(trimCallbacks) }
    }

    fun tick(bucket: Int) {
        synchronized(lock) {
            if (ticks % SCAN_EVERY_TICKS == 0L) {
                runCatching { scan() }
                runCatching { sampleMemory() }
            }
            ticks++
            if (bucket != lastBucket) {
                if (lastBucket >= 0) runCatching { sampleThreads() }
                lastBucket = bucket
            }
            if (gamePid >= 0 && (pickRequested || (!firstPickDone && SystemClock.elapsedRealtime() - gamePidSeenMs >= FIRST_PICK_DELAY_MS))) {
                runCatching { pickHotThread(readThreadTicks(gamePid), SystemClock.elapsedRealtime()) }
            }
        }
    }

    fun properties(): Map<String, Any> = synchronized(lock) {
        runCatching { sampleThreads() }
        buildMap {
            fun hottest(list: List<ThreadHot>) = list.groupBy { it.name }
                .maxWithOrNull(compareBy({ it.value.size }, { e -> e.value.maxOf { it.pct } }))?.key
            hottest(legacyHotThreads)?.let { put("hot_thread_name", it) }
            p95(legacyHotThreads.map { it.pct })?.let { put("hot_thread_pct_p95", it) }
            hottest(hotThreads)?.let { put("hot_thread_pick_name", it) }
            p95(hotThreads.map { it.pct })?.let { put("hot_thread_pick_pct_p95", it) }
            p95(wineserverPct)?.let { put("wineserver_pct_p95", it) }
            p95(gameRssMb)?.let { put("game_rss_p95_mb", it) }
            p95(appPssMb)?.let { put("app_pss_p95_mb", it) }
            if (availMinMb >= 0L) put("avail_ram_min_mb", availMinMb)
            put("low_memory_events", lowMemoryEvents)
        }
    }

    private fun p95(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    private fun sampleMemory() {
        val pssKb = Debug.getPss()
        if (pssKb > 0L) appPssMb.add((pssKb / 1024L).toInt())
        val am = activityManager ?: return
        am.getMemoryInfo(memoryInfo)
        val availMb = memoryInfo.availMem / MB
        if (availMinMb < 0L || availMb < availMinMb) availMinMb = availMb
        if (memoryInfo.lowMemory && !lastLowMemory) lowMemoryEvents++
        lastLowMemory = memoryInfo.lowMemory
        latestAvailMb = availMb
        latestLowMemory = memoryInfo.lowMemory
    }

    private fun scan() {
        val dirs = File("/proc").listFiles { file -> file.name.isNotEmpty() && file.name[0].isDigit() } ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = if (prevProcMs > 0L) (nowMs - prevProcMs) / 1000.0 else 0.0
        val ticksNow = HashMap<Int, Long>()
        var wineserver: Int? = null
        var gameCandidate = -1
        var gameCandidatePct = 0
        var legacyCandidate = -1
        var legacyCandidatePct = 0
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            try {
                if (Os.stat(dir.path).st_uid != uid) continue
                val fields = ProcStatParser.parse(File(dir, "stat").readText()) ?: continue
                val total = fields.utime + fields.stime
                ticksNow[pid] = total
                val name = procNames[pid]?.takeIf { it.first == fields.comm }?.second
                    ?: processName(dir, fields.comm).also { procNames[pid] = fields.comm to it }
                val prev = prevProcTicks[pid] ?: continue
                if (dtSec <= 0.0) continue
                val pct = ProcStatParser.cpuPercent(total - prev, dtSec, clkTck)
                val lower = name.lowercase(Locale.US)
                if (lower != "wineserver" && lower !in ProcStatParser.excludedProcessNames && pct > legacyCandidatePct) {
                    legacyCandidate = pid
                    legacyCandidatePct = pct
                }
                if (packageName?.let { name.startsWith(it) } == true) continue
                if (lower == "wineserver") {
                    wineserver = (wineserver ?: 0) + pct
                } else if (lower !in ProcStatParser.excludedProcessNames && pct > gameCandidatePct) {
                    gameCandidate = pid
                    gameCandidatePct = pct
                }
            } catch (_: Exception) {
            }
        }
        prevProcTicks = ticksNow
        prevProcMs = nowMs
        procNames.keys.retainAll(ticksNow.keys)
        wineserver?.let { wineserverPct += it }
        latestWineserverPct = wineserver
        if (legacyCandidate >= 0) {
            if (legacyCandidate != legacyPid) {
                legacyPid = legacyCandidate
                legacyBaseline = readThreadTicks(legacyPid)?.let { HashMap(it) } ?: HashMap()
                legacyBaselineMs = nowMs
            }
            readRssMb(legacyPid)?.let { gameRssMb += it }
        }
        if (gameCandidate < 0) return
        latestGamePct = gameCandidatePct
        if (gameCandidate != gamePid) {
            gamePid = gameCandidate
            threadBaseline = readThreadTicks(gamePid)?.let { HashMap(it) } ?: HashMap()
            threadBaselineMs = nowMs
            gamePidSeenMs = nowMs
            firstPickDone = false
            hotPick = null
        }
    }

    private fun sampleThreads() {
        sampleLegacyThreads()
        val pid = gamePid
        if (pid < 0 || threadBaselineMs == 0L) return
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = (nowMs - threadBaselineMs) / 1000.0
        val current = readThreadTicks(pid)
        if (current == null) {
            gamePid = -1
            threadBaseline = HashMap()
            threadBaselineMs = 0L
            hotPick = null
            return
        }
        pickHotThread(current, nowMs)
        if (dtSec > 1.0) {
            HotThreadFilter.pick(candidates(pid, current), pid)
                ?.let { hotThreads += ThreadHot(it.name, ProcStatParser.cpuPercent(it.totalTicks, dtSec, clkTck)) }
        }
        threadBaseline = HashMap(current)
        threadBaselineMs = nowMs
    }

    /** The raw busiest thread of [legacyPid] since the last pass, as hot_thread_name always was. */
    private fun sampleLegacyThreads() {
        val pid = legacyPid
        if (pid < 0 || legacyBaselineMs == 0L) return
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = (nowMs - legacyBaselineMs) / 1000.0
        val current = readThreadTicks(pid)
        if (current == null) {
            legacyPid = -1
            legacyBaseline = HashMap()
            legacyBaselineMs = 0L
            return
        }
        if (dtSec > 1.0) {
            current.entries
                .mapNotNull { (tid, value) ->
                    val prev = legacyBaseline[tid] ?: return@mapNotNull null
                    value.name to ProcStatParser.cpuPercent(value.total - prev.total, dtSec, clkTck)
                }
                .maxByOrNull { it.second }
                ?.let { (name, pct) -> legacyHotThreads += ThreadHot(name, pct) }
        }
        legacyBaseline = HashMap(current)
        legacyBaselineMs = nowMs
    }

    /**
     * Picks the game thread with the most CPU time since the last full pass ([HotThreadFilter]), leaving that
     * pass's baseline as it is.
     */
    private fun pickHotThread(current: Map<Int, ThreadTicks>?, nowMs: Long) {
        val dtSec = (nowMs - threadBaselineMs) / 1000.0
        if (current == null || threadBaselineMs == 0L || dtSec <= 1.0) return
        pickRequested = false
        firstPickDone = true
        HotThreadFilter.pick(candidates(gamePid, current), gamePid)?.let { hotPick = HotPick(gamePid, it.tid, it.name) }
    }

    /** Tick deltas since the baseline of the threads of [pid] that were in it. */
    private fun candidates(pid: Int, current: Map<Int, ThreadTicks>): List<HotThreadFilter.Candidate> =
        current.mapNotNull { (tid, value) ->
            val prev = threadBaseline[tid] ?: return@mapNotNull null
            HotThreadFilter.Candidate(pid, tid, value.name, value.total - prev.total)
        }

    private fun readThreadTicks(pid: Int): Map<Int, ThreadTicks>? {
        val taskDirs = File("/proc/$pid/task").listFiles() ?: return null
        val result = HashMap<Int, ThreadTicks>(taskDirs.size)
        for (dir in taskDirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            val fields = try {
                ProcStatParser.parse(File(dir, "stat").readText())
            } catch (_: Exception) {
                null
            } ?: continue
            result[tid] = ThreadTicks(fields.comm, fields.utime + fields.stime)
        }
        return result
    }

    private fun processName(dir: File, comm: String): String {
        val cmdline = try {
            String(File(dir, "cmdline").readBytes())
        } catch (_: Exception) {
            ""
        }
        return ProcStatParser.processName(cmdline, comm)
    }

    private fun readRssMb(pid: Int): Int? = try {
        val pages = File("/proc/$pid/statm").readText().trim().split(' ').getOrNull(1)?.toLongOrNull()
        pages?.let { (it * pageSize / MB).toInt() }
    } catch (_: Exception) {
        null
    }
}
