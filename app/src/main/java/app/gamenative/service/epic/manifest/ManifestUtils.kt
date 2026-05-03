package app.gamenative.service.epic.manifest

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Utilities for working with Epic Games manifests
 */
object ManifestUtils {

    /**
     * Load and parse a manifest from bytes
     */
    fun loadFromBytes(data: ByteArray): EpicManifest {
        return EpicManifest.readAll(data)
    }
    /**
     * Get list of files that are part of the default/required install.
     * Epic manifests list all files including optional (e.g. language packs). Files with
     * no install tags are typically always installed; files with tags are optional.
     */
    fun getRequiredInstallFiles(manifest: EpicManifest): List<FileManifest> {
        val all = manifest.fileManifestList?.elements ?: return emptyList()
        val required = all.filter { it.installTags.isEmpty() }
        return if (required.isEmpty()) all else required
    }

    /**
     * Get list of all unique chunks referenced by files
     */
    fun getRequiredChunks(manifest: EpicManifest): List<ChunkInfo> {
        return getRequiredChunksForFileList(manifest, manifest.fileManifestList?.elements ?: emptyList())
    }

    /**
     * Get unique chunks referenced by the given file list
     */
    fun getRequiredChunksForFileList(manifest: EpicManifest, files: List<FileManifest>): List<ChunkInfo> {
        val chunkGuids = mutableSetOf<String>()
        val chunks = mutableListOf<ChunkInfo>()

        files.forEach { file ->
            file.chunkParts.forEach { part ->
                val guidStr = part.guidStr
                if (chunkGuids.add(guidStr)) {
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
     * Calculate total download size for manifest (all files).
     */
    fun getTotalDownloadSize(manifest: EpicManifest): Long {
        return getRequiredChunks(manifest).sumOf { it.fileSize }
    }

    /**
     * Calculate total installed size for manifest (all files).
     */
    fun getTotalInstalledSize(manifest: EpicManifest): Long {
        return manifest.fileManifestList?.elements?.sumOf { it.fileSize } ?: 0L
    }

    /**
     * Calculate download and install size for the given install tags (required + selectedTags).
     * Use emptyList() for required-only. Same logic as download uses.
     */
    fun getSizesForSelectedInstallTags(manifest: EpicManifest, selectedTags: List<String>): Pair<Long, Long> {
        val files = getFilesForSelectedInstallTags(manifest, selectedTags)
        val installSize = files.sumOf { it.fileSize }
        val chunks = getRequiredChunksForFileList(manifest, files)
        val downloadSize = chunks.sumOf { it.fileSize }
        return downloadSize to installSize
    }

    /**
     * Get files matching install tags (optional content only; does not include required).
     */
    fun getFilesWithTags(manifest: EpicManifest, tags: List<String>): List<FileManifest> {
        return manifest.fileManifestList?.elements?.filter { file ->
            tags.any { tag -> file.installTags.contains(tag) }
        } ?: emptyList()
    }

    /**
     * Get the file list to download when the user selects optional install tags (e.g. languages).
     * Returns required (no-tag) files plus any file that has at least one of [selectedTags].
     * So: base game + selected optional content (e.g. German + French = required + German files + French files).
     * Use this when building the download set for "required + these tags" (like Legendary/Heroic).
     */
    fun getFilesForSelectedInstallTags(manifest: EpicManifest, selectedTags: List<String>): List<FileManifest> {
        val all = manifest.fileManifestList?.elements ?: return emptyList()
        if (selectedTags.isEmpty()) return getRequiredInstallFiles(manifest)
        val withLanguage = all.filter { file ->
            file.installTags.isEmpty() || file.installTags.any { it in selectedTags }
        }
        // If selected language matched no files (e.g. manifest uses "de" not "German"), fall back to required-only
        return if (withLanguage.isEmpty()) getRequiredInstallFiles(manifest) else withLanguage
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
