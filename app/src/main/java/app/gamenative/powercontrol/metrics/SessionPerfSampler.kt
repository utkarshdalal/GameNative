package app.gamenative.powercontrol.metrics

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import app.gamenative.powercontrol.PowerManager
import app.gamenative.utils.PerfGpuClock
import app.gamenative.utils.ProcStatParser
import app.gamenative.utils.SessionSample
import app.gamenative.utils.SessionSampleBuffer
import app.gamenative.utils.SessionVerdict
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Light per-session sampler of the Optimize loop (optimize-loop-spec 5.1). Runs once a second on the
 * [PerformanceMetricsCollector] tick and reuses that tick's readings; its own reads are the tracked hot thread's
 * stat and status files, the GPU's current max clock and, where the collector has no GPU clock, the GPU's cur_freq.
 */
object SessionPerfSampler {
    private const val HIST_CAP_MS = 200
    private const val LOW_HOT_PCT = 50
    private const val LOW_HOT_REPICK_MS = 30_000L
    private const val GAME_BUSY_PCT = 100

    private val lock = Any()
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    private var buffer: SessionSampleBuffer? = null
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

    private class HotReading(val pct: Int, val user: Int, val sys: Int, val state: Char, val core: Int?, val vol: Int?, val invol: Int?)

    fun start() {
        synchronized(lock) {
            buffer = SessionSampleBuffer(PowerTelemetry.clusterMaxKhz.size)
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
    ) = synchronized(lock) {
        val buf = buffer ?: return@synchronized
        if (refreshHz.isFinite() && refreshHz > 0f) lastRefreshHz = refreshHz
        val nowMs = SystemClock.elapsedRealtime()
        val deltas = newFrameDeltas(frames, frameCount, frameStride)
        val hot = readHot(nowMs)
        if (!fps.isFinite() || fps <= 0f) return@synchronized
        if (firstFrameMs == 0L) firstFrameMs = nowMs
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
     * The session's perf_verdict, stutter_share, throttle_onset_s and throttle_signal; empty when no frame was
     * sampled. Clears the session's samples so a later session never reports them. [legacyShare] is the older
     * classifier's bottleneck_share, used when the sampler cannot name a cause.
     */
    fun properties(
        designCapDetected: Boolean,
        fpsBy5Min: List<Int> = emptyList(),
        legacyShare: Map<String, Double>? = null,
    ): Map<String, Any> = synchronized(lock) {
        val buf = buffer ?: return@synchronized emptyMap()
        buffer = null
        if (buf.size == 0) return@synchronized emptyMap()
        SessionVerdict.compute(
            SessionVerdict.Input(
                samples = buf.toList(),
                intervalSec = buf.keepEvery,
                clusterCpuinfoMaxMhz = PowerTelemetry.clusterMaxKhz.map { (it / 1000L).toInt() }.toIntArray(),
                clusterCores = PowerTelemetry.clusterCores,
                gpuTopMhz = gpuTopMhz,
                hotThreadName = hotNames.maxByOrNull { it.value }?.key,
                trimEvents = SessionProcessTelemetry.lowMemoryEventCount,
                designCapDetected = designCapDetected,
                fpsCap = PowerManager.targetFps,
                stutterShare = SessionVerdict.stutterShare(histogramMs),
                fpsBy5Min = fpsBy5Min,
                legacyShare = legacyShare,
                refreshHz = lastRefreshHz.toDouble(),
            ),
        ).properties()
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
