package app.gamenative.html5.diag

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import app.gamenative.powercontrol.metrics.GpuClock
import app.gamenative.powercontrol.metrics.SystemMetricsSources
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// frames the app window rendered (FrameMetrics), in 10s windows -- the same rate the Performance HUD shows.
// FrameMetrics reports frame completion, not display presentation, so this is the window's render rate; it can
// still sit far below the page's rAF rate (diag-game.js), which is the gap these windows exist to locate.
internal class Html5HostFrameMetrics(
    private val context: Context,
    private val window: Window,
    private val onWindow: (JSONObject) -> Unit,
) {
    private val thread = HandlerThread("Html5HostFrames").apply { start() }
    private val lock = Any()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // guarded by lock: the listener runs on [thread], drain() on whoever calls finish().
    private var index = 0
    private var startMs = 0L
    private var startCpuMs = 0L
    private var totalMs = FloatList()
    private var gpuMs = FloatList()
    private var syncMs = FloatList()
    private var issueMs = FloatList()
    private var swapMs = FloatList()
    private var missedDeadline = 0
    private var droppedReported = 0
    private var stopped = false

    // false when the frame listener couldn't be registered: frame fields are then unavailable, not zero.
    @Volatile
    private var observing = false

    // cpufreq per policy (cluster), sampled every SAMPLE_MS: a renderer stuck on slow or capped cores shows here
    // even though its own threads can't be read from our process. scaling_max below cpuinfo_max = a cap.
    private class Cluster(val dir: String, val cpus: String, val hwMaxKhz: Long?) {
        var n = 0
        var curSumKhz = 0L
        var curMaxKhz = 0L
        var capMinKhz = Long.MAX_VALUE
        var capN = 0
    }

    private val clusters: List<Cluster> = runCatching {
        File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }.orEmpty()
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            .map { d ->
                Cluster(
                    d.path,
                    runCatching { File(d, "related_cpus").readText().trim() }.getOrDefault("?"),
                    SystemMetricsSources.readLongFromLine("${d.path}/cpuinfo_max_freq"),
                )
            }
    }.getOrDefault(emptyList())
    private var gpuMhzSum = 0L
    private var gpuMhzN = 0
    private val handler = Handler(thread.looper)
    private val sampler = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (stopped) return
                sampleLocked()
                // windows close on time too, not only on a frame: a stall with no frames must not stretch the
                // current window across it.
                if (SystemClock.elapsedRealtime() - startMs >= WINDOW_MS) emitLocked(partial = false)
            }
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        synchronized(lock) {
            if (stopped) return@synchronized
            record(metrics, dropped)
            if (SystemClock.elapsedRealtime() - startMs >= WINDOW_MS) emitLocked(partial = false)
        }
    }

    fun start() {
        synchronized(lock) { resetLocked() }
        observing = runCatching { window.addOnFrameMetricsAvailableListener(listener, handler) }
            .onFailure { Timber.tag(TAG).w(it, "frame metrics listener not added") }
            .isSuccess
        handler.post(sampler)
    }

    // emits the unfinished window, so a short session still reports.
    fun drain() {
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        synchronized(lock) {
            if (stopped) return
            stopped = true
            // even with no frames: a stall right before exit is exactly what the last window should show.
            if (SystemClock.elapsedRealtime() - startMs >= MIN_PARTIAL_MS || totalMs.size > 0) emitLocked(partial = true)
        }
        thread.quitSafely()
    }

    private fun sampleLocked() {
        for (c in clusters) {
            SystemMetricsSources.readLongFromLine("${c.dir}/scaling_cur_freq")?.let { cur ->
                c.n++
                c.curSumKhz += cur
                if (cur > c.curMaxKhz) c.curMaxKhz = cur
            }
            SystemMetricsSources.readLongFromLine("${c.dir}/scaling_max_freq")?.let { cap ->
                c.capN++
                if (cap < c.capMinKhz) c.capMinKhz = cap
            }
        }
        runCatching { GpuClock.currentMhz() }.getOrNull()?.let {
            gpuMhzSum += it.toLong()
            gpuMhzN++
        }
    }

    private fun record(m: FrameMetrics, dropped: Int) {
        val total = ns(m, FrameMetrics.TOTAL_DURATION)
        totalMs.add(total)
        syncMs.add(ns(m, FrameMetrics.SYNC_DURATION))
        issueMs.add(ns(m, FrameMetrics.COMMAND_ISSUE_DURATION))
        swapMs.add(ns(m, FrameMetrics.SWAP_BUFFERS_DURATION))
        droppedReported += dropped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            gpuMs.add(ns(m, FrameMetrics.GPU_DURATION))
            if (total > ns(m, FrameMetrics.DEADLINE)) missedDeadline++
        }
    }

    private fun emitLocked(partial: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val wallMs = (now - startMs).coerceAtLeast(1)
        val cpuMs = Process.getElapsedCpuTime() - startCpuMs
        val out = JSONObject().apply {
            put("window", index + 1)
            put("partial", partial)
            put("lenSec", round1(wallMs / 1000f))
            if (!observing) {
                put("frameMetrics", "unavailable")
            } else {
                put("frames", totalMs.size)
                // reports Android dropped are still rendered frames, so they count toward the rate; the
                // percentiles below only cover the frames that were reported.
                put("windowFps", round1((totalMs.size + droppedReported) * 1000f / wallMs))
            }
            put("frameMsP50", totalMs.pct(0.5f))
            put("frameMsP95", totalMs.pct(0.95f))
            put("frameMsP99", totalMs.pct(0.99f))
            if (gpuMs.size > 0) {
                put("gpuMsP50", gpuMs.pct(0.5f))
                put("gpuMsP95", gpuMs.pct(0.95f))
                put("missedDeadline", missedDeadline)
            }
            // sync = texture uploads; commandIssue = recording GL, incl. WebView's draw functor; swap = queueing.
            put("syncMsP95", syncMs.pct(0.95f))
            put("commandIssueMsP95", issueMs.pct(0.95f))
            put("swapMsP95", swapMs.pct(0.95f))
            put("droppedReported", droppedReported)
            // our process only -- WebView's in-process GPU/compositor/audio work, not the sandboxed renderer.
            put("appCpuCores", round1(cpuMs.toFloat() / wallMs))
            put("appCpuPctOfDevice", round1(cpuMs * 100f / wallMs / cores))
            put("thermalStatus", thermal())
            if (clusters.any { it.n > 0 || it.capN > 0 }) {
                put(
                    "cpuClusters",
                    JSONArray().apply {
                        clusters.filter { it.n > 0 || it.capN > 0 }.forEach { c ->
                            put(
                                JSONObject().apply {
                                    put("cpus", c.cpus)
                                    put("hwMaxMhz", c.hwMaxKhz?.div(1000))
                                    if (c.n > 0) {
                                        put("curAvgMhz", c.curSumKhz / c.n / 1000)
                                        put("curMaxMhz", c.curMaxKhz / 1000)
                                    }
                                    if (c.capN > 0) put("capMinMhz", c.capMinKhz / 1000)
                                },
                            )
                        }
                    },
                )
            }
            if (gpuMhzN > 0) put("gpuClockAvgMhz", gpuMhzSum / gpuMhzN)
        }
        index++
        resetLocked()
        runCatching { onWindow(out) }.onFailure { Timber.tag(TAG).w(it, "host frame window not recorded") }
    }

    private fun resetLocked() {
        startMs = SystemClock.elapsedRealtime()
        startCpuMs = Process.getElapsedCpuTime()
        totalMs = FloatList()
        gpuMs = FloatList()
        syncMs = FloatList()
        issueMs = FloatList()
        swapMs = FloatList()
        missedDeadline = 0
        droppedReported = 0
        clusters.forEach {
            it.n = 0
            it.curSumKhz = 0
            it.curMaxKhz = 0
            it.capMinKhz = Long.MAX_VALUE
            it.capN = 0
        }
        gpuMhzSum = 0
        gpuMhzN = 0
    }

    private fun thermal(): Int? =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus }
            .getOrNull()

    private fun ns(m: FrameMetrics, id: Int): Float = m.getMetric(id) / 1_000_000f

    private fun round1(v: Float): Double = String.format(Locale.US, "%.1f", v).toDouble()

    private class FloatList {
        private var data = FloatArray(128)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun pct(p: Float): Double? {
            if (size == 0) return null
            val sorted = data.copyOf(size).also { it.sort() }
            val v = sorted[minOf(size - 1, (size * p).toInt())]
            return String.format(Locale.US, "%.1f", v).toDouble()
        }
    }

    private companion object {
        const val TAG = "Html5HostFrameMetrics"
        const val WINDOW_MS = 10_000L
        const val SAMPLE_MS = 500L
        const val MIN_PARTIAL_MS = 1_000L
    }
}
