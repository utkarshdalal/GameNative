package app.gamenative.ui.widget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
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
    initialCompactMode: Boolean = false,
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var isCompactMode = initialCompactMode

    private val stackedContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val compactContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val fpsMetric = createMetricViews(0xFF4CAF50.toInt())
    private val cpuMetric = createMetricViews(0xFF42A5F5.toInt())
    private val gpuMetric = createMetricViews(0xFFEF5350.toInt())
    private val ramMetric = createMetricViews(0xFFFFEE58.toInt())
    private val batteryMetric = createMetricViews(0xFFFFFFFF.toInt())
    private val powerMetric = createMetricViews(0xFF4DD0E1.toInt())
    private val cpuTempMetric = createMetricViews(0xFFBDBDBD.toInt())
    private val gpuTempMetric = createMetricViews(0xFFBDBDBD.toInt())

    private val metrics = listOf(
        fpsMetric,
        cpuMetric,
        gpuMetric,
        ramMetric,
        batteryMetric,
        powerMetric,
        cpuTempMetric,
        gpuTempMetric,
    )

    private val fpsGraphStacked = FpsGraphView(context).apply {
        layoutParams = LinearLayout.LayoutParams(72.dp, 16.dp).apply {
            topMargin = 1.dp
            bottomMargin = 3.dp
        }
    }

    private val fpsGraphCompact = FpsGraphView(context).apply {
        layoutParams = LinearLayout.LayoutParams(44.dp, 12.dp).apply {
            marginStart = 6.dp
            marginEnd = 2.dp
        }
    }

    private val compactSeparators = mutableListOf<TextView>()

    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10.dp.toFloat()
            setColor(0xB8000000.toInt())
            setStroke(1.dp, 0x44FFFFFF)
        }
        setPadding(10.dp, 8.dp, 10.dp, 8.dp)

        stackedContainer.addView(fpsMetric.stacked)
        stackedContainer.addView(fpsGraphStacked)
        metrics.drop(1).forEach { metric ->
            stackedContainer.addView(metric.stacked)
        }

        compactContainer.addView(fpsMetric.compact)
        compactContainer.addView(fpsGraphCompact)
        metrics.forEachIndexed { index, metric ->
            if (index == 0) {
                if (index < metrics.lastIndex) {
                    createSeparator().also {
                        compactSeparators += it
                        compactContainer.addView(it)
                    }
                }
                return@forEachIndexed
            }

            compactContainer.addView(metric.compact)
            if (index < metrics.lastIndex) {
                createSeparator().also {
                    compactSeparators += it
                    compactContainer.addView(it)
                }
            }
        }

        addView(stackedContainer)
        addView(compactContainer)
        applyLayoutMode()
    }

    fun isCompactMode(): Boolean = isCompactMode

    fun setCompactMode(compactMode: Boolean) {
        if (isCompactMode == compactMode) {
            return
        }

        isCompactMode = compactMode
        applyLayoutMode()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdates()
    }

    override fun onDetachedFromWindow() {
        stopUpdates()
        super.onDetachedFromWindow()
    }

    private fun applyLayoutMode() {
        stackedContainer.visibility = if (isCompactMode) GONE else VISIBLE
        compactContainer.visibility = if (isCompactMode) VISIBLE else GONE
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
            fpsValue = currentFps,
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
        updateMetric(fpsMetric, snapshot.fps)
        fpsGraphStacked.addSample(snapshot.fpsValue)
        fpsGraphCompact.addSample(snapshot.fpsValue)
        updateMetric(cpuMetric, snapshot.cpu)
        updateMetric(gpuMetric, snapshot.gpu)
        updateMetric(ramMetric, snapshot.ram)
        updateMetric(batteryMetric, snapshot.battery)
        updateMetric(powerMetric, snapshot.power)
        updateMetric(cpuTempMetric, snapshot.cpuTemp)
        updateMetric(gpuTempMetric, snapshot.gpuTemp)
        updateCompactSeparators()
    }

    private fun updateMetric(metric: MetricViews, text: String?) {
        updateText(metric.stacked, text)
        updateText(metric.compact, text)
    }

    private fun updateText(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    private fun updateCompactSeparators() {
        val compactMetricTail = listOf(
            cpuMetric,
            gpuMetric,
            ramMetric,
            batteryMetric,
            powerMetric,
            cpuTempMetric,
            gpuTempMetric,
        )

        compactSeparators.firstOrNull()?.visibility =
            if (fpsMetric.compact.visibility == VISIBLE && compactMetricTail.any { it.compact.visibility == VISIBLE }) {
                VISIBLE
            } else {
                GONE
            }

        var hasVisibleMetricBefore = compactMetricTail.firstOrNull()?.compact?.visibility == VISIBLE
        compactMetricTail.drop(1).forEachIndexed { index, metric ->
            compactSeparators[index + 1].visibility =
                if (metric.compact.visibility == VISIBLE && hasVisibleMetricBefore) VISIBLE else GONE
            if (metric.compact.visibility == VISIBLE) {
                hasVisibleMetricBefore = true
            }
        }
    }

    private fun createMetricViews(color: Int): MetricViews {
        return MetricViews(
            stacked = createStackedTextView(color),
            compact = createCompactTextView(color),
        )
    }

    private fun createStackedTextView(color: Int): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
            setPadding(0, 2.dp, 0, 2.dp)
        }
    }

    private fun createCompactTextView(color: Int): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
        }
    }

    private fun createSeparator(): TextView {
        return TextView(context).apply {
            text = " | "
            setTextColor(0x88FFFFFF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private data class MetricViews(
        val stacked: TextView,
        val compact: TextView,
    )

    private data class HudSnapshot(
        val fpsValue: Float,
        val fps: String,
        val cpu: String?,
        val gpu: String?,
        val ram: String,
        val battery: String?,
        val power: String?,
        val cpuTemp: String?,
        val gpuTemp: String?,
    )

    private inner class FpsGraphView(context: Context) : View(context) {
        private val samples = ArrayDeque<Float>()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x664CAF50
            style = Paint.Style.STROKE
            strokeWidth = 3.dp.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7CFF6B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun addSample(fps: Float) {
            if (samples.size >= FPS_GRAPH_SAMPLE_COUNT) {
                samples.removeFirst()
            }
            samples.addLast(fps.coerceAtLeast(0f))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (samples.size < 2 || width <= 0 || height <= 0) {
                return
            }

            val chartWidth = width.toFloat()
            val chartHeight = height.toFloat()
            val values = samples.toList()
            val maxValue = max(FPS_GRAPH_MIN_SCALE, (values.maxOrNull() ?: FPS_GRAPH_MIN_SCALE) * 1.05f)
            val xStep = if (values.size > 1) chartWidth / (values.size - 1) else chartWidth

            path.reset()
            values.forEachIndexed { index, value ->
                val x = index * xStep
                val normalized = (value / maxValue).coerceIn(0f, 1f)
                val y = chartHeight - (normalized * chartHeight)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val FPS_GRAPH_SAMPLE_COUNT = 30
        const val FPS_GRAPH_MIN_SCALE = 60f
    }
}
