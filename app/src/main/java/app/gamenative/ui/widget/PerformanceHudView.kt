package app.gamenative.ui.widget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.utils.DateTimeUtils.formatRuntimeHours
import java.io.File
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
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
    initialConfig: PerformanceHudConfig = PerformanceHudConfig(),
    initialCompactMode: Boolean = false,
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var config = initialConfig
    private var isCompactMode = initialCompactMode
    private var lastSnapshot: HudSnapshot? = null
    private var attachedMetricSignature: List<MetricSignature> = emptyList()
    private var appearance = appearanceFor(initialConfig.size)
    private var smoothedBatteryRuntimeHours: Double? = null

    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    private val stackedContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val compactContainer = WrapLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val fpsMetric = createMetricViews(
        id = MetricId.FPS,
        textColor = 0xFF4CAF50.toInt(),
        graphColor = 0xFF7CFF6B.toInt(),
        graphScaleMode = GraphScaleMode.FPS_DYNAMIC,
    )
    private val cpuMetric = createMetricViews(
        id = MetricId.CPU,
        textColor = 0xFF42A5F5.toInt(),
        graphColor = 0xFF42A5F5.toInt(),
        graphScaleMode = GraphScaleMode.PERCENT_100,
    )
    private val gpuMetric = createMetricViews(
        id = MetricId.GPU,
        textColor = 0xFFEF5350.toInt(),
        graphColor = 0xFFEF5350.toInt(),
        graphScaleMode = GraphScaleMode.PERCENT_100,
    )
    private val ramMetric = createMetricViews(MetricId.RAM, 0xFFFFEE58.toInt())
    private val batteryMetric = createMetricViews(MetricId.BATTERY, 0xFFFFFFFF.toInt())
    private val powerMetric = createMetricViews(MetricId.POWER, 0xFF4DD0E1.toInt())
    private val runtimeMetric = createMetricViews(MetricId.RUNTIME, 0xFFA5D6A7.toInt())
    private val clockMetric = createMetricViews(MetricId.CLOCK, 0xFFFFCC80.toInt())
    private val cpuTempMetric = createMetricViews(MetricId.CPU_TEMP, 0xFFBDBDBD.toInt())
    private val gpuTempMetric = createMetricViews(MetricId.GPU_TEMP, 0xFFBDBDBD.toInt())
    private val batteryTempMetric = createMetricViews(MetricId.BATTERY_TEMP, 0xFFBDBDBD.toInt())

    private val allMetrics = listOf(
        fpsMetric,
        cpuMetric,
        gpuMetric,
        ramMetric,
        batteryMetric,
        powerMetric,
        runtimeMetric,
        clockMetric,
        cpuTempMetric,
        gpuTempMetric,
        batteryTempMetric,
    )

    private val allTextRows = allMetrics.flatMap { listOf(it.stackedText, it.compactText) }
    private val allGraphs = allMetrics.flatMap { listOfNotNull(it.stackedGraph, it.compactGraph) }

    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null

    init {
        background = backgroundDrawable
        addView(stackedContainer)
        addView(compactContainer)
        applyAppearance()
        applyLayoutMode()
        refreshVisibleMetrics()
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

    fun setConfig(config: PerformanceHudConfig) {
        if (this.config == config) {
            return
        }

        this.config = config
        applyAppearance()
        lastSnapshot?.let(::applySnapshotText) ?: refreshVisibleMetrics()
        refreshVisibleMetrics()
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
                val rawFps = fpsProvider()
                val currentFps = if (rawFps.isFinite()) rawFps.coerceAtLeast(0f) else 0f
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

        setPadding(
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
                (opacity * 96f).roundToInt(),
                255,
                255,
                255,
            ),
        )

        compactContainer.horizontalSpacing = appearance.columnSpacingDp.dp
        compactContainer.verticalSpacing = appearance.rowSpacingDp.dp

        allTextRows.forEach { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp)
            textView.maxLines = 1
            textView.ellipsize = TextUtils.TruncateAt.END
            textView.setShadowLayer(
                if (config.showTextOutline) {
                    (textView.textSize * HUD_TEXT_SHADOW_RADIUS_RATIO)
                        .coerceIn(MIN_HUD_TEXT_SHADOW_RADIUS_PX, MAX_HUD_TEXT_SHADOW_RADIUS_PX)
                } else {
                    0f
                },
                0f,
                0f,
                HUD_TEXT_SHADOW_COLOR,
            )
        }

        allMetrics.forEach(::applyMetricAppearance)
        allGraphs.forEach { it.applyAppearance(appearance) }

        attachedMetricSignature = emptyList()
        requestLayout()
    }

    private fun applyMetricAppearance(metric: MetricViews) {
        val textColor = blendMetricColor(metric.baseTextColor)
        metric.stackedText.setTextColor(textColor)
        metric.compactText.setTextColor(textColor)
        metric.stackedText.setPadding(0, appearance.rowVerticalPaddingDp.dp, 0, 0)
        metric.compactText.setPadding(0, 0, 0, 0)
        metric.stackedGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor))
        metric.compactGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor))

        (metric.stackedGraph?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = appearance.stackedGraphWidthDp.dp
            params.height = appearance.stackedGraphHeightDp.dp
            params.topMargin = appearance.stackedGraphTopMarginDp.dp
            params.bottomMargin = appearance.stackedGraphBottomMarginDp.dp
            metric.stackedGraph.layoutParams = params
        }

        (metric.compactGraph?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = appearance.compactGraphWidthDp.dp
            params.height = appearance.compactGraphHeightDp.dp
            params.marginStart = appearance.compactGraphStartMarginDp.dp
            metric.compactGraph.layoutParams = params
        }
    }

    private fun collectSnapshot(currentFps: Float): HudSnapshot {
        val cpuPercent = readCpuUsagePercent()
        val gpuPercent = readGpuUsagePercent()
        val batterySnapshot = collectBatterySnapshot()
        return HudSnapshot(
            fpsValue = currentFps,
            cpuValue = cpuPercent?.toFloat(),
            gpuValue = gpuPercent?.toFloat(),
            fps = String.format(Locale.US, "FPS %.1f", currentFps),
            cpu = cpuPercent?.let { "CPU $it%" },
            gpu = gpuPercent?.let { "GPU $it%" },
            ram = "RAM ${readUsedRamText()}",
            battery = batterySnapshot.percent?.let { "BAT $it%" },
            power = batterySnapshot.powerWatts?.let { watts ->
                String.format(Locale.US, "PWR %.1fW", watts)
            },
            runtime = batterySnapshot.runtimeText,
            batteryTemp = batterySnapshot.temperatureC?.let { "BAT TEMP ${it}°C" },
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
        val temperatureC = statusIntent
            .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            .takeIf { it > 0 }
            ?.let { (it / 10f).roundToInt() }

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
                    "LEFT ${formatRuntimeHours(smoothedHours)}"
                }
            }
        }

        return BatterySnapshot(
            percent = percent,
            powerWatts = powerWatts,
            runtimeText = runtimeText,
            temperatureC = temperatureC,
        )
    }

    private fun readClockText(): String {
        return "TIME ${DateFormat.getTimeFormat(context).format(Date())}"
    }

    private fun renderSnapshot(snapshot: HudSnapshot) {
        lastSnapshot = snapshot
        recordGraphSamples(snapshot)
        applySnapshotText(snapshot)
        refreshVisibleMetrics()
    }

    private fun recordGraphSamples(snapshot: HudSnapshot) {
        fpsMetric.stackedGraph?.addSample(snapshot.fpsValue)
        fpsMetric.compactGraph?.addSample(snapshot.fpsValue)
        cpuMetric.stackedGraph?.addSample(snapshot.cpuValue)
        cpuMetric.compactGraph?.addSample(snapshot.cpuValue)
        gpuMetric.stackedGraph?.addSample(snapshot.gpuValue)
        gpuMetric.compactGraph?.addSample(snapshot.gpuValue)
    }

    private fun applySnapshotText(snapshot: HudSnapshot) {
        updateMetricText(fpsMetric, snapshot.fps)
        updateMetricText(cpuMetric, snapshot.cpu)
        updateMetricText(gpuMetric, snapshot.gpu)
        updateMetricText(ramMetric, snapshot.ram)
        updateMetricText(batteryMetric, snapshot.battery)
        updateMetricText(powerMetric, snapshot.power)
        updateMetricText(runtimeMetric, snapshot.runtime)
        updateMetricText(batteryTempMetric, snapshot.batteryTemp)
        updateMetricText(clockMetric, snapshot.clock)
        updateMetricText(cpuTempMetric, snapshot.cpuTemp)
        updateMetricText(gpuTempMetric, snapshot.gpuTemp)
    }

    private fun updateMetricText(metric: MetricViews, text: String?) {
        val safeText = text.orEmpty()
        metric.stackedText.text = safeText
        metric.compactText.text = safeText
    }

    private fun refreshVisibleMetrics() {
        val visibleMetrics = buildList {
            addMetricIfVisible(fpsMetric, config.showFrameRate, config.showFrameRateGraph)
            addMetricIfVisible(cpuMetric, config.showCpuUsage, config.showCpuUsageGraph)
            addMetricIfVisible(gpuMetric, config.showGpuUsage, config.showGpuUsageGraph)
            addMetricIfVisible(ramMetric, config.showRamUsage)
            addMetricIfVisible(batteryMetric, config.showBatteryLevel)
            addMetricIfVisible(powerMetric, config.showPowerDraw)
            addMetricIfVisible(runtimeMetric, config.showBatteryRuntime)
            addMetricIfVisible(clockMetric, config.showClockTime)
            addMetricIfVisible(cpuTempMetric, config.showCpuTemperature)
            addMetricIfVisible(gpuTempMetric, config.showGpuTemperature)
            addMetricIfVisible(batteryTempMetric, config.showBatteryTemperature)
        }

        val signatures = visibleMetrics.map {
            MetricSignature(
                id = it.metric.id,
                showText = it.showText,
                showGraph = it.showGraph,
            )
        }
        if (signatures != attachedMetricSignature) {
            rebuildVisibleMetrics(visibleMetrics)
            attachedMetricSignature = signatures
        }

        visibility = if (visibleMetrics.isEmpty()) GONE else VISIBLE
    }

    private fun MutableList<VisibleMetric>.addMetricIfVisible(
        metric: MetricViews,
        enabled: Boolean,
        showGraph: Boolean = false,
    ) {
        val hasText = metric.stackedText.text.isNotBlank()
        val shouldShowText = enabled && hasText
        val shouldShowGraph = showGraph && metric.supportsGraph

        metric.stackedText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.compactText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.stackedGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE
        metric.compactGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE

        if (shouldShowText || shouldShowGraph) {
            add(
                VisibleMetric(
                    metric = metric,
                    showText = shouldShowText,
                    showGraph = shouldShowGraph,
                ),
            )
        }
    }

    private fun rebuildVisibleMetrics(visibleMetrics: List<VisibleMetric>) {
        stackedContainer.removeAllViews()
        compactContainer.removeAllViews()

        visibleMetrics.forEachIndexed { index, visibleMetric ->
            stackedContainer.addView(
                visibleMetric.metric.stackedContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < visibleMetrics.lastIndex) {
                        bottomMargin = appearance.rowSpacingDp.dp
                    }
                },
            )
            compactContainer.addView(visibleMetric.metric.compactContainer)
        }
    }

    private fun createMetricViews(
        id: MetricId,
        textColor: Int,
        graphColor: Int? = null,
        graphScaleMode: GraphScaleMode? = null,
    ): MetricViews {
        val stackedText = createTextView(textColor)
        val compactText = createTextView(textColor)
        val stackedGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else {
            null
        }
        val compactGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else {
            null
        }

        val stackedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                stackedText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            stackedGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(
                        appearance.stackedGraphWidthDp.dp,
                        appearance.stackedGraphHeightDp.dp,
                    ),
                )
                it.visibility = GONE
            }
        }

        val compactContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                compactText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            compactGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(
                        appearance.compactGraphWidthDp.dp,
                        appearance.compactGraphHeightDp.dp,
                    ),
                )
                it.visibility = GONE
            }
        }

        return MetricViews(
            id = id,
            supportsGraph = stackedGraph != null && compactGraph != null,
            baseTextColor = textColor,
            baseGraphColor = graphColor,
            stackedText = stackedText,
            compactText = compactText,
            stackedContainer = stackedContainer,
            compactContainer = compactContainer,
            stackedGraph = stackedGraph,
            compactGraph = compactGraph,
        )
    }

    private fun blendMetricColor(color: Int): Int {
        val intensity = config.colorIntensity.coerceIn(0f, 1f)
        if (intensity >= 0.999f) return color

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= intensity
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun createTextView(color: Int): TextView {
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
                rowSpacingDp = 4,
                columnSpacingDp = 8,
                cornerRadiusDp = 8,
                strokeWidthDp = 1,
                stackedGraphWidthDp = 60,
                stackedGraphHeightDp = 12,
                compactGraphWidthDp = 38,
                compactGraphHeightDp = 10,
                stackedGraphTopMarginDp = 1,
                stackedGraphBottomMarginDp = 2,
                compactGraphStartMarginDp = 4,
            )
            PerformanceHudSize.MEDIUM -> HudAppearance(
                textSizeSp = 11f,
                containerHorizontalPaddingDp = 10,
                containerVerticalPaddingDp = 8,
                rowVerticalPaddingDp = 2,
                rowSpacingDp = 6,
                columnSpacingDp = 12,
                cornerRadiusDp = 10,
                strokeWidthDp = 1,
                stackedGraphWidthDp = 72,
                stackedGraphHeightDp = 16,
                compactGraphWidthDp = 44,
                compactGraphHeightDp = 12,
                stackedGraphTopMarginDp = 1,
                stackedGraphBottomMarginDp = 3,
                compactGraphStartMarginDp = 6,
            )
            PerformanceHudSize.LARGE -> HudAppearance(
                textSizeSp = 13f,
                containerHorizontalPaddingDp = 12,
                containerVerticalPaddingDp = 10,
                rowVerticalPaddingDp = 3,
                rowSpacingDp = 8,
                columnSpacingDp = 14,
                cornerRadiusDp = 12,
                strokeWidthDp = 1,
                stackedGraphWidthDp = 88,
                stackedGraphHeightDp = 20,
                compactGraphWidthDp = 56,
                compactGraphHeightDp = 16,
                stackedGraphTopMarginDp = 2,
                stackedGraphBottomMarginDp = 4,
                compactGraphStartMarginDp = 8,
            )
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private data class MetricViews(
        val id: MetricId,
        val supportsGraph: Boolean,
        val baseTextColor: Int,
        val baseGraphColor: Int?,
        val stackedText: TextView,
        val compactText: TextView,
        val stackedContainer: LinearLayout,
        val compactContainer: LinearLayout,
        val stackedGraph: MetricGraphView? = null,
        val compactGraph: MetricGraphView? = null,
    )

    private data class VisibleMetric(
        val metric: MetricViews,
        val showText: Boolean,
        val showGraph: Boolean,
    )

    private inner class MetricGraphView(
        context: Context,
        lineColor: Int,
        private val scaleMode: GraphScaleMode,
    ) : View(context) {
        private val samples = ArrayDeque<Float>()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(102, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
            style = Paint.Style.STROKE
            strokeWidth = 2.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setLineColor(color: Int) {
            linePaint.color = color
            glowPaint.color = Color.argb(102, Color.red(color), Color.green(color), Color.blue(color))
            invalidate()
        }

        fun applyAppearance(appearance: HudAppearance) {
            linePaint.strokeWidth = when (appearance.textSizeSp) {
                in 0f..10.5f -> 1.2f.dpF
                in 10.5f..12f -> 1.5f.dpF
                else -> 1.8f.dpF
            }
            glowPaint.strokeWidth = linePaint.strokeWidth * 2f
            invalidate()
        }

        fun addSample(value: Float?) {
            val sample = value
                ?.takeIf { it.isFinite() && it >= 0f }
                ?: Float.NaN
            if (samples.size >= GRAPH_SAMPLE_COUNT) {
                samples.removeFirst()
            }
            samples.addLast(sample)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || samples.isEmpty()) {
                return
            }

            val values = samples.toList()
            val validValues = values.filter { it.isFinite() }
            if (validValues.size < 2) {
                return
            }

            val chartWidth = width.toFloat()
            val chartHeight = height.toFloat()
            val maxValue = when (scaleMode) {
                GraphScaleMode.FPS_DYNAMIC -> max(GRAPH_FPS_MIN_SCALE, (validValues.maxOrNull() ?: GRAPH_FPS_MIN_SCALE) * 1.05f)
                GraphScaleMode.PERCENT_100 -> 100f
            }
            val xStep = if (values.size > 1) chartWidth / (values.size - 1) else chartWidth

            path.reset()
            var hasActiveSegment = false
            values.forEachIndexed { index, value ->
                if (!value.isFinite()) {
                    hasActiveSegment = false
                    return@forEachIndexed
                }

                val x = index * xStep
                val normalized = (value / maxValue).coerceIn(0f, 1f)
                val y = chartHeight - (normalized * chartHeight)
                if (!hasActiveSegment) {
                    path.moveTo(x, y)
                    hasActiveSegment = true
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private inner class WrapLayout(context: Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
            set(value) {
                field = value
                requestLayout()
            }

        var verticalSpacing: Int = 0
            set(value) {
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val maxContentWidth = when (widthMode) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            }

            var lineWidth = 0
            var lineHeight = 0
            var maxLineWidthUsed = 0
            var totalHeight = paddingTop + paddingBottom
            var visibleChildCount = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue

                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedWidth = if (lineWidth == 0) {
                    childWidth
                } else {
                    lineWidth + horizontalSpacing + childWidth
                }

                if (lineWidth > 0 && proposedWidth > maxContentWidth) {
                    maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                    totalHeight += lineHeight + verticalSpacing
                    lineWidth = childWidth
                    lineHeight = childHeight
                } else {
                    lineWidth = proposedWidth
                    lineHeight = max(lineHeight, childHeight)
                }
                visibleChildCount++
            }

            if (visibleChildCount > 0) {
                maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                totalHeight += lineHeight
            }

            val measuredWidth = resolveSize(maxLineWidthUsed + paddingLeft + paddingRight, widthMeasureSpec)
            val measuredHeight = resolveSize(totalHeight, heightMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val maxContentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue

                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedRight = if (x == paddingLeft) {
                    x + childWidth
                } else {
                    x + horizontalSpacing + childWidth
                }

                if (x != paddingLeft && proposedRight - paddingLeft > maxContentWidth) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }

                if (x != paddingLeft) {
                    x += horizontalSpacing
                }

                child.layout(
                    x,
                    y,
                    x + childWidth,
                    y + childHeight,
                )
                x += childWidth
                lineHeight = max(lineHeight, childHeight)
            }
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MIN_BACKGROUND_OPACITY = 0.0f
        const val MAX_BACKGROUND_OPACITY = 1.0f
        const val MAX_RUNTIME_HOURS = 72.0
        const val RUNTIME_SMOOTHING_OLD_WEIGHT = 0.65
        const val RUNTIME_SMOOTHING_NEW_WEIGHT = 0.35
        const val GRAPH_SAMPLE_COUNT = 30
        const val GRAPH_FPS_MIN_SCALE = 60f
        const val HUD_TEXT_SHADOW_RADIUS_RATIO = 0.18f
        const val MIN_HUD_TEXT_SHADOW_RADIUS_PX = 1.5f
        const val MAX_HUD_TEXT_SHADOW_RADIUS_PX = 4f
        val HUD_TEXT_SHADOW_COLOR: Int = Color.argb(220, 0, 0, 0)
    }
}
