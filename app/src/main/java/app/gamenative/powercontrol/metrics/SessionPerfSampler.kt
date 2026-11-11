package app.gamenative.powercontrol.metrics

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import app.gamenative.powercontrol.PowerManager
import app.gamenative.utils.PerfGpuClock
import app.gamenative.utils.ProcStatParser
import app.gamenative.utils.SessionSample
import app.gamenative.utils.SessionSampleBuffer
import app.gamenative.utils.SessionSeries
import app.gamenative.utils.SessionVerdict
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Light per-session sampler of the Optimize loop. Runs once a second on the
 * [PerformanceMetricsCollector] tick and reuses that tick's readings; its own reads are the tracked hot thread's
 * stat and status files, the GPU's current max clock and, where the collector has no GPU clock, the GPU's cur_freq.
 * From the first frame on it also reads /proc/vmstat and, in a DXVK session, stats the game's state cache file,
 * one each a second, and reads the appended cache entries in the background.
 */
object SessionPerfSampler {
    private const val HIST_CAP_MS = 200
    private const val LOW_HOT_PCT = 50
    private const val LOW_HOT_REPICK_MS = 30_000L
    private const val GAME_BUSY_PCT = 100
    private const val WINDOW_SEC = 300
    private const val CACHE_LIST_EVERY_MS = 30_000L
    private const val CACHE_LIST_FOR_MS = 600_000L
    private const val CACHE_SCAN_BUDGET_MS = 50L
    /** GPU busy percent above which a second with no frame is not counted as the game standing still. */
    private const val STILL_GPU_MAX_PCT = 10

    private val lock = Any()
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    private var buffer: SessionSampleBuffer? = null

    /** The last finished session's records, until the next session starts. */
    @Volatile
    private var lastSeries: SessionSeries? = null
    private var firstFrameMs = 0L
    private var lastFrameNs = 0L
    private val histogramMs = LongArray(HIST_CAP_MS + 1)
    private val deltaScratch = LongArray(FrameTimeRing.capacity())

    private var gpuCurPath: String? = null
    private var gpuMaxPath: String? = null
    private var gpuTopMhz: Int? = null
    private var maxAvailableCpuKhz: Long? = null

    private var tracked: SessionProcessTelemetry.HotPick? = null
    private var prevUtime = -1L
    private var prevStime = -1L
    private var prevCtxt: LongArray? = null
    private var prevHotMs = 0L
    private var lowHotSinceMs = 0L
    private var lastLowMemoryEvents = 0
    private val hotNames = HashMap<String, Int>()
    private var lastRefreshHz = 0f
    /** Seconds since the game last presented a frame, counted only while it was not suspended and the GPU was idle. */
    private var stillSec = 0

    /** The state cache folder and the game's exe for the next session; null when it does not run on DXVK. */
    private class CacheTarget(val dir: File, val exeBase: String?)

    @Volatile
    private var nextCache: CacheTarget? = null
    private var cacheTarget: CacheTarget? = null
    private var cacheFile: File? = null
    private var cacheHeader: DxvkStateCache.Header? = null
    private var cacheBaseBytes = 0L
    private var cacheScanner: DxvkStateCache.Incremental? = null
    private var cacheHighBytes = 0L
    private var cacheListedMs = 0L
    private val cacheGrowth = WindowCounter()

    private var vmPrev: VmStat.Reading? = null
    private var vmRead = false
    private var vmFailures = 0
    private val majorFaults = WindowCounter()
    private val reclaimed = WindowCounter()

    /**
     * Set before the session starts: the folder DXVK keeps its state cache in (DXVK_STATE_CACHE_PATH) and the
     * game's executable, or null for a session that does not run on DXVK.
     */
    fun armStateCache(dir: String?, executablePath: String?) {
        nextCache = dir?.takeIf { it.isNotBlank() }?.let {
            val exe = executablePath?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.replace(Regex("\\.exe$", RegexOption.IGNORE_CASE), "")?.takeIf { e -> e.isNotEmpty() }
            CacheTarget(File(it), exe)
        }
    }

    private class HotReading(val pct: Int, val user: Int, val sys: Int, val state: Char, val core: Int?, val vol: Int?, val invol: Int?)

    fun start() {
        synchronized(lock) {
            buffer = SessionSampleBuffer(PowerTelemetry.clusterMaxKhz.size)
            lastSeries = null
            firstFrameMs = 0L
            lastFrameNs = 0L
            histogramMs.fill(0L)
            gpuCurPath = runCatching { PerfGpuClock.discoverCurPath() }.getOrNull()
            gpuMaxPath = runCatching { PerfGpuClock.maxPath(gpuCurPath) }.getOrNull()
            gpuTopMhz = runCatching { PerfGpuClock.topMhz(gpuCurPath) }.getOrNull()
                ?: runCatching { PowerManager.getAvailableGpuFrequencies().maxOrNull()?.div(1000L)?.toInt() }.getOrNull()
            maxAvailableCpuKhz = runCatching { PowerManager.getAvailableCpuFrequencies().maxOrNull() }.getOrNull()
            resetHot(null)
            lastLowMemoryEvents = 0
            hotNames.clear()
            lastRefreshHz = 0f
            stillSec = 0
            cacheTarget = nextCache
            nextCache = null
            cacheFile = null
            cacheHeader = null
            cacheBaseBytes = 0L
            cacheScanner = null
            cacheHighBytes = 0L
            cacheListedMs = 0L
            cacheGrowth.clear()
            vmPrev = null
            vmRead = false
            vmFailures = 0
            majorFaults.clear()
            reclaimed.clear()
        }
    }

    fun tick(
        frames: LongArray,
        frameCount: Int,
        frameStride: Int,
        fps: Float,
        refreshHz: Float,
        capFps: Int,
        cpu: CpuUsageReading?,
        gpuBusy: Int?,
        cpuTempC: Int?,
        thermalHeadroom: Float?,
        thermalStatus: Int?,
        gameSuspended: Boolean = false,
    ) = synchronized(lock) {
        val buf = buffer ?: return@synchronized
        if (refreshHz.isFinite() && refreshHz > 0f) lastRefreshHz = refreshHz
        val nowMs = SystemClock.elapsedRealtime()
        val deltas = newFrameDeltas(frames, frameCount, frameStride)
        val hot = readHot(nowMs)
        val playing = fps.isFinite() && fps > 0f
        if (playing && firstFrameMs == 0L) firstFrameMs = nowMs
        if (firstFrameMs != 0L && !gameSuspended) {
            stillSec = if (playing || (gpuBusy != null && gpuBusy > STILL_GPU_MAX_PCT)) 0 else stillSec + 1
        }
        if (firstFrameMs != 0L) {
            val window = ((nowMs - firstFrameMs) / 1000L / WINDOW_SEC).toInt()
            readVmStat(window)
            readStateCache(nowMs, window)
        }
        if (!playing) return@synchronized
        val t = ((nowMs - firstFrameMs) / 1000L).toInt()
        val gameplay = t >= SessionVerdict.WARMUP_SEC

        val intervalMs = if (refreshHz > 0f) 1000.0 / refreshHz else 0.0
        val capMs = if (capFps > 0) 1000.0 / capFps else 0.0
        var refreshMultiple = 0
        for (i in 0 until deltas) {
            val ms = deltaScratch[i] / 1_000_000.0
            if (gameplay) histogramMs[ms.toInt().coerceIn(0, HIST_CAP_MS)]++
            if (intervalMs <= 0.0 || (capMs > 0.0 && abs(ms - capMs) <= 1.0)) continue
            val multiple = (ms / intervalMs).roundToInt()
            if (multiple >= 2 && abs(ms - multiple * intervalMs) <= 1.0) refreshMultiple++
        }
        val stutterLimitMs = 3 * SessionVerdict.histogramMedianMs(histogramMs)
        var stutter = 0
        if (gameplay && stutterLimitMs > 0) {
            for (i in 0 until deltas) if (deltaScratch[i] / 1_000_000 > stutterLimitMs) stutter++
        }

        val clusterFreqs = PowerTelemetry.latestClusterFreqs
        val lowMemoryEvents = SessionProcessTelemetry.lowMemoryEventCount
        val trimEvent = lowMemoryEvents > lastLowMemoryEvents
        lastLowMemoryEvents = lowMemoryEvents
        if (gameplay && hot != null) tracked?.name?.let { hotNames[it] = (hotNames[it] ?: 0) + 1 }
        val realCpu = cpu?.takeIf { it.source == CpuUsageSource.PROC_STAT }

        buf.add(
            SessionSample(
                t = t,
                fps = fps,
                frames = deltas,
                stutterFrames = stutter,
                refreshMultipleFrames = refreshMultiple,
                gpuBusy = gpuBusy,
                gpuMhz = PowerTelemetry.latestGpuClkMhz ?: runCatching { PerfGpuClock.readMhz(gpuCurPath) }.getOrNull(),
                gpuMaxMhz = runCatching { PerfGpuClock.readMhz(gpuMaxPath) }.getOrNull(),
                cpuTotal = realCpu?.percent,
                coreMax = realCpu?.coreMaxPercent,
                clusterCurMhz = clusterFreqs.map { (it.first?.div(1000L))?.toInt() ?: -1 }.toIntArray(),
                clusterMaxMhz = clusterFreqs.map { (it.second?.div(1000L))?.toInt() ?: -1 }.toIntArray(),
                thermalStatus = thermalStatus,
                thermalHeadroom = thermalHeadroom,
                cpuTempC = cpuTempC,
                skinTempC = PowerTelemetry.latestSkinC,
                hotPct = hot?.pct,
                hotUserTicks = hot?.user,
                hotSysTicks = hot?.sys,
                hotState = hot?.state,
                hotCore = hot?.core,
                hotVolPerSec = hot?.vol,
                hotInvolPerSec = hot?.invol,
                wineserverPct = SessionProcessTelemetry.latestWineserverPct,
                availMb = SessionProcessTelemetry.latestAvailMb?.toInt(),
                lowMemory = SessionProcessTelemetry.latestLowMemory,
                trimEvent = trimEvent,
                capActive = capActive(),
            ),
        )
    }

    /**
     * The session's perf_verdict, stutter_share, throttle_onset_s, throttle_signal and frames_stopped_s (the seconds
     * before exit the game presented no frame while it was not suspended); empty when no frame was sampled. The
     * session's records move to [seriesFor] until the next session starts, so a later verdict never reads them.
     * [legacyShare] is the older classifier's bottleneck_share, used when the sampler cannot name a cause.
     */
    fun properties(
        designCapDetected: Boolean,
        fpsBy5Min: List<Int> = emptyList(),
        legacyShare: Map<String, Double>? = null,
    ): Map<String, Any> = synchronized(lock) {
        val buf = buffer ?: return@synchronized emptyMap()
        buffer = null
        lastSeries = null
        if (buf.size == 0) return@synchronized emptyMap()
        val cache = stateCacheResult()
        val clusterMaxMhz = PowerTelemetry.clusterMaxKhz.map { (it / 1000L).toInt() }.toIntArray()
        val trimEvents = SessionProcessTelemetry.lowMemoryEventCount
        val result = SessionVerdict.compute(
            SessionVerdict.Input(
                samples = buf.toList(),
                intervalSec = buf.keepEvery,
                clusterCpuinfoMaxMhz = clusterMaxMhz,
                clusterCores = PowerTelemetry.clusterCores,
                gpuTopMhz = gpuTopMhz,
                hotThreadName = hotNames.maxByOrNull { it.value }?.key,
                trimEvents = trimEvents,
                designCapDetected = designCapDetected,
                fpsCap = PowerManager.targetFps,
                stutterShare = SessionVerdict.stutterShare(histogramMs),
                fpsBy5Min = fpsBy5Min,
                legacyShare = legacyShare,
                refreshHz = lastRefreshHz.toDouble(),
                clusterKinds = runCatching { CpuClusterLayout.device() }.getOrNull(),
                majorFaultsBy5Min = if (vmRead) majorFaults.toList() else null,
                memReclaimBy5Min = if (vmRead) reclaimed.toList() else null,
                pipelineCompilesBy5Min = cache?.first,
                tessellationPipelines = cache?.second,
            ),
        )
        lastSeries = SessionSeries(buf, result.gameplaySec, clusterMaxMhz, gpuTopMhz, trimEvents, designCapDetected, legacyShare)
        result.properties() + ("frames_stopped_s" to stillSec)
    }

    /**
     * The records of the session that just ended when [metricsGameplaySec] is its gameplay_s, for the exit requests;
     * null once the next session has started, or for an older run.
     */
    internal fun seriesFor(metricsGameplaySec: Int?): SessionSeries? =
        lastSeries?.takeIf { metricsGameplaySec != null && it.gameplaySec == metricsGameplaySec }

    /** One /proc/vmstat read: the major faults and pages scanned for reclaim since the last second, system-wide. */
    private fun readVmStat(window: Int) {
        if (vmFailures >= 3) return
        val reading = try {
            File(VmStat.PATH).bufferedReader().useLines { VmStat.parse(it) }
        } catch (_: Exception) {
            null
        }
        if (reading == null) {
            vmFailures++
            return
        }
        vmFailures = 0
        val prev = vmPrev
        vmPrev = reading
        if (prev == null) return
        vmRead = true
        majorFaults.add(window, reading.majorFaults - prev.majorFaults)
        reclaimed.add(window, reading.reclaimScanned - prev.reclaimScanned)
    }

    /**
     * One stat of the game's state cache file a second; growth past the largest size seen is new pipelines. Until
     * the file is found the expected `<exe>.dxvk-cache` is stat'ed once a second and, for the first ten minutes,
     * the folder is listed every 30 s for a cache written since the session started (a launcher exe).
     */
    private fun readStateCache(nowMs: Long, window: Int) {
        val target = cacheTarget ?: return
        var file = cacheFile
        if (file == null) {
            file = locateStateCache(target, nowMs) ?: return
            cacheFile = file
            cacheHeader = DxvkStateCache.readHeader(file)
            cacheBaseBytes = file.length().coerceAtLeast(DxvkStateCache.HEADER_BYTES.toLong())
            cacheHighBytes = cacheBaseBytes
            cacheGrowth.touch(window)
            if (cacheHeader?.variable == true) cacheScanner = DxvkStateCache.Incremental(file, cacheBaseBytes).also { scanCache(it) }
            return
        }
        val size = file.length()
        cacheGrowth.touch(window)
        val grew = size > cacheHighBytes
        if (grew) {
            cacheGrowth.add(window, size - cacheHighBytes)
            cacheHighBytes = size
        }
        cacheScanner?.let { if (grew || !it.caughtUp) scanCache(it) }
    }

    private val cacheScanQueued = AtomicBoolean(false)
    private val cacheScanExecutor by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "dxvk-cache-scan").apply { isDaemon = true } }
    }

    /** One pass of [scanner] off the sampler's thread and lock, unless one is still running. */
    private fun scanCache(scanner: DxvkStateCache.Incremental) {
        if (!cacheScanQueued.compareAndSet(false, true)) return
        try {
            cacheScanExecutor.execute {
                try {
                    val deadline = SystemClock.elapsedRealtime() + CACHE_SCAN_BUDGET_MS
                    scanner.advance { SystemClock.elapsedRealtime() > deadline }
                } finally {
                    cacheScanQueued.set(false)
                }
            }
        } catch (e: Exception) {
            cacheScanQueued.set(false)
        }
    }

    private fun locateStateCache(target: CacheTarget, nowMs: Long): File? {
        target.exeBase?.let { exe -> File(target.dir, exe + DxvkStateCache.SUFFIX).takeIf { it.isFile }?.let { return it } }
        val sinceFirstFrame = nowMs - firstFrameMs
        if (sinceFirstFrame > CACHE_LIST_FOR_MS || (cacheListedMs != 0L && nowMs - cacheListedMs < CACHE_LIST_EVERY_MS)) return null
        cacheListedMs = nowMs
        val startedWall = System.currentTimeMillis() - sinceFirstFrame
        return target.dir.listFiles { f -> f.name.endsWith(DxvkStateCache.SUFFIX) }
            ?.filter { it.lastModified() >= startedWall }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * pipeline_compiles_by_5min and tessellation_pipelines from what the background scan read of the state cache
     * while the game ran, without touching the file; null when the session was not on DXVK or no cache was found.
     */
    private fun stateCacheResult(): Pair<List<Int>, Boolean?>? {
        if (cacheFile == null) return null
        val header = cacheHeader
        val scan = cacheScanner?.latest
        return DxvkStateCache.compilesByWindow(cacheGrowth.toList(), DxvkStateCache.entryBytes(header, scan)) to scan?.tessellation
    }

    /** Copies the frame intervals that arrived since the last tick into [deltaScratch]; returns their count. */
    private fun newFrameDeltas(frames: LongArray, frameCount: Int, frameStride: Int): Int {
        val stride = frameStride.coerceAtLeast(1)
        var deltas = 0
        var prevNs = lastFrameNs
        var index = 0
        while (index < frameCount) {
            val ts = frames[index]
            index += stride
            if (ts <= lastFrameNs) continue
            if (prevNs > 0L && ts > prevNs) deltaScratch[deltas++] = ts - prevNs
            prevNs = ts
        }
        lastFrameNs = prevNs
        return deltas
    }

    private fun readHot(nowMs: Long): HotReading? {
        val pick = SessionProcessTelemetry.hotPick
        if (pick == null) {
            resetHot(null)
            return null
        }
        if (pick !== tracked) resetHot(pick)
        val dir = "/proc/${pick.pid}/task/${pick.tid}"
        val stat = try {
            ProcStatParser.parse(File("$dir/stat").readText())
        } catch (_: Exception) {
            null
        }
        if (stat == null) {
            SessionProcessTelemetry.requestHotPick()
            return null
        }
        val ctxt = try {
            File("$dir/status").bufferedReader().useLines { ProcStatParser.parseCtxtSwitches(it) }
        } catch (_: Exception) {
            null
        }
        val dtSec = (nowMs - prevHotMs) / 1000.0
        val reading = if (prevHotMs > 0L && prevUtime >= 0L && dtSec > 0.0) {
            val user = (stat.utime - prevUtime).coerceAtLeast(0L)
            val sys = (stat.stime - prevStime).coerceAtLeast(0L)
            val prev = prevCtxt
            HotReading(
                pct = ProcStatParser.cpuPercent(user + sys, dtSec, clkTck),
                user = user.toInt(),
                sys = sys.toInt(),
                state = stat.state,
                core = stat.processor,
                vol = if (ctxt != null && prev != null) ((ctxt[0] - prev[0]).coerceAtLeast(0L) / dtSec).roundToInt() else null,
                invol = if (ctxt != null && prev != null) ((ctxt[1] - prev[1]).coerceAtLeast(0L) / dtSec).roundToInt() else null,
            )
        } else {
            null
        }
        prevUtime = stat.utime
        prevStime = stat.stime
        prevCtxt = ctxt
        prevHotMs = nowMs
        if (reading != null && reading.pct < LOW_HOT_PCT && (SessionProcessTelemetry.latestGamePct ?: 0) > GAME_BUSY_PCT) {
            if (lowHotSinceMs == 0L) {
                lowHotSinceMs = nowMs
            } else if (nowMs - lowHotSinceMs >= LOW_HOT_REPICK_MS) {
                SessionProcessTelemetry.requestHotPick()
                lowHotSinceMs = 0L
            }
        } else {
            lowHotSinceMs = 0L
        }
        return reading
    }

    private fun resetHot(pick: SessionProcessTelemetry.HotPick?) {
        tracked = pick
        prevUtime = -1L
        prevStime = -1L
        prevCtxt = null
        prevHotMs = 0L
        lowHotSinceMs = 0L
    }

    private fun capActive(): Boolean = runCatching {
        if (!PowerManager.isGameStarted || !PowerManager.isDriverSupported() || !PowerManager.isProfilePowerControlEnabled()) {
            return@runCatching false
        }
        val maxAvailable = maxAvailableCpuKhz
        val capped = maxAvailable != null && PowerManager.currentProfile.maxCpuFreq in 1 until maxAvailable
        capped || PowerManager.latestTunerCaps() != null
    }.getOrDefault(false)
}
