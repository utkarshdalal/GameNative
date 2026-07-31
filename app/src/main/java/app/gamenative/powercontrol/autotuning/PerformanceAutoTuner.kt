package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.PowerManager
import timber.log.Timber
import kotlin.math.abs

/**
 * Automatic performance tuner that uses PID controllers to adjust CPU, GPU, and RAM bus
 * performance based on target FPS and current utilization metrics.
 *
 * @param availableCpuFreqs List of available CPU frequencies
 * @param numGpuLevels Number of GPU power levels
 * @param numBusLevels Number of RAM bus levels
 * @param onCpuFrequencyChange Callback when CPU frequency changes
 * @param onGpuLevelChange Callback when GPU level changes
 * @param onBusLevelChange Callback when RAM bus level changes
 * @param enableLogging Enable verbose logging of tuning operations
 */
class PerformanceAutoTuner(
    private val availableCpuFreqs: List<Long>,
    private val numGpuLevels: Int,
    private val numBusLevels: Int,
    private val onCpuFrequencyChange: (Long) -> Unit,
    private val onGpuLevelChange: (Int) -> Unit,
    private val onBusLevelChange: (Int) -> Unit,
    private val getTuningStrategy: () -> AutoTuningStrategy,
    private val enableLogging: Boolean = false,
    private val skipWarmupCycles: Boolean = false,
) {
    enum class BottleneckType {
        CPU_BOUND,
        GPU_BOUND,
        BOTH_BOUND,
        MEMORY_BOUND,
        NONE
    }

    companion object {
        private const val TAG = "PerformanceAutoTuner"

        private const val WARMUP_CYCLES = 10 // Around 20 seconds

        // Tuning thresholds
        private const val FPS_ERROR_THRESHOLD = 2.0
        private const val FPS_ERROR_LARGE = 5.0
        private const val USAGE_LOW_THRESHOLD = 70.0
        private const val USAGE_HIGH_THRESHOLD = 85.0
        private const val MIN_PERFORMANCE = 20.0
        private const val MAX_PERFORMANCE = 100.0
        private const val PERFORMANCE_REDUCTION_STEP = 2.0
        private const val ADJUSTMENT_DECAY_FACTOR = 0.3
    }

    /**
     * Get adjustment aggressiveness based on tuning strategy and bottleneck status
     */
    private fun getAdjustmentFactor(isBottleneck: Boolean): Double {
        return when (getTuningStrategy()) {
            AutoTuningStrategy.POWER_EFFICIENT -> if (isBottleneck) 0.5 else ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.BALANCED -> ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.AGGRESSIVE -> if (isBottleneck) 0.7 else ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.CONSERVATIVE -> if (isBottleneck) 0.2 else 0.1
        }
    }

    /**
     * Check if we should reduce non-bottleneck components
     */
    private fun shouldReduceNonBottleneck(): Boolean {
        return when (getTuningStrategy()) {
            AutoTuningStrategy.POWER_EFFICIENT -> true
            AutoTuningStrategy.BALANCED -> false
            AutoTuningStrategy.AGGRESSIVE -> false
            AutoTuningStrategy.CONSERVATIVE -> false
        }
    }

    private var cpuPidController: PidController? = null
    private var gpuPidController: PidController? = null
    private var busPidController: PidController? = null
    private var currentCpuPerformance: Double = 50.0
    private var currentGpuPerformance: Double = 50.0
    private var currentBusPerformance: Double = 50.0
    private var warmUpCycles = 0
    private var isRunning: Boolean = false
    private var tuningThread: Thread? = null
    private var currentBottleneck: BottleneckType = BottleneckType.NONE

    /**
     * Start the auto-tuning process
     */
    fun start() {
        if (isRunning) {
            Timber.tag(TAG).w("Auto-tuning already running")
            return
        }

        if (availableCpuFreqs.isEmpty()) {
            Timber.tag(TAG).e("No CPU frequencies available for auto-tuning")
            return
        }

        val minCpuFreq = availableCpuFreqs.first().toDouble()
        val maxCpuFreq = availableCpuFreqs.last().toDouble()

        Timber.tag(TAG).i("Starting auto-tuning (CPU: $minCpuFreq-$maxCpuFreq kHz, GPU levels: $numGpuLevels, Bus levels: $numBusLevels)")

        // Initialize CPU PID controller for incremental adjustments
        cpuPidController = PidController(
            kp = 0.5,
            ki = 0.2,
            kd = 0.1,
            outputMin = -100.0,
            outputMax = 100.0,
            integralLimit = 50.0,
            tag = "CpuPidController",
            enableLogging = enableLogging
        )

        // Initialize GPU PID controller
        if (numGpuLevels > 0) {
            gpuPidController = PidController(
                kp = 0.5,
                ki = 0.2,
                kd = 0.1,
                outputMin = -100.0,
                outputMax = 100.0,
                integralLimit = 50.0,
                tag = "GpuPidController",
                enableLogging = enableLogging
            )
        }

        // Initialize RAM Bus PID controller
        if (numBusLevels > 0) {
            busPidController = PidController(
                kp = 0.5,
                ki = 0.2,
                kd = 0.1,
                outputMin = -100.0,
                outputMax = 100.0,
                integralLimit = 50.0,
                tag = "BusPidController",
                enableLogging = enableLogging
            )
        }

        // Reset performance baselines
        currentCpuPerformance = 50.0
        currentGpuPerformance = 50.0
        currentBusPerformance = 50.0

        isRunning = true

        // Start tuning thread
        tuningThread = Thread {
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    performTuningCycle()
                    Thread.sleep(2000)
                }
            } catch (e: InterruptedException) {
                if (enableLogging) {
                    Timber.tag(TAG).i("Auto-tuning thread interrupted")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Auto-tuning error")
            } finally {
                Timber.tag(TAG).i("Auto-tuning stopped")
            }
        }.apply {
            name = "PerformanceAutoTuner"
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    /**
     * Stop the auto-tuning process
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        tuningThread?.interrupt()
        tuningThread?.join(1000)
        tuningThread = null

        cpuPidController?.reset()
        gpuPidController?.reset()
        busPidController?.reset()
        cpuPidController = null
        gpuPidController = null
        busPidController = null

        if (enableLogging) {
            Timber.tag(TAG).i("Auto-tuning stopped and reset")
        }
    }

    /**
     * Perform one tuning cycle
     */
    private fun performTuningCycle() {
        // Skip first ${WARMUP_CYCLES} cycles regardless of FPS to allow game to start
        if (!skipWarmupCycles && ++warmUpCycles < WARMUP_CYCLES) return

        val targetFps = PowerManager.targetFps.toDouble()
        val currentFps = PowerManager.currentFps.toDouble()

        // Skip tuning when targetFps is 0 (FPS limiter disabled) or currentFps is 0
        if (targetFps == 0.0 || currentFps == 0.0) {
            return
        }

        val cpuUsage = PowerManager.currentCpuUsage.toDouble()
        val gpuUsage = PowerManager.currentGpuUsage.toDouble()
        val fpsError = abs(targetFps - currentFps)

        currentBottleneck = detectBottleneck(cpuUsage, gpuUsage, fpsError)

        if (enableLogging) {
            Timber.tag(TAG).i("Auto-tuning cycle (target: $targetFps, current: $currentFps, bottleneck: $currentBottleneck, strategy: ${getTuningStrategy()})")
        }

        tuneCpu(targetFps, currentFps)
        tuneGpu(targetFps, currentFps)
        tuneBus(targetFps, currentFps)
    }

    /**
     * Tune CPU frequency based on FPS and CPU utilization
     */
    private fun tuneCpu(targetFps: Double, currentFps: Double) {
        cpuPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)
            val cpuUsage = PowerManager.currentCpuUsage.toDouble()

            // Check if CPU is the bottleneck
            val isCpuBottleneck = currentBottleneck == BottleneckType.CPU_BOUND ||
                                  currentBottleneck == BottleneckType.BOTH_BOUND
            val isNotCpuBottleneck = currentBottleneck == BottleneckType.GPU_BOUND ||
                                     currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS with low CPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && cpuUsage < USAGE_LOW_THRESHOLD && currentCpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentCpuPerformance = (currentCpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If CPU is clearly not the bottleneck, reduce it to save power (only for POWER_EFFICIENT)
            else if (shouldReduceNonBottleneck() && isNotCpuBottleneck && fpsError > FPS_ERROR_THRESHOLD && currentCpuPerformance > MIN_PERFORMANCE + 10.0) {
                currentCpuPerformance = (currentCpuPerformance - PERFORMANCE_REDUCTION_STEP * 0.5).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If CPU is the bottleneck, increase performance based on strategy
            else if (isCpuBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentCpuPerformance = (currentCpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If CPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || cpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentCpuPerformance = (currentCpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to actual CPU frequency
            val minCpuFreq = availableCpuFreqs.first()
            val maxCpuFreq = availableCpuFreqs.last()
            val targetCpuFreq = minCpuFreq + ((maxCpuFreq - minCpuFreq) * currentCpuPerformance / 100.0)
            val closestFreq = findClosestFrequency(availableCpuFreqs, targetCpuFreq.toLong())

            // Apply frequency change
            onCpuFrequencyChange(closestFreq)

            if (enableLogging) {
                Timber.tag(TAG).d(
                    "CPU: FPS=%.1f/%.1f, usage=%.1f%%, perf=%.1f%%, freq=%d kHz",
                    currentFps, targetFps, cpuUsage, currentCpuPerformance, closestFreq
                )
            }
        }
    }

    /**
     * Tune GPU power level based on FPS and GPU utilization
     */
    private fun tuneGpu(targetFps: Double, currentFps: Double) {
        if (numGpuLevels <= 0) return

        gpuPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)
            val gpuUsage = PowerManager.currentGpuUsage.toDouble()

            // Check if GPU is the bottleneck
            val isGpuBottleneck = currentBottleneck == BottleneckType.GPU_BOUND ||
                                  currentBottleneck == BottleneckType.BOTH_BOUND
            val isNotGpuBottleneck = currentBottleneck == BottleneckType.CPU_BOUND ||
                                     currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS with low GPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && gpuUsage < USAGE_LOW_THRESHOLD && currentGpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentGpuPerformance = (currentGpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If GPU is clearly not the bottleneck, reduce it to save power (only for POWER_EFFICIENT)
            else if (shouldReduceNonBottleneck() && isNotGpuBottleneck && fpsError > FPS_ERROR_THRESHOLD && currentGpuPerformance > MIN_PERFORMANCE + 10.0) {
                currentGpuPerformance = (currentGpuPerformance - PERFORMANCE_REDUCTION_STEP * 0.5).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If GPU is the bottleneck, increase performance based on strategy
            else if (isGpuBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentGpuPerformance = (currentGpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If GPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || gpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentGpuPerformance = (currentGpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to UI-friendly GPU power level (higher = better performance)
            val targetLevel = (currentGpuPerformance * (numGpuLevels - 1) / 100.0).toInt()
            val gpuLevel = targetLevel.coerceIn(0, numGpuLevels - 1)

            // Apply GPU level change
            onGpuLevelChange(gpuLevel)

            if (enableLogging) {
                Timber.tag(TAG).d(
                    "GPU: FPS=%.1f/%.1f, usage=%.1f%%, perf=%.1f%%, level=%d",
                    currentFps, targetFps, gpuUsage, currentGpuPerformance, gpuLevel
                )
            }
        }
    }

    /**
     * Tune RAM bus level based on FPS
     */
    private fun tuneBus(targetFps: Double, currentFps: Double) {
        if (numBusLevels <= 0) return

        busPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)

            // Check if memory/bus is the bottleneck
            val isMemoryBottleneck = currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS, reduce bus performance to save power
            if (fpsError < FPS_ERROR_THRESHOLD && currentBusPerformance > MIN_PERFORMANCE + 5.0) {
                currentBusPerformance = (currentBusPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If memory is the bottleneck, increase bus performance based on strategy
            else if (isMemoryBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentBusPerformance = (currentBusPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If we're missing target FPS, increase bus performance
            else if (fpsError > FPS_ERROR_LARGE) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentBusPerformance = (currentBusPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to UI-friendly bus level (higher = better performance)
            val targetLevel = (currentBusPerformance * (numBusLevels - 1) / 100.0).toInt()
            val busLevel = targetLevel.coerceIn(0, numBusLevels - 1)

            // Apply bus level change
            onBusLevelChange(busLevel)

            if (enableLogging) {
                Timber.tag(TAG).d(
                    "Bus: FPS=%.1f/%.1f, perf=%.1f%%, level=%d",
                    currentFps, targetFps, currentBusPerformance, busLevel
                )
            }
        }
    }

    /**
     * Find the closest available frequency to the target frequency
     */
    private fun findClosestFrequency(availableFreqs: List<Long>, targetFreq: Long): Long {
        if (availableFreqs.isEmpty()) return targetFreq
        return availableFreqs.minByOrNull { abs(it - targetFreq) } ?: targetFreq
    }

    /**
     * Detect performance bottleneck based on CPU/GPU usage and FPS error
     * Takes into account which components are supported by the driver
     */
    private fun detectBottleneck(cpuUsage: Double, gpuUsage: Double, fpsError: Double): BottleneckType {
        val isMissingTarget = fpsError > FPS_ERROR_THRESHOLD

        if (!isMissingTarget) return BottleneckType.NONE

        val hasGpuSupport = numGpuLevels > 0
        val hasBusSupport = numBusLevels > 0

        val cpuHigh = cpuUsage > USAGE_HIGH_THRESHOLD
        val gpuHigh = gpuUsage > USAGE_HIGH_THRESHOLD
        val cpuLow = cpuUsage < USAGE_LOW_THRESHOLD
        val gpuLow = gpuUsage < USAGE_LOW_THRESHOLD

        return when {
            // Both CPU and GPU are bottlenecks (only if GPU is supported)
            hasGpuSupport && cpuHigh && gpuHigh -> BottleneckType.BOTH_BOUND

            // CPU is the bottleneck
            cpuHigh && (!hasGpuSupport || gpuLow) -> BottleneckType.CPU_BOUND

            // GPU is the bottleneck (only if GPU is supported)
            hasGpuSupport && gpuHigh && cpuLow -> BottleneckType.GPU_BOUND

            // Memory/Bus bottleneck - both CPU and GPU have headroom (only if bus is supported)
            hasBusSupport && cpuLow && (!hasGpuSupport || gpuLow) -> BottleneckType.MEMORY_BOUND

            // Unknown bottleneck or no clear pattern
            else -> BottleneckType.NONE
        }
    }

    /**
     * Check if auto-tuning is currently running
     */
    fun isRunning(): Boolean = isRunning
}
