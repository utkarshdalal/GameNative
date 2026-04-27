package app.gamenative.service.epic

import android.content.Context
import android.util.Log
import app.gamenative.PrefManager
import app.gamenative.data.DownloadInfo
import app.gamenative.enums.Marker
import app.gamenative.utils.CdnRankingUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.data.EpicGame
import app.gamenative.service.StreamingAssembly
import app.gamenative.service.epic.manifest.EpicManifest
import app.gamenative.service.epic.manifest.ManifestUtils
import app.gamenative.utils.Net
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * EpicDownloadManager handles downloading Epic games
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
    companion object {
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer for decompression
        private const val MAX_CHUNK_RETRIES = 3 // Maximum retries per chunk
        private const val RETRY_DELAY_MS = 1000L // Initial retry delay in milliseconds
        private const val STREAM_PROGRESS_TIME_INTERVAL_MS = 200L
    }

    /**
     * Download and install an Epic game
     *
     * @param context Android context
     * @param game Epic game to download
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @param containerLanguage Container language (e.g. "english", "german"). Same as GOG/Steam; used to select install tags so the correct language files are downloaded.
     * @param dlcIds Optional DLC game IDs to include
     * @param commonRedistDir Optional directory for common redistributables
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        context: Context,
        game: EpicGame,
        installPath: String,
        downloadInfo: DownloadInfo,
        containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
        dlcIds: List<Int>,
        commonRedistDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {

            Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

            File(installPath).mkdirs()
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            // Emit download started event so UI can attach progress listeners
            val gameId = game.id
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, true),
            )

            // Check for DLCs early to calculate total download size
            val dlcsToDownload = if (dlcIds.size > 0) {
                try {
                    Timber.tag("Epic").d("User has opted to download ${dlcIds.size} DLC titles for game: ${game.title}")
                    val dlcs = epicManager.getGamesById(dlcIds)
                    if (dlcs.isNotEmpty()) {
                        Timber.tag("Epic").d("Found ${dlcs.size} DLC(s) for ${game.title}")
                    }
                    dlcs.filter { dlcIds.contains(it.id) }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error checking for DLCs, continuing without")
                    emptyList()
                }
            } else {
                emptyList()
            }

            Timber.tag("Epic").i("Filtered to ${dlcsToDownload.size} DLC(s) for ${game.title}")

            // Fetch manifest binary and CDN URLs from Epic
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
            val preferredCdnUrls = manifestData.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestData.cdnUrls },
            )

            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs, parsing...")

            // Parse manifest binary to get chunks and files
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            // Use container language (same as GOG) to select install tags: required + optional language files.
            val selectedTags = EpicConstants.containerLanguageToEpicInstallTags(containerLanguage)
            val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, selectedTags)
            val chunks = ManifestUtils.getRequiredChunksForFileList(manifest, files)

            if (selectedTags.isNotEmpty()) {
                Timber.tag("Epic").i("Container language '$containerLanguage' -> install tags: ${selectedTags.joinToString()}")
            }

            val chunkDir = manifest.getChunkDir()

            // chunks can be empty when every file is zero-chunk (e.g. empty
            // config stubs); files.isEmpty() is the real error condition
            if (files.isEmpty()) {
                val msg = if (selectedTags.isNotEmpty()) {
                    "No files found for the selected language. This game may not support this language."
                } else {
                    "No file manifest in manifest"
                }
                return@withContext Result.failure(Exception(msg))
            }

            // Calculate total download size including DLCs
            var totalDownloadSize = chunks.sumOf { it.fileSize }
            var totalInstalledSize = chunks.sumOf { it.windowSize.toLong() }
            val baseGameSize = totalDownloadSize

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
                            val dlcDownloadSize = dlcParsed.chunkDataList?.elements?.sumOf { it.fileSize } ?: 0L
                            val dlcInstalledSize = dlcParsed.chunkDataList?.elements?.sumOf { it.windowSize.toLong() } ?: 0L
                            totalDownloadSize += dlcDownloadSize
                            totalInstalledSize += dlcInstalledSize
                            dlcManifestData.add(dlc to dlcManifest)
                            Timber.tag("Epic").i("DLC ${dlc.title} size: ${dlcDownloadSize / 1_000_000} MB")
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
                |  Base game download size: ${baseGameSize / 1_000_000_000.0} GB
                |  Base game installed size: ${totalInstalledSize / 1_000_000_000.0} GB
                |  DLCs: ${dlcManifestData.size}
                |  Total download size (including DLCs): ${totalDownloadSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
                |  ChunkDir: $chunkDir
                """.trimMargin(),
            )

            downloadInfo.setTotalExpectedBytes(totalDownloadSize)
            downloadInfo.updateStatusMessage("Downloading base game...")

            // Download chunks in parallel
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

            // Build file-ordered chunk queue and run streaming download + assembly
            val fileChunkIds = files.map { f -> f.chunkParts.map { it.guidStr } }
            val chunkQueue = buildFileOrderedChunkQueue(manifest, fileChunkIds)
            val chunkLastFile = StreamingAssembly.buildChunkLastFileMap(fileChunkIds)
            val installDir = File(installPath)
            installDir.mkdirs()

            val downloadResult = downloadAndAssembleEpicChunks(
                chunkQueue, files, chunkLastFile, chunkCacheDir, chunkDir, cdnUrls, installDir, downloadInfo,
            )
            if (downloadResult.isFailure) {
                return@withContext downloadResult
            }

            chunkCacheDir.deleteRecursively()

            // Log final directory structure
            Timber.tag("Epic").i("Download completed successfully for ${game.title}")
            logDirectoryStructure(installDir)

            // Download DLCs using pre-fetched manifest data
            if (dlcManifestData.isNotEmpty()) {
                try {
                    Timber.tag("Epic").i("Downloading ${dlcManifestData.size} DLC(s) for ${game.title}")

                    dlcManifestData.forEachIndexed { index, (dlc, manifestData) ->
                        try {
                            Timber.tag("Epic").i("Downloading DLC ${index + 1}/${dlcManifestData.size}: ${dlc.title}")
                            downloadInfo.updateStatusMessage("Downloading DLC: ${dlc.title} (${index + 1}/${dlcManifestData.size})")

                            // Download the DLC using already-fetched manifest
                            val dlcResult = downloadGameWithManifest(
                                context = context,
                                game = dlc,
                                manifestData = manifestData,
                                installPath = installPath,
                                downloadInfo = downloadInfo,
                            )

                            if (dlcResult.isFailure) {
                                if (!downloadInfo.isActive()) {
                                    return@withContext dlcResult
                                }
                                Timber.tag("Epic").w("Failed to download DLC ${dlc.title}: ${dlcResult.exceptionOrNull()?.message}")
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
            // Update database with install info
            try {
                val updatedGame = game.copy(
                    isInstalled = true,
                    installPath = installPath,
                    installSize = totalInstalledSize,
                )
                epicManager.updateGame(updatedGame)
                Timber.tag("Epic").i("Updated database: game marked as installed")
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to update database for game ${game.id}")
                // Don't fail the entire download for DB issues
            }

            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            // Clean up and update UI
            downloadInfo.updateStatusMessage("Complete")
            // Ensure bytes-based progress shows 100% completion
            downloadInfo.updateBytesDownloaded(downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded())
            downloadInfo.clearPersistedBytesDownloaded(installPath)
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            // Notify UI that installation status changed
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId),
            )

            Timber.tag("Epic").i("Download completed successfully for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Download failed: ${e.message}")
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
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
            val preferredCdnUrls = manifestData.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestData.cdnUrls },
            )
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            val chunkDataList = manifest.chunkDataList
                ?: return@withContext Result.failure(Exception("No chunk data in manifest"))
            val fileManifestList = manifest.fileManifestList
                ?: return@withContext Result.failure(Exception("No file manifest in manifest"))

            val files = fileManifestList.elements
            val chunkDir = manifest.getChunkDir()

            val fileChunkIds = files.map { f -> f.chunkParts.map { it.guidStr } }
            val chunkQueue = buildFileOrderedChunkQueue(manifest, fileChunkIds)
            val chunkLastFile = StreamingAssembly.buildChunkLastFileMap(fileChunkIds)

            val chunkCacheDir = File(installPath, ".chunks")
            chunkCacheDir.mkdirs()
            val installDir = File(installPath)
            installDir.mkdirs()

            val dlcDownloadResult = downloadAndAssembleEpicChunks(
                chunkQueue, files, chunkLastFile, chunkCacheDir, chunkDir, cdnUrls, installDir, downloadInfo,
            )
            if (dlcDownloadResult.isFailure) return@withContext dlcDownloadResult

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
     * Download the Epic Online Services overlay 
     */
    suspend fun downloadOverlay(
        manifestResult: EpicManager.ManifestResult,
        installPath: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // ! Avoiding Cloudflare as it causes issues with some downloads and is inconsistent.
            // Matches the degradation behavior of downloadGame / downloadGameWithManifest:
            // if Cloudflare is the only CDN offered, fall back to it rather than hard-failing.
            val preferredCdnUrls = manifestResult.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestResult.cdnUrls },
            )
            if (cdnUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No usable CDN URLs in manifest"))
            }

            val manifest = EpicManifest.readAll(manifestResult.manifestBytes)
            val chunks = manifest.chunkDataList?.elements
                ?: return@withContext Result.failure(Exception("Manifest contains no chunk data"))
            val files = manifest.fileManifestList?.elements
                ?: return@withContext Result.failure(Exception("Manifest contains no file list"))
            val chunkDir = manifest.getChunkDir()

            val installDir = File(installPath).also { it.mkdirs() }
            val chunkCacheDir = File(installDir, ".chunks").also { it.mkdirs() }

            // Dummy DownloadInfo – overlay downloads are small and need no UI progress events
            val dummyDownloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = -1,
                downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList(),
            )

            val parallelDownloads = PrefManager.downloadSpeed.coerceAtLeast(1)
            val downloadHttpClient = Net.httpForParallelDownloads(parallelDownloads)

            var downloadedChunks = 0
            val totalChunks = chunks.size

            chunks.chunked(parallelDownloads).forEach { batch ->
                val results = batch.map { chunk ->
                    async {
                        downloadChunkWithRetry(chunk, chunkCacheDir, chunkDir, cdnUrls, dummyDownloadInfo, downloadHttpClient)
                    }
                }.awaitAll()

                results.firstOrNull { it.isFailure }?.let { failure ->
                    chunkCacheDir.deleteRecursively()
                    return@withContext Result.failure(
                        failure.exceptionOrNull() ?: Exception("Chunk download failed"),
                    )
                }

                downloadedChunks += batch.size
                onProgress?.invoke(downloadedChunks, totalChunks)
            }

            files.chunked(4).forEach { batch ->
                val results = batch.map { fileManifest ->
                    async { assembleFile(fileManifest, chunkCacheDir, installDir) }
                }.awaitAll()

                results.firstOrNull { it.isFailure }?.let { failure ->
                    chunkCacheDir.deleteRecursively()
                    return@withContext Result.failure(
                        failure.exceptionOrNull() ?: Exception("File assembly failed"),
                    )
                }
            }

            chunkCacheDir.deleteRecursively()
            Timber.tag("Epic").i("downloadOverlay completed: $installPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "downloadOverlay failed for $installPath")
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
        downloadHttpClient: okhttp3.OkHttpClient,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_CHUNK_RETRIES) { attempt ->
            val result = downloadChunk(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo, downloadHttpClient)

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

    private suspend fun rankCdnUrlsByProbe(cdnUrls: List<EpicManager.CdnUrl>): List<EpicManager.CdnUrl> {
        if (cdnUrls.size <= 1) return cdnUrls

        val rankedBaseUrls = CdnRankingUtils.rankBaseUrlsByHeadProbe(
            cdnUrls.map { it.baseUrl },
            Net.http,
            "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit",
        )
        val rankIndex = rankedBaseUrls.withIndex().associate { it.value to it.index }

        return cdnUrls.sortedBy { rankIndex[it.baseUrl] ?: Int.MAX_VALUE }
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
        downloadHttpClient: okhttp3.OkHttpClient,
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
                    downloadHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastException = Exception("HTTP ${response.code} downloading chunk from ${cdnUrl.baseUrl}")
                            return@use // Exit use block, response will be closed automatically
                        }

                        // Download and decompress Epic chunk file using streaming to avoid OOM exceptions
                        val responseBody = response.body!!
                        responseBody.byteStream().use { input ->
                            // Stream directly from network into chunk parser/decompressor.
                            // This removes an extra temp compressed-file write/read cycle.
                            decompressStreamingChunkToFile(input, decompressedFile, chunk.windowSize.toLong(), chunk.shaHash, downloadInfo)
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

        // Skip SHA hash (20 bytes), hash type (1 byte)
        buffer.position(buffer.position() + 21)

        // Read uncompressed size (4 bytes)
        val uncompressedSize = buffer.int

        // Read chunk data starting from header end
        val dataStart = headerSize
        //! Note: This may require adjustments if we see chunks bigger than 2GB - Unlikely but worth Observing
        val dataBytes = chunkBytes.copyOfRange(dataStart, dataStart + compressedSize)

        return if (isCompressed) {
            // Decompress using zlib
            val inflater = Inflater()
            try {
                inflater.setInput(dataBytes)
                val result = ByteArray(uncompressedSize)
                val resultLength = inflater.inflate(result)
                if (resultLength != uncompressedSize) {
                    throw IllegalStateException("Decompressed chunk size mismatch: expected $uncompressedSize, got $resultLength")
                }
                result
            } finally {
                inflater.end()
            }
        } else {
            // Already uncompressed
            dataBytes
        }
    }

    /**
     * Decompress an Epic chunk file directly to output file with streaming hash verification
     * This avoids allocating huge ByteArrays (1.5GB) in memory
     */
    private fun decompressStreamingChunkToFile(
        inputStream: InputStream,
        outputFile: File,
        expectedSize: Long,
        expectedHash: ByteArray,
        downloadInfo: DownloadInfo,
    ) {
        val digest = MessageDigest.getInstance("SHA-1")
        var totalBytesWritten = 0L
        var lastProgressEmitAt = System.currentTimeMillis()

        inputStream.buffered().use { input ->
            // Read the entire header - determine size dynamically
            val headerStart = ByteArray(12)
            if (input.read(headerStart) != 12) {
                throw Exception("Failed to read chunk header start")
            }

            val startBuffer = ByteBuffer.wrap(headerStart).order(ByteOrder.LITTLE_ENDIAN)
            val magic = startBuffer.int
            if (magic != 0xB1FE3AA2.toInt()) {
                throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
            }

            val headerVersion = startBuffer.int
            val headerSize = startBuffer.int

            // Epic chunks can have different header sizes (62 or 66 bytes)
            // Minimum viable header is 62 bytes
            if (headerSize < 62 || headerSize > 66) {
                throw Exception("Invalid header size: $headerSize (expected 62-66 bytes)")
            }

            // Read the remaining header bytes
            val remainingSize = headerSize - 12
            val remainingBytes = ByteArray(remainingSize)
            if (input.read(remainingBytes) != remainingSize) {
                throw Exception("Failed to read remaining header: expected $remainingSize bytes")
            }

            // Parse the header fields from the remaining bytes sequentially
            // This matches the format in legendary/models/chunk.py
            val buffer = ByteBuffer.wrap(remainingBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Chunk header format (after magic/version/headerSize):
            // compressedSize: 4 bytes (file offset 12-15)
            // GUID: 16 bytes (file offset 16-31)
            // hash: 8 bytes (file offset 32-39)
            // storedAs: 1 byte (file offset 40)
            // SHA hash: 20 bytes (file offset 41-60)
            // For 66-byte headers:
            //   hash type: 1 byte (file offset 61)
            //   uncompressedSize: 4 bytes (file offset 62-65)
            // For 62-byte headers (older format):
            //   uncompressedSize: 4 bytes (file offset 58-61) - replaces hash type + padding

            // Ensure we have minimum required fields (up to SHA hash)
            if (buffer.remaining() < 50) {
                throw Exception("Buffer underflow: only ${buffer.remaining()} bytes available, need at least 50")
            }

            val compressedSize = buffer.int  // Read compressed size
            buffer.position(buffer.position() + 16)  // Skip GUID (16 bytes)
            buffer.position(buffer.position() + 8)   // Skip hash (8 bytes)
            val storedAs = buffer.get().toInt() and 0xFF  // Read storedAs flag
            val isCompressed = (storedAs and 0x1) == 0x1

            // Version Check to understand header spacing
            // Header version 2: includes sha_hash (20 bytes) + hash_type (1 byte) = 62 total bytes
            // Header version 3: adds uncompressed_size (4 bytes) = 66 total bytes
            if (headerVersion >= 2) {
                buffer.position(buffer.position() + 20)  // Skip SHA hash (20 bytes)
                if (buffer.remaining() >= 1) {
                    buffer.position(buffer.position() + 1)   // Skip hash type (1 byte)
                }
            }

            val uncompressedSize = if (headerVersion >= 3 && buffer.remaining() >= 4) {
                // Version 3+: uncompressedSize field is present (4 bytes)
                buffer.int
            } else {
                // Version 2 or no uncompressedSize field: use expectedSize parameter
                Timber.tag("Epic").d("Header version $headerVersion doesn't include uncompressedSize field, using expectedSize=$expectedSize")
                expectedSize.toInt()
            }

            Timber.tag("Epic").d("Chunk header: magic=0x${magic.toString(16)}, headerVersion=$headerVersion, headerSize=$headerSize, compressedSize=$compressedSize, uncompressedSize=$uncompressedSize, storedAs=0x${storedAs.toString(16)}, isCompressed=$isCompressed, expectedSize=$expectedSize")

            outputFile.outputStream().buffered().use { output ->
                if (isCompressed) {
                    // Streaming decompression
                    val inflater = Inflater()
                    try {
                        val inputBuffer = ByteArray(65536) // 64KB compressed read buffer
                        val outputBuffer = ByteArray(65536) // 64KB decompressed write buffer
                        var endOfStream = false
                        var firstRead = true

                        while (totalBytesWritten < uncompressedSize && !endOfStream) {
                            // Feed more input if needed
                            if (inflater.needsInput() && !endOfStream) {
                                val bytesRead = input.read(inputBuffer)
                                if (bytesRead == -1) {
                                    endOfStream = true
                                    Timber.tag("Epic").d("Unexpected end of stream: read=$totalBytesWritten, expected=$uncompressedSize")
                                } else {
                                    downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressEmitAt >= STREAM_PROGRESS_TIME_INTERVAL_MS) {
                                        downloadInfo.emitProgressChange()
                                        lastProgressEmitAt = now
                                    }
                                    if (firstRead) {
                                        Log.d("Epic", "First compressed data bytes: ${inputBuffer.take(16).joinToString(" ") { "%02x".format(it) }}")
                                        firstRead = false
                                    }
                                    inflater.setInput(inputBuffer, 0, bytesRead)
                                }
                            }

                            // Try to decompress
                            try {
                                val decompressed = inflater.inflate(outputBuffer)
                                if (decompressed > 0) {
                                    output.write(outputBuffer, 0, decompressed)
                                    digest.update(outputBuffer, 0, decompressed)
                                    totalBytesWritten += decompressed
                                } else if (inflater.finished() || endOfStream) {
                                    // No more data available
                                    break
                                }
                            } catch (e: java.util.zip.DataFormatException) {
                                Timber.tag("Epic").d("DataFormatException during inflate: ${e.message}")
                                Timber.tag("Epic").d("  totalBytesWritten=$totalBytesWritten, expectedSize=$uncompressedSize")
                                Timber.tag("Epic").d("  inflater: finished=${inflater.finished()}, needsInput=${inflater.needsInput()}")
                                throw Exception("Failed to decompress chunk: ${e.message}", e)
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                } else {
                    // Already uncompressed - stream directly
                    val buffer = ByteArray(65536)
                    var remaining = compressedSize
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size)
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitAt >= STREAM_PROGRESS_TIME_INTERVAL_MS) {
                            downloadInfo.emitProgressChange()
                            lastProgressEmitAt = now
                        }
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                        remaining -= bytesRead
                    }
                }
            }
        }

        // Verify size
        if (totalBytesWritten != expectedSize) {
            Timber.tag("Epic").d("Size mismatch: expected=$expectedSize, actual=$totalBytesWritten, diff=${expectedSize - totalBytesWritten}")
            outputFile.delete()
            throw Exception("Decompressed size mismatch: expected $expectedSize, got $totalBytesWritten")
        }

        // Verify hash
        val actualHash = digest.digest()
        if (!actualHash.contentEquals(expectedHash)) {
            val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
            val actualHex = actualHash.joinToString("") { "%02x".format(it) }
            outputFile.delete()
            throw Exception("Chunk hash verification failed: expected $expectedHex, got $actualHex")
        }

        // Ensure UI receives a final progress update after this chunk's bytes.
        downloadInfo.emitProgressChange()
    }

    /**
     * Read and decompress an Epic Chunk file from disk using streaming to avoid OOM
     * This version reads from a file input stream and decompresses in chunks
     */
    private fun readEpicChunkFromFile(chunkFile: File, expectedSize: Long): ByteArray {
        chunkFile.inputStream().buffered().use { input ->
            // Read header (66 bytes)
            val headerBytes = ByteArray(66)
            val headerRead = input.read(headerBytes)
            if (headerRead != 66) {
                throw Exception("Failed to read chunk header")
            }

            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Parse header
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

            // Skip SHA hash (20 bytes), hash type (1 byte)
            buffer.position(buffer.position() + 21)

            // Read uncompressed size (4 bytes)
            val uncompressedSize = buffer.int

            // Skip to data start if header is larger than 66 bytes
            if (headerSize > 66) {
                input.skip((headerSize - 66).toLong())
            }

            return if (isCompressed) {
                // Decompress using streaming to avoid loading entire compressed data into memory
                val inflater = Inflater()
                try {
                    val result = ByteArray(uncompressedSize)
                    var resultOffset = 0
                    val inputBuffer = ByteArray(65536) // 64KB buffer for reading compressed data

                    while (resultOffset < uncompressedSize) {
                        if (inflater.needsInput()) {
                            val bytesRead = input.read(inputBuffer)
                            if (bytesRead == -1) break
                            inflater.setInput(inputBuffer, 0, bytesRead)
                        }

                        val decompressed = inflater.inflate(result, resultOffset, uncompressedSize - resultOffset)
                        resultOffset += decompressed

                        if (inflater.finished()) break
                    }

                    if (resultOffset != uncompressedSize) {
                        throw IllegalStateException("Decompressed chunk size mismatch: expected $uncompressedSize, got $resultOffset")
                    }
                    result
                } finally {
                    inflater.end()
                }
            } else {
                // Already uncompressed - read directly
                val result = ByteArray(compressedSize)
                var totalRead = 0
                while (totalRead < compressedSize) {
                    val bytesRead = input.read(result, totalRead, compressedSize - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }
                result
            }
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

    private fun buildFileOrderedChunkQueue(
        manifest: EpicManifest,
        fileChunkIds: List<List<String>>,
    ): List<app.gamenative.service.epic.manifest.ChunkInfo> {
        val orderedIds = StreamingAssembly.buildOrderedChunkQueue(fileChunkIds)
        return orderedIds.map { id ->
            manifest.chunkDataList?.getChunkByGuid(id)
                ?: throw IllegalStateException("Chunk $id referenced by file but not found in manifest")
        }
    }

    // assembles files as chunks arrive, deletes chunks once their last consumer is assembled
    private suspend fun downloadAndAssembleEpicChunks(
        chunkQueue: List<app.gamenative.service.epic.manifest.ChunkInfo>,
        files: List<app.gamenative.service.epic.manifest.FileManifest>,
        chunkLastFile: Map<String, Int>,
        chunkCacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val totalChunks = chunkQueue.size
        val totalFiles = files.size
        val parallelDownloads = PrefManager.downloadSpeed.coerceAtLeast(1)
        val downloadHttpClient = Net.httpForParallelDownloads(parallelDownloads)
        val downloadedChunkIds = mutableSetOf<String>()
        var nextFileToAssemble = 0

        downloadInfo.setProgress(0.0f)

        // Assemble every file whose chunks are all present, as soon as it becomes ready.
        suspend fun assembleReady(): Result<Unit> {
            while (nextFileToAssemble < totalFiles) {
                val file = files[nextFileToAssemble]
                if (!file.chunkParts.all { it.guidStr in downloadedChunkIds }) break
                if (!downloadInfo.isActive()) return Result.failure(Exception("Download cancelled"))

                val assembleResult = assembleFile(file, chunkCacheDir, installDir)
                if (assembleResult.isFailure) {
                    return Result.failure(
                        assembleResult.exceptionOrNull() ?: Exception("Failed to assemble file"),
                    )
                }

                for (part in file.chunkParts) {
                    if (chunkLastFile[part.guidStr] == nextFileToAssemble) {
                        File(chunkCacheDir, part.guidStr).delete()
                    }
                }

                nextFileToAssemble++
            }
            return Result.success(Unit)
        }

        for (chunkBatch in chunkQueue.chunked(parallelDownloads)) {
            if (!downloadInfo.isActive()) {
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            // Stream chunk completions within each batch to avoid jumpy progress updates.
            val completionChannel = Channel<Pair<String, Result<File>>>(chunkBatch.size)
            val resultsByChunk = mutableMapOf<String, Result<File>>()
            var assemblyFailure: Throwable? = null

            coroutineScope {
                chunkBatch.forEach { chunk ->
                    launch {
                        val result = downloadChunkWithRetry(
                            chunk,
                            chunkCacheDir,
                            chunkDir,
                            cdnUrls,
                            downloadInfo,
                            downloadHttpClient,
                        )
                        completionChannel.send(chunk.guidStr to result)
                    }
                }

                repeat(chunkBatch.size) {
                    val (chunkGuid, result) = completionChannel.receive()
                    resultsByChunk[chunkGuid] = result

                    if (result.isSuccess && assemblyFailure == null) {
                        downloadedChunkIds.add(chunkGuid)
                        val assembleResult = assembleReady()
                        if (assembleResult.isFailure) {
                            assemblyFailure = assembleResult.exceptionOrNull() ?: Exception("Failed to assemble ready files")
                            return@repeat
                        }

                        val progress = downloadedChunkIds.size.toFloat() / totalChunks
                        downloadInfo.setProgress(progress)
                        downloadInfo.updateStatusMessage(
                            "Downloading (${downloadedChunkIds.size}/$totalChunks chunks, $nextFileToAssemble/$totalFiles files)",
                        )
                    }
                }
            }
            completionChannel.close()

            if (assemblyFailure != null) {
                return@withContext Result.failure(assemblyFailure!!)
            }

            val failedResult = chunkBatch
                .map { chunk ->
                    resultsByChunk[chunk.guidStr] ?: Result.failure(Exception("Missing batch result for chunk ${chunk.guidStr}"))
                }
                .firstOrNull { it.isFailure }
            if (failedResult != null) {
                return@withContext Result.failure(
                    failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                )
            }

            Timber.tag("Epic").d("Progress: ${downloadedChunkIds.size}/$totalChunks chunks, $nextFileToAssemble/$totalFiles files assembled")
        }

        // final assembly pass for zero-chunk files that the chunk loop never reaches
        while (nextFileToAssemble < totalFiles) {
            val file = files[nextFileToAssemble]
            if (!file.chunkParts.all { it.guidStr in downloadedChunkIds }) break

            val assembleResult = assembleFile(file, chunkCacheDir, installDir)
            if (assembleResult.isFailure) {
                return@withContext Result.failure(
                    assembleResult.exceptionOrNull() ?: Exception("Failed to assemble file"),
                )
            }
            nextFileToAssemble++
        }

        if (nextFileToAssemble != totalFiles) {
            return@withContext Result.failure(
                Exception("Assembly incomplete: only $nextFileToAssemble of $totalFiles files assembled")
            )
        }

        Timber.tag("Epic").i("Streaming complete: $totalChunks chunks, $nextFileToAssemble files assembled")
        Result.success(Unit)
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
