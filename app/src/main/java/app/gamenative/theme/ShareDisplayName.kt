package app.gamenative.theme

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The public name shown on themes shared from this device, kept in the theme state file. */
internal object ShareDisplayName {
    const val STATE_KEY = "shareDisplayName"

    fun normalize(name: String): String = name.trim()

    fun read(state: JsonObject?): String =
        (state?.get(STATE_KEY) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize).orEmpty()

    fun write(state: MutableMap<String, JsonElement>, name: String) {
        val n = normalize(name)
        if (n.isEmpty()) state.remove(STATE_KEY) else state[STATE_KEY] = JsonPrimitive(n)
    }
}
