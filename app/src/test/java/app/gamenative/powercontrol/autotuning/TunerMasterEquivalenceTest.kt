package app.gamenative.powercontrol.autotuning

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * X11 sessions, frame-generation sessions and every session where the collector counts no
 * repeats hand the tuner repeatedFrameCount = 0 (a null count becomes 0 in ClusterTuner). There
 * the engine must decide exactly as on master: same action, domain, steps, reason, steady count
 * and frozen set on every cycle, including after warm-start caps are noted.
 */
class TunerMasterEquivalenceTest {

    private val strategies = listOf(
        StrategyTuning.BALANCED,
        StrategyTuning.POWER_EFFICIENT,
        StrategyTuning.AGGRESSIVE,
        StrategyTuning.CONSERVATIVE,
    )

    /** Step index per domain, 0 = uncapped, [maxSteps] = floor, moved by the decisions. */
    private class Caps(val maxSteps: Int, val gpuAvailable: Boolean) {
        val index = TunerDomain.entries.associateWith { 0 }.toMutableMap()
        fun capabilities() = TunerDomain.entries.associateWith { d ->
            val available = d != TunerDomain.GPU || gpuAvailable
            DomainCapability(
                available = available,
                canRaise = available && index[d]!! > 0,
                canTrim = available && index[d]!! < maxSteps,
            )
        }
        fun apply(decision: TunerDecision) {
            val d = decision.domain ?: return
            when (decision.action) {
                TunerAction.TRIM -> index[d] = (index[d]!! + decision.steps).coerceAtMost(maxSteps)
                TunerAction.RAISE, TunerAction.UNDO -> index[d] = (index[d]!! - decision.steps).coerceAtLeast(0)
                TunerAction.HOLD -> Unit
            }
        }
    }

    private fun randomInput(r: Random, target: Int, caps: Caps): TunerInput {
        val period = if (target > 0) 1000f / target else 16.7f
        // Mostly on target, with stretches of deficit, heavy load and missing readings.
        val mode = r.nextInt(10)
        val fps = when {
            target == 0 -> 40f + r.nextFloat() * 60f
            mode < 6 -> target - r.nextFloat() * 1.2f
            mode < 8 -> target - 2f - r.nextFloat() * 15f
            else -> target * (0.5f + r.nextFloat() * 0.4f)
        }
        val total = r.nextInt(60, 130)
        return TunerInput(
            fps = fps,
            targetFps = target,
            frameTimeP50Ms = period * (0.9f + r.nextFloat() * 0.3f),
            frameTimeP95Ms = period * (0.9f + r.nextFloat() * (if (mode < 6) 0.3f else 1.5f)),
            slowFrameCount = if (mode < 6) r.nextInt(0, 2) else r.nextInt(0, total / 3),
            totalFrameCount = total,
            cpuUsagePercent = if (r.nextInt(12) == 0) null else r.nextFloat() * 100f,
            gpuUsagePercent = if (r.nextInt(12) == 0) null else r.nextFloat() * 100f,
            cpuTempC = 40 + r.nextInt(50),
            gpuTempC = 40 + r.nextInt(50),
            capabilities = caps.capabilities(),
        )
    }

    @Test
    fun `without repeats every decision matches master`() {
        var cycles = 0
        for (seed in 0 until 200) {
            val r = Random(seed)
            val strategy = strategies[seed % strategies.size]
            val current = TunerDecisionEngine { strategy }
            val master = MasterTunerDecisionEngine { strategy }
            val caps = Caps(maxSteps = 4 + seed % 12, gpuAvailable = seed % 5 != 0)
            if (seed % 3 == 0) {
                // A warm start carried caps in; on master it left no trace in the engine.
                current.noteWarmStart(TunerDomain.entries.associateWith { r.nextInt(0, caps.maxSteps) })
            }
            var target = listOf(30, 40, 45, 60, 90, 120, 0)[seed % 7]
            repeat(600) { cycle ->
                if (r.nextInt(150) == 0) target = listOf(30, 45, 60, 120)[r.nextInt(4)]
                if (r.nextInt(200) == 0) {
                    current.releaseHolds()
                    master.releaseHolds()
                }
                val input = randomInput(r, target, caps)
                val expected = master.decide(input)
                val actual = current.decide(input)
                assertEquals("seed $seed cycle $cycle", expected, actual)
                caps.apply(actual)
                cycles++
            }
        }
        assertEquals(200 * 600, cycles)
    }
}
