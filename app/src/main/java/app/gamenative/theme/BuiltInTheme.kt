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

    private const val COLOUR = "Colour"

    /** Prefix of the helper variables options compose `--gn-*` values from; still `--gn-` so the theme stays shareable. */
    private const val PART = "--gn-custom-"

    private val RADII = listOf("0", "4", "8", "12", "16", "24")
    private val BLURS = listOf("Light" to 6, "Medium" to 12, "Strong" to 24)
    private val OPACITIES = listOf("100%" to "1", "90%" to "0.9", "75%" to "0.75", "50%" to "0.5", "25%" to "0.25")
    private val BAR_HEIGHTS = listOf("Compact" to "0.8", "Tall" to "1.25", "Extra tall" to "1.5")
    private val WIDTHS = listOf(1, 2, 3, 4)

    private fun str(s: String) = JsonPrimitive(s)

    private fun arr(vararg s: String) = JsonArray(s.map { str(it) })

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    /** `--gn-*` values as a patch option injects them. */
    private fun setVars(vararg vars: Pair<String, String>) = JsonObject(linkedMapOf(*vars.map { (k, v) -> k to arr(v, "All") }.toTypedArray()))

    private fun setVar(variable: String, value: String) = setVars(variable to value)

    private fun choice(type: String, default: String, values: List<Pair<String, JsonObject>>, components: List<JsonObject> = emptyList()): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = str(type)
        m["default"] = str(default)
        m["values"] = JsonObject(linkedMapOf(*values.toTypedArray()))
        if (components.isNotEmpty()) m["components"] = JsonArray(components)
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

    private fun colourComponent(variable: String, default: String, name: String = COLOUR) =
        component(name, DeckyThemeManifest.COLOR_PICKER, variable, default)

    /** Patches in display order and the section each one is listed under. */
    private class Builder {
        val patches = LinkedHashMap<String, JsonElement>()
        val sections = LinkedHashMap<String, String>()
        var section = ""

        fun put(name: String, patch: JsonObject) {
            check(name !in patches) { name }
            patches[name] = patch
            sections[name] = section
        }

        fun checkbox(name: String, yes: JsonObject = obj(), components: List<JsonObject> = emptyList()) =
            put(name, choice("checkbox", NO, listOf(NO to obj(), YES to yes), components))

        /** A switch that sets [variable] to the colour picked next to it. */
        fun colour(name: String, variable: String, default: String) = checkbox(name, components = listOf(colourComponent(variable, default)))

        fun flag(name: String, variable: String) = checkbox(name, setVar(variable, "1"))

        /** "Default" (sets nothing) followed by [options]. */
        fun dropdown(name: String, options: List<Pair<String, JsonObject>>) =
            put(name, choice("dropdown", DEFAULT, listOf(DEFAULT to obj()) + options))

        fun values(name: String, variable: String, options: List<Pair<String, String>>) =
            dropdown(name, options.map { (label, v) -> label to setVar(variable, v) })

        fun lengths(name: String, variable: String, options: List<Pair<String, Int>>) =
            values(name, variable, options.map { (label, px) -> label to "${px}px" })

        /**
         * `<width> <colour>` values (lines, glows): a switch with the colour and a size list. The size list keeps
         * [defaultPx] on "Default", so the switch alone gives a visible line.
         */
        fun line(name: String, sizeName: String, variable: String, colour: String, defaultPx: Int, sizes: List<Pair<String, Int>>) {
            val part = PART + variable.removePrefix("--gn-")
            checkbox(name, setVar(variable, "var($part-size, ${defaultPx}px) var($part)"), listOf(colourComponent(part, colour)))
            lengths(sizeName, "$part-size", sizes)
        }

        /** Title / subtitle outline: `<width> <colour> <blur>`. */
        fun stroke(name: String, variable: String) {
            val part = PART + variable.removePrefix("--gn-")
            checkbox(name, setVar(variable, "var($part-size, 2px) var($part) var($part-blur, 0px)"), listOf(colourComponent(part, "#000000")))
            dropdown(
                "$name style",
                listOf(
                    "Thin" to setVar("$part-size", "1px"),
                    "Thick" to setVar("$part-size", "3px"),
                    "Soft shadow" to setVars("$part-size" to "2px", "$part-blur" to "4px"),
                    "Wide shadow" to setVars("$part-size" to "3px", "$part-blur" to "10px"),
                ),
            )
        }

        /**
         * A gradient from a start and end colour (and an optional middle one) with a direction list. [fill] targets
         * take layered backgrounds, so they also offer a glow over the gradient.
         */
        fun gradient(name: String, variable: String, start: String, end: String, middle: Boolean = false, fill: Boolean = true) {
            val part = PART + variable.removePrefix("--gn-")
            val stops = "var($part-stops, var($part-1), var($part-2))"
            checkbox(
                name,
                setVar(variable, "var($part-shape, linear-gradient(180deg, $stops))"),
                listOf(colourComponent("$part-1", start, "Start"), colourComponent("$part-2", end, "End")),
            )
            if (middle) {
                checkbox(
                    "$name middle colour",
                    setVar("$part-stops", "var($part-1), var($part-m), var($part-2)"),
                    listOf(colourComponent("$part-m", start)),
                )
            }
            val shapes = listOf(
                "Bottom to top" to "linear-gradient(0deg, $stops)",
                "Left to right" to "linear-gradient(90deg, $stops)",
                "Right to left" to "linear-gradient(270deg, $stops)",
                "Diagonal" to "linear-gradient(135deg, $stops)",
                "Centred glow" to "radial-gradient(circle at 50% 50%, $stops)",
                "Glow from top" to "radial-gradient(ellipse at 50% 0%, $stops)",
            ) + if (fill) {
                listOf("Glow over gradient" to "radial-gradient(ellipse at 50% 0%, var($part-1), transparent 70%), linear-gradient(180deg, $stops)")
            } else {
                emptyList()
            }
            put(
                "$name direction",
                choice("dropdown", "Top to bottom", listOf("Top to bottom" to obj()) + shapes.map { (label, v) -> label to setVar("$part-shape", v) }),
            )
        }

        /** Bar options shared by the top bar ([prefix] `--gn-header`) and the footer. */
        fun bar(label: String, prefix: String, base: String) {
            colour("$label colour", prefix, base)
            gradient("$label gradient", "$prefix-fill", base, "#182030")
            line("$label line", "$label line width", "$prefix-border", "#4fc3f7", 1, WIDTHS.map { "$it px" to it })
            line("$label glow", "$label glow size", "$prefix-glow", "#4fc3f7", 8, listOf("Small" to 4, "Large" to 16, "Huge" to 24))
            lengths("$label blur", "$prefix-blur", BLURS)
            colour("$label text colour", "$prefix-text", "#e8edf5")
            values("$label height", "$prefix-height", BAR_HEIGHTS)
        }
    }

    private class Built(val json: String, val sections: Map<String, String>)

    private val built: Built by lazy { build() }

    val themeJson: String get() = built.json

    /** Section heading a patch of this theme is listed under. */
    fun sectionOf(patch: String): String? = built.sections[patch]

    private fun build(): Built {
        val b = Builder()
        with(b) {
            section = "Colours"
            colour("Background colour", "--gn-background", "#10141c")
            colour("Panel colour", "--gn-surface", "#182030")
            colour("Raised panel colour", "--gn-surface-variant", "#222c40")
            colour("Accent colour", "--gn-primary", "#4fc3f7")
            colour("Text on accent colour", "--gn-on-primary", "#000000")
            gradient("Accent gradient", "--gn-accent-gradient", "#4fc3f7", "#7e57c2", middle = true, fill = false)
            colour("Outline colour", "--gn-outline", "#3a4660")
            colour("Bar colour", "--gn-bar", "#0b0f17")
            colour("Tile colour", "--gn-card", "#222c40")

            section = "Text"
            checkbox("Font", components = listOf(component("Font file", DeckyThemeManifest.FONT_PICKER, "--gn-font", "")))
            colour("Text colour", "--gn-text", "#e8edf5")
            colour("Secondary text colour", "--gn-text-muted", "#9aa6b8")
            colour("Heading colour", "--gn-heading", "#4fc3f7")
            colour("Subtitle colour", "--gn-subtitle", "#9aa6b8")
            line("Text glow", "Text glow size", "--gn-text-glow", "#4fc3f7", 4, listOf("Small" to 2, "Large" to 8))
            values("Title alignment", "--gn-title-align", listOf("Left" to "start", "Centre" to "center"))
            stroke("Title outline", "--gn-title-stroke")
            stroke("Subtitle outline", "--gn-subtitle-stroke")

            section = "Top bar"
            bar("Top bar", "--gn-header", "#0b0f17")

            section = "Footer"
            bar("Footer", "--gn-footer", "#0b0f17")
            flag("Hide button hints", "--gn-hide-footer-hints")

            section = "Controls"
            colour("Button colour", "--gn-button", "#2a3448")
            colour("Button text colour", "--gn-on-button", "#e8edf5")
            gradient("Button gradient", "--gn-button-fill", "#2a3448", "#182030")
            colour("Focused button colour", "--gn-button-focus", "#4fc3f7")
            colour("Focused button text colour", "--gn-on-button-focus", "#000000")
            line("Button underline", "Button underline width", "--gn-button-underline", "#4fc3f7", 2, WIDTHS.map { "$it px" to it })
            colour("Toggle colour", "--gn-toggle", "#4fc3f7")
            gradient("Switch on gradient", "--gn-toggle-on", "#4fc3f7", "#7e57c2")
            colour("Switch off colour", "--gn-toggle-off", "#3a4660")
            colour("Switch handle on colour", "--gn-thumb-on", "#ffffff")
            colour("Switch handle off colour", "--gn-thumb-off", "#9aa6b8")
            colour("Slider track colour", "--gn-slider-track", "#3a4660")
            colour("Slider handle colour", "--gn-slider-thumb", "#ffffff")

            section = "Focus"
            colour("Focus colour", "--gn-focus", "#4fc3f7")
            lengths("Focus glow", "--gn-focus-glow", listOf("None" to 0, "Small" to 4, "Medium" to 8, "Large" to 16))
            lengths("Focus ring width", "--gn-focus-ring", listOf("None" to 0) + WIDTHS.map { "$it px" to it })
            colour("Focus ring colour", "--gn-focus-ring-color", "#4fc3f7")
            colour("Focused item colour", "--gn-focus-fill", "#4fc3f7")
            colour("Focused item text colour", "--gn-on-focus-fill", "#000000")
            val underline = PART + "focus-underline"
            checkbox(
                "Focus underline",
                setVar("--gn-focus-underline", "linear-gradient(90deg, var($underline-1), var($underline-2))"),
                listOf(colourComponent("$underline-1", "#4fc3f7", "Start"), colourComponent("$underline-2", "#7e57c2", "End")),
            )
            lengths("Focus underline height", "--gn-focus-underline-height", WIDTHS.map { "$it px" to it })
            flag("Pulsing focus ring", "--gn-focus-blink")

            section = "Menus and panels"
            colour("Menu colour", "--gn-menu", "#182030")
            colour("Menu text colour", "--gn-on-menu", "#e8edf5")
            gradient("Menu gradient", "--gn-menu-fill", "#182030", "#10141c")
            values("Menu opacity", "--gn-menu-opacity", OPACITIES)
            lengths("Menu blur", "--gn-menu-blur", BLURS)
            lengths("Menu corner radius", "--gn-menu-radius", RADII.map { it to it.toInt() })
            line("Menu border", "Menu border width", "--gn-menu-border", "#4fc3f7", 1, WIDTHS.map { "$it px" to it })
            lengths("Menu glow", "--gn-menu-glow", listOf("Small" to 6, "Medium" to 12, "Large" to 24))
            colour("Focused menu item colour", "--gn-menu-focus", "#4fc3f7")
            colour("Focused menu item text colour", "--gn-on-menu-focus", "#000000")
            values("Panel opacity", "--gn-panel-opacity", OPACITIES)
            gradient("Panel gradient", "--gn-panel-fill", "#182030", "#10141c")
            lengths("Panel blur", "--gn-blur", BLURS)

            section = "Background"
            checkbox("Background image", components = listOf(component("Image", DeckyThemeManifest.IMAGE_PICKER, "--gn-background-image", "")))
            gradient("Background gradient", "--gn-background-gradient", "#10141c", "#1f2a44", middle = true, fill = false)

            section = "Layout and shape"
            dropdown("Corner radius", RADII.map { it to setVar("--gn-corner-radius", "${it}px") })
            lengths("Tile corner radius", "--gn-card-corner-radius", RADII.map { it to it.toInt() })
            lengths("Icon corner radius", "--gn-icon-corner-radius", RADII.map { it to it.toInt() })
            put(
                "Tile size",
                choice(
                    "dropdown", DEFAULT,
                    listOf(
                        "Extra small" to setVar("--gn-card-scale", "0.65"),
                        "Small" to setVar("--gn-card-scale", "0.8"),
                        DEFAULT to obj(),
                        "Large" to setVar("--gn-card-scale", "1.25"),
                        "Extra large" to setVar("--gn-card-scale", "1.5"),
                    ),
                ),
            )
            values("Tile tilt", "--gn-card-tilt", listOf("-6°" to "-6deg", "-3°" to "-3deg", "3°" to "3deg", "6°" to "6deg"))
            values("Library layout", "--gn-library-layout", listOf("Grid" to "grid", "List" to "list", "Carousel" to "carousel"))
            values("Game artwork height", "--gn-hero-scale", listOf("Short" to "0.75", "Tall" to "1.25", "Extra tall" to "1.5"))
            flag("Hide badges", "--gn-hide-badges")
        }
        val json = JsonObject(
            linkedMapOf<String, JsonElement>(
                "name" to str(NAME),
                "author" to str("GameNative"),
                "version" to str(VERSION),
                "description" to str("Pick your own colours and layout."),
                "manifest_version" to JsonPrimitive(DeckyThemeManifest.LOADER_VERSION),
                "target" to str("System-Wide"),
                "inject" to JsonObject(emptyMap()),
                "patches" to JsonObject(b.patches),
            ),
        ).toString()
        return Built(json, b.sections)
    }

    const val VERSION = "v2.0"
}
