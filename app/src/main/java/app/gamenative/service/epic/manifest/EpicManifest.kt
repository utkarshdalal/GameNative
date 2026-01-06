package app.gamenative.service.epic.manifest

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater
import timber.log.Timber

/**
 * Base class for Epic Games manifest parsing.
 * Supports both binary and JSON manifest formats.
 */
sealed class EpicManifest {
    var headerSize: Int = 41
    var sizeCompressed: Int = 0
    var sizeUncompressed: Int = 0
    var shaHash: ByteArray = ByteArray(20)
    var storedAs: Byte = 0
    var version: Int = 18
    var data: ByteArray = ByteArray(0)

    // Parsed components
    var meta: ManifestMeta? = null
    var chunkDataList: ChunkDataList? = null
    var fileManifestList: FileManifestList? = null
    var customFields: CustomFields? = null

    val isCompressed: Boolean
        get() = (storedAs.toInt() and 0x1) != 0

    companion object {
        const val HEADER_MAGIC: UInt = 0x44BEC00Cu
        const val DEFAULT_SERIALIZATION_VERSION = 17

        /**
         * Detects manifest format and returns appropriate parser
         */
        fun detect(data: ByteArray): EpicManifest {
            return if (data.size >= 4) {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buffer.int.toUInt()
                if (magic == HEADER_MAGIC) {
                    Timber.tag("Epic").i("Binary Manifest Detected!")
                    BinaryManifest()
                } else {
                    Timber.tag("Epic").i("JSON Manifest Detected!")
                    JsonManifest()
                }
            } else {
                // Try JSON by default for small files
                Timber.tag("Epic").i("Defaulting to JSON Manifest...")
                JsonManifest()
            }
        }

        /**
         * Read and parse complete manifest from bytes
         */
        fun readAll(data: ByteArray): EpicManifest {
            val manifest = detect(data)
            manifest.read(data)
            manifest.parseContents()
            return manifest
        }
    }

    abstract fun read(data: ByteArray)
    abstract fun parseContents()

    /**
     * Get chunk directory based on manifest version
     */
    fun getChunkDir(): String {
        return when {
            version >= 15 -> "ChunksV4"
            version >= 6 -> "ChunksV3" // TODO: Fix V3Chunking - Currently this one is problematic
            version >= 3 -> "ChunksV2"
            else -> "Chunks"
        }
    }
}

/**
 * Binary format manifest parser (most common format)
 */
class BinaryManifest : EpicManifest() {
    override fun read(data: ByteArray) {
        val input = ByteArrayInputStream(data)
        val buffer = ByteBuffer.allocate(data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(data)
        buffer.flip()

        // Read header
        val magic = buffer.int.toUInt()
        if (magic != HEADER_MAGIC) {
            throw IllegalArgumentException("Invalid manifest header magic: 0x${magic.toString(16)}")
        }

        headerSize = buffer.int
        sizeUncompressed = buffer.int
        sizeCompressed = buffer.int
        buffer.get(shaHash)
        storedAs = buffer.get()
        version = buffer.int

        // Seek to end of header if we didn't read it all
        if (buffer.position() != headerSize) {
            buffer.position(headerSize)
        }

        // Read body data
        val bodyData = ByteArray(buffer.remaining())
        buffer.get(bodyData)

        // Decompress if necessary
        this.data = if (isCompressed) {
            val inflater = Inflater()
            inflater.setInput(bodyData)
            val decompressed = ByteArray(sizeUncompressed)
            val resultLength = inflater.inflate(decompressed)
            inflater.end()

            // Verify hash
            val md = MessageDigest.getInstance("SHA-1")
            val computedHash = md.digest(decompressed)
            if (!computedHash.contentEquals(shaHash)) {
                throw IllegalStateException("Manifest hash mismatch!")
            }

            decompressed
        } else {
            bodyData
        }
    }

    override fun parseContents() {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Parse in order: Meta, CDL, FML, CustomFields
        meta = ManifestMeta.read(buffer)
        chunkDataList = ChunkDataList.read(buffer, meta?.featureLevel ?: version)
        fileManifestList = FileManifestList.read(buffer)
        customFields = CustomFields.read(buffer)

        // Clear raw data to save memory
        data = ByteArray(0)
    }
}

/**
 * JSON format manifest parser (older format, less common)
 */
class JsonManifest : EpicManifest() {
    override fun read(data: ByteArray) {
        this.data = data
        storedAs = 0 // Never compressed
    }

    override fun parseContents() {
        // Use JsonManifestParser to parse the JSON data
        val parsedManifest = JsonManifestParser.parse(data)

        // Copy parsed data to this instance
        this.version = parsedManifest.version
        this.headerSize = parsedManifest.headerSize
        this.storedAs = parsedManifest.storedAs
        this.meta = parsedManifest.meta
        this.chunkDataList = parsedManifest.chunkDataList
        this.fileManifestList = parsedManifest.fileManifestList
        this.customFields = parsedManifest.customFields

        // Clear raw data to save memory
        this.data = ByteArray(0)
    }
}

/**
 * Manifest metadata containing game information
 */
data class ManifestMeta(
    var metaSize: Int = 0,
    var dataVersion: Byte = 0,
    var featureLevel: Int = 18,
    var isFileData: Boolean = false,
    var appId: Int = 0,
    var appName: String = "",
    var buildVersion: String = "",
    var launchExe: String = "",
    var launchCommand: String = "",
    var prereqIds: List<String> = emptyList(),
    var prereqName: String = "",
    var prereqPath: String = "",
    var prereqArgs: String = "",
    var uninstallActionPath: String = "",
    var uninstallActionArgs: String = "",
    var buildId: String = ""
) {
    companion object {
        fun read(buffer: ByteBuffer): ManifestMeta {
            val meta = ManifestMeta()
            val startPos = buffer.position()

            meta.metaSize = buffer.int
            meta.dataVersion = buffer.get()
            meta.featureLevel = buffer.int
            meta.isFileData = buffer.get() == 1.toByte()
            meta.appId = buffer.int
            meta.appName = readFString(buffer)
            meta.buildVersion = readFString(buffer)
            meta.launchExe = readFString(buffer)
            meta.launchCommand = readFString(buffer)

            // Prerequisite IDs list
            val prereqCount = buffer.int
            meta.prereqIds = List(prereqCount) { readFString(buffer) }

            meta.prereqName = readFString(buffer)
            meta.prereqPath = readFString(buffer)
            meta.prereqArgs = readFString(buffer)

            // Data version 1+ includes build ID
            if (meta.dataVersion >= 1) {
                meta.buildId = readFString(buffer)
            }

            // Data version 2+ includes uninstall actions
            if (meta.dataVersion >= 2) {
                meta.uninstallActionPath = readFString(buffer)
                meta.uninstallActionArgs = readFString(buffer)
            }

            // Verify we read the expected amount
            val bytesRead = buffer.position() - startPos
            if (bytesRead != meta.metaSize) {
                // Skip remaining bytes if we didn't read all
                buffer.position(startPos + meta.metaSize)
            }

            return meta
        }
    }
}

/**
 * Chunk Data List - contains all downloadable chunks
 */
data class ChunkDataList(
    var version: Byte = 0,
    var size: Int = 0,
    var count: Int = 0,
    val elements: MutableList<ChunkInfo> = mutableListOf(),
    private var manifestVersion: Int = 18
) {
    private val guidMap: MutableMap<String, Int> by lazy {
        elements.mapIndexed { index, chunk -> chunk.guidStr to index }.toMap(mutableMapOf())
    }

    private val guidIntMap: MutableMap<ULong, Int> by lazy {
        elements.mapIndexed { index, chunk -> chunk.guidNum to index }.toMap(mutableMapOf())
    }

    fun getChunkByGuid(guid: String): ChunkInfo? {
        return guidMap[guid.lowercase()]?.let { elements[it] }
    }

    fun getChunkByGuidNum(guidNum: ULong): ChunkInfo? {
        return guidIntMap[guidNum]?.let { elements[it] }
    }

    companion object {
        fun read(buffer: ByteBuffer, manifestVersion: Int): ChunkDataList {
            val cdl = ChunkDataList(manifestVersion = manifestVersion)
            val startPos = buffer.position()

            cdl.size = buffer.int
            cdl.version = buffer.get()
            cdl.count = buffer.int

            // Pre-allocate chunk list
            repeat(cdl.count) {
                cdl.elements.add(ChunkInfo(manifestVersion = manifestVersion))
            }

            // Read data in columnar format (all GUIDs, then all hashes, etc.)
            // GUIDs (128-bit each)
            cdl.elements.forEach { chunk ->
                chunk.guid = intArrayOf(buffer.int, buffer.int, buffer.int, buffer.int)
            }

            // Hashes (64-bit each)
            cdl.elements.forEach { chunk ->
                chunk.hash = buffer.long.toULong()
            }

            // SHA1 hashes (160-bit each)
            cdl.elements.forEach { chunk ->
                buffer.get(chunk.shaHash)
            }

            // Group numbers (8-bit each)
            cdl.elements.forEach { chunk ->
                chunk.groupNum = buffer.get().toInt() and 0xFF
            }

            // Window sizes (32-bit each) - uncompressed size
            cdl.elements.forEach { chunk ->
                chunk.windowSize = buffer.int
            }

            // File sizes (64-bit each) - compressed download size
            cdl.elements.forEach { chunk ->
                chunk.fileSize = buffer.long
            }

            // Verify size
            val bytesRead = buffer.position() - startPos
            if (bytesRead != cdl.size) {
                buffer.position(startPos + cdl.size)
            }

            return cdl
        }
    }
}

/**
 * Information about a single chunk
 */
data class ChunkInfo(
    var guid: IntArray = IntArray(4),
    var hash: ULong = 0u,
    var shaHash: ByteArray = ByteArray(20),
    var groupNum: Int = 0,
    var windowSize: Int = 0,
    var fileSize: Long = 0,
    var useHashPrefixForV3: Boolean = false,
    private val manifestVersion: Int = 18
) {
    val guidStr: String by lazy {
        guid.joinToString("-") { "%08x".format(it) }
    }

    val guidNum: ULong by lazy {
        (guid[0].toULong() shl 96) or
                (guid[1].toULong() shl 64) or
                (guid[2].toULong() shl 32) or
                guid[3].toULong()
    }

    /**
     * Get the download path for this chunk
     * For V3/V4: subfolder is groupNum
     */
    fun getPath(chunkDir: String = getChunkDir(manifestVersion)): String {
        val guidHex = guid.joinToString("") { "%08X".format(it) }
        val hashHex = hash.toString(16).uppercase().padStart(16, '0')
        val subfolder = when (chunkDir) {
            "ChunksV3" -> {
                if (useHashPrefixForV3) hashHex.substring(0, 2) else "%02d".format(groupNum)
            }
            else -> "%02d".format(groupNum)
        }
        return "$chunkDir/$subfolder/${hashHex}_$guidHex.chunk"
    }

    companion object {
        private fun getChunkDir(version: Int): String {
            Timber.tag("EpicManifest").i("Found Manifest version: $version")
            return when {
                version >= 15 -> "ChunksV4"
                version >= 6 -> "ChunksV3"
                version >= 3 -> "ChunksV2"
                else -> "Chunks"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChunkInfo
        return guid.contentEquals(other.guid)
    }

    override fun hashCode(): Int {
        return guid.contentHashCode()
    }
}

/**
 * File Manifest List - contains all game files
 */
data class FileManifestList(
    var version: Byte = 0,
    var size: Int = 0,
    var count: Int = 0,
    val elements: MutableList<FileManifest> = mutableListOf()
) {
    private val pathMap: MutableMap<String, Int> by lazy {
        elements.mapIndexed { index, fm -> fm.filename to index }.toMap(mutableMapOf())
    }

    fun getFileByPath(path: String): FileManifest? {
        return pathMap[path]?.let { elements[it] }
    }

    companion object {
        fun read(buffer: ByteBuffer): FileManifestList {
            val fml = FileManifestList()
            val startPos = buffer.position()

            fml.size = buffer.int
            fml.version = buffer.get()
            fml.count = buffer.int

            // Pre-allocate file list
            repeat(fml.count) {
                fml.elements.add(FileManifest())
            }

            // Read in columnar format
            // Filenames
            fml.elements.forEach { fm ->
                fm.filename = readFString(buffer)
            }

            // Symlink targets
            fml.elements.forEach { fm ->
                fm.symlinkTarget = readFString(buffer)
            }

            // SHA1 hashes
            fml.elements.forEach { fm ->
                buffer.get(fm.hash)
            }

            // Flags
            fml.elements.forEach { fm ->
                fm.flags = buffer.get().toInt() and 0xFF
            }

            // Install tags
            fml.elements.forEach { fm ->
                val tagCount = buffer.int
                fm.installTags = List(tagCount) { readFString(buffer) }
            }

            // Chunk parts
            fml.elements.forEach { fm ->
                val partCount = buffer.int
                var fileOffset = 0L

                repeat(partCount) {
                    val partStartPos = buffer.position()
                    val partSize = buffer.int

                    val part = ChunkPart(
                        guid = intArrayOf(buffer.int, buffer.int, buffer.int, buffer.int),
                        offset = buffer.int,
                        size = buffer.int,
                        fileOffset = fileOffset
                    )

                    fm.chunkParts.add(part)
                    fileOffset += part.size.toLong()

                    // Ensure we read the expected size
                    val partBytesRead = buffer.position() - partStartPos
                    if (partBytesRead < partSize) {
                        buffer.position(partStartPos + partSize)
                    }
                }

                fm.fileSize = fileOffset
            }

            // Version 1+: MD5 hashes and MIME types
            if (fml.version >= 1) {
                fml.elements.forEach { fm ->
                    val hasMd5 = buffer.int
                    if (hasMd5 != 0) {
                        buffer.get(fm.hashMd5)
                    }
                }

                fml.elements.forEach { fm ->
                    fm.mimeType = readFString(buffer)
                }
            }

            // Version 2+: SHA256 hashes
            if (fml.version >= 2) {
                fml.elements.forEach { fm ->
                    buffer.get(fm.hashSha256)
                }
            }

            // Verify size
            val bytesRead = buffer.position() - startPos
            if (bytesRead != fml.size) {
                buffer.position(startPos + fml.size)
            }

            return fml
        }
    }
}

/**
 * Represents a single file in the manifest
 */
data class FileManifest(
    var filename: String = "",
    var symlinkTarget: String = "",
    var hash: ByteArray = ByteArray(20),
    var flags: Int = 0,
    var installTags: List<String> = emptyList(),
    var chunkParts: MutableList<ChunkPart> = mutableListOf(),
    var fileSize: Long = 0,
    var hashMd5: ByteArray = ByteArray(16),
    var mimeType: String = "",
    var hashSha256: ByteArray = ByteArray(32)
) {
    val isReadOnly: Boolean get() = (flags and 0x1) != 0
    val isCompressed: Boolean get() = (flags and 0x2) != 0
    val isExecutable: Boolean get() = (flags and 0x4) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FileManifest
        return filename == other.filename
    }

    override fun hashCode(): Int {
        return filename.hashCode()
    }
}

/**
 * Represents a part of a file that comes from a chunk
 */
data class ChunkPart(
    val guid: IntArray,
    val offset: Int,
    val size: Int,
    val fileOffset: Long
) {
    val guidStr: String by lazy {
        guid.joinToString("-") { "%08x".format(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChunkPart
        return guid.contentEquals(other.guid) && offset == other.offset
    }

    override fun hashCode(): Int {
        var result = guid.contentHashCode()
        result = 31 * result + offset
        return result
    }
}

/**
 * Custom fields in the manifest
 */
data class CustomFields(
    private val fields: MutableMap<String, String> = mutableMapOf()
) {
    operator fun get(key: String): String? = fields[key]
    operator fun set(key: String, value: String) {
        fields[key] = value
    }

    companion object {
        fun read(buffer: ByteBuffer): CustomFields {
            val cf = CustomFields()

            if (buffer.hasRemaining()) {
                val size = buffer.int
                val count = buffer.int

                repeat(count) {
                    val key = readFString(buffer)
                    val value = readFString(buffer)
                    cf[key] = value
                }
            }

            return cf
        }
    }
}

/**
 * Read a variable-length string from the buffer (Epic's FString format)
 */
private fun readFString(buffer: ByteBuffer): String {
    val length = buffer.int

    return when {
        length < 0 -> {
            // UTF-16 encoded (negative length)
            val absLength = -length * 2
            val bytes = ByteArray(absLength - 2)
            buffer.get(bytes)
            buffer.position(buffer.position() + 2) // Skip null terminator
            String(bytes, Charsets.UTF_16LE)
        }
        length > 0 -> {
            // ASCII encoded
            val bytes = ByteArray(length - 1)
            buffer.get(bytes)
            buffer.position(buffer.position() + 1) // Skip null terminator
            String(bytes, Charsets.US_ASCII)
        }
        else -> ""
    }
}
