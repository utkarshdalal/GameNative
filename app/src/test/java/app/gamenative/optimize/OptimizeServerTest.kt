package app.gamenative.optimize

import app.gamenative.utils.SessionSample
import app.gamenative.utils.SessionVerdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeServerTest {

    private val card = OptimizeCard(
        OptimizeCard.FIRST, 1L, "Game", profile = OptimizeProfile.BALANCED, cls = "gpu",
    )

    private fun verdict(show: Boolean?, kind: String? = null, profile: String? = null, cls: String? = "thermal_gpu", block: String? = null) =
        ServerVerdict.parse(
            JSONObject().apply {
                put("class", cls ?: JSONObject.NULL)
                put("confidence", 0.9)
                show?.let { put("show_card", it) }
                kind?.let { put("card_kind", it) }
                profile?.let { put("suggested_profile", it) }
                block?.let { put("block_reason", it) }
            },
        )

    @Test
    fun `without a server verdict the local decision stands`() {
        assertEquals(card, OptimizeServer.firstDecision(null, card, null, null).card)
        val blocked = OptimizeServer.firstDecision(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, card, null, null)
        assertNull(blocked.card)
        assertEquals(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, blocked.block)
        // A verdict that does not say show_card leaves it to the app too.
        assertEquals(card, OptimizeServer.firstDecision(null, card, verdict(show = null), null).card)
    }

    @Test
    fun `the server verdict overrules the local one both ways`() {
        val shown = OptimizeServer.firstDecision(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, card, verdict(true, profile = "power"), null).card!!
        assertEquals("thermal_gpu", shown.cls)
        assertEquals(OptimizeProfile.POWER, shown.profile)
        val hidden = OptimizeServer.firstDecision(null, card, verdict(false, block = "short"), null)
        assertNull(hidden.card)
        assertEquals("short", hidden.block)
        assertEquals(OptimizeServer.BLOCK_SERVER, OptimizeServer.firstDecision(null, card, verdict(false), null).block)
    }

    @Test
    fun `a server cap card needs a cap value`() {
        assertEquals(OptimizeCard.CAP, OptimizeServer.firstDecision(null, card, verdict(true, kind = "cap"), 40).card!!.kind)
        assertEquals(40, OptimizeServer.firstDecision(null, card, verdict(true, kind = "cap"), 40).card!!.capFps)
        assertEquals(OptimizeCard.FIRST, OptimizeServer.firstDecision(null, card, verdict(true, kind = "cap"), null).card!!.kind)
        // A slowdown tuning cannot change leaves no card, whatever the server says.
        val spin = OptimizeServer.firstDecision(null, card, verdict(true, cls = OptimizeCards.SYNC_SPIN), null)
        assertNull(spin.card)
        assertEquals(OptimizeCards.SUPPRESSED_CLASS, spin.block)
    }

    @Test
    fun `only verdict blocks ask the server at exit`() {
        fun asks(block: String?, sec: Int = 600, hold: String? = null, asked: String? = null, rules: String? = "r1") =
            OptimizeServer.asksAtExit(block, sec, hold, asked, rules)
        assertTrue(asks(null, sec = 10))
        assertTrue(asks(OptimizeCards.SUPPRESSED_NOT_CONFIDENT))
        assertTrue(asks(OptimizeCards.SUPPRESSED_CLASS))
        // Short sessions, the user's own holds and a game already asked under this rules build never ask.
        assertFalse(asks(OptimizeCards.SUPPRESSED_SHORT))
        assertFalse(asks(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, sec = 299))
        assertFalse(asks(OptimizeCards.SUPPRESSED_NEVER))
        assertFalse(asks(OptimizeCards.SUPPRESSED_NOT_NOW))
        assertFalse(asks(OptimizeCards.SUPPRESSED_CAP_SNOOZED))
        assertFalse(asks(OptimizeCards.SUPPRESSED_CLASS, hold = OptimizeCards.SUPPRESSED_NO_RULE))
        assertFalse(asks(OptimizeCards.SUPPRESSED_CLASS, asked = "r1"))
        assertTrue(asks(OptimizeCards.SUPPRESSED_CLASS, asked = "r0"))
        assertTrue(asks(OptimizeCards.SUPPRESSED_CLASS, rules = null))
        assertFalse(asks(OptimizeCards.SUPPRESSED_CLASS, asked = OptimizeServer.UNKNOWN_RULES, rules = null))
    }

    @Test
    fun `the user's holds come before the session's verdict`() {
        val unsure = Metrics(gameplaySec = 900, confident = false, cls = "gpu")
        val hold = NoRuleHold(10_000L, "r1", "no_rule")
        assertEquals(OptimizeCards.SUPPRESSED_NO_RULE, OptimizeCards.firstCardBlock(OptimizeLocalState(noRule = hold), unsure, 1_000L, "r1"))
        assertEquals(OptimizeCards.SUPPRESSED_CAP_SNOOZED, OptimizeCards.firstCardBlock(OptimizeLocalState(capSnoozedUntil = 5_000L), unsure, 1_000L))
        assertEquals(
            OptimizeCards.SUPPRESSED_NOT_NOW,
            OptimizeCards.firstCardBlock(OptimizeLocalState(suppression = CardSuppression(5_000L, null)), unsure, 1_000L),
        )
        assertEquals(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, OptimizeCards.firstCardBlock(OptimizeLocalState(capSnoozedUntil = 5_000L), unsure, 5_000L))
        assertNull(OptimizeCards.userHold(OptimizeLocalState(), unsure, 1_000L))
        assertEquals(OptimizeCards.SUPPRESSED_NOT_NOW, OptimizeCards.userHold(OptimizeLocalState(suppression = CardSuppression(5_000L, unsure)), null, 1_000L))
    }

    @Test
    fun `the exit ask is remembered per rules build`() {
        val state = OptimizeLocalState(exitAskedRulesVersion = "r1")
        assertEquals("r1", OptimizeLocalState.fromJson(JSONObject(state.toJson().toString())).exitAskedRulesVersion)
        assertNull(OptimizeLocalState.fromJson(JSONObject(OptimizeLocalState().toJson().toString())).exitAskedRulesVersion)
    }

    @Test
    fun `server answers parse leniently`() {
        val r = NextRungResponse.parse(JSONObject().put("status", "no_rule").put("verdict_server", JSONObject().put("show_card", "true").put("suggested_profile", "nope")))
        assertEquals(true, r.verdictServer!!.showCard)
        assertNull(r.verdictServer!!.suggestedProfile)
        assertNull(NextRungResponse.parse(JSONObject().put("status", "rung")).verdictServer)
        assertNull(NextRungResponse.parse(JSONObject().put("status", "rung").put("verdict_server", "x")).verdictServer)
        val ack = OutcomeAck.parse(JSONObject().put("ok", true).put("judgement_server", JSONObject().put("result", "helped").put("deltas", JSONObject().put("fps", 4.2))))
        assertEquals(OptimizeJudge.HELPED, ack.judgement!!.result)
        assertNull(OutcomeAck.parse(JSONObject().put("ok", true).put("judgement_server", JSONObject().put("result", "great"))).judgement)
        assertNull(OutcomeAck.parse(JSONObject().put("ok", true)).judgement)
    }

    private fun rungJson(id: String) = JSONObject().put("rung_id", id).put("version", "v1").put("levers", JSONArray())

    private fun judgement(result: String, kind: String? = null) =
        ServerJudgement.parse(JSONObject().put("result", result).put("reason", "fps").apply { kind?.let { put("after_card_kind", it) } }.put("deltas", JSONObject().put("fps", 3)))!!

    private fun revertState(local: String): OptimizeLocalState {
        val pending = PendingRung(Rung.parse(rungJson("r1"))!!, 5L, Metrics(gameplaySec = 900), 0, emptyList(), revert = local)
        val ladder = ProfileLadder(LadderState.TRYING, pending = pending, tried = listOf(TriedRung("r1", "v1", local, "fps")))
        return OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(OptimizeProfile.BALANCED to ladder),
            card = OptimizeCard(OptimizeCard.WORSE, 1L, "Game", OptimizeProfile.BALANCED, rungId = "r1"),
        )
    }

    @Test
    fun `a server keep takes the place of a local revert and of its card`() {
        val after = Metrics(gameplaySec = 900, steadyFps = 50.0)
        val s = OptimizeServer.applyJudgement(revertState(OptimizeJudge.WORSE), OptimizeProfile.BALANCED, "r1", judgement(OptimizeJudge.HELPED), after)
        val ladder = s.ladder(OptimizeProfile.BALANCED)
        assertNull(ladder.pending)
        assertEquals("r1", ladder.kept.single().rungId)
        assertEquals(after, ladder.kept.single().baseline)
        assertEquals(OptimizeJudge.HELPED, ladder.tried.last().result)
        assertEquals(OptimizeCard.HELPED, s.card!!.kind)
        assertEquals(3, s.card!!.serverDeltas!!.getInt("fps"))
        assertEquals(s.card, OptimizeCard.fromJson(s.card!!.toJson()))
    }

    @Test
    fun `a server revert takes the newest kept step off`() {
        val kept = KeptRung("r1", "v1", 1, emptyList(), 5L, emptyList(), Metrics(gameplaySec = 900), rung = rungJson("r1"), before = Metrics(gameplaySec = 600))
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(OptimizeProfile.BALANCED to ProfileLadder(LadderState.DONE, kept = listOf(kept), tried = listOf(TriedRung("r1", "v1", OptimizeJudge.HELPED)))),
            card = OptimizeCard(OptimizeCard.DONE, 1L, "Game", OptimizeProfile.BALANCED, rungId = "r1"),
        )
        val s = OptimizeServer.applyJudgement(state, OptimizeProfile.BALANCED, "r1", judgement(OptimizeJudge.NO_CHANGE, kind = "no_change"), null)
        val ladder = s.ladder(OptimizeProfile.BALANCED)
        assertTrue(ladder.kept.isEmpty())
        assertEquals(OptimizeJudge.NO_CHANGE, ladder.pending!!.revert)
        assertEquals(LadderState.TRYING, ladder.state)
        assertEquals(OptimizeCard.NO_CHANGE, s.card!!.kind)
    }

    @Test
    fun `a local crash never waits for or yields to the server`() {
        val crashed = revertState(OptimizeJudge.CRASH)
        assertEquals(crashed, OptimizeServer.applyJudgement(crashed, OptimizeProfile.BALANCED, "r1", judgement(OptimizeJudge.HELPED), null))
        val other = revertState(OptimizeJudge.WORSE)
        assertEquals(other, OptimizeServer.applyJudgement(other, OptimizeProfile.BALANCED, "r9", judgement(OptimizeJudge.HELPED), null))
        assertEquals(other, OptimizeServer.applyJudgement(other, OptimizeProfile.BALANCED, "r1", judgement(OptimizeJudge.CRASH), null))
    }

    @Test
    fun `a step the server keeps without comparing is never shown as made worse`() {
        assertEquals(OptimizeCard.HELPED, OptimizeServer.afterCardKind(judgement(OptimizeJudge.NOT_COMPARABLE)))
        assertEquals(OptimizeCard.WORSE, OptimizeServer.afterCardKind(judgement(OptimizeJudge.WORSE)))
        assertEquals(OptimizeCard.NO_CHANGE, OptimizeServer.afterCardKind(judgement(OptimizeJudge.NO_CHANGE)))
    }

    private val gpuTop = 1100

    /**
     * 660 s of gameplay at 59.5 fps under a 60 cap, thermal status 5 for most of it and the GPU max clock between 60
     * and 100 % of its top: the device is hot the whole session while the frame rate holds.
     */
    private fun hotAtTargetRun(legacyShare: Map<String, Double>? = null, status: Int = 5, cpuTemp: Int = 97, gpuBusy: Int = 55): JSONObject {
        val samples = (0 until SessionVerdict.WARMUP_SEC + 660).map { t ->
            val maxMhz = (gpuTop * (0.6 + 0.4 * ((t / 37) % 5) / 4.0)).toInt()
            SessionSample(
                t = t, fps = 59.5f, frames = 60, gpuBusy = gpuBusy, gpuMhz = maxMhz, gpuMaxMhz = maxMhz, cpuTotal = 45, coreMax = 80,
                clusterMaxMhz = intArrayOf(3533, 4320), clusterCurMhz = intArrayOf(2900, 4320),
                thermalStatus = if (t < 60) 1 else status, cpuTempC = cpuTemp, hotPct = 70, hotUserTicks = 63, hotSysTicks = 7,
                hotState = 'R', hotCore = 7, hotVolPerSec = 200, hotInvolPerSec = 50, availMb = 6000,
            )
        }
        val result = SessionVerdict.compute(
            SessionVerdict.Input(
                samples = samples,
                clusterCpuinfoMaxMhz = intArrayOf(3533, 4320),
                clusterCores = listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)),
                gpuTopMhz = gpuTop,
                fpsCap = 60,
                stutterShare = 0.002,
                fpsBy5Min = listOf(59, 59, 59),
                legacyShare = legacyShare,
                refreshHz = 120.0,
            ),
        )
        return JSONObject(result.properties()).apply {
            put("avg_fps", 59.5)
            put("session_length", SessionVerdict.WARMUP_SEC + 660)
            put("fps_by_5min", JSONArray(listOf(59, 59, 59)))
            put("perf_by_5min", JSONArray(listOf(1000, 820, 800).map { JSONObject().put("gpu_clk_mhz_avg", it).put("gpu_avg", gpuBusy) }))
            put("main_window_class", "game")
            put("charging_start", false)
            put("charging", false)
        }
    }

    private fun exitBlock(session: Metrics) = OptimizeCards.firstCardBlock(OptimizeLocalState(), session, 1_000L, "r1", fpsCap = 60)

    @Test
    fun `a hot session at its target asks the server at exit`() {
        val unsure = Metrics.fromRun(hotAtTargetRun())
        assertEquals(660, unsure.gameplaySec)
        assertTrue(OptimizeServer.throttledSession(unsure))
        val unsureBlock = exitBlock(unsure)
        assertEquals(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, unsureBlock)
        // Asked even when this rules build already answered for the game.
        assertTrue(OptimizeServer.asksAtExit(unsureBlock, unsure.gameplaySec, null, "r1", "r1", throttled = true))
        assertFalse(OptimizeServer.asksAtExit(unsureBlock, unsure.gameplaySec, null, "r1", "r1", throttled = false))

        // With a confident class the session runs fine by the local rules, and still asks.
        val fine = Metrics.fromRun(hotAtTargetRun(legacyShare = mapOf("gpu" to 0.7, "thermal" to 0.1)))
        assertEquals("gpu", fine.cls)
        val fineBlock = exitBlock(fine)
        assertEquals(OptimizeCards.SUPPRESSED_RAN_FINE, fineBlock)
        assertTrue(OptimizeServer.asksAtExit(fineBlock, fine.gameplaySec, null, null, "r1", OptimizeServer.throttledSession(fine)))
        assertFalse(OptimizeServer.asksAtExit(fineBlock, fine.gameplaySec, null, null, "r1", throttled = false))
        // The user's own holds still win.
        assertFalse(OptimizeServer.asksAtExit(fineBlock, fine.gameplaySec, OptimizeCards.SUPPRESSED_NOT_NOW, null, "r1", throttled = true))
    }

    @Test
    fun `the card for a hot session at its target follows the server's answer`() {
        val fine = Metrics.fromRun(hotAtTargetRun(legacyShare = mapOf("gpu" to 0.7, "thermal" to 0.1)))
        val local = card.copy(cls = fine.cls, metrics = fine)
        val block = exitBlock(fine)
        val shown = OptimizeServer.firstDecision(block, local, verdict(true, profile = "power"), null).card!!
        assertEquals("thermal_gpu", shown.cls)
        assertEquals(OptimizeProfile.POWER, shown.profile)
        assertEquals(OptimizeServer.BLOCK_SERVER, OptimizeServer.firstDecision(block, local, verdict(false), null).block)
        // No answer, or an older server without show_card: the local block stands.
        assertEquals(OptimizeCards.SUPPRESSED_RAN_FINE, OptimizeServer.firstDecision(block, local, null, null).block)
        assertEquals(OptimizeCards.SUPPRESSED_RAN_FINE, OptimizeServer.firstDecision(block, local, verdict(show = null), null).block)
    }

    @Test
    fun `a cool or short session that ran fine does not ask`() {
        val cool = Metrics.fromRun(hotAtTargetRun(legacyShare = mapOf("gpu" to 0.7, "thermal" to 0.1), status = 0, cpuTemp = 60))
        assertFalse(OptimizeServer.throttledSession(cool))
        assertFalse(OptimizeServer.asksAtExit(exitBlock(cool), cool.gameplaySec, null, null, "r1", OptimizeServer.throttledSession(cool)))
        val short = Metrics(gameplaySec = SessionVerdict.MIN_THERMAL_GAMEPLAY_SEC - 1, throttleOnsetSec = 30)
        assertFalse(OptimizeServer.throttledSession(short))
    }

    @Test
    fun `external power is read from the run and sent with the session`() {
        assertEquals(false, Metrics.fromRun(hotAtTargetRun()).onExternalPower)
        val plugged = Metrics.fromRun(hotAtTargetRun().put("charging", true))
        assertEquals(true, plugged.onExternalPower)
        assertEquals(true, plugged.toReportJson().getBoolean("on_external_power"))
        assertEquals(true, Metrics.fromJson(plugged.toJson())!!.onExternalPower)
        val unknown = Metrics.fromRun(JSONObject().put("session_length", 900))
        assertNull(unknown.onExternalPower)
        assertFalse(unknown.toReportJson().has("on_external_power"))
    }
}
