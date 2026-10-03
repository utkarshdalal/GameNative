package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class DebugRunParams(
    val winedebug: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val attach: Set<String> = emptySet(),
    val minSeconds: Int? = null,
    val instruction: String? = null,
) {
    val isEmpty: Boolean
        get() = winedebug.isEmpty() && env.isEmpty() && attach.isEmpty() && minSeconds == null && instruction.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("channels", JSONArray(winedebug))
        put("env", JSONObject(env))
        put("attach", JSONArray((DEFAULT_ATTACH + attach).toList()))
        put("minSeconds", minSeconds ?: JSONObject.NULL)
        put("instruction", instruction ?: JSONObject.NULL)
    }

    companion object {
        const val ATTACH_LOGCAT = "logcat"
        const val ATTACH_PERF = "perf"
        const val ATTACH_WRAPPER_DIAG = "wrapper_diag"

        const val BASE_WINEDEBUG = "warn+seh,+loaddll,+process,+timestamp,+pid,+tid"

        val DEFAULT_ATTACH: Set<String> = linkedSetOf(ATTACH_LOGCAT, ATTACH_PERF)
        private val ALLOWED_ATTACH = setOf(ATTACH_LOGCAT, ATTACH_PERF, ATTACH_WRAPPER_DIAG)

        private val CHANNEL_PATTERN = Regex("^[+-]?[a-z0-9_]{1,24}$")
        private val BANNED_CHANNELS = setOf("all", "relay", "server", "heap", "snoop", "file")
        private const val MAX_CHANNELS = 8

        val ALLOWED_ENV: Set<String> = setOf(
            "DXVK_LOG_LEVEL", "DXVK_CONFIG", "VKD3D_DEBUG", "VKD3D_SHADER_DEBUG", "VKD3D_CONFIG",
            "WRAPPER_LOG_LEVEL", "TU_DEBUG", "MESA_LOG_LEVEL", "MESA_DEBUG", "MESA_VK_ABORT_ON_DEVICE_LOSS",
            "BOX64_LOG", "BOX64_SHOWSEGV", "BOX64_SHOWBT", "BOX64_DYNAREC_LOG",
            "FEX_SILENTLOG", "FEX_OUTPUTLOG", "FEX_TSOENABLED", "FEX_HALFBARRIERTSOENABLED",
            "WINEDLLOVERRIDES",
        )
        private const val MAX_ENV = 8
        private const val MAX_ENV_VALUE = 128
        private const val MAX_INSTRUCTION = 300
        private const val MAX_MIN_SECONDS = 3600

        fun channelName(item: String): String =
            item.substring(maxOf(item.lastIndexOf('+'), item.lastIndexOf('-')) + 1)

        fun fromJson(run: JSONObject?): DebugRunParams? {
            if (run == null) return null
            val channels = mutableListOf<String>()
            run.optJSONArray("winedebug")?.let { arr ->
                for (i in 0 until arr.length()) {
                    if (channels.size >= MAX_CHANNELS) break
                    val raw = arr.optString(i, "").trim().lowercase()
                    if (!CHANNEL_PATTERN.matches(raw)) continue
                    if (channelName(raw) in BANNED_CHANNELS) continue
                    val item = if (raw.startsWith("+") || raw.startsWith("-")) raw else "+$raw"
                    if (channels.none { channelName(it) == channelName(item) }) channels += item
                }
            }
            val env = linkedMapOf<String, String>()
            run.optJSONObject("env")?.let { obj ->
                val names = obj.keys()
                while (names.hasNext() && env.size < MAX_ENV) {
                    val name = names.next()
                    if (name !in ALLOWED_ENV) continue
                    val value = obj.opt(name)
                    if (value !is String && value !is Number && value !is Boolean) continue
                    val text = value.toString()
                    if (text.length > MAX_ENV_VALUE || text.contains('\n') || text.contains('\r')) continue
                    env[name] = text
                }
            }
            val attach = linkedSetOf<String>()
            run.optJSONArray("attach")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim().lowercase()
                    if (item in ALLOWED_ATTACH && item !in DEFAULT_ATTACH) attach += item
                }
            }
            val minSeconds = if (run.has("minSeconds") && !run.isNull("minSeconds")) {
                run.optInt("minSeconds", 0).takeIf { it in 1..MAX_MIN_SECONDS }
            } else {
                null
            }
            val instruction = if (run.has("instruction") && !run.isNull("instruction")) {
                run.optString("instruction", "").replace(Regex("\\s+"), " ").trim().take(MAX_INSTRUCTION).ifBlank { null }
            } else {
                null
            }
            return DebugRunParams(channels, env, attach, minSeconds, instruction).takeUnless { it.isEmpty }
        }

        fun mergeWineDebug(base: String, extra: List<String>): String {
            val items = base.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val names = items.map { channelName(it) }.toMutableSet()
            for (item in extra) {
                val name = channelName(item)
                if (name.isEmpty() || name == "all" || name in names) continue
                items += item
                names += name
            }
            return items.joinToString(",")
        }

        private fun parseDllOverrides(value: String): LinkedHashMap<String, String> {
            val result = linkedMapOf<String, String>()
            value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val eq = entry.indexOf('=')
                val dlls = if (eq >= 0) entry.substring(0, eq) else entry
                val mode = if (eq >= 0) entry.substring(eq + 1).trim() else ""
                dlls.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { result[it] = mode }
            }
            return result
        }

        fun mergeDllOverrides(existing: String, run: String): String {
            val merged = parseDllOverrides(existing)
            parseDllOverrides(run).forEach { (dll, mode) ->
                merged.remove(dll)
                merged[dll] = mode
            }
            return merged.entries.joinToString(";") { (dll, mode) -> if (mode.isEmpty()) dll else "$dll=$mode" }
        }

        fun applyToDebugEnv(envVars: EnvVars, appId: String, params: DebugRunParams) {
            val extraChannels = buildList {
                addAll(params.winedebug)
                if (ATTACH_WRAPPER_DIAG in params.attach) add("+vulkan")
            }
            envVars.put("WINEDEBUG", mergeWineDebug(BASE_WINEDEBUG, extraChannels))
            if (ATTACH_WRAPPER_DIAG in params.attach) {
                envVars.put("WRAPPER_DIAG", "1")
                envVars.put("WRAPPER_DIAG_APPID", appId)
                envVars.put("WRAPPER_LOG_LEVEL", "info")
            }
            params.env.forEach { (name, value) ->
                if (name == "WINEDLLOVERRIDES") {
                    envVars.put(name, mergeDllOverrides(envVars.get(name), value))
                } else {
                    envVars.put(name, value)
                }
            }
        }
    }
}

object DebugRunParamsHolder {
    private val params = ConcurrentHashMap<String, DebugRunParams>()

    fun set(appId: String, value: DebugRunParams?) {
        if (value == null) params.remove(appId) else params[appId] = value
    }

    fun get(appId: String): DebugRunParams? = params[appId]

    fun clear(appId: String) {
        params.remove(appId)
    }
}
