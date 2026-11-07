package app.gamenative.utils

import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The PE images a hosted FEX code cache is looked up by. A cache is keyed by the image's sha256;
 * FEX finds it on disk by a name derived from the lowercase file name and two PE header fields
 * (TimeDateStamp, SizeOfImage), which the server turns into the file name for this device.
 * Hashes are kept in a per-container index keyed by host path, size and mtime, so each file
 * version is hashed once.
 */
object FexImages {

    private const val TAG = "FexImages"
    private const val INDEX_FILE = ".gamenative/fex-image-hashes.json"

    data class Header(val timeDateStamp: Long, val sizeOfImage: Long)

    data class Image(val sha256: String, val name: String, val timeDateStamp: Long, val sizeOfImage: Long)

    /**
     * Reads TimeDateStamp (e_lfanew+8) and SizeOfImage (e_lfanew+24+56, the same offset in PE32 and
     * PE32+) after checking the "MZ" and "PE\0\0" signatures. Null for anything that is not a PE image.
     */
    fun readHeader(file: File): Header? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 0x40) return null
            val dos = ByteArray(0x40)
            raf.readFully(dos)
            if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
            val peOffset = ByteBuffer.wrap(dos).order(ByteOrder.LITTLE_ENDIAN).getInt(0x3c).toLong() and 0xffffffffL
            if (peOffset + 24 + 60 > length) return null
            val nt = ByteArray(24 + 60)
            raf.seek(peOffset)
            raf.readFully(nt)
            val buf = ByteBuffer.wrap(nt).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(0) != 0x00004550) return null
            Header(buf.getInt(8).toLong() and 0xffffffffL, buf.getInt(24 + 56).toLong() and 0xffffffffL)
        }
    }.getOrNull()

    /**
     * Describes each PE file in [files], hashing only those whose size or mtime differ from the
     * index under [containerRoot]. Files that are not PE images or cannot be read are left out.
     * The index keeps the entries of files that still exist.
     */
    fun describe(containerRoot: File, files: List<File>): List<Image> {
        val indexFile = File(containerRoot, INDEX_FILE)
        val index = runCatching { JSONObject(indexFile.readText()) }.getOrElse { JSONObject() }
        var changed = false
        val images = files.mapNotNull { file ->
            val header = readHeader(file) ?: return@mapNotNull null
            val key = file.absolutePath
            val size = file.length()
            val mtime = file.lastModified()
            val cached = index.optJSONObject(key)?.takeIf {
                it.optLong("size", -1) == size && it.optLong("mtime", -1) == mtime &&
                    it.optLong("timeDateStamp", -1) == header.timeDateStamp && it.optLong("sizeOfImage", -1) == header.sizeOfImage
            }?.optString("sha256")?.takeIf { it.length == 64 }
            val sha256 = cached ?: runCatching { sha256(file) }.getOrElse {
                Timber.tag(TAG).w(it, "Cannot hash ${file.path}")
                return@mapNotNull null
            }.also {
                index.put(
                    key,
                    JSONObject()
                        .put("size", size)
                        .put("mtime", mtime)
                        .put("sha256", it)
                        .put("timeDateStamp", header.timeDateStamp)
                        .put("sizeOfImage", header.sizeOfImage),
                )
                changed = true
            }
            Image(sha256, file.name.lowercase(), header.timeDateStamp, header.sizeOfImage)
        }
        val gone = index.keys().asSequence().filter { !File(it).isFile }.toList()
        gone.forEach { index.remove(it) }
        if (changed || gone.isNotEmpty()) {
            runCatching {
                indexFile.parentFile?.mkdirs()
                indexFile.writeText(index.toString())
            }.onFailure { Timber.tag(TAG).w(it, "Cannot write ${indexFile.path}") }
        }
        return images
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
