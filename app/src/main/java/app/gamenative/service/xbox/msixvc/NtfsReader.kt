// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

/** Reads bytes relative to the start of a volume or disk. */
fun interface VolumeReader {
    fun read(offset: Long, length: Int): ByteArray
}

/** First used GPT partition, logical block size 4096 (as xodus opens the drive). */
object GptReader {
    private const val BLOCK = 4096L
    private val SIGNATURE = "EFI PART".toByteArray(Charsets.US_ASCII)

    /** Returns (start byte, length in bytes) of the first used partition. */
    fun firstUsedPartition(disk: VolumeReader): Pair<Long, Long> {
        val header = disk.read(BLOCK, 92)
        if (!header.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw MsixvcException.NtfsUnsupported("drive has no GPT header")
        }
        val entriesLba = header.i64(0x48)
        val count = header.u32(0x50)
        val entrySize = header.u32(0x54)
        if (entrySize < 128 || entrySize > 4096 || count <= 0 || count > 4096) {
            throw MsixvcException.NtfsUnsupported("invalid GPT partition table ($count x $entrySize)")
        }
        val table = disk.read(entriesLba * BLOCK, (count * entrySize).toInt())
        for (i in 0 until count.toInt()) {
            val o = (i * entrySize).toInt()
            val used = (0 until 16).any { table[o + it].toInt() != 0 }
            if (!used) continue
            val first = table.i64(o + 0x20)
            val last = table.i64(o + 0x28)
            return Pair(first * BLOCK, (last - first + 1) * BLOCK)
        }
        throw MsixvcException.NtfsUnsupported("no used GPT partition found")
    }
}

/** A data stream piece: volume byte offset (-1 = sparse, reads as zeros) and length. */
data class NtfsExtent(val offset: Long, val length: Long)

class NtfsFileEntry(
    /** Path with '/' separators, relative to the volume root. */
    val path: String,
    val size: Long,
    val extents: List<NtfsExtent>,
    /** Non-null for resident data. */
    val residentData: ByteArray?,
    /** Why the stream cannot be read (compressed or EFS-encrypted); extents are empty then. */
    val unsupported: String? = null,
)

/**
 * Minimal read-only NTFS reader. It scans the MFT linearly instead of walking the directory
 * B-trees: extension records are merged into their base record (which covers $ATTRIBUTE_LIST),
 * and paths are rebuilt from $FILE_NAME parent references. Only unnamed $DATA streams are listed.
 */
class NtfsReader(private val volume: VolumeReader) {
    private class DataSegment(
        val lowestVcn: Long,
        val runs: List<LongArray>, // [vcn, lcn or -1, clusters]
        val dataSize: Long,
        val initializedSize: Long,
        val resident: ByteArray?,
    )

    private class Name(val parent: Long, val parentSeq: Int, val namespace: Int, val name: String)

    private class Record(val number: Long, val seq: Int, val flags: Int) {
        val names = ArrayList<Name>()
        val data = ArrayList<DataSegment>()
        var unsupported: String? = null
        val inUse get() = flags and 1 != 0
        val isDirectory get() = flags and 2 != 0
    }

    var clusterSize = 0L
        private set
    private var recordSize = 0
    private var sectorSize = 0

    fun listFiles(): List<NtfsFileEntry> {
        val boot = volume.read(0, 512)
        if (!boot.copyOfRange(3, 11).contentEquals("NTFS    ".toByteArray(Charsets.US_ASCII))) {
            throw MsixvcException.NtfsUnsupported("partition is not NTFS")
        }
        sectorSize = boot.u16(0x0B)
        val spcRaw = boot.u8(0x0D)
        val spc = if (spcRaw > 0x80) 1L shl (256 - spcRaw) else spcRaw.toLong()
        clusterSize = sectorSize * spc
        val mftLcn = boot.i64(0x30)
        val cpr = boot[0x40].toInt()
        recordSize = if (cpr > 0) (cpr * clusterSize).toInt() else 1 shl -cpr
        if (sectorSize < 256 || clusterSize <= 0 || recordSize < 512 || recordSize > 65536) {
            throw MsixvcException.NtfsUnsupported("unsupported NTFS geometry")
        }

        val mftRecord = parseRecord(0, volume.read(mftLcn * clusterSize, recordSize))
            ?.record ?: throw MsixvcException.NtfsUnsupported("invalid \$MFT record")
        if (mftRecord.unsupported != null) throw MsixvcException.NtfsUnsupported("\$MFT: ${mftRecord.unsupported}")
        val mftData = mftRecord.data.singleOrNull()
            ?: throw MsixvcException.NtfsUnsupported("\$MFT data spans extension records")
        val mftExtents = runsToExtents(mftData.runs, mftData.dataSize, mftData.dataSize)

        val records = HashMap<Long, Record>()
        val extensions = ArrayList<Pair<Long, Record>>()
        var recordNo = 0L
        for (ext in mftExtents) {
            if (ext.offset < 0) {
                recordNo += ext.length / recordSize
                continue
            }
            var done = 0L
            while (done < ext.length) {
                val n = minOf(READ_CHUNK.toLong(), ext.length - done).toInt()
                val chunk = volume.read(ext.offset + done, n)
                var o = 0
                while (o + recordSize <= n) {
                    val rec = parseRecord(recordNo, chunk.copyOfRange(o, o + recordSize))
                    if (rec != null && rec.inUse) {
                        val base = rec.baseRecord
                        if (base != 0L) extensions.add(base to rec.record) else records[recordNo] = rec.record
                    }
                    recordNo++
                    o += recordSize
                }
                done += n
            }
        }
        for ((base, ext) in extensions) {
            val r = records[base] ?: continue
            r.names.addAll(ext.names)
            r.data.addAll(ext.data)
            if (r.unsupported == null) r.unsupported = ext.unsupported
        }

        val dirPaths = HashMap<Long, String?>()
        fun dirPath(number: Long, seq: Int, depth: Int): String? {
            if (number == ROOT) return ""
            if (depth > 512) return null
            val dir = records[number] ?: return null
            if (!dir.isDirectory || dir.seq != seq) return null
            if (dirPaths.containsKey(number)) return dirPaths[number]
            val name = preferredNames(dir).firstOrNull()
            val p = name?.let { n -> dirPath(n.parent, n.parentSeq, depth + 1)?.let { join(it, n.name) } }
            dirPaths[number] = p
            return p
        }

        val result = ArrayList<NtfsFileEntry>()
        for (rec in records.values) {
            if (rec.isDirectory || rec.number < FIRST_USER_RECORD) continue
            for (n in preferredNames(rec)) {
                val parent = dirPath(n.parent, n.parentSeq, 0) ?: continue
                val path = join(parent, n.name)
                if (path.startsWith("$")) continue
                result.add(rec.unsupported?.let { NtfsFileEntry(path, 0, emptyList(), null, it) } ?: buildEntry(path, rec))
            }
        }
        result.sortBy { it.path }
        return result
    }

    private fun preferredNames(r: Record): List<Name> {
        val nonDos = r.names.filter { it.namespace != NAMESPACE_DOS }
        return nonDos.ifEmpty { r.names }
    }

    private fun join(base: String, name: String) = if (base.isEmpty()) name else "$base/$name"

    private fun buildEntry(path: String, rec: Record): NtfsFileEntry {
        val segments = rec.data.sortedBy { it.lowestVcn }
        if (segments.isEmpty()) return NtfsFileEntry(path, 0, emptyList(), ByteArray(0))
        val first = segments[0]
        first.resident?.let { return NtfsFileEntry(path, it.size.toLong(), emptyList(), it) }
        val runs = segments.flatMap { it.runs }
        return NtfsFileEntry(path, first.dataSize, runsToExtents(runs, first.dataSize, first.initializedSize), null)
    }

    private fun runsToExtents(runs: List<LongArray>, size: Long, initialized: Long): List<NtfsExtent> {
        val out = ArrayList<NtfsExtent>()
        var pos = 0L
        for (run in runs) {
            if (pos >= size) break
            val len = minOf(run[2] * clusterSize, size - pos)
            val start = if (run[1] < 0) -1L else run[1] * clusterSize
            // Bytes past the initialized size read as zeros.
            val validLen = (initialized - pos).coerceIn(0, len)
            if (start >= 0 && validLen > 0) out.add(NtfsExtent(start, validLen))
            if (len - validLen > 0 || start < 0) {
                val zeroStart = if (start < 0) 0 else validLen
                out.add(NtfsExtent(-1, len - zeroStart))
            }
            pos += len
        }
        if (pos < size) throw MsixvcException.NtfsUnsupported("data runs shorter than stream size")
        return mergeSparse(out)
    }

    private fun mergeSparse(list: List<NtfsExtent>): List<NtfsExtent> {
        val out = ArrayList<NtfsExtent>()
        for (e in list) {
            val last = out.lastOrNull()
            if (last != null && ((last.offset < 0 && e.offset < 0) || (last.offset >= 0 && last.offset + last.length == e.offset))) {
                out[out.size - 1] = NtfsExtent(last.offset, last.length + e.length)
            } else {
                out.add(e)
            }
        }
        return out
    }

    private class Parsed(val record: Record, val baseRecord: Long) {
        val inUse get() = record.inUse
    }

    private fun parseRecord(number: Long, b: ByteArray): Parsed? {
        if (b.u32(0) != 0x454C4946L) return null // "FILE"
        if (!applyFixups(b)) return null
        val rec = Record(number, b.u16(0x10), b.u16(0x16))
        val base = b.i64(0x20) and 0xFFFFFFFFFFFFL
        val used = minOf(b.u32(0x18).toInt(), b.size)
        var o = b.u16(0x14)
        while (o + 16 <= used) {
            val type = b.u32(o)
            if (type == 0xFFFFFFFFL) break
            val len = b.u32(o + 4).toInt()
            if (len < 16 || o + len > used) break
            val nonResident = b.u8(o + 8) != 0
            val nameLength = b.u8(o + 9)
            val flags = b.u16(o + 0x0C)
            when (type) {
                ATTR_FILE_NAME -> if (!nonResident) {
                    val v = o + b.u16(o + 0x14)
                    val ref = b.i64(v)
                    val chars = b.u8(v + 0x40)
                    val name = String(CharArray(chars) { b.u16(v + 0x42 + it * 2).toChar() })
                    rec.names.add(Name(ref and 0xFFFFFFFFFFFFL, (ref ushr 48).toInt() and 0xFFFF, b.u8(v + 0x41), name))
                }
                ATTR_DATA -> if (nameLength == 0) {
                    if (flags and 0x00FF != 0) rec.unsupported = "compressed data stream"
                    if (flags and 0x4000 != 0) rec.unsupported = "EFS-encrypted data stream"
                    if (!nonResident) {
                        val vLen = b.u32(o + 0x10).toInt()
                        val vOff = o + b.u16(o + 0x14)
                        val data = b.copyOfRange(vOff, vOff + vLen)
                        rec.data.add(DataSegment(0, emptyList(), vLen.toLong(), vLen.toLong(), data))
                    } else {
                        val lowestVcn = b.i64(o + 0x10)
                        val runOffset = o + b.u16(o + 0x20)
                        rec.data.add(
                            DataSegment(lowestVcn, decodeRuns(b, runOffset, o + len, lowestVcn), b.i64(o + 0x30), b.i64(o + 0x38), null),
                        )
                    }
                }
            }
            o += len
        }
        return Parsed(rec, base)
    }

    private fun decodeRuns(b: ByteArray, start: Int, end: Int, lowestVcn: Long): List<LongArray> {
        val runs = ArrayList<LongArray>()
        var p = start
        var lcn = 0L
        var vcn = lowestVcn
        while (p < end) {
            val h = b.u8(p)
            if (h == 0) break
            val lenSize = h and 0x0F
            val offSize = (h ushr 4) and 0x0F
            p++
            if (lenSize == 0 || lenSize > 8 || offSize > 8 || p + lenSize + offSize > end) {
                throw MsixvcException.NtfsUnsupported("malformed data run")
            }
            var count = 0L
            for (i in 0 until lenSize) count = count or (b.u8(p + i).toLong() shl (8 * i))
            p += lenSize
            if (offSize == 0) {
                runs.add(longArrayOf(vcn, -1, count))
            } else {
                var delta = 0L
                for (i in 0 until offSize) delta = delta or (b.u8(p + i).toLong() shl (8 * i))
                val shift = 64 - 8 * offSize
                if (shift > 0) delta = (delta shl shift) shr shift
                lcn += delta
                runs.add(longArrayOf(vcn, lcn, count))
            }
            p += offSize
            vcn += count
        }
        return runs
    }

    /** Update sequence array: the last two bytes of every 512-byte block hold the sequence number. */
    private fun applyFixups(b: ByteArray): Boolean {
        val usaOffset = b.u16(4)
        val usaCount = b.u16(6)
        if (usaCount < 1 || usaOffset + usaCount * 2 > b.size || (usaCount - 1) * FIXUP_BLOCK > b.size) return false
        val usn = b.u16(usaOffset)
        for (i in 1 until usaCount) {
            val end = i * FIXUP_BLOCK - 2
            if (b.u16(end) != usn) return false
            b[end] = b[usaOffset + i * 2]
            b[end + 1] = b[usaOffset + i * 2 + 1]
        }
        return true
    }

    companion object {
        private const val FIXUP_BLOCK = 512
        private const val ROOT = 5L
        private const val FIRST_USER_RECORD = 16L
        private const val NAMESPACE_DOS = 2
        private const val ATTR_FILE_NAME = 0x30L
        private const val ATTR_DATA = 0x80L
        private const val READ_CHUNK = 4 shl 20
    }
}
