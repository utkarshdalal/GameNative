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

data class OptimizeLocalState(
    val profile: OptimizeProfile = OptimizeProfile.OFF,
    val started: Boolean = false,
    val kept: List<KeptRung> = emptyList(),
    val pending: PendingRung? = null,
    val tried: List<TriedRung> = emptyList(),
    val bad: List<String> = emptyList(),
    val outcomeQueue: List<JSONObject> = emptyList(),
    val lastCard: JSONObject? = null,
    /** Metrics of the last session on the current settings when the latest run record no longer reflects them. */
    val baselineOverride: Metrics? = null,
    val lastStatus: String? = null,
) {
    val hasChanges: Boolean get() = kept.isNotEmpty() || pending != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("profile", profile.wireId)
        put("started", started)
        put("kept", JSONArray().apply { kept.forEach { put(it.toJson()) } })
        put("pending", pending?.toJson() ?: JSONObject.NULL)
        put("tried", JSONArray().apply { tried.forEach { put(it.toJson()) } })
        put("bad", JSONArray(bad))
        put("outcome_queue", JSONArray().apply { outcomeQueue.forEach { put(it) } })
        put("last_card", lastCard ?: JSONObject.NULL)
        put("baseline_override", baselineOverride?.toJson() ?: JSONObject.NULL)
        lastStatus?.let { put("last_status", it) }
    }

    companion object {
        const val VERSION = 1
        const val MAX_QUEUE = 50
        const val MAX_TRIED = 200

        fun fromJson(o: JSONObject): OptimizeLocalState = OptimizeLocalState(
            profile = OptimizeProfile.fromWire(o.optStringOrNull("profile")),
            started = o.optBoolean("started", false),
            kept = o.optJSONArray("kept").objects().mapNotNull { KeptRung.fromJson(it) },
            pending = PendingRung.fromJson(o.optJSONObject("pending")),
            tried = o.optJSONArray("tried").objects().mapNotNull { TriedRung.fromJson(it) },
            bad = o.optJSONArray("bad").strings(),
            outcomeQueue = o.optJSONArray("outcome_queue").objects(),
            lastCard = o.optJSONObject("last_card"),
            baselineOverride = Metrics.fromJson(o.optJSONObject("baseline_override")),
            lastStatus = o.optStringOrNull("last_status"),
        )
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

    fun rungDir(rungId: String): File = File(dir, rungId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
}
