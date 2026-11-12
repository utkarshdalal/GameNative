package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Share checks and packaging of a theme as a standalone native theme zip. Pure JVM. */
object CommunityThemePackage {

    const val MAX_ZIP_BYTES = 5L * 1024 * 1024
    private const val TEXT_ALLOWANCE = 16L * 1024
    private const val FONT_NAME = "font"
    private const val IMAGE_NAME = "background"

    private val URL_RE = Regex("url\\(\\s*(?:'([^']*)'|\"([^\"]*)\"|([^)\\s]*))\\s*\\)", RegexOption.IGNORE_CASE)
    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val FONT_EXT = setOf("ttf", "otf")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")

    /** True when [sheet] has only `:root { --gn-* }` rules and `@font-face`, with urls to local files. */
    fun isNative(sheet: CssSheet): Boolean {
        if (sheet.keyframes.isNotEmpty() || sheet.imports.isNotEmpty()) return false
        for (r in sheet.rules) {
            if (r.selectors.any { it.trim() != ":root" }) return false
            for (d in r.decls) if (!d.property.startsWith("--gn-") || !localUrls(d.value)) return false
        }
        return sheet.fontFaces.all { ff -> ff.all { localUrls(it.value) } }
    }

    /** Relative paths, the loader's `/themes_custom/` folder path and `file:` paths it writes for picked images. */
    private fun localUrls(value: String): Boolean {
        if (!value.contains("url(", ignoreCase = true)) return true
        for (m in URL_RE.findAll(value)) {
            val u = (m.groups[1] ?: m.groups[2] ?: m.groups[3])?.value?.trim().orEmpty()
            if (u.isEmpty()) continue
            val low = u.lowercase()
            if (low.startsWith("file:") || low.startsWith("/themes_custom/")) continue
            if (u.startsWith("/") || u.startsWith("\\") || SCHEME_RE.containsMatchIn(u)) return false
        }
        return true
    }

    fun blocker(native: Boolean, spec: ThemeSpec): ShareBlocker? = when {
        !native -> ShareBlocker.NOT_NATIVE
        spec.isEmpty -> ShareBlocker.EMPTY
        estimatedBytes(spec) > MAX_ZIP_BYTES -> ShareBlocker.TOO_LARGE
        else -> null
    }

    /** Upper bound of the packaged zip size: the copied files plus room for theme.json and theme.css. */
    fun estimatedBytes(spec: ThemeSpec): Long =
        TEXT_ALLOWANCE + assets(spec).sumOf { (_, f) -> f.length() }

    /** Files copied into the package: package name -> source. */
    private fun assets(spec: ThemeSpec): List<Pair<String, File>> = buildList {
        spec.fontFile?.let(::File)?.takeIf { it.isFile }?.let { f ->
            val ext = f.extension.lowercase()
            if (ext in FONT_EXT) add("$FONT_NAME.$ext" to f)
        }
        spec.backgroundImage?.let(::File)?.takeIf { it.isFile }?.let { f ->
            imageExt(f)?.let { add("$IMAGE_NAME.$it" to f) }
        }
    }

    /** Extension of an image by its first bytes (picked images can carry a generic name), else by its name. */
    fun imageExt(f: File): String? {
        val head = ByteArray(12)
        val n = try {
            f.inputStream().use { it.read(head) }
        } catch (e: IOException) {
            return null
        }
        fun b(i: Int) = head[i].toInt() and 0xff
        if (n >= 4 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4e && b(3) == 0x47) return "png"
        if (n >= 3 && b(0) == 0xff && b(1) == 0xd8 && b(2) == 0xff) return "jpg"
        if (n >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        return f.extension.lowercase().takeIf { it in IMAGE_EXT }
    }

    fun themeJson(name: String, author: String, version: String, description: String): String {
        val m = LinkedHashMap<String, JsonElement>()
        m["name"] = JsonPrimitive(name)
        m["author"] = JsonPrimitive(author)
        m["version"] = JsonPrimitive(version)
        if (description.isNotBlank()) m["description"] = JsonPrimitive(description)
        m["manifest_version"] = JsonPrimitive(DeckyThemeManifest.LOADER_VERSION)
        m["target"] = JsonPrimitive("System-Wide")
        m["inject"] = JsonObject(mapOf("theme.css" to JsonArray(listOf(JsonPrimitive("All")))))
        return JsonObject(m).toString()
    }

    /** Writes [spec] as a native theme zip at [dest]: theme.json, theme.css and the font / background image it uses. */
    fun write(spec: ThemeSpec, name: String, author: String, version: String, description: String, dest: File) {
        val files = assets(spec)
        val byPath = HashMap<String, String>()
        spec.fontFile?.let { p -> files.firstOrNull { it.second.path == p }?.let { byPath[p] = it.first } }
        spec.backgroundImage?.let { p -> files.firstOrNull { it.second.path == p }?.let { byPath[p] = it.first } }
        val css = ThemeCssWriter.write(spec) { byPath[it] }
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            fun put(entry: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("theme.json", themeJson(name, author, version, description).toByteArray())
            put("theme.css", css.toByteArray())
            for ((entry, f) in files) {
                zip.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
