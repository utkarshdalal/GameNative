package app.gamenative.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One CSS file or generated CSS text a theme injects, in CSS Loader order. */
data class ThemeInject(
    /** Theme-relative CSS file, or null when [css] holds generated text. */
    val file: String?,
    val css: String?,
    /** Expanded tab targets. */
    val tabs: List<String>,
) {
    /** True when one of the tabs is Big Picture / Steam menu / Quick Access, the tabs this app mirrors. */
    val relevant: Boolean get() = tabs.any { DeckyThemeManifest.isRelevantTab(it) }
}

/** Per-theme option values chosen by the user; missing entries use the theme defaults. */
data class ThemeValues(
    val choices: Map<String, String> = emptyMap(),
    /** "<patch>/<component>" -> colour value as CSS Loader stores it (#hex or hsl()). */
    val colors: Map<String, String> = emptyMap(),
    /** "<patch>/<component>" -> absolute path of an image the user picked. */
    val images: Map<String, String> = emptyMap(),
)

/** theme.json of a CSS Loader theme, with the reference loader's defaults and legacy formats. */
data class DeckyThemeManifest(
    val name: String,
    val displayName: String?,
    val id: String,
    val author: String?,
    val version: String,
    val description: String?,
    val manifestVersion: Int,
    val target: String?,
    val flags: List<String>,
    val tabMappings: Map<String, List<String>>,
    val inject: List<Pair<String, List<String>>>,
    val patches: List<Patch>,
    /** Theme name -> {patch: value} applied to that dependency. */
    val dependencies: Map<String, Map<String, String>>,
    /** No theme.json: the folder only has a theme.css. */
    val cssOnly: Boolean = false,
) {
    data class Patch(
        val name: String,
        val default: String,
        val type: String,
        val options: List<Pair<String, List<Pair<String, List<String>>>>>,
        val components: List<Component>,
    ) {
        val optionNames: List<String> get() = options.map { it.first }
        fun valueOf(values: ThemeValues): String = values.choices[name]?.takeIf { it in optionNames } ?: default
    }

    data class Component(
        val patch: String,
        val name: String,
        val type: String,
        val default: String,
        val on: String,
        val cssVariable: String,
        val tabs: List<String>,
    ) {
        val key: String get() = "$patch/$name"
    }

    val label: String get() = displayName ?: name

    /** Every inject at [values], in the reference loader's order: theme injects, then each patch's active option. */
    fun injects(values: ThemeValues): List<ThemeInject> {
        if (cssOnly) return listOf(ThemeInject("theme.css", null, listOf(".*")))
        val out = ArrayList<ThemeInject>()
        for ((k, tabs) in inject) out.add(toInject(k, tabs))
        for (p in patches) {
            val v = p.valueOf(values)
            p.options.firstOrNull { it.first == v }?.second?.forEach { (k, tabs) -> out.add(toInject(k, tabs)) }
            for (c in p.components) {
                if (c.on != v) continue
                val css = componentCss(c, values) ?: continue
                out.add(ThemeInject(null, css, extendTabs(c.tabs)))
            }
        }
        return out
    }

    private fun toInject(key: String, tabs: List<String>): ThemeInject =
        if (key.startsWith("--")) {
            val value = tabs.firstOrNull() ?: ""
            ThemeInject(null, ":root { $key: $value; }", extendTabs(tabs.drop(1)))
        } else {
            ThemeInject(key, null, extendTabs(tabs))
        }

    fun extendTabs(tabs: List<String>, depth: Int = 0): List<String> {
        if (depth > 20) return emptyList()
        if (tabs.isEmpty()) return tabMappings["default"]?.let { extendTabs(it, depth + 1) } ?: emptyList()
        val out = ArrayList<String>()
        for (x in tabs) {
            val mapped = tabMappings[x] ?: DEFAULT_MAPPINGS[x]
            if (mapped != null) out.addAll(extendTabs(mapped, depth + 1)) else out.add(x)
        }
        return out
    }

    fun componentValue(c: Component, values: ThemeValues): String =
        if (c.type == COLOR_PICKER) values.colors[c.key] ?: c.default else c.default

    /** The `:root` CSS a component injects, as the reference loader generates it. */
    fun componentCss(c: Component, values: ThemeValues): String? {
        val v = componentValue(c, values)
        if (c.cssVariable.contains(';') || v.contains(';')) return null
        val name = c.cssVariable
        return when (c.type) {
            COLOR_PICKER -> {
                val rgb = componentRgb(v)
                if (rgb == null) {
                    ":root { $name: $v; }"
                } else {
                    val (r, g, b) = rgb
                    ":root { $name: $v; ${name}_r: $r; ${name}_g: $g; ${name}_b: $b; ${name}_rgb: $r, $g, $b; }"
                }
            }
            IMAGE_PICKER -> {
                val picked = values.images[c.key]
                if (picked != null) {
                    ":root { $name: url(\"file://${picked.replace("\"", "%22")}\") }"
                } else {
                    if (!isSafeRelative(v)) return null
                    ":root { $name: url(/themes_custom/${v.replace(" ", "%20").replace('\\', '/')}) }"
                }
            }
            else -> null
        }
    }

    companion object {
        const val COLOR_PICKER = "color-picker"
        const val IMAGE_PICKER = "image-picker"
        const val LOADER_VERSION = 9

        private val BIGPICTURE = setOf("~Valve Steam Gamepad/default~", "~Valve%20Steam%20Gamepad~")

        val DEFAULT_MAPPINGS: Map<String, List<String>> = mapOf(
            "desktop" to listOf("Steam|SteamLibraryWindow"),
            "desktopchat" to listOf("!friendsui-container"),
            "desktoppopup" to listOf(
                "OverlayBrowser_Browser", "SP Overlay:.*", "notificationtoasts_.*", "SteamBrowser_Find",
                "OverlayTab\\d+_Find", "!ModalDialogPopup", "!FullModalOverlay",
            ),
            "desktopoverlay" to listOf("desktoppopup"),
            "desktopcontextmenu" to listOf(".*Menu", ".*Supernav"),
            "bigpicture" to BIGPICTURE.toList(),
            "bigpictureoverlay" to listOf("QuickAccess", "MainMenu"),
            "store" to listOf("~https://store.steampowered.com~", "~https://steamcommunity.com~"),
            "SP" to listOf("bigpicture"),
            "Steam Big Picture Mode" to listOf("bigpicture"),
            "MainMenu" to listOf("MainMenu.*"),
            "MainMenu_.*" to listOf("MainMenu"),
            "QuickAccess" to listOf("QuickAccess.*"),
            "QuickAccess_.*" to listOf("QuickAccess"),
            "Steam" to listOf("desktop"),
            "SteamLibraryWindow" to listOf("desktop"),
            "All" to listOf("bigpicture", "bigpictureoverlay"),
        )

        fun isRelevantTab(t: String): Boolean =
            t in BIGPICTURE || t.startsWith("MainMenu") || t.startsWith("QuickAccess") || t == ".*"

        fun isSafeRelative(path: String): Boolean {
            val p = path.trim()
            if (p.isEmpty() || p.startsWith("/") || p.startsWith("\\") || p.contains(':')) return false
            return p.split('/', '\\').none { it.trim() == ".." }
        }

        /** r, g, b the reference loader derives for `_r/_g/_b/_rgb` (its short-hex path gives 0..15 per channel). */
        fun componentRgb(v: String): Triple<Int, Int, Int>? = try {
            if (v.startsWith("#")) {
                val h = v.substring(1)
                if (h.length < 6) Triple(h[0].digitToInt(16), h[1].digitToInt(16), h[2].digitToInt(16))
                else Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
            } else if ((v.startsWith("hsla(") || v.startsWith("hsl(")) && v.endsWith(")")) {
                val parts = v.substring(v.indexOf('(') + 1, v.length - 1).split(',')
                hslToRgb(parts[0].trim().toDouble(), parts[1].trim().dropLast(1).toDouble(), parts[2].trim().dropLast(1).toDouble())
            } else {
                null
            }
        } catch (e: RuntimeException) {
            null
        }

        private fun hslToRgb(hue: Double, sat: Double, light: Double): Triple<Int, Int, Int> {
            val h = hue / 360.0
            val l = light / 100.0
            val s = sat / 100.0
            if (s == 0.0) return Triple((l * 100).toInt(), (l * 100).toInt(), (l * 100).toInt())
            val m2 = if (l <= 0.5) l * (1 + s) else l + s - l * s
            val m1 = 2 * l - m2
            fun mask(hh0: Double): Double {
                val hh = ((hh0 % 1.0) + 1.0) % 1.0
                return when {
                    hh < 1.0 / 6 -> m1 + (m2 - m1) * hh * 6
                    hh < 0.5 -> m2
                    hh < 2.0 / 3 -> m1 + (m2 - m1) * (2.0 / 3 - hh) * 6
                    else -> m1
                }
            }
            return Triple((mask(h + 1.0 / 3) * 255).toInt(), (mask(h) * 255).toInt(), (mask(h - 1.0 / 3) * 255).toInt())
        }

        private fun isValidHex(v: String): Boolean =
            v.startsWith("#") && v.length in setOf(4, 5, 7, 9) && v.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        /** A folder with only theme.css. */
        fun cssOnly(folderName: String) = DeckyThemeManifest(
            name = folderName, displayName = null, id = folderName, author = null, version = "v1.0", description = null,
            manifestVersion = 1, target = null, flags = emptyList(), tabMappings = emptyMap(), inject = emptyList(),
            patches = emptyList(), dependencies = emptyMap(), cssOnly = true,
        )

        /** Parses theme.json; throws IllegalArgumentException when it cannot be a theme. */
        fun parse(text: String): DeckyThemeManifest {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("theme.json is not an object")
            val name = root.str("name")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("theme.json has no name")
            val require = root["manifest_version"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 1
            if (require > LOADER_VERSION) {
                throw IllegalArgumentException("Theme needs a newer CSS Loader (manifest version $require)")
            }
            val tabs = (root["tabs"] as? JsonObject)?.mapValues { strList(it.value) } ?: emptyMap()
            val patches = ArrayList<Patch>()
            (root["patches"] as? JsonObject)?.forEach { (pname, pv) -> (pv as? JsonObject)?.let { parsePatch(pname, it) }?.let(patches::add) }
            val deps = LinkedHashMap<String, Map<String, String>>()
            when (val d = root["dependencies"]) {
                is JsonObject -> d.forEach { (k, v) ->
                    deps[k] = (v as? JsonObject)?.mapNotNull { (pk, pv) -> (pv as? JsonPrimitive)?.contentOrNull?.let { pk to it } }?.toMap()
                        ?: emptyMap()
                }
                is JsonArray -> strList(d).forEach { deps[it] = emptyMap() }
                else -> {}
            }
            return DeckyThemeManifest(
                name = name,
                displayName = root.str("display_name"),
                id = root.str("id") ?: name,
                author = root.str("author"),
                version = root.str("version") ?: "v1.0",
                description = root.str("description"),
                manifestVersion = require,
                target = root.str("target"),
                flags = strList(root["flags"]).map { it.uppercase() },
                tabMappings = tabs,
                inject = injectMap(root["inject"]),
                patches = patches,
                dependencies = deps,
            )
        }

        private fun parsePatch(name: String, p: JsonObject): Patch? {
            val opts: List<Pair<String, List<Pair<String, List<String>>>>> =
                if (p.containsKey("values")) {
                    (p["values"] as? JsonObject)?.map { (k, v) -> k to injectMap(v) } ?: emptyList()
                } else {
                    p.filter { (k, v) -> k != "default" && v is JsonObject }.map { (k, v) -> k to injectMap(v) }
                }
            if (opts.isEmpty()) return null
            val names = opts.map { it.first }
            val default = p.str("default")?.takeIf { it in names } ?: names.first()
            var type = p.str("type") ?: "dropdown"
            if (type !in setOf("dropdown", "checkbox", "slider", "none")) type = "dropdown"
            if (type == "checkbox" && !("No" in names && "Yes" in names)) type = "dropdown"
            val comps = (p["components"] as? JsonArray)?.mapNotNull { el ->
                val c = el as? JsonObject ?: return@mapNotNull null
                val cname = c.str("name") ?: return@mapNotNull null
                val ctype = c.str("type")?.takeIf { it == COLOR_PICKER || it == IMAGE_PICKER } ?: return@mapNotNull null
                val on = c.str("on")?.takeIf { it in names } ?: return@mapNotNull null
                val variable = c.str("css_variable")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                var def = c.str("default") ?: ""
                if (ctype == COLOR_PICKER && !isValidHex(def)) def = "#FFFFFF"
                Component(name, cname, ctype, def, on, if (variable.startsWith("--")) variable else "--$variable", strList(c["tabs"]))
            } ?: emptyList()
            return Patch(name, default, type, opts, comps)
        }

        private fun injectMap(e: JsonElement?): List<Pair<String, List<String>>> =
            (e as? JsonObject)?.map { (k, v) -> k to strList(v) } ?: emptyList()

        private fun strList(e: JsonElement?): List<String> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
