package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVerdictTest {

    private fun gameplay(seconds: Int = 600, sample: (Int) -> SessionSample): List<SessionSample> =
        (SessionVerdict.WARMUP_SEC until SessionVerdict.WARMUP_SEC + seconds).map(sample)

    private fun compute(samples: List<SessionSample>, trimEvents: Int = 0, designCap: Boolean = false, gpuTopMhz: Int? = null) =
        SessionVerdict.compute(
            SessionVerdict.Input(
                samples = samples,
                clusterCpuinfoMaxMhz = intArrayOf(2000, 3000),
                clusterCores = listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)),
                gpuTopMhz = gpuTopMhz,
                trimEvents = trimEvents,
                designCapDetected = designCap,
            ),
        )

    private fun base(
        t: Int,
        fps: Float = 50f,
        gpu: Int? = 50,
        gpuMhz: Int? = null,
        gpuMaxMhz: Int? = null,
        cpu: Int? = 40,
        coreMax: Int? = if (cpu != null) 70 else null,
        hot: Int? = 60,
        user: Int? = hot?.let { it * 9 / 10 },
        sys: Int? = hot?.let { it - it * 9 / 10 },
        state: Char? = 'R',
        vol: Int? = 100,
        status: Int? = 0,
        cpuTemp: Int? = 60,
        bigMax: Int = 3000,
        availMb: Int? = 4000,
        frames: Int = fps.toInt(),
        refreshMultiple: Int = 0,
    ) = SessionSample(
        t = t,
        fps = fps,
        frames = frames,
        refreshMultipleFrames = refreshMultiple,
        gpuBusy = gpu,
        gpuMhz = gpuMhz,
        gpuMaxMhz = gpuMaxMhz,
        cpuTotal = cpu,
        coreMax = coreMax,
        clusterMaxMhz = intArrayOf(2000, bigMax),
        thermalStatus = status,
        cpuTempC = cpuTemp,
        hotPct = hot,
        hotUserTicks = user,
        hotSysTicks = sys,
        hotState = state,
        hotCore = 7,
        hotVolPerSec = vol,
        hotInvolPerSec = 50,
        availMb = availMb,
    )

    @Test
    fun doomShapeIsSyncSpin() {
        val r = compute(gameplay { base(it, gpu = 35, cpu = 30, hot = 100, user = 24, sys = 76, vol = 0) })
        assertEquals(SessionVerdict.SYNC_SPIN, r.cls)
        assertTrue(r.confident)
        assertEquals(1.0, r.shares.getValue("sync"), 1e-9)
        assertEquals(0.24, r.hot!!["user_share"] as Double, 1e-9)
        assertEquals("big", r.hot!!["cluster"])
    }

    @Test
    fun sleepingThreadWithManyVoluntarySwitchesIsSyncSpin() {
        val r = compute(gameplay { base(it, gpu = 40, hot = 30, state = 'S', vol = 8000) })
        assertEquals(SessionVerdict.SYNC_SPIN, r.cls)
    }

    @Test
    fun mccShapeIsCpuSingle() {
        val r = compute(gameplay { base(it, gpu = 50, cpu = 35, hot = 90, user = 81, sys = 9) })
        assertEquals(SessionVerdict.CPU_SINGLE, r.cls)
        assertTrue(r.confident)
        assertEquals("real", r.properties().let { (it["perf_verdict"] as Map<*, *>)["proc_stat"] })
    }

    @Test
    fun clockProxyPhoneCpuClassIsNotConfident() {
        val r = compute(gameplay { base(it, gpu = 50, cpu = null, hot = 90, user = 81, sys = 9) })
        assertEquals(SessionVerdict.CPU_SINGLE, r.cls)
        assertFalse(r.confident)
        assertFalse(r.procStatReal)
    }

    @Test
    fun clockProxyPhoneSyncSpinIsNotConfident() {
        val r = compute(gameplay { base(it, gpu = 35, cpu = null, hot = 100, user = 24, sys = 76, vol = 0) })
        assertEquals(SessionVerdict.SYNC_SPIN, r.cls)
        assertFalse(r.confident)
    }

    @Test
    fun gpuBusyWithLowCpuIsGpu() {
        val r = compute(gameplay { base(it, gpu = 92, cpu = 40, hot = 60, gpuMhz = 900) }, gpuTopMhz = 900)
        assertEquals(SessionVerdict.GPU, r.cls)
        assertTrue(r.confident)
        assertTrue(r.confidence >= 0.5)
    }

    @Test
    fun cappedGpuClockIsNotGpuBound() {
        val r = compute(gameplay { base(it, gpu = 92, cpu = 40, hot = 60, gpuMhz = 500) }, gpuTopMhz = 900)
        assertEquals(0.0, r.shares.getValue("gpu"), 1e-9)
    }

    @Test
    fun gpuWithoutBusyCounterIsNotConfident() {
        val r = compute(gameplay { base(it, gpu = if (it % 2 == 0) 92 else null, cpu = 40, hot = 60) })
        assertEquals(SessionVerdict.GPU, r.cls)
        assertFalse(r.confident)
    }

    @Test
    fun highTotalCpuIsCpuMulti() {
        val r = compute(gameplay { base(it, gpu = 50, cpu = 85, coreMax = 80, hot = 60) })
        assertEquals(SessionVerdict.CPU_MULTI, r.cls)
        assertTrue(r.confident)
    }

    @Test
    fun framesAtRefreshMultiplesArePresentLocked() {
        val r = compute(gameplay { base(it, fps = 30f, gpu = 40, hot = 40, refreshMultiple = 30) })
        assertEquals(SessionVerdict.PRESENT_LOCKED, r.cls)
    }

    @Test
    fun lowAvailableMemoryIsMemory() {
        val r = compute(gameplay { base(it, gpu = 50, hot = 40, availMb = 200) })
        assertEquals(SessionVerdict.MEMORY, r.cls)
    }

    @Test
    fun repeatedTrimEventsWithLowAvailableMemoryOutrankGpu() {
        val r = compute(gameplay { base(it, gpu = 92, cpu = 40, hot = 60, availMb = 800) }, trimEvents = 3)
        assertEquals(SessionVerdict.MEMORY, r.cls)
        assertTrue(r.memoryHard)
        assertEquals(1.0, r.confidence, 1e-9)
        assertEquals(800, r.availMinMb)
    }

    @Test
    fun trimEventsWithPlentyOfFreeMemoryLeaveTheGpuShareInCharge() {
        val r = compute(
            gameplay { base(it, gpu = if (it % 10 < 7) 92 else 50, cpu = 40, hot = 60, gpuMhz = 900, availMb = 3274) },
            trimEvents = 4,
            gpuTopMhz = 900,
        )
        assertFalse(r.memoryHard)
        assertEquals(0.7, r.shares.getValue("gpu"), 1e-9)
        assertEquals(SessionVerdict.GPU, r.cls)
        assertTrue(r.confident)
        assertEquals(3274, r.availMinMb)
    }

    @Test
    fun trimEventsWithUnknownAvailableMemoryAreNotAHardMemoryVerdict() {
        val r = compute(gameplay { base(it, gpu = 50, cpu = 40, hot = 40, availMb = null) }, trimEvents = 5)
        assertFalse(r.memoryHard)
        assertNull(r.availMinMb)
        assertTrue(r.cls != SessionVerdict.MEMORY)
    }

    @Test
    fun lowFpsWithNothingSaturatedIsNotSaturated() {
        val r = compute(gameplay { base(it, fps = 20f, gpu = 50, hot = 40) })
        assertEquals(SessionVerdict.NOT_SATURATED, r.cls)
        assertFalse(r.confident)
        assertEquals("low_fps", r.symptom)
        assertEquals(SessionVerdict.NONE, compute(gameplay { base(it, fps = 20f, gpu = 50, hot = 40) }, designCap = true).cls)
    }

    @Test
    fun shortSessionHasNoVerdict() {
        val r = compute(gameplay(200) { base(it, gpu = 92, cpu = 40) })
        assertEquals(SessionVerdict.NONE, r.cls)
        assertFalse(r.confident)
        assertEquals(200, r.gameplaySec)
    }

    @Test
    fun warmupSecondsAreDropped() {
        val samples = (0 until SessionVerdict.WARMUP_SEC).map { base(it, gpu = 99) } + gameplay { base(it) }
        assertEquals(600, compute(samples).gameplaySec)
    }

    @Test
    fun statusThrottleWithFpsDropIsThermalGpu() {
        val onset = SessionVerdict.WARMUP_SEC + 400
        val r = compute(
            gameplay(1200) {
                if (it < onset) base(it, fps = 60f, gpu = 90) else base(it, fps = 45f, gpu = 90, status = 2)
            },
        )
        assertEquals(SessionVerdict.THERMAL_GPU, r.cls)
        assertTrue(r.thermalGpu)
        assertTrue(r.confident)
        assertEquals("status", r.thermalSignal)
        assertEquals(400, r.throttleOnsetSec)
        assertEquals(60.0, r.fpsBeforeOnset!!, 1e-9)
        assertEquals(45.0, r.fpsAfterOnset!!, 1e-9)
        assertEquals(400, r.properties()["throttle_onset_s"])
    }

    @Test
    fun throttleWithoutFrameCostIsNotThermal() {
        val onset = SessionVerdict.WARMUP_SEC + 400
        val r = compute(gameplay(1200) { base(it, fps = 60f, gpu = 90, status = if (it < onset) 0 else 2) })
        assertFalse(r.thermalGpu)
        assertEquals(SessionVerdict.GPU, r.cls)
        assertEquals("status", r.thermalSignal)
    }

    @Test
    fun silentDeviceUsesClockAndTemperature() {
        val onset = SessionVerdict.WARMUP_SEC + 400
        val r = compute(
            gameplay(1200) {
                if (it < onset) {
                    base(it, fps = 60f, gpu = 90)
                } else {
                    base(it, fps = 45f, gpu = 90, bigMax = 1500, cpuTemp = 85)
                }
            },
        )
        assertEquals("clock_temp", r.thermalSignal)
        assertEquals(400, r.throttleOnsetSec)
        assertEquals(SessionVerdict.THERMAL_GPU, r.cls)
    }

    @Test
    fun clockDropWithoutHeatIsNoThrottle() {
        val onset = SessionVerdict.WARMUP_SEC + 400
        val r = compute(gameplay(1200) { base(it, fps = 60f, gpu = 90, bigMax = if (it < onset) 3000 else 1500, cpuTemp = 70) })
        assertEquals("none", r.thermalSignal)
        assertNull(r.throttleOnsetSec)
        assertFalse(r.properties().containsKey("throttle_onset_s"))
    }

    /** The Odin 3 The Forest session: hot from the first minute, GPU busy at 70 % clock, the game thread near full. */
    private fun forestSession(status: Int?, cpuTemp: Int?) = gameplay(874) {
        base(it, fps = 51f, gpu = 88, gpuMhz = 630, cpu = 65, hot = 89, status = status?.let { s -> s + it % 3 }, cpuTemp = cpuTemp)
    }

    @Test
    fun hotFromLaunchWithBusyGpuIsThermalGpu() {
        val r = compute(forestSession(status = 3, cpuTemp = 101), gpuTopMhz = 900)
        assertEquals(SessionVerdict.THERMAL_GPU, r.cls)
        assertTrue(r.confident)
        assertTrue(r.thermalGpu)
        assertEquals(0, r.throttleOnsetSec)
        assertEquals(0.8, r.confidence, 1e-9)
        assertEquals("low_fps", r.symptom)
        assertNull((r.properties()["perf_verdict"] as Map<*, *>)["source"])
    }

    @Test
    fun hotFromLaunchWithFallingFiveMinuteFpsIsHeatDrop() {
        val r = SessionVerdict.compute(
            SessionVerdict.Input(forestSession(status = 3, cpuTemp = 101), gpuTopMhz = 900, fpsBy5Min = listOf(30, 60, 55, 48)),
        )
        assertEquals(SessionVerdict.THERMAL_GPU, r.cls)
        assertEquals("heat_drop", r.symptom)
    }

    @Test
    fun throttledGpuBelowFullClockStillCountsAsGpu() {
        val throttled = compute(gameplay(400) { base(it, gpu = 92, gpuMhz = 630, cpu = 65, hot = 60, status = 2) }, gpuTopMhz = 900)
        assertEquals(1.0, throttled.shares.getValue("gpu"), 1e-9)
        assertEquals(SessionVerdict.GPU, throttled.cls)
        assertTrue(throttled.confident)
        val capped = compute(gameplay(400) { base(it, gpu = 92, gpuMhz = 630, cpu = 65, hot = 60, status = 0) }, gpuTopMhz = 900)
        assertEquals(0.0, capped.shares.getValue("gpu"), 1e-9)
    }

    @Test
    fun legacyClassifierFillsInWhenTheSamplerNamesNothing() {
        val samples = forestSession(status = null, cpuTemp = null)
        fun withLegacy(share: Map<String, Double>?) = SessionVerdict.compute(SessionVerdict.Input(samples, gpuTopMhz = 900, legacyShare = share))
        assertEquals(SessionVerdict.NONE, withLegacy(null).cls)

        val r = withLegacy(mapOf("cpu" to 0.1, "gpu" to 0.46, "thermal" to 0.88))
        assertEquals(SessionVerdict.THERMAL_GPU, r.cls)
        assertTrue(r.confident)
        assertEquals(0.46, r.confidence, 1e-9)
        val verdict = r.properties()["perf_verdict"] as Map<*, *>
        assertEquals(SessionVerdict.SOURCE_LEGACY, verdict["source"])
        assertEquals(SessionVerdict.THERMAL_GPU, verdict["class"])

        val gpuOnly = withLegacy(mapOf("gpu" to 0.9, "thermal" to 0.1))
        assertEquals(SessionVerdict.GPU, gpuOnly.cls)
        assertEquals(0.6, gpuOnly.confidence, 1e-9)
        assertEquals(SessionVerdict.NONE, withLegacy(mapOf("gpu" to 0.3, "thermal" to 0.9)).cls)
    }

    @Test
    fun confidentSamplerVerdictOutranksTheLegacyClassifier() {
        val r = SessionVerdict.compute(
            SessionVerdict.Input(gameplay { base(it, gpu = 92, cpu = 40, hot = 60, gpuMhz = 900) }, gpuTopMhz = 900, legacyShare = mapOf("gpu" to 0.5, "thermal" to 0.9)),
        )
        assertEquals(SessionVerdict.GPU, r.cls)
        assertNull(r.source)
    }

    @Test
    fun propertiesCarryTheSpecKeys() {
        val props = SessionVerdict.compute(
            SessionVerdict.Input(gameplay { base(it, gpu = 92, cpu = 40) }, stutterShare = 0.0213),
        ).properties()
        assertEquals(setOf("perf_verdict", "stutter_share", "throttle_signal"), props.keys)
        val verdict = props["perf_verdict"] as Map<*, *>
        assertEquals(SessionVerdict.GPU, verdict["class"])
        assertEquals(true, verdict["confident"])
        assertEquals("none", verdict["card"])
        assertEquals(0.0213, props["stutter_share"])
    }

    @Test
    fun stutterShareCountsFramesOverThreeTimesTheMedian() {
        val hist = LongArray(201)
        hist[16] = 97
        hist[40] = 1
        hist[60] = 2
        assertEquals(16, SessionVerdict.histogramMedianMs(hist))
        assertEquals(0.02, SessionVerdict.stutterShare(hist)!!, 1e-9)
        assertNull(SessionVerdict.stutterShare(LongArray(201)))
    }

    @Test
    fun bufferStaysBoundedOverThreeHours() {
        val buffer = SessionSampleBuffer(clusterCount = 2)
        for (t in 0 until 3 * 3600) buffer.add(base(t, hot = 100, user = 24, sys = 76, vol = 40_000))
        assertTrue(buffer.size <= 3600)
        assertEquals(4, buffer.keepEvery)
        val last = buffer.toList().last()
        assertEquals(100, last.hotPct)
        assertEquals(76, last.hotSysTicks)
        assertEquals(Short.MAX_VALUE.toInt(), last.hotVolPerSec)
        assertEquals('R', last.hotState)
        assertEquals(3000, last.clusterMaxMhz!![1])
        assertNull(last.skinTempC)
    }

    private fun computeLoad(samples: List<SessionSample>, fpsCap: Int = 0, refreshHz: Double = 0.0) =
        SessionVerdict.compute(SessionVerdict.Input(samples = samples, fpsCap = fpsCap, refreshHz = refreshHz))

    @Test
    fun gpuWorkPerFrameIsBusyTimesClockOverFps() {
        assertEquals(0.95 * 700 / 57.0, SessionVerdict.gpuWorkPerFrame(95.0, 700.0, 57.0)!!, 1e-9)
        assertNull(SessionVerdict.gpuWorkPerFrame(95.0, null, 57.0))
        assertNull(SessionVerdict.gpuWorkPerFrame(95.0, 0.0, 57.0))
        assertNull(SessionVerdict.gpuWorkPerFrame(95.0, 700.0, 0.0))
    }

    @Test
    fun workPerFrameStaysPutWhenHeatMovesClockAndFpsTogether() {
        val cool = SessionVerdict.gpuWorkPerFrame(95.0, 940.0, 60.0)!!
        val hot = SessionVerdict.gpuWorkPerFrame(95.0, 660.0, 42.0)!!
        assertEquals(1.0, hot / cool, 0.03)
    }

    @Test
    fun dualBoundSessionKeepsItsClassAndReportsBothAsTheCeiling() {
        val r = computeLoad(
            gameplay(900) { base(it, fps = 57f, gpu = 95, gpuMhz = 700, cpu = 50, hot = 82) },
            fpsCap = 60,
            refreshHz = 60.0,
        )
        assertEquals(SessionVerdict.GPU, r.cls)
        val load = r.load!!
        assertEquals(SessionVerdict.CEILING_BOTH, load.fpsCeilingReason)
        assertEquals(95, load.gpuBusyP95)
        assertEquals(82, load.hotThreadP95)
        assertEquals(0.95 * 700 / 57.0, load.gpuWorkPerFrame!!, 1e-6)
        val verdict = r.properties()["perf_verdict"] as Map<*, *>
        assertEquals("both", verdict["fps_ceiling_reason"])
        assertEquals(57.0, verdict["fps_p10"])
        assertEquals(60.0, verdict["refresh_hz"])
        assertEquals(95, verdict["gpu_busy_p95"])
        assertEquals(82, verdict["hot_thread_p95"])
        assertEquals(load.windows.size, (verdict["gpu_work_by_5min"] as List<*>).size)
        assertEquals(700, (verdict["gpu_clock_mhz_by_5min"] as List<*>).first())
    }

    @Test
    fun ceilingReasons() {
        fun reason(fps: Double, gpu: Int?, hot: Int?, cap: Int = 60, refresh: Double = 120.0, ceiling: Double? = fps) =
            SessionVerdict.fpsCeilingReason(fps, ceiling, cap, refresh, gpu, hot)
        assertEquals(SessionVerdict.CEILING_CAP, reason(59.0, gpu = 95, hot = 85))
        assertEquals(SessionVerdict.CEILING_CAP, reason(119.0, gpu = 40, hot = 40, cap = 0))
        assertEquals(SessionVerdict.CEILING_BOTH, reason(56.0, gpu = 92, hot = 80))
        assertEquals(SessionVerdict.CEILING_MAIN_THREAD, reason(40.0, gpu = 60, hot = 90))
        assertEquals(SessionVerdict.CEILING_GPU, reason(40.0, gpu = 97, hot = 50))
        assertEquals(SessionVerdict.CEILING_NONE, reason(40.0, gpu = 70, hot = 50))
        assertEquals(SessionVerdict.CEILING_NONE, reason(40.0, gpu = null, hot = null))
        // Both saturated but fps far below the cap and its own ceiling: a stall, not a steady dual bound.
        assertEquals(SessionVerdict.CEILING_GPU, reason(30.0, gpu = 92, hot = 80, ceiling = 50.0))
    }

    @Test
    fun capLimitedSessionReportsTheCap() {
        val r = computeLoad(gameplay(600) { base(it, fps = 60f, gpu = 60, gpuMhz = 500, hot = 50) }, fpsCap = 60, refreshHz = 120.0)
        assertEquals(SessionVerdict.CEILING_CAP, r.load!!.fpsCeilingReason)
    }

    @Test
    fun gpuOnlySessionReportsGpu() {
        val r = computeLoad(gameplay(600) { base(it, fps = 40f, gpu = 97, gpuMhz = 800, cpu = 30, hot = 50) }, refreshHz = 60.0)
        assertEquals(SessionVerdict.GPU, r.cls)
        assertEquals(SessionVerdict.CEILING_GPU, r.load!!.fpsCeilingReason)
    }

    @Test
    fun noGpuClockLeavesWorkOut() {
        val r = computeLoad(gameplay(600) { base(it, fps = 40f, gpu = 97, gpuMhz = null) }, refreshHz = 60.0)
        assertNull(r.load!!.gpuWorkPerFrame)
        assertTrue(r.load!!.windows.isEmpty())
        val verdict = r.properties()["perf_verdict"] as Map<*, *>
        assertFalse(verdict.containsKey("gpu_work_by_5min"))
    }
}
