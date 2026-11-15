package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Safe unpacking of theme zips and lookup of the theme folder inside them. Pure JVM. */
object ThemeZip {

    const val MAX_ENTRIES = 2000
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    private val IGNORED_TOP = setOf("__MACOSX", ".DS_Store")

    /** Entry name -> safe relative path, or null for absolute paths, `..`, drive letters and empty names. */
    fun safeEntryPath(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.startsWith("/") || n.contains('\u0000')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Names of central-directory entries that a Unix zip tool marked as symlinks. */
    fun symlinkEntries(zip: File): Set<String> {
        val out = HashSet<String>()
        RandomAccessFile(zip, "r").use { raf ->
            val len = raf.length()
            if (len < 22) return out
            val tailLen = minOf(len, 22L + 65535L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (le32(tail, i) == 0x06054b50L) { eocd = i; break }
            }
            if (eocd < 0) return out
            val count = le16(tail, eocd + 10)
            val cdSize = le32(tail, eocd + 12)
            val cdOffset = le32(tail, eocd + 16)
            if (cdOffset + cdSize > len || cdSize > Int.MAX_VALUE) return out
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)
            var p = 0
            repeat(count) {
                if (p + 46 > cd.size || le32(cd, p) != 0x02014b50L) return out
                val madeBy = le16(cd, p + 4)
                val nameLen = le16(cd, p + 28)
                val extraLen = le16(cd, p + 30)
                val commentLen = le16(cd, p + 32)
                val ext = le32(cd, p + 38)
                if (p + 46 + nameLen > cd.size) return out
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                if ((madeBy shr 8) == 3 && ((ext shr 16) and 0xF000L) == 0xA000L) out.add(name)
                p += 46 + nameLen + extraLen + commentLen
            }
        }
        return out
    }

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, i: Int): Long =
        (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)

    /**
     * Unpacks [zip] into the empty folder [dest]. Throws IOException on a path that escapes [dest],
     * a symlink, or when the entry count or unpacked size passes the caps.
     */
    fun extract(zip: File, dest: File, maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_TOTAL_BYTES) {
        val links = symlinkEntries(zip)
        if (links.isNotEmpty()) throw IOException("Zip contains symbolic links")
        val destPath = dest.canonicalPath
        var total = 0L
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > maxEntries) throw IOException("Zip has too many files")
            for (e in entries) {
                val rel = safeEntryPath(e.name) ?: throw IOException("Unsafe path in zip: ${e.name}")
                val target = File(dest, rel)
                if (!target.canonicalPath.startsWith(destPath + File.separator)) throw IOException("Unsafe path in zip: ${e.name}")
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("Zip unpacks to more than ${maxBytes / (1024 * 1024)} MB")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    /** The theme folder: [dir] itself, or its single top-level folder. */
    fun findThemeRoot(dir: File): File? {
        if (ThemeLoader.isThemeFolder(dir)) return dir
        val kids = dir.listFiles()?.filter { it.name !in IGNORED_TOP && !it.name.startsWith("._") } ?: return null
        val dirs = kids.filter { it.isDirectory }
        if (dirs.size == 1 && kids.none { it.isFile && !it.name.startsWith(".") }) {
            return dirs[0].takeIf { ThemeLoader.isThemeFolder(it) }
        }
        return null
    }
}
