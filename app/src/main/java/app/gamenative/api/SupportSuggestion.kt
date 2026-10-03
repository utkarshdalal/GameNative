package app.gamenative.api

import app.gamenative.utils.DebugRunParams
import com.winlator.container.Container
import org.json.JSONObject

data class SupportSuggestion(
    val changes: List<Change>,
    val rerun: Boolean,
    val run: DebugRunParams?,
    val applicable: Boolean,
) {
    data class Change(
        val key: String,
        val name: String?,
        val op: String?,
        val from: String?,
        val to: String?,
        val why: String?,
    ) {
        val parent: String get() = key.substringBefore('.')
        val subKey: String? get() = key.substringAfter('.', "").ifEmpty { null }
        val isEnv: Boolean get() = key == ENV_KEY
        val isUnset: Boolean get() = isEnv && op == OP_UNSET
    }

    companion object {
        const val ENV_KEY = "envVars"
        const val OP_SET = "set"
        const val OP_UNSET = "unset"

        val KV_PARENTS = setOf("dxwrapperConfig", "wincomponents", "graphicsDriverConfig")

        private val EXCLUDED_KEYS = setOf(
            "containerVariant", "fexcoreTSOMode", "fexcoreX87Mode", "fexcoreMultiBlock",
            ENV_KEY, "dxwrapperConfig", "wincomponents", "graphicsDriverConfig",
        )

        val TOP_LEVEL_KEYS: Set<String> = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.keys - EXCLUDED_KEYS

        val WIN_COMPONENTS = setOf(
            "direct3d", "directsound", "directinput8", "directinput", "directmusic", "directplay",
            "directshow", "directx", "vcrun2010", "wmdecoder", "opengl",
        )

        private val BLOCKED_ENV = setOf("WINEDEBUG")

        private val ENUM_VALUES: Map<String, Set<String>> = mapOf(
            "dxwrapper" to setOf("wined3d", "dxvk", "vkd3d", "cnc-ddraw"),
            "audioDriver" to setOf("alsa", "pulseaudio", "disabled"),
            "emulator" to setOf("FEXCore", "Box64"),
            "steamType" to setOf(
                Container.STEAM_TYPE_NORMAL, Container.STEAM_TYPE_LIGHT,
                Container.STEAM_TYPE_ULTRALIGHT, Container.STEAM_TYPE_HEADLESS,
            ),
            "suspendPolicy" to setOf(
                Container.SUSPEND_POLICY_AUTO, Container.SUSPEND_POLICY_NEVER, Container.SUSPEND_POLICY_MANUAL,
            ),
            "startupSelection" to setOf("0", "1", "2"),
        )

        private val SUB_KEY = Regex("^[A-Za-z0-9_]{1,40}$")
        private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private const val MAX_CHANGES = 20
        private const val MAX_VALUE = 512
        private const val MAX_KV_VALUE = 128
        private const val MAX_WHY = 500

        private fun JSONObject.text(name: String): String? =
            if (!has(name) || isNull(name)) {
                null
            } else {
                when (val value = opt(name)) {
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> null
                }
            }

        private fun plain(value: String, max: Int): Boolean =
            value.length <= max && value.none { it == '\n' || it == '\r' || it.code < 0x20 }

        fun isValid(change: Change): Boolean {
            val to = change.to
            if (change.isEnv) {
                val name = change.name ?: return false
                if (!ENV_NAME.matches(name) || name.uppercase() in BLOCKED_ENV) return false
                return when (change.op) {
                    OP_UNSET -> true
                    OP_SET -> to != null && plain(to, MAX_VALUE)
                    else -> false
                }
            }
            if (to == null) return false
            val subKey = change.subKey
            if (subKey != null) {
                if (change.parent !in KV_PARENTS || !SUB_KEY.matches(subKey)) return false
                if (!plain(to, MAX_KV_VALUE) || to.contains(',') || to.contains('=')) return false
                if (change.parent == "wincomponents") return subKey in WIN_COMPONENTS && (to == "0" || to == "1")
                return true
            }
            if (change.key !in TOP_LEVEL_KEYS) return false
            ENUM_VALUES[change.key]?.let { return to in it }
            return when (SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[change.key]) {
                SuggestionConfigKeys.ConfigValueType.BOOLEAN -> to.lowercase() in setOf("true", "false", "1", "0")
                SuggestionConfigKeys.ConfigValueType.INT -> to.toIntOrNull() != null
                SuggestionConfigKeys.ConfigValueType.STRING -> plain(to, MAX_VALUE)
                null -> false
            }
        }

        fun parse(json: JSONObject?): SupportSuggestion? {
            if (json == null) return null
            if (json.optInt("v", 0) != 1) return null
            val array = json.optJSONArray("changes")
            val changes = mutableListOf<Change>()
            var applicable = true
            if (array != null) {
                if (array.length() > MAX_CHANGES) applicable = false
                for (i in 0 until minOf(array.length(), MAX_CHANGES)) {
                    val item = array.optJSONObject(i)
                    if (item == null) {
                        applicable = false
                        continue
                    }
                    val key = item.text("key")
                    if (key == null) {
                        applicable = false
                        continue
                    }
                    val change = Change(
                        key = key,
                        name = item.text("name"),
                        op = item.text("op") ?: if (key == ENV_KEY) OP_SET else null,
                        from = item.text("from"),
                        to = item.text("to"),
                        why = item.text("why")?.trim()?.take(MAX_WHY)?.ifEmpty { null },
                    )
                    if (!isValid(change)) applicable = false
                    changes += change
                }
            }
            val duplicate = changes.groupBy { it.key + "\u0000" + (it.name ?: "") }.any { it.value.size > 1 }
            if (duplicate) applicable = false
            val run = DebugRunParams.fromJson(json.optJSONObject("run"))
            val rerun = json.optBoolean("rerun", false)
            if (changes.isEmpty() && run == null && !rerun) return null
            return SupportSuggestion(changes, rerun, run, applicable)
        }
    }
}
