package app.gamenative.html5.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Window
import android.webkit.ConsoleMessage
import androidx.webkit.WebViewCompat
import app.gamenative.html5.host.AngleOverrideAdvisor
import app.gamenative.runtime.WebViewContainer
import app.gamenative.utils.DebugReportUtils
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// the html5 half of a debug run. the page console is written to the file a wine run logs to, so the report
// builder, the upload and the recovery of an interrupted run are shared. what the header says about the WebView
// sits in a sidecar next to that log: both are on disk, so a run that takes the app down is still reported.
object Html5DebugRun {
    private const val TAG = "Html5DebugRun"
    const val GAME_LINE_PREFIX = "[GN-DIAG-GAME] "
    private const val FLUSH_INTERVAL_MS = 1_000L
    private const val MAX_FRAME_SAMPLES = 16
    private const val FINISH_TIMEOUT_SEC = 3L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Html5DebugRun").apply { isDaemon = true }
    }

    // evaluated at teardown before the page goes; diag-game.js returns its partial window, or null.
    const val FLUSH_JS = "(window.__gnDiagFlush ? window.__gnDiagFlush() : null)"

    @Volatile
    private var active = false

    val isActive: Boolean get() = active

    @Volatile
    private var hostFrames: Html5HostFrameMetrics? = null

    const val HOST_FRAMES_PREFIX = "[GN-DIAG-HOST] "

    // composited-frame metrics for the activity window, drained by finish() so the last window lands too.
    fun attachWindow(context: Context, window: Window) {
        if (!active || hostFrames != null) return
        hostFrames = Html5HostFrameMetrics(context.applicationContext, window, ::onHostFrames).also { it.start() }
    }

    private fun onHostFrames(sample: JSONObject) {
        if (!active) return
        executor.execute {
            runCatching {
                writeLine("$HOST_FRAMES_PREFIX$sample")
                appendWindow("hostFrames", "hostFrameWindowsTotal", sample)
                flush()
            }.onFailure { Timber.tag(TAG).w(it, "host frame window not recorded") }
        }
    }

    // executor-confined from here down.
    private var writer: BufferedWriter? = null
    private var sidecarFile: File? = null
    private var sidecar: JSONObject? = null
    private var startedAtElapsedMs = 0L
    private var lastFlushMs = 0L

    private fun sidecarFile(context: Context, appId: String): File =
        File(DebugReportUtils.wineLogFile(context, appId).parentFile, "debug_run_$appId.html5.json")

    fun start(context: Context, appId: String, container: WebViewContainer, engine: String?) {
        val appContext = context.applicationContext
        val info = runCatching { launchInfo(appContext, container, engine) }
            .onFailure { Timber.tag(TAG).e(it, "launch info failed") }
            .getOrElse { JSONObject() }
        info.put("startedAtMs", System.currentTimeMillis())
        active = true
        executor.execute {
            runCatching {
                closeWriter()
                val log = DebugReportUtils.wineLogFile(appContext, appId)
                log.parentFile?.mkdirs()
                writer = log.bufferedWriter()
                sidecarFile = sidecarFile(appContext, appId)
                sidecar = info
                startedAtElapsedMs = SystemClock.elapsedRealtime()
                // also keeps the log non-empty for a page that never logs: an empty log makes no report.
                writeLine("html5 debug run $info")
                flush()
                saveSidecar()
            }.onFailure { Timber.tag(TAG).e(it, "start failed for %s", appId) }
        }
    }

    fun onConsoleMessage(msg: ConsoleMessage) {
        if (!active) return
        val message = msg.message() ?: return
        val line = "${levelOf(msg.messageLevel())} ${msg.sourceId()}:${msg.lineNumber()} $message"
        executor.execute {
            runCatching {
                writeLine(line)
                if (message.startsWith(GAME_LINE_PREFIX)) {
                    addFrameSample(JSONObject(message.removePrefix(GAME_LINE_PREFIX)))
                    flush()
                } else if (SystemClock.elapsedRealtime() - lastFlushMs >= FLUSH_INTERVAL_MS) {
                    flush()
                }
            }.onFailure { Timber.tag(TAG).w(it, "console line not recorded") }
        }
    }

    // evaluateJavascript result: the partial window as JSON, or "null". runs before finish() on the same
    // executor, so it lands in the sidecar before the report is built.
    fun onFinalSample(json: String?) {
        if (!active || json.isNullOrBlank() || json == "null") return
        executor.execute {
            runCatching {
                val sample = JSONObject(json)
                writeLine("$GAME_LINE_PREFIX$sample")
                addFrameSample(sample)
                flush()
            }.onFailure { Timber.tag(TAG).w(it, "final frame sample not recorded") }
        }
    }

    fun onProbe(json: String) {
        if (!active) return
        executor.execute {
            runCatching {
                val probe = JSONObject(json)
                sidecar?.put("probe", probe)
                saveSidecar()
                writeLine("webview probe $probe")
                flush()
            }.onFailure { Timber.tag(TAG).w(it, "probe not recorded") }
        }
    }

    // waits for the log to reach the disk: the report is built from it right after.
    fun finish() {
        if (!active) return
        // BEFORE active flips: drain enqueues the partial window ahead of the closing runnable below.
        hostFrames?.drain()
        hostFrames = null
        active = false
        runCatching {
            executor.submit(Runnable {
                sidecar?.put("endedAtMs", System.currentTimeMillis())
                saveSidecar()
                closeWriter()
                sidecar = null
                sidecarFile = null
            }).get(FINISH_TIMEOUT_SEC, TimeUnit.SECONDS)
        }.onFailure { Timber.tag(TAG).w(it, "finish did not complete") }
    }

    // called while the report is built; the sidecar existing is what marks the run as html5.
    fun applyToHeader(context: Context, appId: String, header: JSONObject) {
        val file = sidecarFile(context, appId)
        if (!file.exists()) return
        val html5 = runCatching { JSONObject(file.readText()) }
            .onFailure { Timber.tag(TAG).w(it, "unreadable sidecar for %s", appId) }
            .getOrElse { JSONObject() }
        val started = html5.optLong("startedAtMs")
        val ended = html5.optLong("endedAtMs")
        header.put("runtime", "html5")
        // the wine container's session figures are from an earlier wine session of this game, if any.
        header.put("avgFps", JSONObject.NULL)
        header.put("totalFrames", JSONObject.NULL)
        header.put("sessionLengthSec", if (started > 0 && ended > started) ((ended - started) / 1000).toInt() else JSONObject.NULL)
        header.put("html5", html5)
    }

    fun clearSidecar(context: Context, appId: String) {
        sidecarFile(context, appId).delete()
    }

    private fun launchInfo(context: Context, container: WebViewContainer, engine: String?): JSONObject {
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val refreshRate = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        }.getOrNull()
        return JSONObject().apply {
            put("engine", engine ?: container.engineProfile)
            put("subEngine", container.subEngine ?: JSONObject.NULL)
            put("webRoot", container.webRoot)
            put("entryPoint", container.entryPoint)
            put("inputMap", container.inputMap)
            put("renderScale", container.renderScale.toDouble())
            put("touchscreenMode", container.isTouchscreenMode)
            put("webviewPackage", webView?.packageName ?: JSONObject.NULL)
            put("webviewVersion", webView?.versionName ?: JSONObject.NULL)
            put("angleOverrideActive", AngleOverrideAdvisor.isOverrideActive(context))
            put("refreshRateHz", refreshRate?.toDouble() ?: JSONObject.NULL)
            put("powerSaveMode", power?.isPowerSaveMode ?: JSONObject.NULL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put("thermalStatus", power?.currentThermalStatus?.let(::thermalName) ?: JSONObject.NULL)
            }
        }
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }

    private fun levelOf(level: ConsoleMessage.MessageLevel?): String = when (level) {
        ConsoleMessage.MessageLevel.ERROR -> "E"
        ConsoleMessage.MessageLevel.WARNING -> "W"
        ConsoleMessage.MessageLevel.DEBUG -> "D"
        else -> "I"
    }

    private fun addFrameSample(sample: JSONObject) = appendWindow("frames", "frameWindowsTotal", sample)

    private fun appendWindow(key: String, totalKey: String, sample: JSONObject) {
        val current = sidecar ?: return
        val windows = current.optJSONArray(key) ?: JSONArray().also { current.put(key, it) }
        // keep window 1 (boot/title) and the latest ones; dropping new windows would hide a late slowdown.
        if (windows.length() >= MAX_FRAME_SAMPLES) windows.remove(1)
        windows.put(sample)
        current.put(totalKey, current.optInt(totalKey) + 1)
        saveSidecar()
    }

    private fun writeLine(line: String) {
        val out = writer ?: return
        val seconds = (SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000.0
        out.write(String.format(Locale.US, "%8.3f ", seconds))
        out.write(line)
        out.newLine()
    }

    private fun flush() {
        writer?.flush()
        lastFlushMs = SystemClock.elapsedRealtime()
    }

    private fun saveSidecar() {
        val file = sidecarFile ?: return
        val current = sidecar ?: return
        file.writeText(current.toString())
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }
}
