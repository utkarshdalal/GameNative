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
import java.io.File
import org.json.JSONObject
import com.posthog.PostHog
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.widget.FrameRating
import com.winlator.xserver.Window
import kotlin.math.roundToInt
import timber.log.Timber

object DeviceInfo {

    fun registerSuperProperties(context: Context) {
        try {
            registerAll(deviceProperties(context))
        } catch (e: Exception) {
            Timber.w(e, "DeviceInfo: device properties failed")
        }
    }

    fun registerGpuSuperProperties(context: Context) {
        try {
            registerAll(gpuProperties(context))
        } catch (e: Exception) {
            Timber.w(e, "DeviceInfo: gpu properties failed")
        }
    }

    private fun registerAll(props: Map<String, Any>) {
        props.forEach { (key, value) -> PostHog.register(key, value) }
    }

    private fun systemProperty(name: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun deviceProperties(context: Context): Map<String, Any> = buildMap {
        put("modern_build", BuildConfig.MODERN_ANDROID)
        put("device_codename", Build.DEVICE)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("build_fingerprint", Build.FINGERPRINT)
        put("build_id", Build.ID)
        put("build_incremental", Build.VERSION.INCREMENTAL)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("firmware_build_time", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(Build.TIME)))
        systemProperty("ro.product.first_api_level")?.toIntOrNull()?.let { put("first_api_level", it) }
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

object SessionReport {

    private val CONFIG_DIFF_IGNORED = setOf(
        "id", "name", "sessionMetadata", "drives", "executablePath", "execArgs", "configSource",
    )

    fun markConfigApplied(container: Container, source: String) {
        try {
            val snapshot = JSONObject(container.containerJson)
            CONFIG_DIFF_IGNORED.forEach { snapshot.remove(it) }
            container.setConfigSource(source)
            container.saveData()
            appliedConfigFile(container).writeText(snapshot.toString())
        } catch (e: Exception) {
            Timber.w(e, "SessionReport: mark config failed")
        }
    }

    private fun appliedConfigFile(container: Container) = File(container.rootDir, "applied_config.json")

    fun configProperties(container: Container): Map<String, Any> = buildMap {
        try {
            put("config_source", container.configSource.ifEmpty { if (PrefManager.autoApplyKnownConfig) "none" else "disabled" })
            val applied = appliedConfigFile(container).takeIf { it.exists() }?.readText().orEmpty()
            if (applied.isEmpty()) {
                markConfigApplied(container, container.configSource.ifEmpty { "existing" })
                return@buildMap
            }
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
            Timber.w(e, "SessionReport: config diff failed")
        }
    }

    fun exitProperties(
        context: Context?,
        frameRating: FrameRating?,
        windowActivity: WindowActivity,
        container: Container,
        reason: String,
    ): Map<String, Any> = buildMap {
        put("exit_reason", reason)
        try {
            putAll(configProperties(container))
            putAll(windowActivity.snapshot(context, frameRating?.totalFrames ?: 0L))
            if (frameRating != null) {
                put("total_frames", frameRating.totalFrames)
                frameRating.fpsBy5Min.takeIf { it.isNotEmpty() }?.let { put("fps_by_5min", it) }
                if (frameRating.totalFrames >= 60) {
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
            Timber.w(e, "SessionReport: exit properties failed")
        }
    }
}

class WindowActivity {

    private class Entry(val className: String, val firstMs: Long) {
        var lastMs: Long = firstMs
        var frames: Long = 0
        var mapped: Boolean = false
    }

    private var trackedClass: String? = null
    private var trackedStartFrames = 0L

    private val lock = Any()
    private var startMs = 0L
    private val windows = LinkedHashMap<String, Entry>()
    private val classByWindowId = HashMap<Int, String>()
    private var batteryStartPct = -1
    private val thermalTransitions = ArrayList<Pair<Long, Int>>()
    private var powerManager: PowerManager? = null
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        try {
            synchronized(lock) {
                if (thermalTransitions.lastOrNull()?.second != status) {
                    thermalTransitions.add((SystemClock.elapsedRealtime() - startMs) / 1000 to status)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: thermal sample failed")
        }
    }

    fun start(context: Context) {
        try {
            begin(context)
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: start failed")
        }
    }

    private fun begin(context: Context) {
        stop()
        synchronized(lock) {
            startMs = SystemClock.elapsedRealtime()
            windows.clear()
            classByWindowId.clear()
            thermalTransitions.clear()
            trackedClass = null
            trackedStartFrames = 0L
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager?.let { it.removeThermalStatusListener(thermalListener) }
            }
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: stop failed")
        }
        powerManager = null
    }

    private fun readBatteryPct(context: Context): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    fun onWindowContent(window: Window) {
        try {
            entryFor(window)
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: sample failed")
        }
    }

    fun onTrackedWindow(window: Window?, totalFrames: Long) {
        try {
            val next = window?.let { entryFor(it) }
            synchronized(lock) {
                closeTrackedSegment(totalFrames)
                trackedClass = next?.className
                trackedStartFrames = totalFrames
            }
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: tracked window failed")
        }
    }

    private fun closeTrackedSegment(totalFrames: Long) {
        val cls = trackedClass ?: return
        val delta = totalFrames - trackedStartFrames
        if (delta > 0) windows[cls]?.let { it.frames += delta }
        trackedStartFrames = totalFrames
    }

    fun onWindowMapped(window: Window) {
        try {
            entryFor(window).mapped = true
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: map failed")
        }
    }

    fun onWindowUnmapped(window: Window) {
        try {
            val entry = synchronized(lock) { classByWindowId[window.id]?.let { windows[it] } } ?: return
            entry.mapped = false
            entry.lastMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Timber.w(e, "WindowActivity: unmap failed")
        }
    }

    private fun entryFor(window: Window): Entry = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (startMs == 0L) startMs = now
        val className = classByWindowId.getOrPut(window.id) { window.className.ifBlank { "unknown" } }
        windows.getOrPut(className) { Entry(className, now) }
    }

    fun snapshot(context: Context?, totalFrames: Long): Map<String, Any> = try {
        buildSnapshot(context, totalFrames)
    } catch (e: Exception) {
        Timber.w(e, "WindowActivity: snapshot failed")
        emptyMap()
    }

    private fun buildSnapshot(context: Context?, totalFrames: Long): Map<String, Any> = synchronized(lock) {
        closeTrackedSegment(totalFrames)
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
            if (windows.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                fun endMs(e: Entry) = if (e.mapped) now else e.lastMs
                put("window_classes", windows.keys.joinToString(","))
                windows.values.maxByOrNull { endMs(it) }?.let { put("last_window_class", it.className) }
                windows.values.maxByOrNull { it.frames }?.takeIf { it.frames > 0 }?.let { main ->
                    put("main_window_class", main.className)
                    put("main_window_seconds", (endMs(main) - main.firstMs) / 1000)
                    put("main_window_frames", main.frames)
                }
                put(
                    "window_timeline",
                    windows.values.map { e ->
                        mapOf(
                            "class" to e.className,
                            "first_s" to ((e.firstMs - startMs) / 1000),
                            "last_s" to ((endMs(e) - startMs) / 1000),
                            "frames" to e.frames,
                        )
                    },
                )
            }
        }
    }
}
