package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Theme font files in a form Android's Typeface reads: TrueType / OpenType as they are, WOFF and WOFF2 decoded once
 * into a cache. Pure JVM.
 */
object ThemeFonts {

    private val SFNT_EXT = setOf("ttf", "otf")
    private const val TMP_SUFFIX = ".tmp"
    private const val WOFF = "wOFF"
    private const val WOFF2 = "wOF2"

    /** [path] when it is a TrueType / OpenType file, its decoded copy in [cacheDir] for a WOFF or WOFF2 file, else null. */
    fun loadable(path: String, cacheDir: File?): String? {
        val src = File(path)
        val woff2 = when (signature(src)) {
            WOFF -> false
            WOFF2 -> true
            else -> return path.takeIf { path.substringAfterLast('.').lowercase() in SFNT_EXT }
        }
        return cacheDir?.let { decoded(src, it, woff2) }
    }

    /** The first four bytes of [f] as text, or null. */
    private fun signature(f: File): String? = try {
        f.inputStream().use { input ->
            val b = ByteArray(4)
            if (input.read(b) == 4) String(b, Charsets.ISO_8859_1) else null
        }
    } catch (e: IOException) {
        null
    }

    private fun decoded(src: File, cacheDir: File, woff2: Boolean): String? {
        if (!src.isFile || src.length() > WoffDecoder.MAX_SFNT_BYTES) return null
        val key = sha1("${src.absolutePath}:${src.length()}:${src.lastModified()}")
        for (ext in SFNT_EXT) File(cacheDir, "$key.$ext").takeIf { it.isFile }?.let { return it.absolutePath }
        return try {
            val bytes = src.readBytes()
            val sfnt = (if (woff2) Woff2Decoder.decode(bytes) else WoffDecoder.decode(bytes)) ?: return null
            cacheDir.mkdirs()
            val dest = File(cacheDir, "$key.${if (WoffDecoder.isCff(sfnt)) "otf" else "ttf"}")
            val tmp = File(cacheDir, "$key$TMP_SUFFIX")
            tmp.writeBytes(sfnt)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return null
            }
            dest.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes cached fonts in [cacheDir] that are not in [keep]. */
    fun prune(cacheDir: File, keep: Set<String>) {
        cacheDir.listFiles()?.forEach { if (it.isFile && it.absolutePath !in keep) it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
