package app.gamenative.service.epic.manifest

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Utilities for working with Epic Games manifests
 */
object ManifestUtils {

    /**
     * Load and parse a manifest from a file
     */
    fun loadFromFile(file: File): EpicManifest {
        return EpicManifest.readAll(file.readBytes())
    }

    /**
     * Load and parse a manifest from an input stream
     */
    fun loadFromStream(stream: InputStream): EpicManifest {
        return EpicManifest.readAll(stream.readBytes())
    }

    /**
     * Load and parse a manifest from bytes
     */
    fun loadFromBytes(data: ByteArray): EpicManifest {
        return EpicManifest.readAll(data)
    }

    /**
     * Verify manifest hash matches expected value
     */
    fun verifyManifestHash(manifestBytes: ByteArray, expectedHash: String): Boolean {
        val md = MessageDigest.getInstance("SHA-1")
        val computedHash = md.digest(manifestBytes)
        val computedHashHex = computedHash.joinToString("") { "%02x".format(it) }
        return computedHashHex.equals(expectedHash, ignoreCase = true)
    }

    /**
     * Get list of all unique chunks referenced by files
     */
    fun getRequiredChunks(manifest: EpicManifest): List<ChunkInfo> {
        val chunkGuids = mutableSetOf<String>()
        val chunks = mutableListOf<ChunkInfo>()

        manifest.fileManifestList?.elements?.forEach { file ->
            file.chunkParts.forEach { part ->
                val guidStr = part.guidStr
                if (chunkGuids.add(guidStr)) {
                    // Find the chunk info for this GUID
                    manifest.chunkDataList?.getChunkByGuid(guidStr)?.let { chunk ->
                        chunks.add(chunk)
                    }
                }
            }
        }

        return chunks
    }

    /**
     * Get list of chunks required for specific files
     */
    fun getChunksForFiles(manifest: EpicManifest, filePaths: List<String>): List<ChunkInfo> {
        val chunkGuids = mutableSetOf<String>()
        val chunks = mutableListOf<ChunkInfo>()

        manifest.fileManifestList?.elements?.forEach { file ->
            if (filePaths.contains(file.filename)) {
                file.chunkParts.forEach { part ->
                    val guidStr = part.guidStr
                    if (chunkGuids.add(guidStr)) {
                        manifest.chunkDataList?.getChunkByGuid(guidStr)?.let { chunk ->
                            chunks.add(chunk)
                        }
                    }
                }
            }
        }

        return chunks
    }

    /**
     * Calculate total download size for manifest
     */
    fun getTotalDownloadSize(manifest: EpicManifest): Long {
        return getRequiredChunks(manifest).sumOf { it.fileSize }
    }

    /**
     * Calculate total installed size for manifest
     */
    fun getTotalInstalledSize(manifest: EpicManifest): Long {
        return manifest.fileManifestList?.elements?.sumOf { it.fileSize } ?: 0L
    }

    /**
     * Get list of all executable files
     */
    fun getExecutableFiles(manifest: EpicManifest): List<FileManifest> {
        return manifest.fileManifestList?.elements?.filter { it.isExecutable } ?: emptyList()
    }

    /**
     * Find file by path in manifest
     */
    fun findFile(manifest: EpicManifest, path: String): FileManifest? {
        return manifest.fileManifestList?.getFileByPath(path)
    }

    /**
     * Get files matching install tags
     */
    fun getFilesWithTags(manifest: EpicManifest, tags: List<String>): List<FileManifest> {
        return manifest.fileManifestList?.elements?.filter { file ->
            tags.any { tag -> file.installTags.contains(tag) }
        } ?: emptyList()
    }

    /**
     * Build chunk download URL
     */
    fun buildChunkUrl(baseUrl: String, chunk: ChunkInfo, manifest: EpicManifest): String {
        val chunkPath = chunk.getPath(manifest.getChunkDir())
        return "$baseUrl/$chunkPath"
    }

    /**
     * Compare two manifests and get list of changed files
     */
    fun compareManifests(oldManifest: EpicManifest, newManifest: EpicManifest): ManifestComparison {
        val oldFiles = oldManifest.fileManifestList?.elements?.associateBy { it.filename } ?: emptyMap()
        val newFiles = newManifest.fileManifestList?.elements?.associateBy { it.filename } ?: emptyMap()

        val added = mutableListOf<FileManifest>()
        val removed = mutableListOf<FileManifest>()
        val modified = mutableListOf<Pair<FileManifest, FileManifest>>()
        val unchanged = mutableListOf<FileManifest>()

        // Find added and modified files
        newFiles.forEach { (filename, newFile) ->
            val oldFile = oldFiles[filename]
            when {
                oldFile == null -> added.add(newFile)
                !oldFile.hash.contentEquals(newFile.hash) -> modified.add(oldFile to newFile)
                else -> unchanged.add(newFile)
            }
        }

        // Find removed files
        oldFiles.forEach { (filename, oldFile) ->
            if (!newFiles.containsKey(filename)) {
                removed.add(oldFile)
            }
        }

        return ManifestComparison(
            added = added,
            removed = removed,
            modified = modified,
            unchanged = unchanged
        )
    }

    /**
     * Get chunks needed for an update (delta download)
     */
    fun getDeltaChunks(oldManifest: EpicManifest, newManifest: EpicManifest): List<ChunkInfo> {
        val comparison = compareManifests(oldManifest, newManifest)
        val changedFiles = comparison.added + comparison.modified.map { it.second }
        return getChunksForFiles(newManifest, changedFiles.map { it.filename })
    }

    /**
     * Format bytes to human-readable string
     */
    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return "%.2f %s".format(size, units[unitIndex])
    }
}

/**
 * Result of comparing two manifests
 */
data class ManifestComparison(
    val added: List<FileManifest>,
    val removed: List<FileManifest>,
    val modified: List<Pair<FileManifest, FileManifest>>,
    val unchanged: List<FileManifest>
) {
    val hasChanges: Boolean
        get() = added.isNotEmpty() || removed.isNotEmpty() || modified.isNotEmpty()

    val totalChangedFiles: Int
        get() = added.size + removed.size + modified.size

    fun getAddedSize(): Long = added.sumOf { it.fileSize }
    fun getRemovedSize(): Long = removed.sumOf { it.fileSize }
    fun getModifiedOldSize(): Long = modified.sumOf { it.first.fileSize }
    fun getModifiedNewSize(): Long = modified.sumOf { it.second.fileSize }

    override fun toString(): String {
        return buildString {
            appendLine("Manifest Comparison:")
            appendLine("  Added: ${added.size} files (${ManifestUtils.formatBytes(getAddedSize())})")
            appendLine("  Removed: ${removed.size} files (${ManifestUtils.formatBytes(getRemovedSize())})")
            appendLine("  Modified: ${modified.size} files")
            appendLine("  Unchanged: ${unchanged.size} files")
        }
    }
}

/**
 * Download information for a file
 */
data class FileDownloadInfo(
    val file: FileManifest,
    val chunks: List<Pair<ChunkInfo, ChunkPart>>,
    val downloadSize: Long,
    val installedSize: Long
)

/**
 * Builder for constructing download plans
 */
class DownloadPlanBuilder(private val manifest: EpicManifest) {
    private val selectedFiles = mutableSetOf<String>()

    /**
     * Add file by path
     */
    fun addFile(path: String): DownloadPlanBuilder {
        selectedFiles.add(path)
        return this
    }

    /**
     * Add multiple files
     */
    fun addFiles(paths: Collection<String>): DownloadPlanBuilder {
        selectedFiles.addAll(paths)
        return this
    }

    /**
     * Add all files matching a pattern
     */
    fun addPattern(pattern: Regex): DownloadPlanBuilder {
        manifest.fileManifestList?.elements?.forEach { file ->
            if (pattern.matches(file.filename)) {
                selectedFiles.add(file.filename)
            }
        }
        return this
    }

    /**
     * Add all files with specific tags
     */
    fun addTags(tags: List<String>): DownloadPlanBuilder {
        ManifestUtils.getFilesWithTags(manifest, tags).forEach { file ->
            selectedFiles.add(file.filename)
        }
        return this
    }

    /**
     * Build the download plan
     */
    fun build(): DownloadPlan {
        val fileInfos = mutableListOf<FileDownloadInfo>()
        val chunkMap = mutableMapOf<String, ChunkInfo>()

        manifest.fileManifestList?.elements?.forEach { file ->
            if (selectedFiles.contains(file.filename)) {
                val chunksForFile = mutableListOf<Pair<ChunkInfo, ChunkPart>>()

                file.chunkParts.forEach { part ->
                    val chunk = manifest.chunkDataList?.getChunkByGuid(part.guidStr)
                    if (chunk != null) {
                        chunksForFile.add(chunk to part)
                        chunkMap[chunk.guidStr] = chunk
                    }
                }

                fileInfos.add(
                    FileDownloadInfo(
                        file = file,
                        chunks = chunksForFile,
                        downloadSize = chunksForFile.sumOf { it.first.fileSize },
                        installedSize = file.fileSize
                    )
                )
            }
        }

        return DownloadPlan(
            files = fileInfos,
            uniqueChunks = chunkMap.values.toList(),
            totalDownloadSize = chunkMap.values.sumOf { it.fileSize },
            totalInstalledSize = fileInfos.sumOf { it.installedSize }
        )
    }
}

/**
 * Complete download plan
 */
data class DownloadPlan(
    val files: List<FileDownloadInfo>,
    val uniqueChunks: List<ChunkInfo>,
    val totalDownloadSize: Long,
    val totalInstalledSize: Long
) {
    val fileCount: Int get() = files.size
    val chunkCount: Int get() = uniqueChunks.size

    override fun toString(): String {
        return buildString {
            appendLine("Download Plan:")
            appendLine("  Files: $fileCount")
            appendLine("  Chunks: $chunkCount")
            appendLine("  Download Size: ${ManifestUtils.formatBytes(totalDownloadSize)}")
            appendLine("  Installed Size: ${ManifestUtils.formatBytes(totalInstalledSize)}")
        }
    }
}
