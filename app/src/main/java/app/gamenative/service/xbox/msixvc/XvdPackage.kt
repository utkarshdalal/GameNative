// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.util.Locale
import java.util.UUID

/** An encrypted XVC region. Offsets are absolute package bytes. */
class EncryptedSection(
    val offset: Long,
    val length: Long,
    val regionId: Int,
    val keyIndex: Int,
    val keyId: UUID,
    val firstSegmentIndex: Long,
) {
    val end get() = offset + length
    fun containsPage(page: Long) = page * PAGE_SIZE >= offset && page * PAGE_SIZE < end
}

/** A piece of a file: absolute package offset (-1 = zeros) and length. */
data class PackageExtent(val offset: Long, val length: Long)

class PackageFile(
    /** Package path, '\' separated, as listed by the package. */
    val path: String,
    val size: Long,
    /** Set for files the package marks KEEP_ENCRYPTED_ON_DISK; they are still installed decrypted. */
    val keepEncrypted: Boolean,
    val extents: List<PackageExtent>,
    val residentData: ByteArray? = null,
)

class XvdPackage private constructor(
    val source: RangeSource,
    val header: XvdHeader,
    val layout: XvdLayout,
    val xvcInfo: XvcInfo?,
    val regions: List<XvcRegionHeader>,
    val encryptedSections: List<EncryptedSection>,
) {
    /** The id xodus uses as the package content id (the header vduid). */
    val contentId: UUID get() = header.vduid
    val vduid: UUID get() = header.vduid
    val xvcContentId: UUID? get() = xvcInfo?.contentId
    val version: PackageVersion get() = header.packageVersion

    /** False for every package that opens; MSIXVC2 packages are refused in [open]. */
    val isMsixvc2: Boolean get() = false

    val files: List<PackageFile> by lazy { listFiles() }
    val totalInstallSize: Long get() = files.sumOf { it.size }

    fun sectionForPage(page: Long): EncryptedSection? = encryptedSections.firstOrNull { it.containsPage(page) }

    /** Index of a package page in hash tree level 0. */
    private fun hashIndex(page: Long) = page - layout.userData.startPage

    /** Bytes of the level 0 hash entry that hold the truncated SHA-256 of the stored page. */
    val level0HashLength: Int
        get() = if (header.volumeFlags and XvdVolumeFlags.ENCRYPTION_DISABLED != 0) HASH_ENTRY_LENGTH else 20

    private val hashPageCache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > HASH_PAGE_CACHE
    }

    private fun hashTreePages(first: Long, last: Long): Array<ByteArray> {
        synchronized(hashPageCache) {
            if ((first..last).all { hashPageCache.containsKey(it) }) return Array((last - first + 1).toInt()) { hashPageCache[first + it]!! }
        }
        val raw = source.read(layout.hashTree.startByte + first * PAGE_SIZE, ((last - first + 1) * PAGE_SIZE).toInt())
        val pages = Array((last - first + 1).toInt()) { raw.copyOfRange(it * PAGE_SIZE.toInt(), (it + 1) * PAGE_SIZE.toInt()) }
        synchronized(hashPageCache) { pages.forEachIndexed { i, p -> hashPageCache[first + i] = p } }
        return pages
    }

    /**
     * Level 0 hash entries (24 bytes each) for package pages [firstPage, firstPage + count).
     * In encrypted volumes the entry is a 20-byte truncated SHA-256 of the stored page plus the
     * XTS data unit (u32 LE); otherwise a 24-byte truncated SHA-256.
     */
    fun readLevel0Entries(firstPage: Long, count: Int): ByteArray {
        val out = ByteArray(count * HASH_ENTRY_LENGTH)
        if (count == 0) return out
        val levels = layout.hashTreeLayout.topLevel() + 1L
        val hashed = header.numberOfHashedPages
        fun locate(page: Long): Triple<Long, Long, Long> {
            val idx = hashIndex(page)
            if (idx < 0 || idx >= hashed) throw MsixvcException.InvalidPackage("page $page is not covered by the hash tree")
            return calculateHashBlockNumAndRunForBlockNum(header.xvdType, levels, hashed, idx, 0)
        }
        val firstBlock = locate(firstPage).first
        val lastBlock = locate(firstPage + count - 1).first
        val pages = hashTreePages(firstBlock, lastBlock)
        var i = 0
        while (i < count) {
            val (block, entry, run) = locate(firstPage + i)
            val n = minOf(run, (count - i).toLong()).toInt()
            System.arraycopy(pages[(block - firstBlock).toInt()], (entry * HASH_ENTRY_LENGTH).toInt(), out, i * HASH_ENTRY_LENGTH, n * HASH_ENTRY_LENGTH)
            i += n
        }
        return out
    }

    /** Length of the unencrypted prefix of [start, start + len). */
    fun nonEncryptedPrefixLen(start: Long, len: Long): Long {
        val end = start + len
        var prefix = len
        for (s in encryptedSections) {
            if (s.end <= start || s.offset >= end) continue
            if (start >= s.offset) return 0
            prefix = minOf(prefix, s.offset - start)
        }
        return prefix
    }

    fun parseUserPackageFiles(): Map<String, UserPackageFile> {
        val files = LinkedHashMap<String, UserPackageFile>()
        val base = layout.userData.startByte
        if (layout.userData.length < 16) return files
        val udh = source.read(base, 16)
        val udLength = udh.u32(0)
        if (udh.u32(8) != 0L) return files
        var off = base + udLength
        val fh = source.read(off, 528)
        val count = fh.u32(524)
        off += 528
        if (count * 528 > layout.userData.length) throw MsixvcException.InvalidPackage("invalid user data file count $count")
        val entries = source.read(off, (count * 528).toInt())
        for (i in 0 until count.toInt()) {
            val o = i * 528
            val name = entries.utf16z(o, 260)
            files[name] = UserPackageFile(base + 16 + entries.u32(o + 524), entries.u32(o + 520))
        }
        return files
    }

    /** Port of parse_segment_metadata: segments are laid out back to back in each encrypted section. */
    fun parseSegmentMetadata(sm: UserPackageFile): LinkedHashMap<String, PackageFile> {
        val data = source.read(sm.offset, sm.length.toInt())
        val h = SegmentMetadataHeader.parse(data, 0)
        val pathsOffset = h.headerLength + h.segmentCount * SegmentMetadataSegment.SIZE
        if (SegmentMetadataHeader.SIZE + h.segmentCount * SegmentMetadataSegment.SIZE > data.size) {
            throw MsixvcException.InvalidPackage("SegmentMetadata.bin is truncated")
        }
        val segments = (0 until h.segmentCount.toInt()).map {
            SegmentMetadataSegment.parse(data, SegmentMetadataHeader.SIZE + it * SegmentMetadataSegment.SIZE)
        }
        val files = LinkedHashMap<String, PackageFile>()
        for (section in encryptedSections) {
            var page = ceilDiv(section.offset, PAGE_SIZE)
            for (segNo in section.firstSegmentIndex until h.segmentCount) {
                val seg = segments[segNo.toInt()]
                val pOff = (pathsOffset + seg.pathOffset).toInt()
                if (pOff + seg.pathLength * 2 > data.size) throw MsixvcException.InvalidPackage("segment path out of bounds")
                val name = String(CharArray(seg.pathLength) { data.u16(pOff + it * 2).toChar() })
                val pages = if (seg.fileSize == 0L) 1 else ceilDiv(seg.fileSize, PAGE_SIZE)
                if (page * PAGE_SIZE >= section.end) break
                val extents = if (seg.fileSize == 0L) emptyList() else listOf(PackageExtent(page * PAGE_SIZE, seg.fileSize))
                files[name] = PackageFile(name, seg.fileSize, seg.keepEncryptedOnDisk, extents)
                page += pages
            }
        }
        return files
    }

    /**
     * Port of parse_ntfs_segment_metadata: the first used GPT partition of the drive holds an
     * NTFS volume whose metadata lies in the unencrypted prefix of the drive. With [onlyPlain],
     * only files whose data starts in that prefix are returned.
     */
    fun parseNtfsFiles(onlyPlain: Boolean): LinkedHashMap<String, PackageFile> {
        val driveStart = layout.driveData.startByte
        val drivePlain = nonEncryptedPrefixLen(driveStart, header.driveSize)
        val plainEnd = driveStart + drivePlain
        fun plainReader(base: Long) = VolumeReader { offset, length ->
            val abs = base + offset
            if (offset < 0 || abs + length > plainEnd) {
                throw MsixvcException.NtfsUnsupported("NTFS metadata read at $abs+$length lies outside the unencrypted drive prefix")
            }
            source.read(abs, length)
        }
        val (partStart, _) = GptReader.firstUsedPartition(plainReader(driveStart))
        val partOffset = driveStart + partStart
        val entries = NtfsReader(plainReader(partOffset)).listFiles()
        val files = LinkedHashMap<String, PackageFile>()
        for (e in entries) {
            if (e.path.contains(':')) continue
            val extents = e.extents.map { if (it.offset < 0) PackageExtent(-1, it.length) else PackageExtent(partOffset + it.offset, it.length) }
            val firstData = extents.firstOrNull { it.offset >= 0 }
            if (onlyPlain && firstData != null && firstData.offset >= plainEnd) continue
            e.unsupported?.let { throw MsixvcException.NtfsUnsupported("${e.path}: $it") }
            val name = e.path.replace('/', '\\')
            val keep = !onlyPlain && name.lowercase(Locale.ROOT).endsWith(".exe")
            files[name] = PackageFile(name, e.size, keep, extents, e.residentData)
        }
        return files
    }

    /** SegmentMetadata.bin files plus NTFS files, as xodus's streaming command merges them. */
    fun listFiles(): List<PackageFile> {
        var segmentFiles = LinkedHashMap<String, PackageFile>()
        parseUserPackageFiles()["SegmentMetadata.bin"]?.let { segmentFiles = parseSegmentMetadata(it) }
        val ntfsFiles = parseNtfsFiles(onlyPlain = segmentFiles.isNotEmpty())
        val merged = LinkedHashMap<String, PackageFile>()
        for (f in segmentFiles.values + ntfsFiles.values) merged[normalizeKey(f.path)] = f
        return merged.values.sortedBy { it.extents.firstOrNull { e -> e.offset >= 0 }?.offset ?: -1L }
    }

    private fun normalizeKey(p: String) = p.replace('/', '\\').trimStart('\\').lowercase(Locale.ROOT)

    companion object {
        private const val HASH_PAGE_CACHE = 256
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** MSIXVC2 packages are ZIP archives (see xodus issue #53 and XvdTool.Streaming PR 3). */
        fun isMsixvc2(source: RangeSource): Boolean =
            source.length >= 4 && source.read(0, 4).contentEquals(ZIP_MAGIC)

        fun open(source: RangeSource): XvdPackage {
            if (isMsixvc2(source)) throw MsixvcException.Msixvc2Unsupported()
            if (source.length < XvdHeader.SIZE) throw MsixvcException.InvalidPackage("file too short")
            val header = XvdHeader.parse(source.read(0, XvdHeader.SIZE))
            val layout = header.layout()
            if (!header.isDataIntegrityEnabled) {
                throw MsixvcException.UnsupportedPackage("packages without data integrity are not supported")
            }
            if (header.isResiliencyEnabled) {
                throw MsixvcException.UnsupportedPackage("resilient hash trees are not supported")
            }
            var xvcInfo: XvcInfo? = null
            val regions = ArrayList<XvcRegionHeader>()
            if (layout.xvcInfo.length > 0) {
                val info = source.read(layout.xvcInfo.startByte, XvcInfo.SIZE)
                xvcInfo = XvcInfo.parse(info)
                if (xvcInfo.version >= 1) {
                    val count = xvcInfo.regionCount
                    if (count > 4096) throw MsixvcException.InvalidPackage("invalid region count $count")
                    val rb = source.read(layout.xvcInfo.startByte + XvcInfo.SIZE, (count * XvcRegionHeader.SIZE).toInt())
                    for (i in 0 until count.toInt()) regions.add(XvcRegionHeader.parse(rb, i * XvcRegionHeader.SIZE))
                }
            }
            val sections = regions.filter { it.keyIndex >= 0 }.map { r ->
                if (r.offset + r.length > source.length) throw MsixvcException.InvalidPackage("region ${r.regionId} exceeds package size")
                val keyId = xvcInfo!!.keyIds[r.keyIndex]
                    ?: throw MsixvcException.InvalidPackage("region ${r.regionId} uses empty key slot ${r.keyIndex}")
                EncryptedSection(r.offset, r.length, r.regionId, r.keyIndex, keyId, r.firstSegmentIndex)
            }
            return XvdPackage(source, header, layout, xvcInfo, regions, sections)
        }
    }
}
