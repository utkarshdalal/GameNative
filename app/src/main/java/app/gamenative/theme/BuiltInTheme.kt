package app.gamenative.theme

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The "Custom" theme the app ships: an ordinary theme folder whose options set `--gn-*` variables,
 * so it is edited with the same options screen as any imported theme.
 */
object BuiltInTheme {

    const val ID = "builtin-custom"
    const val NAME = "Custom"

    private const val YES = "Yes"
    private const val NO = "No"
    private const val DEFAULT = "Default"

    private class ColorPatch(val name: String, val variable: String, val default: String)

    private val COLORS = listOf(
        ColorPatch("Background colour", "gn-background", "#10141c"),
        ColorPatch("Panel colour", "gn-surface", "#182030"),
        ColorPatch("Accent colour", "gn-primary", "#4fc3f7"),
        ColorPatch("Text colour", "gn-text", "#e8edf5"),
        ColorPatch("Secondary text colour", "gn-text-muted", "#9aa6b8"),
        ColorPatch("Focus colour", "gn-focus", "#4fc3f7"),
        ColorPatch("Bar colour", "gn-bar", "#0b0f17"),
        ColorPatch("Tile colour", "gn-card", "#222c40"),
        ColorPatch("Button colour", "gn-button", "#2a3448"),
        ColorPatch("Toggle colour", "gn-toggle", "#4fc3f7"),
        ColorPatch("Menu colour", "gn-menu", "#182030"),
    )

    private val RADII = listOf("0", "4", "8", "12", "16", "24")

    private fun str(s: String) = JsonPrimitive(s)

    private fun arr(vararg s: String) = JsonArray(s.map { str(it) })

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    /** One `--gn-*` value as a patch option injects it. */
    private fun setVar(variable: String, value: String) = obj(variable to arr(value, "All"))

    private fun choice(type: String, default: String, values: List<Pair<String, JsonObject>>, components: JsonArray? = null): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = str(type)
        m["default"] = str(default)
        m["values"] = JsonObject(linkedMapOf(*values.toTypedArray()))
        if (components != null) m["components"] = components
        return JsonObject(m)
    }

    private fun component(name: String, type: String, variable: String, default: String) = obj(
        "name" to str(name),
        "type" to str(type),
        "on" to str(YES),
        "default" to str(default),
        "css_variable" to str(variable),
        "tabs" to arr("All"),
    )

    val themeJson: String by lazy {
        val patches = LinkedHashMap<String, JsonElement>()
        for (c in COLORS) {
            patches[c.name] = choice(
                "checkbox", NO,
                listOf(NO to obj(), YES to obj()),
                JsonArray(listOf(component("Colour", DeckyThemeManifest.COLOR_PICKER, c.variable, c.default))),
            )
        }
        patches["Corner radius"] = choice(
            "dropdown", DEFAULT,
            listOf(DEFAULT to obj()) + RADII.map { it to setVar("--gn-corner-radius", "${it}px") },
        )
        patches["Background image"] = choice(
            "checkbox", NO,
            listOf(NO to obj(), YES to obj()),
            JsonArray(listOf(component("Image", DeckyThemeManifest.IMAGE_PICKER, "gn-background-image", ""))),
        )
        patches["Tile size"] = choice(
            "dropdown", DEFAULT,
            listOf(
                "Small" to setVar("--gn-card-scale", "0.8"),
                DEFAULT to obj(),
                "Large" to setVar("--gn-card-scale", "1.25"),
            ),
        )
        patches["Title alignment"] = choice(
            "dropdown", DEFAULT,
            listOf(
                DEFAULT to obj(),
                "Left" to setVar("--gn-title-align", "start"),
                "Centre" to setVar("--gn-title-align", "center"),
            ),
        )
        patches["Hide badges"] = choice("checkbox", NO, listOf(NO to obj(), YES to setVar("--gn-hide-badges", "1")))
        patches["Hide button hints"] = choice("checkbox", NO, listOf(NO to obj(), YES to setVar("--gn-hide-footer-hints", "1")))
        JsonObject(
            linkedMapOf<String, JsonElement>(
                "name" to str(NAME),
                "author" to str("GameNative"),
                "version" to str(VERSION),
                "description" to str("Pick your own colours and layout."),
                "manifest_version" to JsonPrimitive(DeckyThemeManifest.LOADER_VERSION),
                "target" to str("System-Wide"),
                "inject" to JsonObject(emptyMap()),
                "patches" to JsonObject(patches),
            ),
        ).toString()
    }

    const val VERSION = "v1.0"
}
