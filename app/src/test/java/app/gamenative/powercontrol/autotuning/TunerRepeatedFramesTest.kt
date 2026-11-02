package app.gamenative.powercontrol.autotuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the repeated-frame steady rule against the shape seen on the Retroid Pocket 6 in
 * SteamWorld Dig 2: a 60 fps cap held at 59.9 fps with a p95 of one period, while the trimmed
 * clocks make the game repeat a frame every few seconds.
 */
class TunerRepeatedFramesTest {

    private val capabilities = mapOf(
        TunerDomain.PRIME to DomainCapability(available = true, canRaise = true, canTrim = true),
        TunerDomain.PERFORMANCE to DomainCapability(available = true, canRaise = true, canTrim = true),
        TunerDomain.GPU to DomainCapability(available = true, canRaise = true, canTrim = true),
    )

    /** Capped at 60 and meeting it, CPU and GPU both well under the bound thresholds. */
    private fun input(repeated: Int = 0) = TunerInput(
        fps = 59.9f,
        targetFps = 60,
        frameTimeP50Ms = 16.7f,
        frameTimeP95Ms = 17f,
        slowFrameCount = 0,
        totalFrameCount = 120,
        cpuUsagePercent = 30f,
        gpuUsagePercent = 40f,
        cpuTempC = 60,
        gpuTempC = 60,
        capabilities = capabilities,
        repeatedFrameCount = repeated,
    )

    private fun runToTrim(engine: TunerDecisionEngine): TunerDecision {
        repeat(20) {
            val decision = engine.decide(input())
            if (decision.action == TunerAction.TRIM) return decision
        }
        throw AssertionError("never trimmed")
    }

    /** Runs a clean window until the trim watch has ended. */
    private fun finishWatch(engine: TunerDecisionEngine) {
        repeat(TunerThresholds.TRIM_WATCH_CYCLES) {
            assertEquals(TunerAction.HOLD, engine.decide(input()).action)
        }
    }

    @Test
    fun `a clean capped render trims`() {
        val trim = runToTrim(TunerDecisionEngine())
        assertEquals(TunerDomain.PRIME, trim.domain)
    }

    @Test
    fun `one repeat in the window blocks the next trim`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        finishWatch(engine)
        repeat(20) {
            val decision = engine.decide(input(repeated = 1))
            assertEquals("trimmed through a repeat: ${decision.reason}", TunerAction.HOLD, decision.action)
            assertTrue(decision.reason, decision.reason.startsWith("repeat-hold repeated=1 in 10s"))
        }
    }

    @Test
    fun `a repeat during the trim watch gives that step back`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        val undo = engine.decide(input(repeated = 1))
        assertEquals(TunerAction.UNDO, undo.action)
        assertEquals(TunerDomain.PRIME, undo.domain)
        assertTrue(undo.reason, undo.reason.contains("repeated=1"))
    }

    @Test
    fun `repeats after the watch step the last trim back once per window`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        finishWatch(engine)
        val second = runToTrim(engine)
        finishWatch(engine)

        val undo = engine.decide(input(repeated = 3))
        assertEquals(TunerAction.UNDO, undo.action)
        assertEquals(second.domain, undo.domain)
        assertEquals("repeat-undo:${second.domain!!.key} repeated=3 in 10s", undo.reason)

        // The same repeats are still in the window: hold until it has turned over.
        repeat(TunerThresholds.REPEAT_UNDO_COOLDOWN_CYCLES - 1) {
            assertEquals(TunerAction.HOLD, engine.decide(input(repeated = 3)).action)
        }
        assertEquals(TunerAction.UNDO, engine.decide(input(repeated = 3)).action)
    }

    @Test
    fun `a single repeat holds but does not step back`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        finishWatch(engine)
        val decision = engine.decide(input(repeated = 1))
        assertEquals(TunerAction.HOLD, decision.action)
    }

    @Test
    fun `repeats with no trim this session hold and raise nothing`() {
        val engine = TunerDecisionEngine()
        repeat(TunerThresholds.STEADY_RENDER_CYCLES - 1) { engine.decide(input(repeated = 0)) }
        val decision = engine.decide(input(repeated = 5))
        assertEquals(TunerAction.HOLD, decision.action)
        assertTrue(decision.reason, decision.reason.startsWith("repeat-hold"))
    }

    @Test
    fun `three step-backs that did not cut repeats turn step-backs off`() {
        val engine = TunerDecisionEngine()
        repeat(4) {
            runToTrim(engine)
            finishWatch(engine)
        }
        var undos = 0
        repeat(TunerThresholds.REPEAT_UNDO_COOLDOWN_CYCLES * 6) {
            val decision = engine.decide(input(repeated = 4))
            if (decision.action == TunerAction.UNDO) undos++
            assertTrue(decision.reason, decision.action != TunerAction.TRIM)
        }
        assertEquals(TunerThresholds.REPEAT_UNDO_INEFFECTIVE_LIMIT, undos)
        val held = engine.decide(input(repeated = 4))
        assertEquals(TunerAction.HOLD, held.action)
        assertTrue(held.reason, held.reason.endsWith("step-back off"))
    }

    @Test
    fun `step-backs that cut repeats stay on`() {
        val engine = TunerDecisionEngine()
        repeat(4) {
            runToTrim(engine)
            finishWatch(engine)
        }
        var undos = 0
        repeat(4) {
            if (engine.decide(input(repeated = 4)).action == TunerAction.UNDO) undos++
            // The window after each step-back holds fewer repeats than the step-back saw.
            repeat(TunerThresholds.REPEAT_UNDO_COOLDOWN_CYCLES) { engine.decide(input(repeated = 1)) }
        }
        assertEquals(4, undos)
    }

    @Test
    fun `a cap change drops the cooldown of a step-back in flight`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        finishWatch(engine)
        runToTrim(engine)
        finishWatch(engine)
        assertEquals(TunerAction.UNDO, engine.decide(input(repeated = 3)).action)
        assertEquals(TunerAction.HOLD, engine.decide(input(repeated = 3)).action)
        assertEquals(TunerAction.UNDO, engine.decide(input(repeated = 3).copy(targetFps = 30, fps = 30f)).action)
    }

    @Test
    fun `warm-start caps step back on repeats like trims made this session`() {
        val engine = TunerDecisionEngine()
        repeat(TunerThresholds.STEADY_RENDER_CYCLES - 1) { engine.decide(input()) }
        // A state file left perf at its floor, 10 steps down, and prime 2 steps down.
        engine.noteWarmStart(mapOf(TunerDomain.PRIME to 2, TunerDomain.PERFORMANCE to 10, TunerDomain.GPU to 0))
        val undo = engine.decide(input(repeated = 2))
        assertEquals(TunerAction.UNDO, undo.action)
        assertEquals(TunerDomain.PERFORMANCE, undo.domain)
        assertTrue(undo.reason, undo.reason.startsWith("repeat-undo:perf repeated=2"))
    }

    @Test
    fun `a warm-start floor keeps stepping back while repeats go on`() {
        val engine = TunerDecisionEngine()
        repeat(TunerThresholds.STEADY_RENDER_CYCLES - 1) { engine.decide(input()) }
        engine.noteWarmStart(mapOf(TunerDomain.PERFORMANCE to 10))
        var undos = 0
        repeat(TunerThresholds.REPEAT_UNDO_COOLDOWN_CYCLES * 12) {
            val decision = engine.decide(input(repeated = 4))
            if (decision.action == TunerAction.UNDO) {
                undos++
                assertEquals(TunerDomain.PERFORMANCE, decision.domain)
            }
        }
        // The ineffective-step-back cutoff does not apply: every warm-start step comes back.
        assertEquals(10, undos)
    }
}
