package app.gamenative.ui.widget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PerformanceHudSize(val prefValue: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    ;

    companion object {
        fun fromPrefValue(value: String?): PerformanceHudSize {
            return values().firstOrNull { it.prefValue == value } ?: MEDIUM
        }
    }
}

/**
 * Controls which metrics are rendered inside the floating performance HUD.
 */
data class PerformanceHudConfig(
    val showFrameRate: Boolean = true,
    val showCpuUsage: Boolean = true,
    val showGpuUsage: Boolean = true,
    val showRamUsage: Boolean = true,
    val showBatteryLevel: Boolean = true,
    val showPowerDraw: Boolean = true,
    val showBatteryRuntime: Boolean = false,
    val showClockTime: Boolean = false,
    val showCpuTemperature: Boolean = true,
    val showGpuTemperature: Boolean = true,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val size: PerformanceHudSize = PerformanceHudSize.MEDIUM,
) {
    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.72f
    }
}

/**
 * Lightweight floating HUD shown above the in-game surface.
 *
 * Metric collection runs off the main thread and rows are hidden automatically
 * when a given stat is not available on the current device.
 */
class PerformanceHudView(
    context: Context,
    private val fpsProvider: () -> Float,
    initialConfig: PerformanceHudConfig = PerformanceHudConfig(),
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var config = initialConfig
    private var lastSnapshot: HudSnapshot? = null
    private var attachedRows: List<TextView> = emptyList()
    private var appearance = appearanceFor(initialConfig.size)
    private var smoothedBatteryRuntimeHours: Double? = null

    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    private val fpsText = createRow(0xFF4CAF50.toInt())
    private val cpuText = createRow(0xFF42A5F5.toInt())
    private val gpuText = createRow(0xFFEF5350.toInt())
    private val ramText = createRow(0xFFFFEE58.toInt())
    private val batteryText = createRow(0xFFFFFFFF.toInt())
    private val powerText = createRow(0xFF4DD0E1.toInt())
    private val runtimeText = createRow(0xFFA5D6A7.toInt())
    private val clockText = createRow(0xFFFFCC80.toInt())
    private val cpuTempText = createRow(0xFFBDBDBD.toInt())
    private val gpuTempText = createRow(0xFFBDBDBD.toInt())

    private val allRows = listOf(
        fpsText,
        cpuText,
        gpuText,
        ramText,
        batteryText,
        powerText,
        runtimeText,
        clockText,
        cpuTempText,
        gpuTempText,
    )

    private val contentContainer = GridLayout(context).apply {
        orientation = GridLayout.HORIZONTAL
        columnCount = 2
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        background = backgroundDrawable
    }

    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null

    init {
        addView(contentContainer)
        applyAppearance()
    }

    fun setConfig(config: PerformanceHudConfig) {
        if (this.config == config) {
            return
        }

        this.config = config
        applyAppearance()
        lastSnapshot?.let(::renderSnapshot) ?: refreshVisibleRows()
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

    private fun applyAppearance() {
        appearance = appearanceFor(config.size)
        val opacity = config.backgroundOpacity.coerceIn(MIN_BACKGROUND_OPACITY, MAX_BACKGROUND_OPACITY)

        contentContainer.setPadding(
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
        )

        backgroundDrawable.cornerRadius = appearance.cornerRadiusDp.dp.toFloat()
        backgroundDrawable.setColor(
            Color.argb(
                (opacity * 255f).roundToInt(),
                0,
                0,
                0,
            ),
        )
        backgroundDrawable.setStroke(
            appearance.strokeWidthDp.dp.coerceAtLeast(1),
            Color.argb(
                (opacity * 96f).roundToInt().coerceAtLeast(24),
                255,
                255,
                255,
            ),
        )

        allRows.forEach { row ->
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp)
            row.setPadding(0, appearance.rowVerticalPaddingDp.dp, 0, appearance.rowVerticalPaddingDp.dp)
        }

        attachedRows = emptyList()
        requestLayout()
    }

    private fun collectSnapshot(currentFps: Float): HudSnapshot {
        val batterySnapshot = collectBatterySnapshot()
        return HudSnapshot(
            fps = String.format(Locale.US, "FPS %.1f", currentFps),
            cpu = readCpuUsagePercent()?.let { "CPU $it%" },
            gpu = readGpuUsagePercent()?.let { "GPU $it%" },
            ram = "RAM ${readUsedRamText()}",
            battery = batterySnapshot.percent?.let { "BAT $it%" },
            power = batterySnapshot.powerWatts?.let { watts ->
                String.format(Locale.US, "PWR %.1fW", watts)
            },
            runtime = batterySnapshot.runtimeText,
            clock = readClockText(),
            cpuTemp = readCpuTempC()?.let { "CPU TEMP ${it}°C" },
            gpuTemp = readGpuTempC()?.let { "GPU TEMP ${it}°C" },
        )
    }

    private fun collectBatterySnapshot(): BatterySnapshot {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return BatterySnapshot()

        val percent = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

        val statusIntent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(percent = percent)

        val status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val currentMicroAmps = abs(batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val chargeCounterMicroAmpHours = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val voltageMilliVolts = statusIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val powerWatts = if (currentMicroAmps > 0L && voltageMilliVolts > 0) {
            (currentMicroAmps.toDouble() * voltageMilliVolts.toDouble()) / 1_000_000_000.0
        } else {
            null
        }

        val runtimeText = when {
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL -> {
                smoothedBatteryRuntimeHours = null
                "LEFT CHG"
            }
            currentMicroAmps <= 0L || chargeCounterMicroAmpHours <= 0L -> null
            else -> {
                val rawHours = chargeCounterMicroAmpHours.toDouble() / currentMicroAmps.toDouble()
                if (!rawHours.isFinite() || rawHours <= 0.0 || rawHours > MAX_RUNTIME_HOURS) {
                    null
                } else {
                    val smoothedHours = smoothedBatteryRuntimeHours
                        ?.let { (it * RUNTIME_SMOOTHING_OLD_WEIGHT) + (rawHours * RUNTIME_SMOOTHING_NEW_WEIGHT) }
                        ?: rawHours
                    smoothedBatteryRuntimeHours = smoothedHours
                    "LEFT ${formatRuntime(smoothedHours)}"
                }
            }
        }

        return BatterySnapshot(
            percent = percent,
            powerWatts = powerWatts,
            runtimeText = runtimeText,
        )
    }

    private fun readClockText(): String {
        return "TIME ${DateFormat.getTimeFormat(context).format(Date())}"
    }

    private fun renderSnapshot(snapshot: HudSnapshot) {
        lastSnapshot = snapshot

        fpsText.text = snapshot.fps
        cpuText.text = snapshot.cpu.orEmpty()
        gpuText.text = snapshot.gpu.orEmpty()
        ramText.text = snapshot.ram
        batteryText.text = snapshot.battery.orEmpty()
        powerText.text = snapshot.power.orEmpty()
        runtimeText.text = snapshot.runtime.orEmpty()
        clockText.text = snapshot.clock.orEmpty()
        cpuTempText.text = snapshot.cpuTemp.orEmpty()
        gpuTempText.text = snapshot.gpuTemp.orEmpty()

        refreshVisibleRows()
    }

    private fun refreshVisibleRows() {
        val visibleRows = buildList {
            addRowIfVisible(fpsText, config.showFrameRate)
            addRowIfVisible(cpuText, config.showCpuUsage)
            addRowIfVisible(gpuText, config.showGpuUsage)
            addRowIfVisible(ramText, config.showRamUsage)
            addRowIfVisible(batteryText, config.showBatteryLevel)
            addRowIfVisible(powerText, config.showPowerDraw)
            addRowIfVisible(runtimeText, config.showBatteryRuntime)
            addRowIfVisible(clockText, config.showClockTime)
            addRowIfVisible(cpuTempText, config.showCpuTemperature)
            addRowIfVisible(gpuTempText, config.showGpuTemperature)
        }

        val columnCount = if (visibleRows.size <= 1) 1 else 2
        val shouldRebuildLayout =
            contentContainer.columnCount != columnCount ||
                visibleRows.size != attachedRows.size ||
                visibleRows.zip(attachedRows).any { (current, previous) -> current !== previous }

        if (shouldRebuildLayout) {
            contentContainer.removeAllViews()
            contentContainer.columnCount = columnCount
            visibleRows.forEachIndexed { index, row ->
                contentContainer.addView(row, createRowLayoutParams(index, columnCount))
            }
            attachedRows = visibleRows
        }

        visibility = if (visibleRows.isEmpty()) GONE else VISIBLE
    }

    private fun MutableList<TextView>.addRowIfVisible(view: TextView, enabled: Boolean) {
        if (enabled && view.text.isNotBlank()) {
            add(view)
        }
    }

    private fun createRowLayoutParams(index: Int, columnCount: Int): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
            val isFirstColumn = columnCount > 1 && index % columnCount == 0
            setMargins(
                0,
                appearance.rowSpacingDp.dp,
                if (isFirstColumn) appearance.columnSpacingDp.dp else 0,
                appearance.rowSpacingDp.dp,
            )
        }
    }

    private fun createRow(color: Int): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
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

    private fun formatRuntime(hours: Double): String {
        val totalMinutes = (hours * 60.0).roundToInt().coerceAtLeast(1)
        val wholeHours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            wholeHours > 0 && minutes > 0 -> "${wholeHours}h ${minutes}m"
            wholeHours > 0 -> "${wholeHours}h"
            else -> "${minutes}m"
        }
    }

    private fun readFirstLine(path: String): String? {
        return try {
            File(path).bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    private fun appearanceFor(size: PerformanceHudSize): HudAppearance {
        return when (size) {
            PerformanceHudSize.SMALL -> HudAppearance(
                textSizeSp = 10f,
                containerHorizontalPaddingDp = 8,
                containerVerticalPaddingDp = 6,
                rowVerticalPaddingDp = 1,
                rowSpacingDp = 1,
                columnSpacingDp = 8,
                cornerRadiusDp = 8,
                strokeWidthDp = 1,
            )
            PerformanceHudSize.MEDIUM -> HudAppearance(
                textSizeSp = 11f,
                containerHorizontalPaddingDp = 10,
                containerVerticalPaddingDp = 8,
                rowVerticalPaddingDp = 2,
                rowSpacingDp = 2,
                columnSpacingDp = 12,
                cornerRadiusDp = 10,
                strokeWidthDp = 1,
            )
            PerformanceHudSize.LARGE -> HudAppearance(
                textSizeSp = 13f,
                containerHorizontalPaddingDp = 12,
                containerVerticalPaddingDp = 10,
                rowVerticalPaddingDp = 3,
                rowSpacingDp = 3,
                columnSpacingDp = 14,
                cornerRadiusDp = 12,
                strokeWidthDp = 1,
            )
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private data class BatterySnapshot(
        val percent: Int? = null,
        val powerWatts: Double? = null,
        val runtimeText: String? = null,
    )

    private data class HudSnapshot(
        val fps: String,
        val cpu: String?,
        val gpu: String?,
        val ram: String,
        val battery: String?,
        val power: String?,
        val runtime: String?,
        val clock: String,
        val cpuTemp: String?,
        val gpuTemp: String?,
    )

    private data class HudAppearance(
        val textSizeSp: Float,
        val containerHorizontalPaddingDp: Int,
        val containerVerticalPaddingDp: Int,
        val rowVerticalPaddingDp: Int,
        val rowSpacingDp: Int,
        val columnSpacingDp: Int,
        val cornerRadiusDp: Int,
        val strokeWidthDp: Int,
    )

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MIN_BACKGROUND_OPACITY = 0.15f
        const val MAX_BACKGROUND_OPACITY = 1.0f
        const val MAX_RUNTIME_HOURS = 72.0
        const val RUNTIME_SMOOTHING_OLD_WEIGHT = 0.65
        const val RUNTIME_SMOOTHING_NEW_WEIGHT = 0.35
    }
}
