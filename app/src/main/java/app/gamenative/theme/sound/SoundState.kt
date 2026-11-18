package app.gamenative.theme.sound

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

data class InstalledSoundPack(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val isMusic: Boolean = false,
    val soundCount: Int = 0,
)

data class SoundSettings(
    val enabled: Boolean = false,
    val volume: Float = 0.6f,
    val activePackId: String? = null,
    val musicEnabled: Boolean = false,
    val musicVolume: Float = 0.4f,
    val activeMusicPackId: String? = null,
)

/** Store record a pack folder was installed from. */
data class SoundPackOrigin(val storeId: String? = null, val storeVersion: String? = null)

/** Contents of `soundpacks/state.json`. Pure JVM. */
data class SoundState(
    val settings: SoundSettings = SoundSettings(),
    val origins: Map<String, SoundPackOrigin> = emptyMap(),
) {
    fun toJson(): String = JsonObject(
        mapOf(
            "settings" to JsonObject(
                buildMap {
                    put("enabled", JsonPrimitive(settings.enabled))
                    put("volume", JsonPrimitive(settings.volume))
                    settings.activePackId?.let { put("activePackId", JsonPrimitive(it)) }
                    put("musicEnabled", JsonPrimitive(settings.musicEnabled))
                    put("musicVolume", JsonPrimitive(settings.musicVolume))
                    settings.activeMusicPackId?.let { put("activeMusicPackId", JsonPrimitive(it)) }
                },
            ),
            "packs" to JsonObject(
                origins.mapValues { (_, o) ->
                    JsonObject(
                        buildMap {
                            o.storeId?.let { put("storeId", JsonPrimitive(it)) }
                            o.storeVersion?.let { put("storeVersion", JsonPrimitive(it)) }
                        },
                    )
                },
            ),
        ),
    ).toString()

    companion object {
        fun clampVolume(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

        /** Defaults for anything missing or unreadable. */
        fun parse(text: String?): SoundState {
            if (text.isNullOrBlank()) return SoundState()
            val root = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return SoundState()
            val s = root["settings"] as? JsonObject ?: JsonObject(emptyMap())
            fun bool(k: String, d: Boolean) = (s[k] as? JsonPrimitive)?.booleanOrNull ?: d
            fun vol(k: String, d: Float) = (s[k] as? JsonPrimitive)?.floatOrNull?.let(::clampVolume) ?: d
            fun str(k: String) = (s[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
            val defaults = SoundSettings()
            val settings = SoundSettings(
                enabled = bool("enabled", defaults.enabled),
                volume = vol("volume", defaults.volume),
                activePackId = str("activePackId"),
                musicEnabled = bool("musicEnabled", defaults.musicEnabled),
                musicVolume = vol("musicVolume", defaults.musicVolume),
                activeMusicPackId = str("activeMusicPackId"),
            )
            val origins = LinkedHashMap<String, SoundPackOrigin>()
            (root["packs"] as? JsonObject)?.forEach { (id, v) ->
                val o = v as? JsonObject ?: return@forEach
                origins[id] = SoundPackOrigin(
                    storeId = (o["storeId"] as? JsonPrimitive)?.contentOrNull,
                    storeVersion = (o["storeVersion"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            return SoundState(settings, origins)
        }
    }
}
