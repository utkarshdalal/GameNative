package app.gamenative.powercontrol.loop

import timber.log.Timber

/**
 * Battery policy that holds a CPU frequency cap during GPU-bound stretches and lifts it the
 * moment a CPU burst appears (loading, scene transitions, heavy moments). Measured on RP6:
 * a static 1.3 GHz cap saves ~20% system power but costs ~6% fps by clipping those bursts;
 * lifting on demand recovers the fps while keeping the capped watts most of the time.
 *
 * Measured constants honored below: clock settle after a cap change is 1-3 s (LIFT dwell is
 * 6 ticks = 3 s), the fps cost appears at any uniform cap, knee cap = 1.3 GHz.
 *
 * State machine (tick = one loop iteration; @500 ms):
 *   WARMUP  - build the frametime baseline, no caps; enter CAPPED after ENTER_STREAK
 *             consecutive GPU-bound ticks with fresh frame stats.
 *   CAPPED  - cpuMax pinned to the GPU-bound cap; lift on any burst signal.
 *   LIFTED  - cpuMax at stock; re-cap only after LIFT_DWELL ticks then RECOVER_STREAK
 *             consecutive calm ticks.
 * Baseline is an EMA of avg_frametime_ms updated only while uncapped so the cap's own cost
 * cannot poison it. Flap guard forces a 60 s LIFTED hold if the machine oscillates too often.
 * Battery temp >= THERMAL_LIFT_C latches LIFTED for the session.
 */
class DynamicCapPolicy : Policy {

    private enum class State { WARMUP, CAPPED, LIFTED }

    private companion object {
        const val TAG = "DynamicCapPolicy"

        const val WARMUP_TICKS = 20
        const val BASELINE_ALPHA = 0.05

        const val FRESH_MS = 2_000L

        const val ENTER_GPU_BUSY = 75
        const val ENTER_STREAK = 4

        const val LIFT_AVG_MULT = 1.15
        const val LIFT_P99_MULT = 1.5
        const val LIFT_GPU_BUSY = 60

        const val RECOVER_AVG_MULT = 1.05
        const val RECOVER_GPU_BUSY = 75
        const val RECOVER_STREAK = 4
        const val LIFT_DWELL_TICKS = 6

        const val FLAP_WINDOW_MS = 60_000L
        const val FLAP_MAX_CHANGES = 6
        const val FLAP_LOCK_MS = 60_000L

        const val THERMAL_LIFT_C = 43.0

        const val CAP_FALLBACK_KHZ = 1_300_000L
        const val STOCK_MAX_KHZ = 2_016_000L
    }

    private var state = State.WARMUP
    private var tick = 0
    private var baseline: Double? = null

    private var enterStreak = 0
    private var recoverStreak = 0
    private var liftTick = 0

    private var fullRangeEmitted = false
    private var thermalLatched = false
    private var thermalLogged = false

    private val transitionTimesMs = ArrayDeque<Long>()
    private var flapLockUntilMs = 0L
    private var flapLogged = false

    // Cap resolved from params each tick so the state handlers can emit it without threading params.
    private var capKhz = CAP_FALLBACK_KHZ

    override fun decide(snapshot: Snapshot, params: DeviceParams?): Decision {
        tick++
        capKhz = params?.cpu?.gpuBoundCapKhz ?: CAP_FALLBACK_KHZ
        val nowMs = System.currentTimeMillis()

        val avg = snapshot.frameStats?.avgFrametimeMs
        val p99 = snapshot.frameStats?.p99FrametimeMs
        val gpuBusy = snapshot.gpuBusyPercent
        val ageMs = snapshot.frameStatsAgeMs
        val fresh = snapshot.frameStats != null && ageMs != null && ageMs <= FRESH_MS

        // Baseline only tracks uncapped frametime so the cap's cost cannot skew it.
        if (state != State.CAPPED && avg != null) {
            baseline = baseline?.let { it + BASELINE_ALPHA * (avg - it) } ?: avg
        }

        // Thermal latch: once tripped, never cap again this session.
        if (!thermalLatched && snapshot.battTempC != null && snapshot.battTempC >= THERMAL_LIFT_C) {
            thermalLatched = true
            if (!thermalLogged) {
                Timber.tag(TAG).i("thermal latch battTempC=%.1f -> LIFTED for session", snapshot.battTempC)
                thermalLogged = true
            }
            return forceLifted(nowMs, "thermal battTempC=${snapshot.battTempC}")
        }
        if (thermalLatched) {
            return Decision()
        }

        // Flap guard: if oscillating too fast, hold LIFTED for FLAP_LOCK_MS.
        if (nowMs < flapLockUntilMs) {
            return forceLifted(nowMs, "flapLock")
        }

        return when (state) {
            State.WARMUP -> decideWarmup(nowMs, gpuBusy, fresh)
            State.CAPPED -> decideCapped(nowMs, avg, p99, gpuBusy, ageMs)
            State.LIFTED -> decideLifted(nowMs, avg, gpuBusy, fresh)
        }
    }

    private fun decideWarmup(nowMs: Long, gpuBusy: Int?, fresh: Boolean): Decision {
        val firstEmit = if (!fullRangeEmitted) {
            fullRangeEmitted = true
            Decision(cpuMaxKhz = STOCK_MAX_KHZ)
        } else {
            Decision()
        }

        val gpuBound = fresh && gpuBusy != null && gpuBusy >= ENTER_GPU_BUSY
        enterStreak = if (gpuBound) enterStreak + 1 else 0

        val warmedUp = baseline != null && tick >= WARMUP_TICKS
        if (warmedUp && enterStreak >= ENTER_STREAK) {
            return enterCapped(nowMs, gpuBusy, "warmup->capped")
        }
        return firstEmit
    }

    private fun decideCapped(nowMs: Long, avg: Double?, p99: Double?, gpuBusy: Int?, ageMs: Long?): Decision {
        val base = baseline
        val stale = ageMs == null || ageMs > FRESH_MS
        val avgBurst = base != null && avg != null && avg > LIFT_AVG_MULT * base
        val p99Burst = base != null && p99 != null && p99 > LIFT_P99_MULT * base
        val gpuFreed = gpuBusy != null && gpuBusy < LIFT_GPU_BUSY

        if (avgBurst || p99Burst || gpuFreed || stale) {
            val reason = buildString {
                append("lift")
                if (avgBurst) append(" avg=$avg>${LIFT_AVG_MULT}*${base}")
                if (p99Burst) append(" p99=$p99>${LIFT_P99_MULT}*${base}")
                if (gpuFreed) append(" gpuBusy=$gpuBusy<$LIFT_GPU_BUSY")
                if (stale) append(" stale=${ageMs}ms")
            }
            return lift(nowMs, reason)
        }
        return Decision()
    }

    private fun decideLifted(nowMs: Long, avg: Double?, gpuBusy: Int?, fresh: Boolean): Decision {
        val base = baseline
        val dwellMet = tick - liftTick >= LIFT_DWELL_TICKS
        val calm = fresh && base != null && avg != null &&
            avg <= RECOVER_AVG_MULT * base && gpuBusy != null && gpuBusy >= RECOVER_GPU_BUSY
        recoverStreak = if (dwellMet && calm) recoverStreak + 1 else 0

        if (dwellMet && recoverStreak >= RECOVER_STREAK && base != null) {
            return enterCapped(nowMs, gpuBusy, "lifted->capped")
        }
        return Decision()
    }

    private fun enterCapped(nowMs: Long, gpuBusy: Int?, reason: String): Decision {
        if (registerTransition(nowMs)) {
            return forceLifted(nowMs, "flapGuardTripped")
        }
        state = State.CAPPED
        enterStreak = 0
        recoverStreak = 0
        Timber.tag(TAG).i(
            "%s tick=%d gpuBusy=%s baseline=%s cap=%d",
            reason, tick, gpuBusy.toString(), baseline?.let { "%.2f".format(it) }, capKhz,
        )
        return Decision(cpuMaxKhz = capKhz)
    }

    private fun lift(nowMs: Long, reason: String): Decision {
        if (registerTransition(nowMs)) {
            return forceLifted(nowMs, "flapGuardTripped")
        }
        state = State.LIFTED
        liftTick = tick
        recoverStreak = 0
        Timber.tag(TAG).i("%s tick=%d baseline=%s", reason, tick, baseline?.let { "%.2f".format(it) })
        return Decision(cpuMaxKhz = STOCK_MAX_KHZ)
    }

    private fun forceLifted(nowMs: Long, reason: String): Decision {
        val wasLifted = state == State.LIFTED
        if (!wasLifted) {
            state = State.LIFTED
            liftTick = tick
            recoverStreak = 0
            Timber.tag(TAG).i("force-lift %s tick=%d", reason, tick)
            return Decision(cpuMaxKhz = STOCK_MAX_KHZ)
        }
        return Decision()
    }

    /** Records a state change; returns true if the flap threshold was exceeded (lock engaged). */
    private fun registerTransition(nowMs: Long): Boolean {
        while (transitionTimesMs.isNotEmpty() && nowMs - transitionTimesMs.first() > FLAP_WINDOW_MS) {
            transitionTimesMs.removeFirst()
        }
        transitionTimesMs.addLast(nowMs)
        if (transitionTimesMs.size > FLAP_MAX_CHANGES) {
            flapLockUntilMs = nowMs + FLAP_LOCK_MS
            if (!flapLogged) {
                Timber.tag(TAG).i("flap guard tripped changes=%d/60s -> LIFTED %ds", transitionTimesMs.size, FLAP_LOCK_MS / 1000)
                flapLogged = true
            }
            return true
        }
        flapLogged = false
        return false
    }
}
