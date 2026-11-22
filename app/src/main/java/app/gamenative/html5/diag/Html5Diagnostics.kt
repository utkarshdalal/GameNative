package app.gamenative.html5.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.webkit.WebViewCompat
import app.gamenative.BuildConfig
import app.gamenative.powercontrol.PowerManager as GnPowerManager
import app.gamenative.runtime.WebViewContainer
import app.gamenative.utils.ContainerUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// backs Settings > HTML5 diagnostics: host-side facts, the latest in-game snapshot from diag-game.js, and the
// plain-text report users copy/share. lets us triage devices we don't own.
object Html5Diagnostics {
    private const val TAG = "Html5Diagnostics"
    const val GAME_LINE_PREFIX = "[GN-DIAG-GAME] "

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // set at launch so in-game snapshots carry the container settings they ran under.
    @Volatile
    private var launchInfo: JSONObject? = null

    private fun lastGameFile(context: Context) = File(File(context.filesDir, "html5-diag"), "last-game.json")

    fun hostInfo(context: Context): LinkedHashMap<String, Any?> {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val resolver = context.contentResolver
        fun global(key: String) = runCatching { Settings.Global.getString(resolver, key) }.getOrNull()
        val dm = context.resources.displayMetrics
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE
        }
        return linkedMapOf(
            "app" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "soc" to soc,
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "webviewPackage" to pkg?.packageName,
            "webviewVersion" to pkg?.versionName,
            "display" to "${dm.widthPixels}x${dm.heightPixels} density=${dm.density}",
            "refreshRateHz" to runCatching {
                (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
            }.getOrNull(),
            // ANGLE backend is chosen by these global settings; we can read but not write them.
            "angleAllApps" to global("angle_gl_driver_all_angle"),
            "angleSelectionPkgs" to global("angle_gl_driver_selection_pkgs"),
            "angleSelectionValues" to global("angle_gl_driver_selection_values"),
            "thermalStatus" to pm?.currentThermalStatus?.let(::thermalName),
            "powerSaveMode" to pm?.isPowerSaveMode,
            "powerControl" to runCatching { GnPowerManager.isProfilePowerControlEnabled() }.getOrNull(),
        )
    }

    private fun thermalName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }

    fun onLaunch(context: Context, appId: String, container: WebViewContainer, engine: String?) {
        runCatching {
            val host = hostInfo(context)
            launchInfo = JSONObject().apply {
                put("launchedAt", timestamp())
                put("appId", appId)
                put("game", runCatching { ContainerUtils.resolveGameName(appId) }.getOrNull())
                put("engine", engine)
                put("subEngine", container.subEngine)
                put("renderScale", container.renderScale)
                put("touchscreenMode", container.isTouchscreenMode)
                put("launchThermalStatus", host["thermalStatus"])
                put("launchPowerControl", host["powerControl"])
            }
            Timber.tag(TAG).i("launch appId=%s engine=%s host=%s", appId, engine, host)
        }.onFailure { Timber.tag(TAG).e(it, "launch diagnostics failed") }
    }

    // called from onConsoleMessage; returns true if the line was ours.
    fun onConsoleLine(context: Context, message: String): Boolean {
        if (!message.startsWith(GAME_LINE_PREFIX)) return false
        val payload = message.removePrefix(GAME_LINE_PREFIX)
        val launch = launchInfo
        val appContext = context.applicationContext
        ioScope.launch {
            runCatching {
                val sample = JSONObject(payload).put("capturedAt", timestamp())
                val file = lastGameFile(appContext)
                val launchJson = launch ?: JSONObject()
                // same launch -> append the window; new launch -> start over.
                val existing = readLastGame(appContext)?.takeIf {
                    it.optJSONObject("launch")?.optString("launchedAt") == launchJson.optString("launchedAt")
                }
                val merged = existing ?: JSONObject().put("launch", launchJson).put("samples", JSONArray())
                merged.getJSONArray("samples").put(sample)
                file.parentFile?.mkdirs()
                file.writeText(merged.toString())
            }.onFailure { Timber.tag(TAG).e(it, "failed to persist game snapshot") }
        }
        return true
    }

    fun readLastGame(context: Context): JSONObject? = runCatching {
        lastGameFile(context).takeIf { it.exists() }?.readText()?.let(::JSONObject)
    }.onFailure { Timber.tag(TAG).e(it, "failed to read game snapshot") }.getOrNull()

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun formatReport(host: Map<String, Any?>, probe: JSONObject?, probeError: String?, lastGame: JSONObject?): String =
        buildString {
            appendLine("GameNative HTML5 diagnostics")
            appendLine("generated: ${timestamp()}")
            appendLine()
            appendLine("== Device ==")
            host.forEach { (k, v) -> appendLine("$k: ${v ?: "-"}") }
            appendLine()
            appendLine("== WebView probe ==")
            when {
                probe != null -> appendJson(probe, "")
                else -> appendLine(probeError ?: "-")
            }
            appendLine()
            appendLine("== Last HTML5 game ==")
            if (lastGame != null) appendJson(lastGame, "") else appendLine("none yet -- play an HTML5 game for ~30s, then reopen")
        }

    // flattens nested objects/arrays to dotted keys; scalar arrays (extension lists) stay on one line.
    private fun StringBuilder.appendJson(obj: JSONObject, prefix: String) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            appendValue(if (prefix.isEmpty()) key else "$prefix.$key", obj.opt(key))
        }
    }

    private fun StringBuilder.appendValue(key: String, value: Any?) {
        when (value) {
            is JSONObject -> appendJson(value, key)
            is JSONArray -> {
                val items = (0 until value.length()).map { value.opt(it) }
                if (items.any { it is JSONObject || it is JSONArray }) {
                    items.forEachIndexed { i, item -> appendValue("$key[${i + 1}]", item) }
                } else {
                    appendLine("$key: ${items.joinToString(", ")}")
                }
            }
            JSONObject.NULL, null -> appendLine("$key: -")
            else -> appendLine("$key: $value")
        }
    }
}
