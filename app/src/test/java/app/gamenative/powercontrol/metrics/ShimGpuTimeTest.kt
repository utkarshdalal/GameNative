package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimGpuTimeTest {
    private val s = 1_000_000_000L

    private fun rec(role: String, busy: Long, wall: Long, presents: Long, updated: Long, devices: Int = 1) =
        ShimGpuTime.Record(role, busy, wall, presents, 0L, updated, devices, 10L)

    @Test
    fun parsesV1LineWithAndWithoutTimed() {
        val r = ShimGpuTime.parse("v1 guest 123 456 7 1 999 2 40\n")!!
        assertEquals("guest", r.role)
        assertEquals(123L, r.busyNs)
        assertEquals(456L, r.wallNs)
        assertEquals(7L, r.presents)
        assertEquals(1L, r.untimed)
        assertEquals(999L, r.updatedNs)
        assertEquals(2, r.devices)
        assertEquals(40L, r.timed)
        assertNull(ShimGpuTime.parse("v1 gamescope 1 2 3 4 5 1")!!.timed)
    }

    @Test
    fun rejectsOtherVersionsAndGarbage() {
        assertNull(ShimGpuTime.parse("v2 guest 1 2 3 4 5 1 1"))
        assertNull(ShimGpuTime.parse("v1 guest 1 2 3"))
        assertNull(ShimGpuTime.parse("v1 guest a 2 3 4 5 1"))
        assertNull(ShimGpuTime.parse("v1 guest -1 2 3 4 5 1"))
        assertNull(ShimGpuTime.parse(""))
    }

    @Test
    fun firstSightGivesNoReadingThenDeltas() {
        val now = 10 * s
        val first = ShimGpuTime.step(emptyMap(), mapOf("100" to rec("guest", 0, 0, 0, now)), now)
        assertNull(first.percent)
        assertNull(first.frameMs)
        // 1 s later: 400 ms busy over 1 s wall and 50 presents -> 40 %, 8 ms per frame.
        val second = ShimGpuTime.step(
            first.states,
            mapOf("100" to rec("guest", 400_000_000, s, 50, now + s)),
            now + s,
        )
        assertEquals(40, second.percent)
        assertEquals(8f, second.frameMs!!, 0.001f)
        assertTrue(second.guestUpdated)
    }

    @Test
    fun unchangedFileKeepsLastWindowWithoutNewSample() {
        val now = 10 * s
        val a = ShimGpuTime.step(emptyMap(), mapOf("1" to rec("guest", 0, 0, 0, now)), now)
        val b = ShimGpuTime.step(a.states, mapOf("1" to rec("guest", s / 2, s, 60, now + s)), now + s)
        val c = ShimGpuTime.step(b.states, mapOf("1" to rec("guest", s / 2, s, 60, now + s)), now + s + s / 2)
        assertEquals(50, c.percent)
        assertFalse(c.guestUpdated)
    }

    @Test
    fun processesAreSummedAndCapped() {
        val now = 10 * s
        val start = mapOf("1" to rec("guest", 0, 0, 0, now), "2" to rec("gamescope", 0, 0, 0, now))
        val a = ShimGpuTime.step(emptyMap(), start, now)
        val b = ShimGpuTime.step(
            a.states,
            mapOf("1" to rec("guest", 600_000_000, s, 30, now + s), "2" to rec("gamescope", 100_000_000, s, 0, now + s)),
            now + s,
        )
        assertEquals(70, b.percent)
        // Only the guest's presents count toward GPU time per frame.
        assertEquals(20f, b.frameMs!!, 0.001f)
        assertEquals(2, b.devices)
        val c = ShimGpuTime.step(
            b.states,
            mapOf("1" to rec("guest", 1_500_000_000, 2 * s, 60, now + 2 * s), "2" to rec("gamescope", 400_000_000, 2 * s, 0, now + 2 * s)),
            now + 2 * s,
        )
        assertEquals(100, c.percent)
    }

    @Test
    fun staleFilesAreIgnored() {
        val now = 10 * s
        val a = ShimGpuTime.step(emptyMap(), mapOf("1" to rec("guest", 0, 0, 0, now)), now)
        val b = ShimGpuTime.step(a.states, mapOf("1" to rec("guest", s / 4, s, 10, now + s)), now + s)
        assertEquals(25, b.percent)
        val c = ShimGpuTime.step(b.states, mapOf("1" to rec("guest", s / 4, s, 10, now + s)), now + 4 * s)
        assertNull(c.percent)
        assertNull(c.frameMs)
        assertEquals(0, c.devices)
    }

    @Test
    fun countersGoingBackwardsRestartThePid() {
        val now = 10 * s
        val a = ShimGpuTime.step(emptyMap(), mapOf("1" to rec("guest", 5 * s, 9 * s, 500, now)), now)
        val b = ShimGpuTime.step(a.states, mapOf("1" to rec("guest", 0, s, 1, now + s)), now + s)
        assertNull(b.percent)
        val c = ShimGpuTime.step(b.states, mapOf("1" to rec("guest", s / 10, 2 * s, 11, now + 2 * s)), now + 2 * s)
        assertEquals(10, c.percent)
    }

    @Test
    fun coverageIsTimedOverAllSubmits() {
        val r = listOf(
            ShimGpuTime.Record("guest", 0, 0, 0, 5, 0, 1, 95),
            ShimGpuTime.Record("gamescope", 0, 0, 0, 0, 0, 1, 100),
        )
        assertEquals(195.0 / 200.0, ShimGpuTime.coverage(r)!!, 1e-9)
        assertNull(ShimGpuTime.coverage(emptyList()))
    }

    @Test
    fun bucketGpuTimeKeys() {
        assertTrue(PowerTelemetry.gpuTimeExtras(emptyList()).isEmpty())
        val m = PowerTelemetry.gpuTimeExtras((1..20).map { it.toFloat() })
        assertEquals(10.5, m["gpu_ms_avg"])
        assertEquals(19.0, m["gpu_ms_p95"])
        assertNotNull(PowerTelemetry.gpuTimeExtras(listOf(7.25f))["gpu_ms_p95"])
    }
}
