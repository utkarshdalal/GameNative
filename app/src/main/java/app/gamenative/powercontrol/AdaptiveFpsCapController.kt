package app.gamenative.powercontrol

import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
import app.gamenative.powercontrol.autotuning.FpsCapChange
import app.gamenative.powercontrol.autotuning.FpsCapSnapshot
import app.gamenative.powercontrol.metrics.JsonlSessionLog
import java.io.File
import java.util.Locale
import timber.log.Timber

/**
 * Session owner of the adaptive FPS cap.
 *
 * Runs on every device that has a reachable frame limiter and is independent of auto-tuning:
 * the loop reads the metrics collector and the user's limiter value, and moves the effective
 * cap through [AdaptiveFpsCap]. When [app.gamenative.powercontrol.autotuning.ClusterTuner]
 * happens to be running, its clock state is folded into the decision - the cap only steps down
 * once the tuner has nothing left to give, and an upward probe reopens the tuner's clocks.
 */
object AdaptiveFpsCapController {
    private const val TAG = "PowerTuner"
    private const val CYCLE_INTERVAL_MS = 1000L
    private const val METRICS_STALE_MS = 2000L
    private const val EVENT_RESTORE = "cap:disable-restore"

    private val sessionLog = JsonlSessionLog(TAG, "fpscap-")

    @Volatile
    private var cap = AdaptiveFpsCap()

    private var loopThread: Thread? = null
    private var loggedRefusedApply = false

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /**
     * Cap values for logs owned by other components, or null while the controller is idle.
     */
    fun snapshot(): FpsCapSnapshot? = if (running) cap.snapshot() else null

    /**
     * True while an upward probe and its watch own the render window. Other experiments wait
     * for this to clear before they start one of their own.
     */
    fun isProbeActive(): Boolean = running && cap.capState == AdaptiveFpsCap.STATE_PROBE

    fun start(containerDir: File?, logDirectory: File?, sessionStartMillis: Long = System.currentTimeMillis()) {
        if (running) return

        loopThread?.let {
            it.interrupt()
            it.join(1000)
        }
        loopThread = null

        cap = AdaptiveFpsCap()
        loggedRefusedApply = false
        logDirectory?.let { sessionLog.open(it, sessionStartMillis) }

        running = true
        loopThread = Thread { loop() }.apply {
            name = "AdaptiveFpsCap"
            priority = Thread.NORM_PRIORITY
            start()
        }

        Timber.tag(TAG).i("Adaptive FPS cap started at the user ceiling (log=%s)", sessionLog.path ?: "none")
    }

    fun stop() = shutdown("stop")

    fun pause() = shutdown("pause")

    private fun shutdown(path: String) {
        if (loopThread == null && !running) return

        running = false
        loopThread?.interrupt()
        loopThread?.join(1000)
        loopThread = null

        restoreUserCap(path)
        sessionLog.close()
        Timber.tag(TAG).i("Adaptive FPS cap stopped (%s)", path)
    }

    private fun loop() {
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                runCatching { runCycle() }
                    .onFailure { Timber.tag(TAG).e(it, "Adaptive FPS cap cycle failed") }
                Thread.sleep(CYCLE_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Timber.tag(TAG).d("Adaptive FPS cap loop interrupted")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Adaptive FPS cap loop error")
        }
    }

    private fun runCycle() {
        if (PowerManager.currentProfile?.enableAdaptiveFpsCap == false) {
            running = false
            restoreUserCap("disabled")
            return
        }

        val targetFps = PowerManager.targetFps
        cap.observeCap(targetFps)

        val snapshot = PowerManager.latestMetrics
        if (targetFps <= 0 ||
            snapshot == null ||
            System.currentTimeMillis() - snapshot.timestampMs > METRICS_STALE_MS ||
            snapshot.fps <= 0f
        ) {
            cap.interrupt()
            return
        }

        val trimmedSteps = PowerManager.tunerTrimmedSteps()

        // A harvest only trims domains that are not the bottleneck, so its steps are not what
        // holds the frame rate back and must not block the step down. Treating them as held
        // performance deadlocks the two features against each other: the harvest keeps the
        // step count above zero, the cap never drops, and the target stays out of reach.
        val harvesting = PowerManager.tunerIsHarvesting() == true
        val clocksOpen = trimmedSteps == null || trimmedSteps == 0 || harvesting
        val clockHeadroom = trimmedSteps == null || trimmedSteps >= AdaptiveFpsCap.MIN_TRIMMED_STEPS

        val change = cap.onCycle(snapshot.fps, clocksOpen, clockHeadroom) ?: return

        if (change.requiresApply && !PowerManager.applyFpsCapToEngines(change.toFps)) {
            if (!loggedRefusedApply) {
                loggedRefusedApply = true
                Timber.tag(TAG).w(
                    "Frame limiter did not accept cap %d, holding %d",
                    change.toFps,
                    change.fromFps,
                )
            }
            return
        }

        if (change.openAllClocks) {
            PowerManager.openTunerClocksForProbe()
        }

        cap.commit(change)
        logChange(change, snapshot.fps, targetFps, trimmedSteps)
    }

    /**
     * Puts the user's own limiter value back when the feature is switched off mid-game or the
     * session ends on a lowered rung, and drops every counter.
     */
    private fun restoreUserCap(reason: String) {
        val userCapFps = cap.userCapFps
        val effectiveCapFps = cap.effectiveCapFps

        if (userCapFps > 0 && effectiveCapFps < userCapFps) {
            val applied = PowerManager.applyFpsCapToEngines(userCapFps)
            Timber.tag(TAG).i(
                "%s %s: cap %d->%d (applied=%b)",
                EVENT_RESTORE,
                reason,
                effectiveCapFps,
                userCapFps,
                applied,
            )
            logEvent(
                event = EVENT_RESTORE,
                reason = reason,
                fromFps = effectiveCapFps,
                toFps = userCapFps,
                rungIndex = 0,
                cycles = 0,
                fps = null,
                targetFps = userCapFps,
                trimmedSteps = null,
            )
        }

        cap = AdaptiveFpsCap()
    }

    private fun logChange(change: FpsCapChange, fps: Float, targetFps: Int, trimmedSteps: Int?) {
        when {
            change.action == AdaptiveFpsCap.ACTION_PROBE -> Timber.tag(TAG).i(
                "%s %d->%d after %d headroom cycles (user=%d rung=%d)",
                change.action,
                change.fromFps,
                change.toFps,
                change.triggerCycles,
                cap.userCapFps,
                change.rungIndex,
            )
            change.action == AdaptiveFpsCap.ACTION_REDOWN -> Timber.tag(TAG).i(
                "%s %d->%d on watch cycle %d, backoff=%dmin",
                change.action,
                change.fromFps,
                change.toFps,
                change.triggerCycles,
                cap.backoffMinutes,
            )
            change.reason == AdaptiveFpsCap.RECOVERY_COMMIT_REASON -> Timber.tag(TAG).i(
                "%s committed at %d (user=%d rung=%d)",
                change.action,
                change.toFps,
                cap.userCapFps,
                change.rungIndex,
            )
            else -> Timber.tag(TAG).i(
                "%s %s: cap %d->%d (user=%d rung=%d) after %d cycles at fps=%.1f/%d",
                change.action,
                change.reason,
                change.fromFps,
                change.toFps,
                cap.userCapFps,
                change.rungIndex,
                change.triggerCycles,
                fps,
                targetFps,
            )
        }

        logEvent(
            event = change.action,
            reason = change.reason,
            fromFps = change.fromFps,
            toFps = change.toFps,
            rungIndex = change.rungIndex,
            cycles = change.triggerCycles,
            fps = fps,
            targetFps = targetFps,
            trimmedSteps = trimmedSteps,
        )
    }

    private fun logEvent(
        event: String,
        reason: String,
        fromFps: Int,
        toFps: Int,
        rungIndex: Int,
        cycles: Int,
        fps: Float?,
        targetFps: Int,
        trimmedSteps: Int?,
    ) {
        val line = String.format(
            Locale.US,
            "{\"t\":%d,\"event\":\"%s\",\"reason\":\"%s\",\"from\":%d,\"to\":%d,\"rung\":%d," +
                "\"cycles\":%d,\"fps\":%s,\"target\":%d,\"userCap\":%d,\"effectiveCap\":%d," +
                "\"capState\":\"%s\",\"backoffMin\":%d,\"trimmedSteps\":%s}",
            System.currentTimeMillis(),
            event,
            reason,
            fromFps,
            toFps,
            rungIndex,
            cycles,
            fps?.let { String.format(Locale.US, "%.2f", it) } ?: "null",
            targetFps,
            cap.userCapFps,
            cap.effectiveCapFps,
            cap.capState,
            cap.backoffMinutes,
            trimmedSteps?.toString() ?: "null",
        )
        sessionLog.append(line)
    }

}
