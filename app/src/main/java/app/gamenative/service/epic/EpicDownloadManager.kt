package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicGame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * EpicDownloadManager handles downloading Epic games using Kotlin/OkHttp
 * instead of Legendary's Python downloader.
 *
 * Epic's CDN structure:
 * 1. Fetch manifest from CDN (contains list of chunks and files)
 * 2. Download chunks from CDN (compressed data)
 * 3. Decompress and assemble chunks into files
 * 4. Verify file hashes
 *
 * Manifest structure (from legendary.models.manifest):
 * - meta: App metadata (app_name, build_version, etc.)
 * - chunk_data_list: List of chunks to download
 * - file_manifest_list: List of files and their chunk composition
 */
@Singleton
class EpicDownloadManager @Inject constructor(
    private val epicManager: EpicManager,
) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 4
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer for decompression
    }

    /**
     * Download and install an Epic game
     *
     * @param context Android context
     * @param game Epic game to download
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        context: Context,
        game: EpicGame,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

            // Step 1: Fetch manifest binary and CDN URLs from Epic
            val manifestResult = epicManager.fetchManifestFromEpic(
                context,
                game.namespace,
                game.id,
                game.appName,
            )
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val manifestData = manifestResult.getOrNull()!!
            val cdnUrls = manifestData.cdnUrls.filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs, parsing...")

            // Step 2: Parse manifest binary to get chunks and files
            val manifest = app.gamenative.service.epic.manifest.EpicManifest.readAll(manifestData.manifestBytes)

            // Extract chunk and file data from parsed manifest
            val chunkDataList = manifest.chunkDataList
                ?: return@withContext Result.failure(Exception("No chunk data in manifest"))
            val fileManifestList = manifest.fileManifestList
                ?: return@withContext Result.failure(Exception("No file manifest in manifest"))

            val chunks = chunkDataList.elements
            val files = fileManifestList.elements
            val chunkDir = manifest.getChunkDir()

            val totalSize = files.sumOf { it.fileSize }
            val chunkCount = chunks.size
            val fileCount = files.size

            Timber.tag("Epic").i(
                """
                |Download prepared:
                |  Total size: ${totalSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
                |  ChunkDir: $chunkDir
                """.trimMargin(),
            )

            downloadInfo.setTotalExpectedBytes(totalSize)
            downloadInfo.updateStatusMessage("Downloading chunks...")

            // Step 3: Download chunks in parallel
            val chunkCacheDir = File(installPath, ".chunks")
            chunkCacheDir.mkdirs()

            Timber.tag("Epic").d(
                """
                |=== NATIVE KOTLIN MANIFEST DATA ===
                |CDN URLs (${cdnUrls.size}):
                |${cdnUrls.joinToString("\n") { "  - ${it.baseUrl}" }}
                |Chunks: ${chunks.size}
                |Files: ${files.size}
                |==================================
                """.trimMargin(),
            )

            // Download chunks in batches to avoid overwhelming the system
            chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                if (!downloadInfo.isActive()) {
                    Timber.tag("Epic").w("Download cancelled by user")
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                // Download batch in parallel
                val results = chunkBatch.map { chunk ->
                    async {
                        downloadChunk(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed
                results.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                    )
                }
            }

            downloadInfo.updateStatusMessage("Decompressing and assembling files...")

            // Step 4: Assemble files from chunks
            val installDir = File(installPath)
            installDir.mkdirs()

            for ((index, fileManifest) in files.withIndex()) {
                downloadInfo.updateStatusMessage("Assembling file ${index + 1}/$fileCount")

                val assembleResult = assembleFile(fileManifest, chunkCacheDir, installDir)
                if (assembleResult.isFailure) {
                    return@withContext Result.failure(
                        assembleResult.exceptionOrNull() ?: Exception("Failed to assemble file"),
                    )
                }
            }

            // Step 5: Cleanup chunk directory
            chunkCacheDir.deleteRecursively()

            // Log final directory structure
            Timber.tag("Epic").i("Download completed successfully for ${game.title}")
            logDirectoryStructure(installDir)

            downloadInfo.updateStatusMessage("Complete")
            downloadInfo.setProgress(1.0f)
            downloadInfo.emitProgressChange() // Force final progress update

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Download a single chunk from Epic CDN with decompression
     */
    private suspend fun downloadChunk(
        chunk: app.gamenative.service.epic.manifest.ChunkInfo,
        chunkCacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        downloadInfo: DownloadInfo,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val chunkFile = File(chunkCacheDir, "${chunk.guidStr}.chunk")
            val decompressedFile = File(chunkCacheDir, chunk.guidStr)

            // Skip if already downloaded and decompressed
            if (decompressedFile.exists() && decompressedFile.length() == chunk.windowSize.toLong()) {
                Timber.tag("Epic").d("Chunk ${chunk.guidStr} already exists, skipping")
                downloadInfo.updateBytesDownloaded(chunk.fileSize)
                return@withContext Result.success(decompressedFile)
            }

            // Get chunk path for downloading
            val chunkPath = chunk.getPath(chunkDir)

            Timber.tag("EpicManifest").i("Chunk Dir: $chunkPath")

            // Try each CDN base URL until one succeeds
            var lastException: Exception? = null
            for (cdnUrl in cdnUrls) {
                try {
                    // Note: chunks are downloaded without auth tokens (tokens are only for manifests)
                    // Build full URL: baseUrl + cloudDir + chunkPath
                    val url = "${cdnUrl.baseUrl}${cdnUrl.cloudDir}/$chunkPath"
                    Timber.tag("Epic").d("Downloading chunk from: $url")

                    // TODO: Check whether or not we should provide the user agent.
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                        .build()

                    Timber.tag("Epic").d(
                        """
                        |NATIVE Chunk download request:
                        |  URL: ${request.url}
                        |  Method: ${request.method}
                        |  Headers: ${request.headers}
                        """.trimMargin(),
                    )

                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        lastException = Exception("HTTP ${response.code} downloading chunk from ${cdnUrl.baseUrl}")
                        continue
                    }

                    // Download Epic chunk file (contains header + potentially compressed data)
                    val chunkBytes = response.body?.bytes() ?: throw Exception("Empty response body")
                    downloadInfo.updateBytesDownloaded(chunkBytes.size.toLong())

                    // Parse Epic Chunk format and decompress if needed
                    val decompressedData = readEpicChunk(chunkBytes)

                    // Verify size matches expected
                    if (decompressedData.size.toLong() != chunk.windowSize.toLong()) {
                        throw Exception("Decompressed size mismatch: expected ${chunk.windowSize}, got ${decompressedData.size}")
                    }

                    // Verify SHA hash
                    if (!verifyChunkHashBytes(decompressedData, chunk.shaHash)) {
                        throw Exception("Chunk hash verification failed for ${chunk.guid}")
                    }

                    // Write decompressed data
                    decompressedFile.outputStream().use { it.write(decompressedData) }

                    return@withContext Result.success(decompressedFile)
                } catch (e: Exception) {
                    Timber.tag("Epic").w(e, "Failed to download from ${cdnUrl.baseUrl}, trying next...")
                    lastException = e
                }
            }

            // All URLs failed
            return@withContext Result.failure(lastException ?: Exception("All CDN URLs failed for chunk ${chunk.guidStr}"))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to download chunk ${chunk.guidStr}")
            Result.failure(e)
        }
    }

    /**
     * Read and decompress an Epic Chunk file
     * Epic chunks have their own format with header + optional compression
     *
     * Format (from legendary/models/chunk.py):
     * - Magic: 0xB1FE3AA2 (4 bytes)
     * - Header version: 3 (4 bytes)
     * - Header size: 66 (4 bytes)
     * - Compressed size (4 bytes)
     * - GUID (16 bytes)
     * - Hash (8 bytes)
     * - Stored as flags (1 byte) - bit 0 = compressed
     * - SHA hash (20 bytes)
     * - Hash type (1 byte)
     * - Uncompressed size (4 bytes)
     * - Data (compressed_size bytes)
     */
    private fun readEpicChunk(chunkBytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Read header
        val magic = buffer.int
        if (magic != 0xB1FE3AA2.toInt()) {
            throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
        }

        val headerVersion = buffer.int
        val headerSize = buffer.int
        val compressedSize = buffer.int

        // Skip GUID (16 bytes), hash (8 bytes)
        buffer.position(buffer.position() + 24)

        // Read stored_as flag
        val storedAs = buffer.get().toInt() and 0xFF
        val isCompressed = (storedAs and 0x1) == 0x1

        // Skip SHA hash (20 bytes), hash type (1 byte), uncompressed size (4 bytes)
        buffer.position(buffer.position() + 25)

        // Read chunk data starting from header end
        val dataStart = headerSize
        val dataBytes = chunkBytes.copyOfRange(dataStart, dataStart + compressedSize)

        return if (isCompressed) {
            // Decompress using zlib
            val inflater = Inflater()
            try {
                inflater.setInput(dataBytes)
                val result = ByteArray(1024 * 1024) // Epic chunks are always 1 MiB uncompressed
                val resultLength = inflater.inflate(result)
                result.copyOf(resultLength)
            } finally {
                inflater.end()
            }
        } else {
            // Already uncompressed
            dataBytes
        }
    }

    /**
     * Decompress a chunk file using zlib inflation (deprecated - keeping for reference)
     * Epic chunks use zlib compression (deflate algorithm)
     */
    @Deprecated("Use readEpicChunk instead")
    private fun decompressChunk(compressedFile: File, outputFile: File, expectedSize: Long) {
        val inflater = Inflater()
        try {
            compressedFile.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    val compressedData = input.readBytes()
                    inflater.setInput(compressedData)

                    val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                    var totalDecompressed = 0L

                    while (!inflater.finished()) {
                        val decompressedCount = inflater.inflate(buffer)
                        if (decompressedCount > 0) {
                            output.write(buffer, 0, decompressedCount)
                            totalDecompressed += decompressedCount
                        }
                    }

                    if (totalDecompressed != expectedSize) {
                        throw Exception("Decompressed size mismatch: expected $expectedSize, got $totalDecompressed")
                    }
                }
            }
        } finally {
            inflater.end()
        }
    }

    /**
     * Verify chunk SHA-1 hash from byte array
     */
    private fun verifyChunkHashBytes(data: ByteArray, expectedHash: ByteArray): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(data)
            val actualHash = digest.digest()
            val matches = actualHash.contentEquals(expectedHash)

            if (!matches) {
                val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
                val actualHex = actualHash.joinToString("") { "%02x".format(it) }
                Timber.tag("Epic").e("Hash mismatch: expected $expectedHex, got $actualHex")
            }

            matches
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Hash verification failed")
            false
        }
    }

    /**
     * Verify chunk SHA-1 hash from file
     */
    private fun verifyChunkHash(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actualHash.equals(expectedHash, ignoreCase = true)

            if (!matches) {
                Timber.tag("Epic").e("Hash mismatch: expected $expectedHash, got $actualHash")
            }

            matches
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Hash verification failed")
            false
        }
    }

    /**
     * Assemble a file from its chunks
     */
    private suspend fun assembleFile(
        fileManifest: app.gamenative.service.epic.manifest.FileManifest,
        chunkCacheDir: File,
        installDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, fileManifest.filename)
            outputFile.parentFile?.mkdirs()

            outputFile.outputStream().use { output ->
                for (chunkPart in fileManifest.chunkParts) {
                    val chunkFile = File(chunkCacheDir, chunkPart.guidStr)

                    if (!chunkFile.exists()) {
                        return@withContext Result.failure(Exception("Chunk file missing: ${chunkPart.guidStr}"))
                    }

                    // Read chunk data at specified offset
                    chunkFile.inputStream().use { input ->
                        input.skip(chunkPart.offset.toLong())

                        val buffer = ByteArray(8192)
                        var remaining = chunkPart.size.toLong()

                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)

                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to assemble file ${fileManifest.filename}")
            Result.failure(e)
        }
    }

    /**
     * Log the directory structure of the installed game
     */
    private fun logDirectoryStructure(dir: File, prefix: String = "", isRoot: Boolean = true) {
        if (!dir.exists()) {
            Timber.tag("Epic").w("Directory does not exist: ${dir.absolutePath}")
            return
        }

        if (isRoot) {
            Timber.tag("Epic").i("=== Installation Directory Structure ===")
            Timber.tag("Epic").i("Root: ${dir.absolutePath}")
        }

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

        files.forEachIndexed { index, file ->
            val isLast = index == files.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val fileInfo = if (file.isDirectory) {
                "${file.name}/"
            } else {
                val size = formatFileSize(file.length())
                "${file.name} ($size)"
            }

            Timber.tag("Epic").i("$prefix$connector$fileInfo")

            // Recursively log subdirectories
            if (file.isDirectory) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                logDirectoryStructure(file, newPrefix, isRoot = false)
            }
        }

        if (isRoot) {
            val totalSize = calculateTotalSize(dir)
            val fileCount = countFiles(dir)
            Timber.tag("Epic").i("=== Summary ===")
            Timber.tag("Epic").i("Total files: $fileCount")
            Timber.tag("Epic").i("Total size: ${formatFileSize(totalSize)}")
            Timber.tag("Epic").i("==================")
        }
    }

    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Calculate total size of a directory recursively
     */
    private fun calculateTotalSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateTotalSize(it) } ?: 0
    }

    /**
     * Count total number of files in a directory recursively
     */
    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile) return 1
        return dir.listFiles()?.sumOf { countFiles(it) } ?: 0
    }
}
