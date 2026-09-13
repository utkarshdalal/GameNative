package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.winlator.container.Container
import org.json.JSONObject
import com.posthog.PostHog
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.widget.FrameRating
import com.winlator.xserver.Window
import kotlin.math.roundToInt
import timber.log.Timber

object DeviceTelemetry {

    fun registerSuperProperties(context: Context) {
        try {
            registerAll(deviceProperties(context))
        } catch (e: Exception) {
            Timber.w(e, "DeviceTelemetry: device properties failed")
        }
    }

    fun registerGpuSuperProperties(context: Context) {
        try {
            registerAll(gpuProperties(context))
        } catch (e: Exception) {
            Timber.w(e, "DeviceTelemetry: gpu properties failed")
        }
    }

    private fun registerAll(props: Map<String, Any>) {
        props.forEach { (key, value) -> PostHog.register(key, value) }
    }

    private fun deviceProperties(context: Context): Map<String, Any> = buildMap {
        put("modern_build", BuildConfig.MODERN_ANDROID)
        put("device_codename", Build.DEVICE)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("build_fingerprint", Build.FINGERPRINT)
        put("build_id", Build.ID)
        put("build_incremental", Build.VERSION.INCREMENTAL)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("kernel_version", System.getProperty("os.version") ?: "")
        put("cpu_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        put("cpu_cores", Runtime.getRuntime().availableProcessors())
        HardwareUtils.getSOCName()?.let { put("soc_model", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() }?.let { put("soc_manufacturer", it) }
        }

        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memInfo)
        if (memInfo.totalMem > 0) put("ram_total_mb", memInfo.totalMem / (1024L * 1024L))

        runCatching { StatFs(context.dataDir.path).totalBytes }
            .getOrNull()?.let { put("storage_total_gb", it / (1024L * 1024L * 1024L)) }

        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.let { d ->
            val mode = d.mode
            put("screen_width_px", mode.physicalWidth)
            put("screen_height_px", mode.physicalHeight)
            put("screen_refresh_hz", d.supportedModes.maxOfOrNull { it.refreshRate }?.toInt() ?: mode.refreshRate.toInt())
        }
        put("screen_density_dpi", context.resources.displayMetrics.densityDpi)
    }

    private fun gpuProperties(context: Context): Map<String, Any> = buildMap {
        GPUInformation.getRenderer(context)?.takeIf { it.isNotBlank() }?.let { put("gpu_name", it) }
        GPUInformation.getVendor(context)?.takeIf { it.isNotBlank() }?.let { put("gpu_vendor", it) }
        GPUInformation.getVersion(context)?.takeIf { it.isNotBlank() }?.let { put("gpu_driver_version", it) }
        runCatching { GPUHelper.vkGetApiVersion() }.getOrNull()?.let { api ->
            put("vulkan_api_version", "${GPUHelper.vkVersionMajor(api)}.${GPUHelper.vkVersionMinor(api)}.${api and 0xFFF}")
        }
    }
}

object SessionTelemetry {

    private val CONFIG_DIFF_IGNORED = setOf(
        "id", "name", "sessionMetadata", "drives", "executablePath", "execArgs", "configSource", "appliedConfigJson",
    )

    fun markConfigApplied(container: Container, source: String) {
        val snapshot = JSONObject(container.containerJson)
        CONFIG_DIFF_IGNORED.forEach { snapshot.remove(it) }
        container.setAppliedConfig(source, snapshot.toString())
        container.saveData()
    }

    fun configProperties(container: Container): Map<String, Any> = buildMap {
        put("config_source", container.configSource.ifEmpty { if (PrefManager.autoApplyKnownConfig) "none" else "disabled" })
        val applied = container.appliedConfigJson
        if (applied.isEmpty()) return@buildMap
        try {
            val before = JSONObject(applied)
            val after = JSONObject(container.containerJson)
            val changed = ArrayList<String>()
            for (key in after.keys()) {
                if (key in CONFIG_DIFF_IGNORED) continue
                if (key == "extraData") {
                    val b = before.optJSONObject(key) ?: JSONObject()
                    val a = after.optJSONObject(key) ?: JSONObject()
                    for (sub in a.keys()) if (b.optString(sub) != a.optString(sub)) changed.add("extraData.$sub")
                } else if (before.optString(key) != after.optString(key)) {
                    changed.add(key)
                }
            }
            put("config_edited_fields", changed.take(20))
        } catch (e: Exception) {
            Timber.w(e, "SessionTelemetry: config diff failed")
        }
    }

    fun exitProperties(
        context: Context?,
        frameRating: FrameRating?,
        gameplayTracker: GameplayTracker,
        container: Container,
        reason: String,
    ): Map<String, Any> = buildMap {
        put("exit_reason", reason)
        try {
            putAll(configProperties(container))
            putAll(gameplayTracker.snapshot(context))
            putAll(CrashCapture.properties())
            if (frameRating != null) {
                put("total_frames", frameRating.totalFrames)
                frameRating.fpsBy5Min.takeIf { it.isNotEmpty() }?.let { put("fps_by_5min", it) }
                if (frameRating.totalFrames > 1) {
                    put("frame_p50_ms", frameRating.getFramePercentileMs(0.50))
                    put("frame_p99_ms", frameRating.getFramePercentileMs(0.99))
                }
            }

            if (context == null) return@buildMap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.let { pm ->
                    put("thermal_status", pm.currentThermalStatus)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getThermalHeadroom(10).takeIf { !it.isNaN() }?.let { put("thermal_headroom", (it * 100).roundToInt() / 100.0) }
                    }
                }
            }
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).takeIf { it > 0 }
                    ?.let { put("battery_temp_c", (it / 10f).roundToInt()) }
                put("charging", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.let { dm ->
                dm.getDisplay(Display.DEFAULT_DISPLAY)?.let { put("display_refresh_hz", it.refreshRate.roundToInt()) }
                put("external_display", dm.displays.any { it.displayId != Display.DEFAULT_DISPLAY })
            }
        } catch (e: Exception) {
            Timber.w(e, "SessionTelemetry: exit properties failed")
        }
    }
}

class GameplayTracker {

    private class Entry(val className: String, val firstMs: Long) {
        var lastMs: Long = firstMs
        var gameplaySeconds: Int = 0
        var lastCountedSecond: Long = -1
    }

    private val lock = Any()
    private var startMs = 0L
    private val entries = LinkedHashMap<String, Entry>()
    private val classByWindowId = HashMap<Int, String>()
    private var firstGameplayMs = 0L
    private var batteryStartPct = -1
    private val thermalTransitions = ArrayList<Pair<Long, Int>>()
    private var powerManager: PowerManager? = null
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        synchronized(lock) {
            if (thermalTransitions.lastOrNull()?.second != status) {
                thermalTransitions.add((SystemClock.elapsedRealtime() - startMs) / 1000 to status)
            }
        }
    }

    fun start(context: Context) {
        stop()
        synchronized(lock) {
            startMs = SystemClock.elapsedRealtime()
            entries.clear()
            classByWindowId.clear()
            firstGameplayMs = 0L
            thermalTransitions.clear()
            batteryStartPct = readBatteryPct(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.also {
                synchronized(lock) { thermalTransitions.add(0L to it.currentThermalStatus) }
                runCatching { it.addThermalStatusListener(thermalListener) }
            }
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.let { runCatching { it.removeThermalStatusListener(thermalListener) } }
        }
        powerManager = null
    }

    private fun readBatteryPct(context: Context): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    fun onWindowContent(window: Window, screenWidth: Int, screenHeight: Int) {
        val now = SystemClock.elapsedRealtime()
        val area = window.width.toLong() * window.height.toLong()
        val isGameplaySized = area * 2 >= screenWidth.toLong() * screenHeight.toLong()
        synchronized(lock) {
            if (startMs == 0L) startMs = now
            val className = classByWindowId.getOrPut(window.id) { window.className.ifBlank { "unknown" } }
            val entry = entries.getOrPut(className) { Entry(className, now) }
            entry.lastMs = now
            if (!isGameplaySized) return
            if (firstGameplayMs == 0L) firstGameplayMs = now
            val second = now / 1000
            if (entry.lastCountedSecond != second) {
                entry.lastCountedSecond = second
                entry.gameplaySeconds++
            }
        }
    }

    fun snapshot(context: Context?): Map<String, Any> = synchronized(lock) {
        val best = entries.values.maxByOrNull { it.gameplaySeconds }
        buildMap {
            val batteryEnd = context?.let { readBatteryPct(it) } ?: -1
            if (batteryStartPct >= 0 && batteryEnd >= 0) {
                put("battery_start_pct", batteryStartPct)
                put("battery_end_pct", batteryEnd)
            }
            if (thermalTransitions.isNotEmpty()) {
                put("thermal_peak", thermalTransitions.maxOf { it.second })
                thermalTransitions.firstOrNull { it.second >= PowerManager.THERMAL_STATUS_MODERATE }
                    ?.let { put("time_to_throttle_s", it.first) }
                put("thermal_transitions", thermalTransitions.map { mapOf("t" to it.first, "status" to it.second) })
            }
            put("gameplay_seconds", best?.gameplaySeconds ?: 0)
            best?.takeIf { it.gameplaySeconds > 0 }?.let { put("gameplay_window_class", it.className) }
            if (firstGameplayMs > 0 && startMs > 0) put("time_to_gameplay_s", ((firstGameplayMs - startMs) / 100L) / 10.0)
            put(
                "window_timeline",
                entries.values.map { e ->
                    mapOf(
                        "class" to e.className,
                        "first_s" to ((e.firstMs - startMs) / 1000),
                        "last_s" to ((e.lastMs - startMs) / 1000),
                        "gameplay_s" to e.gameplaySeconds,
                    )
                },
            )
        }
    }
}

object CrashCapture {

    private const val MAX_FRAMES = 5

    private val winePrefix = Regex("^(\\d+\\.\\d+:)?[0-9a-f]{4}:([0-9a-f]{4}:)?")
    private val frame = Regex("^\\s*=?>?\\s*\\d+\\s+0x[0-9a-f]+ in (\\S+) \\(\\+0x([0-9a-f]+)\\)")
    private val hexAddress = Regex("0x[0-9a-fA-F]+")

    private val lock = Any()
    private var exception: String? = null
    private val frames = ArrayList<String>()
    private var inBacktrace = false

    fun reset() = synchronized(lock) {
        exception = null
        frames.clear()
        inBacktrace = false
    }

    fun onLine(raw: String) {
        val line = winePrefix.replace(raw, "").trim()
        synchronized(lock) {
            if (exception == null) {
                if (line.startsWith("Unhandled exception:")) {
                    exception = hexAddress.replace(line.removePrefix("Unhandled exception:").trim(), "").trim().take(120)
                }
                return
            }
            if (frames.size >= MAX_FRAMES) return
            if (line.startsWith("Backtrace:")) {
                inBacktrace = true
                return
            }
            if (!inBacktrace) return
            val m = frame.find(line)
            if (m != null) {
                frames.add("${m.groupValues[1]}+0x${m.groupValues[2]}")
            } else if (frames.isNotEmpty()) {
                inBacktrace = false
            }
        }
    }

    fun properties(): Map<String, Any> = synchronized(lock) {
        val exc = exception ?: return emptyMap()
        buildMap {
            put("crash_exception", exc)
            frames.firstOrNull()?.let { top ->
                put("crash_signature", top)
                put("crash_module", top.substringBefore("+0x"))
            }
            if (frames.isNotEmpty()) put("crash_frames", frames.toList())
        }
    }
}

