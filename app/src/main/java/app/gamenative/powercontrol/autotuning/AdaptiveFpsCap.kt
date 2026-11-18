package app.gamenative.powercontrol.autotuning

/**
 * A single move of the effective FPS cap along the ladder.
 *
 * [requiresApply] is false for bookkeeping events that report a decision without moving the
 * limiter. [openAllClocks] asks the owner to release every power domain in the same cycle.
 */
data class FpsCapChange(
    val fromFps: Int,
    val toFps: Int,
    val rungIndex: Int,
    val action: String,
    val reason: String,
    val triggerCycles: Int,
    val requiresApply: Boolean = true,
    val openAllClocks: Boolean = false,
)

/**
 * Cap values for logs owned by other components.
 */
data class FpsCapSnapshot(
    val userCapFps: Int,
    val effectiveCapFps: Int,
    val capState: String,
)

/**
 * Effective FPS cap ladder.
 *
 * The user's limiter value is the ceiling and every session starts on it. When the render
 * window misses the effective cap by more than [DEFICIT_FPS_MARGIN] for [CYCLES_BEFORE_STEP]
 * cycles in a row - and, on devices where a tuner still owns the clocks, only while those
 * clocks are wide open - the effective cap drops one rung.
 *
 * The cap also recovers inside a session. After [HEADROOM_CYCLES_BEFORE_PROBE] consecutive
 * cycles that hit the effective cap while the owner reports spare clock headroom, the cap moves
 * one rung up and every domain is opened at the same moment. The next [PROBE_WATCH_CYCLES]
 * cycles are the watch: after [PROBE_GRACE_CYCLES] ramp cycles, one cycle below the new cap
 * steps back down and starts a backoff of [BACKOFF_CYCLES]; a clean watch commits the rung and
 * clears the backoff. Probe and backoff state is session only and is never persisted.
 *
 * Pure JVM logic: no Android, sysfs or engine dependency. The owner applies the returned change
 * and calls [commit] only when the apply succeeded.
 */
class AdaptiveFpsCap {
    companion object {
        const val TRIGGER_REASON = "fps:deficit"
        const val RECOVERY_PROBE_REASON = "recovery:probe"
        const val RECOVERY_COMMIT_REASON = "recovery:commit"
        const val RECOVERY_REDOWN_REASON = "recovery:redown"

        const val CYCLES_BEFORE_STEP = 30
        const val HEADROOM_CYCLES_BEFORE_PROBE = 60
        const val PROBE_WATCH_CYCLES = 15
        const val PROBE_GRACE_CYCLES = 3
        const val HEADROOM_FPS_MARGIN = 1f
        const val DEFICIT_FPS_MARGIN = 3f

        /** Trimmed clock steps a tuning owner needs before its headroom counts as evidence. */
        const val MIN_TRIMMED_STEPS = 2
        const val MIN_CAP_FPS = 20
        const val CYCLES_PER_MINUTE = 60

        const val ACTION_DOWN = "cap:down"
        const val ACTION_UP = "cap:up"
        const val ACTION_PROBE = "cap:probe"
        const val ACTION_REDOWN = "cap:redown"

        const val STATE_IDLE = "idle"
        const val STATE_EVIDENCE = "evidence"
        const val STATE_PROBE = "probe"
        const val STATE_BACKOFF = "backoff"

        /** Backoff after the first, second and every later failed probe, in cycles. */
        val BACKOFF_CYCLES = listOf(300, 600, 1200)

        /**
         * Rungs for a user cap, highest first. Rounded quarters, deduplicated, and never
         * below [MIN_CAP_FPS].
         */
        fun ladderFor(userCapFps: Int): List<Int> {
            if (userCapFps < MIN_CAP_FPS) return emptyList()
            return listOf(
                userCapFps,
                Math.round(userCapFps * 3f / 4f),
                Math.round(userCapFps / 2f),
            ).distinct().filter { it >= MIN_CAP_FPS }
        }
    }

    private var ladder: List<Int> = emptyList()
    private var lastAppliedCapFps = UNSET_CAP
    private var headroomCycles = 0
    private var probeWatchCycles = 0
    private var probeFailures = 0

    var userCapFps: Int = 0
        private set

    var rungIndex: Int = 0
        private set

    var triggerCycles: Int = 0
        private set

    var backoffCycles: Int = 0
        private set

    val effectiveCapFps: Int
        get() = ladder.getOrNull(rungIndex) ?: userCapFps

    val backoffMinutes: Int
        get() = backoffCycles / CYCLES_PER_MINUTE

    val capState: String
        get() = when {
            probeWatchCycles > 0 -> STATE_PROBE
            backoffCycles > 0 -> STATE_BACKOFF
            headroomCycles > 0 -> STATE_EVIDENCE
            else -> STATE_IDLE
        }

    /**
     * Tracks the limiter value this class regulates against. A value it did not apply itself
     * is a new user ceiling: the ladder is rebuilt, the cap returns to it and every recovery
     * counter starts over.
     */
    fun observeCap(observedCapFps: Int) {
        if (observedCapFps == lastAppliedCapFps) return

        lastAppliedCapFps = observedCapFps
        userCapFps = observedCapFps
        ladder = ladderFor(observedCapFps)
        rungIndex = 0
        triggerCycles = 0
        resetRecovery()
    }

    /**
     * Feeds one steady cycle in and returns the cap change to apply, or null to hold.
     *
     * [clocksOpen] is false while a tuning owner still holds clock headroom back, which blocks
     * the downward step. [clockHeadroom] reports that the same owner runs on meaningfully
     * trimmed clocks, the extra evidence the upward probe needs. Owners without a tuner pass
     * true for both.
     */
    fun onCycle(fps: Float, clocksOpen: Boolean, clockHeadroom: Boolean): FpsCapChange? {
        if (ladder.size < 2) {
            triggerCycles = 0
            headroomCycles = 0
            return null
        }

        if (backoffCycles > 0) backoffCycles--

        val belowCap = fps < effectiveCapFps - DEFICIT_FPS_MARGIN

        if (probeWatchCycles > 0) return watchProbe(belowCap)

        if (belowCap && clocksOpen) {
            headroomCycles = 0
            triggerCycles++
            if (triggerCycles < CYCLES_BEFORE_STEP) return null

            val cycles = triggerCycles
            triggerCycles = 0
            return changeTo(rungIndex + 1, TRIGGER_REASON, cycles)
        }

        triggerCycles = 0
        return recoveryProbe(fps, clockHeadroom)
    }

    /**
     * Records a change whose apply reached the frame limiter, and advances the recovery state
     * machine to match.
     */
    fun commit(change: FpsCapChange) {
        rungIndex = change.rungIndex
        lastAppliedCapFps = change.toFps
        triggerCycles = 0
        headroomCycles = 0

        when {
            change.action == ACTION_PROBE -> probeWatchCycles = PROBE_WATCH_CYCLES
            change.action == ACTION_REDOWN -> {
                probeWatchCycles = 0
                probeFailures++
                backoffCycles = backoffCyclesFor(probeFailures)
            }
            change.reason == RECOVERY_COMMIT_REASON -> {
                probeWatchCycles = 0
                probeFailures = 0
                backoffCycles = 0
            }
            change.action == ACTION_DOWN && change.reason == TRIGGER_REASON -> {
                probeFailures = 0
                backoffCycles = 0
            }
        }
    }

    /**
     * Clears the consecutive trigger and headroom counts without moving the cap. Used for
     * cycles that produced no decision at all, such as a metrics gap.
     */
    fun interrupt() {
        triggerCycles = 0
        headroomCycles = 0
    }

    /**
     * Cap values for owners that log the cap alongside their own state.
     */
    fun snapshot(): FpsCapSnapshot = FpsCapSnapshot(userCapFps, effectiveCapFps, capState)

    private fun resetRecovery() {
        headroomCycles = 0
        probeWatchCycles = 0
        probeFailures = 0
        backoffCycles = 0
    }

    private fun watchProbe(belowCap: Boolean): FpsCapChange? {
        val watchCycle = PROBE_WATCH_CYCLES - probeWatchCycles + 1
        if (belowCap && watchCycle > PROBE_GRACE_CYCLES) {
            triggerCycles = 0
            return changeTo(rungIndex + 1, RECOVERY_REDOWN_REASON, watchCycle)
                ?.copy(action = ACTION_REDOWN)
        }

        probeWatchCycles--
        if (probeWatchCycles > 0) return null

        return FpsCapChange(
            fromFps = effectiveCapFps,
            toFps = effectiveCapFps,
            rungIndex = rungIndex,
            action = ACTION_UP,
            reason = RECOVERY_COMMIT_REASON,
            triggerCycles = PROBE_WATCH_CYCLES,
            requiresApply = false,
        )
    }

    private fun recoveryProbe(fps: Float, clockHeadroom: Boolean): FpsCapChange? {
        if (rungIndex <= 0 || !hasHeadroom(fps, clockHeadroom)) {
            headroomCycles = 0
            return null
        }

        headroomCycles++
        if (backoffCycles > 0 || headroomCycles < HEADROOM_CYCLES_BEFORE_PROBE) return null

        val cycles = headroomCycles
        headroomCycles = 0
        return changeTo(rungIndex - 1, RECOVERY_PROBE_REASON, cycles)
            ?.copy(action = ACTION_PROBE, openAllClocks = true)
    }

    private fun hasHeadroom(fps: Float, clockHeadroom: Boolean): Boolean {
        if (fps < effectiveCapFps - HEADROOM_FPS_MARGIN) return false
        return clockHeadroom
    }

    private fun backoffCyclesFor(failures: Int): Int {
        val index = (failures - 1).coerceIn(0, BACKOFF_CYCLES.lastIndex)
        return BACKOFF_CYCLES[index]
    }

    private fun changeTo(newRungIndex: Int, reason: String, cycles: Int): FpsCapChange? {
        val fromFps = effectiveCapFps
        val toFps = ladder.getOrNull(newRungIndex) ?: return null
        if (toFps == fromFps) return null

        return FpsCapChange(
            fromFps = fromFps,
            toFps = toFps,
            rungIndex = newRungIndex,
            action = if (toFps > fromFps) ACTION_UP else ACTION_DOWN,
            reason = reason,
            triggerCycles = cycles,
        )
    }
}

private const val UNSET_CAP = Int.MIN_VALUE
