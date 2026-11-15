package app.gamenative.optimize

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizeServerSamplesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var roots: PathRoots
    private lateinit var ini: File

    // The kept step left shadows at 1; a step that raises quality sets them to 2.
    private val kept = "[SystemSettings]\r\nr.ShadowQuality=1\r\n"

    @Before
    fun setUp() {
        val home = tmp.newFolder("home")
        roots = PathRoots(null, null, home)
        ini = File(home, "Saved/Config/Windows/Engine.ini").apply {
            parentFile!!.mkdirs()
            writeText(kept)
        }
    }

    private fun stepUp(recovery: Boolean) = Rung.parse(
        JSONObject(
            """{"rung_id":"cfg.game.balanced.b.s8","version":"h8","step":8,"relative":${!recovery},"recovery":$recovery,"levers":[
              {"lever_type":"engine_file","payload":{"path":"{userProfilePath}/Saved/Config/Windows/Engine.ini","format":"ini","ops":[
                {"op":"set","section":"SystemSettings","key":"r.ShadowQuality","value":"2",
                 "ladder":["0","0","0","1","1","1","2","2","2","3","3","3"],"lower_is":"less"}]}}]}""",
        ),
    )!!

    @Test
    fun `a recovery rung writes its values as sent and comes off like any rung`() {
        val rung = stepUp(recovery = true)
        assertTrue(rung.recovery)
        assertFalse(rung.relative)
        val lever = rung.levers.single() as Lever.EngineFile
        val count = OptimizeApplier.OpCount()
        val record = OptimizeApplier.applyFile(0, lever, roots, tmp.newFolder("rung"), rung.relative, count)!!
        assertEquals(1, count.applied)
        assertTrue(ini.readText().contains("r.ShadowQuality=2"))

        OptimizeReverter.revertFile(record)
        assertEquals(kept, ini.readText())
    }

    @Test
    fun `a relative rung still never raises a value`() {
        val rung = stepUp(recovery = false)
        assertTrue(rung.relative)
        assertFalse(rung.recovery)
        val count = OptimizeApplier.OpCount()
        assertNull(OptimizeApplier.applyFile(0, rung.levers.single() as Lever.EngineFile, roots, tmp.newFolder("rung"), rung.relative, count))
        assertEquals(1, count.skipped)
        assertEquals(kept, ini.readText())
        // A server that marks a recovery step relative by mistake still gets it written as sent.
        val both = Rung.parse(JSONObject(rung.raw.toString()).put("recovery", true))!!
        assertFalse(both.relative)
    }

    @Test
    fun `a recovery step may reuse another phase's step number`() {
        val ladder = ProfileLadder(tried = listOf(TriedRung("cfg.game.balanced.a.s8", "h1", OptimizeJudge.NO_CHANGE)))
        assertEquals("step 8 already tried", ladder.repeatReason(stepUp(recovery = false)))
        assertNull(ladder.repeatReason(stepUp(recovery = true)))
        // Its own id and version still count.
        val again = ladder.copy(tried = ladder.tried + TriedRung("cfg.game.balanced.b.s8", "h8", OptimizeJudge.WORSE))
        assertTrue(again.repeats(stepUp(recovery = true)))
    }

    @Test
    fun `a declined cap ask is no tried rung`() {
        val ladder = ProfileLadder(state = LadderState.PAUSED, tried = listOf(TriedRung("cfg.game.balanced.b.s6", "h6", OptimizeJudge.HELPED)))
        val state = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED).withLadder(OptimizeProfile.BALANCED, ladder)
        val declined = OptimizeCards.capDeclined(state, 1_000L)
        assertEquals(ladder.tried, declined.ladder(OptimizeProfile.BALANCED).tried)
        assertEquals(1_000L + OptimizeCards.CAP_SNOOZE_MS, declined.capSnoozedUntil)
        val cap = Rung.parse(JSONObject("""{"rung_id":"cap.40","version":"c40","is_fps_cap":true,"ask_first":true,"levers":[]}"""))!!
        assertEquals(OptimizeCards.UnaskedCap.END, OptimizeCards.unaskedCap(declined, cap, 2_000L))
    }

    @Test
    fun `outcome sessions carry what the server judge reads`() {
        val crashed = Metrics(
            gameplaySec = 95,
            steadyFps = 41.0,
            cls = "gpu",
            confident = true,
            symptom = "low_fps",
            exitReason = "processes_exited",
            windowSeen = false,
            cpuTempMax = 83,
            gpuTempMax = 77,
            thermalPeak = 3,
            crashKind = "exception",
            crashCode = "c0000005",
            crashHash = "ab12",
        )
        val json = crashed.toReportJson()
        assertEquals("processes_exited", json.getString("exit_reason"))
        assertFalse(json.getBoolean("window_seen"))
        assertEquals("exception", json.getString("crash_kind"))
        assertEquals("c0000005", json.getString("crash_code"))
        assertEquals("ab12", json.getString("crash_hash"))
        assertEquals(83, json.getInt("cpu_temp_max"))
        assertEquals(77, json.getInt("gpu_temp_max"))
        assertEquals(3, json.getInt("thermal_peak"))
        assertTrue(json.getBoolean("confident"))
        assertEquals("low_fps", json.getString("symptom"))
        assertEquals(crashed.copy(), Metrics.fromJson(json)!!.copy(gpuWorkPerFrame = null))
        // A plain session still says its window was seen and leaves out what it did not measure.
        val plain = Metrics(gameplaySec = 900).toReportJson()
        assertTrue(plain.getBoolean("window_seen"))
        listOf("exit_reason", "crash_kind", "crash_code", "crash_hash", "cpu_temp_max", "thermal_peak", "confident").forEach { assertFalse(it, plain.has(it)) }

        val limits = OptimizeServer.putLimits(JSONObject(), 40, 0)
        assertEquals(40, limits.getInt("fps_limit"))
        assertEquals(0, limits.getInt("user_fps_cap"))
        assertFalse(OptimizeServer.putLimits(JSONObject(), null, null).has("fps_limit"))
    }

    @Test
    fun `no_samples leaves the first card to the local verdict`() {
        val card = OptimizeCard(OptimizeCard.FIRST, 1L, "Game", profile = OptimizeProfile.PERFORMANCE, cls = "gpu")
        val noSamples = ServerVerdict.parse(JSONObject().put("show_card", false).put("block_reason", "no_samples").put("source", "client"))!!
        assertEquals(card, OptimizeServer.firstDecision(null, card, noSamples, null).card)
        assertEquals(OptimizeCards.SUPPRESSED_SHORT, OptimizeServer.firstDecision(OptimizeCards.SUPPRESSED_SHORT, card, noSamples, null).block)
        val fromSamples = ServerVerdict.parse(JSONObject().put("show_card", false).put("block_reason", "not_confident").put("source", "samples"))!!
        assertEquals("samples", fromSamples.source)
        assertNull(OptimizeServer.firstDecision(null, card, fromSamples, null).card)
        assertEquals("not_confident", OptimizeServer.firstDecision(null, card, fromSamples, null).block)
    }
}
