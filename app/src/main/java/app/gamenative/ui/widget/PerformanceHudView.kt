package app.gamenative.ui.widget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight floating HUD shown above the in-game surface.
 *
 * Metric collection runs off the main thread and rows are hidden automatically
 * when a given stat is not available on the current device.
 */
class PerformanceHudView(
    context: Context,
    private val fpsProvider: () -> Float,
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null

    private val fpsText = createRow(0xFF4CAF50.toInt())
    private val cpuText = createRow(0xFF42A5F5.toInt())
    private val gpuText = createRow(0xFFEF5350.toInt())
    private val ramText = createRow(0xFFFFEE58.toInt())
    private val batteryText = createRow(0xFFFFFFFF.toInt())
    private val powerText = createRow(0xFF4DD0E1.toInt())
    private val cpuTempText = createRow(0xFFBDBDBD.toInt())
    private val gpuTempText = createRow(0xFFBDBDBD.toInt())

    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null

    init {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10.dp.toFloat()
            setColor(0xB8000000.toInt())
            setStroke(1.dp, 0x44FFFFFF)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            this.background = background
        }

        listOf(
            fpsText,
            cpuText,
            gpuText,
            ramText,
            batteryText,
            powerText,
            cpuTempText,
            gpuTempText,
        ).forEach(container::addView)

        addView(container)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdates()
    }

    override fun onDetachedFromWindow() {
        stopUpdates()
        super.onDetachedFromWindow()
    }

    private fun startUpdates() {
        if (updateJob?.isActive == true) {
            return
        }

        updateJob = scope.launch {
            while (isActive) {
                val currentFps = fpsProvider().coerceAtLeast(0f)
                val snapshot = withContext(Dispatchers.IO) {
                    collectSnapshot(currentFps)
                }
                renderSnapshot(snapshot)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun collectSnapshot(currentFps: Float): HudSnapshot {
        return HudSnapshot(
            fps = String.format(Locale.US, "FPS %.1f", currentFps),
            cpu = readCpuUsagePercent()?.let { "CPU $it%" },
            gpu = readGpuUsagePercent()?.let { "GPU $it%" },
            ram = "RAM ${readUsedRamText()}",
            battery = readBatteryPercent()?.let { "BAT $it%" },
            power = readPowerWatts()?.let { watts ->
                String.format(Locale.US, "PWR %.1fW", watts)
            },
            cpuTemp = readCpuTempC()?.let { "CPU TEMP ${it}°C" },
            gpuTemp = readGpuTempC()?.let { "GPU TEMP ${it}°C" },
        )
    }

    private fun renderSnapshot(snapshot: HudSnapshot) {
        fpsText.text = snapshot.fps
        updateRow(cpuText, snapshot.cpu)
        updateRow(gpuText, snapshot.gpu)
        ramText.text = snapshot.ram
        updateRow(batteryText, snapshot.battery)
        updateRow(powerText, snapshot.power)
        updateRow(cpuTempText, snapshot.cpuTemp)
        updateRow(gpuTempText, snapshot.gpuTemp)
    }

    private fun updateRow(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    private fun createRow(color: Int): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 2.dp, 0, 2.dp)
        }
    }

    private fun readCpuUsagePercent(): Int? {
        val parts = readFirstLine("/proc/stat")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?: return null

        if (parts.size < 5 || parts.firstOrNull() != "cpu") {
            return null
        }

        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) {
            return null
        }

        val idle = values.getOrElse(3) { 0L }
        val iowait = values.getOrElse(4) { 0L }
        val total = values.sum()
        val idleTotal = idle + iowait

        val previousTotal = lastCpuTotal
        val previousIdle = lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idleTotal

        if (previousTotal == null || previousIdle == null) {
            return null
        }

        val totalDiff = total - previousTotal
        val idleDiff = idleTotal - previousIdle
        if (totalDiff <= 0) {
            return null
        }

        return (((totalDiff - idleDiff).coerceAtLeast(0L)) * 100L / totalDiff).toInt().coerceIn(0, 100)
    }

    private fun readGpuUsagePercent(): Int? {
        val raw = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpubusy") ?: return null
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            return null
        }

        val busy = parts[0].toLongOrNull() ?: return null
        val total = parts[1].toLongOrNull() ?: return null
        if (total <= 0L) {
            return null
        }

        return ((busy * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun readUsedRamText(): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "—"
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0L)
        val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (usedGb >= 1.0) {
            String.format(Locale.US, "%.1fGB", usedGb)
        } else {
            val usedMb = usedBytes / (1024L * 1024L)
            "${usedMb}MB"
        }
    }

    private fun readBatteryPercent(): Int? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val value = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return value.takeIf { it in 0..100 }
    }

    private fun readPowerWatts(): Double? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val currentMicroAmps = abs(batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        if (currentMicroAmps <= 0L) {
            return null
        }

        val statusIntent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val voltageMilliVolts = statusIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        if (voltageMilliVolts <= 0) {
            return null
        }

        return (currentMicroAmps.toDouble() * voltageMilliVolts.toDouble()) / 1_000_000_000.0
    }

    private fun readCpuTempC(): Int? {
        return readTemperatureC(
            discoverThermalZoneTempPaths { type ->
                type.contains("cpu") || type.contains("tsens")
            },
        )
    }

    private fun readGpuTempC(): Int? {
        return readTemperatureC(
            listOf("/sys/class/kgsl/kgsl-3d0/temp") +
                discoverThermalZoneTempPaths { type ->
                    type.contains("gpu") || type.contains("kgsl")
                },
        )
    }

    private fun discoverThermalZoneTempPaths(matches: (String) -> Boolean): List<String> {
        val thermalDir = File("/sys/class/thermal")
        val zones = thermalDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("thermal_zone")
        } ?: return emptyList()

        return zones.mapNotNull { zone ->
            val type = readFirstLine(File(zone, "type").path)?.trim()?.lowercase(Locale.US) ?: return@mapNotNull null
            if (!matches(type)) {
                return@mapNotNull null
            }
            File(zone, "temp").path
        }
    }

    private fun readTemperatureC(paths: List<String>): Int? {
        for (path in paths.distinct()) {
            val raw = readFirstLine(path)?.trim()?.toIntOrNull() ?: continue
            val celsius = if (raw > 1000) raw / 1000 else raw
            if (celsius in 1..150) {
                return celsius
            }
        }
        return null
    }

    private fun readFirstLine(path: String): String? {
        return try {
            File(path).bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private data class HudSnapshot(
        val fps: String,
        val cpu: String?,
        val gpu: String?,
        val ram: String,
        val battery: String?,
        val power: String?,
        val cpuTemp: String?,
        val gpuTemp: String?,
    )

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}
