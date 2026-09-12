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

    fun exitProperties(
        context: Context?,
        frameRating: FrameRating?,
        gameplayTracker: GameplayTracker,
        reason: String,
    ): Map<String, Any> = buildMap {
        put("exit_reason", reason)
        try {
            putAll(gameplayTracker.snapshot())
            if (frameRating != null) {
                put("total_frames", frameRating.totalFrames)
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

    fun reset() = synchronized(lock) {
        startMs = SystemClock.elapsedRealtime()
        entries.clear()
        classByWindowId.clear()
        firstGameplayMs = 0L
    }

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

    fun snapshot(): Map<String, Any> = synchronized(lock) {
        val best = entries.values.maxByOrNull { it.gameplaySeconds }
        buildMap {
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
