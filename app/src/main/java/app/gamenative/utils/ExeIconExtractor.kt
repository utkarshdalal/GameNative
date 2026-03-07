package app.gamenative.utils

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PE resource parser to extract icon(s) from a Windows EXE/DLL.
 *
 * It reads the resource directory, finds RT_GROUP_ICON (14) and RT_ICON (3),
 * rebuilds a standard .ico file containing all images referenced by the group,
 * and writes it to [outIcoFile].
 *
 * Only reads PE headers + resource section into memory (not the entire file).
 */
object ExeIconExtractor {
    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_HEADER_READ = 4096
    private const val MAX_RSRC_SECTION = 10 * 1024 * 1024 // 10 MB

    fun tryExtractMainIcon(exeFile: File, outIcoFile: File): Boolean {
        return try {
            RandomAccessFile(exeFile, "r").use { raf ->
                val fileSize = raf.length()
                if (fileSize < 0x100) return false

                // read headers (DOS + PE + optional + section table)
                val headerSize = MAX_HEADER_READ.toLong().coerceAtMost(fileSize).toInt()
                val hdr = ByteArray(headerSize)
                raf.readFully(hdr)
                val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)

                val peHeaderOff = hb.getInt(0x3C)
                if (peHeaderOff <= 0 || peHeaderOff + 4 > headerSize) return false
                // full 4-byte PE signature: "PE\0\0"
                if (hb.get(peHeaderOff).toInt() != 'P'.code ||
                    hb.get(peHeaderOff + 1).toInt() != 'E'.code ||
                    hb.get(peHeaderOff + 2).toInt() != 0 ||
                    hb.get(peHeaderOff + 3).toInt() != 0
                ) return false

                val coffStart = peHeaderOff + 4
                val numberOfSections = hb.getShort(coffStart + 2).toInt() and 0xFFFF
                val sizeOfOptionalHeader = hb.getShort(coffStart + 16).toInt() and 0xFFFF
                val optionalHeaderStart = coffStart + 20
                val magic = hb.getShort(optionalHeaderStart).toInt() and 0xFFFF
                val dataDirectoriesStart = optionalHeaderStart + when (magic) {
                    0x10B -> 96  // PE32
                    0x20B -> 112 // PE32+
                    else -> return false
                }
                if (dataDirectoriesStart + 24 > headerSize) return false
                val resourceDirRva = hb.getInt(dataDirectoriesStart + 2 * 8)

                // find the section containing the resource directory
                val secTable = optionalHeaderStart + sizeOfOptionalHeader
                if (secTable + numberOfSections * 40 > headerSize) {
                    // section table extends beyond our header read; re-read with larger buffer
                    val needed = secTable + numberOfSections * 40
                    if (needed > fileSize) return false
                    val bigHdr = ByteArray(needed)
                    raf.seek(0)
                    raf.readFully(bigHdr)
                    return extractFromHeaders(raf, fileSize, ByteBuffer.wrap(bigHdr).order(ByteOrder.LITTLE_ENDIAN),
                        numberOfSections, secTable, resourceDirRva, outIcoFile)
                }

                extractFromHeaders(raf, fileSize, hb, numberOfSections, secTable, resourceDirRva, outIcoFile)
            }
        } catch (e: Exception) {
            Timber.w(e, "EXE icon extraction failed for ${exeFile.name}")
            false
        }
    }

    private fun extractFromHeaders(
        raf: RandomAccessFile,
        fileSize: Long,
        hb: ByteBuffer,
        numberOfSections: Int,
        secTable: Int,
        resourceDirRva: Int,
        outIcoFile: File,
    ): Boolean {
        // find .rsrc section covering resourceDirRva
        var rsrcVA = 0
        var rsrcRawPtr = 0
        var rsrcRawSize = 0
        for (i in 0 until numberOfSections) {
            val base = secTable + i * 40
            val va = hb.getInt(base + 12)
            val rawSize = hb.getInt(base + 16)
            val rawPtr = hb.getInt(base + 20)
            val virtualSize = hb.getInt(base + 8)
            // use Long to avoid Int overflow on large VAs
            val sectionStart = va.toLong()
            val sectionEnd = sectionStart + maxOf(rawSize, virtualSize).toLong()
            if (resourceDirRva.toLong() >= sectionStart && resourceDirRva.toLong() < sectionEnd && rawPtr > 0) {
                rsrcVA = va
                rsrcRawPtr = rawPtr
                rsrcRawSize = rawSize
                break
            }
        }
        if (rsrcRawSize <= 0 || rsrcRawPtr <= 0) return false
        if (rsrcRawSize > MAX_RSRC_SECTION) return false
        if (rsrcRawPtr.toLong() + rsrcRawSize > fileSize) return false

        // read only the resource section
        val rsrc = ByteArray(rsrcRawSize)
        raf.seek(rsrcRawPtr.toLong())
        raf.readFully(rsrc)
        val bb = ByteBuffer.wrap(rsrc).order(ByteOrder.LITTLE_ENDIAN)

        // convert RVA to offset within rsrc buffer
        fun rvaToRsrc(rva: Int): Int {
            val off = rva - rsrcVA
            return if (off in 0 until rsrcRawSize) off else -1
        }

        val resRootOff = rvaToRsrc(resourceDirRva)
        if (resRootOff < 0 || resRootOff + 16 > rsrcRawSize) return false

        data class Entry(val nameOrId: Int, val dataOrSubdirRva: Int, val isSubdir: Boolean, val isNamed: Boolean)

        fun readDirectory(offset: Int): List<Entry> {
            if (offset + 16 > rsrcRawSize) return emptyList()
            val entryCountNamed = bb.getShort(offset + 12).toInt() and 0xFFFF
            val entryCountId = bb.getShort(offset + 14).toInt() and 0xFFFF
            val total = entryCountNamed + entryCountId
            val entries = ArrayList<Entry>(total)
            var eoff = offset + 16
            repeat(total) {
                if (eoff + 8 > rsrcRawSize) return emptyList()
                val name = bb.getInt(eoff)
                val dataRva = bb.getInt(eoff + 4)
                val isDir = (dataRva and 0x80000000.toInt()) != 0
                val isNamed = (name and 0x80000000.toInt()) != 0
                entries.add(Entry(name, dataRva and 0x7FFFFFFF, isDir, isNamed))
                eoff += 8
            }
            return entries
        }

        // subdirectory offsets are relative to resource root
        fun subdirOffset(dirRva: Int): Int {
            val off = resRootOff + dirRva
            return if (off in 0 until rsrcRawSize) off else -1
        }

        // locate RT_GROUP_ICON: Type(14) -> first ID -> first LANG
        val typeEntries = readDirectory(resRootOff)
        val groupType = typeEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == RT_GROUP_ICON }
            ?: return false
        if (!groupType.isSubdir) return false
        val groupTypeDirOff = subdirOffset(groupType.dataOrSubdirRva)
        if (groupTypeDirOff < 0) return false
        val idEntries = readDirectory(groupTypeDirOff)
        val groupId = idEntries.firstOrNull() ?: return false
        if (!groupId.isSubdir) return false
        val groupIdDirOff = subdirOffset(groupId.dataOrSubdirRva)
        if (groupIdDirOff < 0) return false
        val langEntries = readDirectory(groupIdDirOff)
        val groupLang = langEntries.firstOrNull() ?: return false
        if (groupLang.isSubdir) return false
        val groupDataEntryOff = subdirOffset(groupLang.dataOrSubdirRva)
        if (groupDataEntryOff < 0 || groupDataEntryOff + 16 > rsrcRawSize) return false
        val groupDataRva = bb.getInt(groupDataEntryOff)
        val groupSize = bb.getInt(groupDataEntryOff + 4)
        if (groupSize < 6) return false // GRPICONDIR header is 6 bytes
        val groupDataOff = rvaToRsrc(groupDataRva)
        if (groupDataOff < 0 || groupDataOff + groupSize > rsrcRawSize) return false

        // parse GRPICONDIR
        val reserved = bb.getShort(groupDataOff).toInt() and 0xFFFF
        val type = bb.getShort(groupDataOff + 2).toInt() and 0xFFFF
        val count = bb.getShort(groupDataOff + 4).toInt() and 0xFFFF
        if (reserved != 0 || type != 1 || count <= 0 || count > 64) return false

        data class GroupEntry(
            val width: Int, val height: Int, val colorCount: Int,
            val planes: Int, val bitCount: Int, val bytesInRes: Int, val id: Int,
        )

        val groupEntries = ArrayList<GroupEntry>(count)
        var ptr = groupDataOff + 6
        repeat(count) {
            if (ptr + 14 > groupDataOff + groupSize) return false
            groupEntries.add(GroupEntry(
                width = bb.get(ptr).toInt() and 0xFF,
                height = bb.get(ptr + 1).toInt() and 0xFF,
                colorCount = bb.get(ptr + 2).toInt() and 0xFF,
                planes = bb.getShort(ptr + 4).toInt() and 0xFFFF,
                bitCount = bb.getShort(ptr + 6).toInt() and 0xFFFF,
                bytesInRes = bb.getInt(ptr + 8),
                id = bb.getShort(ptr + 12).toInt() and 0xFFFF,
            ))
            ptr += 14
        }

        // build map of RT_ICON id -> data
        val iconType = typeEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == RT_ICON }
            ?: return false
        if (!iconType.isSubdir) return false
        val iconTypeDirOff = subdirOffset(iconType.dataOrSubdirRva)
        if (iconTypeDirOff < 0) return false
        val iconIdEntries = readDirectory(iconTypeDirOff)

        fun findIconDataById(id: Int): ByteArray? {
            val idEntry = iconIdEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == id } ?: return null
            if (!idEntry.isSubdir) return null
            val langDirOff = subdirOffset(idEntry.dataOrSubdirRva)
            if (langDirOff < 0) return null
            val langs = readDirectory(langDirOff)
            val lang = langs.firstOrNull() ?: return null
            if (lang.isSubdir) return null
            val dataEntryOff = subdirOffset(lang.dataOrSubdirRva)
            if (dataEntryOff < 0 || dataEntryOff + 16 > rsrcRawSize) return null
            val dataRva = bb.getInt(dataEntryOff)
            val dataSize = bb.getInt(dataEntryOff + 4)
            if (dataSize <= 0 || dataSize > rsrcRawSize) return null
            val dataOff = rvaToRsrc(dataRva)
            if (dataOff < 0 || dataOff + dataSize > rsrcRawSize) return null
            val bytes = ByteArray(dataSize)
            System.arraycopy(rsrc, dataOff, bytes, 0, dataSize)
            return bytes
        }

        // collect valid icon data, skipping entries where data lookup fails
        data class IconEntry(val ge: GroupEntry, val data: ByteArray)
        val entries = ArrayList<IconEntry>(groupEntries.size)
        var totalDataSize = 0L
        for (ge in groupEntries) {
            val data = findIconDataById(ge.id) ?: continue
            totalDataSize += data.size
            // malformed PE could reference overlapping regions — cap total
            if (totalDataSize > MAX_RSRC_SECTION) return false
            entries.add(IconEntry(ge, data))
        }
        if (entries.isEmpty()) return false

        // build ICO: header + directory entries + concatenated images
        val entriesBytes = ByteArray(entries.size * 16)
        var imageOffset = 6 + entriesBytes.size
        for ((i, entry) in entries.withIndex()) {
            val base = i * 16
            entriesBytes[base + 0] = entry.ge.width.coerceAtMost(255).toByte()
            entriesBytes[base + 1] = entry.ge.height.coerceAtMost(255).toByte()
            entriesBytes[base + 2] = entry.ge.colorCount.coerceAtMost(255).toByte()
            entriesBytes[base + 3] = 0
            putShort(entriesBytes, base + 4, entry.ge.planes)
            putShort(entriesBytes, base + 6, entry.ge.bitCount)
            putInt(entriesBytes, base + 8, entry.data.size)
            putInt(entriesBytes, base + 12, imageOffset)
            imageOffset += entry.data.size
        }

        val out = ByteArray(6 + entriesBytes.size + entries.sumOf { it.data.size })
        putShort(out, 0, 0) // reserved
        putShort(out, 2, 1) // type ico
        putShort(out, 4, entries.size)
        System.arraycopy(entriesBytes, 0, out, 6, entriesBytes.size)
        var off = 6 + entriesBytes.size
        for (entry in entries) {
            System.arraycopy(entry.data, 0, out, off, entry.data.size)
            off += entry.data.size
        }

        outIcoFile.outputStream().use { it.write(out) }
        return true
    }

    private fun putShort(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putInt(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v ushr 8) and 0xFF).toByte()
        arr[off + 2] = ((v ushr 16) and 0xFF).toByte()
        arr[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
