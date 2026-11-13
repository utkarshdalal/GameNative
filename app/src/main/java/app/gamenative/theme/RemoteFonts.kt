package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/**
 * Fonts a theme loads from the internet (`@import` of a remote stylesheet, remote `@font-face` sources), downloaded once
 * into a folder kept with the theme and from then on resolved like files of the theme. Only `@font-face` rules are read
 * from a remote stylesheet, one level deep; downloaded files are used only when they are TrueType, OpenType, WOFF or
 * WOFF2.
 * Pure JVM: the network goes through [Fetcher].
 */
class RemoteFonts(
    private val fetcher: Fetcher,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {

    fun interface Fetcher {
        /** Body of a GET of the HTTPS [url], at most [maxBytes]; throws IOException otherwise. */
        fun get(url: String, maxBytes: Long): ByteArray
    }

    /**
     * Downloads what [sheets] load from the internet into [dir], reusing files already there; at most [MAX_REQUESTS]
     * requests. Failures are logged and skipped. Returns true when a new file was saved.
     */
    fun sync(sheets: List<CssSheet>, dir: File): Boolean {
        var requests = 0
        var saved = false
        val faces = ArrayList<Pair<String?, List<String>>>()
        for (sheet in sheets) {
            faces.addAll(families(sheet))
            for (url in sheet.imports.mapNotNull(::importUrl)) {
                if (!isHttps(url)) {
                    log("RemoteFonts: skipping $url (not https)", null)
                    continue
                }
                val cached = File(dir, cssName(url))
                if (!cached.isFile) {
                    if (requests >= MAX_REQUESTS) break
                    requests++
                    val css = try {
                        String(fetch(url, MAX_CSS_BYTES), Charsets.UTF_8)
                    } catch (e: IOException) {
                        log("RemoteFonts: stylesheet $url failed", e)
                        continue
                    }
                    if (!write(cached, fontFacesCss(CssParser.parse(css), url).toByteArray())) continue
                    saved = true
                }
                parseCached(cached)?.let { faces.addAll(families(it)) }
            }
        }
        // the app draws one file per family, so one face of each family is enough
        val done = HashSet<String>()
        for ((family, face) in faces) {
            if (family != null && family in done) continue
            val urls = face.filter(::isHttps)
            if (urls.isEmpty()) continue
            if (urls.any { localFile(dir, it) != null }) {
                family?.let(done::add)
                continue
            }
            for (url in urls) {
                if (requests >= MAX_REQUESTS) return saved
                requests++
                val bytes = try {
                    fetch(url, MAX_FONT_BYTES)
                } catch (e: IOException) {
                    log("RemoteFonts: font $url failed", e)
                    continue
                }
                val ext = fontExt(bytes)
                if (ext == null) {
                    log("RemoteFonts: $url is not a usable font", null)
                    continue
                }
                if (!write(File(dir, "${key(url)}.$ext"), bytes)) continue
                saved = true
                family?.let(done::add)
                break
            }
        }
        return saved
    }

    private fun fetch(url: String, max: Long): ByteArray {
        val bytes = fetcher.get(url, max)
        if (bytes.size > max) throw IOException("$url is larger than $max bytes")
        return bytes
    }

    companion object {
        const val MAX_CSS_BYTES = 256L * 1024
        const val MAX_FONT_BYTES = 8L * 1024 * 1024
        const val MAX_REQUESTS = 8

        private val FONT_EXT = listOf("ttf", "otf", "woff", "woff2")
        private val FORMAT_RE = Regex("format\\(\\s*['\"]?([^'\")\\s]+)", RegexOption.IGNORE_CASE)
        private val QUOTED_RE = Regex("^\\s*(?:'([^']*)'|\"([^\"]*)\")")

        fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

        /** Target of an `@import` prelude: `url(...)` or a quoted string. */
        fun importUrl(prelude: String): String? =
            CssValues.firstUrl(prelude) ?: QUOTED_RE.find(prelude)?.let { (it.groups[1] ?: it.groups[2])?.value?.trim() }?.takeIf { it.isNotEmpty() }

        /** The downloaded copy of [url] in [dir], or null. */
        fun localFile(dir: File, url: String): File? {
            if (!isHttps(url)) return null
            val k = key(url)
            return FONT_EXT.map { File(dir, "$k.$it") }.firstOrNull { it.isFile }
        }

        /** The `@font-face` rules of a remote stylesheet [url] kept in [dir], or null. */
        fun cachedImport(dir: File, url: String): File? = if (isHttps(url)) File(dir, cssName(url)).takeIf { it.isFile } else null

        /** Font urls of an `@font-face`, the last `src` first, cheapest to load first: TrueType / OpenType, WOFF, then WOFF2. */
        fun sources(face: List<CssDecl>): List<String> =
            face.filter { it.property == "src" }.asReversed().flatMap { d -> CssValues.splitTop(d.value, ',') }
                .sortedBy(::decodeCost)
                .mapNotNull(CssValues::firstUrl)

        /** 0 for a TrueType / OpenType `src` entry, 1 for WOFF, 2 for WOFF2, by its `format()` or else its file extension. */
        private fun decodeCost(entry: String): Int {
            val kind = FORMAT_RE.find(entry)?.groupValues?.get(1)
                ?: CssValues.firstUrl(entry)?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.')
            val k = kind?.lowercase() ?: return 0
            return when {
                k.startsWith("woff2") -> 2
                k.startsWith("woff") -> 1
                else -> 0
            }
        }

        /** Family and font urls of each `@font-face` of [sheet], and the url of a `--gn-font` it sets (no family). */
        private fun families(sheet: CssSheet): List<Pair<String?, List<String>>> =
            sheet.fontFaces.map { f ->
                f.lastOrNull { it.property == "font-family" }?.value?.trim()?.trim('\'', '"')?.lowercase() to sources(f)
            } + gnFonts(sheet).map { null to listOf(it) }

        private fun gnFonts(sheet: CssSheet): List<String> =
            sheet.rules.flatMap { r -> r.decls.filter { it.property == "--gn-font" } }.mapNotNull { CssValues.firstUrl(it.value) }

        /** Font urls of each `@font-face` of [sheet], and of a `--gn-font` it sets. */
        fun fontSources(sheet: CssSheet): List<List<String>> =
            sheet.fontFaces.map(::sources) + gnFonts(sheet).map { listOf(it) }

        /** Every remote url [sheets] load, with the fonts of the remote stylesheets already kept in [dir]. */
        fun referenced(sheets: List<CssSheet>, dir: File): Set<String> {
            val out = HashSet<String>()
            for (s in sheets) {
                for (u in s.imports.mapNotNull(::importUrl).filter(::isHttps)) {
                    out.add(u)
                    cachedImport(dir, u)?.let(::parseCached)?.let { c -> fontSources(c).forEach { out.addAll(it.filter(::isHttps)) } }
                }
                fontSources(s).forEach { out.addAll(it.filter(::isHttps)) }
            }
            return out
        }

        /** Deletes the files in [dir] that no url in [keep] maps to. */
        fun prune(dir: File, keep: Set<String>) {
            val names = keep.flatMap { u -> listOf(cssName(u)) + FONT_EXT.map { "${key(u)}.$it" } }.toSet()
            dir.listFiles()?.forEach { if (it.name !in names) it.deleteRecursively() }
        }

        /** Extension for a font by its first bytes; null for anything else and for a WOFF / WOFF2 file that does not decode. */
        fun fontExt(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            val tag = String(bytes, 0, 4, Charsets.ISO_8859_1)
            return when {
                tag == "\u0000\u0001\u0000\u0000" || tag == "true" -> "ttf"
                tag == "OTTO" -> "otf"
                tag == "wOFF" -> "woff".takeIf { WoffDecoder.decode(bytes) != null }
                tag == "wOF2" -> "woff2".takeIf { Woff2Decoder.decode(bytes) != null }
                else -> null
            }
        }

        /** Only the `@font-face` rules of [sheet], with their urls made absolute against [base]. */
        fun fontFacesCss(sheet: CssSheet, base: String): String = buildString {
            for (face in sheet.fontFaces) {
                append("@font-face {\n")
                for (d in face) {
                    val value = if (d.property == "src") absoluteUrls(d.value, base) else d.value
                    if (value.contains('{') || value.contains('}') || value.contains(';')) continue
                    append("  ").append(d.property).append(": ").append(value).append(";\n")
                }
                append("}\n")
            }
        }

        /** `src` entries with absolute HTTPS urls; entries that are not HTTPS once resolved are dropped. */
        private fun absoluteUrls(value: String, base: String): String =
            CssValues.splitTop(value, ',').mapNotNull { part ->
                val u = CssValues.firstUrl(part) ?: return@mapNotNull part.trim().takeIf { it.isNotEmpty() }
                val abs = try {
                    URI(base).resolve(u.replace(" ", "%20")).toString()
                } catch (e: IllegalArgumentException) {
                    return@mapNotNull null
                }
                if (!isHttps(abs)) return@mapNotNull null
                val rest = part.substringAfter(')', "").trim()
                "url(\"${abs.replace("\"", "%22")}\")" + if (rest.isEmpty()) "" else " $rest"
            }.joinToString(", ")

        private fun parseCached(f: File): CssSheet? = try {
            if (f.length() > MAX_CSS_BYTES) null else CssParser.parse(f.readText())
        } catch (e: IOException) {
            null
        }

        private fun write(dest: File, bytes: ByteArray): Boolean {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            return try {
                dest.parentFile?.mkdirs()
                tmp.writeBytes(bytes)
                tmp.renameTo(dest).also { if (!it) tmp.delete() }
            } catch (e: IOException) {
                tmp.delete()
                false
            }
        }

        private fun cssName(url: String) = "${key(url)}.css"

        private fun key(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
