package app.gamenative.utils

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSeriesTest {

    private fun record(t: Int) = SessionSample(
        t = t,
        fps = 44.96f + (t % 7),
        frames = 45,
        stutterFrames = t % 3,
        refreshMultipleFrames = t % 5,
        gpuBusy = 70 + t % 29,
        gpuMhz = 719,
        gpuMaxMhz = 840,
        cpuTotal = 20 + t % 40,
        coreMax = 50 + t % 50,
        clusterCurMhz = intArrayOf(1800, 2400, 3000),
        clusterMaxMhz = intArrayOf(2016, 2803, 3302),
        thermalStatus = if (t > 900) 2 else 0,
        cpuTempC = 55 + t / 100,
        skinTempC = 36 + t / 300,
        hotPct = 50 + t % 49,
        hotUserTicks = 40 + t % 55,
        hotSysTicks = t % 10,
        hotState = if (t % 5 == 0) 'S' else 'R',
        hotCore = 4 + t % 4,
        hotVolPerSec = 100 + t % 2900,
        hotInvolPerSec = 10 + t % 390,
        wineserverPct = t % 12,
        availMb = 3200 - t / 10,
        lowMemory = t == 3,
        trimEvent = t == 4,
        capActive = t == 5,
    )

    @Test
    fun `records serialize as one column per field`() {
        val rows = listOf(record(0), record(1), SessionSample(t = 2, fps = 30f), record(3), record(4), record(5))
        val o = SessionSeries.toJson(rows, 1)
        assertEquals(1, o.getInt("interval_sec"))
        assertEquals("[0,1,2,3,4,5]", o.getJSONArray("t").toString())
        assertEquals(45.0, o.getJSONArray("fps").getDouble(0), 1e-9)
        assertEquals(30.0, o.getJSONArray("fps").getDouble(2), 1e-9)
        assertEquals(6, o.getJSONArray("frames").length())
        // An unsampled field of one record is null; the server reads it as not sampled.
        assertTrue(o.getJSONArray("gpu_busy").isNull(2))
        assertEquals(71, o.getJSONArray("gpu_busy").getInt(1))
        assertEquals("[2016,2803,3302]", o.getJSONArray("cluster_max_mhz").getJSONArray(0).toString())
        assertTrue(o.getJSONArray("cluster_max_mhz").isNull(2))
        assertEquals("SR-RRS", o.getString("hot_state"))
        assertEquals("[0,0,0,1,2,4]", o.getJSONArray("flags").toString())
        listOf(
            "stutter_frames", "refresh_multiple_frames", "gpu_mhz", "gpu_max_mhz", "cpu_total", "core_max", "thermal_status", "cpu_temp_c",
            "skin_temp_c", "hot_pct", "hot_user_ticks", "hot_sys_ticks", "hot_core", "hot_vol_ps", "hot_invol_ps", "wineserver_pct", "avail_mb",
        ).forEach { assertEquals(it, 6, o.getJSONArray(it).length()) }
    }

    @Test
    fun `a field no record has is left out`() {
        val o = SessionSeries.toJson(listOf(SessionSample(t = 0, fps = 60f), SessionSample(t = 1, fps = 59.94f)), 2)
        assertEquals(2, o.getInt("interval_sec"))
        assertEquals(59.9, o.getJSONArray("fps").getDouble(1), 1e-9)
        listOf("gpu_busy", "cluster_max_mhz", "hot_state", "avail_mb", "cpu_temp_c").forEach { assertFalse(it, o.has(it)) }
        assertTrue(o.has("frames") && o.has("flags"))
    }

    @Test
    fun `a long series is thinned evenly and keeps its seconds`() {
        val rows = (0 until 10_000).map { SessionSample(t = it, fps = 50f) }
        val o = SessionSeries.toJson(rows, 1)
        val t = o.getJSONArray("t")
        assertTrue(t.length() <= SessionSeries.MAX_SAMPLES)
        assertEquals(3334, t.length())
        assertEquals(3, o.getInt("interval_sec"))
        assertEquals(0, t.getInt(0))
        assertEquals(3, t.getInt(1))
        assertEquals(t.length(), o.getJSONArray("fps").length())
        assertEquals(t.length(), o.getJSONArray("flags").length())
        // A buffer that already keeps one record in two doubles again.
        assertEquals(4, SessionSeries.toJson((0 until 4001).map { SessionSample(t = it * 2, fps = 50f) }, 2).getInt("interval_sec"))
        assertEquals(1, SessionSeries.toJson(rows.take(SessionSeries.MAX_SAMPLES), 1).getInt("interval_sec"))
    }

    @Test
    fun `a full buffer stays within the size bound`() {
        val buffer = SessionSampleBuffer(3)
        repeat(7200) { buffer.add(record(it)) }
        val series = SessionSeries(buffer, 7080, intArrayOf(2016, 2803, 3302), 840, 2, false, mapOf("gpu" to 0.512, "thermal" to 0.1))
        val metrics = JSONObject()
        series.putInto(metrics)
        val samples = metrics.getJSONObject("samples")
        assertTrue(samples.getJSONArray("t").length() <= SessionSeries.MAX_SAMPLES)
        assertEquals(buffer.keepEvery, samples.getInt("interval_sec"))
        val bytes = metrics.toString().toByteArray()
        assertTrue("${bytes.size} bytes", bytes.size < 400_000)
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.size()
        assertTrue("$gz bytes gzipped", gz < bytes.size / 3)
        assertEquals("[2016,2803,3302]", metrics.getJSONArray("cluster_cpuinfo_max_mhz").toString())
        assertEquals(840, metrics.getInt("gpu_top_mhz"))
        assertEquals(2, metrics.getInt("trim_events"))
        assertFalse(metrics.getBoolean("design_cap_detected"))
        assertEquals(0.51, metrics.getJSONObject("legacy_share").getDouble("gpu"), 1e-9)
    }
}
