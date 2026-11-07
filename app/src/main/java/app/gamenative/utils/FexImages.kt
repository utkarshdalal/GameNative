package app.gamenative.utils

import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The PE images a hosted FEX code cache is looked up by. A cache is keyed by the image's code
 * identity ([codeSha256]), which survives per-game restamping of resources and timestamps; FEX finds it on disk by a name derived from the lowercase file name and two PE header fields
 * (TimeDateStamp, SizeOfImage), which the server turns into the file name for this device.
 * Hashes are kept in a per-container index keyed by host path, size and mtime, so each file
 * version is hashed once.
 */
object FexImages {

    private const val TAG = "FexImages"
    private const val INDEX_FILE = ".gamenative/fex-image-hashes.json"

    data class Header(val timeDateStamp: Long, val sizeOfImage: Long)

    data class Image(
        val sha256: String,
        val codeSha256: String,
        val name: String,
        val timeDateStamp: Long,
        val sizeOfImage: Long,
    )

    private val SKIPPED_SECTIONS = setOf(".rsrc", ".reloc")

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
                    it.optLong("timeDateStamp", -1) == header.timeDateStamp && it.optLong("sizeOfImage", -1) == header.sizeOfImage &&
                    it.optString("sha256").length == 64 && it.optString("codeSha256").length == 64
            }
            val (sha256, codeSha256) = if (cached != null) {
                cached.getString("sha256") to cached.getString("codeSha256")
            } else {
                val hashes = runCatching {
                    sha256(file) to (codeSha256(file) ?: throw IllegalArgumentException("malformed section table"))
                }.getOrElse {
                    Timber.tag(TAG).w(it, "Cannot hash ${file.path}")
                    return@mapNotNull null
                }
                index.put(
                    key,
                    JSONObject()
                        .put("size", size)
                        .put("mtime", mtime)
                        .put("sha256", hashes.first)
                        .put("codeSha256", hashes.second)
                        .put("timeDateStamp", header.timeDateStamp)
                        .put("sizeOfImage", header.sizeOfImage),
                )
                changed = true
                hashes
            }
            Image(sha256, codeSha256, file.name.lowercase(), header.timeDateStamp, header.sizeOfImage)
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

    /**
     * SHA-256 over, in section-table order, every section other than .rsrc and .reloc: u32le
     * VirtualAddress, VirtualSize and SizeOfRawData, then SizeOfRawData bytes at PointerToRawData,
     * with any part past the end of the file counted as zeros. Null if the file is not a PE image
     * or its section table does not fit in the file.
     */
    fun codeSha256(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 0x40) return null
            val dos = ByteArray(0x40)
            raf.readFully(dos)
            if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
            val peOffset = ByteBuffer.wrap(dos).order(ByteOrder.LITTLE_ENDIAN).getInt(0x3c).toLong() and 0xffffffffL
            if (peOffset + 24 > length) return null
            val coff = ByteArray(24)
            raf.seek(peOffset)
            raf.readFully(coff)
            val coffBuf = ByteBuffer.wrap(coff).order(ByteOrder.LITTLE_ENDIAN)
            if (coffBuf.getInt(0) != 0x00004550) return null
            val sectionCount = coffBuf.getShort(6).toInt() and 0xffff
            val optionalHeaderSize = coffBuf.getShort(20).toInt() and 0xffff
            val tableOffset = peOffset + 24 + optionalHeaderSize
            if (tableOffset + sectionCount * 40L > length) return null
            val table = ByteArray(sectionCount * 40)
            raf.seek(tableOffset)
            raf.readFully(table)
            val sections = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            val fields = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sectionCount) {
                val base = i * 40
                val nameEnd = (0 until 8).firstOrNull { table[base + it] == 0.toByte() } ?: 8
                val name = String(table, base, nameEnd, Charsets.US_ASCII)
                if (name in SKIPPED_SECTIONS) continue
                val virtualSize = sections.getInt(base + 8)
                val virtualAddress = sections.getInt(base + 12)
                val rawSize = sections.getInt(base + 16).toLong() and 0xffffffffL
                val rawPointer = sections.getInt(base + 20).toLong() and 0xffffffffL
                // A section cannot be larger than the file it sits in; refuse to hash gigabytes of
                // padding for a header that says otherwise.
                if (rawSize > length || rawPointer > length) return null
                fields.clear()
                fields.putInt(virtualAddress).putInt(virtualSize).putInt(rawSize.toInt())
                digest.update(fields.array())

                var remaining = rawSize
                var position = rawPointer
                while (remaining > 0 && position < length) {
                    raf.seek(position)
                    val n = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    remaining -= n
                    position += n
                }
                if (remaining > 0) {
                    buffer.fill(0)
                    while (remaining > 0) {
                        val n = minOf(remaining, buffer.size.toLong()).toInt()
                        digest.update(buffer, 0, n)
                        remaining -= n
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

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
