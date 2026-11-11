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
    fun theStatusNamesOnlyTheKindOfChange() {
        fun change(levers: String, cap: Boolean = false) =
            OptimizeChange.of(Rung.parse(JSONObject("""{"rung_id":"r","is_fps_cap":$cap,"levers":[$levers]}"""))!!)
        fun container(key: String) = """{"lever_type":"container","payload":{"set":[{"key":"$key","value":"1"}]}}"""
        assertEquals(OptimizeChange.SETTINGS, OptimizeChange.of(Rung.parse(JSONObject(rungJson))!!))
        assertEquals(OptimizeChange.SETTINGS, change(container("screenSize")))
        assertEquals(OptimizeChange.CORES, change(container("cpuList")))
        assertEquals(OptimizeChange.COMPONENTS, change(container("envVars.GN_X")))
        assertEquals(OptimizeChange.COMPONENTS, change(container("dxwrapper")))
        assertEquals(OptimizeChange.FPS_HOLD, change(container("fpsLimit"), cap = true))
        assertNull(change("""{"lever_type":"teleport","payload":{}}"""))
    }

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
        val baseline = Metrics(gameplaySec = 900, steadyFps = 41.5, stutterShare = 0.012, throttleOnsetSec = 420, cls = "gpu", confidence = 0.8, confident = true, exitReason = "quick_menu")
        val power = ProfileLadder(
            state = LadderState.PAUSED,
            kept = listOf(
                KeptRung("k1", "12", 1, listOf("cpu"), 1000L, listOf(JSONObject().put("type", "container")), baseline, "vc_fps_held", "fps held", JSONObject(rungJson), applied = true, before = baseline.copy(gameplaySec = 700)),
            ),
            pending = PendingRung(rung, 2000L, baseline, 1, listOf(JSONObject().put("type", "registry")), revert = "worse"),
            tried = listOf(TriedRung("t1", "3", OptimizeJudge.NO_CHANGE)),
            bad = listOf("b1"),
            noChangeStreak = 1,
            baselineOverride = baseline.copy(gameplaySec = 1000),
            lastStatus = "rung",
        )
        val balanced = ProfileLadder(
            state = LadderState.DONE,
            kept = listOf(KeptRung("k2", "1", 1, emptyList(), 0L, emptyList(), null, applied = false)),
        )
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.POWER,
            profiles = mapOf(OptimizeProfile.POWER to power, OptimizeProfile.BALANCED to balanced),
            never = true,
            card = OptimizeCard(OptimizeCard.HELPED, 5L, "Game", OptimizeProfile.POWER, rungId = "k1", outcome = OptimizeCard.OUTCOME_FPS, fpsGainPct = 12, metrics = baseline),
            suppression = CardSuppression(99L, baseline),
            cardAction = OptimizeCards.ACTION_NOT_NOW,
            entitlement = Entitlement(Entitlement.TRIAL, 1, 3),
            outcomeQueue = listOf(JSONObject().put("outcome_id", "o1")),
        )
        val store = OptimizeStore(tmp.root)
        store.write(state)
        assertTrue(File(tmp.root, ".gamenative/optimize/state.json").isFile)
        assertEquals(2, JSONObject(store.file.readText()).getInt("v"))
        val back = store.read()

        assertEquals(OptimizeProfile.POWER, back.activeProfile)
        val p = back.profiles.getValue(OptimizeProfile.POWER)
        assertEquals(LadderState.PAUSED, p.state)
        val kept = p.kept.single()
        assertEquals("k1", kept.rungId)
        assertEquals("12", kept.version)
        assertEquals(listOf("cpu"), kept.leverClasses)
        assertEquals("container", kept.levers[0].getString("type"))
        assertEquals(baseline, kept.baseline)
        assertEquals(700, kept.before!!.gameplaySec)
        assertEquals("witcher3_grass", Rung.parse(kept.rung)!!.rungId)
        assertTrue(kept.applied)
        assertEquals("vc_fps_held", kept.visualCostId)
        val pending = p.pending!!
        assertEquals("witcher3_grass", pending.rung.rungId)
        assertEquals(4, pending.rung.levers.size)
        assertEquals(1, pending.shortSessions)
        assertEquals("worse", pending.revert)
        assertEquals(baseline, pending.baseline)
        assertEquals(listOf(TriedRung("t1", "3", OptimizeJudge.NO_CHANGE)), p.tried)
        assertEquals(listOf("b1"), p.bad)
        assertEquals(1, p.noChangeStreak)
        assertEquals(1000, p.baselineOverride!!.gameplaySec)
        assertEquals("rung", p.lastStatus)
        val b = back.profiles.getValue(OptimizeProfile.BALANCED)
        assertEquals(LadderState.DONE, b.state)
        assertFalse(b.kept.single().applied)
        assertTrue(back.never)
        assertEquals(OptimizeCard.HELPED, back.card!!.kind)
        assertEquals(12, back.card!!.fpsGainPct)
        assertEquals(baseline, back.card!!.metrics)
        assertEquals(99L, back.suppression!!.untilMillis)
        assertEquals(OptimizeCards.ACTION_NOT_NOW, back.cardAction)
        assertEquals(Entitlement(Entitlement.TRIAL, 1, 3), back.entitlement)
        assertEquals(2, back.entitlement!!.trialGamesLeft)
        assertEquals("o1", back.outcomeQueue[0].getString("outcome_id"))
        assertTrue(back.hasChanges)
    }

    @Test
    fun capPreferAndSnoozeRoundTripThroughTheStore() {
        val store = OptimizeStore(tmp.root)
        val card = OptimizeCard(OptimizeCard.CAP, 5L, "Game", OptimizeProfile.POWER, cls = "thermal_gpu", capFps = 30)
        store.write(OptimizeLocalState(capPreferred = true, capSnoozedUntil = 1234L, card = card))
        val json = JSONObject(store.file.readText())
        assertEquals("cap", json.getString("ladder_prefer"))
        val back = store.read()
        assertTrue(back.capPreferred)
        assertEquals(1234L, back.capSnoozedUntil)
        assertEquals(OptimizeCard.CAP, back.card!!.kind)
        assertEquals(30, back.card!!.capFps)

        store.write(back.copy(capPreferred = false, capSnoozedUntil = 0L))
        assertFalse(JSONObject(store.file.readText()).has("ladder_prefer"))
        val cleared = store.read()
        assertFalse(cleared.capPreferred)
        assertEquals(0L, cleared.capSnoozedUntil)
        assertFalse(OptimizeLocalState.fromJson(JSONObject("{\"v\":2,\"profiles\":{}}")).capPreferred)
    }

    @Test
    fun v1StateMigratesToTheActiveProfile() {
        val v1 = JSONObject()
            .put("v", 1)
            .put("profile", "quality")
            .put("started", true)
            .put("kept", org.json.JSONArray().put(KeptRung("k1", "2", 1, emptyList(), 10L, listOf(JSONObject().put("type", "registry")), null).toJson().apply { remove("applied") }))
            .put("pending", PendingRung(Rung.parse(JSONObject(rungJson))!!, 20L, null, 2, emptyList()).toJson())
            .put("tried", org.json.JSONArray().put(TriedRung("k1", "2", OptimizeJudge.HELPED).toJson()))
            .put("bad", org.json.JSONArray().put("x"))
            .put("outcome_queue", org.json.JSONArray().put(JSONObject().put("outcome_id", "o9")))
            .put("last_card", JSONObject().put("result", "helped"))
            .put("baseline_override", JSONObject.NULL)
            .put("last_status", "rung")
        val store = OptimizeStore(tmp.root)
        store.dir.mkdirs()
        store.file.writeText(v1.toString())
        val state = store.read()
        assertEquals(OptimizeProfile.QUALITY, state.activeProfile)
        val ladder = state.profiles.getValue(OptimizeProfile.QUALITY)
        assertEquals(LadderState.TRYING, ladder.state)
        assertEquals("k1", ladder.kept.single().rungId)
        assertTrue(ladder.kept.single().applied)
        assertNull(ladder.kept.single().rung)
        assertEquals(2, ladder.pending!!.shortSessions)
        assertEquals(listOf(TriedRung("k1", "2", OptimizeJudge.HELPED)), ladder.tried)
        assertEquals(listOf("x"), ladder.bad)
        assertEquals("o9", state.outcomeQueue.single().getString("outcome_id"))
        assertNull(state.card)
        assertFalse(state.never)

        store.write(state)
        assertEquals(state.activeProfile, store.read().activeProfile)
        assertEquals(2, JSONObject(store.file.readText()).getInt("v"))
    }

    @Test
    fun v1ExhaustedAndOffMigrate() {
        val exhausted = OptimizeLocalState.fromJson(JSONObject().put("v", 1).put("profile", "power").put("last_status", "exhausted"))
        assertEquals(LadderState.EXHAUSTED, exhausted.profiles.getValue(OptimizeProfile.POWER).state)
        val off = OptimizeLocalState.fromJson(JSONObject().put("v", 1).put("profile", "off").put("outcome_queue", org.json.JSONArray().put(JSONObject().put("outcome_id", "o1"))))
        assertNull(off.activeProfile)
        assertTrue(off.profiles.isEmpty())
        assertEquals(1, off.outcomeQueue.size)
    }

    @Test
    fun missingOrBrokenStateIsTheDefault() {
        val store = OptimizeStore(tmp.root)
        assertNull(store.read().activeProfile)
        store.dir.mkdirs()
        store.file.writeText("{not json")
        assertEquals(OptimizeLocalState(), store.read())
    }

    @Test
    fun nextRungResponseCarriesTheEntitlement() {
        val response = NextRungResponse.parse(
            JSONObject("{\"status\":\"entitlement_required\",\"rung\":null,\"entitlement\":{\"state\":\"none\",\"trial_games_used\":3,\"trial_games_limit\":3}}"),
        )
        assertEquals(NextRungResponse.STATUS_ENTITLEMENT_REQUIRED, response.status)
        assertNull(response.rung)
        assertEquals(Entitlement(Entitlement.NONE, 3, 3), response.entitlement)
        assertEquals(0, response.entitlement!!.trialGamesLeft)
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
        fun with(ladder: ProfileLadder) = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, profiles = mapOf(OptimizeProfile.BALANCED to ladder))
        assertEquals(OptimizeStatus.Off, OptimizeStatus.of(OptimizeLocalState()))
        assertEquals(OptimizeStatus.Never, OptimizeStatus.of(OptimizeLocalState(never = true)))
        assertEquals(OptimizeStatus.NotStarted, OptimizeStatus.of(with(ProfileLadder())))
        assertEquals(OptimizeStatus.TuningNext, OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING))))
        val failed = TriedRung("r1", "1", OptimizeJudge.APPLY_FAILED)
        assertEquals(OptimizeStatus.RetryNext, OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, tried = listOf(failed)))))
        assertEquals(
            OptimizeStatus.TuningNext,
            OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, tried = listOf(failed, TriedRung("r2", "1", OptimizeJudge.NO_OP))))),
        )
        assertEquals(
            OptimizeStatus.Trying(OptimizeChange.SETTINGS),
            OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, tried = listOf(failed), pending = PendingRung(rung, 0L, null, 0, emptyList())))),
        )
        assertEquals(
            OptimizeStatus.Measuring,
            OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, pending = PendingRung(rung, 0L, null, 1, emptyList())))),
        )
        assertEquals(
            OptimizeStatus.Trying(OptimizeChange.SETTINGS),
            OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, pending = PendingRung(rung, 0L, null, 0, emptyList())))),
        )
        assertEquals(OptimizeStatus.Paused, OptimizeStatus.of(with(ProfileLadder(state = LadderState.PAUSED))))
        assertEquals(OptimizeStatus.Done, OptimizeStatus.of(with(ProfileLadder(state = LadderState.DONE))))
        assertEquals(OptimizeStatus.Exhausted, OptimizeStatus.of(with(ProfileLadder(state = LadderState.EXHAUSTED))))
        assertEquals(
            OptimizeStatus.TrialUsed,
            OptimizeStatus.of(with(ProfileLadder(state = LadderState.TRYING, lastStatus = NextRungResponse.STATUS_ENTITLEMENT_REQUIRED))),
        )
        val kept = KeptRung("k", "1", 1, emptyList(), 0L, emptyList(), null)
        assertEquals(1, OptimizeStatus.keptCount(with(ProfileLadder(state = LadderState.DONE, kept = listOf(kept)))))
    }

    @Test
    fun suggestedGoalShowsWheneverOneIsKnown() {
        fun with(state: LadderState) =
            OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, profiles = mapOf(OptimizeProfile.BALANCED to ProfileLadder(state = state)))
        val off = OptimizeLocalState()
        assertEquals(OptimizeProfile.PERFORMANCE, OptimizeStatus.suggestedProfile(off, OptimizeProfile.PERFORMANCE, hot = false))
        assertEquals(OptimizeProfile.QUALITY, OptimizeStatus.suggestedProfile(with(LadderState.TRYING), OptimizeProfile.QUALITY, hot = false))
        assertEquals(OptimizeProfile.QUALITY, OptimizeStatus.suggestedProfile(with(LadderState.PAUSED), OptimizeProfile.QUALITY, hot = true))
        assertEquals(OptimizeProfile.POTATO, OptimizeStatus.suggestedProfile(with(LadderState.DONE), OptimizeProfile.POTATO, hot = false))
        assertEquals(OptimizeProfile.POWER, OptimizeStatus.suggestedProfile(with(LadderState.DONE), OptimizeProfile.POTATO, hot = true))

        val firstCard = OptimizeCard(OptimizeCard.FIRST, 0L, profile = OptimizeProfile.QUALITY)
        assertEquals(OptimizeProfile.QUALITY, OptimizeStatus.suggestedProfile(off.copy(card = firstCard), null, hot = false))
        assertEquals(OptimizeProfile.BALANCED, OptimizeStatus.suggestedProfile(off.copy(card = firstCard), OptimizeProfile.BALANCED, hot = false))
        val doneCard = OptimizeCard(OptimizeCard.DONE, 0L, profile = OptimizeProfile.QUALITY)
        assertNull(OptimizeStatus.suggestedProfile(off.copy(card = doneCard), null, hot = false))
        assertNull(OptimizeStatus.suggestedProfile(with(LadderState.DONE), null, hot = false))
        assertNull(OptimizeStatus.suggestedProfile(off, OptimizeProfile.OFF, hot = false))
    }

    @Test
    fun restoreOnlyWhenSomethingIsOn() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        val kept = KeptRung("k", "1", 1, emptyList(), 0L, emptyList(), null)
        assertFalse(OptimizeLadders.hasRestorable(null))
        assertFalse(OptimizeLadders.hasRestorable(ProfileLadder(state = LadderState.DONE)))
        assertTrue(OptimizeLadders.hasRestorable(ProfileLadder(state = LadderState.DONE, kept = listOf(kept))))
        assertTrue(OptimizeLadders.hasRestorable(ProfileLadder(state = LadderState.TRYING, pending = PendingRung(rung, 0L, null, 0, emptyList()))))
    }

    @Test
    fun prefetchedRungRoundTripsAndChecksWhatItWasAskedFor() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        val now = 1_800_000_000_000L
        val key = PrefetchKey("C:\\Game\\TheForest.exe", "dxvk-2.4", 60)
        val kept = KeptRung("k1", "1", 1, emptyList(), 0L, emptyList(), null)
        val ladder = ProfileLadder(state = LadderState.TRYING, kept = listOf(kept))
        val prefetched = PrefetchedRung(rung, now, OptimizeProfile.BALANCED, key, listOf("k1"), "rules-1")

        val state = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, prefetched = prefetched)
        val back = OptimizeLocalState.fromJson(JSONObject(state.toJson().toString())).prefetched!!
        assertEquals("witcher3_grass", back.rung.rungId)
        assertEquals(now, back.fetchedAt)
        assertEquals(OptimizeProfile.BALANCED, back.profile)
        assertEquals(key, back.key)
        assertEquals(listOf("k1"), back.kept)

        val day = 24L * 60 * 60 * 1000
        assertTrue(back.isUsable(now + day, OptimizeProfile.BALANCED, key, ladder, null))
        assertFalse(back.isUsable(now + 8 * day, OptimizeProfile.BALANCED, key, ladder, null))
        assertFalse(back.isUsable(now, OptimizeProfile.PERFORMANCE, key, ladder, null))
        assertFalse(back.isUsable(now, OptimizeProfile.BALANCED, key.copy(dxwrapper = "vkd3d"), ladder, null))
        assertFalse(back.isUsable(now, OptimizeProfile.BALANCED, key.copy(fpsLimit = 30), ladder, null))
        assertFalse(back.isUsable(now, OptimizeProfile.BALANCED, key, ladder.copy(kept = emptyList()), null))
        assertFalse(back.isUsable(now, OptimizeProfile.BALANCED, key, ladder.copy(bad = listOf("witcher3_grass")), null))
        assertFalse(back.isUsable(now, OptimizeProfile.BALANCED, key, ladder.copy(tried = listOf(TriedRung("witcher3_grass", "a1b2c3d4", "worse"))), null))

        val expiring = Rung.parse(JSONObject(rungJson).put("expires_at", "2027-01-01T00:00:00Z"))!!
        assertFalse(prefetched.copy(rung = expiring).isUsable(now, OptimizeProfile.BALANCED, key, ladder, null))
        assertNull(OptimizeLocalState.fromJson(JSONObject(OptimizeLocalState().toJson().toString())).prefetched)
    }

    @Test
    fun aRungRepeatingAKeptOrTriedChangeIsRefused() {
        val rung = Rung.parse(JSONObject(rungJson))!!
        val now = 1_800_000_000_000L
        val key = PrefetchKey("C:\\Game\\TheForest.exe", "dxvk-2.4", 60)
        val ladder = ProfileLadder(state = LadderState.TRYING)
        assertFalse(ladder.repeats(rung))

        val keptSameHash = KeptRung("other_id", "a1b2c3d4", 1, emptyList(), 0L, emptyList(), null)
        assertTrue(ladder.copy(kept = listOf(keptSameHash)).repeats(rung))
        assertTrue(ladder.copy(tried = listOf(TriedRung("other_id", "a1b2c3d4", "no_change"))).repeats(rung))
        assertFalse(ladder.copy(tried = listOf(TriedRung("other_id", "ffff", "no_change"))).repeats(rung))

        val reordered = JSONObject(rungJson).getJSONArray("levers").getJSONObject(1).getJSONObject("payload").let { p ->
            JSONObject().put("values", p.getJSONArray("values")).put("key", p.getString("key")).put("hive", p.getString("hive"))
        }
        val keptLevers = JSONObject(rungJson).apply {
            getJSONArray("levers").put(1, JSONObject().put("payload", reordered).put("lever_type", "registry"))
        }
        val keptSameLevers = KeptRung("old_id", "0000", 1, emptyList(), 0L, emptyList(), null, rung = keptLevers)
        assertTrue(ladder.copy(kept = listOf(keptSameLevers)).repeats(rung))
        val keptOtherLevers = keptSameLevers.copy(rung = JSONObject(rungJson).put("levers", org.json.JSONArray()))
        assertFalse(ladder.copy(kept = listOf(keptOtherLevers)).repeats(rung))

        val prefetched = PrefetchedRung(rung, now, OptimizeProfile.BALANCED, key, listOf("old_id"), "rules-1")
        assertFalse(prefetched.isUsable(now, OptimizeProfile.BALANCED, key, ladder.copy(kept = listOf(keptSameLevers)), null))
        assertTrue(prefetched.isUsable(now, OptimizeProfile.BALANCED, key, ladder.copy(kept = listOf(keptOtherLevers)), null))
    }

    @Test
    fun registryValuesEncodeLikeWine() {
        assertEquals("dword:00000003", OptimizeFiles.encodeRegistryValue("dword", "3"))
        assertEquals("dword:ffffffff", OptimizeFiles.encodeRegistryValue("dword", "-1"))
        assertEquals("dword:000000ff", OptimizeFiles.encodeRegistryValue("dword", "0xff"))
        assertEquals("\"High\"", OptimizeFiles.encodeRegistryValue("sz", "High"))
        assertEquals("hex(4):00,00,00,00,00,00,f0,3f", OptimizeFiles.encodeRegistryValue("binary_f64le", "1.0"))
        assertEquals("hex(4):33,33,33,33,33,33,d3,3f", OptimizeFiles.encodeRegistryValue("binary_f64le", "0.30"))
        assertEquals("hex(4):00,00,00,00,00,00,4e,40", OptimizeFiles.encodeRegistryValue("binary_f64le", "60.0"))
        assertEquals("hex(b):05,00,00,00,00,00,00,00", OptimizeFiles.encodeRegistryValue("qword", "5"))
    }

    @Test
    fun aSettingsStepAlreadyKeptOrTriedIsRefused() {
        fun rung(id: String, version: String, step: Int? = null) = Rung.parse(
            JSONObject().put("rung_id", id).put("version", version).apply { step?.let { put("step", it) } },
        )!!
        val ladder = ProfileLadder(state = LadderState.TRYING)
        val id = "cfg.game.performance.a+b.s6"
        // The server re-served a kept step under the same id with a new version.
        val kept = KeptRung(
            id, "v1", 1, emptyList(), 0L, emptyList(), null,
            rung = JSONObject().put("rung_id", id).put("version", "v1").put("levers", org.json.JSONArray().put(JSONObject().put("lever_type", "container"))),
        )
        assertEquals("already kept", ladder.copy(kept = listOf(kept)).repeatReason(rung(id, "v2")))
        // Another id with the same step number is the step already kept.
        val otherClasses = "cfg.game.performance.c.s6"
        assertEquals("step 6 already kept", ladder.copy(kept = listOf(kept)).repeatReason(rung(otherClasses, "v3")))
        assertEquals("step 6 already tried", ladder.copy(tried = listOf(TriedRung(id, "v1", "worse"))).repeatReason(rung(otherClasses, "v3")))
        assertEquals("already tried", ladder.copy(tried = listOf(TriedRung(id, "v1", "worse"))).repeatReason(rung(id, "v2")))
        // The step field outranks the id.
        assertEquals(5, settingsStep("cfg.g.performance.c.s6", JSONObject().put("step", 5)))
        assertTrue(ladder.copy(kept = listOf(kept)).repeats(rung("cfg.g.performance.c.s9", "v4", step = 6)))
        assertFalse(ladder.copy(kept = listOf(kept)).repeats(rung("cfg.game.performance.c.s5", "v4")))
        // Cap rungs have no step.
        assertNull(settingsStep("cap.balanced.30", null))
        assertFalse(ladder.copy(kept = listOf(kept)).repeats(rung("cap.performance.s6", "v5")))
    }

    @Test
    fun potatoProfileMapsToItsWireIdAndBack() {
        assertEquals("potato", OptimizeProfile.POTATO.wireId)
        assertEquals(OptimizeProfile.POTATO, OptimizeProfile.fromWire("potato"))
        assertEquals(OptimizeProfile.OFF, OptimizeProfile.fromWire("spud"))
        OptimizeProfile.entries.forEach { assertEquals(it, OptimizeProfile.fromWire(it.wireId)) }
        assertEquals(app.gamenative.R.string.optimize_profile_potato, OptimizeProfile.POTATO.title)

        val state = OptimizeLocalState(activeProfile = OptimizeProfile.POTATO, profiles = mapOf(OptimizeProfile.POTATO to ProfileLadder()))
        val back = OptimizeLocalState.fromJson(JSONObject(state.toJson().toString()))
        assertEquals(OptimizeProfile.POTATO, back.activeProfile)
        assertTrue(back.profiles.containsKey(OptimizeProfile.POTATO))
    }

    @Test
    fun goalsAreOfferedInOrderWithPotatoLast() {
        assertEquals(
            listOf(OptimizeProfile.BALANCED, OptimizeProfile.QUALITY, OptimizeProfile.PERFORMANCE, OptimizeProfile.POWER, OptimizeProfile.POTATO),
            app.gamenative.ui.component.dialog.OPTIMIZE_GOAL_ORDER,
        )
    }
}
