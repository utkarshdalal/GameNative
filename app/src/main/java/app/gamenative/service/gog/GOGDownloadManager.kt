package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.service.gog.api.DepotFile
import app.gamenative.service.gog.api.FileChunk
import app.gamenative.service.gog.api.GOGApiClient
import app.gamenative.service.gog.api.GOGManifestParser
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/**
 * GOGDownloadManager handles downloading GOG games
 *
 * GOG's CDN structure (Gen 2):
 * 1. Fetch build manifest (contains depots and product metadata)
 * 2. Fetch depot manifests (contains file lists with chunks)
 * 3. Get secure CDN links (time-limited URLs for chunks) -> We have issues here
 * 4. Download chunks from CDN (zlib compressed data) -> We have issues here
 * 5. Decompress and verify chunks (MD5)
 * 6. Assemble files from chunks
 *
 * GOG Chunk Format (Gen 2):
 * - Chunks are identified by compressedMd5 hash
 * - Downloaded from secure CDN URLs (time-limited)
 * - Compressed using zlib
 * - Verified using MD5 hash after decompression
 * - Multiple chunks assemble into single files
 */
@Singleton
class GOGDownloadManager @Inject constructor(
    private val apiClient: GOGApiClient,
    private val parser: GOGManifestParser,
    private val gogManager: GOGManager,
    @ApplicationContext private val context: Context,
) {

    private val httpClient = Net.http

    /**
     * Context needed to refresh secure CDN links when they expire
     */
    private data class SecureLinkContext(
        val gameId: String,
        val generation: Int,
        val productIds: Set<String>,
        val chunkToProductMap: Map<String, String>,
    )

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 4
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer
        private const val MAX_CHUNK_RETRIES = 3 // Maximum retries per chunk
        private const val RETRY_DELAY_MS = 1000L // Initial retry delay in milliseconds
    }

    /**
     * Download and install a GOG game
     *
     * @param gameId GOG game ID (numeric)
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @param language Target language (e.g., "en-US")
     * @param withDlcs Whether to include DLC content
     * @param supportDir Optional directory for support files (redistributables)
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        gameId: String,
        installPath: File,
        downloadInfo: DownloadInfo,
        language: String = "en-US",
        withDlcs: Boolean = false,
        supportDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // TODO: We need to handle COMMON REDIST folder and go through the same thing.
            Timber.tag("GOG").i("Starting download for game $gameId to ${installPath.absolutePath}")
            if (supportDir != null) {
                Timber.tag("GOG").i("Starting download for game $gameId to ${supportDir.absolutePath}")
            }

            // Emit download started event so UI can attach progress listeners
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true),
            )

            downloadInfo.updateStatusMessage("Fetching builds...")

            // Step 1: Get available builds
            val buildsResult = apiClient.getBuilds(gameId, "windows")
            if (buildsResult.isFailure) {
                return@withContext Result.failure(
                    buildsResult.exceptionOrNull() ?: Exception("Failed to fetch builds"),
                )
            }

            val builds = buildsResult.getOrThrow()
            val selectedBuild = parser.selectBuild(builds.items, platform = "windows")
                ?: return@withContext Result.failure(Exception("No suitable build found for Windows"))

            Timber.tag("GOG").i("Selected build: ${selectedBuild.buildId} (Gen ${selectedBuild.generation}, Platform: ${selectedBuild.platform})")

            downloadInfo.updateStatusMessage("Fetching manifest...")

            // Step 2: Fetch main manifest
            val manifestResult = apiClient.fetchManifest(selectedBuild.link)
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val manifest = manifestResult.getOrThrow()
            Timber.tag("GOG").d("Manifest: ${manifest.installDirectory}, ${manifest.depots.size} depot(s)")

            downloadInfo.updateStatusMessage("Filtering depots...")

            // Step 3: Filter depots by language and bitness
            val languageDepots = parser.filterDepotsByLanguage(manifest, language)
            if (languageDepots.isEmpty()) {
                return@withContext Result.failure(Exception("No depots found for language: $language"))
            }

            val bitnessDepots = parser.filterDepotsByBitness(languageDepots, bitness = "64")
            if (bitnessDepots.isEmpty()) {
                return@withContext Result.failure(Exception("No 64-bit depots found for language: $language"))
            }

            // Filter by ownership to exclude unowned DLC depots
            val ownedGameIds = gogManager.getAllGameIds()
            val depots = parser.filterDepotsByOwnership(bitnessDepots, ownedGameIds)
            if (depots.isEmpty()) {
                return@withContext Result.failure(Exception("No owned depots found for language: $language"))
            }

            Timber.tag("GOG").d("Found ${depots.size} owned depot(s) for $language (64-bit)")

            downloadInfo.updateStatusMessage("Fetching depot manifests...")

            // Step 4: Fetch depot manifests to get file lists
            val allFiles = mutableListOf<DepotFile>()
            for ((index, depot) in depots.withIndex()) {
                downloadInfo.updateStatusMessage("Fetching depot ${index + 1}/${depots.size}...")

                val depotResult = apiClient.fetchDepotManifest(depot.manifest)
                if (depotResult.isFailure) {
                    return@withContext Result.failure(
                        depotResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"),
                    )
                }

                allFiles.addAll(depotResult.getOrThrow().files)
            }

            Timber.tag("GOG").d("Total files from all depots: ${allFiles.size}")

            // Step 5: Separate base game, DLC, and support files
            // ! Note: We could actually give back the DLC list and ask which ones they want to download... (DLC Manager).
            val (baseFiles, dlcFiles) = parser.separateBaseDLC(allFiles, manifest.baseProductId)
            val filesToDownload = if (withDlcs) baseFiles + dlcFiles else baseFiles
            val (gameFiles, supportFiles) = parser.separateSupportFiles(filesToDownload)

            // Calculate sizes separately for transparency
            val (baseGameFiles, _) = parser.separateSupportFiles(baseFiles)
            val baseGameSize = parser.calculateTotalSize(baseGameFiles)
            val dlcSize = if (withDlcs && dlcFiles.isNotEmpty()) {
                val (dlcGameFiles, _) = parser.separateSupportFiles(dlcFiles)
                parser.calculateTotalSize(dlcGameFiles)
            } else {
                0L
            }

            Timber.tag("GOG").d(
                """
                |Download plan:
                |  Base game files: ${baseFiles.size}
                |  DLC files: ${dlcFiles.size}
                |  Game files to download: ${gameFiles.size}
                |  Support files: ${supportFiles.size}
                |  Base game size: ${baseGameSize / 1_000_000.0} MB
                |  DLC size: ${dlcSize / 1_000_000.0} MB
                |  Including DLCs: $withDlcs
                """.trimMargin(),
            )

            // Step 6: Calculate sizes and extract chunk hashes
            val totalSize = parser.calculateTotalSize(gameFiles)
            val chunkHashes = parser.extractChunkHashes(gameFiles)

            Timber.tag("GOG").d(
                """
                |Download stats:
                |  Total compressed size: ${totalSize / 1_000_000.0} MB (${if (withDlcs) "including DLC" else "base game only"})
                |  Unique chunks: ${chunkHashes.size}
                |  Files: ${gameFiles.size}
                """.trimMargin(),
            )

            downloadInfo.setTotalExpectedBytes(totalSize)

            // Step 7: Get secure CDN links for chunks
            downloadInfo.updateStatusMessage("Getting secure download links...")

            Timber.tag("GOG").d("User owns ${ownedGameIds.size} products: ${ownedGameIds.joinToString()}")

            // Build mapping of product ID to secure URLs and chunk to product ID
            val productUrlMap = mutableMapOf<String, List<String>>()
            val chunkToProductMap = mutableMapOf<String, String>()

            // Map each chunk to its product ID, but only for products the user owns
            gameFiles.forEach { file ->
                val productId = file.productId ?: gameId // Use base game ID if productId is null

                // Only include files from products the user owns
                if (productId in ownedGameIds) {
                    file.chunks.forEach { chunk ->
                        chunkToProductMap[chunk.compressedMd5] = productId
                    }
                } else {
                    Timber.tag("GOG").d("Skipping file ${file.path} from unowned product $productId")
                }
            }

            // Get unique product IDs we need to fetch secure links for
            val productIds = chunkToProductMap.values.toSet()
            Timber.tag("GOG").d("Need secure links for ${productIds.size} owned product(s): ${productIds.joinToString()}")
            Timber.tag("GOG").d("Mapped ${chunkToProductMap.size} chunks to products")

            // Fetch secure links for each product
            for (productId in productIds) {
                val linksResult = apiClient.getSecureLink(
                    productId = productId,
                    path = "/",
                    generation = selectedBuild.generation,
                )
                if (linksResult.isSuccess) {
                    val urls = linksResult.getOrThrow().urls
                    productUrlMap[productId] = urls
                    Timber.tag("GOG").d("Got ${urls.size} secure URL(s) for product $productId")
                } else {
                    return@withContext Result.failure(
                        linksResult.exceptionOrNull() ?: Exception("Failed to get secure links for product $productId"),
                    )
                }
            }

            // Build chunk URL map using the correct product URL for each chunk
            val chunkUrlMap = parser.buildChunkUrlMapWithProducts(chunkHashes, chunkToProductMap, productUrlMap)

            // Store context for refreshing secure links if they expire
            val secureLinkContext = SecureLinkContext(
                gameId = gameId,
                generation = selectedBuild.generation,
                productIds = productIds,
                chunkToProductMap = chunkToProductMap,
            )

            // Step 8: Download chunks
            downloadInfo.updateStatusMessage("Downloading chunks...")

            val chunkCacheDir = File(installPath, ".gog_chunks")
            chunkCacheDir.mkdirs()

            val downloadResult = downloadChunks(
                chunkUrlMap = chunkUrlMap,
                chunkCacheDir = chunkCacheDir,
                downloadInfo = downloadInfo,
                chunkHashes = chunkHashes,
                secureLinkContext = secureLinkContext,
                chunkToProductMap = chunkToProductMap,
            )
            if (downloadResult.isFailure) {
                return@withContext downloadResult
            }

            // Step 9: Assemble game files
            downloadInfo.updateStatusMessage("Assembling files...")

            // Use installPath directly since it already includes the game-specific folder
            val gameInstallDir = installPath
            gameInstallDir.mkdirs()

            val assembleResult = assembleFiles(gameFiles, chunkCacheDir, gameInstallDir, downloadInfo)
            if (assembleResult.isFailure) {
                return@withContext assembleResult
            }

            // ! Looks like we're not correctly getting the chunks to pull in the dependencies.
            // Step 10: Install support files if directory provided
            if (supportDir != null && supportFiles.isNotEmpty()) {
                // This should be _CommonRedist almost entirely.
                downloadInfo.updateStatusMessage("Installing support files...")
                supportDir.mkdirs()

                val supportResult = assembleFiles(supportFiles, chunkCacheDir, supportDir, downloadInfo)
                if (supportResult.isFailure) {
                    Timber.tag("GOG").w("Failed to install support files: ${supportResult.exceptionOrNull()?.message}")
                    // Continue anyway - support files are optional
                }
            }

            // Step 11: Cleanup
            chunkCacheDir.deleteRecursively()

            // Step 12: Update database with install info
            downloadInfo.updateStatusMessage("Updating database...")
            try {
                val game = gogManager.getGameFromDbById(gameId)
                if (game != null) {
                    // Use installPath directly since it already includes the game-specific folder
                    val installSize = calculateDirectorySize(installPath)
                    val updatedGame = game.copy(
                        isInstalled = true,
                        installPath = installPath.absolutePath,
                        installSize = installSize,
                    )
                    gogManager.updateGame(updatedGame)
                    Timber.tag("GOG").i("Updated database: game marked as installed, size: ${installSize / 1_000_000} MB")
                } else {
                    Timber.tag("GOG").w("Game $gameId not found in database, skipping DB update")
                }
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to update database for game $gameId")
                // Don't fail the entire download for DB issues
            }

            // Step 13: Emit completion event
            downloadInfo.updateStatusMessage("Complete")
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            // Notify UI that installation status changed
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0),
            )

            Timber.tag("GOG").i("Download completed successfully for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Download failed: ${e.message}")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            // Emit download stopped event on failure
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
            )

            Result.failure(e)
        }
    }

    /**
     * Download all chunks from CDN with parallel execution
     *
     * @param chunkUrlMap Map of chunk MD5 hash to secure CDN URL
     * @param chunkCacheDir Directory to cache downloaded chunks
     * @param downloadInfo Progress tracker
     * @param chunkHashes List of all chunk hashes needed
     * @param secureLinkContext Context for refreshing secure links if they expire
     * @param chunkToProductMap Map of chunk MD5 hash to product ID for debugging
     */
    private suspend fun downloadChunks(
        chunkUrlMap: Map<String, String>,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
        chunkHashes: List<String>,
        secureLinkContext: SecureLinkContext,
        chunkToProductMap: Map<String, String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentChunkUrlMap = chunkUrlMap
            val chunks = chunkUrlMap.entries.toList()
            val totalChunks = chunks.size
            var downloadedChunks = 0

            Timber.tag("GOG").d("Downloading $totalChunks chunks...")

            // Initialize download progress
            downloadInfo.setProgress(0.0f)
            downloadInfo.setActive(true)
            downloadInfo.emitProgressChange()

            // Download in batches to avoid overwhelming the system
            chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                if (!downloadInfo.isActive()) {
                    Timber.tag("GOG").w("Download cancelled by user")
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                // Download batch in parallel with retry logic
                val results = chunkBatch.map { (chunkMd5, _) ->
                    async {
                        // Use current URL map in case it was refreshed
                        val url = currentChunkUrlMap[chunkMd5] ?: return@async Result.failure<File>(
                            Exception("No URL found for chunk $chunkMd5"),
                        )
                        Timber.tag("GOG").d("Chunk $chunkMd5 URL: $url")
                        downloadChunkWithRetry(chunkMd5, url, chunkCacheDir, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed due to expired links (401/403/404)
                val expiredLinkFailures = results.zip(chunkBatch).filter { (result, _) ->
                    result.isFailure && result.exceptionOrNull()?.message?.let { msg ->
                        msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 404")
                    } == true
                }

                if (expiredLinkFailures.isNotEmpty()) {
                    Timber.tag("GOG").w("Detected ${expiredLinkFailures.size} expired secure link(s), refreshing...")

                    // Log which products the failing chunks belong to
                    expiredLinkFailures.forEach { (result, chunk) ->
                        val chunkMd5 = chunk.key
                        val productId = chunkToProductMap[chunkMd5]
                        Timber.tag("GOG").w("Chunk $chunkMd5 belongs to product $productId: ${result.exceptionOrNull()?.message}")
                    }

                    // Refresh secure links
                    val refreshResult = refreshSecureLinks(secureLinkContext, chunkHashes)
                    if (refreshResult.isSuccess) {
                        currentChunkUrlMap = refreshResult.getOrThrow()
                        Timber.tag("GOG").i("Secure links refreshed successfully, retrying failed chunks")

                        // Retry the failed chunks with new URLs
                        val retryResults = chunkBatch.map { (chunkMd5, _) ->
                            async {
                                val url = currentChunkUrlMap[chunkMd5] ?: return@async Result.failure<File>(
                                    Exception("No URL found for chunk $chunkMd5 after refresh"),
                                )
                                downloadChunkWithRetry(chunkMd5, url, chunkCacheDir, downloadInfo)
                            }
                        }.awaitAll()

                        // Check retry results
                        retryResults.firstOrNull { it.isFailure }?.let { failedResult ->
                            return@withContext Result.failure(
                                failedResult.exceptionOrNull() ?: Exception("Failed to download chunk after link refresh"),
                            )
                        }
                    } else {
                        Timber.tag("GOG").e("Failed to refresh secure links: ${refreshResult.exceptionOrNull()?.message}")
                        return@withContext Result.failure(
                            refreshResult.exceptionOrNull() ?: Exception("Failed to refresh secure links"),
                        )
                    }
                } else {
                    // Check if any download failed for other reasons
                    results.firstOrNull { it.isFailure }?.let { failedResult ->
                        return@withContext Result.failure(
                            failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                        )
                    }
                }

                downloadedChunks += chunkBatch.size

                // Update progress with smooth interpolation
                val progress = downloadedChunks.toFloat() / totalChunks
                downloadInfo.setProgress(progress)
                downloadInfo.updateStatusMessage("Downloading chunks ($downloadedChunks/$totalChunks)")
                downloadInfo.emitProgressChange()

                Timber.tag("GOG").d("Progress: ${(progress * 100).toInt()}% ($downloadedChunks/$totalChunks chunks)")
            }

            Timber.tag("GOG").i("All $totalChunks chunks downloaded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download chunks")
            Result.failure(e)
        }
    }

    /**
     * Refresh secure CDN links when they expire
     *
     * @param context Context containing info needed to fetch new links
     * @param chunkHashes List of chunk hashes needed
     * @return New chunk URL map with fresh secure links
     */
    private suspend fun refreshSecureLinks(
        context: SecureLinkContext,
        chunkHashes: List<String>,
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val productUrlMap = mutableMapOf<String, List<String>>()

            // Get secure links for each product
            for (productId in context.productIds) {
                val linksResult = apiClient.getSecureLink(
                    productId = productId,
                    path = "/",
                    generation = context.generation,
                )
                if (linksResult.isSuccess) {
                    productUrlMap[productId] = linksResult.getOrThrow().urls
                } else {
                    return@withContext Result.failure(
                        linksResult.exceptionOrNull() ?: Exception("Failed to refresh secure links for product $productId"),
                    )
                }
            }

            Timber.tag("GOG").d("Refreshed secure links for ${productUrlMap.size} product(s)")

            // Rebuild chunk URL map with new secure links
            val newChunkUrlMap = parser.buildChunkUrlMapWithProducts(chunkHashes, context.chunkToProductMap, productUrlMap)
            Result.success(newChunkUrlMap)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to refresh secure links")
            Result.failure(e)
        }
    }

    /**
     * Download a single chunk with retry logic
     *
     * @param chunkMd5 Compressed MD5 hash (chunk identifier)
     * @param url Secure CDN URL (time-limited)
     * @param chunkCacheDir Cache directory
     * @param downloadInfo Progress tracker
     */
    private suspend fun downloadChunkWithRetry(
        chunkMd5: String,
        url: String,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_CHUNK_RETRIES) { attempt ->
            val result = downloadChunk(chunkMd5, url, chunkCacheDir, downloadInfo)

            if (result.isSuccess) {
                if (attempt > 0) {
                    Timber.tag("GOG").i("Chunk $chunkMd5 downloaded successfully after ${attempt + 1} attempts")
                }
                return@withContext result
            }

            lastException = result.exceptionOrNull() as? Exception

            if (attempt < MAX_CHUNK_RETRIES - 1) {
                val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("GOG").w("Chunk $chunkMd5 download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms...")
                kotlinx.coroutines.delay(delay)
            }
        }

        Timber.tag("GOG").e(lastException, "Failed to download chunk $chunkMd5 after $MAX_CHUNK_RETRIES attempts")
        Result.failure(lastException ?: Exception("Failed to download chunk $chunkMd5"))
    }

    /**
     * Download a single chunk from GOG CDN
     *
     * @param chunkMd5 Compressed MD5 hash (chunk identifier)
     * @param url Secure CDN URL (time-limited)
     * @param chunkCacheDir Cache directory
     * @param downloadInfo Progress tracker
     */
    private suspend fun downloadChunk(
        chunkMd5: String,
        url: String,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val chunkFile = File(chunkCacheDir, "$chunkMd5.chunk")

            // Skip if already downloaded and verified
            if (chunkFile.exists()) {
                val existingMd5 = calculateMd5(chunkFile.readBytes())
                if (existingMd5 == chunkMd5) {
                    Timber.tag("GOG").d("Chunk $chunkMd5 already exists and verified, skipping")
                    return@withContext Result.success(chunkFile)
                } else {
                    Timber.tag("GOG").w("Chunk $chunkMd5 exists but failed verification, re-downloading")
                    chunkFile.delete()
                }
            }

            // Download compressed chunk
            Timber.tag("GOG").d("Downloading chunk $chunkMd5 from: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GOG Galaxy")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.tag("GOG").e("HTTP ${response.code} for chunk $chunkMd5 from URL: $url")
                return@withContext Result.failure(Exception("HTTP ${response.code} downloading chunk $chunkMd5"))
            }

            val compressedBytes = response.body?.bytes()
                ?: return@withContext Result.failure(Exception("Empty response for chunk $chunkMd5"))

            // Verify compressed MD5
            val actualMd5 = calculateMd5(compressedBytes)
            if (actualMd5 != chunkMd5) {
                return@withContext Result.failure(
                    Exception("Compressed MD5 mismatch for chunk: expected $chunkMd5, got $actualMd5"),
                )
            }

            // Save compressed chunk (will decompress during assembly)
            chunkFile.writeBytes(compressedBytes)
            downloadInfo.updateBytesDownloaded(compressedBytes.size.toLong())

            Result.success(chunkFile)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download chunk $chunkMd5")
            Result.failure(e)
        }
    }

    /**
     * Assemble files from downloaded chunks
     *
     * @param files List of files to assemble
     * @param chunkCacheDir Directory containing downloaded chunks
     * @param installDir Target installation directory
     * @param downloadInfo Progress tracker
     */
    private suspend fun assembleFiles(
        files: List<DepotFile>,
        chunkCacheDir: File,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val totalFiles = files.size

            for ((index, file) in files.withIndex()) {
                if (!downloadInfo.isActive()) {
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                downloadInfo.updateStatusMessage("Assembling ${index + 1}/$totalFiles: ${file.path}")

                val assembleResult = assembleFile(file, chunkCacheDir, installDir)
                if (assembleResult.isFailure) {
                    return@withContext Result.failure(
                        assembleResult.exceptionOrNull() ?: Exception("Failed to assemble ${file.path}"),
                    )
                }
            }

            Timber.tag("GOG").i("Assembled $totalFiles file(s) successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to assemble files")
            Result.failure(e)
        }
    }

    /**
     * Assemble a single file from its chunks
     *
     * @param file File metadata with chunks
     * @param chunkCacheDir Directory containing downloaded chunks
     * @param installDir Target installation directory
     */
    private suspend fun assembleFile(
        file: DepotFile,
        chunkCacheDir: File,
        installDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, file.path)
            outputFile.parentFile?.mkdirs()

            outputFile.outputStream().use { output ->
                for (chunk in file.chunks) {
                    // Get compressed chunk file
                    val chunkFile = File(chunkCacheDir, "${chunk.compressedMd5}.chunk")

                    if (!chunkFile.exists()) {
                        return@withContext Result.failure(
                            Exception("Chunk file missing: ${chunk.compressedMd5}"),
                        )
                    }

                    // Read compressed data
                    val compressedBytes = chunkFile.readBytes()

                    // Decompress chunk
                    val decompressedBytes = decompressChunk(compressedBytes, chunk)
                    if (decompressedBytes.isFailure) {
                        return@withContext Result.failure(
                            decompressedBytes.exceptionOrNull()
                                ?: Exception("Failed to decompress chunk ${chunk.compressedMd5}"),
                        )
                    }

                    val data = decompressedBytes.getOrThrow()

                    // Verify decompressed MD5
                    val actualMd5 = calculateMd5(data)
                    if (actualMd5 != chunk.md5) {
                        return@withContext Result.failure(
                            Exception("Decompressed MD5 mismatch for chunk: expected ${chunk.md5}, got $actualMd5"),
                        )
                    }

                    // Write to output file
                    output.write(data)
                }
            }

            // Verify final file hash if provided
            if (file.md5 != null) {
                val fileMd5 = calculateMd5File(outputFile)
                if (fileMd5 != file.md5) {
                    Timber.tag("GOG").w("File MD5 mismatch: ${file.path}, expected ${file.md5}, got $fileMd5")
                    // Don't fail - some games have incorrect MD5 in manifest
                }
            }

            Timber.tag("GOG").d("Assembled: ${file.path} (${outputFile.length()} bytes)")
            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to assemble file ${file.path}")
            Result.failure(e)
        }
    }

    /**
     * Decompress a GOG chunk using zlib
     *
     * GOG chunks are compressed with zlib
     * If chunk.compressedSize is null, data is uncompressed
     *
     * @param compressedBytes Compressed chunk data
     * @param chunk Chunk metadata
     * @return Decompressed data
     */
    private fun decompressChunk(compressedBytes: ByteArray, chunk: FileChunk): Result<ByteArray> {
        return try {
            // If no compressed size specified, data is already uncompressed
            if (chunk.compressedSize == null) {
                return Result.success(compressedBytes)
            }

            // Decompress using zlib
            val inflater = Inflater()
            try {
                inflater.setInput(compressedBytes)
                val outputStream = ByteArrayOutputStream(chunk.size.toInt())
                val buffer = ByteArray(8192)

                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    outputStream.write(buffer, 0, count)
                }

                val decompressed = outputStream.toByteArray()

                // Verify size matches expected
                if (decompressed.size.toLong() != chunk.size) {
                    return Result.failure(
                        Exception("Decompressed size mismatch: expected ${chunk.size}, got ${decompressed.size}"),
                    )
                }

                Result.success(decompressed)
            } finally {
                inflater.end()
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to decompress chunk ${chunk.compressedMd5}")
            Result.failure(e)
        }
    }

    /**
     * Calculate MD5 hash of byte array
     */
    private fun calculateMd5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculate MD5 hash of file
     */
    private fun calculateMd5File(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculate the total size of a directory recursively
     *
     * @param directory The directory to calculate size for
     * @return Total size in bytes
     */
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return 0L
            }

            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }
}
