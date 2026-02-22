package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.service.gog.api.DepotFile
import app.gamenative.service.gog.api.FileChunk
import app.gamenative.service.gog.api.GOGApiClient
import app.gamenative.service.gog.api.GOGManifestMeta
import app.gamenative.service.gog.api.GOGManifestParser
import app.gamenative.service.gog.api.V1DepotFile
import app.gamenative.utils.Net
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/**
 * Custom exception for HTTP status errors with typed status code
 */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

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
    private val WINDOWS_OS_VERSION = "windows"
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
        private const val DEPENDENCY_URL = "https://content-system.gog.com/dependencies/repository?generation=2"
    }

    /**
     * Download and install a GOG game
     *
     * @param gameId GOG game ID (numeric)
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @param language Target language (see [GOGConstants.GOG_DOWNLOAD_LANGUAGE])
     * @param withDlcs Whether to include DLC content
     * @param supportDir Optional directory for support files (redistributables)
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        gameId: String,
        installPath: File,
        downloadInfo: DownloadInfo,
        language: String = GOGConstants.GOG_DOWNLOAD_LANGUAGE,
        withDlcs: Boolean = false,
        supportDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").i("Starting download for game $gameId to ${installPath.absolutePath}")

            if(supportDir != null) {
                Timber.tag("GOG").i("Will also put dependencies into ${supportDir.absolutePath}")
            }

            // Get the actual game from database to check what ID we have stored
            val dbGame = gogManager.getGameFromDbById(gameId)

            if (dbGame == null) {
                return@withContext Result.failure(
                    Exception("Failed to fetch game from DB"),
                )
            }

            Timber.tag("GOG").d("Database game ID: ${dbGame.id}, title: ${dbGame.title}")

            // Emit download started event so UI can attach progress listeners
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true),
            )

            downloadInfo.updateStatusMessage("Fetching builds...")

            // Step 1: Get available builds — prefer Gen 2, fall back to Gen 1 (legacy)
            val selectedBuild = run {
                val gen2Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 2)
                if (gen2Result.isFailure) {
                    return@withContext Result.failure(
                        gen2Result.exceptionOrNull() ?: Exception("Failed to fetch Gen 2 builds"),
                    )
                }
                parser.selectBuild(gen2Result.getOrThrow().items, preferredGeneration = 2, platform = WINDOWS_OS_VERSION)
                    ?.let { return@run it }
                val gen1Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 1)
                if (gen1Result.isFailure) {
                    return@withContext Result.failure(
                        gen1Result.exceptionOrNull() ?: Exception("Failed to fetch builds"),
                    )
                }
                val builds = gen1Result.getOrThrow()
                parser.selectBuild(builds.items, preferredGeneration = 1, platform = WINDOWS_OS_VERSION)
                    ?: run {
                        val hint = when {
                            builds.items.isEmpty() -> "No builds returned for Windows (game may be Linux/Mac only)."
                            else -> "No Windows build. Available: ${builds.items.joinToString { "Gen ${it.generation}/${it.platform}" }}."
                        }
                        return@withContext Result.failure(Exception("No suitable build found for Windows. $hint"))
                    }
            }

            Timber.tag("GOG").i("Selected build: ${selectedBuild.buildId} (Gen ${selectedBuild.generation}, Platform: ${selectedBuild.platform})")
            Timber.tag("GOG").d("Build productId: ${selectedBuild.productId}, input gameId: $gameId")
            Timber.tag("GOG").d("Full build details: buildId=${selectedBuild.buildId}, productId=${selectedBuild.productId}, platform=${selectedBuild.platform}, gen=${selectedBuild.generation}, version=${selectedBuild.versionName}, branch=${selectedBuild.branch}, legacyBuildId=${selectedBuild.legacyBuildId}")

            val realGameId = gameId

            downloadInfo.updateStatusMessage("Fetching manifest...")

            // Step 2: Fetch main manifest
            val gameManifestResult = apiClient.fetchManifest(selectedBuild.link)
            if (gameManifestResult.isFailure) {
                return@withContext Result.failure(
                    gameManifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val gameManifest = gameManifestResult.getOrThrow()
            Timber.tag("GOG").d("Game Manifest: ${gameManifest.installDirectory}, ${gameManifest.depots.size} depot(s)")
            Timber.tag("GOG").d("Game Manifest baseProductId: ${gameManifest.baseProductId}")

            gameManifest.products?.let { products ->
                Timber.tag("GOG").d("Manifest products: ${products.joinToString { "name=${it.name}, id=${it.productId}" }}")
            }

            // Gen 1 (legacy): different manifest format and download flow (direct file URLs, no chunks)
            if (selectedBuild.generation == 1 && gameManifest.productTimestamp != null) {
                return@withContext downloadGameGen1(
                    gameId = gameId,
                    installPath = installPath,
                    downloadInfo = downloadInfo,
                    gameManifest = gameManifest,
                    selectedBuild = selectedBuild,
                    language = language,
                    withDlcs = withDlcs,
                    supportDir = supportDir,
                )
            }

            // Grab Dependencies from the gameManifest for later.
            val dependencies = gameManifest.dependencies

            downloadInfo.updateStatusMessage("Filtering depots...")

            // Step 3: Filter depots by language and bitness (32-bit or 64-bit)
            val languageDepots = parser.filterDepotsByLanguage(gameManifest, language)
            if (languageDepots.isEmpty()) {
                return@withContext Result.failure(Exception("No depots found for language: $language"))
            }

            // TODO: Verify if we need this anymore.
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
            depots.forEachIndexed { index, depot ->
                Timber.tag("GOG").d("  Depot $index: productId=${depot.productId}, manifest=${depot.manifest}, size=${depot.size}, compressedSize=${depot.compressedSize}")
            }

            downloadInfo.updateStatusMessage("Fetching depot manifests...")

            // Step 4: Fetch depot manifests to get file lists
            // Track which depot each file came from for proper productId mapping
            data class FileWithDepot(val file: DepotFile, val depotProductId: String)
            val allFilesWithDepots = mutableListOf<FileWithDepot>()

            for ((index, depot) in depots.withIndex()) {
                downloadInfo.updateStatusMessage("Fetching depot ${index + 1}/${depots.size}...")

                val depotResult = apiClient.fetchDepotManifest(depot.manifest)
                if (depotResult.isFailure) {
                    return@withContext Result.failure(
                        depotResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"),
                    )
                }

                val files = depotResult.getOrThrow().files
                files.forEach { file ->
                    allFilesWithDepots.add(FileWithDepot(file, depot.productId))
                }
            }

            val allFiles = allFilesWithDepots.map { it.file }
            Timber.tag("GOG").d("Total files from all depots: ${allFiles.size}")

            // Step 5: Separate base game, DLC, and support files
            val (baseFiles, dlcFiles) = parser.separateBaseDLC(allFiles, gameManifest.baseProductId)
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

            // Build mapping of product ID to secure URLs and chunk to product ID
            val productUrlMap = mutableMapOf<String, List<String>>()
            val chunkToProductMap = mutableMapOf<String, String>()

            Timber.tag("GOG").d("Mapping chunks to products. gameId parameter: $gameId, realGameId: $realGameId, manifest baseProductId: ${gameManifest.baseProductId}")

            // Map each chunk to its product ID using depot info
            allFilesWithDepots.forEach { (file, depotProductId) ->
                // Use depot's productId as fallback when file has null/placeholder productId

                // TODO: Remove this logic and always use the depotProductId.
                val productId = when {
                    file.productId == null -> {
                        Timber.tag("GOG").d("File ${file.path} has null productId, using depotProductId: $depotProductId")
                        depotProductId
                    }
                    file.productId == "2147483047" -> {
                        Timber.tag("GOG").d("File ${file.path} has placeholder productId, using depotProductId: $depotProductId")
                        depotProductId
                    }
                    else -> {
                        Timber.tag("GOG").d("File ${file.path} has productId: ${file.productId}")
                        file.productId
                    }
                }

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
                gameId = realGameId,
                generation = selectedBuild.generation,
                productIds = productIds,
                chunkToProductMap = chunkToProductMap,
            )

            // Step 8: Download chunks
            Timber.tag("GOG").i("Downoading Chunks for game $gameId")

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

            // Download Dependencies (They will either go to root or supportDir depending on )
            if (supportDir != null && dependencies.isNotEmpty()) {
                downloadInfo.updateStatusMessage("Downloading dependencies...")
                supportDir.mkdirs()

                val dependencyResult = downloadDependencies(gameId, dependencies, installPath, supportDir, downloadInfo)
                if (dependencyResult.isFailure){
                    Timber.tag("GOG").w("Failed to install Dependencies: ${dependencyResult.exceptionOrNull()?.message}")
                }

            }

            // Step 11: Cleanup
            chunkCacheDir.deleteRecursively()

            val effectiveLanguage = parser.getEffectiveLanguageFromDepots(depots, language)
            saveManifestToGameDir(installPath, gameManifest, selectedBuild.buildId, selectedBuild.versionName, effectiveLanguage)

            finalizeInstallSuccess(gameId, installPath, downloadInfo)
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
     * Saves manifest data needed for post-install setup (scriptinterpreter or temp_executable) to
     * installPath/_gog_manifest.json. Used on first launch to create registry keys etc.
     * @param language Language used for the download (from the selected language depots).
     */
    private fun saveManifestToGameDir(
        installPath: File,
        gameManifest: GOGManifestMeta,
        buildId: String,
        versionName: String,
        language: String,
    ) {
        try {
            val productsArray = JSONArray()
            gameManifest.products.forEach { p ->
                productsArray.put(
                    JSONObject().apply {
                        put("productId", p.productId)
                        put("name", p.name)
                        put("temp_executable", p.temp_executable ?: "")
                        put("temp_arguments", p.temp_arguments ?: "")
                    },
                )
            }
            val root = JSONObject().apply {
                put("version", 2)
                put("baseProductId", gameManifest.baseProductId)
                put("scriptInterpreter", gameManifest.scriptInterpreter)
                put("products", productsArray)
                put("buildId", buildId)
                put("versionName", versionName)
                put("language", language)
            }
            val file = File(installPath, GOGManifestUtils.MANIFEST_FILE_NAME)
            file.writeText(root.toString())
            Timber.tag("GOG").d("Saved setup manifest to ${file.absolutePath} (scriptInterpreter=${gameManifest.scriptInterpreter})")
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to save GOG setup manifest")
        }
    }

    /**
     * Shared finalization after a successful install: update DB, set download complete, emit events.
     * Used by both Gen 2 and Gen 1 success paths.
     */
    private suspend fun finalizeInstallSuccess(gameId: String, installPath: File, downloadInfo: DownloadInfo) {
        downloadInfo.updateStatusMessage("Updating database...")
        try {
            val game = gogManager.getGameFromDbById(gameId)
            if (game != null) {
                val installSize = calculateDirectorySize(installPath)
                gogManager.updateGame(game.copy(isInstalled = true, installPath = installPath.absolutePath, installSize = installSize))
                Timber.tag("GOG").i("Updated database: game marked as installed, size: ${installSize / 1_000_000} MB")
            } else {
                Timber.tag("GOG").w("Game $gameId not found in database, skipping DB update")
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to update database for game $gameId")
        }
        downloadInfo.updateStatusMessage("Complete")
        downloadInfo.setProgress(1.0f)
        downloadInfo.setActive(false)
        downloadInfo.emitProgressChange()
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
        )
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0),
        )
    }

    /**
     * Gen 1 (legacy) download: one main.bin per depot; files are read via Range requests (offset/size).
     * See heroic-gogdl: task_executor.py v1() uses endpoint["parameters"]["path"] += "/main.bin" and Range: bytes=offset-(offset+size-1).
     */
    private suspend fun downloadGameGen1(
        gameId: String,
        installPath: File,
        downloadInfo: DownloadInfo,
        gameManifest: app.gamenative.service.gog.api.GOGManifestMeta,
        selectedBuild: app.gamenative.service.gog.api.GOGBuild,
        language: String,
        withDlcs: Boolean,
        supportDir: File?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val timestamp = gameManifest.productTimestamp ?: return@withContext Result.failure(Exception("Gen 1 manifest missing productTimestamp"))
            val platform = selectedBuild.platform
            val ownedGameIds = gogManager.getAllGameIds()

            // Gen 1: do not filter by language — some games put main content in a depot tagged with another
            // language (or no tag), so filtering to en-US can leave only a small depot and skip the main one.
            downloadInfo.updateStatusMessage("Filtering depots...")
            val bitnessDepots = parser.filterDepotsByBitness(gameManifest.depots, bitness = "64")
            val depots = parser.filterDepotsByOwnership(bitnessDepots, ownedGameIds)
            if (depots.isEmpty()) {
                return@withContext Result.failure(Exception("No owned depots found"))
            }

            val baseProductId = gameManifest.baseProductId
            val filesToDownload = if (withDlcs) depots else depots.filter { it.productId == baseProductId }
            if (filesToDownload.isEmpty()) {
                return@withContext Result.failure(Exception("No depots to download"))
            }

            val productIds = filesToDownload.map { it.productId }.toSet()
            val securePath = "/$platform/$timestamp/"
            val productUrlMap = mutableMapOf<String, List<String>>()
            for (productId in productIds) {
                val linksResult = apiClient.getSecureLink(productId = productId, path = securePath, generation = 1)
                if (linksResult.isFailure) {
                    return@withContext Result.failure(linksResult.exceptionOrNull() ?: Exception("Failed to get secure link for product $productId"))
                }
                productUrlMap[productId] = linksResult.getOrThrow().urls
            }

            data class FileWithProduct(val file: V1DepotFile, val productId: String)
            val allV1Files = mutableListOf<FileWithProduct>()

            downloadInfo.updateStatusMessage("Fetching depot manifests...")
            for ((idx, depot) in filesToDownload.withIndex()) {
                downloadInfo.updateStatusMessage("Fetching depot ${idx + 1}/${filesToDownload.size}...")
                val depotJsonResult = apiClient.fetchDepotManifestV1(depot.productId, platform, timestamp, depot.manifest)
                if (depotJsonResult.isFailure) {
                    return@withContext Result.failure(depotJsonResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"))
                }
                val v1Files = parser.parseV1DepotManifest(depotJsonResult.getOrThrow())
                v1Files.forEach { allV1Files.add(FileWithProduct(it, depot.productId)) }
            }

            val gameFiles = allV1Files.filter { !it.file.isSupport }
            val supportFiles = allV1Files.filter { it.file.isSupport }
            val totalSize = gameFiles.sumOf { it.file.size } + supportFiles.sumOf { it.file.size }
            downloadInfo.setTotalExpectedBytes(totalSize)
            downloadInfo.updateStatusMessage("Downloading files...")
            downloadInfo.setProgress(0f)
            downloadInfo.setActive(true)
            downloadInfo.emitProgressChange()

            val totalFiles = gameFiles.size + supportFiles.size
            var doneFiles = 0

            // Gen 1: one main.bin URL per product; each file is fetched with Range: bytes=offset-(offset+size-1)
            val mainBinUrlByProduct = productUrlMap.mapValues { (_, urls) ->
                (urls.firstOrNull()?.trimEnd('/') ?: "") + "/main.bin"
            }

            fun downloadOneFile(f: FileWithProduct, baseDir: File): Result<Unit> {
                val file = f.file
                val outFile = File(baseDir, file.path)
                outFile.parentFile?.mkdirs()

                if (file.size == 0L) {
                    outFile.createNewFile()
                    return Result.success(Unit)
                }

                val mainBinUrl = mainBinUrlByProduct[f.productId]
                    ?: return Result.failure(Exception("No main.bin URL for product ${f.productId}"))

                val offset = file.offset
                if (offset == null) {
                    return Result.failure(Exception("Gen 1 file ${file.path} has no offset (main.bin range request required)"))
                }

                val rangeHeader = "bytes=$offset-${offset + file.size - 1}"
                val request = Request.Builder()
                    .url(mainBinUrl)
                    .header("User-Agent", "GOG Galaxy")
                    .header("Range", rangeHeader)
                    .build()

                return try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code} for ${file.path}"))
                        val body = response.body ?: return Result.failure(Exception("Empty response"))
                        val md = MessageDigest.getInstance("MD5")
                        val buffer = ByteArray(256 * 1024) // 256KB
                        val progressInterval = 512L * 1024 // emit progress every 512KB
                        var copiedInFile = 0L
                        DigestOutputStream(
                            BufferedOutputStream(FileOutputStream(outFile)),
                            md
                        ).use { out ->
                            body.byteStream().use { input ->
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    if (!downloadInfo.isActive()) {
                                        outFile.delete()
                                        return Result.failure(Exception("Download cancelled"))
                                    }
                                    out.write(buffer, 0, n)
                                    copiedInFile += n
                                    downloadInfo.updateBytesDownloaded(n.toLong())
                                    if (copiedInFile >= progressInterval || downloadInfo.getBytesDownloaded() >= totalSize) {
                                        copiedInFile = 0L
                                        downloadInfo.setProgress(
                                            (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f)
                                        )
                                        downloadInfo.emitProgressChange()
                                    }
                                }
                            }
                        }
                        val bytesWritten = outFile.length()
                        if (bytesWritten != file.size) return Result.failure(Exception("Size mismatch ${file.path}"))
                        val md5 = md.digest().joinToString("") { "%02x".format(it) }
                        if (file.hash.isNotEmpty() && md5 != file.hash) return Result.failure(Exception("MD5 mismatch ${file.path}"))
                        // bytes already reported during copy; ensure final progress is exact
                        downloadInfo.setProgress(
                            (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f)
                        )
                        downloadInfo.emitProgressChange()
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            for (f in gameFiles) {
                if (!downloadInfo.isActive()) return@withContext Result.failure(Exception("Download cancelled"))
                downloadInfo.updateStatusMessage("Downloading ${f.file.path}")
                val res = downloadOneFile(f, installPath)
                if (res.isFailure) return@withContext res
                doneFiles++
            }
            if (supportDir != null) {
                supportDir.mkdirs()
                for (f in supportFiles) {
                    if (!downloadInfo.isActive()) return@withContext Result.failure(Exception("Download cancelled"))
                    downloadInfo.updateStatusMessage("Downloading support ${f.file.path}")
                    val res = downloadOneFile(f, supportDir)
                    if (res.isFailure) return@withContext res
                    doneFiles++
                }
            }

            val effectiveLanguage = parser.getEffectiveLanguageFromDepots(filesToDownload, language)
            saveManifestToGameDir(installPath, gameManifest, selectedBuild.buildId, selectedBuild.versionName, effectiveLanguage)

            finalizeInstallSuccess(gameId, installPath, downloadInfo)
            Timber.tag("GOG").i("Gen 1 download completed for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Gen 1 download failed: ${e.message}")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
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
                        downloadChunkWithRetry(chunkMd5, url, chunkCacheDir, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed due to expired links (401/403/404)
                val expiredLinkFailures = results.zip(chunkBatch).filter { (result, _) ->
                    val exception = result.exceptionOrNull()
                    exception is HttpStatusException && exception.statusCode in listOf(401, 403, 404)
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
     * Downloads the dependencies for a given game by using the dependency array from the game's depo-list
     * It will download the dependencies using the dependency URL
     *
     */
    private suspend fun downloadDependencies(
        gameId: String,
        dependencies: List<String>,
        gameDir: File,
        supportDir: File,
        downloadInfo: DownloadInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (dependencies.isEmpty()) {
                Timber.tag("GOG").d("No dependencies to download")
                return@withContext Result.success(Unit)
            }

            Timber.tag("GOG").i("Downloading ${dependencies.size} dependencies: ${dependencies.joinToString()}")

            // Get dependency repository
            val dependencyRepositoryResult = apiClient.fetchDependencyRepository(DEPENDENCY_URL)
            if (dependencyRepositoryResult.isFailure) {
                return@withContext Result.failure(
                    dependencyRepositoryResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency repository"),
                )
            }

            val repositoryManifestUrl = dependencyRepositoryResult.getOrThrow().repositoryManifest
            if (repositoryManifestUrl.isBlank()) {
                return@withContext Result.failure(Exception("Empty repository manifest URL"))
            }

            // Get the decompressed manifest
            val dependencyManifestResult = apiClient.fetchDependencyManifest(repositoryManifestUrl)
            if (dependencyManifestResult.isFailure) {
                return@withContext Result.failure(
                    dependencyManifestResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency manifest"),
                )
            }

            val dependencyManifest = dependencyManifestResult.getOrThrow()

            // Filter depots by the dependencyId so we only install what we need (e.g., MSVC2013)
            val filteredDepots = dependencyManifest.depots.filter { depot ->
                dependencies.contains(depot.dependencyId)
            }

            if (filteredDepots.isEmpty()) {
                Timber.tag("GOG").w("No matching dependency depots found for: ${dependencies.joinToString()}")
                return@withContext Result.success(Unit)
            }

            Timber.tag("GOG").d("Found ${filteredDepots.size} dependency depot(s) to download")

            // Get open link URLs for dependencies
            val openLinkResult = apiClient.getDependencyOpenLink()
            if (openLinkResult.isFailure) {
                return@withContext Result.failure(
                    openLinkResult.exceptionOrNull() ?: Exception("Failed to get dependency open link"),
                )
            }

            val dependencyBaseUrls = openLinkResult.getOrThrow()
            if (dependencyBaseUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No dependency URLs returned"))
            }

            Timber.tag("GOG").d("Got ${dependencyBaseUrls.size} dependency base URL(s)")

            // Download each dependency
            for ((index, depot) in filteredDepots.withIndex()) {
                downloadInfo.updateStatusMessage("Downloading dependency ${index + 1}/${filteredDepots.size}: ${depot.readableName}")

                Timber.tag("GOG").i("Downloading dependency: ${depot.readableName} (${depot.dependencyId})")

                // Determine install directory based on executable path
                // If path starts with __redist, install to supportDir, otherwise install to gameDir
                val installBaseDir = if (depot.executable?.path?.startsWith("__redist") == true) {
                    Timber.tag("GOG").d("Dependency ${depot.dependencyId} has __redist path, installing to supportDir")
                    supportDir
                } else {
                    Timber.tag("GOG").d("Dependency ${depot.dependencyId} has no __redist path, installing to gameDir")
                    gameDir
                }

                // Fetch depot manifest to get file list using open link URLs
                val depotManifestResult = apiClient.fetchDependencyDepotManifest(depot.manifest, dependencyBaseUrls)
                if (depotManifestResult.isFailure) {
                    Timber.tag("GOG").w("Failed to fetch depot manifest for ${depot.readableName}: ${depotManifestResult.exceptionOrNull()?.message}")
                    continue
                }

                val depotManifest = depotManifestResult.getOrThrow()
                val depotFiles = depotManifest.files

                if (depotFiles.isEmpty()) {
                    Timber.tag("GOG").w("No files in dependency depot: ${depot.readableName}")
                    continue
                }

                // Extract chunk hashes
                val chunkHashes = parser.extractChunkHashes(depotFiles)

                // Build chunk URL map using dependency base URLs
                val chunkUrlMap = buildChunkUrlMap(chunkHashes, dependencyBaseUrls)

                // Create cache directory for this dependency
                val depotCacheDir = File(installBaseDir, ".gog_dep_${depot.dependencyId}")
                depotCacheDir.mkdirs()

                // Download chunks
                val downloadResult = downloadChunksSimple(chunkUrlMap, depotCacheDir, downloadInfo)
                if (downloadResult.isFailure) {
                    Timber.tag("GOG").w("Failed to download chunks for ${depot.readableName}: ${downloadResult.exceptionOrNull()?.message}")
                    continue
                }

                // Assemble files - the file paths in the manifest already contain the full directory structure
                // so we use installBaseDir directly without adding depot.dependencyId
                val depotInstallDir = installBaseDir
                depotInstallDir.mkdirs()

                // Strip __redist/ prefix from file paths if they're being installed to supportDir
                // This prevents paths like supportDir/__redist/DirectX and gives us supportDir/DirectX instead
                val filesToAssemble = if (installBaseDir == supportDir) {
                    depotFiles.map { file ->
                        if (file.path.startsWith("__redist/")) {
                            file.copy(path = file.path.removePrefix("__redist/"))
                        } else {
                            file
                        }
                    }
                } else {
                    depotFiles
                }

                val assembleResult = assembleFiles(filesToAssemble, depotCacheDir, depotInstallDir, downloadInfo)
                if (assembleResult.isFailure) {
                    Timber.tag("GOG").w("Failed to assemble files for ${depot.readableName}: ${assembleResult.exceptionOrNull()?.message}")
                    continue
                }

                // Cleanup cache
                depotCacheDir.deleteRecursively()

                Timber.tag("GOG").i("Successfully downloaded dependency: ${depot.readableName} to ${depotInstallDir.absolutePath}")
            }

            Timber.tag("GOG").i("Completed downloading ${filteredDepots.size} dependencies")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download dependencies")
            Result.failure(e)
        }
    }

    /**
     * Downloads GOG redistributables (e.g. ISI/scriptinterpreter.exe) to a shared directory.
     * Used so scriptinterpreter can be run on first launch for games that need it (e.g. Fallout 3).
     * Heroic uses the same approach: download redist to a shared path via gogdl redist command.
     *
     * @param redistDir Target directory (e.g. context.filesDir/GOG/redist). Files end up under redistDir/ISI/ etc.
     * @param dependencyIds Dependency IDs to download; default ISI (scriptinterpreter).
     * @param onProgress Progress callback 0f..1f
     */
    suspend fun downloadRedist(
        redistDir: File,
        dependencyIds: List<String> = listOf("ISI"),
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val downloadInfo = DownloadInfo(
            jobCount = 1,
            gameId = 0,
            downloadingAppIds = CopyOnWriteArrayList(),
        )
        downloadInfo.addProgressListener { onProgress(it) }
        redistDir.mkdirs()
        val result = downloadDependencies(
            gameId = "redist",
            dependencies = dependencyIds,
            gameDir = redistDir,
            supportDir = redistDir,
            downloadInfo = downloadInfo,
        )
        if (result.isSuccess) {
            onProgress(1f)
        }
        result
    }

    /**
     * Build chunk URL map using base URLs
     */
    private fun buildChunkUrlMap(chunkHashes: List<String>, baseUrls: List<String>): Map<String, String> {
        val chunkUrlMap = mutableMapOf<String, String>()
        val baseUrl = baseUrls.firstOrNull() ?: return emptyMap()
        // Ensure base URL ends with / for proper concatenation
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        chunkHashes.forEach { hash ->
            // Build GOG Galaxy path format: AA/BB/CCDD...
            val galaxyPath = if (hash.length >= 4) {
                "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
            } else {
                hash
            }
            chunkUrlMap[hash] = "$normalizedBaseUrl$galaxyPath"
        }

        return chunkUrlMap
    }

    /**
     * Simplified chunk download without retry and secure link refresh
     * Used for dependencies which use open links
     */
    private suspend fun downloadChunksSimple(
        chunkUrlMap: Map<String, String>,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val chunks = chunkUrlMap.entries.toList()

            // Download in batches
            chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                val results = chunkBatch.map { (chunkMd5, url) ->
                    async {
                        downloadChunk(chunkMd5, url, chunkCacheDir, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed
                results.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to download chunk"),
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download dependency chunks")
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

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GOG").e("HTTP ${response.code} for chunk $chunkMd5 from URL: $url")
                    return@withContext Result.failure(
                        HttpStatusException(response.code, "HTTP ${response.code} downloading chunk $chunkMd5")
                    )
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
            }
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
                    if (count > 0) {
                        outputStream.write(buffer, 0, count)
                    } else {
                        // No bytes produced - check if we need more input or a dictionary
                        if (inflater.needsInput()) {
                            throw java.io.IOException(
                                "Incomplete zlib data: decompression requires more input but none available"
                            )
                        } else if (inflater.needsDictionary()) {
                            throw java.io.IOException(
                                "Zlib data requires a preset dictionary which is not supported"
                            )
                        }
                        // If neither condition is true, inflater is still processing internally
                        // Continue loop, but this should be rare
                    }
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
