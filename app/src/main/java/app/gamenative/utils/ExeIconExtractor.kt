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
 * Notes/limits:
 * - Designed for common PE32/PE32+ files that store icons in the standard
 *   resource layout. Exotic layouts may not be supported.
 * - Best-effort with bounds checks; on any parsing error it returns false.
 */
object ExeIconExtractor {
    private const val RT_CURSOR = 1
    private const val RT_BITMAP = 2
    private const val RT_ICON = 3
    private const val RT_GROUP_CURSOR = 12
    private const val RT_GROUP_ICON = 14

    fun tryExtractMainIcon(exeFile: File, outIcoFile: File): Boolean {
        return try {
            RandomAccessFile(exeFile, "r").use { raf ->
                val size = raf.length()
                if (size < 0x100) return false
                val buf = ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                raf.readFully(buf)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

                val peHeaderOff = bb.getInt(0x3C)
                if (peHeaderOff <= 0 || peHeaderOff + 4 > bb.capacity()) return false
                if (bb.get(peHeaderOff).toInt() != 'P'.code || bb.get(peHeaderOff + 1).toInt() != 'E'.code) return false

                val coffStart = peHeaderOff + 4
                val numberOfSections = bb.getShort(coffStart + 2).toInt() and 0xFFFF
                val sizeOfOptionalHeader = bb.getShort(coffStart + 16).toInt() and 0xFFFF
                val optionalHeaderStart = coffStart + 20
                val magic = bb.getShort(optionalHeaderStart).toInt() and 0xFFFF
                // Optional header data directories location depends on PE32/PE32+
                val dataDirectoriesStart = optionalHeaderStart + when (magic) {
                    0x10B -> 96 // PE32
                    0x20B -> 112 // PE32+
                    else -> return false
                }
                val resourceDirRva = bb.getInt(dataDirectoriesStart + 2 * 8) // index 2 = IMAGE_DIRECTORY_ENTRY_RESOURCE
                val resourceDirSize = bb.getInt(dataDirectoriesStart + 2 * 8 + 4)

                // Sections table
                var secTable = optionalHeaderStart + sizeOfOptionalHeader
                if (secTable + numberOfSections * 40 > bb.capacity()) return false

                fun rvaToFileOffset(rva: Int): Int {
                    for (i in 0 until numberOfSections) {
                        val base = secTable + i * 40
                        val virtualAddress = bb.getInt(base + 12)
                        val sizeOfRawData = bb.getInt(base + 16)
                        val pointerToRawData = bb.getInt(base + 20)
                        if (rva >= virtualAddress && rva < virtualAddress + sizeOfRawData &&
                            pointerToRawData > 0
                        ) {
                            val off = pointerToRawData + (rva - virtualAddress)
                            if (off >= 0 && off < bb.capacity()) return off
                        }
                    }
                    return -1
                }

                val resRootOff = rvaToFileOffset(resourceDirRva)
                if (resRootOff < 0 || resRootOff + 16 > bb.capacity()) return false

                // Resource directory traversal helpers
                data class Dir(val off: Int)
                data class Entry(val nameOrId: Int, val dataOrSubdirRva: Int, val isSubdir: Boolean, val isNamed: Boolean)

                fun readDirectory(offset: Int): Pair<List<Entry>, Int> {
                    val entryCountNamed = bb.getShort(offset + 12).toInt() and 0xFFFF
                    val entryCountId = bb.getShort(offset + 14).toInt() and 0xFFFF
                    val total = entryCountNamed + entryCountId
                    val entries = ArrayList<Entry>(total)
                    var eoff = offset + 16
                    repeat(total) {
                        if (eoff + 8 > bb.capacity()) return Pair(emptyList(), 0)
                        val name = bb.getInt(eoff)
                        val dataRva = bb.getInt(eoff + 4)
                        val isDir = (dataRva and 0x80000000.toInt()) != 0
                        val isNamed = (name and 0x80000000.toInt()) != 0
                        entries.add(Entry(name, dataRva and 0x7FFFFFFF.toInt(), isDir, isNamed))
                        eoff += 8
                    }
                    return Pair(entries, total)
                }

                fun subdirOffset(dirRva: Int): Int {
                    val off = rvaToFileOffset(resourceDirRva + dirRva)
                    return if (off >= 0) off else -1
                }

                // Locate RT_GROUP_ICON node: Type(14) -> first ID -> first LANG
                val (typeEntries, _) = readDirectory(resRootOff)
                val groupType = typeEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == RT_GROUP_ICON }
                    ?: return false
                if (!groupType.isSubdir) return false
                val groupTypeDirOff = subdirOffset(groupType.dataOrSubdirRva)
                if (groupTypeDirOff < 0) return false
                val (idEntries, _) = readDirectory(groupTypeDirOff)
                val groupId = idEntries.firstOrNull() ?: return false
                if (!groupId.isSubdir) return false
                val groupIdDirOff = subdirOffset(groupId.dataOrSubdirRva)
                if (groupIdDirOff < 0) return false
                val (langEntries, _) = readDirectory(groupIdDirOff)
                val groupLang = langEntries.firstOrNull() ?: return false
                val groupDataEntryOff = rvaToFileOffset(resourceDirRva + groupLang.dataOrSubdirRva)
                if (groupDataEntryOff < 0 || groupDataEntryOff + 16 > bb.capacity()) return false
                val groupDataRva = bb.getInt(groupDataEntryOff)
                val groupSize = bb.getInt(groupDataEntryOff + 4)
                val groupDataOff = rvaToFileOffset(groupDataRva)
                if (groupDataOff < 0 || groupDataOff + groupSize > bb.capacity()) return false

                // Parse GRPICONDIR
                val reserved = bb.getShort(groupDataOff).toInt() and 0xFFFF
                val type = bb.getShort(groupDataOff + 2).toInt() and 0xFFFF
                val count = bb.getShort(groupDataOff + 4).toInt() and 0xFFFF
                if (reserved != 0 || type != 1 || count <= 0 || count > 64) return false

                data class GroupEntry(
                    val width: Int,
                    val height: Int,
                    val colorCount: Int,
                    val planes: Int,
                    val bitCount: Int,
                    val bytesInRes: Int,
                    val id: Int,
                )

                val groupEntries = ArrayList<GroupEntry>(count)
                var ptr = groupDataOff + 6
                repeat(count) {
                    if (ptr + 14 > groupDataOff + groupSize) return false
                    val w = bb.get(ptr).toInt() and 0xFF
                    val h = bb.get(ptr + 1).toInt() and 0xFF
                    val cc = bb.get(ptr + 2).toInt() and 0xFF
                    /* reserved */
                    val planes = bb.getShort(ptr + 4).toInt() and 0xFFFF
                    val bitcount = bb.getShort(ptr + 6).toInt() and 0xFFFF
                    val bytes = bb.getInt(ptr + 8)
                    val id = bb.getShort(ptr + 12).toInt() and 0xFFFF
                    groupEntries.add(GroupEntry(w, h, cc, planes, bitcount, bytes, id))
                    ptr += 14
                }

                // Build map of RT_ICON id -> data
                val iconType = typeEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == RT_ICON }
                    ?: return false
                if (!iconType.isSubdir) return false
                val iconTypeDirOff = subdirOffset(iconType.dataOrSubdirRva)
                if (iconTypeDirOff < 0) return false
                val (iconIdEntries, _) = readDirectory(iconTypeDirOff)

                fun findIconDataById(id: Int): ByteArray? {
                    val idEntry = iconIdEntries.firstOrNull { !it.isNamed && (it.nameOrId and 0x7FFFFFFF) == id } ?: return null
                    if (!idEntry.isSubdir) return null
                    val langDirOff = subdirOffset(idEntry.dataOrSubdirRva)
                    if (langDirOff < 0) return null
                    val (langs, _) = readDirectory(langDirOff)
                    val lang = langs.firstOrNull() ?: return null
                    val dataEntryOff = rvaToFileOffset(resourceDirRva + lang.dataOrSubdirRva)
                    if (dataEntryOff < 0 || dataEntryOff + 16 > bb.capacity()) return null
                    val dataRva = bb.getInt(dataEntryOff)
                    val dataSize = bb.getInt(dataEntryOff + 4)
                    val dataOff = rvaToFileOffset(dataRva)
                    if (dataOff < 0 || dataOff + dataSize > bb.capacity()) return null
                    val bytes = ByteArray(dataSize)
                    System.arraycopy(buf, dataOff, bytes, 0, dataSize)
                    return bytes
                }

                // Build ICO: header + entries + concatenated images
                val images = ArrayList<ByteArray>(groupEntries.size)
                val entriesBytes = ByteArray(groupEntries.size * 16)
                var imageOffset = 6 + entriesBytes.size // after header+entries
                var ei = 0
                for (ge in groupEntries) {
                    val data = findIconDataById(ge.id) ?: continue
                    images.add(data)
                    val base = ei * 16
                    entriesBytes[base + 0] = ge.width.coerceAtMost(255).toByte()
                    entriesBytes[base + 1] = ge.height.coerceAtMost(255).toByte()
                    entriesBytes[base + 2] = ge.colorCount.coerceAtMost(255).toByte()
                    entriesBytes[base + 3] = 0 // reserved
                    putShort(entriesBytes, base + 4, ge.planes)
                    putShort(entriesBytes, base + 6, ge.bitCount)
                    putInt(entriesBytes, base + 8, data.size)
                    putInt(entriesBytes, base + 12, imageOffset)
                    imageOffset += data.size
                    ei++
                }
                if (images.isEmpty()) return false

                val out = ByteArray(6 + entriesBytes.size + images.sumOf { it.size })
                // ICONDIR
                putShort(out, 0, 0) // reserved
                putShort(out, 2, 1) // type ico
                putShort(out, 4, images.size)
                // entries
                System.arraycopy(entriesBytes, 0, out, 6, entriesBytes.size)
                // images
                var off = 6 + entriesBytes.size
                for (img in images) {
                    System.arraycopy(img, 0, out, off, img.size)
                    off += img.size
                }

                outIcoFile.outputStream().use { it.write(out) }
                true
            }
        } catch (e: Exception) {
            Timber.w(e, "EXE icon extraction failed for ${exeFile.name}")
            false
        }
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
