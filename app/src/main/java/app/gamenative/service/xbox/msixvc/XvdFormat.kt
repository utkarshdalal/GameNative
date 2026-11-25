// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.util.UUID

const val PAGE_SIZE = 4096L
const val PAGES_PER_BLOCK = 170L // 0xAA000 / 0x1000
const val HASH_ENTRY_LENGTH = 24
const val HASH_ENTRIES_IN_PAGE = 170L // 4096 / 24
val MAX_HASHED_PAGES: Long = HASH_ENTRIES_IN_PAGE * HASH_ENTRIES_IN_PAGE * HASH_ENTRIES_IN_PAGE * HASH_ENTRIES_IN_PAGE

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xff
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
internal fun ByteArray.i32(o: Int): Int = u32(o).toInt()
internal fun ByteArray.i64(o: Int): Long = u32(o) or (u32(o + 4) shl 32)

/** Microsoft GUID layout (first three fields little-endian), as uuid::Uuid::from_bytes_le. */
internal fun ByteArray.guidLe(o: Int): UUID {
    val msb = (u32(o) shl 32) or (u16(o + 4).toLong() shl 16) or u16(o + 6).toLong()
    var lsb = 0L
    for (i in 8 until 16) lsb = (lsb shl 8) or u8(o + i).toLong()
    return UUID(msb, lsb)
}

internal fun ByteArray.utf16z(o: Int, maxChars: Int): String {
    val sb = StringBuilder()
    for (i in 0 until maxChars) {
        val c = u16(o + i * 2)
        if (c == 0) break
        sb.append(c.toChar())
    }
    return sb.toString()
}

internal fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

/** Version is stored as build, patch, minor, major (u16 LE each). */
data class PackageVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) : Comparable<PackageVersion> {
    override fun compareTo(other: PackageVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.build })

    override fun toString() = "$major.$minor.$patch.$build"

    companion object {
        fun parse(b: ByteArray, o: Int) = PackageVersion(b.u16(o + 6), b.u16(o + 4), b.u16(o + 2), b.u16(o))
    }
}

object XvdVolumeFlags {
    const val READ_ONLY = 1
    const val ENCRYPTION_DISABLED = 2
    const val DATA_INTEGRITY_DISABLED = 4
    const val LEGACY_SECTOR_SIZE = 8
    const val RESILIENCY_ENABLED = 16
}

class XvdHeader(
    val volumeFlags: Int,
    val formatVersion: Long,
    val driveSize: Long,
    val vduid: UUID,
    /** The vduid bytes as stored; the XTS tweak uses the first eight. */
    val vduidRaw: ByteArray,
    val uduid: UUID,
    val xvdType: Int,
    val contentType: Int,
    val embeddedXvdLength: Long,
    val userDataLength: Long,
    val xvcDataLength: Long,
    val dynamicHeaderLength: Long,
    val blockSize: Long,
    val productId: UUID,
    val pduid: UUID,
    val packageVersion: PackageVersion,
    val mutablePageCount: Long,
    val sequenceNumber: Long,
) {
    val numberOfHashedPages: Long
        get() = ceilDiv(userDataLength, PAGE_SIZE) + ceilDiv(xvcDataLength, PAGE_SIZE) +
            ceilDiv(dynamicHeaderLength, PAGE_SIZE) + ceilDiv(driveSize, PAGE_SIZE)

    val isDataIntegrityEnabled get() = volumeFlags and XvdVolumeFlags.DATA_INTEGRITY_DISABLED == 0
    val isResiliencyEnabled get() = volumeFlags and XvdVolumeFlags.RESILIENCY_ENABLED != 0

    fun layout(): XvdLayout {
        var current = 0L
        fun next(len: Long): XvdSection = XvdSection(current, len).also { current += ceilDiv(len, PAGE_SIZE) }
        val hashTree = HashTreeLayout.create(numberOfHashedPages)
        return XvdLayout(
            header = next(PAGE_SIZE * 3),
            embeddedXvd = next(embeddedXvdLength),
            mutableData = next(mutablePageCount * PAGE_SIZE),
            hashTree = next(hashTree.pages * PAGE_SIZE),
            hashTreeLayout = hashTree,
            userData = next(userDataLength),
            xvcInfo = next(xvcDataLength),
            dynamicHeader = next(dynamicHeaderLength),
            driveData = next(driveSize),
        )
    }

    companion object {
        const val SIZE = 4096
        private val MAGIC = "msft-xvd".toByteArray(Charsets.US_ASCII)

        fun parse(b: ByteArray): XvdHeader {
            if (b.size < SIZE) throw MsixvcException.InvalidPackage("header too short")
            if (!b.copyOfRange(0x200, 0x208).contentEquals(MAGIC)) {
                throw MsixvcException.InvalidPackage("invalid XVD magic")
            }
            val driveSize = b.i64(0x218)
            val xvdType = b.i32(0x280)
            if (xvdType != 0 && xvdType != 1) throw MsixvcException.InvalidPackage("invalid xvd type $xvdType")
            val h = XvdHeader(
                volumeFlags = b.i32(0x208),
                formatVersion = b.u32(0x20C),
                driveSize = driveSize,
                vduid = b.guidLe(0x220),
                vduidRaw = b.copyOfRange(0x220, 0x230),
                uduid = b.guidLe(0x230),
                xvdType = xvdType,
                contentType = b.i32(0x284),
                embeddedXvdLength = b.u32(0x288),
                userDataLength = b.u32(0x28C),
                xvcDataLength = b.u32(0x290),
                dynamicHeaderLength = b.u32(0x294),
                blockSize = b.u32(0x298),
                productId = b.guidLe(0x39C),
                pduid = b.guidLe(0x3AC),
                packageVersion = PackageVersion.parse(b, 0x3BC),
                mutablePageCount = b.u8(0x470).toLong(),
                sequenceNumber = b.i64(0x48C),
            )
            if (driveSize < 0 || driveSize > MAX_HASHED_PAGES * PAGE_SIZE || h.numberOfHashedPages > MAX_HASHED_PAGES) {
                throw MsixvcException.InvalidPackage("invalid hashed pages count")
            }
            if (h.numberOfHashedPages == 0L) throw MsixvcException.InvalidPackage("package has no hashed pages")
            return h
        }
    }
}

/** A section of the package: start page and length in bytes. */
data class XvdSection(val startPage: Long, val length: Long) {
    val startByte get() = startPage * PAGE_SIZE
}

data class XvdLayout(
    val header: XvdSection,
    val embeddedXvd: XvdSection,
    val mutableData: XvdSection,
    val hashTree: XvdSection,
    val hashTreeLayout: HashTreeLayout,
    val userData: XvdSection,
    val xvcInfo: XvdSection,
    val dynamicHeader: XvdSection,
    val driveData: XvdSection,
)

/** Pages are relative to the start of the hash tree. */
data class HashTreeLevel(val startPage: Long, val numPages: Long, val numHashes: Long) {
    val isTop get() = numHashes in 1..HASH_ENTRIES_IN_PAGE
}

/** Levels are stored top (3) to bottom (0); see docs/msixvc/integrity.md. */
data class HashTreeLayout(val level3: HashTreeLevel, val level2: HashTreeLevel, val level1: HashTreeLevel, val level0: HashTreeLevel) {
    val pages get() = level0.startPage + level0.numPages

    fun topLevel(): Int = listOf(level0, level1, level2, level3).indexOfFirst { it.isTop }.also {
        check(it >= 0) { "level 0 must contain at least one hash entry" }
    }

    companion object {
        private fun storedHashes(hashedPages: Long) = if (hashedPages == 1L) 0L else hashedPages
        private fun levelPages(hashedPages: Long) = ceilDiv(storedHashes(hashedPages), HASH_ENTRIES_IN_PAGE)

        fun create(hashedPages: Long): HashTreeLayout {
            require(hashedPages > 0) { "hashed pages must be positive" }
            require(hashedPages <= MAX_HASHED_PAGES) { "too many hashed pages" }
            val l0 = maxOf(levelPages(hashedPages), 1L)
            val l1 = levelPages(l0)
            val l2 = levelPages(l1)
            val l3 = levelPages(l2)
            check(l3 <= 1)
            val level3 = HashTreeLevel(0, l3, storedHashes(l2))
            val level2 = HashTreeLevel(level3.startPage + l3, l2, storedHashes(l1))
            val level1 = HashTreeLevel(level2.startPage + l2, l1, storedHashes(l0))
            val level0 = HashTreeLevel(level1.startPage + l1, l0, hashedPages)
            return HashTreeLayout(level3, level2, level1, level0)
        }
    }
}

/** (hash block page within the hash tree, entry index within that page, run length). Port of math.rs. */
fun calculateHashBlockNumAndRunForBlockNum(
    xvdType: Int,
    hashTreeLevels: Long,
    numberOfHashedPages: Long,
    blockNum: Long,
    hashLevel: Int,
    resilient: Boolean = false,
    unknown: Boolean = false,
): Triple<Long, Long, Long> {
    fun exp(n: Int): Long {
        var r = 1L
        repeat(n) { r *= PAGES_PER_BLOCK }
        return r
    }
    if (xvdType > 1 || hashLevel > 3) return Triple(0xFFFF, 0, 1)
    val entry = (blockNum / exp(hashLevel)) % PAGES_PER_BLOCK
    val run = PAGES_PER_BLOCK - entry
    if (hashLevel == 3) return Triple(0, entry, run)
    var result = blockNum / exp(hashLevel + 1)
    var remaining = hashTreeLevels - (hashLevel + 1)
    if (hashLevel == 0 && remaining > 0) {
        result += ceilDiv(numberOfHashedPages, exp(2))
        remaining--
    }
    if ((hashLevel == 0 || hashLevel == 1) && remaining > 0) {
        result += ceilDiv(numberOfHashedPages, exp(3))
        remaining--
    }
    if (remaining > 0) result += ceilDiv(numberOfHashedPages, exp(4))
    if (resilient) result *= 2
    if (unknown) result += 1
    return Triple(result, entry, run)
}

class XvcRegionHeader(
    val regionId: Int,
    /** Index into the XVC key slots, or -1 when the region is not encrypted (key id >= 0xC0). */
    val keyIndex: Int,
    val flags: Int,
    val firstSegmentIndex: Long,
    val description: String,
    val offset: Long,
    val length: Long,
) {
    companion object {
        const val SIZE = 128

        fun parse(b: ByteArray, o: Int): XvcRegionHeader {
            val keyId = b.u16(o + 4)
            val offset = b.i64(o + 0x50)
            val length = b.i64(o + 0x58)
            if (offset % PAGE_SIZE != 0L) throw MsixvcException.InvalidPackage("region offset $offset is not page aligned")
            if (length % PAGE_SIZE != 0L) throw MsixvcException.InvalidPackage("region length $length is not page aligned")
            return XvcRegionHeader(
                regionId = b.i32(o),
                keyIndex = if (keyId < 0xC0) keyId else -1,
                flags = b.i32(o + 8),
                firstSegmentIndex = b.u32(o + 12),
                description = b.utf16z(o + 16, 0x20),
                offset = offset,
                length = length,
            )
        }
    }
}

class XvcInfo(
    val contentId: UUID,
    /** 192 key slots; null for the nil UUID. */
    val keyIds: List<UUID?>,
    val version: Long,
    val regionCount: Long,
    val keyCount: Int,
) {
    companion object {
        const val SIZE = 3496
        const val KEY_SLOTS = 0xC0
        private val NIL = UUID(0, 0)

        fun parse(b: ByteArray): XvcInfo {
            val keys = (0 until KEY_SLOTS).map { i -> b.guidLe(16 + i * 16).takeIf { it != NIL } }
            return XvcInfo(
                contentId = b.guidLe(0),
                keyIds = keys,
                version = b.u32(0xD10),
                regionCount = b.u32(0xD14),
                keyCount = b.u16(0xD1E),
            )
        }
    }
}

class UserPackageFile(val offset: Long, val length: Long)

class SegmentMetadataHeader(val headerLength: Long, val segmentCount: Long, val filePathsLength: Long) {
    companion object {
        const val SIZE = 100
        private val MAGIC = " PFX".toByteArray(Charsets.US_ASCII)

        fun parse(b: ByteArray, o: Int): SegmentMetadataHeader {
            if (!b.copyOfRange(o, o + 4).contentEquals(MAGIC)) {
                throw MsixvcException.InvalidPackage("invalid SegmentMetadata magic")
            }
            return SegmentMetadataHeader(b.u32(o + 12), b.u32(o + 16), b.u32(o + 20))
        }
    }
}

class SegmentMetadataSegment(val flags: Int, val pathLength: Int, val pathOffset: Long, val fileSize: Long) {
    val keepEncryptedOnDisk get() = flags and 1 != 0

    companion object {
        const val SIZE = 16

        fun parse(b: ByteArray, o: Int) = SegmentMetadataSegment(b.u16(o), b.u16(o + 2), b.u32(o + 4), b.i64(o + 8))
    }
}
