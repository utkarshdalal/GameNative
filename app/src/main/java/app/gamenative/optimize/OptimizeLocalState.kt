package app.gamenative.optimize

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class KeptRung(
    val rungId: String,
    val version: String,
    val order: Int,
    val leverClasses: List<String>,
    val appliedAt: Long,
    val levers: List<JSONObject>,
    val baseline: Metrics?,
    val visualCostId: String? = null,
    val visualCostEn: String? = null,
    /** The rung as the server sent it, so it can be put back on after a profile switch; null for rungs kept before v2. */
    val rung: JSONObject? = null,
    /** False while the profile is not the active one and its changes are off the game. */
    val applied: Boolean = true,
    /** The session before the step, the baseline again once the step is undone. */
    val before: Metrics? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rung_id", rungId)
        put("version", version)
        put("order", order)
        put("lever_classes", JSONArray(leverClasses))
        put("applied_at", appliedAt)
        put("levers", JSONArray().apply { levers.forEach { put(it) } })
        put("baseline", baseline?.toJson() ?: JSONObject.NULL)
        visualCostId?.let { put("visual_cost_id", it) }
        visualCostEn?.let { put("visual_cost_en", it) }
        rung?.let { put("rung", it) }
        put("applied", applied)
        before?.let { put("before", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): KeptRung? {
            val id = o.optStringOrNull("rung_id") ?: return null
            return KeptRung(
                rungId = id,
                version = o.optStringOrNull("version").orEmpty(),
                order = o.optInt("order", 0),
                leverClasses = o.optJSONArray("lever_classes").strings(),
                appliedAt = o.optLong("applied_at", 0L),
                levers = o.optJSONArray("levers").objects(),
                baseline = Metrics.fromJson(o.optJSONObject("baseline")),
                visualCostId = o.optStringOrNull("visual_cost_id"),
                visualCostEn = o.optStringOrNull("visual_cost_en"),
                rung = o.optJSONObject("rung"),
                applied = o.optBoolean("applied", true),
                before = Metrics.fromJson(o.optJSONObject("before")),
            )
        }
    }
}

data class PendingRung(
    val rung: Rung,
    val appliedAt: Long,
    val baseline: Metrics?,
    val shortSessions: Int,
    val levers: List<JSONObject>,
    /** Result the rung was judged with when it still has to be taken off before the next launch. */
    val revert: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rung", rung.raw)
        put("applied_at", appliedAt)
        put("baseline", baseline?.toJson() ?: JSONObject.NULL)
        put("short_sessions", shortSessions)
        put("levers", JSONArray().apply { levers.forEach { put(it) } })
        revert?.let { put("revert", it) }
    }

    fun toKept(newBaseline: Metrics?): KeptRung = KeptRung(
        rungId = rung.rungId,
        version = rung.version,
        order = rung.order,
        leverClasses = rung.leverClasses,
        appliedAt = appliedAt,
        levers = levers,
        baseline = newBaseline,
        visualCostId = rung.visualCostId,
        visualCostEn = rung.visualCostEn,
        rung = rung.raw,
        before = baseline,
    )

    companion object {
        fun fromJson(o: JSONObject?): PendingRung? {
            if (o == null) return null
            val rung = Rung.parse(o.optJSONObject("rung")) ?: return null
            return PendingRung(
                rung = rung,
                appliedAt = o.optLong("applied_at", 0L),
                baseline = Metrics.fromJson(o.optJSONObject("baseline")),
                shortSessions = o.optInt("short_sessions", 0),
                levers = o.optJSONArray("levers").objects(),
                revert = o.optStringOrNull("revert"),
            )
        }
    }
}

data class TriedRung(val rungId: String, val version: String, val result: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rung_id", rungId)
        put("version", version)
        put("result", result)
    }

    companion object {
        fun fromJson(o: JSONObject): TriedRung? {
            val id = o.optStringOrNull("rung_id") ?: return null
            return TriedRung(id, o.optStringOrNull("version").orEmpty(), o.optString("result"))
        }
    }
}

enum class LadderState(val wireId: String) {
    IDLE("idle"),
    TRYING("trying"),
    PAUSED("paused"),
    DONE("done"),
    EXHAUSTED("exhausted"),
    ;

    companion object {
        fun fromWire(id: String?): LadderState = entries.firstOrNull { it.wireId == id } ?: IDLE
    }
}

/** One profile's ladder: what it kept, what it is trying and what it already tried. Kept across profile switches. */
data class ProfileLadder(
    val state: LadderState = LadderState.IDLE,
    val kept: List<KeptRung> = emptyList(),
    val pending: PendingRung? = null,
    val tried: List<TriedRung> = emptyList(),
    val bad: List<String> = emptyList(),
    val noChangeStreak: Int = 0,
    /** Metrics of the last session on the current settings when the latest run record no longer reflects them. */
    val baselineOverride: Metrics? = null,
    val lastStatus: String? = null,
) {
    val hasApplied: Boolean get() = pending != null || kept.any { it.applied }

    /** True when [rung] makes a change this profile already kept or tried: its lever hash is on the ladder, or its levers equal a kept rung's. */
    fun repeats(rung: Rung): Boolean {
        if (rung.version.isNotEmpty() && (kept.any { it.version == rung.version } || tried.any { it.version == rung.version })) return true
        val levers = canonicalJson(rung.raw.optJSONArray("levers"))
        return kept.any { k -> k.rung?.let { canonicalJson(it.optJSONArray("levers")) == levers } == true }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state.wireId)
        put("kept", JSONArray().apply { kept.forEach { put(it.toJson()) } })
        put("pending", pending?.toJson() ?: JSONObject.NULL)
        put("tried", JSONArray().apply { tried.forEach { put(it.toJson()) } })
        put("bad", JSONArray(bad))
        put("no_change_streak", noChangeStreak)
        put("baseline_override", baselineOverride?.toJson() ?: JSONObject.NULL)
        lastStatus?.let { put("last_status", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ProfileLadder = ProfileLadder(
            state = LadderState.fromWire(o.optStringOrNull("state")),
            kept = o.optJSONArray("kept").objects().mapNotNull { KeptRung.fromJson(it) },
            pending = PendingRung.fromJson(o.optJSONObject("pending")),
            tried = o.optJSONArray("tried").objects().mapNotNull { TriedRung.fromJson(it) },
            bad = o.optJSONArray("bad").strings(),
            noChangeStreak = o.optInt("no_change_streak", 0),
            baselineOverride = Metrics.fromJson(o.optJSONObject("baseline_override")),
            lastStatus = o.optStringOrNull("last_status"),
        )
    }
}

/** JSON text with object keys sorted at every level, so equal values compare equal whatever order they were written in. */
internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** The container settings a next-rung answer was asked for; an answer is only used while they still hold. */
data class PrefetchKey(val executablePath: String, val dxwrapper: String, val fpsLimit: Int)

/** A next-rung answer fetched ahead of the launch that uses it: at exit, or after a launch that stopped waiting. */
data class PrefetchedRung(
    val rung: Rung,
    val fetchedAt: Long,
    val profile: OptimizeProfile,
    val key: PrefetchKey,
    /** Ids of the changes that were on when it was asked for. */
    val kept: List<String>,
) {
    fun isUsable(nowMillis: Long, profile: OptimizeProfile, key: PrefetchKey, ladder: ProfileLadder): Boolean =
        nowMillis - fetchedAt in 0..MAX_AGE_MS &&
            !rung.isExpired(nowMillis) &&
            this.profile == profile &&
            this.key == key &&
            kept == keptIds(ladder) &&
            rung.rungId !in ladder.bad &&
            ladder.tried.none { it.rungId == rung.rungId && it.version == rung.version } &&
            !ladder.repeats(rung)

    fun toJson(): JSONObject = JSONObject().apply {
        put("rung", rung.raw)
        put("fetched_at", fetchedAt)
        put("profile", profile.wireId)
        put("executable_path", key.executablePath)
        put("dxwrapper", key.dxwrapper)
        put("fps_limit", key.fpsLimit)
        put("kept", JSONArray(kept))
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun keptIds(ladder: ProfileLadder): List<String> = ladder.kept.filter { it.applied }.map { it.rungId }

        fun fromJson(o: JSONObject?): PrefetchedRung? {
            if (o == null) return null
            val rung = Rung.parse(o.optJSONObject("rung")) ?: return null
            val profile = OptimizeProfile.fromWire(o.optStringOrNull("profile")).takeIf { it != OptimizeProfile.OFF } ?: return null
            return PrefetchedRung(
                rung = rung,
                fetchedAt = o.optLong("fetched_at", 0L),
                profile = profile,
                key = PrefetchKey(o.optString("executable_path"), o.optString("dxwrapper"), o.optInt("fps_limit", 0)),
                kept = o.optJSONArray("kept").strings(),
            )
        }
    }
}

/** A card waiting to be shown once on the game or library screen after a session. */
data class OptimizeCard(
    val kind: String,
    val at: Long,
    val gameName: String? = null,
    val profile: OptimizeProfile? = null,
    val cls: String? = null,
    val rungId: String? = null,
    val outcome: String? = null,
    val fpsGainPct: Int? = null,
    val stutterDropPct: Int? = null,
    /** The session that produced the card. */
    val metrics: Metrics? = null,
    /** Why [profile] is suggested on a first card, an [OptimizeCards] REASON_ id. */
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("at", at)
        gameName?.let { put("game_name", it) }
        profile?.let { put("profile", it.wireId) }
        cls?.let { put("class", it) }
        rungId?.let { put("rung_id", it) }
        outcome?.let { put("outcome", it) }
        fpsGainPct?.let { put("fps_gain_pct", it) }
        stutterDropPct?.let { put("stutter_drop_pct", it) }
        metrics?.let { put("metrics", it.toJson()) }
        reason?.let { put("reason", it) }
    }

    companion object {
        const val FIRST = "first"
        const val HELPED = "helped"
        const val NO_CHANGE = "no_change"
        const val WORSE = "worse"
        const val EXHAUSTED = "exhausted"
        const val DONE = "done"
        const val ENTITLEMENT = "entitlement"

        const val OUTCOME_FPS = "fps"
        const val OUTCOME_STUTTER = "stutter"
        const val OUTCOME_STABLE = "stable"

        fun fromJson(o: JSONObject?): OptimizeCard? {
            if (o == null) return null
            val kind = o.optStringOrNull("kind") ?: return null
            return OptimizeCard(
                kind = kind,
                at = o.optLong("at", 0L),
                gameName = o.optStringOrNull("game_name"),
                profile = o.optStringOrNull("profile")?.let { OptimizeProfile.fromWire(it) }?.takeIf { it != OptimizeProfile.OFF },
                cls = o.optStringOrNull("class"),
                rungId = o.optStringOrNull("rung_id"),
                outcome = o.optStringOrNull("outcome"),
                fpsGainPct = o.optIntOrNull("fps_gain_pct"),
                stutterDropPct = o.optIntOrNull("stutter_drop_pct"),
                metrics = Metrics.fromJson(o.optJSONObject("metrics")),
                reason = o.optStringOrNull("reason"),
            )
        }
    }
}

/** "Not now": no first card until [untilMillis], unless a session runs worse than [metrics]. */
data class CardSuppression(val untilMillis: Long, val metrics: Metrics?) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("until", untilMillis)
        put("metrics", metrics?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject?): CardSuppression? {
            if (o == null || !o.has("until")) return null
            return CardSuppression(o.optLong("until", 0L), Metrics.fromJson(o.optJSONObject("metrics")))
        }
    }
}

data class OptimizeLocalState(
    val activeProfile: OptimizeProfile? = null,
    val profiles: Map<OptimizeProfile, ProfileLadder> = emptyMap(),
    val never: Boolean = false,
    val card: OptimizeCard? = null,
    val suppression: CardSuppression? = null,
    val cardAction: String? = null,
    val entitlement: Entitlement? = null,
    val entitlementCheckedAt: Long = 0L,
    val outcomeQueue: List<JSONObject> = emptyList(),
    val prefetched: PrefetchedRung? = null,
) {
    val active: ProfileLadder? get() = activeProfile?.let { profiles[it] }

    val hasChanges: Boolean get() = profiles.values.any { it.hasApplied }

    fun ladder(profile: OptimizeProfile): ProfileLadder = profiles[profile] ?: ProfileLadder()

    fun withLadder(profile: OptimizeProfile, ladder: ProfileLadder): OptimizeLocalState = copy(profiles = profiles + (profile to ladder))

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("active_profile", activeProfile?.wireId ?: JSONObject.NULL)
        put("profiles", JSONObject().apply { profiles.forEach { (profile, ladder) -> put(profile.wireId, ladder.toJson()) } })
        put("never", never)
        put("card_pending", card?.toJson() ?: JSONObject.NULL)
        put("suppression", suppression?.toJson() ?: JSONObject.NULL)
        put("card_action", cardAction ?: JSONObject.NULL)
        put("entitlement", entitlement?.toJson() ?: JSONObject.NULL)
        put("entitlement_checked_at", entitlementCheckedAt)
        put("outcome_queue", JSONArray().apply { outcomeQueue.forEach { put(it) } })
        prefetched?.let { put("prefetched", it.toJson()) }
    }

    companion object {
        const val VERSION = 2
        const val MAX_QUEUE = 50
        const val MAX_TRIED = 200

        private fun profileOf(id: String?): OptimizeProfile? = OptimizeProfile.fromWire(id).takeIf { it != OptimizeProfile.OFF }

        fun fromJson(o: JSONObject): OptimizeLocalState {
            if (o.optInt("v", 1) < 2 || !o.has("profiles")) return fromV1(o)
            val profilesJson = o.optJSONObject("profiles") ?: JSONObject()
            val profiles = buildMap<OptimizeProfile, ProfileLadder> {
                profilesJson.keys().forEach { key ->
                    val profile = profileOf(key) ?: return@forEach
                    profilesJson.optJSONObject(key)?.let { put(profile, ProfileLadder.fromJson(it)) }
                }
            }
            return OptimizeLocalState(
                activeProfile = profileOf(o.optStringOrNull("active_profile")),
                profiles = profiles,
                never = o.optBoolean("never", false),
                card = OptimizeCard.fromJson(o.optJSONObject("card_pending")),
                suppression = CardSuppression.fromJson(o.optJSONObject("suppression")),
                cardAction = o.optStringOrNull("card_action"),
                entitlement = Entitlement.fromJson(o.optJSONObject("entitlement")),
                entitlementCheckedAt = o.optLong("entitlement_checked_at", 0L),
                outcomeQueue = o.optJSONArray("outcome_queue").objects(),
                prefetched = PrefetchedRung.fromJson(o.optJSONObject("prefetched")),
            )
        }

        /** v1 had one profile with one ladder; it becomes the active profile, still trying. */
        fun fromV1(o: JSONObject): OptimizeLocalState {
            val queue = o.optJSONArray("outcome_queue").objects()
            val kept = o.optJSONArray("kept").objects().mapNotNull { KeptRung.fromJson(it) }
            val pending = PendingRung.fromJson(o.optJSONObject("pending"))
            val profile = profileOf(o.optStringOrNull("profile"))
                ?: return OptimizeLocalState(outcomeQueue = queue)
            val lastStatus = o.optStringOrNull("last_status")
            val ladder = ProfileLadder(
                state = if (lastStatus == NextRungResponse.STATUS_EXHAUSTED && pending == null) LadderState.EXHAUSTED else LadderState.TRYING,
                kept = kept,
                pending = pending,
                tried = o.optJSONArray("tried").objects().mapNotNull { TriedRung.fromJson(it) },
                bad = o.optJSONArray("bad").strings(),
                baselineOverride = Metrics.fromJson(o.optJSONObject("baseline_override")),
                lastStatus = lastStatus,
            )
            return OptimizeLocalState(activeProfile = profile, profiles = mapOf(profile to ladder), outcomeQueue = queue)
        }
    }
}

/** Reads and writes `<rootDir>/.gamenative/optimize/state.json`. Callers hold [OptimizeController.mutex]. */
class OptimizeStore(rootDir: File) {
    val dir = File(rootDir, ".gamenative/optimize")
    val file = File(dir, "state.json")

    fun read(): OptimizeLocalState {
        if (!file.isFile) return OptimizeLocalState()
        return try {
            OptimizeLocalState.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Timber.tag("Optimize").w(e, "state.json unreadable, starting fresh")
            OptimizeLocalState()
        }
    }

    fun write(state: OptimizeLocalState) {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(state.toJson().toString())
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safe(rungId: String): String = rungId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun rungDir(profile: OptimizeProfile, rungId: String): File = File(dir, "${profile.wireId}-${safe(rungId)}")

    /** Drops a rung's backups, including where v1 kept them. */
    fun dropRungDir(profile: OptimizeProfile, rungId: String) {
        rungDir(profile, rungId).deleteRecursively()
        File(dir, safe(rungId)).takeIf { it.isDirectory }?.deleteRecursively()
    }
}
