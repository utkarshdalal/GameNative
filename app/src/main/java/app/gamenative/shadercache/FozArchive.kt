package app.gamenative.shadercache

import java.io.File
import java.io.RandomAccessFile

/**
 * Walks a Fossilize stream archive: a 16 byte header, then entries of 40 hex characters of tag and hash, four
 * little-endian uint32 (stored size, flags, crc, payload size) and the stored payload.
 */
internal object FozArchive {
    const val HEADER_BYTES = 16L
    const val NAME_CHARS = 40
    const val TAG_CHARS = 24
    const val ENTRY_HEADER_BYTES = NAME_CHARS + 16
    private val MAGIC = byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray()
    val HEADER = MAGIC + byteArrayOf(0, 0, 0, 6)
    private val NAME = Regex("[0-9a-f]{$NAME_CHARS}")

    /**
     * Calls [block] with the name, file offset and whole length of each complete entry of [file]. Nothing is called
     * for a file that is not an archive; an entry cut short at the end is left out, as Fossilize does.
     */
    fun forEachEntry(file: File, block: (name: String, offset: Long, length: Long) -> Unit) {
        if (!file.isFile) return
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < HEADER_BYTES) return
            val magic = ByteArray(MAGIC.size).also { raf.readFully(it) }
            if (!magic.contentEquals(MAGIC)) return
            var offset = HEADER_BYTES
            val entry = ByteArray(ENTRY_HEADER_BYTES)
            while (offset + ENTRY_HEADER_BYTES <= length) {
                raf.seek(offset)
                raf.readFully(entry)
                val size = ENTRY_HEADER_BYTES + uint32(entry, NAME_CHARS)
                if (offset + size > length) return
                val name = String(entry, 0, NAME_CHARS, Charsets.US_ASCII)
                if (NAME.matches(name)) block(name, offset, size)
                offset += size
            }
        }
    }

    /** The names of the complete entries of [file]. */
    fun names(file: File): Set<String> {
        val names = HashSet<String>()
        runCatching { forEachEntry(file) { name, _, _ -> names.add(name) } }
        return names
    }

    private fun uint32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
}
