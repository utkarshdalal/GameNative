package app.gamenative.optimize

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizeStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rungJson = """
        {"rung_id":"witcher3_grass","version":"a1b2c3d4","profile":"balanced","class":"gpu","lever_classes":["gpu_pixel"],
         "order":2,"remaining":3,"is_fps_cap":false,"outcome":"fps","visual_cost_id":"vc_less_grass_view_distance",
         "visual_cost_en":"less grass","expires_at":"2030-01-01T00:00:00Z",
         "levers":[
          {"lever_type":"engine_file","payload":{"path":"{documentsPath}/The Witcher 3/user.settings","format":"ini","encoding":"UTF16",
            "create_if_missing":false,"ops":[{"op":"set","section":"Rendering","key":"GrassDensity","value":"800"},{"op":"delete","section":"Rendering","key":"Foo"}]}},
          {"lever_type":"registry","payload":{"hive":"HKCU","key":"Software\\SKS\\TheForest","values":[{"name":"X","type":"dword","data":"3"},{"name":"Y","delete":true}]}},
          {"lever_type":"container","payload":{"set":[{"key":"fpsLimit","value":"45"},{"key":"envVars.GN_X","value":"1"}]}},
          {"lever_type":"teleport","payload":{}}
         ]}
    """.trimIndent()

    @Test
    fun rungParsesEveryLeverType() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        assertEquals("witcher3_grass", rung.rungId)
        assertEquals("a1b2c3d4", rung.version)
        assertEquals(2, rung.order)
        assertEquals(listOf("gpu_pixel"), rung.leverClasses)
        assertFalse(rung.isExpired(System.currentTimeMillis()))
        val file = rung.levers[0] as Lever.EngineFile
        assertEquals("UTF16", file.encoding)
        assertEquals(FileOp("set", "Rendering", "GrassDensity", "800"), file.ops[0])
        assertTrue(file.ops[1].isDelete)
        val reg = rung.levers[1] as Lever.Registry
        assertEquals("Software\\SKS\\TheForest", reg.key)
        assertTrue(reg.values[1].delete)
        val container = rung.levers[2] as Lever.ContainerSet
        assertEquals("fpsLimit" to "45", container.set[0])
        assertTrue(rung.levers[3] is Lever.Unknown)
    }

    @Test
    fun numericVersionReadsAsText() {
        val rung = Rung.parse(JSONObject("{\"rung_id\":\"r\",\"version\":7,\"levers\":[]}"))!!
        assertEquals("7", rung.version)
    }

    @Test
    fun nextRungResponseParses() {
        val response = NextRungResponse.parse(JSONObject("{\"status\":\"exhausted\",\"message_id\":\"m1\",\"rung\":null}"))
        assertEquals("exhausted", response.status)
        assertEquals("m1", response.messageId)
        assertNull(response.rung)
    }

    @Test
    fun stateRoundTripsThroughTheStore() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        val baseline = Metrics(gameplaySec = 900, steadyFps = 41.5, stutterShare = 0.012, throttleOnsetSec = 420, cls = "gpu", confidence = 0.8, exitReason = "quick_menu")
        val state = OptimizeLocalState(
            profile = OptimizeProfile.POWER,
            started = true,
            kept = listOf(
                KeptRung("k1", "12", 1, listOf("cpu"), 1000L, listOf(JSONObject().put("type", "container")), baseline, "vc_fps_held", "fps held"),
            ),
            pending = PendingRung(rung, 2000L, baseline, 1, listOf(JSONObject().put("type", "registry")), revert = "worse"),
            tried = listOf(TriedRung("t1", "3", OptimizeJudge.NO_CHANGE)),
            bad = listOf("b1"),
            outcomeQueue = listOf(JSONObject().put("outcome_id", "o1")),
            lastCard = JSONObject().put("result", "helped"),
            baselineOverride = baseline.copy(gameplaySec = 1000),
            lastStatus = "rung",
        )
        val store = OptimizeStore(tmp.root)
        store.write(state)
        assertTrue(File(tmp.root, ".gamenative/optimize/state.json").isFile)
        val back = store.read()

        assertEquals(OptimizeProfile.POWER, back.profile)
        assertTrue(back.started)
        assertEquals(1, back.kept.size)
        val kept = back.kept[0]
        assertEquals("k1", kept.rungId)
        assertEquals("12", kept.version)
        assertEquals(listOf("cpu"), kept.leverClasses)
        assertEquals("container", kept.levers[0].getString("type"))
        assertEquals(baseline, kept.baseline)
        assertEquals("vc_fps_held", kept.visualCostId)
        val pending = back.pending!!
        assertEquals("witcher3_grass", pending.rung.rungId)
        assertEquals(4, pending.rung.levers.size)
        assertEquals(1, pending.shortSessions)
        assertEquals("worse", pending.revert)
        assertEquals(baseline, pending.baseline)
        assertEquals(listOf(TriedRung("t1", "3", OptimizeJudge.NO_CHANGE)), back.tried)
        assertEquals(listOf("b1"), back.bad)
        assertEquals("o1", back.outcomeQueue[0].getString("outcome_id"))
        assertEquals("helped", back.lastCard!!.getString("result"))
        assertEquals(1000, back.baselineOverride!!.gameplaySec)
        assertEquals("rung", back.lastStatus)
        assertTrue(back.hasChanges)
    }

    @Test
    fun missingOrBrokenStateIsTheDefault() {
        val store = OptimizeStore(tmp.root)
        assertEquals(OptimizeProfile.OFF, store.read().profile)
        store.dir.mkdirs()
        store.file.writeText("{not json")
        assertEquals(OptimizeLocalState(), store.read())
    }

    @Test
    fun metricsComeFromTheRunRecord() {
        val run = JSONObject()
            .put("session_length", 1500)
            .put("avg_fps", 44.5)
            .put("exit_reason", "quick_menu")
            .put("stutter_share", 0.0123)
            .put("throttle_onset_s", 600)
            .put("main_window_class", "game.exe")
            .put("perf_verdict", JSONObject().put("class", "gpu").put("confidence", 0.7).put("symptom", "low_fps").put("gameplay_s", 1400).put("proc_stat", "real"))
        val m = Metrics.fromRun(run)
        assertEquals(1400, m.gameplaySec)
        assertEquals(44.5, m.steadyFps!!, 0.0)
        assertEquals(0.0123, m.stutterShare!!, 0.0)
        assertEquals(600, m.throttleOnsetSec)
        assertEquals("gpu", m.cls)
        assertEquals("low_fps", m.symptom)
        assertTrue(m.windowSeen)
        assertEquals(true, m.procStatReal)

        val bare = Metrics.fromRun(JSONObject().put("session_length", 80).put("avg_fps", 0.0).put("exit_reason", "guest_terminated"))
        assertEquals(80, bare.gameplaySec)
        assertNull(bare.steadyFps)
        assertFalse(bare.windowSeen)
        assertNull(bare.toVerdictJson())
    }

    @Test
    fun statusFollowsTheState() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        assertEquals(OptimizeStatus.Off, OptimizeStatus.of(OptimizeLocalState()))
        assertEquals(OptimizeStatus.NotStarted, OptimizeStatus.of(OptimizeLocalState(profile = OptimizeProfile.BALANCED)))
        assertEquals(OptimizeStatus.Measuring, OptimizeStatus.of(OptimizeLocalState(profile = OptimizeProfile.BALANCED, started = true)))
        val trying = OptimizeLocalState(profile = OptimizeProfile.BALANCED, started = true, pending = PendingRung(rung, 0L, null, 0, emptyList()))
        assertEquals(OptimizeStatus.Trying("vc_less_grass_view_distance", "less grass"), OptimizeStatus.of(trying))
        val kept = KeptRung("k", "1", 1, emptyList(), 0L, emptyList(), null)
        assertEquals(OptimizeStatus.Kept(1), OptimizeStatus.of(OptimizeLocalState(profile = OptimizeProfile.STABILITY, started = true, kept = listOf(kept))))
    }

    @Test
    fun registryValuesEncodeLikeWine() {
        assertEquals("dword:00000003", OptimizeFiles.encodeRegistryValue("dword", "3"))
        assertEquals("dword:ffffffff", OptimizeFiles.encodeRegistryValue("dword", "-1"))
        assertEquals("dword:000000ff", OptimizeFiles.encodeRegistryValue("dword", "0xff"))
        assertEquals("\"High\"", OptimizeFiles.encodeRegistryValue("sz", "High"))
        assertEquals("hex:00,00,00,00,00,00,f0,3f", OptimizeFiles.encodeRegistryValue("binary_f64le", "1.0"))
        assertEquals("hex(b):05,00,00,00,00,00,00,00", OptimizeFiles.encodeRegistryValue("qword", "5"))
    }
}
