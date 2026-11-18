package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckClassifierTest {

    private fun input(
        fps: Float = 45f,
        frameMs: Float = 1000f / 45f,
        fpsCap: Int = 0,
        vsyncHz: Float = 120f,
        cpuTotal: Float? = 40f,
        coreMax: Float? = 50f,
        gpuPct: Float? = 50f,
        gpuFrameMs: Float? = null,
        headroom: Float? = 0.5f,
        thermalStatus: Int? = 0,
    ) = BottleneckInput(fps, frameMs, fpsCap, vsyncHz, cpuTotal, coreMax, gpuPct, gpuFrameMs, headroom, thermalStatus)

    private data class Case(val name: String, val input: BottleneckInput, val expected: Bottleneck)

    private val cases = listOf(
        // CAPPED
        Case("at fps cap, light load", input(fps = 30.4f, frameMs = 32.9f, fpsCap = 30), Bottleneck.CAPPED),
        Case("at vsync, light load", input(fps = 119.2f, frameMs = 8.4f), Bottleneck.CAPPED),
        Case("design cap 60 when cap and vsync unknown", input(fps = 59.5f, frameMs = 16.8f, vsyncHz = 0f), Bottleneck.CAPPED),
        Case("design cap 30 when cap and vsync unknown", input(fps = 30.9f, frameMs = 32.4f, vsyncHz = 0f), Bottleneck.CAPPED),
        Case("60 is not a cap when vsync is known", input(fps = 60f, frameMs = 16.7f, vsyncHz = 120f), Bottleneck.NONE),
        Case("cap: coreMax unknown uses cpu total", input(fps = 60f, frameMs = 16.7f, fpsCap = 60, coreMax = null, cpuTotal = 30f), Bottleneck.CAPPED),
        Case("cap: gpu frame time well under frame", input(fps = 60f, frameMs = 16.7f, fpsCap = 60, gpuFrameMs = 8f), Bottleneck.CAPPED),
        Case("not capped: core at 70", input(fps = 60f, frameMs = 16.7f, fpsCap = 60, coreMax = 70f), Bottleneck.NONE),
        Case("not capped: gpu at 70", input(fps = 60f, frameMs = 16.7f, fpsCap = 60, gpuPct = 70f), Bottleneck.NONE),
        Case("not capped: gpu time 0.7 of frame", input(fps = 60f, frameMs = 16.7f, fpsCap = 60, gpuFrameMs = 11.7f), Bottleneck.NONE),
        Case("not capped: 1.5 fps off cap", input(fps = 58.5f, frameMs = 17.1f, fpsCap = 60), Bottleneck.NONE),
        Case("capped wins over thermal", input(fps = 30f, frameMs = 33.3f, fpsCap = 30, headroom = 1.0f), Bottleneck.CAPPED),
        Case("zero fps is never capped", input(fps = 0f, frameMs = 0f, fpsCap = 0, vsyncHz = 0f), Bottleneck.NONE),
        // GPU
        Case("gpu busy percent", input(gpuPct = 85f), Bottleneck.GPU),
        Case("gpu frame time 0.8 of frame", input(gpuPct = null, gpuFrameMs = 17.8f), Bottleneck.GPU),
        Case("gpu frame time under 0.8", input(gpuPct = null, gpuFrameMs = 17.0f), Bottleneck.NONE),
        Case("gpu frame time ignored without frame time", input(frameMs = 0f, gpuPct = null, gpuFrameMs = 20f), Bottleneck.NONE),
        Case("gpu and thermal headroom", input(gpuPct = 95f, headroom = 0.95f), Bottleneck.GPU_THERMAL),
        Case("gpu and severe status", input(gpuPct = 95f, thermalStatus = 3), Bottleneck.GPU_THERMAL),
        // CPU
        Case("hot core", input(coreMax = 85f, gpuPct = 40f), Bottleneck.CPU),
        Case("core 84 is not cpu bound", input(coreMax = 84f, cpuTotal = 95f), Bottleneck.NONE),
        Case("cpu total when core unknown", input(coreMax = null, cpuTotal = 90f), Bottleneck.CPU),
        Case("cpu total 89 when core unknown", input(coreMax = null, cpuTotal = 89f), Bottleneck.NONE),
        Case("cpu and thermal", input(coreMax = 99f, headroom = 1.2f), Bottleneck.CPU_THERMAL),
        // CPU and GPU both loaded
        Case("both: gpu time decides", input(coreMax = 99f, gpuPct = 60f, gpuFrameMs = 20f), Bottleneck.GPU),
        Case("both: higher core load wins", input(coreMax = 99f, gpuPct = 90f), Bottleneck.CPU),
        Case("both: higher gpu load wins", input(coreMax = 88f, gpuPct = 95f), Bottleneck.GPU),
        // THERMAL
        Case("headroom 0.95 alone", input(headroom = 0.95f), Bottleneck.THERMAL),
        Case("severe status alone", input(thermalStatus = 3), Bottleneck.THERMAL),
        Case("moderate status is not thermal", input(headroom = 0.9f, thermalStatus = 2), Bottleneck.NONE),
        // NONE
        Case("nothing loaded", input(), Bottleneck.NONE),
        Case("all unknown", input(cpuTotal = null, coreMax = null, gpuPct = null, headroom = null, thermalStatus = null), Bottleneck.NONE),
    )

    @Test
    fun classifiesEveryBranch() {
        for (case in cases) {
            assertEquals(case.name, case.expected, BottleneckClassifier.classify(case.input))
        }
    }

    @Test
    fun labels() {
        assertEquals("GPU+THERMAL", Bottleneck.GPU_THERMAL.label())
        assertEquals("GPU+HOT", Bottleneck.GPU_THERMAL.shortLabel())
        assertEquals("cpu+thermal", Bottleneck.CPU_THERMAL.key())
        assertEquals("capped", Bottleneck.CAPPED.key())
        assertEquals("NOTHING", Bottleneck.NONE.label())
    }
}
