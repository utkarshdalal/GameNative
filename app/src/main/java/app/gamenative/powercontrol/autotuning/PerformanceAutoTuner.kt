package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.PowerManager
import timber.log.Timber
import kotlin.math.abs

/**
 * Automatic performance tuner that uses PID controllers to adjust CPU and GPU
 * performance based on target FPS and current utilization metrics.
 *
 * @param availableCpuFreqs List of available CPU frequencies
 * @param numGpuLevels Number of GPU power levels
 * @param onCpuFrequencyChange Callback when CPU frequency changes
 * @param onGpuLevelChange Callback when GPU level changes
 * @param enableLogging Enable verbose logging of tuning operations
 */
class PerformanceAutoTuner(
    private val availableCpuFreqs: List<Long>,
    private val numGpuLevels: Int,
    private val onCpuFrequencyChange: (Long) -> Unit,
    private val onGpuLevelChange: (Int) -> Unit,
    private val enableLogging: Boolean = false
) {
    companion object {
        private const val TAG = "PerformanceAutoTuner"

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

    private var cpuPidController: PidController? = null
    private var gpuPidController: PidController? = null
    private var currentCpuPerformance: Double = 50.0
    private var currentGpuPerformance: Double = 50.0
    private var isRunning: Boolean = false
    private var tuningThread: Thread? = null

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

        Timber.tag(TAG).i("Starting auto-tuning (CPU: $minCpuFreq-$maxCpuFreq kHz, GPU levels: $numGpuLevels)")

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

        // Reset performance baselines
        currentCpuPerformance = 50.0
        currentGpuPerformance = 50.0

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
        cpuPidController = null
        gpuPidController = null

        if (enableLogging) {
            Timber.tag(TAG).i("Auto-tuning stopped and reset")
        }
    }

    /**
     * Perform one tuning cycle
     */
    private fun performTuningCycle() {
        val targetFps = PowerManager.targetFps.toDouble()
        val currentFps = PowerManager.currentFps.toDouble()

        // Skip tuning when currentFps is 0
        if (currentFps == 0.0) {
            return
        }

        if (enableLogging) {
            Timber.tag(TAG).i("Auto-tuning cycle (target: $targetFps, current: $currentFps)")
        }

        tuneCpu(targetFps, currentFps)
        tuneGpu(targetFps, currentFps)
    }

    /**
     * Tune CPU frequency based on FPS and CPU utilization
     */
    private fun tuneCpu(targetFps: Double, currentFps: Double) {
        cpuPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)
            val cpuUsage = PowerManager.currentCpuUsage.toDouble()

            // If we're hitting target FPS with low CPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && cpuUsage < USAGE_LOW_THRESHOLD && currentCpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentCpuPerformance = (currentCpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If CPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || cpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                currentCpuPerformance = (currentCpuPerformance + adjustment * ADJUSTMENT_DECAY_FACTOR).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
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

            // If we're hitting target FPS with low GPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && gpuUsage < USAGE_LOW_THRESHOLD && currentGpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentGpuPerformance = (currentGpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If GPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || gpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                currentGpuPerformance = (currentGpuPerformance + adjustment * ADJUSTMENT_DECAY_FACTOR).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
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
     * Find the closest available frequency to the target frequency
     */
    private fun findClosestFrequency(availableFreqs: List<Long>, targetFreq: Long): Long {
        if (availableFreqs.isEmpty()) return targetFreq
        return availableFreqs.minByOrNull { abs(it - targetFreq) } ?: targetFreq
    }

    /**
     * Check if auto-tuning is currently running
     */
    fun isRunning(): Boolean = isRunning
}
