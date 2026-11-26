package app.gamenative.service.ea

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Central directory reader for the zip archives EA's CDN serves game builds in, fed from the archive tail.
 * Port of Maxima's content/zip.rs (ArmchairDevelopers/Maxima, GPL-3.0).
 */
object EaZip {
    data class Entry(
        val name: String,
        val crc32: Long,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val dataOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    /** Result of scanning the archive tail: either the entries, or the earlier offset whose bytes are still needed. */
    sealed interface TailResult {
        data class Entries(val entries: List<Entry>) : TailResult
        data class NeedFrom(val offset: Long) : TailResult
    }

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val EOCD_SIZE = 22
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_SIZE = 56
    private const val CENTRAL_HEADER_SIZE = 46
    private const val U32_MAX = 0xFFFFFFFFL
    private const val U16_MAX = 0xFFFF
    private const val MAX_BACKSCAN = 6L * 1024 * 1024
    private const val MIN_GROWTH = 64L * 1024

    private val windows1252: Charset = Charset.forName("windows-1252")

    private class RawEntry(
        val name: String,
        val crc32: Long,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val disk: Long,
    )

    /** [tail] holds the last tail.size bytes of an archive of [totalSize] bytes. */
    fun parseTail(tail: ByteArray, totalSize: Long): TailResult {
        require(tail.size.toLong() <= totalSize) { "tail (${tail.size}) is larger than the archive ($totalSize)" }
        val base = totalSize - tail.size
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        fun needFrom(offset: Long): TailResult {
            if (offset < 0 || offset >= base) throw IllegalStateException("zip structure points at offset $offset, outside the unread range [0, $base)")
            return TailResult.NeedFrom(offset)
        }

        val eocdPos = findEocd(buf, tail.size)
        if (eocdPos < 0) {
            if (base == 0L) throw IllegalStateException("no end of central directory record in the archive")
            if (tail.size >= MAX_BACKSCAN) throw IllegalStateException("no end of central directory record in the last $MAX_BACKSCAN bytes")
            val wanted = minOf(MAX_BACKSCAN, maxOf(tail.size.toLong() * 2, MIN_GROWTH), totalSize)
            return needFrom(totalSize - wanted)
        }

        try {
            buf.position(eocdPos + 4)
            var disk = u16(buf).toLong()
            var cdDisk = u16(buf).toLong()
            u16(buf)
            var totalEntries = u16(buf).toLong()
            var cdSize = u32(buf)
            var cdOffset = u32(buf)

            if (cdOffset == U32_MAX || cdSize == U32_MAX || totalEntries == U16_MAX.toLong()) {
                val locatorPos = eocdPos - ZIP64_LOCATOR_SIZE
                if (locatorPos < 0) return needFrom(maxOf(0L, base + locatorPos))
                buf.position(locatorPos)
                if (u32(buf) != ZIP64_LOCATOR_SIGNATURE) throw IllegalStateException("missing ZIP64 end of central directory locator")
                u32(buf)
                val zip64EocdOffset = u64(buf, "ZIP64 end of central directory offset")
                if (zip64EocdOffset + ZIP64_EOCD_SIZE > base + locatorPos) throw IllegalStateException("bad ZIP64 end of central directory offset $zip64EocdOffset")
                if (zip64EocdOffset < base) return needFrom(zip64EocdOffset)
                buf.position((zip64EocdOffset - base).toInt())
                if (u32(buf) != ZIP64_EOCD_SIGNATURE) throw IllegalStateException("bad ZIP64 end of central directory signature")
                u64(buf, "ZIP64 record size")
                u16(buf)
                u16(buf)
                disk = u32(buf)
                cdDisk = u32(buf)
                u64(buf, "ZIP64 disk entry count")
                totalEntries = u64(buf, "ZIP64 entry count")
                cdSize = u64(buf, "ZIP64 central directory size")
                cdOffset = u64(buf, "ZIP64 central directory offset")
            }

            if (disk != cdDisk) throw IllegalStateException("multi-disk archives are not supported")
            if (cdOffset > totalSize || cdSize > totalSize) throw IllegalStateException("bad central directory offset $cdOffset (size $cdSize)")
            if (cdOffset < base) return needFrom(cdOffset)

            buf.position((cdOffset - base).toInt())
            val raw = ArrayList<RawEntry>(minOf(totalEntries, 65536L).toInt())
            for (i in 0 until totalEntries) {
                raw += readCentralEntry(buf, i, totalEntries)
            }
            return TailResult.Entries(withDataOffsets(raw, cdOffset))
        } catch (e: BufferUnderflowException) {
            throw IllegalStateException("zip central directory is truncated", e)
        }
    }

    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0) return false
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) return false
        if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) return false
        return normalized.split('/').none { it == ".." }
    }

    private fun findEocd(buf: ByteBuffer, size: Int): Int {
        var fallback = -1
        var pos = size - EOCD_SIZE
        while (pos >= 0) {
            if (u32At(buf, pos) == EOCD_SIGNATURE) {
                val commentLength = (buf.getShort(pos + 20).toInt() and 0xFFFF)
                if (pos + EOCD_SIZE + commentLength == size) return pos
                if (fallback < 0) fallback = pos
            }
            pos--
        }
        return fallback
    }

    private fun readCentralEntry(buf: ByteBuffer, index: Long, total: Long): RawEntry {
        if (buf.remaining() < CENTRAL_HEADER_SIZE) throw IllegalStateException("central directory entry $index of $total is truncated")
        val signature = u32(buf)
        if (signature != CENTRAL_HEADER_SIGNATURE) {
            throw IllegalStateException("central directory entry $index of $total has signature 0x${signature.toString(16)}")
        }
        u16(buf)
        u16(buf)
        val flags = u16(buf)
        val method = u16(buf)
        u16(buf)
        u16(buf)
        val crc = u32(buf)
        var compressed = u32(buf)
        var uncompressed = u32(buf)
        val nameLength = u16(buf)
        val extraLength = u16(buf)
        val commentLength = u16(buf)
        var disk = u16(buf).toLong()
        u16(buf)
        u32(buf)
        var localHeaderOffset = u32(buf)

        val nameBytes = ByteArray(nameLength).also { buf.get(it) }
        val name = String(nameBytes, if (flags and (1 shl 11) != 0) Charsets.UTF_8 else windows1252)
        val extra = ByteArray(extraLength).also { buf.get(it) }
        if (buf.remaining() < commentLength) throw BufferUnderflowException()
        buf.position(buf.position() + commentLength)

        if (compressed == U32_MAX || uncompressed == U32_MAX || localHeaderOffset == U32_MAX || disk == U16_MAX.toLong()) {
            val zip64 = extraField(extra, 0x0001)
                ?: throw IllegalStateException("zip entry $name has no ZIP64 extra field")
            val field = ByteBuffer.wrap(zip64).order(ByteOrder.LITTLE_ENDIAN)
            try {
                if (uncompressed == U32_MAX) uncompressed = u64(field, "uncompressed size of $name")
                if (compressed == U32_MAX) compressed = u64(field, "compressed size of $name")
                if (localHeaderOffset == U32_MAX) localHeaderOffset = u64(field, "local header offset of $name")
                if (disk == U16_MAX.toLong()) disk = u32(field)
            } catch (e: BufferUnderflowException) {
                throw IllegalStateException("zip entry $name has a short ZIP64 extra field", e)
            }
        }

        return RawEntry(name, crc, method, compressed, uncompressed, localHeaderOffset, disk)
    }

    private fun extraField(extra: ByteArray, id: Int): ByteArray? {
        val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) {
            val fieldId = u16(buf)
            val size = u16(buf)
            if (size > buf.remaining()) return null
            if (fieldId == id) return ByteArray(size).also { buf.get(it) }
            buf.position(buf.position() + size)
        }
        return null
    }

    private fun withDataOffsets(raw: List<RawEntry>, cdOffset: Long): List<Entry> {
        val sorted = raw.sortedBy { it.localHeaderOffset }
        return sorted.mapIndexed { i, e ->
            val next = sorted.getOrNull(i + 1)
            val dataOffset = when {
                next == null -> cdOffset - e.compressedSize
                next.disk == e.disk -> next.localHeaderOffset - e.compressedSize
                else -> -1L
            }
            Entry(e.name, e.crc32, e.method, e.compressedSize, e.uncompressedSize, e.localHeaderOffset, dataOffset)
        }
    }

    private fun u16(buf: ByteBuffer): Int = buf.short.toInt() and 0xFFFF

    private fun u32(buf: ByteBuffer): Long = buf.int.toLong() and U32_MAX

    private fun u32At(buf: ByteBuffer, pos: Int): Long = buf.getInt(pos).toLong() and U32_MAX

    private fun u64(buf: ByteBuffer, what: String): Long {
        val value = buf.long
        if (value < 0) throw IllegalStateException("$what is out of range")
        return value
    }
}
