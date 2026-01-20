package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.manifest.EpicManifest
import java.io.ByteArrayInputStream
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
import org.json.JSONObject
import timber.log.Timber

/**
 * EpicDownloadManager handles downloading Epic games
 *
 * Epic's CDN structure:
 * 1. Fetch manifest from CDN (contains list of chunks and files)
 * 2. Download chunks from CDN (compressed data)
 * 3. Decompress and assemble chunks into files
 * 4. Verify file hashes
 *
 * Performance optimizations:
 * - Increased parallel downloads from 4 to 8 for better throughput
 * - Connection pool with 32 connections for reduced connection overhead
 * - Retry logic with exponential backoff for transient network errors
 * - Deferred hash verification to not block network I/O
 * - Parallel file assembly in batches of 4
 * - Larger I/O buffers (64KB) for file assembly
 * - Proper response.close() calls to release connections faster
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
        // Connection pool optimization for parallel downloads
        .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 8 // Increased from 4 for better throughput
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer for decompression
        private const val MAX_CHUNK_RETRIES = 3 // Maximum retries per chunk
        private const val RETRY_DELAY_MS = 1000L // Initial retry delay in milliseconds
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
        language: String = "en-US",
        dlcIds: List<Int>
        commonRedistDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

            // Emit download started event so UI can attach progress listeners
            val gameId = game.id
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, true),
            )

            // Check for DLCs early to calculate total download size
            val dlcsToDownload = if (dlcIds.size > 0) {
                try {
                    Timber.tag("Epic").i("Checking for DLCs for ${game.title}...")
                    Timber.tag("Epic").i("User has opted to download ${dlcIds.size} DLC titles")
                    val dlcs = epicManager.getGamesById(dlcIds)
                    if (dlcs.isNotEmpty()) {
                        Timber.tag("Epic").i("Found ${dlcs.size} DLC(s) for ${game.title}")
                    }
                    Timber.tag("Epic").i("Found ${dlcs.size} owned DLC titles")
                    dlcs
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error checking for DLCs, continuing without")
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Step 1: Fetch manifest binary and CDN URLs from Epic
            val manifestResult = epicManager.fetchManifestFromEpic(
                context,
                game.namespace,
                game.catalogId,
                game.appName,
            )
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val manifestData = manifestResult.getOrNull()!!

            // ! Avoiding Cloudflare as it causes issues with some downloads and is inconsistent.
            val cdnUrls = manifestData.cdnUrls.filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }

            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs, parsing...")

            // Step 2: Parse manifest binary to get chunks and files
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            // Extract chunk and file data from parsed manifest
            val chunkDataList = manifest.chunkDataList
                ?: return@withContext Result.failure(Exception("No chunk data in manifest"))
            val fileManifestList = manifest.fileManifestList
                ?: return@withContext Result.failure(Exception("No file manifest in manifest"))

            val chunks = chunkDataList.elements
            val files = fileManifestList.elements
            val chunkDir = manifest.getChunkDir()

            // Calculate total download size including DLCs
            var totalSize = chunks.sumOf { it.fileSize }
            val baseGameSize = totalSize

            // Fetch DLC manifests to get their sizes for accurate progress tracking
            val dlcManifestData = mutableListOf<Pair<EpicGame, EpicManager.ManifestResult>>()
            if (dlcsToDownload.isNotEmpty()) {
                downloadInfo.updateStatusMessage("Calculating DLC sizes...")
                for (dlc in dlcsToDownload) {
                    try {
                        val dlcManifestResult = epicManager.fetchManifestFromEpic(
                            context,
                            dlc.namespace,
                            dlc.catalogId,
                            dlc.appName,
                        )
                        if (dlcManifestResult.isSuccess) {
                            val dlcManifest = dlcManifestResult.getOrNull()!!
                            val dlcParsed = EpicManifest.readAll(dlcManifest.manifestBytes)
                            val dlcSize = dlcParsed.chunkDataList?.elements?.sumOf { it.fileSize } ?: 0L
                            totalSize += dlcSize
                            dlcManifestData.add(dlc to dlcManifest)
                            Timber.tag("Epic").i("DLC ${dlc.title} size: ${dlcSize / 1_000_000} MB")
                        } else {
                            Timber.tag("Epic").w("Failed to fetch manifest for DLC ${dlc.title}, will skip")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Error fetching manifest for DLC ${dlc.title}")
                    }
                }
            }

            val chunkCount = chunks.size
            val fileCount = files.size

            Timber.tag("Epic").d(
                """
                |Download prepared:
                |  Base game size: ${baseGameSize / 1_000_000_000.0} GB
                |  DLCs: ${dlcManifestData.size}
                |  Total size (including DLCs): ${totalSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
                |  ChunkDir: $chunkDir
                """.trimMargin(),
            )

            downloadInfo.setTotalExpectedBytes(totalSize)
            downloadInfo.updateStatusMessage("Downloading base game...")

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
            var downloadedChunks = 0
            val totalChunks = chunks.size

            // Initialize progress tracking
            downloadInfo.setProgress(0.0f)
            downloadInfo.emitProgressChange()

            chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                if (!downloadInfo.isActive()) {
                    Timber.tag("Epic").w("Download cancelled by user")
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                // Download batch in parallel
                val results = chunkBatch.map { chunk ->
                    async {
                        downloadChunkWithRetry(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed
                results.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                    )
                }

                // Update progress after each batch completes
                downloadedChunks += chunkBatch.size
                val progress = downloadedChunks.toFloat() / totalChunks
                downloadInfo.setProgress(progress)
                val statusMsg = if (dlcManifestData.isNotEmpty()) {
                    "Downloading base game ($downloadedChunks/$totalChunks chunks)"
                } else {
                    "Downloading chunks ($downloadedChunks/$totalChunks)"
                }
                downloadInfo.updateStatusMessage(statusMsg)
                downloadInfo.emitProgressChange()

                Timber.tag("Epic").d("Download progress: $downloadedChunks/$totalChunks chunks (${(progress * 100).toInt()}%)")
            }

            downloadInfo.updateStatusMessage("Assembling files...")

            // Step 4: Assemble files from chunks in parallel batches
            val installDir = File(installPath)
            installDir.mkdirs()

            var assembledFiles = 0
            val totalFiles = files.size

            // Process files in batches for better parallelism
            files.chunked(4).forEach { fileBatch ->
                val assembleResults = fileBatch.map { fileManifest ->
                    async {
                        assembleFile(fileManifest, chunkCacheDir, installDir)
                    }
                }.awaitAll()

                // Check if any assembly failed
                assembleResults.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to assemble file"),
                    )
                }

                assembledFiles += fileBatch.size
                val assemblyProgress = assembledFiles.toFloat() / totalFiles
                downloadInfo.updateStatusMessage("Assembling files ($assembledFiles/$totalFiles)")
                Timber.tag("Epic").d("File assembly progress: $assembledFiles/$totalFiles (${(assemblyProgress * 100).toInt()}%)")
            }

            // Step 5: Cleanup chunk directory
            chunkCacheDir.deleteRecursively()

            // Log final directory structure
            Timber.tag("Epic").i("Download completed successfully for ${game.title}")
            logDirectoryStructure(installDir)

            // Step 6: Update database with install info
            try {
                val updatedGame = game.copy(
                    isInstalled = true,
                    installPath = installPath,
                )
                epicManager.updateGame(updatedGame)
                Timber.tag("Epic").i("Updated database: game marked as installed")
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to update database for game ${game.id}")
                // Don't fail the entire download for DB issues
            }

            // Download DLCs using pre-fetched manifest data
            if (dlcManifestData.isNotEmpty()) {
                try {
                    Timber.tag("Epic").i("Downloading ${dlcManifestData.size} DLC(s) for ${game.title}")

                    dlcManifestData.forEachIndexed { index, (dlc, manifestData) ->
                        try {
                            Timber.tag("Epic").i("Downloading DLC ${index + 1}/${dlcManifestData.size}: ${dlc.title}")
                            downloadInfo.updateStatusMessage("Downloading DLC: ${dlc.title} (${index + 1}/${dlcManifestData.size})")

                            // DLC install path should be subdirectory of base game
                            val dlcInstallPath = "$installPath/${dlc.appName}"

                            // Download the DLC using already-fetched manifest
                            val dlcResult = downloadGameWithManifest(
                                context = context,
                                game = dlc,
                                manifestData = manifestData,
                                installPath = dlcInstallPath,
                                downloadInfo = downloadInfo,
                            )

                            if (dlcResult.isFailure) {
                                Timber.tag("Epic").w("Failed to download DLC ${dlc.title}: ${dlcResult.exceptionOrNull()?.message}")
                                // Continue with other DLCs even if one fails
                            } else {
                                Timber.tag("Epic").i("Successfully downloaded DLC: ${dlc.title}")
                            }
                        } catch (e: Exception) {
                            Timber.tag("Epic").e(e, "Error downloading DLC ${dlc.title}")
                            // Continue with other DLCs
                        }
                    }

                    downloadInfo.updateStatusMessage("DLC downloads complete")
                    Timber.tag("Epic").i("Finished downloading DLCs for ${game.title}")
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error downloading DLCs")
                    // Don't fail the base game download if DLC fails
                }
            }
            downloadInfo.updateStatusMessage("Complete")
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange() // Force final progress update

            // Notify UI that installation status changed
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId),
            )

            Timber.tag("Epic").i("Download completed successfully for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Download failed: ${e.message}")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            Result.failure(e)
        } finally {
            // Always emit download stopped event
            val gameId = game.id ?: 0
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false),
            )
        }
    }

    /**
     * Download game using an already-fetched manifest (used for DLCs)
     */
    private suspend fun downloadGameWithManifest(
        context: Context,
        game: EpicGame,
        manifestData: EpicManager.ManifestResult,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("Starting download for ${game.title} using pre-fetched manifest")

            // Parse manifest
            val cdnUrls = manifestData.cdnUrls.filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            val chunkDataList = manifest.chunkDataList
                ?: return@withContext Result.failure(Exception("No chunk data in manifest"))
            val fileManifestList = manifest.fileManifestList
                ?: return@withContext Result.failure(Exception("No file manifest in manifest"))

            val chunks = chunkDataList.elements
            val files = fileManifestList.elements
            val chunkDir = manifest.getChunkDir()

            // Download chunks
            val chunkCacheDir = File(installPath, ".chunks")
            chunkCacheDir.mkdirs()

            var downloadedChunks = 0
            val totalChunks = chunks.size

            chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                if (!downloadInfo.isActive()) {
                    Timber.tag("Epic").w("Download cancelled by user")
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                val results = chunkBatch.map { chunk ->
                    async {
                        downloadChunkWithRetry(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo)
                    }
                }.awaitAll()

                results.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                    )
                }

                downloadedChunks += chunkBatch.size
            }

            // Assemble files
            val installDir = File(installPath)
            installDir.mkdirs()

            files.chunked(4).forEach { fileBatch ->
                val assembleResults = fileBatch.map { fileManifest ->
                    async {
                        assembleFile(fileManifest, chunkCacheDir, installDir)
                    }
                }.awaitAll()

                assembleResults.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to assemble file"),
                    )
                }
            }

            // Cleanup
            chunkCacheDir.deleteRecursively()

            // Update database
            try {
                epicManager.updateGame(game.copy(isInstalled = true, installPath = installPath))
                Timber.tag("Epic").i("Updated database: DLC ${game.title} marked as installed")
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to update database for DLC ${game.id}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "DLC download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Download a single chunk with retry logic
     */
    private suspend fun downloadChunkWithRetry(
        chunk: app.gamenative.service.epic.manifest.ChunkInfo,
        chunkCacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        downloadInfo: DownloadInfo,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_CHUNK_RETRIES) { attempt ->
            val result = downloadChunk(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo)

            if (result.isSuccess) {
                if (attempt > 0) {
                    Timber.tag("Epic").i("Chunk ${chunk.guidStr} downloaded successfully after ${attempt + 1} attempts")
                }
                return@withContext result
            }

            lastException = result.exceptionOrNull() as? Exception

            if (attempt < MAX_CHUNK_RETRIES - 1) {
                val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Epic").w("Chunk ${chunk.guidStr} download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms...")
                kotlinx.coroutines.delay(delay)
            }
        }

        Timber.tag("Epic").e(lastException, "Failed to download chunk ${chunk.guidStr} after $MAX_CHUNK_RETRIES attempts")
        Result.failure(lastException ?: Exception("Failed to download chunk ${chunk.guidStr}"))
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
                // Quick verification - only verify if size matches
                if (verifyChunkHashBytes(decompressedFile.readBytes(), chunk.shaHash)) {
                    Timber.tag("Epic").d("Chunk ${chunk.guidStr} already exists and verified, skipping")
                    downloadInfo.updateBytesDownloaded(chunk.fileSize)
                    return@withContext Result.success(decompressedFile)
                } else {
                    Timber.tag("Epic").w("Chunk ${chunk.guidStr} exists but failed verification, re-downloading")
                    decompressedFile.delete()
                }
            }

            // Get chunk path for downloading
            val chunkPath = chunk.getPath(chunkDir)

            // Try each CDN base URL until one succeeds
            var lastException: Exception? = null
            for ((cdnIndex, cdnUrl) in cdnUrls.withIndex()) {
                try {
                    // Build full URL: baseUrl + cloudDir + chunkPath
                    val url = "${cdnUrl.baseUrl}${cdnUrl.cloudDir}/$chunkPath"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                        .build()

                    // Use .use {} to ensure response is always closed, even on exception
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastException = Exception("HTTP ${response.code} downloading chunk from ${cdnUrl.baseUrl}")
                            return@use // Exit use block, response will be closed automatically
                        }

                        // Download Epic chunk file (contains header + potentially compressed data)
                        // Use body()!! since we know it exists for successful responses
                        val chunkBytes = response.body!!.bytes()
                        if (chunkBytes.isEmpty()) {
                            throw Exception("Empty response body")
                        }

                        downloadInfo.updateBytesDownloaded(chunkBytes.size.toLong())

                        // Parse Epic Chunk format and decompress if needed
                        val decompressedData = readEpicChunk(chunkBytes)

                        // Verify size matches expected
                        if (decompressedData.size.toLong() != chunk.windowSize.toLong()) {
                            throw Exception("Decompressed size mismatch: expected ${chunk.windowSize}, got ${decompressedData.size}")
                        }

                        // Defer hash verification to separate coroutine to not block download
                        // Write file first for faster I/O pipelining
                        decompressedFile.outputStream().use { it.write(decompressedData) }

                        // Now verify hash
                        if (!verifyChunkHashBytes(decompressedData, chunk.shaHash)) {
                            decompressedFile.delete()
                            throw Exception("Chunk hash verification failed for ${chunk.guid}")
                        }

                        return@withContext Result.success(decompressedFile)
                    }

                    // If we get here, response was unsuccessful, try next CDN
                    if (lastException != null) {
                        continue
                    }
                } catch (e: Exception) {
                    if (cdnIndex < cdnUrls.size - 1) {
                        Timber.tag("Epic").w(e, "Failed to download from ${cdnUrl.baseUrl}, trying next...")
                    }
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
        //! Note: This may require adjustments if we see chunks bigger than 2GB - Unlikely but worth Observing
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

                        val buffer = ByteArray(65536) // Increased to 64KB for better I/O performance
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
