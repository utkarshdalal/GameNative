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
import app.gamenative.utils.ProcStatParser
import java.io.File
import java.util.Locale
import kotlin.math.ceil

object SessionProcessTelemetry {
    private const val SCAN_EVERY_TICKS = 20
    private const val MB = 1024L * 1024L

    private class ThreadHot(val name: String, val pct: Int)

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
    private var threadBaseline = HashMap<Int, Long>()
    private var threadBaselineMs = 0L

    private val wineserverPct = ArrayList<Int>()
    private val gameRssMb = ArrayList<Int>()
    private val appPssMb = ArrayList<Int>()
    private val hotThreads = ArrayList<ThreadHot>()
    private var availMinMb = -1L
    private var lastLowMemory = false
    private var lowMemoryEvents = 0

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
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
            wineserverPct.clear()
            gameRssMb.clear()
            appPssMb.clear()
            hotThreads.clear()
            availMinMb = -1L
            lastLowMemory = false
            lowMemoryEvents = 0
            appContext = context.applicationContext
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
        }
    }

    fun properties(): Map<String, Any> = synchronized(lock) {
        runCatching { sampleThreads() }
        buildMap {
            hotThreads.groupBy { it.name }
                .maxWithOrNull(compareBy({ it.value.size }, { e -> e.value.maxOf { it.pct } }))
                ?.let { put("hot_thread_name", it.key) }
            p95(hotThreads.map { it.pct })?.let { put("hot_thread_pct_p95", it) }
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
    }

    private fun scan() {
        val dirs = File("/proc").listFiles { file -> file.name.isNotEmpty() && file.name[0].isDigit() } ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = if (prevProcMs > 0L) (nowMs - prevProcMs) / 1000.0 else 0.0
        val ticksNow = HashMap<Int, Long>()
        var wineserver: Int? = null
        var gameCandidate = -1
        var gameCandidatePct = 0
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
        if (gameCandidate < 0) return
        if (gameCandidate != gamePid) {
            gamePid = gameCandidate
            threadBaseline = readThreadTicks(gamePid)?.mapValuesTo(HashMap()) { it.value.second } ?: HashMap()
            threadBaselineMs = nowMs
        }
        readRssMb(gamePid)?.let { gameRssMb += it }
    }

    private fun sampleThreads() {
        val pid = gamePid
        if (pid < 0 || threadBaselineMs == 0L) return
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = (nowMs - threadBaselineMs) / 1000.0
        val current = readThreadTicks(pid)
        if (current == null) {
            gamePid = -1
            threadBaseline = HashMap()
            threadBaselineMs = 0L
            return
        }
        if (dtSec > 1.0) {
            current.entries
                .mapNotNull { (tid, value) ->
                    val prev = threadBaseline[tid] ?: return@mapNotNull null
                    value.first to ProcStatParser.cpuPercent(value.second - prev, dtSec, clkTck)
                }
                .maxByOrNull { it.second }
                ?.let { (name, pct) -> hotThreads += ThreadHot(name, pct) }
        }
        threadBaseline = current.mapValuesTo(HashMap()) { it.value.second }
        threadBaselineMs = nowMs
    }

    private fun readThreadTicks(pid: Int): Map<Int, Pair<String, Long>>? {
        val taskDirs = File("/proc/$pid/task").listFiles() ?: return null
        val result = HashMap<Int, Pair<String, Long>>(taskDirs.size)
        for (dir in taskDirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            val fields = try {
                ProcStatParser.parse(File(dir, "stat").readText())
            } catch (_: Exception) {
                null
            } ?: continue
            result[tid] = fields.comm to (fields.utime + fields.stime)
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
