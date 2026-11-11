package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps CSS `url()`s of a theme to files. `/themes_custom/<folder>/file` is CSS Loader's serving path
 * for its themes folder; relative URLs are relative to the CSS file. Nothing outside [themesRoot] resolves.
 */
class ThemeAssets(
    private val themesRoot: File,
    /** Installed theme folder for a CSS Loader folder name, or null. */
    private val folderLookup: (String) -> File? = { null },
) {
    private val rootPath: String = canonical(themesRoot) ?: themesRoot.absolutePath

    fun resolve(rawUrl: String, themeRoot: File, baseDir: String): String? {
        var url = rawUrl.trim().trim('"', '\'')
        val low = url.lowercase()
        if (low.startsWith("data:") || low.startsWith("http:") || low.startsWith("https:")) return null
        if (low.startsWith("file://")) {
            return inside(File(percentDecode(url.substring(7)).substringBefore('?')), themesRoot)
        }
        url = url.substringBefore('?').substringBefore('#')
        if (url.startsWith("/themes_custom/")) {
            val rest = percentDecode(url.removePrefix("/themes_custom/"))
            val folder = rest.substringBefore('/')
            val rel = rest.substringAfter('/', "")
            if (rel.isEmpty()) return null
            val root = folderLookup(folder) ?: themeRoot
            return inside(File(root, rel), root)
        }
        if (url.startsWith("/")) return null
        val rel = percentDecode(if (baseDir.isEmpty()) url else "$baseDir/$url")
        return inside(File(themeRoot, rel), themeRoot)
    }

    private fun inside(f: File, root: File): String? {
        val p = canonical(f) ?: return null
        val r = canonical(root) ?: return null
        if (!p.startsWith(r + File.separator) || !p.startsWith(rootPath + File.separator)) return null
        return if (File(p).isFile) p else null
    }

    companion object {
        fun canonical(f: File): String? = try {
            f.canonicalPath
        } catch (e: IOException) {
            null
        }

        fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        bytes.write(v); i += 3; continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return bytes.toString("UTF-8")
        }
    }
}

/** A parsed CSS source with the resolver for its URLs. */
class SourceSheet(val sheet: CssSheet, val resolveUrl: (String) -> String?)

/** One installed theme folder with the values it is applied with. */
data class ThemeUnit(val folder: File, val manifest: DeckyThemeManifest, val values: ThemeValues)

/** Reads theme folders into translator input. Pure JVM. */
object ThemeLoader {

    const val MAX_CSS_BYTES = 4L * 1024 * 1024

    private class Cached(val modified: Long, val length: Long, val sheet: CssSheet)

    private val parsed = ConcurrentHashMap<String, Cached>()

    fun clearCache() = parsed.clear()

    fun isThemeFolder(dir: File): Boolean = File(dir, "theme.json").isFile || File(dir, "theme.css").isFile

    /** Throws IllegalArgumentException when [dir] is not a usable theme. */
    fun loadManifest(dir: File): DeckyThemeManifest {
        val json = File(dir, "theme.json")
        if (json.isFile) {
            if (json.length() > 1024 * 1024) throw IllegalArgumentException("theme.json is too large")
            return DeckyThemeManifest.parse(json.readText())
        }
        if (File(dir, "theme.css").isFile) return DeckyThemeManifest.cssOnly(dir.name)
        throw IllegalArgumentException("Folder has no theme.json or theme.css")
    }

    fun parseFile(f: File): CssSheet? {
        if (!f.isFile || f.length() > MAX_CSS_BYTES) return null
        val key = f.absolutePath
        parsed[key]?.let { if (it.modified == f.lastModified() && it.length == f.length()) return it.sheet }
        val sheet = try {
            CssParser.parse(f.readText())
        } catch (e: IOException) {
            return null
        }
        parsed[key] = Cached(f.lastModified(), f.length(), sheet)
        return sheet
    }

    /** CSS of one theme at its values, in cascade order; injects for tabs this app does not mirror are skipped. */
    fun sheets(unit: ThemeUnit, assets: ThemeAssets): List<SourceSheet> {
        val out = ArrayList<SourceSheet>()
        val root = unit.folder
        for (inj in unit.manifest.injects(unit.values)) {
            if (!inj.relevant) continue
            if (inj.file != null) {
                if (!DeckyThemeManifest.isSafeRelative(inj.file)) continue
                val f = File(root, inj.file)
                val rp = ThemeAssets.canonical(f) ?: continue
                val rr = ThemeAssets.canonical(root) ?: continue
                if (!rp.startsWith(rr + File.separator)) continue
                val sheet = parseFile(f) ?: continue
                val base = inj.file.replace('\\', '/').substringBeforeLast('/', "")
                out.add(SourceSheet(sheet) { assets.resolve(it, root, base) })
            } else if (inj.css != null) {
                out.add(SourceSheet(CssParser.parse(inj.css)) { assets.resolve(it, root, "") })
            }
        }
        return out
    }

    /**
     * [unit] with its installed dependencies first (each with the patch values [unit] asks for, as the reference
     * loader applies them), and the names of the dependencies that are not installed.
     */
    fun withDependencies(unit: ThemeUnit, findByName: (String) -> ThemeUnit?): Pair<List<ThemeUnit>, List<String>> {
        val ordered = ArrayList<ThemeUnit>()
        val missing = ArrayList<String>()
        val seen = HashSet<String>()
        fun visit(u: ThemeUnit, depth: Int) {
            if (depth > 8 || !seen.add(u.folder.absolutePath)) return
            for ((depName, patchValues) in u.manifest.dependencies) {
                val dep = findByName(depName)
                if (dep == null) {
                    if (depName !in missing) missing.add(depName)
                    continue
                }
                visit(dep.copy(values = dep.values.copy(choices = dep.values.choices + patchValues)), depth + 1)
            }
            ordered.add(u)
        }
        visit(unit, 0)
        return ordered to missing
    }
}
