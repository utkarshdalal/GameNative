package app.gamenative.optimize

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimizeJudgeTest {

    private fun session(
        gameplay: Int = 1200,
        fps: Double? = 40.0,
        stutter: Double? = 0.02,
        throttle: Int? = null,
        reason: String = "quick_menu",
        window: Boolean = true,
    ) = Metrics(gameplaySec = gameplay, steadyFps = fps, stutterShare = stutter, throttleOnsetSec = throttle, exitReason = reason, windowSeen = window)

    @Test
    fun fpsGainOfEightPercentHelps() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(fps = 40.0), session(fps = 43.2), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(fps = 40.0), session(fps = 42.0), isFpsCap = false))
    }

    @Test
    fun stutterDropNeedsRelativeAndAbsoluteChange() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(stutter = 0.020), session(stutter = 0.014), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(stutter = 0.010), session(stutter = 0.006), isFpsCap = false))
    }

    @Test
    fun laterOrNoThrottlingHelps() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(throttle = 300), session(throttle = 480), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(throttle = 300), session(throttle = 400), isFpsCap = false))
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(throttle = 300), session(gameplay = 1200, throttle = null), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(throttle = 1100), session(gameplay = 1200, throttle = null), isFpsCap = false))
    }

    @Test
    fun regressionsMakeItWorse() {
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(session(fps = 40.0), session(fps = 37.9), isFpsCap = false))
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(session(stutter = 0.02), session(stutter = 0.03), isFpsCap = false))
        assertEquals(
            OptimizeJudge.WORSE,
            OptimizeJudge.compare(session(fps = 40.0, stutter = 0.05), session(fps = 37.0, stutter = 0.01), isFpsCap = false),
        )
    }

    @Test
    fun fpsCapRungsAreJudgedOnStutterAndThrottling() {
        assertEquals(
            OptimizeJudge.HELPED,
            OptimizeJudge.compare(session(fps = 55.0, throttle = 300), session(fps = 45.0, throttle = 900), isFpsCap = true),
        )
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(fps = 55.0), session(fps = 45.0), isFpsCap = true))
    }

    @Test
    fun shortCrashIsACrashUnlessTheBaselineEndedTheSameWay() {
        val crash = session(gameplay = 30, reason = "guest_terminated")
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(crash, session(), isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(crash, null, isFpsCap = false, shortSessions = 0))
        assertEquals(
            OptimizeJudge.Decision.Short(1),
            OptimizeJudge.judge(crash, session(gameplay = 40, reason = "guest_terminated"), isFpsCap = false, shortSessions = 0),
        )
        val noWindow = session(gameplay = 60, reason = "quick_menu", window = false)
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(noWindow, session(), isFpsCap = false, shortSessions = 0))
    }

    @Test
    fun shortSessionsBecomeNotComparableOnTheThird() {
        val short = session(gameplay = 300)
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(2), OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 1))
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NOT_COMPARABLE),
            OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 2),
        )
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(), null, isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(), session(gameplay = 200), isFpsCap = false, shortSessions = 0))
    }

    @Test
    fun longSessionsAreCompared() {
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.HELPED),
            OptimizeJudge.judge(session(fps = 50.0), session(fps = 40.0), isFpsCap = false, shortSessions = 0),
        )
    }
}
