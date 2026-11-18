package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.autotuning.PerformanceAutoTuner.BottleneckType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleneckClassifyTest {

    private fun classify(cpu: Double, gpu: Double?, hasGpu: Boolean = true, hasBus: Boolean = true, fpsError: Double = 10.0) =
        PerformanceAutoTuner.classifyBottleneck(cpu, gpu, fpsError, hasGpuSupport = hasGpu, hasBusSupport = hasBus)

    @Test
    fun withReadingClassifiesAsBefore() {
        assertEquals(BottleneckType.BOTH_BOUND, classify(cpu = 90.0, gpu = 90.0))
        assertEquals(BottleneckType.CPU_BOUND, classify(cpu = 90.0, gpu = 50.0))
        assertEquals(BottleneckType.GPU_BOUND, classify(cpu = 50.0, gpu = 90.0))
        assertEquals(BottleneckType.MEMORY_BOUND, classify(cpu = 50.0, gpu = 50.0))
        assertEquals(BottleneckType.NONE, classify(cpu = 50.0, gpu = 50.0, hasBus = false))
        assertEquals(BottleneckType.NONE, classify(cpu = 78.0, gpu = 50.0))
        assertEquals(BottleneckType.NONE, classify(cpu = 90.0, gpu = 90.0, fpsError = 1.0))
    }

    @Test
    fun missingReadingMatchesNoGpuSupport() {
        for (cpu in listOf(20.0, 50.0, 78.0, 90.0)) {
            for (hasBus in listOf(true, false)) {
                assertEquals(
                    "cpu=$cpu bus=$hasBus",
                    classify(cpu = cpu, gpu = 0.0, hasGpu = false, hasBus = hasBus),
                    classify(cpu = cpu, gpu = null, hasGpu = true, hasBus = hasBus),
                )
            }
        }
    }

    @Test
    fun missingReadingNeverNamesTheGpu() {
        for (cpu in listOf(20.0, 50.0, 78.0, 90.0)) {
            val type = classify(cpu = cpu, gpu = null)
            assertTrue("cpu=$cpu -> $type", type != BottleneckType.GPU_BOUND && type != BottleneckType.BOTH_BOUND)
        }
    }
}
