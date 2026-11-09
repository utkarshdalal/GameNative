package app.gamenative.optimize

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeServerTest {

    private val card = OptimizeCard(
        OptimizeCard.FIRST, 1L, "Game", profile = OptimizeProfile.PERFORMANCE, cls = "gpu", reason = OptimizeCards.REASON_LOW_FPS,
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
        assertNull(shown.reason)
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
        val same = OptimizeServer.firstDecision(null, card, verdict(true, profile = "performance"), null).card!!
        assertEquals(OptimizeCards.REASON_LOW_FPS, same.reason)
    }

    @Test
    fun `only verdict blocks ask the server at exit`() {
        assertTrue(OptimizeServer.asksAtExit(null))
        assertTrue(OptimizeServer.asksAtExit(OptimizeCards.SUPPRESSED_SHORT))
        assertFalse(OptimizeServer.asksAtExit(OptimizeCards.SUPPRESSED_NEVER))
        assertFalse(OptimizeServer.asksAtExit(OptimizeCards.SUPPRESSED_NOT_NOW))
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
    fun `every visual cost id the rules send has a string`() {
        listOf(
            "vc_sharpness_lower", "vc_none_may_be_unstable", "vc_textures_softer_distance", "vc_none",
            "vc_textures_stream_later", "vc_image_softer_upscaled",
        ).forEach { assertTrue(it, OptimizeStatus.visualCostRes(it) != null) }
    }
}
