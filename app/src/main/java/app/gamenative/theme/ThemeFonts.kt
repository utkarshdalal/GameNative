package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Theme font files in a form Android's Typeface reads: TrueType / OpenType as they are, WOFF decoded once into a cache. Pure JVM. */
object ThemeFonts {

    private val SFNT_EXT = setOf("ttf", "otf")
    private const val TMP_SUFFIX = ".tmp"

    /** [path] when it is a TrueType / OpenType file, its decoded copy in [cacheDir] for a WOFF file, else null. */
    fun loadable(path: String, cacheDir: File?): String? = when (path.substringAfterLast('.').lowercase()) {
        in SFNT_EXT -> path
        "woff" -> cacheDir?.let { fromWoff(File(path), it) }
        else -> null
    }

    private fun fromWoff(src: File, cacheDir: File): String? {
        if (!src.isFile || src.length() > WoffDecoder.MAX_SFNT_BYTES) return null
        val key = sha1("${src.absolutePath}:${src.length()}:${src.lastModified()}")
        for (ext in SFNT_EXT) File(cacheDir, "$key.$ext").takeIf { it.isFile }?.let { return it.absolutePath }
        return try {
            val sfnt = WoffDecoder.decode(src.readBytes()) ?: return null
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
