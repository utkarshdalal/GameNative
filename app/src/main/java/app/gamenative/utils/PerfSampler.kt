package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Display
import androidx.annotation.RequiresApi
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.metrics.FrameTimeRing
import app.gamenative.powercontrol.metrics.GpuUsageSampler
import app.gamenative.powercontrol.metrics.SystemMetricsSources
import com.winlator.container.Container
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt
import android.os.PowerManager as AndroidPowerManager

internal class PerfCluster(val cores: IntArray, val maxKhz: Long)

internal class PerfThread(
    val name: String,
    val cpu: Int,
    val state: Char,
    val processor: Int?,
    val voluntaryPerSec: Int?,
    val nonvoluntaryPerSec: Int?,
)

internal class PerfGame(
    val pid: Int,
    val name: String,
    val cpu: Int,
    val threadCount: Int,
    val dStateThreads: Int,
    val top: List<PerfThread>,
)

internal class PerfSample(
    val t: Int,
    val fps: Float?,
    val frameP50Ms: Float?,
    val frameP99Ms: Float?,
    val frameMaxMs: Float?,
    val cpuTotal: Int?,
    val iowait: Int?,
    val cores: IntArray?,
    val clusterCurMhz: IntArray?,
    val clusterMaxMhz: IntArray?,
    val gpuBusy: Int?,
    val gpuMhz: Int?,
    val thermalStatus: Int?,
    val thermalHeadroom: Float?,
    val cpuTempC: Int?,
    val batteryTempC: Int?,
    val skinTempC: Int?,
    val availMb: Int?,
    val lowMemory: Boolean?,
    val pssMb: Int?,
    val gameRssMb: Int?,
    val game: PerfGame?,
    val wineserverCpu: Int?,
    val procs: List<Pair<String, Int>>,
    val capActive: Boolean,
)

internal class PerfPowerControl(
    val active: Boolean,
    val capActive: Boolean,
    val maxCpuKhz: Long?,
    val maxAvailableKhz: Long?,
    val tunerPrimeKhz: Long?,
    val tunerPerformanceKhz: Long?,
    val tunerGpuLevel: Int?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        put("capActive", capActive)
        putOpt("maxCpuKhz", maxCpuKhz)
        putOpt("maxAvailableKhz", maxAvailableKhz)
        putOpt("tunerPrimeKhz", tunerPrimeKhz)
        putOpt("tunerPerformanceKhz", tunerPerformanceKhz)
        putOpt("tunerGpuLevel", tunerGpuLevel)
    }
}

internal class PerfRun(
    val intervalMs: Long,
    val runLengthSec: Int,
    val refreshRateHz: Float,
    val clusters: List<PerfCluster>,
    val samples: List<PerfSample>,
    val histogramMs: TreeMap<Int, Int>,
    val totalFrames: Long,
    val vsyncMultipleFrames: Long,
    val thermalTransitions: List<Pair<Int, Int>>,
    val powerControl: PerfPowerControl?,
    val installPath: String?,
    val installLocation: String?,
    val totalMemMb: Int?,
)

object PerfSampler {

    private const val INTERVAL_MS = 1000L
    private const val MAX_SAMPLES = 3600
    private const val TOP_THREADS = 5
    private const val TOP_PROCS = 3
    private const val HISTOGRAM_CAP_MS = 200
    private const val MB = 1024L * 1024L

    class Result(val perf: JSONObject, val verdict: JSONObject)

    private val lock = Any()
    private var session: Session? = null

    fun start(context: Context, fpsProvider: () -> Float, drives: String?) {
        synchronized(lock) {
            session?.halt()
            session = try {
                Session(context.applicationContext, fpsProvider, drives).also { it.begin() }
            } catch (e: Exception) {
                Timber.w(e, "PerfSampler: failed to start")
                null
            }
        }
    }

    fun halt() {
        synchronized(lock) { session?.halt() }
    }

    fun stop(): Result? {
        val current = synchronized(lock) {
            val s = session
            session = null
            s
        } ?: return null
        return try {
            current.halt()
            val run = current.toRun()
            Result(perfJson(run), PerfVerdicts.compute(run))
        } catch (e: Exception) {
            Timber.w(e, "PerfSampler: failed to build result")
            null
        }
    }

    private fun perfJson(run: PerfRun): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("intervalMs", run.intervalMs)
        put("runLengthSec", run.runLengthSec)
        put("refreshRateHz", round1(run.refreshRateHz))
        put(
            "clusters",
            JSONArray().apply {
                run.clusters.forEach { c ->
                    put(JSONObject().put("cores", JSONArray(c.cores.toList())).put("maxKhz", c.maxKhz))
                }
            },
        )
        if (run.installPath != null) {
            put("install", JSONObject().put("path", run.installPath).putOpt("location", run.installLocation))
        }
        run.powerControl?.let { put("powerControl", it.toJson()) }
        putOpt("totalMemMb", run.totalMemMb)
        put(
            "frameTimeHistogramMs",
            JSONObject().apply { run.histogramMs.forEach { (ms, count) -> put(ms.toString(), count) } },
        )
        put("vsync", JSONObject().put("totalFrames", run.totalFrames).put("multipleFrames", run.vsyncMultipleFrames))
        put(
            "thermalTransitions",
            JSONArray().apply {
                run.thermalTransitions.forEach { (t, status) -> put(JSONObject().put("t", t).put("status", status)) }
            },
        )
        put(
            "samples",
            JSONArray().apply {
                var prev: PerfSample? = null
                run.samples.forEach { s ->
                    put(sampleJson(s, prev))
                    prev = s
                }
            },
        )
    }

    private fun sampleJson(s: PerfSample, prev: PerfSample?): JSONObject = JSONObject().apply {
        put("t", s.t)
        putOpt("fps", s.fps?.let { round1(it) })
        if (s.frameP50Ms != null && s.frameP99Ms != null && s.frameMaxMs != null) {
            put("ft", JSONArray().put(round1(s.frameP50Ms)).put(round1(s.frameP99Ms)).put(round1(s.frameMaxMs)))
        }
        putOpt("cpu", s.cpuTotal)
        putOpt("iow", s.iowait)
        s.cores?.let { put("cores", intArrayJson(it)) }
        s.clusterCurMhz?.let { put("freq", intArrayJson(it)) }
        s.clusterMaxMhz?.let {
            val prevMax = prev?.clusterMaxMhz
            if (prevMax == null || !prevMax.contentEquals(it)) put("fmax", intArrayJson(it))
        }
        putOpt("gpu", s.gpuBusy)
        putOpt("gfreq", s.gpuMhz)
        putOpt("th", s.thermalStatus)
        putOpt("hr", s.thermalHeadroom?.let { round2(it) })
        putOpt("tc", s.cpuTempC)
        putOpt("tb", s.batteryTempC)
        putOpt("ts", s.skinTempC)
        if (s.availMb != null) {
            put(
                "mem",
                JSONArray()
                    .put(s.availMb)
                    .put(if (s.lowMemory == true) 1 else 0)
                    .put(s.pssMb ?: JSONObject.NULL)
                    .put(s.gameRssMb ?: JSONObject.NULL),
            )
        }
        s.game?.let { g ->
            put(
                "g",
                JSONObject().apply {
                    if (prev?.game?.pid != g.pid) {
                        put("pid", g.pid)
                        put("n", g.name)
                    }
                    put("cpu", g.cpu)
                    put("thr", g.threadCount)
                    if (g.dStateThreads > 0) put("d", g.dStateThreads)
                    put(
                        "top",
                        JSONArray().apply {
                            g.top.forEach { t ->
                                put(
                                    JSONArray()
                                        .put(t.name)
                                        .put(t.cpu)
                                        .put(t.state.toString())
                                        .put(t.processor ?: JSONObject.NULL)
                                        .put(t.voluntaryPerSec ?: JSONObject.NULL)
                                        .put(t.nonvoluntaryPerSec ?: JSONObject.NULL),
                                )
                            }
                        },
                    )
                },
            )
        }
        putOpt("ws", s.wineserverCpu)
        if (s.procs.isNotEmpty()) {
            put("pr", JSONArray().apply { s.procs.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        }
        if (s.capActive) put("pc", 1)
    }

    private fun intArrayJson(values: IntArray): JSONArray =
        JSONArray().apply { values.forEach { put(if (it < 0) JSONObject.NULL else it) } }

    private fun round1(value: Float): Double = (value * 10f).roundToInt() / 10.0

    private fun round2(value: Float): Double = (value * 100f).roundToInt() / 100.0

    private class StatFields(
        val comm: String,
        val state: Char,
        val utime: Long,
        val stime: Long,
        val threads: Int,
        val processor: Int?,
    )

    private class ProcessCpu(val pid: Int, val name: String, val cpu: Int)

    private class ThreadsReading(val count: Int, val dState: Int, val top: List<PerfThread>)

    private class FrameStats(val p50Ms: Float, val p99Ms: Float, val maxMs: Float)

    private class Session(
        private val context: Context,
        private val fpsProvider: () -> Float,
        drives: String?,
    ) {
        private val selfPid = android.os.Process.myPid()
        private val uid = android.os.Process.myUid()
        private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L).coerceAtLeast(1L)

        private val startMs = SystemClock.elapsedRealtime()
        private val samples = ArrayList<PerfSample>()
        private var sampleIndex = 0L
        private var keepEvery = 1L
        private var lastSampleMs = 0L
        private var lastT = 0

        private val clusters: List<PerfCluster> = runCatching { discoverClusters() }.getOrDefault(emptyList())
        private val coreCount: Int = runCatching { discoverCoreCount() }.getOrDefault(Runtime.getRuntime().availableProcessors())
        private val gpuFreqPath: String? = runCatching { discoverGpuFreqPath() }.getOrNull()
        private val skinTempPaths: List<String> = runCatching { discoverSkinTempPaths() }.getOrDefault(emptyList())
        private val refreshRateHz: Float = runCatching { readRefreshRate() }.getOrDefault(60f)
        private val maxAvailableKhz: Long? = runCatching { PowerManager.getAvailableCpuFrequencies().maxOrNull() }.getOrNull()
        private val installPath: String? = runCatching { gameDrivePath(drives) }.getOrNull()
        private val installLocation: String? = installPath?.let { classifyInstallPath(it) }
        private val totalMemMb: Int? = runCatching { memoryInfo()?.totalMem?.div(MB)?.toInt() }.getOrNull()

        private val gpuSampler = GpuUsageSampler()
        private var prevCpuStat: Map<String, LongArray>? = null
        private var prevProcTicks = HashMap<Int, Long>()
        private val procNames = HashMap<Int, String>()
        private var prevThreadTicks = HashMap<Int, Long>()
        private var prevCtxt = HashMap<Int, LongArray>()

        private val frameScratch = LongArray(FrameTimeRing.capacity())
        private val deltaScratch = LongArray(FrameTimeRing.capacity())
        private var lastFrameNs = 0L
        private val ownsFrameRing = !FrameTimeRing.isRecording()
        private val histogramMs = TreeMap<Int, Int>()
        private var totalFrames = 0L
        private var vsyncMultipleFrames = 0L

        private var lastThermalStatus: Int? = null
        private val thermalTransitions = ArrayList<Pair<Int, Int>>()
        private var powerControl: PerfPowerControl? = null

        @Volatile
        private var running = false
        private var thread: Thread? = null

        fun begin() {
            if (ownsFrameRing) FrameTimeRing.start()
            running = true
            thread = Thread(::loop, "PerfSampler").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
            Timber.i(
                "PerfSampler: started clusters=%s cores=%d gpuFreq=%s refresh=%.1f install=%s",
                clusters.joinToString { "${it.cores.toList()}@${it.maxKhz}" },
                coreCount,
                gpuFreqPath ?: "none",
                refreshRateHz,
                installLocation ?: "unknown",
            )
        }

        fun halt() {
            if (!running) return
            running = false
            thread?.interrupt()
            runCatching { thread?.join(2_000) }
            thread = null
            if (ownsFrameRing) FrameTimeRing.stop()
            Timber.i("PerfSampler: halted after %d samples (%d stored)", sampleIndex, samples.size)
        }

        fun toRun(): PerfRun = synchronized(samples) {
            PerfRun(
                intervalMs = INTERVAL_MS * keepEvery,
                runLengthSec = lastT,
                refreshRateHz = refreshRateHz,
                clusters = clusters,
                samples = samples.toList(),
                histogramMs = TreeMap(histogramMs),
                totalFrames = totalFrames,
                vsyncMultipleFrames = vsyncMultipleFrames,
                thermalTransitions = thermalTransitions.toList(),
                powerControl = powerControl ?: runCatching { readPowerControl() }.getOrNull(),
                installPath = installPath,
                installLocation = installLocation,
                totalMemMb = totalMemMb,
            )
        }

        private fun loop() {
            var nextMs = SystemClock.elapsedRealtime()
            while (running) {
                try {
                    sampleOnce()
                } catch (e: InterruptedException) {
                    return
                } catch (e: Throwable) {
                    Timber.w(e, "PerfSampler: sample failed")
                }
                nextMs += INTERVAL_MS
                val sleepMs = nextMs - SystemClock.elapsedRealtime()
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                } else {
                    nextMs = SystemClock.elapsedRealtime()
                }
            }
        }

        private fun sampleOnce() {
            val nowMs = SystemClock.elapsedRealtime()
            val first = lastSampleMs == 0L
            val dtSec = if (first) 0.0 else (nowMs - lastSampleMs) / 1000.0
            lastSampleMs = nowMs
            val t = ((nowMs - startMs) / 1000L).toInt()

            val cpuStat = safe { readCpuStat() }
            val frames = safe { readFrames() }
            val processes = safe { readProcesses(dtSec) } ?: emptyList()
            if (first) return
            val fps = safe { fpsProvider().takeIf { it.isFinite() && it >= 0f } }

            val wineserver = processes.firstOrNull { it.name == "wineserver" }
            val gameProcess = processes
                .filter { it.name.lowercase(Locale.US) !in excludedProcessNames }
                .maxByOrNull { it.cpu }
            val threads = gameProcess?.let { safe { readThreads(it.pid, dtSec) } }
            val game = gameProcess?.let {
                PerfGame(
                    pid = it.pid,
                    name = it.name,
                    cpu = it.cpu,
                    threadCount = threads?.count ?: 0,
                    dStateThreads = threads?.dState ?: 0,
                    top = threads?.top ?: emptyList(),
                )
            }
            val others = processes
                .filter { it.pid != gameProcess?.pid && it.pid != wineserver?.pid && it.cpu > 0 }
                .sortedByDescending { it.cpu }
                .take(TOP_PROCS)
                .map { it.name to it.cpu }

            val gpu = safe { gpuSampler.sample()?.percent }
            val gpuMhz = safe { readGpuMhz() }
            val curMhz = if (clusters.isEmpty()) null else safe { readClusterFreq("scaling_cur_freq") }
            val maxMhz = if (clusters.isEmpty()) null else safe { readClusterFreq("scaling_max_freq") }

            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) safe { readThermalStatus() } else null
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) safe { readThermalHeadroom() } else null
            if (thermalStatus != null && thermalStatus != lastThermalStatus) {
                lastThermalStatus = thermalStatus
                synchronized(samples) { thermalTransitions += t to thermalStatus }
            }

            val memory = safe { memoryInfo() }
            val pss = safe { (Debug.getPss() / 1024L).toInt() }
            val gameRss = gameProcess?.let { safe { readRssMb(it.pid) } }

            val pc = safe { readPowerControl() }
            if (pc != null) powerControl = pc

            val sample = PerfSample(
                t = t,
                fps = fps,
                frameP50Ms = frames?.p50Ms,
                frameP99Ms = frames?.p99Ms,
                frameMaxMs = frames?.maxMs,
                cpuTotal = cpuStat?.total,
                iowait = cpuStat?.iowait,
                cores = cpuStat?.cores,
                clusterCurMhz = curMhz,
                clusterMaxMhz = maxMhz,
                gpuBusy = gpu,
                gpuMhz = gpuMhz,
                thermalStatus = thermalStatus,
                thermalHeadroom = headroom,
                cpuTempC = safe { SystemMetricsSources.readTemperatureC(SystemMetricsSources.cpuTempPaths()) },
                batteryTempC = safe { readBatteryTempC() },
                skinTempC = if (skinTempPaths.isEmpty()) null else safe { SystemMetricsSources.readTemperatureC(skinTempPaths) },
                availMb = memory?.availMem?.div(MB)?.toInt(),
                lowMemory = memory?.lowMemory,
                pssMb = pss,
                gameRssMb = gameRss,
                game = game,
                wineserverCpu = wineserver?.cpu,
                procs = others,
                capActive = pc?.capActive == true,
            )
            store(sample, t)
        }

        private fun store(sample: PerfSample, t: Int) {
            synchronized(samples) {
                lastT = t
                sampleIndex++
                if (sampleIndex % keepEvery != 0L) return
                samples += sample
                if (samples.size >= MAX_SAMPLES) {
                    val kept = samples.filterIndexed { index, _ -> index % 2 == 0 }
                    samples.clear()
                    samples.addAll(kept)
                    keepEvery *= 2
                }
            }
        }

        private inline fun <T> safe(block: () -> T?): T? = try {
            block()
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Throwable) {
            null
        }

        private class CpuStatReading(val total: Int?, val iowait: Int?, val cores: IntArray?)

        private fun readCpuStat(): CpuStatReading? {
            val now = HashMap<String, LongArray>()
            File("/proc/stat").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("cpu")) break
                    val parts = line.split(' ').filter { it.isNotEmpty() }
                    if (parts.size < 5) continue
                    val values = parts.drop(1).take(8).mapNotNull { it.toLongOrNull() }
                    val idle = values.getOrElse(3) { 0L }
                    val iowait = values.getOrElse(4) { 0L }
                    now[parts[0]] = longArrayOf(values.sum(), idle + iowait, iowait)
                }
            }
            val previous = prevCpuStat
            prevCpuStat = now
            if (previous == null) return null

            fun delta(key: String): Pair<Int, Int>? {
                val a = previous[key] ?: return null
                val b = now[key] ?: return null
                val total = b[0] - a[0]
                if (total <= 0L) return null
                val busy = ((total - (b[1] - a[1])).coerceAtLeast(0L) * 100L / total).toInt().coerceIn(0, 100)
                val iow = ((b[2] - a[2]).coerceAtLeast(0L) * 100L / total).toInt().coerceIn(0, 100)
                return busy to iow
            }

            val all = delta("cpu")
            val cores = IntArray(coreCount) { delta("cpu$it")?.first ?: -1 }
            return CpuStatReading(all?.first, all?.second, cores)
        }

        private fun readFrames(): FrameStats? {
            val count = FrameTimeRing.copySince(lastFrameNs + 1, frameScratch)
            if (count <= 0) return null
            val stride = runCatching { PowerManager.frameSampleStride }.getOrDefault(1).coerceAtLeast(1)
            val intervalMs = 1000.0 / refreshRateHz
            var deltas = 0
            var prevNs = lastFrameNs
            var index = 0
            while (index < count) {
                val ts = frameScratch[index]
                if (prevNs > 0L) {
                    val delta = ts - prevNs
                    if (delta > 0L) {
                        deltaScratch[deltas++] = delta
                        val ms = delta / 1_000_000.0
                        val bucket = ms.toInt().coerceIn(0, HISTOGRAM_CAP_MS)
                        histogramMs[bucket] = (histogramMs[bucket] ?: 0) + 1
                        totalFrames++
                        val multiple = (ms / intervalMs).roundToInt()
                        if (multiple >= 2 && abs(ms - multiple * intervalMs) <= 1.0) vsyncMultipleFrames++
                    }
                }
                prevNs = ts
                index += stride
            }
            lastFrameNs = frameScratch[count - 1]
            if (deltas == 0) return null
            java.util.Arrays.sort(deltaScratch, 0, deltas)
            fun pct(q: Double): Float {
                val i = (Math.ceil(q * deltas).toInt() - 1).coerceIn(0, deltas - 1)
                return deltaScratch[i] / 1_000_000f
            }
            return FrameStats(pct(0.50), pct(0.99), deltaScratch[deltas - 1] / 1_000_000f)
        }

        private fun readProcesses(dtSec: Double): List<ProcessCpu> {
            val dirs = File("/proc").listFiles { file -> file.name.isNotEmpty() && file.name[0].isDigit() } ?: return emptyList()
            val ticksNow = HashMap<Int, Long>()
            val result = ArrayList<ProcessCpu>()
            for (dir in dirs) {
                val pid = dir.name.toIntOrNull() ?: continue
                if (pid == selfPid) continue
                try {
                    if (Os.stat(dir.path).st_uid != uid) continue
                    val fields = parseStat(File(dir, "stat").readText()) ?: continue
                    val ticks = fields.utime + fields.stime
                    ticksNow[pid] = ticks
                    val name = procNames.getOrPut(pid) { processName(dir, fields.comm) }
                    val prev = prevProcTicks[pid]
                    if (prev != null && dtSec > 0.0) {
                        result += ProcessCpu(pid, name, percentOfCore(ticks - prev, dtSec))
                    }
                } catch (_: Exception) {
                }
            }
            prevProcTicks = ticksNow
            procNames.keys.retainAll(ticksNow.keys)
            return result
        }

        private fun processName(dir: File, comm: String): String {
            val args = try {
                String(File(dir, "cmdline").readBytes()).split('\u0000').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            } catch (_: Exception) {
                emptyList()
            }
            val exe = args.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
                ?: args.firstOrNull { it.contains(".exe", ignoreCase = true) }
            val name = exe ?: comm
            return name.substringAfterLast('/').substringAfterLast('\\').take(40)
        }

        private fun readThreads(pid: Int, dtSec: Double): ThreadsReading? {
            val taskDirs = File("/proc/$pid/task").listFiles() ?: return null
            val ticksNow = HashMap<Int, Long>()
            val ctxtNow = HashMap<Int, LongArray>()
            val candidates = ArrayList<Triple<Int, StatFields, Int>>()
            var dState = 0
            for (dir in taskDirs) {
                val tid = dir.name.toIntOrNull() ?: continue
                val fields = try {
                    parseStat(File(dir, "stat").readText())
                } catch (_: Exception) {
                    null
                } ?: continue
                val ticks = fields.utime + fields.stime
                ticksNow[tid] = ticks
                if (fields.state == 'D') dState++
                val prev = prevThreadTicks[tid] ?: continue
                if (dtSec > 0.0) candidates += Triple(tid, fields, percentOfCore(ticks - prev, dtSec))
            }
            val top = candidates.sortedByDescending { it.third }.take(TOP_THREADS).map { (tid, fields, cpu) ->
                val ctxt = readCtxtSwitches(pid, tid)
                val prev = prevCtxt[tid]
                if (ctxt != null) ctxtNow[tid] = ctxt
                val rates = if (ctxt != null && prev != null && dtSec > 0.0) {
                    ((ctxt[0] - prev[0]).coerceAtLeast(0L) / dtSec).roundToInt() to
                        ((ctxt[1] - prev[1]).coerceAtLeast(0L) / dtSec).roundToInt()
                } else {
                    null
                }
                PerfThread(fields.comm, cpu, fields.state, fields.processor, rates?.first, rates?.second)
            }
            prevThreadTicks = ticksNow
            prevCtxt = ctxtNow
            return ThreadsReading(taskDirs.size, dState, top)
        }

        private fun readCtxtSwitches(pid: Int, tid: Int): LongArray? {
            var voluntary = -1L
            var nonvoluntary = -1L
            File("/proc/$pid/task/$tid/status").bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("voluntary_ctxt_switches:") ->
                            voluntary = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                        line.startsWith("nonvoluntary_ctxt_switches:") ->
                            nonvoluntary = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                    }
                }
            }
            if (voluntary < 0L || nonvoluntary < 0L) return null
            return longArrayOf(voluntary, nonvoluntary)
        }

        private fun readRssMb(pid: Int): Int? {
            val statm = File("/proc/$pid/statm").readText().trim().split(' ')
            val pages = statm.getOrNull(1)?.toLongOrNull() ?: return null
            return (pages * pageSize / MB).toInt()
        }

        private fun parseStat(stat: String): StatFields? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close < open || close + 2 > stat.length) return null
            val rest = stat.substring(close + 2).split(' ')
            if (rest.size < 18) return null
            return StatFields(
                comm = stat.substring(open + 1, close),
                state = rest[0].firstOrNull() ?: '?',
                utime = rest[11].toLongOrNull() ?: return null,
                stime = rest[12].toLongOrNull() ?: return null,
                threads = rest[17].toIntOrNull() ?: 0,
                processor = rest.getOrNull(36)?.toIntOrNull(),
            )
        }

        private fun percentOfCore(ticks: Long, dtSec: Double): Int =
            (ticks.coerceAtLeast(0L) * 100.0 / (clkTck * dtSec)).roundToInt()

        private fun readClusterFreq(fileName: String): IntArray = IntArray(clusters.size) { index ->
            var mhz = -1
            for (core in clusters[index].cores) {
                val khz = SystemMetricsSources.readLongFromLine("/sys/devices/system/cpu/cpu$core/cpufreq/$fileName") ?: continue
                mhz = (khz / 1000L).toInt()
                break
            }
            mhz
        }

        private fun readGpuMhz(): Int? {
            val raw = SystemMetricsSources.readLongFromLine(gpuFreqPath ?: return null) ?: return null
            return when {
                raw >= 100_000_000L -> (raw / 1_000_000L).toInt()
                raw >= 100_000L -> (raw / 1_000L).toInt()
                else -> raw.toInt()
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun readThermalStatus(): Int? =
            (context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager)?.currentThermalStatus

        @RequiresApi(Build.VERSION_CODES.R)
        private fun readThermalHeadroom(): Float? =
            (context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager)
                ?.getThermalHeadroom(10)
                ?.takeIf { it.isFinite() }

        private fun readBatteryTempC(): Int? {
            val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).takeIf { it > 0 }?.let { (it / 10f).roundToInt() }
        }

        private fun memoryInfo(): ActivityManager.MemoryInfo? {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            return ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        }

        private fun readPowerControl(): PerfPowerControl {
            val active = PowerManager.isGameStarted &&
                PowerManager.isDriverSupported() &&
                PowerManager.isProfilePowerControlEnabled()
            val profile = PowerManager.currentProfile
            val caps = PowerManager.latestTunerCaps()
            val maxAvailable = maxAvailableKhz
            val capped = maxAvailable != null && profile.maxCpuFreq in 1 until maxAvailable
            return PerfPowerControl(
                active = active,
                capActive = active && (capped || caps != null),
                maxCpuKhz = profile.maxCpuFreq,
                maxAvailableKhz = maxAvailable,
                tunerPrimeKhz = caps?.primeKhz,
                tunerPerformanceKhz = caps?.performanceKhz,
                tunerGpuLevel = caps?.gpuLevel,
            )
        }

        private fun readRefreshRate(): Float {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val rate = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
            return if (rate.isFinite() && rate > 1f) rate else 60f
        }

        private fun discoverCoreCount(): Int {
            val dirs = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
            val maxIndex = dirs?.maxOfOrNull { it.name.removePrefix("cpu").toInt() } ?: -1
            return if (maxIndex >= 0) maxIndex + 1 else Runtime.getRuntime().availableProcessors()
        }

        private fun discoverClusters(): List<PerfCluster> {
            val dirs = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
                ?: return emptyList()
            val byMax = TreeMap<Long, MutableList<Int>>()
            for (dir in dirs) {
                val index = dir.name.removePrefix("cpu").toIntOrNull() ?: continue
                val max = SystemMetricsSources.readLongFromLine("${dir.path}/cpufreq/cpuinfo_max_freq") ?: continue
                byMax.getOrPut(max) { mutableListOf() } += index
            }
            return byMax.map { (max, cores) -> PerfCluster(cores.sorted().toIntArray(), max) }
        }

        private fun discoverGpuFreqPath(): String? {
            listOf(
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            ).firstOrNull { File(it).canRead() }?.let { return it }
            for (root in listOf(File("/sys/class/devfreq"), File("/sys/devices/virtual/devfreq"))) {
                val nodes = root.listFiles { file -> file.isDirectory } ?: continue
                for (node in nodes) {
                    val path = node.path.lowercase(Locale.US)
                    if (listOf("gpu", "mali", "g3d", "kgsl").none { path.contains(it) }) continue
                    val file = File(node, "cur_freq")
                    if (file.canRead()) return file.path
                }
            }
            return null
        }

        private fun discoverSkinTempPaths(): List<String> {
            return listOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal")).flatMap { root ->
                val zones = root.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
                    ?: return@flatMap emptyList()
                zones.mapNotNull { zone ->
                    val type = SystemMetricsSources.readFirstLine(File(zone, "type").path)?.trim()?.lowercase(Locale.US)
                        ?: return@mapNotNull null
                    if (type.contains("skin")) File(zone, "temp").path else null
                }
            }.distinct()
        }

        private fun gameDrivePath(drives: String?): String? {
            if (drives.isNullOrBlank()) return null
            val entries = Container.drivesIterator(drives).map { it[0] to it[1] }
            return (
                entries.firstOrNull { it.first == "A" }
                    ?: entries.firstOrNull { (_, path) ->
                        !path.endsWith("/Download") && !path.endsWith("app.gamenative/storage")
                    }
                )?.second
        }

        private fun classifyInstallPath(path: String): String = when {
            path.startsWith("/data/data/") || path.startsWith("/data/user/") -> "internal"
            path.contains("/Android/data/") && path.startsWith("/storage/emulated/") -> "externalAppSpecific"
            path.contains("/Android/data/") -> "sdCardAppSpecific"
            path.startsWith("/storage/emulated/") -> "externalPublic"
            path.startsWith("/storage/") -> "sdCard"
            path.startsWith("/mnt/") -> "mounted"
            else -> "unknown"
        }

        private companion object {
            val excludedProcessNames = setOf(
                "wineserver",
                "services.exe",
                "explorer.exe",
                "winedevice.exe",
                "svchost.exe",
                "plugplay.exe",
                "rpcss.exe",
                "conhost.exe",
                "start.exe",
                "winhandler.exe",
                "tabtip.exe",
            )
        }
    }
}
