package app.gamenative.powercontrol.autotuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the harvest guard against the shape seen on the Retroid Pocket 6 in Superposition:
 * a GPU-bound render that misses an out-of-reach target while its own frame rate swings by
 * far more than a clock step moves it.
 */
class TunerHarvestGuardTest {

    private val capabilities = mapOf(
        TunerDomain.PRIME to DomainCapability(available = true, canRaise = false, canTrim = true),
        TunerDomain.PERFORMANCE to DomainCapability(available = true, canRaise = false, canTrim = true),
        TunerDomain.GPU to DomainCapability(available = true, canRaise = false, canTrim = true),
    )

    /** GPU pinned at the rail with CPU headroom to spare, the case a harvest is built for. */
    private fun input(
        fps: Float,
        p95Ms: Float = 25f,
        slowFrames: Int = 0,
        totalFrames: Int = 100,
        gpuPercent: Float = 90f,
        capabilities: Map<TunerDomain, DomainCapability> = this.capabilities,
    ) = TunerInput(
        fps = fps,
        targetFps = 60,
        frameTimeP50Ms = 1000f / fps,
        frameTimeP95Ms = p95Ms,
        slowFrameCount = slowFrames,
        totalFrameCount = totalFrames,
        cpuUsagePercent = 30f,
        gpuUsagePercent = gpuPercent,
        cpuTempC = 70,
        gpuTempC = 72,
        capabilities = capabilities,
    )

    private fun runToTrim(engine: TunerDecisionEngine, fps: Float = 40f): TunerDecision {
        repeat(20) {
            val decision = engine.decide(input(fps))
            if (decision.action == TunerAction.TRIM) return decision
        }
        throw AssertionError("harvest never trimmed")
    }

    @Test
    fun `harvest trims a domain that is not the bottleneck`() {
        val engine = TunerDecisionEngine()
        val trim = runToTrim(engine)
        assertEquals(TunerAction.TRIM, trim.action)
        assertEquals(TunerDomain.PRIME, trim.domain)
        assertTrue(trim.reason.startsWith("harvest:"))
    }

    @Test
    fun `the cycle right after a trim only settles`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        val next = engine.decide(input(fps = 5f, p95Ms = 300f, slowFrames = 90))
        assertTrue("judged the settle cycle: ${next.reason}", next.action != TunerAction.UNDO)
    }

    @Test
    fun `a single bad cycle does not give the step back`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        engine.decide(input(40f))
        val spike = engine.decide(input(fps = 20f, p95Ms = 90f, slowFrames = 60))
        assertTrue("one spike undid the trim: ${spike.reason}", spike.action != TunerAction.UNDO)
        val recovered = engine.decide(input(40f))
        assertTrue("recovery undid the trim: ${recovered.reason}", recovered.action != TunerAction.UNDO)
    }

    @Test
    fun `sustained damage still gives the step back`() {
        val engine = TunerDecisionEngine()
        val trim = runToTrim(engine)
        engine.decide(input(40f))
        val decisions = (0 until 3).map { engine.decide(input(fps = 18f, p95Ms = 120f, slowFrames = 70)) }
        val undo = decisions.firstOrNull { it.action == TunerAction.UNDO }
            ?: throw AssertionError("sustained damage never undone: ${decisions.map { it.reason }}")
        assertEquals(trim.domain, undo.domain)
        assertTrue(undo.domain in undo.frozenDomains)
    }

    /**
     * The regression this guard was rebuilt for: on the recorded run the frame rate swung
     * between 38 and 52 on its own, and every trim was reverted on the next cycle.
     */
    @Test
    fun `scene swings on an unreachable target do not revert a harmless trim`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)

        val swing = listOf(42f, 39f, 44f, 38f, 47f, 40f, 52f, 41f)
        val undos = swing.count { fps ->
            engine.decide(input(fps, p95Ms = 1000f / fps, slowFrames = 4)).action == TunerAction.UNDO
        }
        assertEquals("the guard reverted a trim on natural frame-rate swing", 0, undos)
    }

    /**
     * The recorded run gave every harvested step back the moment GPU busy read 82%, which
     * is neither GPU-bound nor GPU-idle, and an unclassified miss raises clocks.
     */
    @Test
    fun `a dip in gpu busy does not turn a harvest into a raise`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)

        val busy = listOf(90f, 88f, 82f, 84f, 83f, 89f, 82f, 87f)
        val raises = busy.count { gpu ->
            engine.decide(input(fps = 41f, gpuPercent = gpu)).action == TunerAction.RAISE
        }
        assertEquals("a GPU busy dip inside the dead band raised clocks", 0, raises)
    }

    @Test
    fun `clocks are still raised once the gpu genuinely goes idle`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)

        val withCpuHeadroom = capabilities + mapOf(
            TunerDomain.PRIME to DomainCapability(available = true, canRaise = true, canTrim = true),
            TunerDomain.PERFORMANCE to DomainCapability(available = true, canRaise = true, canTrim = true),
        )
        val decisions = (0 until 8).map {
            engine.decide(input(fps = 20f, gpuPercent = 40f, capabilities = withCpuHeadroom))
        }
        assertTrue(
            "never raised on a CPU-bound render: ${decisions.map { it.reason }}",
            decisions.any { it.action == TunerAction.RAISE },
        )
    }

    /**
     * releaseHolds marks a regime change (a cap probe reopening every clock), so the render
     * averages from before it must not seed the next harvest baseline. With a stale ~30 fps
     * baseline the 45 fps damage below would sit inside the drop guard and the trim would
     * survive; a fresh ~55 fps baseline catches it.
     */
    @Test
    fun `releaseHolds drops the stale render baseline`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine, fps = 30f)
        engine.releaseHolds()

        runToTrim(engine, fps = 55f)
        val decisions = (0 until 3).map { engine.decide(input(45f)) }
        assertTrue(
            "damage after a regime change was judged against the stale baseline: ${decisions.map { it.reason }}",
            decisions.any { it.action == TunerAction.UNDO },
        )
    }

    /**
     * The adaptive FPS cap reads this to tell harvested steps from held-back performance.
     * If a harvest did not report itself, its steps would block the cap step-down and the
     * two features would deadlock.
     */
    @Test
    fun `the engine reports that it is harvesting`() {
        val engine = TunerDecisionEngine()
        runToTrim(engine)
        assertTrue("a harvest trim did not report harvesting", engine.harvesting)

        repeat(3) { engine.decide(input(41f)) }
        assertTrue("the harvest watch did not report harvesting", engine.harvesting)
    }

    @Test
    fun `a render that meets its target does not report harvesting`() {
        val engine = TunerDecisionEngine()
        repeat(12) { engine.decide(input(fps = 60f, gpuPercent = 50f)) }
        assertTrue("reported harvesting while the target was met", !engine.harvesting)
    }

    @Test
    fun `trims accumulate across a swinging render`() {
        val engine = TunerDecisionEngine()
        val swing = listOf(40f, 44f, 38f, 46f, 39f, 43f, 41f, 45f, 40f, 42f)
        var trims = 0
        var undos = 0
        repeat(6) {
            swing.forEach { fps ->
                when (engine.decide(input(fps, p95Ms = 1000f / fps, slowFrames = 3)).action) {
                    TunerAction.TRIM -> trims++
                    TunerAction.UNDO -> undos++
                    else -> Unit
                }
            }
        }
        assertTrue("expected repeated trims, got $trims", trims >= 3)
        assertEquals("trims were reverted on noise", 0, undos)
    }
}
