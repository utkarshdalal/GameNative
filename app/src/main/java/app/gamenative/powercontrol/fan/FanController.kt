package app.gamenative.powercontrol.fan

import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Closed-loop fan control, verified on the Retroid Pocket 6.
 *
 * The vendor controller only releases the PWM in CUSTOM mode, so a session takes the
 * fan over by recording the mode it found, switching to CUSTOM and regulating the duty
 * cycle from [FanTempController]. The captured mode is written back on every exit path:
 * clean stop, pause, and - through the driver's baseline artifacts - a crashed session.
 */
object FanController {
    private const val TAG = "PowerFan"

    private const val DUTY_PATH = "/sys/class/gpio5_pwm2/duty"
    private const val PERIOD_PATH = "/sys/class/gpio5_pwm2/period"
    private const val DEFAULT_PERIOD_TICKS = 50000

    private const val FAN_MODE_SETTING = "fan_mode"
    private const val FAN_MODE_CUSTOM = 6
    private const val FAN_MODE_SMART = 4
    private val VENDOR_FAN_MODES = setOf(1, 4, 5)

    private const val TICK_INTERVAL_MS = 500L
    private const val METRICS_STALE_MS = 2000L
    private const val REASSERT_EVERY_TICKS = 10L
    private const val LOG_EVERY_TICKS = 20L

    private val controller = FanTempController()

    private var driver: PServerDriver? = null
    private var capturedMode: Int? = null
    private var periodTicks: Int = DEFAULT_PERIOD_TICKS
    private var lastWrittenDuty: Int = -1
    private var lastWrittenPercent: Int = -1
    private var tickCount = 0L
    private var overrideWasEngaged = false
    private var loopThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var latestSample: FanSample? = null
        private set

    fun isRunning(): Boolean = running

    /**
     * True when this session could drive a fan. The PWM nodes are only probed in [start],
     * which stays the authoritative check, because reading them costs a root call.
     */
    fun isAvailable(candidate: PerformanceDriver?): Boolean {
        return candidate is PServerDriver
    }

    /**
     * Capture the vendor fan mode, enter CUSTOM and start regulating.
     * Does nothing unless a [PServerDriver] session has PWM nodes root can actually read.
     */
    fun start(candidate: PerformanceDriver?) {
        if (running) return

        val pserver = candidate as? PServerDriver ?: return
        val period = readPeriodTicks(pserver)
        if (period == null) {
            Timber.tag(TAG).d("Fan PWM nodes not readable, fan control disabled")
            return
        }

        val mode = readFanMode(pserver)
        if (mode == FAN_MODE_CUSTOM) {
            Timber.tag(TAG).i("fan_mode is already CUSTOM(6): another tuner owns the fan, skipping fan management")
            return
        }
        Timber.tag(TAG).i("Captured fan_mode=%s (period=%d ticks)", mode?.toString() ?: "unreadable", period)

        driver = pserver
        capturedMode = mode
        periodTicks = period
        controller.reset()
        lastWrittenDuty = -1
        lastWrittenPercent = -1
        tickCount = 0L
        overrideWasEngaged = false
        latestSample = null

        val floorPercent = FanTuning.FLOOR_PERCENT.roundToInt()
        val floorDuty = dutyFor(floorPercent)
        val entered = pserver.executeRootCommand(
            "settings put system $FAN_MODE_SETTING $FAN_MODE_CUSTOM; " +
                "chmod 644 '$DUTY_PATH'; echo $floorDuty > '$DUTY_PATH'"
        ).isSuccess

        if (!entered) {
            Timber.tag(TAG).w("Failed to enter CUSTOM fan mode, fan control disabled")
            driver = null
            capturedMode = null
            return
        }
        lastWrittenDuty = floorDuty
        lastWrittenPercent = floorPercent
        Timber.tag(TAG).i("CUSTOM fan mode entered at floor %d%% (duty=%d/%d)", floorPercent, floorDuty, period)

        val restoreCommand = restoreCommandFor(restoreMode())
        if (pserver.updateFanRestoreCommand(restoreCommand)) {
            Timber.tag(TAG).i("Fan restore (crash-script-written): %s", restoreCommand)
        }

        running = true
        loopThread = Thread { loop() }.apply {
            name = "FanController"
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun stop() = shutdown("clean")

    fun pause() = shutdown("pause")

    private fun shutdown(path: String) {
        val pserver = driver ?: return

        running = false
        loopThread?.interrupt()
        loopThread?.join(1000)
        loopThread = null

        val mode = restoreMode()
        val captured = capturedMode
        val success = pserver.executeRootCommand("settings put system $FAN_MODE_SETTING $mode").isSuccess
        Timber.tag(TAG).i(
            "Fan restore (%s): fan_mode=%d%s success=%b",
            path,
            mode,
            if (captured in VENDOR_FAN_MODES) "" else " (fallback, captured=${captured?.toString() ?: "unreadable"})",
            success,
        )

        driver = null
        capturedMode = null
        latestSample = null
    }

    private fun loop() {
        var lastTickNanos = System.nanoTime()
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                Thread.sleep(TICK_INTERVAL_MS)
                val now = System.nanoTime()
                val dtSeconds = (now - lastTickNanos) / 1_000_000_000.0
                lastTickNanos = now
                runCatching { tick(dtSeconds) }
                    .onFailure { Timber.tag(TAG).e(it, "Fan cycle failed") }
            }
        } catch (_: InterruptedException) {
            Timber.tag(TAG).d("Fan loop interrupted")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Fan loop error")
        }
    }

    private fun tick(dtSeconds: Double) {
        val pserver = driver ?: return

        val snapshot = PowerManager.latestMetrics ?: return
        if (System.currentTimeMillis() - snapshot.timestampMs > METRICS_STALE_MS) return
        val tempC = pserver.deviceTempC(snapshot.cpuTempC, snapshot.gpuTempC) ?: return

        tickCount++
        val percent = controller.update(tempC.toDouble(), dtSeconds)
        latestSample = FanSample(percent, tempC)

        if (controller.overrideEngaged != overrideWasEngaged) {
            overrideWasEngaged = controller.overrideEngaged
            if (overrideWasEngaged) {
                Timber.tag(TAG).i(
                    "Thermal override engaged at %d C (>= %d C), forcing 100%%",
                    tempC,
                    FanTuning.OVERRIDE_TEMP_C.toInt(),
                )
            } else {
                Timber.tag(TAG).i("Thermal override released at %d C", tempC)
            }
        }

        if (percent != lastWrittenPercent) {
            writeDuty(pserver, percent)
        } else if (tickCount % REASSERT_EVERY_TICKS == 0L) {
            reassertDuty(pserver)
        }

        if (tickCount % LOG_EVERY_TICKS == 0L) {
            Timber.tag(TAG).i(
                "temp=%d smoothed=%.1f target=%d pi=%d%% applied=%d%%",
                tempC,
                controller.smoothedTempC ?: tempC.toDouble(),
                FanTuning.TARGET_TEMP_C.toInt(),
                controller.rawIntegerPercent,
                percent,
            )
        }
    }

    private fun writeDuty(pserver: PServerDriver, percent: Int): Boolean {
        val duty = dutyFor(percent)
        val success = pserver.executeRootCommand(
            "chmod 644 '$DUTY_PATH'; echo $duty > '$DUTY_PATH'"
        ).isSuccess

        if (success) {
            lastWrittenDuty = duty
            lastWrittenPercent = percent
        } else {
            Timber.tag(TAG).w("Failed to write fan duty %d (%d%%)", duty, percent)
        }
        return success
    }

    private fun reassertDuty(pserver: PServerDriver) {
        val current = pserver.executeRootCommand("cat '$DUTY_PATH'")
            .getOrNull()
            ?.trim()
            ?.lines()
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?: return

        if (current == lastWrittenDuty) return
        Timber.tag(TAG).w(
            "External fan duty change detected (node=%d, ours=%d), re-asserting %d%%",
            current,
            lastWrittenDuty,
            lastWrittenPercent,
        )
        writeDuty(pserver, lastWrittenPercent)
    }

    private fun restoreMode(): Int = capturedMode?.takeIf { it in VENDOR_FAN_MODES } ?: FAN_MODE_SMART

    private fun restoreCommandFor(mode: Int): String = "settings put system $FAN_MODE_SETTING $mode"

    private fun dutyFor(percent: Int): Int = (periodTicks.toLong() * percent / 100L).toInt()

    private fun readPeriodTicks(pserver: PServerDriver): Int? {
        val output = pserver.executeRootCommand("cat '$PERIOD_PATH'").getOrNull()?.trim()
        if (output.isNullOrEmpty()) return null
        return output.lines().firstOrNull()?.trim()?.toIntOrNull() ?: DEFAULT_PERIOD_TICKS
    }

    private fun readFanMode(pserver: PServerDriver): Int? {
        return pserver.executeRootCommand("settings get system $FAN_MODE_SETTING")
            .getOrNull()
            ?.trim()
            ?.lines()
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
    }
}
