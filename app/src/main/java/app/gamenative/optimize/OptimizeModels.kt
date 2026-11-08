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
    /** Mean fps of each five-minute window, oldest first. */
    val fpsBy5Min: List<Int> = emptyList(),
    /** Mean GPU busy percent of each five-minute window that sampled it (perf_by_5min gpu_avg), oldest first. */
    val gpuBy5Min: List<Int> = emptyList(),
    /** Mean GPU clock in MHz of each five-minute window that sampled it (perf_by_5min gpu_clk_mhz_avg), oldest first. */
    val gpuClockBy5Min: List<Int> = emptyList(),
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
        if (fpsBy5Min.isNotEmpty()) put("fps_by_5min", JSONArray(fpsBy5Min))
        if (gpuBy5Min.isNotEmpty()) put("gpu_by_5min", JSONArray(gpuBy5Min))
        if (gpuClockBy5Min.isNotEmpty()) put("gpu_clock_by_5min", JSONArray(gpuClockBy5Min))
    }

    fun toReportJson(): JSONObject = JSONObject().apply {
        put("gameplay_s", gameplaySec)
        put("steady_fps", steadyFps ?: JSONObject.NULL)
        put("stutter_share", stutterShare ?: JSONObject.NULL)
        put("throttle_onset_s", throttleOnsetSec ?: JSONObject.NULL)
        put("class", cls ?: JSONObject.NULL)
        put("confidence", confidence ?: JSONObject.NULL)
        if (fpsBy5Min.isNotEmpty()) put("fps_by_5min", JSONArray(fpsBy5Min.take(MAX_REPORT_WINDOWS)))
        if (gpuBy5Min.isNotEmpty()) put("gpu_by_5min", JSONArray(gpuBy5Min.take(MAX_REPORT_WINDOWS)))
        if (gpuClockBy5Min.isNotEmpty()) put("gpu_clock_by_5min", JSONArray(gpuClockBy5Min.take(MAX_REPORT_WINDOWS)))
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
        /** The server accepts at most this many windows per series in an outcome. */
        const val MAX_REPORT_WINDOWS = 48

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
                fpsBy5Min = o.optJSONArray("fps_by_5min").ints(),
                gpuBy5Min = o.optJSONArray("gpu_by_5min").ints(),
                gpuClockBy5Min = o.optJSONArray("gpu_clock_by_5min").ints(),
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
                fpsBy5Min = run.optJSONArray("fps_by_5min").ints(),
                gpuBy5Min = run.optJSONArray("perf_by_5min").objects().mapNotNull { it.optIntOrNull("gpu_avg") },
                gpuClockBy5Min = run.optJSONArray("perf_by_5min").objects().mapNotNull { it.optIntOrNull("gpu_clk_mhz_avg") },
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

internal fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> (opt(i) as? Number)?.toInt() }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }

/** [ladder] is the key's twelve values from lowest to highest quality and [lowerIs] which way lower quality goes. */
data class FileOp(
    val op: String,
    val section: String,
    val key: String,
    val value: String?,
    val ladder: List<String?>? = null,
    val lowerIs: String? = null,
) {
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
            return FileOp(
                op, o.optStringOrNull("section").orEmpty(), key, if (op == "set") value else null,
                ladderOf(o), o.optStringOrNull("lower_is"),
            )
        }
    }
}

/** A key's quality ladder as the server sends it: step 0 is the lowest quality, null where a step has no value. */
internal fun ladderOf(o: JSONObject): List<String?>? {
    val a = o.optJSONArray("ladder") ?: return null
    return (0 until a.length()).map { i -> if (a.isNull(i)) null else a.opt(i).toString() }
}

data class RegistryValue(
    val name: String,
    val type: String?,
    val data: String?,
    val delete: Boolean,
    val ladder: List<String?>? = null,
    val lowerIs: String? = null,
)

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
                        RegistryValue(name, valueType, data, delete, ladderOf(v), v.optStringOrNull("lower_is"))
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
    /** Each file and registry value only moves toward lower quality from what the game has now. */
    val relative: Boolean = false,
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
                relative = o.optBoolean("relative", false),
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
        const val DEFAULT_TRIAL_GAMES_LIMIT = 3

        fun fromJson(o: JSONObject?): Entitlement? {
            if (o == null) return null
            val state = o.optStringOrNull("state") ?: return null
            return Entitlement(state, o.optInt("trial_games_used", 0), o.optInt("trial_games_limit", 0))
        }
    }
}

fun Entitlement?.limitOrDefault(): Int = this?.trialGamesLimit?.takeIf { it > 0 } ?: Entitlement.DEFAULT_TRIAL_GAMES_LIMIT

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
