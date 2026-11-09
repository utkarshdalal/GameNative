package app.gamenative.utils

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the COFF machine type and the RT_VERSION resource (VS_FIXEDFILEINFO plus the
 * StringFileInfo strings) of a Windows PE image without loading the whole file.
 */
object PeVersionReader {
    const val MACHINE_I386 = 0x14c
    const val MACHINE_AMD64 = 0x8664

    data class Info(
        val machine: Int,
        val fileVersion: Long?,
        val strings: Map<String, String>,
        val hybrid: Boolean = false,
    ) {
        val majorVersion: Int? get() = fileVersion?.let { (it ushr 48).toInt() }
        val originalFilename: String? get() = strings["OriginalFilename"] ?: strings["InternalName"]
    }

    private const val RT_VERSION = 16
    private const val FIXED_FILE_INFO_SIGNATURE = 0xFEEF04BDL
    private const val FIXED_FILE_INFO_BYTES = 52
    private const val MAX_SECTIONS = 96
    private const val MAX_DIRECTORY_ENTRIES = 4096
    private const val MAX_VERSION_BYTES = 64 * 1024
    private const val SUBDIRECTORY_FLAG = 0x80000000L
    private const val LOAD_CONFIG_DIRECTORY = 10
    private const val CHPE_METADATA_OFFSET_64 = 0xC8

    private interface Source {
        val size: Long
        fun bytes(offset: Long, length: Int): ByteArray?
    }

    fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            parse(object : Source {
                override val size = raf.length()
                override fun bytes(offset: Long, length: Int): ByteArray? {
                    if (offset < 0 || length < 0 || offset + length > size) return null
                    val out = ByteArray(length)
                    raf.seek(offset)
                    raf.readFully(out)
                    return out
                }
            })
        }
    }.getOrNull()

    fun read(image: ByteArray): Info? = runCatching {
        parse(object : Source {
            override val size = image.size.toLong()
            override fun bytes(offset: Long, length: Int): ByteArray? {
                if (offset < 0 || length < 0 || offset + length > size) return null
                return image.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        })
    }.getOrNull()

    private fun parse(source: Source): Info? {
        val dos = source.bytes(0, 64) ?: return null
        if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
        val ntOffset = u32(dos, 0x3C)
        val nt = source.bytes(ntOffset, 24) ?: return null
        if (u32(nt, 0) != 0x00004550L) return null
        val machine = u16(nt, 4)
        val sectionCount = u16(nt, 6)
        val optionalSize = u16(nt, 20)
        if (sectionCount > MAX_SECTIONS) return Info(machine, null, emptyMap())
        val optional = source.bytes(ntOffset + 24, optionalSize) ?: return Info(machine, null, emptyMap())
        val sections = source.bytes(ntOffset + 24 + optionalSize, sectionCount * 40)
            ?: return Info(machine, null, emptyMap())
        val hybrid = runCatching { isHybrid(source, optional, sections, sectionCount) }.getOrDefault(false)
        val version = runCatching { versionResource(source, optional, sections, sectionCount) }.getOrNull()
            ?: return Info(machine, null, emptyMap(), hybrid)
        return parseVersionInfo(machine, version).copy(hybrid = hybrid)
    }

    private fun isHybrid(source: Source, optional: ByteArray, sections: ByteArray, sectionCount: Int): Boolean {
        if (optional.size < 2 || u16(optional, 0) != 0x20b) return false
        val directory = 112 + LOAD_CONFIG_DIRECTORY * 8
        if (optional.size < directory + 8 || u32(optional, 108) <= LOAD_CONFIG_DIRECTORY) return false
        val rva = u32(optional, directory)
        if (rva == 0L) return false
        val offset = rvaToOffset(rva, sections, sectionCount) ?: return false
        val header = source.bytes(offset, 4) ?: return false
        if (u32(header, 0) < CHPE_METADATA_OFFSET_64 + 8) return false
        val pointer = source.bytes(offset + CHPE_METADATA_OFFSET_64, 8) ?: return false
        return u32(pointer, 0) != 0L || u32(pointer, 4) != 0L
    }

    private fun versionResource(source: Source, optional: ByteArray, sections: ByteArray, sectionCount: Int): ByteArray? {
        if (optional.size < 2) return null
        val (countOffset, directoryOffset) = when (u16(optional, 0)) {
            0x10b -> 92 to 96
            0x20b -> 108 to 112
            else -> return null
        }
        if (optional.size < directoryOffset + 24) return null
        if (u32(optional, countOffset) < 3) return null
        val resourceRva = u32(optional, directoryOffset + 16)
        if (resourceRva == 0L) return null
        val resourceBase = rvaToOffset(resourceRva, sections, sectionCount) ?: return null

        val types = directoryEntries(source, resourceBase) ?: return null
        val typeEntry = types.firstOrNull { (name, _) -> name == RT_VERSION.toLong() } ?: return null
        val names = subdirectory(source, resourceBase, typeEntry.second) ?: return null
        val nameEntry = names.firstOrNull { (it.second and SUBDIRECTORY_FLAG) != 0L } ?: return null
        val languages = subdirectory(source, resourceBase, nameEntry.second) ?: return null
        val languageEntry = languages.firstOrNull { (it.second and SUBDIRECTORY_FLAG) == 0L } ?: return null
        val dataEntry = source.bytes(resourceBase + languageEntry.second, 16) ?: return null
        val dataOffset = rvaToOffset(u32(dataEntry, 0), sections, sectionCount) ?: return null
        val dataSize = minOf(u32(dataEntry, 4), MAX_VERSION_BYTES.toLong()).toInt()
        return source.bytes(dataOffset, dataSize)
    }

    private fun subdirectory(source: Source, base: Long, offsetField: Long): List<Pair<Long, Long>>? {
        if ((offsetField and SUBDIRECTORY_FLAG) == 0L) return null
        return directoryEntries(source, base + (offsetField and 0x7FFFFFFFL))
    }

    private fun directoryEntries(source: Source, offset: Long): List<Pair<Long, Long>>? {
        val header = source.bytes(offset, 16) ?: return null
        val count = u16(header, 12) + u16(header, 14)
        if (count > MAX_DIRECTORY_ENTRIES) return null
        val table = source.bytes(offset + 16, count * 8) ?: return null
        return (0 until count).map { u32(table, it * 8) to u32(table, it * 8 + 4) }
    }

    private fun rvaToOffset(rva: Long, sections: ByteArray, sectionCount: Int): Long? {
        for (i in 0 until sectionCount) {
            val base = i * 40
            val virtualSize = u32(sections, base + 8)
            val virtualAddress = u32(sections, base + 12)
            val rawSize = u32(sections, base + 16)
            val rawPointer = u32(sections, base + 20)
            val span = maxOf(virtualSize, rawSize)
            if (rva >= virtualAddress && rva < virtualAddress + span) {
                val delta = rva - virtualAddress
                return if (delta < rawSize) rawPointer + delta else null
            }
        }
        return null
    }

    private class Block(val key: String, val type: Int, val valueOffset: Int, val valueLength: Int, val childrenOffset: Int, val end: Int)

    private fun block(data: ByteArray, offset: Int, limit: Int): Block? {
        if (offset + 6 > limit) return null
        val length = u16(data, offset)
        if (length < 6) return null
        val end = minOf(offset + length, limit)
        val valueLength = u16(data, offset + 2)
        val type = u16(data, offset + 4)
        var cursor = offset + 6
        val key = StringBuilder()
        while (cursor + 1 < end) {
            val ch = u16(data, cursor)
            cursor += 2
            if (ch == 0) break
            key.append(ch.toChar())
        }
        val valueOffset = align4(cursor)
        val valueBytes = if (type == 1) valueLength * 2 else valueLength
        val childrenOffset = minOf(align4(valueOffset + valueBytes), end)
        return Block(key.toString(), type, valueOffset, valueBytes, childrenOffset, end)
    }

    private fun children(data: ByteArray, parent: Block): List<Block> {
        val result = ArrayList<Block>()
        var cursor = parent.childrenOffset
        while (cursor < parent.end) {
            val child = block(data, cursor, parent.end) ?: break
            result.add(child)
            cursor = align4(child.end)
        }
        return result
    }

    private fun parseVersionInfo(machine: Int, data: ByteArray): Info {
        val root = block(data, 0, data.size)
        if (root == null || root.key != "VS_VERSION_INFO") return Info(machine, null, emptyMap())
        val fixedEnd = root.valueOffset + FIXED_FILE_INFO_BYTES
        val fileVersion = if (root.valueLength >= FIXED_FILE_INFO_BYTES && fixedEnd <= data.size &&
            u32(data, root.valueOffset) == FIXED_FILE_INFO_SIGNATURE
        ) {
            (u32(data, root.valueOffset + 8) shl 32) or u32(data, root.valueOffset + 12)
        } else {
            null
        }
        val strings = LinkedHashMap<String, String>()
        for (info in children(data, root).filter { it.key == "StringFileInfo" }) {
            for (table in children(data, info)) {
                for (entry in children(data, table)) {
                    strings.putIfAbsent(entry.key, utf16z(data, entry.valueOffset, entry.end))
                }
            }
        }
        return Info(machine, fileVersion, strings)
    }

    private fun utf16z(data: ByteArray, offset: Int, end: Int): String {
        val text = StringBuilder()
        var cursor = offset
        while (cursor + 1 < end) {
            val ch = u16(data, cursor)
            if (ch == 0) break
            text.append(ch.toChar())
            cursor += 2
        }
        return text.toString().trim()
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private fun u16(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, index: Int): Long =
        (bytes[index].toLong() and 0xFF) or
            ((bytes[index + 1].toLong() and 0xFF) shl 8) or
            ((bytes[index + 2].toLong() and 0xFF) shl 16) or
            ((bytes[index + 3].toLong() and 0xFF) shl 24)
}
