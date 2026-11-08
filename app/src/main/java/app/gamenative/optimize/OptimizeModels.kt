package app.gamenative.optimize

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class Metrics(
    val gameplaySec: Int,
    val steadyFps: Double? = null,
    val stutterShare: Double? = null,
    val throttleOnsetSec: Int? = null,
    val cls: String? = null,
    val confidence: Double? = null,
    val confident: Boolean? = null,
    val symptom: String? = null,
    val exitReason: String? = null,
    val windowSeen: Boolean = true,
    val procStatReal: Boolean? = null,
    val thermalReporting: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gameplay_s", gameplaySec)
        steadyFps?.let { put("steady_fps", it) }
        stutterShare?.let { put("stutter_share", it) }
        throttleOnsetSec?.let { put("throttle_onset_s", it) }
        cls?.let { put("class", it) }
        confidence?.let { put("confidence", it) }
        confident?.let { put("confident", it) }
        symptom?.let { put("symptom", it) }
        exitReason?.let { put("exit_reason", it) }
        put("window_seen", windowSeen)
        procStatReal?.let { put("proc_stat_real", it) }
        thermalReporting?.let { put("thermal_reporting", it) }
    }

    fun toReportJson(): JSONObject = JSONObject().apply {
        put("gameplay_s", gameplaySec)
        put("steady_fps", steadyFps ?: JSONObject.NULL)
        put("stutter_share", stutterShare ?: JSONObject.NULL)
        put("throttle_onset_s", throttleOnsetSec ?: JSONObject.NULL)
        put("class", cls ?: JSONObject.NULL)
        put("confidence", confidence ?: JSONObject.NULL)
    }

    fun toVerdictJson(): JSONObject? {
        val c = cls ?: return null
        return JSONObject().apply {
            put("class", c)
            put("confidence", confidence ?: 0.0)
            put("symptom", symptom ?: JSONObject.NULL)
            put("steady_fps", steadyFps ?: JSONObject.NULL)
            put("stutter_share", stutterShare ?: JSONObject.NULL)
            put("throttle_onset_s", throttleOnsetSec ?: JSONObject.NULL)
            put("gameplay_s", gameplaySec)
        }
    }

    companion object {
        fun fromJson(o: JSONObject?): Metrics? {
            if (o == null || !o.has("gameplay_s")) return null
            return Metrics(
                gameplaySec = o.optInt("gameplay_s", 0),
                steadyFps = o.optDoubleOrNull("steady_fps"),
                stutterShare = o.optDoubleOrNull("stutter_share"),
                throttleOnsetSec = o.optIntOrNull("throttle_onset_s"),
                cls = o.optStringOrNull("class"),
                confidence = o.optDoubleOrNull("confidence"),
                confident = if (o.has("confident") && !o.isNull("confident")) o.optBoolean("confident") else null,
                symptom = o.optStringOrNull("symptom"),
                exitReason = o.optStringOrNull("exit_reason"),
                windowSeen = o.optBoolean("window_seen", true),
                procStatReal = if (o.has("proc_stat_real")) o.optBoolean("proc_stat_real") else null,
                thermalReporting = if (o.has("thermal_reporting")) o.optBoolean("thermal_reporting") else null,
            )
        }

        /** Reads a SessionReport run record (the game_exited properties). */
        fun fromRun(run: JSONObject): Metrics {
            val verdict = run.optJSONObject("perf_verdict")
            val gameplay = verdict?.optIntOrNull("gameplay_s") ?: run.optInt("session_length", 0)
            return Metrics(
                gameplaySec = gameplay,
                steadyFps = run.optDoubleOrNull("avg_fps")?.takeIf { it > 0.0 },
                stutterShare = run.optDoubleOrNull("stutter_share"),
                throttleOnsetSec = run.optIntOrNull("throttle_onset_s"),
                cls = verdict?.optStringOrNull("class"),
                confidence = verdict?.optDoubleOrNull("confidence"),
                confident = verdict?.takeIf { it.has("confident") && !it.isNull("confident") }?.optBoolean("confident"),
                symptom = verdict?.optStringOrNull("symptom"),
                exitReason = run.optStringOrNull("exit_reason"),
                windowSeen = run.has("main_window_class"),
                procStatReal = verdict?.optStringOrNull("proc_stat")?.let { it == "real" },
                thermalReporting = run.has("thermal_headroom") || run.optInt("thermal_peak", 0) > 0,
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt() ?: optString(key).toIntOrNull()

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) {
        null
    } else {
        ((opt(key) as? Number)?.toDouble() ?: optString(key).toDoubleOrNull())?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }

data class FileOp(val op: String, val section: String, val key: String, val value: String?) {
    val isDelete: Boolean get() = op == "delete"

    fun toJson(): JSONObject = JSONObject().apply {
        put("op", op)
        put("section", section)
        put("key", key)
        put("value", value ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): FileOp? {
            val op = o.optString("op")
            val key = o.optStringOrNull("key") ?: return null
            if (op != "set" && op != "delete") return null
            val value = o.optStringOrNull("value") ?: if (o.has("value") && !o.isNull("value")) "" else null
            if (op == "set" && value == null) return null
            return FileOp(op, o.optStringOrNull("section").orEmpty(), key, if (op == "set") value else null)
        }
    }
}

data class RegistryValue(val name: String, val type: String?, val data: String?, val delete: Boolean)

sealed class Lever {
    abstract val type: String

    data class EngineFile(
        val path: String,
        val format: String,
        val encoding: String,
        val createIfMissing: Boolean,
        val ops: List<FileOp>,
    ) : Lever() {
        override val type: String get() = TYPE_ENGINE_FILE
    }

    data class Registry(val hive: String, val key: String, val values: List<RegistryValue>) : Lever() {
        override val type: String get() = TYPE_REGISTRY
    }

    data class ContainerSet(val set: List<Pair<String, String>>) : Lever() {
        override val type: String get() = TYPE_CONTAINER
    }

    /** Mesa driconf options; [executable] is an exe file name, or null for every process. */
    data class Drirc(val options: List<Pair<String, String>>, val executable: String?) : Lever() {
        override val type: String get() = TYPE_DRIRC
    }

    data class Unknown(override val type: String) : Lever()

    companion object {
        const val TYPE_ENGINE_FILE = "engine_file"
        const val TYPE_REGISTRY = "registry"
        const val TYPE_CONTAINER = "container"
        const val TYPE_DRIRC = "drirc"

        private val DRIRC_OPTION = Regex("^[A-Za-z0-9_]{1,64}$")
        private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

        private fun plainText(s: String, max: Int): Boolean = s.length <= max && !CONTROL.containsMatchIn(s)

        fun parse(o: JSONObject): Lever {
            val type = o.optString("lever_type")
            val payload = o.optJSONObject("payload") ?: return Unknown(type)
            return when (type) {
                TYPE_ENGINE_FILE -> {
                    val path = payload.optStringOrNull("path") ?: return Unknown(type)
                    val opsArray = payload.optJSONArray("ops").objects()
                    val ops = opsArray.mapNotNull { FileOp.fromJson(it) }
                    if (ops.isEmpty() || ops.size != opsArray.size) return Unknown(type)
                    EngineFile(
                        path = path,
                        format = payload.optString("format"),
                        encoding = payload.optStringOrNull("encoding") ?: "default",
                        createIfMissing = payload.optBoolean("create_if_missing", false),
                        ops = ops,
                    )
                }
                TYPE_REGISTRY -> {
                    val key = payload.optStringOrNull("key") ?: return Unknown(type)
                    val raw = payload.optJSONArray("values").objects()
                    val values = raw.mapNotNull { v ->
                        val name = if (v.has("name") && !v.isNull("name")) v.optString("name") else return@mapNotNull null
                        val delete = v.optBoolean("delete", false)
                        val valueType = v.optStringOrNull("type")
                        val data = if (v.has("data") && !v.isNull("data")) v.optString("data") else null
                        if (!delete && (valueType == null || data == null)) return@mapNotNull null
                        RegistryValue(name, valueType, data, delete)
                    }
                    if (values.isEmpty() || values.size != raw.size) return Unknown(type)
                    Registry(payload.optString("hive", "HKCU"), key, values)
                }
                TYPE_CONTAINER -> {
                    val raw = payload.optJSONArray("set").objects()
                    val set = raw.mapNotNull { s ->
                        val key = s.optStringOrNull("key") ?: return@mapNotNull null
                        if (!s.has("value") || s.isNull("value")) return@mapNotNull null
                        key to s.optString("value")
                    }
                    if (set.isEmpty() || set.size != raw.size) return Unknown(type)
                    ContainerSet(set)
                }
                TYPE_DRIRC -> {
                    val raw = payload.optJSONObject("options") ?: return Unknown(type)
                    val options = raw.keys().asSequence().toList().map { name ->
                        if (!DRIRC_OPTION.matches(name) || raw.isNull(name)) return Unknown(type)
                        val value = raw.optString(name)
                        if (!plainText(value, 256)) return Unknown(type)
                        name to value
                    }
                    if (options.isEmpty()) return Unknown(type)
                    val executable = payload.optStringOrNull("executable")
                    if (executable != null && (!plainText(executable, 128) || executable.contains('/') || executable.contains('\\'))) {
                        return Unknown(type)
                    }
                    Drirc(options, executable)
                }
                else -> Unknown(type)
            }
        }
    }
}

class Rung(
    val raw: JSONObject,
    val rungId: String,
    val version: String,
    val profile: String,
    val cls: String?,
    val leverClasses: List<String>,
    val order: Int,
    val remaining: Int,
    val isFpsCap: Boolean,
    val outcome: String?,
    val visualCostId: String?,
    val visualCostEn: String?,
    val levers: List<Lever>,
    val expiresAtMillis: Long?,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis > expiresAtMillis

    companion object {
        fun parse(o: JSONObject?): Rung? {
            if (o == null) return null
            val id = o.optStringOrNull("rung_id") ?: return null
            return Rung(
                raw = o,
                rungId = id,
                version = o.optStringOrNull("version").orEmpty(),
                profile = o.optString("profile"),
                cls = o.optStringOrNull("class"),
                leverClasses = o.optJSONArray("lever_classes").strings(),
                order = o.optInt("order", 0),
                remaining = o.optInt("remaining", 0),
                isFpsCap = o.optBoolean("is_fps_cap", false),
                outcome = o.optStringOrNull("outcome"),
                visualCostId = o.optStringOrNull("visual_cost_id"),
                visualCostEn = o.optStringOrNull("visual_cost_en"),
                levers = o.optJSONArray("levers").objects().map { Lever.parse(it) },
                expiresAtMillis = o.optStringOrNull("expires_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
    }
}

data class Entitlement(val state: String, val trialGamesUsed: Int, val trialGamesLimit: Int) {
    val isMember: Boolean get() = state == MEMBER
    val trialGamesLeft: Int get() = (trialGamesLimit - trialGamesUsed).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state)
        put("trial_games_used", trialGamesUsed)
        put("trial_games_limit", trialGamesLimit)
    }

    companion object {
        const val MEMBER = "member"
        const val TRIAL = "trial"
        const val NONE = "none"

        fun fromJson(o: JSONObject?): Entitlement? {
            if (o == null) return null
            val state = o.optStringOrNull("state") ?: return null
            return Entitlement(state, o.optInt("trial_games_used", 0), o.optInt("trial_games_limit", 0))
        }
    }
}

data class NextRungResponse(val status: String, val messageId: String?, val rung: Rung?, val entitlement: Entitlement? = null) {
    companion object {
        const val STATUS_RUNG = "rung"
        const val STATUS_EXHAUSTED = "exhausted"
        const val STATUS_ENTITLEMENT_REQUIRED = "entitlement_required"

        fun parse(o: JSONObject): NextRungResponse = NextRungResponse(
            o.optString("status"),
            o.optStringOrNull("message_id"),
            Rung.parse(o.optJSONObject("rung")),
            Entitlement.fromJson(o.optJSONObject("entitlement")),
        )
    }
}
