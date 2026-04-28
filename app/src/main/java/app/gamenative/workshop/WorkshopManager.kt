package app.gamenative.workshop

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.SteamID
import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import app.gamenative.utils.Net
import okhttp3.Request
import org.json.JSONObject
import org.tukaani.xz.LZMAInputStream
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages downloading of subscribed Steam Workshop mods before game launch.
 *
 * Uses the existing [DepotDownloader] and [PubFileItem] infrastructure to download
 * Workshop items, leveraging the same CDN pipeline used for game installations.
 *
 * Workshop items are downloaded to the gbe_fork steamclient-mode path:
 *   <winePrefix>/drive_c/Program Files (x86)/Steam/steamapps/workshop/content/<appId>/<itemId>/
 *
 * gbe_fork discovers mods by scanning numeric-named subfolders under workshop/content/<appId>/.
 */
object WorkshopManager {

    private const val TAG = "WorkshopManager"
    private const val MAX_PAGES = 50
    private const val PAGE_SIZE = 100
    private var workshopTypesPatched = false

    /**
     * Fetches the list of subscribed Workshop items for the given app.
     *
     * Uses the `PublishedFile.GetUserFiles` service RPC with `type=mysubscriptions`
     * via SteamUnifiedMessages over the CM connection. Returns full file details
     * including titles, sizes, and manifest IDs.
     *
     * Paginates via `page` until all items are retrieved.
     *
     * @return [WorkshopFetchResult] with the items and a [succeeded][WorkshopFetchResult.succeeded]
     *         flag indicating whether the RPC completed successfully. When the fetch fails
     *         (network error, timeout, handler unavailable), [succeeded] is false and callers
     *         should not delete existing on-disk mods based on the (possibly empty) item list.
     */
    suspend fun getSubscribedItems(
        appId: Int,
        steamClient: SteamClient,
        steamId: SteamID,
    ): WorkshopFetchResult {
        Timber.tag(TAG).d(
            "Fetching subscribed Workshop items via PublishedFile.GetUserFiles for appId=$appId"
        )

        val unifiedMessages = steamClient.getHandler<SteamUnifiedMessages>()
        if (unifiedMessages == null) {
            Timber.tag(TAG).e("SteamUnifiedMessages handler not available")
            return WorkshopFetchResult(emptyList(), succeeded = false)
        }
        val publishedFile = unifiedMessages.createService<PublishedFile>()

        val allItems = mutableListOf<WorkshopItem>()
        var fetchedAtLeastOnePage = false
        var allPagesSucceeded = false
        var page = 1

        while (page <= MAX_PAGES) {
            val result = fetchSubscribedFilesViaRPC(
                publishedFile = publishedFile,
                appId = appId,
                steamId = steamId,
                page = page,
            ) ?: break

            fetchedAtLeastOnePage = true
            allItems.addAll(result.items.map { item ->
                if (item.appId == 0) item.copy(appId = appId) else item
            })

            Timber.tag(TAG).d(
                "GetUserFiles page=$page: ${result.items.size} items, " +
                "total=${result.totalResults} [appId=$appId]"
            )

            if (result.items.isEmpty() || allItems.size >= result.totalResults) {
                allPagesSucceeded = true
                break
            }
            page++
        }

        Timber.tag(TAG).i("Total subscribed Workshop items: ${allItems.size} for appId=$appId (succeeded=$fetchedAtLeastOnePage, complete=$allPagesSucceeded)")
        return WorkshopFetchResult(allItems, succeeded = fetchedAtLeastOnePage, isComplete = allPagesSucceeded)
    }

    private data class SubscribedFilesPage(
        val items: List<WorkshopItem>,
        val totalResults: Int,
    )

    /**
     * Calls `PublishedFile.GetUserFiles` with `type=mysubscriptions` to get
     * the user's full subscription list for the given app.
     */
    private suspend fun fetchSubscribedFilesViaRPC(
        publishedFile: PublishedFile,
        appId: Int,
        steamId: SteamID,
        page: Int,
    ): SubscribedFilesPage? = withContext(Dispatchers.IO) {
        try {
            val request = CPublishedFile_GetUserFiles_Request.newBuilder().apply {
                this.steamid = steamId.convertToUInt64()
                this.appid = appId
                this.page = page
                this.numperpage = PAGE_SIZE
                this.type = "mysubscriptions"
                // ~0 = k_EUGCMatchingUGCType_All — include all file types.
                // Default (0) = k_EUGCMatchingUGCType_Items which omits
                // GameManagedItems (type 12) used by games like Cities in Motion 2.
                this.filetype = 0xFFFFFFFF.toInt()
            }.build()

            Timber.tag(TAG).d("GetUserFiles RPC: appId=$appId page=$page numperpage=$PAGE_SIZE")

            val response = withTimeoutOrNull(30_000L) {
                publishedFile.getUserFiles(request).toFuture().await()
            }

            if (response == null) {
                Timber.tag(TAG).e("GetUserFiles timed out for appId=$appId page=$page")
                return@withContext null
            }

            if (response.result != EResult.OK) {
                Timber.tag(TAG).e(
                    "GetUserFiles failed: result=${response.result} " +
                    "(code=${response.result.code()}) for appId=$appId page=$page"
                )
                return@withContext null
            }

            val body = response.body.build()

            Timber.tag(TAG).d(
                "GetUserFiles response: ${body.publishedfiledetailsCount} items, " +
                "total=${body.total} [appId=$appId]"
            )

            val items = body.publishedfiledetailsList.map { details ->
                WorkshopItem(
                    publishedFileId = details.publishedfileid,
                    appId = if (details.consumerAppid != 0) details.consumerAppid else appId,
                    title = details.title.ifEmpty { details.publishedfileid.toString() },
                    fileSizeBytes = details.fileSize,
                    manifestId = details.hcontentFile,
                    timeUpdated = details.timeUpdated.toLong(),
                    fileUrl = details.fileUrl ?: "",
                    fileName = details.filename ?: "",
                    previewUrl = details.previewUrl ?: "",
                    description = details.fileDescription ?: "",
                    tags = details.tagsList
                        ?.mapNotNull { tag -> tag.tag?.takeIf { it.isNotBlank() } }
                        ?.joinToString(",")
                        ?: "",
                ).also {
                    Timber.tag(TAG).d(
                        "Item ${it.publishedFileId} '${it.title}': " +
                            "manifestId=${it.manifestId}, fileUrl=${it.fileUrl.take(60)}, " +
                            "size=${it.fileSizeBytes}, type=${details.fileType}, " +
                            "filename='${it.fileName}'"
                    )
                }
            }

            return@withContext SubscribedFilesPage(
                items = items,
                totalResults = body.total,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GetUserFiles call failed for appId=$appId page=$page")
            return@withContext null
        }
    }

    /**
     * Deletes workshop content directories for mods that are no longer subscribed.
     * Compares the on-disk directories against the current subscription list
     * and removes any orphaned content.
     */
    fun cleanupUnsubscribedItems(
        subscribedItems: List<WorkshopItem>,
        workshopContentDir: File,
    ) {
        if (!workshopContentDir.exists()) return
        val subscribedIds = subscribedItems.map { it.publishedFileId }.toSet()
        val onDiskDirs = workshopContentDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?: return

        var removedCount = 0
        onDiskDirs.forEach { dir ->
            val id = dir.name.toLong()
            if (id !in subscribedIds) {
                if (dir.deleteRecursively()) {
                    removedCount++
                    Timber.tag(TAG).d("Removed unsubscribed workshop item: ${dir.name}")
                }
                // Also clean up any .partial directory
                val partialDir = File(workshopContentDir, "${dir.name}.partial")
                partialDir.deleteRecursively()
            }
        }
        if (removedCount > 0) {
            Timber.tag(TAG).i("Cleaned up $removedCount unsubscribed workshop items")
        }
    }

    private const val COMPLETE_MARKER = ".workshop_complete"

    /**
     * Filters items that need downloading. An item needs sync if:
     * - It has downloadable content (fileUrl or manifestId)
     * - Its directory doesn't exist or is empty
     * - It has a leftover .partial directory
     * - It's missing the `.workshop_complete` marker (crash recovery)
     * - The mod has been updated on Steam since we last downloaded it
     */
    fun getItemsNeedingSync(
        items: List<WorkshopItem>,
        workshopContentDir: File,
    ): List<WorkshopItem> {
        Timber.tag(TAG).d(
            "getItemsNeedingSync: checking ${items.size} items in $workshopContentDir " +
                "(exists=${workshopContentDir.exists()})"
        )
        return items.filter { item ->
            // Skip items with no downloadable content (deleted/unavailable mods)
            if (item.fileUrl.isEmpty() && item.manifestId == 0L) {
                Timber.tag(TAG).w(
                    "Skipping unavailable item ${item.publishedFileId} '${item.title}' " +
                        "(no fileUrl or manifestId)"
                )
                return@filter false
            }
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            val partialDir = File(workshopContentDir, "${item.publishedFileId}.partial")
            val completeMarker = File(itemDir, COMPLETE_MARKER)

            // DepotDownloader leaves .partial sibling dirs after completing.
            // If the complete marker exists, the download finished — clean up
            // the leftover .partial so it doesn't trigger a false re-download.
            if (completeMarker.exists() && partialDir.exists()) {
                partialDir.deleteRecursively()
                Timber.tag(TAG).d(
                    "Cleaned leftover .partial for ${item.publishedFileId} '${item.title}'"
                )
            }

            // Not downloaded at all or incomplete
            if (!completeMarker.exists() || partialDir.exists()) {
                Timber.tag(TAG).d(
                    "Item ${item.publishedFileId} '${item.title}' needs sync: " +
                        "dir=${itemDir.exists()}, marker=${completeMarker.exists()}, " +
                        "markerPath=${completeMarker.absolutePath}, " +
                        "partial=${partialDir.exists()}"
                )
                return@filter true
            }

            // Check if the mod was updated on Steam since we downloaded it
            val savedTimestamp = runCatching { completeMarker.readText().trim().toLongOrNull() }.getOrNull()
            if (savedTimestamp != null && item.timeUpdated > savedTimestamp) {
                Timber.tag(TAG).i(
                    "Mod ${item.publishedFileId} '${item.title}' updated on Steam: " +
                        "local=$savedTimestamp, remote=${item.timeUpdated}"
                )
                return@filter true
            }

            Timber.tag(TAG).d(
                "Item ${item.publishedFileId} '${item.title}' is up-to-date " +
                    "(saved=$savedTimestamp, remote=${item.timeUpdated})"
            )
            false
        }
    }

    /**
     * Ensures `.workshop_complete` markers are stamped with the current
     * `timeUpdated` so that future update detection does not trigger a
     * wasteful re-download or re-prompt for already-current content.
     */
    fun updateMarkerTimestamps(
        items: List<WorkshopItem>,
        workshopContentDir: File,
    ) {
        var updatedCount = 0
        for (item in items) {
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            val completeMarker = File(itemDir, COMPLETE_MARKER)
            if (!completeMarker.exists()) continue
            if (item.timeUpdated <= 0) continue
            val existing = runCatching { completeMarker.readText().trim().toLongOrNull() }.getOrNull()
            if (existing == null || existing != item.timeUpdated) {
                completeMarker.writeText(item.timeUpdated.toString())
                updatedCount++
            }
        }
        if (updatedCount > 0) {
            Timber.tag(TAG).i("Updated $updatedCount workshop markers with current timestamps")
        }
    }

    /**
     * Fixes files downloaded by older code that saved them without proper
     * extensions. Games need proper extensions — L4D2 scans for .vpk,
     * Skyrim for .bsa/.esp, etc.
     *
     * For each item with a known [WorkshopItem.fileName], if the directory
     * has exactly one non-dotfile that isn't the expected name, rename it.
     * This handles files named with the publishedFileId or a CDN hash.
     */
    fun fixItemFileNames(
        items: List<WorkshopItem>,
        workshopContentDir: File,
    ) {
        var renamedCount = 0
        for (item in items) {
            val baseName = item.fileName.substringAfterLast('/')
            if (baseName.isEmpty()) continue
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            if (!itemDir.isDirectory) continue

            // Already has a properly-named file — skip
            val goodFile = File(itemDir, baseName)
            if (goodFile.exists()) continue

            // Find a single misnamed content file (not dotfiles like .workshop_complete)
            val contentFiles = itemDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?: continue
            if (contentFiles.size != 1) continue

            val badFile = contentFiles[0]

            // CKM files contain packed BSA+ESP data. After extraction,
            // the .ckm is deleted and .esp/.bsa files remain. Don't
            // rename these extraction products back to .ckm.
            val targetExt = baseName.substringAfterLast('.', "").lowercase()
            val currentExt = badFile.extension.lowercase()
            if (targetExt == "ckm" && currentExt in setOf("esp", "esm", "bsa", "bsl")) continue

            if (badFile.renameTo(goodFile)) {
                renamedCount++
                Timber.tag(TAG).i(
                    "Renamed ${badFile.name} → ${goodFile.name} in ${itemDir.name}"
                )
            } else {
                Timber.tag(TAG).w(
                    "Failed to rename ${badFile.name} → ${goodFile.name} in ${itemDir.name}"
                )
            }
        }
        if (renamedCount > 0) {
            Timber.tag(TAG).i("Fixed $renamedCount workshop item filenames")
        }
    }

    /**
     * Extracts Skyrim `.ckm` (Creation Kit Module) files into their component
     * `.bsa` and `.esp` files so the game can load them.
     *
     * CKM format:
     *  - u32 LE: BSA section length (0 if no BSA)
     *  - byte[bsaLen]: BSA file data (includes BSA header)
     *  - u32 LE: ESP section length (may be 0; rest of file is ESP)
     *  - byte[]: ESP file data (includes TES4 header)
     */
    fun extractCkmFiles(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var extractedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            val ckmFiles = itemDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".ckm", ignoreCase = true) }
                ?: return@forEach
            if (ckmFiles.isEmpty()) return@forEach

            for (ckmFile in ckmFiles) {
                try {
                    val baseName = ckmFile.nameWithoutExtension
                    val data = ckmFile.readBytes()
                    if (data.size < 8) {
                        Timber.tag(TAG).w("CKM file too small: ${ckmFile.name} in ${itemDir.name}")
                        continue
                    }

                    // If the file starts with TES4 magic (0x54455334), it's
                    // already an ESP that was wrongly renamed to .ckm by
                    // fixItemFileNames. Rename back to .esp and skip extraction.
                    if (data[0] == 0x54.toByte() && data[1] == 0x45.toByte() &&
                        data[2] == 0x53.toByte() && data[3] == 0x34.toByte()) {
                        val espFile = File(ckmFile.parentFile, "$baseName.esp")
                        if (!espFile.exists()) {
                            Files.move(ckmFile.toPath(), espFile.toPath())
                            Timber.tag(TAG).i("Recovered ESP from mis-renamed CKM: ${ckmFile.name} → ${espFile.name} in ${itemDir.name}")
                        }
                        continue
                    }

                    // Read BSA section length (u32 LE)
                    val bsaLen = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)

                    var offset = 4
                    if (bsaLen > 0 && offset + bsaLen <= data.size) {
                        val bsaFile = File(itemDir, "$baseName.bsa")
                        bsaFile.writeBytes(data.copyOfRange(offset, offset + bsaLen))
                        offset += bsaLen
                    }

                    // Skip ESP length field (4 bytes)
                    if (offset + 4 <= data.size) {
                        offset += 4
                    }

                    // Remaining bytes are the ESP
                    if (offset < data.size) {
                        val espFile = File(itemDir, "$baseName.esp")
                        espFile.writeBytes(data.copyOfRange(offset, data.size))
                    }

                    ckmFile.delete()
                    extractedCount++
                    Timber.tag(TAG).i(
                        "Extracted CKM: ${ckmFile.name} (bsa=${bsaLen > 0}) in ${itemDir.name}"
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to extract CKM: ${ckmFile.name} in ${itemDir.name}")
                }
            }
        }

        if (extractedCount > 0) {
            Timber.tag(TAG).i("Extracted $extractedCount CKM files into BSA/ESP")
        }
    }

    /**
     * Extracts workshop items that consist of a single ZIP archive.
     *
     * Many Workshop authors upload a single `.zip` file as their mod content
     * (e.g. Door Kickers' `modupload.zip`). On a real Steam client the game
     * expects the extracted contents, not the archive. This function detects
     * item directories whose only substantive file is a `.zip`, extracts it
     * in-place, and deletes the archive.
     *
     * A `.zip_extracted` marker file prevents re-extraction on subsequent runs.
     * Preview images (`preview.jpg`/`preview.png`) are preserved.
     */
    fun extractZipMods(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var extractedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach

            // Skip if already extracted
            if (File(itemDir, ".zip_extracted").exists()) return@forEach

            // Find content files (skip hidden files and preview images)
            val contentFiles = itemDir.listFiles()?.filter { f ->
                f.isFile &&
                    !f.name.startsWith(".") &&
                    !f.name.startsWith("preview", ignoreCase = true)
            } ?: return@forEach

            // Only auto-extract when the sole content file is a ZIP
            if (contentFiles.size != 1) return@forEach
            val zipFile = contentFiles.first()
            if (!zipFile.name.endsWith(".zip", ignoreCase = true)) return@forEach

            // Verify it's actually a ZIP via magic bytes
            val magic = ByteArray(4)
            try {
                zipFile.inputStream().use { it.read(magic) }
            } catch (_: Exception) { return@forEach }
            if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte() ||
                magic[2] != 0x03.toByte() || magic[3] != 0x04.toByte()
            ) return@forEach

            try {
                java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(itemDir, entry.name)
                        // Guard against zip-slip (path traversal)
                        if (!outFile.canonicalPath.startsWith(itemDir.canonicalPath + File.separator)) {
                            Timber.tag(TAG).w("Skipping zip entry with path traversal: ${entry.name}")
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
                // Successfully extracted — remove the zip and leave a marker
                zipFile.delete()
                File(itemDir, ".zip_extracted").createNewFile()
                extractedCount++
                Timber.tag(TAG).d("Extracted ZIP mod: ${zipFile.name} in ${itemDir.name}")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to extract ZIP: ${zipFile.name} in ${itemDir.name}")
            }
        }
        if (extractedCount > 0) {
            Timber.tag(TAG).i("Extracted $extractedCount ZIP workshop mods")
        }
    }

    /**
     * Detects workshop content files that are missing their file extension and
     * renames them based on magic-byte signatures.
     *
     * CDN-downloaded files often have numeric filenames (the published file ID)
     * with no extension. Games like GMod scan for `.gma` files by extension, so
     * files without the correct extension are invisible to the game even though
     * they contain valid content.
     *
     * Supported formats:
     *  - GMA (Garry's Mod Addon): magic `GMAD` (47 4D 41 44)
     *  - VPK (Valve Pak):         magic 34 12 AA 55
     *  - BSP (Source Map):        magic `VBSP` (56 42 53 50)
     *  - ZIP/PK archives:        magic `PK` (50 4B 03 04)
     */
    fun fixFileExtensions(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var fixedCount = 0
        var scannedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach

            itemDir.listFiles()?.forEach fileLoop@{ file ->
                if (!file.isFile || file.name.startsWith(".")) return@fileLoop
                scannedCount++
                // Skip files that already have a recognized extension
                val ext = file.extension.lowercase()
                if (ext in WorkshopItem.KNOWN_EXTENSIONS) return@fileLoop

                // Read first 4 bytes for magic detection
                val magic = ByteArray(4)
                val bytesRead = try {
                    file.inputStream().use { it.read(magic) }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to read magic bytes from ${file.name}: ${e.message}")
                    return@fileLoop
                }
                if (bytesRead < 4) return@fileLoop

                val detectedExt = detectExtension(magic)
                if (detectedExt == null) {
                    Timber.tag(TAG).d("Unknown magic bytes for ${file.name} (ext=$ext): ${magic.joinToString(" ") { "%02x".format(it) }}")
                    return@fileLoop
                }

                // If the current extension looks like a truncated version of the
                // detected one (e.g. ".gm" is a prefix of "gma"), replace it.
                // Otherwise append the detected extension.
                val currentExt = file.extension.lowercase()
                val newName = if (currentExt.isNotEmpty() && detectedExt.startsWith(currentExt) && currentExt != detectedExt) {
                    file.nameWithoutExtension + "." + detectedExt
                } else {
                    file.name + "." + detectedExt
                }
                val newFile = File(file.parentFile, newName)
                if (newFile.exists()) {
                    Timber.tag(TAG).d("Target already exists, skipping: ${file.name} → ${newFile.name}")
                    return@fileLoop
                }

                try {
                    Files.move(file.toPath(), newFile.toPath())
                    fixedCount++
                    Timber.tag(TAG).d(
                        "Fixed extension: ${file.name} → ${newFile.name} in ${itemDir.name}"
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to rename ${file.absolutePath} → ${newFile.name}")
                }
            }
        }
        if (fixedCount > 0) {
            Timber.tag(TAG).i("Fixed $fixedCount/$scannedCount workshop file extensions via magic-byte detection")
        } else {
            Timber.tag(TAG).i("fixFileExtensions: scanned $scannedCount files, none needed fixing")
        }
    }

    private fun detectExtension(magic: ByteArray): String? {
        // GMA: "GMAD" (0x47 0x4D 0x41 0x44)
        if (magic[0] == 0x47.toByte() && magic[1] == 0x4D.toByte() &&
            magic[2] == 0x41.toByte() && magic[3] == 0x44.toByte()) return "gma"
        // VPK: 0x34 0x12 0xAA 0x55
        if (magic[0] == 0x34.toByte() && magic[1] == 0x12.toByte() &&
            magic[2] == 0xAA.toByte() && magic[3] == 0x55.toByte()) return "vpk"
        // BSP: "VBSP" (0x56 0x42 0x53 0x50)
        if (magic[0] == 0x56.toByte() && magic[1] == 0x42.toByte() &&
            magic[2] == 0x53.toByte() && magic[3] == 0x50.toByte()) return "bsp"
        // ZIP/PK: "PK\x03\x04" (0x50 0x4B 0x03 0x04)
        if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) return "zip"
        return null
    }

    /**
     * Decompresses LZMA-compressed workshop content files.
     *
     * Steam's CDN stores many workshop items in raw LZMA format. When
     * downloaded via DepotDownloader, these files retain their LZMA
     * compression. Games expect decompressed content (e.g. GMA files for
     * Source engine games need a `GMAD` header, not an LZMA stream).
     *
     * Detects LZMA by checking if the first byte is `0x5D` (the most common
     * LZMA properties byte used by Steam). Decompresses in parallel using
     * coroutines with a 256KB buffer for throughput.
     */
    suspend fun decompressLzmaFiles(
        workshopContentDir: File,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ) {
        if (!workshopContentDir.exists()) return

        // First pass: find all LZMA-compressed files
        val lzmaFiles = withContext(Dispatchers.IO) {
            val files = mutableListOf<File>()
            workshopContentDir.listFiles()?.forEach { itemDir ->
                if (!itemDir.isDirectory) return@forEach
                itemDir.listFiles()?.forEach { file ->
                    if (!file.isFile || file.name.startsWith(".")) return@forEach
                    val firstByte = file.inputStream().use { it.read() }
                    if (firstByte == 0x5D) files.add(file)
                }
            }
            files
        }

        if (lzmaFiles.isEmpty()) return
        Timber.tag(TAG).i("Found ${lzmaFiles.size} LZMA-compressed workshop files to decompress")

        val completed = AtomicInteger(0)
        val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val semaphore = Semaphore(concurrency)

        coroutineScope {
            lzmaFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val tmpFile = File(file.parentFile, file.name + ".lzma_tmp")
                        try {
                            file.inputStream().buffered(262144).use { fis ->
                                LZMAInputStream(fis).use { lzma ->
                                    tmpFile.outputStream().buffered(262144).use { out ->
                                        lzma.copyTo(out, 262144)
                                    }
                                }
                            }
                            if (tmpFile.length() > 0) {
                                tmpFile.renameTo(file)
                                Timber.tag(TAG).i(
                                    "Decompressed LZMA: ${file.name} in ${file.parentFile?.name}"
                                )
                            } else {
                                tmpFile.delete()
                            }
                        } catch (e: Exception) {
                            tmpFile.delete()
                            Timber.tag(TAG).d(
                                "Skipping ${file.name} in ${file.parentFile?.name}: not valid LZMA"
                            )
                        }
                        val done = completed.incrementAndGet()
                        onProgress?.invoke(done, lzmaFiles.size)
                    }
                }
            }.awaitAll()
        }

        Timber.tag(TAG).i("Decompressed ${completed.get()}/${lzmaFiles.size} LZMA workshop files")
    }

    /**
     * Downloads all given Workshop items using DepotDownloader's PubFileItem support.
     *
     * Items are downloaded concurrently (limited by a semaphore based on the user's
     * speed setting) for significantly better throughput. Each item writes a
     * `.workshop_complete` marker on success for crash recovery.
     *
     * @param items Workshop items to download
     * @param steamClient Active SteamClient instance
     * @param licenses User's license list for CDN access
     * @param workshopContentDir The workshop/content/<appId>/ directory
     * @param onItemProgress Called with (completedCount, totalItems, lastTitle) as items finish
     * @param onBytesProgress Called with (totalDownloaded, totalEstimated) across all active items
     * @return Number of items successfully downloaded
     */
    suspend fun downloadItems(
        items: List<WorkshopItem>,
        steamClient: SteamClient,
        licenses: List<License>,
        workshopContentDir: File,
        onItemProgress: (completed: Int, total: Int, currentTitle: String) -> Unit,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Int = coroutineScope {
        workshopContentDir.mkdirs()

        val totalItems = items.size
        val completedCount = AtomicInteger(0)

        // Concurrent download limit based on speed setting
        val concurrentLimit = when (PrefManager.downloadSpeed) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> 2
        }

        // Pre-compute total bytes across all items for a stable progress total
        val fixedTotalBytes = items.sumOf { it.fileSizeBytes }

        // Track downloaded bytes for aggregate display
        // HTTP items use publishedFileId as key; depot batches use negative indices
        val bytesDownloadedMap = ConcurrentHashMap<Long, Long>()

        onItemProgress(0, totalItems, items.firstOrNull()?.title ?: "")

        // Split items by download path
        val httpItems = items.filter { it.fileUrl.isNotEmpty() }
        val depotItems = items.filter { it.fileUrl.isEmpty() }

        Timber.tag(TAG).i(
            "[DL] downloadItems: ${items.size} total, ${httpItems.size} HTTP, " +
                "${depotItems.size} depot, concurrency=$concurrentLimit"
        )

        val allJobs = mutableListOf<Deferred<Boolean>>()

        // Single shared semaphore for both HTTP and depot downloads.
        // Using separate semaphores would waste concurrency budget when all
        // items use the same download path (e.g. all depot, no HTTP).
        val downloadSemaphore = Semaphore(concurrentLimit)

        // === HTTP items: download concurrently via semaphore ===
        if (httpItems.isNotEmpty()) {
            httpItems.mapTo(allJobs) { item ->
                async {
                    val itemDir = File(workshopContentDir, item.publishedFileId.toString())
                    val completeMarker = File(itemDir, COMPLETE_MARKER)
                    try {
                        // Only the actual download needs the semaphore permit.
                        // Preview images are cosmetic and must not block other
                        // items from starting their downloads.
                        downloadSemaphore.withPermit {
                            ensureActive()
                            Timber.tag(TAG).i(
                                "Downloading Workshop item (HTTP): '${item.title}' " +
                                    "(${item.publishedFileId})"
                            )
                            completeMarker.delete()
                            bytesDownloadedMap[item.publishedFileId] = 0L

                            downloadViaHttp(item, itemDir.absolutePath) { downloaded, _ ->
                                bytesDownloadedMap[item.publishedFileId] = downloaded
                                onBytesProgress(bytesDownloadedMap.values.sum(), fixedTotalBytes)
                            }

                            itemDir.mkdirs()
                            // Write marker immediately — mod content is on disk.
                            completeMarker.writeText(item.timeUpdated.toString())
                        }
                        // Semaphore released — progress update + preview don't
                        // block other downloads from starting.
                        val done = completedCount.incrementAndGet()
                        Timber.tag(TAG).i(
                            "Workshop item $done/$totalItems completed: '${item.title}'"
                        )
                        onItemProgress(done, totalItems, item.title)
                        downloadPreviewImage(item, itemDir)
                        true
                    } catch (e: CancellationException) {
                        if (!isActive) {
                            // Parent scope cancelled (user paused)
                            Timber.tag(TAG).w("[DL] HTTP download cancelled by user: '${item.title}'")
                            throw e
                        }
                        // Internal cancellation (DepotDownloader killed the job)
                        Timber.tag(TAG).e(e, "[DL] HTTP download internally cancelled: '${item.title}'")
                        false
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e, "Failed to download Workshop item '${item.title}' " +
                                "(${item.publishedFileId}), skipping"
                        )
                        false
                    }
                }
            }
        }

        // === Depot items: one DepotDownloader per item ===
        // DepotDownloader's internal pipeline shuts down after the first
        // PubFileItem completes.  Process depot items one at a time.
        // To eliminate the ~3s gap (Steam API manifest resolution),
        // pre-start the NEXT item's DepotDownloader while the current
        // one is still downloading chunks.
        if (depotItems.isNotEmpty()) {
            patchSupportedWorkshopFileTypes()
            val (maxDownloads, maxDecompress) = computeDownloadThreads()

            allJobs += async {
                val parentJob = coroutineContext[Job]

                // Creates, configures, and starts a DepotDownloader for
                // one item.  startDownloading() is non-blocking — it
                // kicks off the Steam API manifest resolution in the
                // background.
                fun startDepotDownload(
                    wsItem: WorkshopItem,
                ): Triple<DepotDownloader, File, File> {
                    val dir = File(workshopContentDir, wsItem.publishedFileId.toString())
                    val marker = File(dir, COMPLETE_MARKER)
                    // Don't delete the marker here — this may be a
                    // pipelined pre-start for the NEXT item. Deleting
                    // the marker now would lose "already complete" state
                    // if the user pauses before we get to this item.
                    // The marker is deleted just before we await().

                    val dd = DepotDownloader(
                        steamClient,
                        licenses,
                        debug = false,
                        androidEmulation = true,
                        maxDownloads = maxDownloads,
                        maxDecompress = maxDecompress,
                        parentJob = parentJob,
                        autoStartDownload = false,
                    )
                    dd.addListener(object : IDownloadListener {
                        override fun onDownloadStarted(dl: DownloadItem) {
                            Timber.tag(TAG).i("[DL] onDownloadStarted: $dl")
                        }
                        override fun onDownloadCompleted(dl: DownloadItem) {
                            Timber.tag(TAG).i("[DL] onDownloadCompleted: $dl")
                        }
                        override fun onDownloadFailed(dl: DownloadItem, error: Throwable) {
                            Timber.tag(TAG).e(error, "[DL] onDownloadFailed: $dl")
                        }
                        override fun onStatusUpdate(message: String) {
                            Timber.tag(TAG).d("[DL] onStatusUpdate: $message")
                        }
                        override fun onChunkCompleted(
                            depotId: Int,
                            depotPercentComplete: Float,
                            compressedBytes: Long,
                            uncompressedBytes: Long,
                        ) {
                            bytesDownloadedMap[wsItem.publishedFileId] = uncompressedBytes
                            onBytesProgress(
                                bytesDownloadedMap.values.sum(),
                                fixedTotalBytes,
                            )
                        }
                        override fun onDepotCompleted(
                            depotId: Int,
                            compressedBytes: Long,
                            uncompressedBytes: Long,
                        ) {
                            Timber.tag(TAG).i(
                                "[DL] onDepotCompleted: depot=$depotId " +
                                    "compressed=${compressedBytes / 1024}KB " +
                                    "uncompressed=${uncompressedBytes / 1024}KB"
                            )
                        }
                    })
                    dd.add(
                        PubFileItem(
                            appId = wsItem.appId,
                            pubFile = wsItem.publishedFileId,
                            installDirectory = dir.absolutePath,
                        )
                    )
                    dd.finishAdding()
                    dd.startDownloading()
                    return Triple(dd, dir, marker)
                }

                // Pipeline: pre-start next item while current downloads.
                var pending: Triple<DepotDownloader, File, File>? = null
                // Defer close() calls until the entire loop finishes.
                // Closing a DD while another DD shares the same
                // SteamClient can corrupt the concurrent download.
                val openedDDs = mutableListOf<DepotDownloader>()

                try {
                for ((index, item) in depotItems.withIndex()) {
                    ensureActive()

                    val (depotDownloader, itemDir, completeMarker) =
                        pending ?: startDepotDownload(item)
                    pending = null
                    openedDDs.add(depotDownloader)

                    Timber.tag(TAG).i(
                        "[DL] Downloading depot item: '${item.title}' " +
                            "(${item.publishedFileId})"
                    )

                    // Pre-start the NEXT item so its API call overlaps
                    // with this item's chunk download.
                    if (index + 1 < depotItems.size) {
                        pending = startDepotDownload(depotItems[index + 1])
                    }

                    try {
                        // Delete marker now that we're committed to downloading
                        // this item. (Not earlier, to avoid losing state for
                        // pipelined items if the user pauses.)
                        completeMarker.delete()

                        val timeoutMs = computeDownloadTimeout(item.fileSizeBytes)
                        // getCompletion() returns CompletableFuture<Void>
                        // whose await() always returns null. Use withTimeout
                        // so we can distinguish real timeouts from completion.
                        try {
                            withTimeout(timeoutMs) {
                                depotDownloader.getCompletion().await()
                            }
                        } catch (_: TimeoutCancellationException) {
                            Timber.tag(TAG).w(
                                "[DL] Depot download timed out: '${item.title}'"
                            )
                        }

                        // Write marker if content was downloaded
                        val hasContent = itemDir.exists() &&
                            (itemDir.listFiles()?.any { !it.name.startsWith(".") } == true)
                        if (hasContent) {
                            completeMarker.writeText(item.timeUpdated.toString())
                            File(workshopContentDir, "${item.publishedFileId}.partial")
                                .deleteRecursively()
                            val done = completedCount.incrementAndGet()
                            Timber.tag(TAG).i(
                                "Workshop item $done/$totalItems completed: '${item.title}'"
                            )
                            onItemProgress(done, totalItems, item.title)
                            launch { downloadPreviewImage(item, itemDir) }
                        }
                    } catch (e: CancellationException) {
                        pending?.let { (dd, _, _) ->
                            openedDDs.add(dd)
                        }
                        pending = null
                        Timber.tag(TAG).w("[DL] Depot download cancelled: '${item.title}'")
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e, "[DL] Depot download failed: '${item.title}', skipping"
                        )
                    }
                }
                } finally {
                    // Close any pre-started DD that wasn't consumed.
                    pending?.let { (dd, _, _) -> openedDDs.add(dd) }
                    // Close all DDs now that the loop is done.
                    for (dd in openedDDs) {
                        Thread { try { dd.close() } catch (_: Exception) {} }.start()
                    }
                }
                true
            }
        }

        allJobs.awaitAll()
        val successCount = completedCount.get()

        Timber.tag(TAG).i(
            "Workshop download complete: $successCount/$totalItems items succeeded"
        )

        return@coroutineScope successCount
    }


    /** Timeout per item: 3 min base + 30s per MB, capped at 2 hours. */
    private fun computeDownloadTimeout(fileSizeBytes: Long): Long {
        val baseMins = 3L
        val sizeMb = fileSizeBytes / (1024 * 1024)
        val extraMins = (sizeMb * 30) / 60  // 30s per MB
        return ((baseMins + extraMins).coerceIn(3, 120)) * 60 * 1000
    }

    private fun computeDownloadThreads(): Pair<Int, Int> {
        var downloadRatio = 1.5
        var decompressRatio = 0.5
        when (PrefManager.downloadSpeed) {
            8 -> { downloadRatio = 0.6; decompressRatio = 0.2 }
            16 -> { downloadRatio = 1.2; decompressRatio = 0.4 }
            24 -> { downloadRatio = 1.5; decompressRatio = 0.5 }
            32 -> { downloadRatio = 2.4; decompressRatio = 0.8 }
        }
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxDownloads = (cpuCores * downloadRatio).toInt().coerceAtLeast(1)
        val maxDecompress = (cpuCores * decompressRatio).toInt().coerceAtLeast(1)
        Timber.tag(TAG).d(
            "Download speed setting=${PrefManager.downloadSpeed}, cpuCores=$cpuCores, " +
                "maxDownloads=$maxDownloads, maxDecompress=$maxDecompress"
        )
        return maxDownloads to maxDecompress
    }

    /**
     * Downloads the preview image for a workshop item if available.
     * Saved as preview.jpg (or matching extension) in the item directory
     * so that in-game mod managers can display it.
     */
    private suspend fun downloadPreviewImage(item: WorkshopItem, itemDir: File) {
        if (item.previewUrl.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val ext = item.previewUrl.substringAfterLast('.').substringBefore('?')
                    .lowercase().let { if (it in listOf("jpg", "jpeg", "png", "gif")) it else "jpg" }
                val previewFile = File(itemDir, "preview.$ext")
                if (previewFile.exists()) return@withContext

                val request = Request.Builder().url(item.previewUrl).build()
                Net.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    val body = response.body ?: return@withContext
                    body.byteStream().use { input ->
                        previewFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Timber.tag(TAG).d("Preview image saved for '${item.title}'")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to download preview image for '${item.title}'")
            }
        }
    }

    /**
     * Downloads a workshop item directly from its fileUrl via HTTP.
     * Used for legacy web-hosted items (common in L4D2, Torchlight 2, etc.)
     * Supports resuming partial downloads via HTTP Range headers.
     */
    private suspend fun downloadViaHttp(
        item: WorkshopItem,
        installDirectory: String,
        onBytesProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val itemDir = File(installDirectory)
        itemDir.mkdirs()

        // Use the original filename from Steam's API (e.g. "my_mod.vpk").
        // This is critical — games like L4D2 scan for .vpk files by extension,
        // and Skyrim needs .bsa/.esp extensions to load mods.
        // Strip any directory prefix (e.g. "myl4d2addons/mod.vpk" → "mod.vpk").
        val fileName = item.fileName.substringAfterLast('/').ifEmpty {
            item.fileUrl.substringAfterLast('/').substringBefore('?')
                .ifEmpty { item.publishedFileId.toString() }
        }
        val outputFile = File(itemDir, fileName)

        // Check for existing partial download to resume
        var existingBytes = 0L
        if (outputFile.isFile && outputFile.length() > 0) {
            existingBytes = outputFile.length()
            Timber.tag(TAG).d(
                "Resuming HTTP download for '${item.title}' from byte $existingBytes"
            )
        }

        val requestBuilder = Request.Builder().url(item.fileUrl)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        Timber.tag(TAG).d("HTTP download starting: '${item.title}' from ${item.fileUrl.take(80)}")

        Net.http.newCall(requestBuilder.build()).execute().use { response ->
            // 206 = partial content (resume), 200 = full content (server ignored Range)
            val isResuming = response.code == 206 && existingBytes > 0

            // 416 = Range Not Satisfiable → the partial file is already
            // complete (or bigger than the remote). Accept it as-is.
            if (response.code == 416 && existingBytes > 0) {
                Timber.tag(TAG).i(
                    "HTTP 416 for '${item.title}' — partial file is already complete " +
                        "(${existingBytes} bytes)"
                )
                onBytesProgress(existingBytes, existingBytes)
                return@withContext
            }

            if (!response.isSuccessful) {
                throw WorkshopDownloadException(
                    "HTTP ${response.code} for '${item.title}' (${item.publishedFileId})"
                )
            }

            val body = response.body
                ?: throw WorkshopDownloadException("Empty response body for '${item.title}'")

            // If server returned 200 instead of 206, it doesn't support Range —
            // discard the partial file and download from scratch.
            val resumeOffset = if (isResuming) existingBytes else 0L
            val totalBytes = if (isResuming) {
                existingBytes + (body.contentLength().let { if (it > 0) it else (item.fileSizeBytes - existingBytes) })
            } else {
                body.contentLength().let { if (it > 0) it else item.fileSizeBytes }
            }

            body.byteStream().use { input ->
                // Append when resuming, overwrite when starting fresh
                BufferedOutputStream(
                    java.io.FileOutputStream(outputFile, isResuming)
                ).use { output ->
                    val buffer = ByteArray(262144)
                    var downloaded = resumeOffset
                    var lastProgressUpdate = 0L

                    while (true) {
                        ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        // Throttle progress updates to ~10 per second
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            onBytesProgress(downloaded, totalBytes)
                            lastProgressUpdate = now
                        }
                    }

                    onBytesProgress(downloaded, totalBytes)
                    Timber.tag(TAG).d(
                        "HTTP download completed: '${item.title}' — ${downloaded / 1024}KB" +
                            if (resumeOffset > 0) " (resumed from ${resumeOffset / 1024}KB)" else ""
                    )
                }
            }
        }
    }


    /**
     * Gets the workshop content directory path for gbe_fork steamclient mode.
     *
     * Path: <winePrefix>/drive_c/Program Files (x86)/Steam/steamapps/workshop/content/<appId>/
     *
     * @param winePrefix The absolute path to the wine prefix (e.g. /data/.../imagefs/.wine)
     * @param appId The Steam app ID
     */
    fun getWorkshopContentDir(winePrefix: String, appId: Int): File {
        return File(
            winePrefix,
            "drive_c/Program Files (x86)/Steam/steamapps/workshop/content/$appId"
        )
    }

    /**
     * Resolves the wine prefix for a specific Steam game's container,
     * bypassing the `xuser` symlink.  This ensures workshop content is
     * read from / written to the game's own container directory regardless
     * of which container is currently active (the `xuser` symlink can
     * point to any game's container).
     */
    private fun getContainerWinePrefix(context: Context, appId: Int): String {
        val imageFs = ImageFs.find(context)
        val homeDir = File(imageFs.rootDir, "home")
        val containerDir = File(homeDir, "${ImageFs.USER}-STEAM_$appId")
        return File(containerDir, ".wine").absolutePath
    }

    /**
     * Deletes all downloaded workshop mods for the given container and
     * cleans up any symlinks/copies installed into the game tree.
     *
     * @param context Android context for resolving the wine prefix
     * @param containerId The container ID string (e.g. "STEAM_123456")
     * @param gameRootDir Optional game install dir; when provided, gbe_fork
     *   mods symlinks and strategy-detected entries are cleaned up too.
     * @param gameName Optional game name for strategy detection.
     */
    fun deleteWorkshopMods(
        context: Context,
        containerId: String,
        gameRootDir: File? = null,
        gameName: String = "",
    ) {
        val gameId = ContainerUtils.extractGameIdFromContainerId(containerId)
        val winePrefix = getContainerWinePrefix(context, gameId)
        val workshopDir = getWorkshopContentDir(winePrefix, gameId)

        // Clean up installed mod entries (symlinks/copies) in the game tree
        // before deleting the content dir, so isOurSymlink checks still work.
        if (gameRootDir != null) {
            cleanupInstalledModEntries(gameRootDir, workshopDir, winePrefix, gameName)
        }

        // Also clear the global Steam steam_settings/mods.json.
        // configureModSymlinks populates this for ColdClient games,
        // but cleanupInstalledModEntries only walks gameRootDir and
        // misses the global Steam root.
        val globalModsJson = workshopDir.parentFile  // content/
            ?.parentFile  // workshop/
            ?.parentFile  // steamapps/
            ?.parentFile  // Steam/
            ?.let { File(it, "steam_settings/mods.json") }
        if (globalModsJson != null && globalModsJson.isFile) {
            globalModsJson.writeText("{}")
            Timber.tag(TAG).d("Cleared global mods.json at ${globalModsJson.absolutePath}")
        }
        val globalModImages = globalModsJson?.parentFile?.let { File(it, "mod_images") }
        if (globalModImages != null && globalModImages.isDirectory) {
            globalModImages.deleteRecursively()
            Timber.tag(TAG).d("Cleared global mod_images at ${globalModImages.absolutePath}")
        }

        if (workshopDir.exists()) {
            workshopDir.deleteRecursively()
        }
    }

    /**
     * Removes workshop-owned symlinks and copies from the game tree.
     * Handles gbe_fork steam_settings/mods/ dirs and strategy-detected
     * game mod directories.
     */
    private fun cleanupInstalledModEntries(
        gameRootDir: File,
        workshopContentDir: File,
        winePrefix: String,
        gameName: String,
    ) {
        val workshopBase = workshopContentDir.parentFile ?: workshopContentDir

        // Phase 1: Clean gbe_fork steam_settings/mods/ symlinks
        val dllNames = setOf(
            "steam_api.dll", "steam_api64.dll",
            "steamclient.dll", "steamclient64.dll",
        )
        gameRootDir.walkTopDown().maxDepth(10).forEach { file ->
            if (!file.isFile || file.name.lowercase() !in dllNames) return@forEach
            val modsDir = file.parentFile?.let { File(File(it, "steam_settings"), "mods") }
                ?: return@forEach
            if (modsDir.isDirectory) {
                clearModEntries(modsDir)
                Timber.tag(TAG).d("Cleared gbe_fork mods at ${modsDir.absolutePath}")
            }
            val settingsDir = modsDir.parentFile
            val modsJson = File(settingsDir, "mods.json")
            if (modsJson.isFile) modsJson.writeText("{}")
            val modImagesDir = File(settingsDir, "mod_images")
            if (modImagesDir.isDirectory) modImagesDir.deleteRecursively()
        }

        // Phase 2: Clean strategy-detected game mod directories
        if (winePrefix.isNotEmpty()) {
            try {
                val detection = getOrDetectStrategy(gameRootDir, winePrefix, gameName)
                val strategy = detection.strategy
                if (strategy is WorkshopModPathStrategy.SymlinkIntoDir ||
                    strategy is WorkshopModPathStrategy.CopyIntoDir
                ) {
                    val targetDirs = when (strategy) {
                        is WorkshopModPathStrategy.SymlinkIntoDir -> strategy.effectiveDirs
                        is WorkshopModPathStrategy.CopyIntoDir -> strategy.effectiveDirs
                        else -> emptyList()
                    }
                    val symlinker = WorkshopSymlinker()
                    symlinker.sync(strategy, emptyMap(), workshopBase)
                    Timber.tag(TAG).d(
                        "Cleaned mod entries from ${targetDirs.size} game dir(s)"
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Strategy-based cleanup failed")
            }

            // Phase 3: Clean Unity AppData targets
            try {
                val unityTargets = detectUnityModTargets(gameRootDir, winePrefix).toMutableList()
                // Also include auto-created peer dirs (e.g. "Dreams/") that
                // routeItemsToUnityTargets may have created alongside "Models/".
                val autoCreatedPeers = listOf("Dreams")
                unityTargets.toList().forEach { target ->
                    if (target.name.equals("Models", ignoreCase = true)) {
                        autoCreatedPeers.forEach { peerName ->
                            val peer = File(target.parentFile, peerName)
                            if (peer.isDirectory && peer !in unityTargets) {
                                unityTargets.add(peer)
                            }
                        }
                    }
                }
                if (unityTargets.isNotEmpty()) {
                    val symlinker = WorkshopSymlinker()
                    symlinker.sync(
                        WorkshopModPathStrategy.SymlinkIntoDir(unityTargets),
                        emptyMap(), workshopBase,
                    )
                    Timber.tag(TAG).d("Cleaned Unity AppData mod entries")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unity AppData cleanup failed")
            }
        }

        // Phase 4: Clean Skyrim Data/ manifest-tracked files (ESP/ESM/BSA)
        val dataDir = File(gameRootDir, "Data")
        val skyrimManifest = File(dataDir, ".gamenative_workshop_files")
        if (dataDir.isDirectory && skyrimManifest.isFile) {
            try {
                syncSkyrimWorkshopMods(gameRootDir, emptyList(), winePrefix)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skyrim manifest cleanup failed")
            }
        }

        // Phase 5: Clean Source engine VPK/GMA from addons/ and BSPs from maps/workshop/
        if (isSourceEngine(gameRootDir)) {
            // Clean addons/ and custom/ directories using manifests
            for (dirName in listOf("addons", "custom")) {
                gameRootDir.walkTopDown().maxDepth(5).forEach { dir ->
                    if (!dir.isDirectory || dir.name != dirName) return@forEach
                    if (dir.absolutePath.contains("steam_settings")) return@forEach
                    val manifestFile = File(dir, ".gamenative_workshop_addons")
                    if (manifestFile.isFile) {
                        manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                            try { Files.deleteIfExists(File(dir, name).toPath()) } catch (_: Exception) { }
                        }
                        manifestFile.delete()
                    }
                    // Also remove any symlinks (from older code)
                    dir.listFiles()?.forEach { f ->
                        if (Files.isSymbolicLink(f.toPath())) {
                            Files.deleteIfExists(f.toPath())
                        }
                    }
                }
            }
            gameRootDir.walkTopDown().maxDepth(5).forEach { dir ->
                if (!dir.isDirectory || dir.name != "maps") return@forEach
                if (dir.absolutePath.contains("steam_settings")) return@forEach
                val workshopMapsDir = File(dir, "workshop")
                if (!workshopMapsDir.isDirectory) return@forEach
                workshopMapsDir.listFiles()?.forEach { f ->
                    if (Files.isSymbolicLink(f.toPath())) {
                        Files.deleteIfExists(f.toPath())
                    } else if (f.isDirectory) {
                        f.listFiles()?.forEach { inner -> Files.deleteIfExists(inner.toPath()) }
                        if (f.listFiles()?.isEmpty() == true) f.delete()
                    }
                }
            }
            Timber.tag(TAG).d("Cleaned Source engine workshop artifacts")
        }
    }

    /**
     * Reverts any previous gameinfo.txt patch that added a workshop_mods
     * search path. This undoes changes from earlier versions that injected
     * search paths, which caused localization issues in Source engine games.
     */
    private fun revertGameInfoPatch(gameInfoFile: File) {
        try {
            val content = gameInfoFile.readText()
            if (!content.contains("workshop_mods")) return

            val reverted = content.lines()
                .filterNot { it.contains("workshop_mods") }
                .joinToString("\n")
            gameInfoFile.writeText(reverted)
            Timber.tag(TAG).i("Reverted workshop_mods patch from ${gameInfoFile.name}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to revert gameinfo.txt at ${gameInfoFile.absolutePath}")
        }
    }

    /**
     * Finds a preview image file in a workshop item directory.
     * Returns the first file matching `preview.{jpg,jpeg,png,gif}`, or null.
     */
    private fun findPreviewImage(itemDir: File): File? =
        itemDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("preview.", ignoreCase = true) &&
                it.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif")
        }

    /** Returns true if [gameRootDir] contains a gameinfo.txt (Source engine marker). */
    private fun isSourceEngine(gameRootDir: File): Boolean =
        gameRootDir.walkTopDown().maxDepth(5)
            .any { it.isFile && it.name == "gameinfo.txt" && !it.absolutePath.contains("steam_settings") }

    /**
     * Copies preview images from workshop item directories into a
     * `mod_images/<itemId>/` tree under [settingsDir] for gbe_fork.
     */
    private fun copyPreviewImages(modDirs: List<File>, settingsDir: File) {
        val modImagesDir = File(settingsDir, "mod_images")
        modDirs.forEach { itemDir ->
            val previewFile = findPreviewImage(itemDir) ?: return@forEach
            val itemImagesDir = File(modImagesDir, itemDir.name)
            itemImagesDir.mkdirs()
            materializeWorkshopFile(previewFile, File(itemImagesDir, previewFile.name))
        }
    }

    /**
     * Builds a mods.json JSON object for gbe_fork.
     *
     * Format: `{"<modId>": {"title": "...", "primary_filename": "...", "path": "..."}, ...}`.
     * The `primary_filename` field tells gbe_fork which file is the primary
     * content file. The `path` field is set to the explicit Windows path
     * (under C: drive) so gbe_fork returns it directly to games, bypassing
     * any symlink resolution issues.
     */
    private fun buildModsJson(modDirs: List<File>, items: List<WorkshopItem>): JSONObject {
        val itemsById = items.associateBy { it.publishedFileId }
        val modsObj = JSONObject()
        modDirs.forEach { itemDir ->
            val id = itemDir.name.toLongOrNull() ?: return@forEach
            val item = itemsById[id]
            val entry = JSONObject()
            entry.put("title", item?.title ?: itemDir.name)

            // Find the primary content file (first non-hidden file)
            val contentFile = itemDir.listFiles()
                ?.firstOrNull { it.isFile && !it.name.startsWith(".") }
            if (contentFile != null) {
                entry.put("primary_filename", contentFile.name)
                entry.put("primary_filesize", contentFile.length())
            }

            // total_files_sizes: gbe_fork uses this for GetItemInstallInfo::punSizeOnDisk
            // Walk recursively to include subdirectory files (e.g. Terraria Content/)
            val totalSize = itemDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .sumOf { it.length() }
            entry.put("total_files_sizes", totalSize)

            // time_updated: gbe_fork uses this for GetItemInstallInfo::punTimeStamp
            // Must be a JSON number (not a string) — gbe_fork's nlohmann::json
            // throws type_error when trying to read a string as uint32, which
            // silently skips the entire mod entry.
            if (item != null && item.timeUpdated > 0) {
                entry.put("time_updated", item.timeUpdated)
            }

            // Provide explicit Windows path so gbe_fork returns it directly
            // to games, avoiding symlink-through-Wine resolution issues
            val windowsPath = toWindowsPath(itemDir.absolutePath)
            if (windowsPath != null) {
                entry.put("path", windowsPath)
            }

            // preview_filename: gbe_fork reads this to serve preview images
            // to games via GetQueryUGCPreviewURL / UGCDownload. The actual
            // image file is placed in steam_settings/mod_images/<itemId>/.
            val previewFile = findPreviewImage(itemDir)
            if (previewFile != null) {
                entry.put("preview_filename", previewFile.name)
            }

            // Enriched metadata: gbe_fork's GetPublishedFileDetails() and
            // ISteamUGC queries read these fields from mods.json to populate
            // item details returned to games (fixes "no item details" for
            // games like Dimension Jump).
            if (item != null) {
                if (item.description.isNotEmpty()) {
                    entry.put("description", item.description)
                }
                if (item.tags.isNotEmpty()) {
                    entry.put("tags", item.tags)
                }
                if (item.previewUrl.isNotEmpty()) {
                    entry.put("preview_url", item.previewUrl)
                }
                entry.put(
                    "workshop_item_url",
                    "https://steamcommunity.com/sharedfiles/filedetails/?id=${item.publishedFileId}"
                )
            }

            modsObj.put(itemDir.name, entry)
        }
        return modsObj
    }

    /**
     * Converts a Linux path under a Wine prefix to the equivalent Windows path.
     * E.g. `.../drive_c/Program Files (x86)/Steam/...` → `C:\Program Files (x86)\Steam\...`
     */
    private fun toWindowsPath(linuxPath: String): String? {
        val marker = "/drive_c/"
        val idx = linuxPath.indexOf(marker)
        if (idx < 0) return null
        val relative = linuxPath.substring(idx + marker.length)
        return "C:\\" + relative.replace('/', '\\')
    }

    /**
     * Materializes [source] at [destination] as a regular file path that games
     * can read reliably under Wine. Prefer hard links (cheap and not symlinks),
     * then fall back to copying if hard links are unavailable.
     */
    private fun materializeWorkshopFile(source: File, destination: File): Boolean {
        if (!source.isFile) return false
        destination.parentFile?.mkdirs()

        val destPath = destination.toPath()
        val srcPath = source.toPath()

        if (Files.exists(destPath)) {
            if (Files.isSymbolicLink(destPath)) {
                Files.deleteIfExists(destPath)
            } else {
                if (destination.isFile && destination.length() == source.length()) {
                    return false
                }
                Files.deleteIfExists(destPath)
            }
        }

        return try {
            Files.createLink(destPath, srcPath)
            true
        } catch (_: Exception) {
            Files.copy(srcPath, destPath)
            true
        }
    }

    // ── Wine user / AppData helpers ──────────────────────────────────────────

    private fun wineUserHome(winePrefix: String): File {
        val usersDir = File(winePrefix, "drive_c/users")
        if (!usersDir.isDirectory) return File(usersDir, "steamuser")
        // Pick the first non-Public user directory (Wine creates exactly one user)
        val userDir = usersDir.listFiles()
            ?.firstOrNull { it.isDirectory && !it.name.equals("Public", ignoreCase = true) }
        return userDir ?: File(usersDir, "steamuser")
    }

    private fun appDataRoaming(winePrefix: String): File =
        File(wineUserHome(winePrefix), "AppData/Roaming")

    private fun appDataLocal(winePrefix: String): File =
        File(wineUserHome(winePrefix), "AppData/Local")

    private fun appDataLocalLow(winePrefix: String): File =
        File(wineUserHome(winePrefix), "AppData/LocalLow")

    private fun documentsMyGames(winePrefix: String): File =
        File(wineUserHome(winePrefix), "Documents/My Games")

    private fun documentsDir(winePrefix: String): File =
        File(wineUserHome(winePrefix), "Documents")

    /**
     * Ensures steam_interfaces.txt includes interface versions that the
     * original DLL's regex-based scan may have missed.
     *
     * The initial scan only matched `Steam[A-Za-z]+[0-9]{3}` (e.g. SteamClient017)
     * but newer Steamworks SDK versions use a longer format:
     * `STEAMUGC_INTERFACE_VERSION017`, `STEAMREMOTESTORAGE_INTERFACE_VERSION016`.
     * Without these entries, gbe_fork doesn't initialize ISteamUGC and
     * GetNumSubscribedItems() returns 0.
     */
    private fun ensureInterfacesComplete(origDll: File, interfacesFile: File) {
        if (!origDll.isFile) return
        val existing = if (interfacesFile.isFile) {
            interfacesFile.readLines().map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        } else {
            mutableSetOf()
        }

        val shortPattern = Regex("^Steam[A-Za-z]+[0-9]{3}$", RegexOption.IGNORE_CASE)
        val longPattern = Regex("^STEAM[A-Z]+_INTERFACE_VERSION[0-9]+$")
        val newInterfaces = mutableSetOf<String>()

        val buf = ByteArray(65_536)
        val sb = StringBuilder(512)
        var total = 0L
        val maxBytes = 64L * 1024 * 1024

        fun flush() {
            val s = sb.toString().trimEnd()
            sb.clear()
            if (s.length < 10) return
            if (shortPattern.matches(s)) newInterfaces += s
            if (longPattern.matches(s)) newInterfaces += s
        }

        try {
            java.io.BufferedInputStream(java.io.FileInputStream(origDll), buf.size).use { st ->
                var r: Int
                while (st.read(buf).also { r = it } != -1) {
                    for (i in 0 until r) {
                        val b = buf[i].toInt() and 0xFF
                        if (b in 0x20..0x7E) sb.append(b.toChar()) else flush()
                    }
                    total += r
                    if (total >= maxBytes) break
                }
                flush()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("ensureInterfacesComplete scan error: ${e.message}")
            return
        }

        val added = newInterfaces.filter { it !in existing }
        if (added.isEmpty()) return

        val merged = (existing + newInterfaces).sorted()
        interfacesFile.writeText(merged.joinToString("\n") + "\n")
        Timber.tag(TAG).i("Updated steam_interfaces.txt with ${added.size} new entries: $added")
    }

    /**
     * Routes workshop items to the correct Unity AppData subdirectory target
     * based on their content. For example, Superliminal items with 3D model
     * files (`.obj`) go to `UGC/Models/`, while level/dream items go to
     * `UGC/Dreams/`.
     *
     * When a target category has no explicit directory yet, it is created
     * (e.g. `Dreams/` is auto-created as a peer of `Models/` for items
     * that don't contain 3D model files).
     */
    private fun routeItemsToUnityTargets(
        targets: List<File>,
        activeItemDirs: Map<Long, File>,
        workshopContentDir: File,
        titlesByItemId: Map<Long, String>,
        symlinker: WorkshopSymlinker,
    ) {
        // 3D model file extensions that indicate "Models" content
        val modelExtensions = setOf(
            "obj", "fbx", "blend", "dae", "stl", "ply", "3ds", "gltf", "glb",
        )

        val targetsByName = targets.associateBy { it.name.lowercase() }
        val modelTarget = targetsByName["models"]

        // Partition items: model-content items vs everything else
        val modelItems = mutableMapOf<Long, File>()
        val otherItems = mutableMapOf<Long, File>()
        for ((itemId, dir) in activeItemDirs) {
            val hasModelFiles = dir.listFiles()?.any { f ->
                f.isFile && f.extension.lowercase() in modelExtensions
            } == true
            if (hasModelFiles && modelTarget != null) {
                modelItems[itemId] = dir
            } else {
                otherItems[itemId] = dir
            }
        }

        // Route model items to Models/ target
        if (modelTarget != null && modelItems.isNotEmpty()) {
            val result = symlinker.sync(
                WorkshopModPathStrategy.SymlinkIntoDir(listOf(modelTarget)),
                modelItems, workshopContentDir, titlesByItemId,
            )
            Timber.tag(TAG).i("Unity route Models/: created=${result.created}")
        }

        // Route remaining items to the appropriate target
        if (otherItems.isNotEmpty()) {
            // Prefer an existing non-Models target (Dreams, Levels, etc.)
            val otherTarget = targets.firstOrNull {
                it.name.lowercase() != "models"
            } ?: if (modelTarget != null) {
                // No non-Models target exists; create "Dreams" as a peer
                File(modelTarget.parentFile, "Dreams").also { it.mkdirs() }
            } else {
                targets.first()
            }
            val result = symlinker.sync(
                WorkshopModPathStrategy.SymlinkIntoDir(listOf(otherTarget)),
                otherItems, workshopContentDir, titlesByItemId,
            )
            Timber.tag(TAG).i("Unity route ${otherTarget.name}/: created=${result.created}")
        }

        // Clean model items from the non-Models target and vice versa
        // (handled by symlinker.sync which removes stale entries)
    }

    /**
     * Detects Unity games via `*_Data/app.info` and returns the
     * `Application.persistentDataPath` directory with any mod-like
     * subdirectory structure mirrored from the install directory.
     *
     * Unity's persistentDataPath on Windows is
     * `%USERPROFILE%/AppData/LocalLow/<company>/<product>/`.
     * Games like Superliminal read workshop mods from subdirectories
     * there (e.g. UGC/Models/) rather than from the install directory.
     */
    private fun detectUnityModTargets(
        gameInstallDir: File,
        winePrefix: String,
    ): List<File> {
        val dataDir = gameInstallDir.listFiles()?.firstOrNull {
            it.isDirectory && it.name.endsWith("_Data", ignoreCase = true) &&
                File(it, "app.info").isFile
        } ?: return emptyList()

        val lines = runCatching { File(dataDir, "app.info").readLines() }.getOrNull()
            ?: return emptyList()
        if (lines.size < 2) return emptyList()
        val company = lines[0].trim()
        val product = lines[1].trim()
        if (company.isBlank() || product.isBlank()) return emptyList()

        val persistentPath = File(appDataLocalLow(winePrefix), "$company/$product")
        Timber.tag(TAG).i("Unity persistentDataPath: ${persistentPath.absolutePath}")

        val modDirNames = setOf(
            "mods", "mod", "addons", "addon", "plugins", "plugin",
            "workshop_mods", "usermods", "user_mods", "modules", "module", "ugc",
        )

        val targets = mutableListOf<File>()
        gameInstallDir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name.lowercase() !in modDirNames) return@forEach

            // Check for real (non-symlink) subdirectories within the mod
            // directory. Symlinks here are from our own auto-detect phase
            // and must be skipped to avoid treating them as game-defined
            // subdirectories (e.g. Superliminal UGC/Models/ vs UGC/Dreams/).
            // Only use install-dir subdirectories — AppData may contain stale
            // directories from previous runs that would be misidentified as
            // game-defined subdirs (e.g. "Companion Cube/" created by old
            // Phase 7 sync).
            val installSubDirs = child.listFiles()?.filter {
                it.isDirectory && !it.name.startsWith(".") &&
                    !Files.isSymbolicLink(it.toPath())
            } ?: emptyList()

            if (installSubDirs.isNotEmpty()) {
                installSubDirs.forEach { sub ->
                    targets.add(File(persistentPath, "${child.name}/${sub.name}"))
                }
            } else {
                targets.add(File(persistentPath, child.name))
            }
        }

        return targets
    }

    private fun syncSkyrimWorkshopMods(gameRootDir: File, modDirs: List<File>, winePrefix: String) {
        val dataDir = File(gameRootDir, "Data")
        if (!dataDir.isDirectory) return

        val pluginExts = setOf("esp", "esm", "esl")
        val archiveExts = setOf("bsa", "bsl")
        val allExts = pluginExts + archiveExts
        val activatedPlugins = linkedSetOf<String>()
        val manifestFile = File(dataDir, ".gamenative_workshop_files")

        // Remove files placed by previous workshop syncs (tracked by manifest)
        if (manifestFile.isFile) {
            val previousFiles = manifestFile.readText().lines().filter { it.isNotBlank() }
            previousFiles.forEach { name ->
                try {
                    Files.deleteIfExists(File(dataDir, name).toPath())
                } catch (_: Exception) { }
            }
        }

        // Clean stale numeric files left by older broken workshop sync runs,
        // and stale .ckm files leaked into Data/ by previous sync logic.
        dataDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val shouldDelete = f.name.toLongOrNull() != null ||
                f.extension.equals("ckm", ignoreCase = true)
            if (!shouldDelete) return@forEach
            try {
                Files.deleteIfExists(f.toPath())
            } catch (_: Exception) { }
        }

        val placedFiles = mutableListOf<String>()
        modDirs.forEach { itemDir ->
            itemDir.walkTopDown().maxDepth(4).forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name.startsWith(".")) return@forEach
                val ext = file.extension.lowercase()
                if (ext !in allExts) return@forEach

                val outFile = File(dataDir, file.name)
                materializeWorkshopFile(file, outFile)
                placedFiles.add(file.name)
                if (ext in pluginExts) {
                    activatedPlugins.add(file.name)
                }
            }
        }

        // Write manifest of files we placed so future syncs can clean them up
        if (placedFiles.isNotEmpty()) {
            manifestFile.writeText(placedFiles.joinToString("\n", postfix = "\n"))
        } else {
            manifestFile.delete()
        }

        val skyrimLocalDir = File(appDataLocal(winePrefix), "Skyrim")
        skyrimLocalDir.mkdirs()

        val basePlugins = listOf(
            "Skyrim.esm",
            "Update.esm",
            "Dawnguard.esm",
            "HearthFires.esm",
            "Dragonborn.esm",
        ).filter { File(dataDir, it).isFile }

        val finalOrder = (basePlugins + activatedPlugins.toList()).distinct()
        File(skyrimLocalDir, "plugins.txt").writeText(finalOrder.joinToString("\n", postfix = "\n"))
        File(skyrimLocalDir, "loadorder.txt").writeText(finalOrder.joinToString("\n", postfix = "\n"))

        Timber.tag(TAG).i("Skyrim workshop sync: ${activatedPlugins.size} plugins activated, ${placedFiles.size} files placed")
    }

    // ── Strategy cache ────────────────────────────────────────────────────────

    /** Bump when detection logic changes to invalidate all cached strategies. */
    private const val STRATEGY_CACHE_VERSION = 15

    private fun strategyCacheFile(gameRootDir: File): File =
        File(gameRootDir, ".gamenative_mod_strategy.json")

    private fun saveStrategyCache(
        gameRootDir: File,
        result: WorkshopModPathDetector.DetectionResult,
    ) {
        // Don't cache the "no signals found" fallback — game data directories
        // (e.g. Documents/.../Maps/) may not exist until after the first launch,
        // so re-detect on every launch until we get a meaningful result.
        if (result.strategy == WorkshopModPathStrategy.Standard &&
            result.confidence == WorkshopModPathDetector.Confidence.LOW
        ) {
            Timber.tag(TAG).d("Skipping cache for Standard/LOW (will re-detect next launch)")
            return
        }
        try {
            val dirs = when (val s = result.strategy) {
                is WorkshopModPathStrategy.SymlinkIntoDir -> s.targetDirs
                is WorkshopModPathStrategy.CopyIntoDir -> s.targetDirs
                else -> emptyList()
            }
            val fanOut = when (val s = result.strategy) {
                is WorkshopModPathStrategy.SymlinkIntoDir -> s.fanOut.name
                is WorkshopModPathStrategy.CopyIntoDir -> s.fanOut.name
                else -> null
            }
            val json = JSONObject().apply {
                put("version", STRATEGY_CACHE_VERSION)
                put("type", result.strategy::class.simpleName)
                put("confidence", result.confidence.name)
                put("reason", result.reason)
                if (dirs.isNotEmpty()) {
                    val arr = org.json.JSONArray()
                    dirs.forEach { arr.put(it.absolutePath) }
                    put("dirs", arr)
                }
                if (fanOut != null) put("fanOut", fanOut)
                put("stdSeen", result.stdSeen)
            }
            strategyCacheFile(gameRootDir).writeText(json.toString(2))
            Timber.tag(TAG).d("Saved strategy cache for ${gameRootDir.name}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save strategy cache")
        }
    }

    private fun loadCachedStrategy(gameRootDir: File): WorkshopModPathDetector.DetectionResult? {
        val file = strategyCacheFile(gameRootDir)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())

            // Invalidate cache if version doesn't match
            val version = json.optInt("version", 0)
            if (version != STRATEGY_CACHE_VERSION) {
                Timber.tag(TAG).i("Strategy cache version mismatch ($version != $STRATEGY_CACHE_VERSION), re-detecting")
                file.delete()
                return null
            }

            val type = json.getString("type")
            val confidence = WorkshopModPathDetector.Confidence.valueOf(json.getString("confidence"))
            val reason = json.optString("reason", "cached")

            val strategy = when (type) {
                "Standard" -> WorkshopModPathStrategy.Standard
                "SymlinkIntoDir" -> {
                    val arr = json.getJSONArray("dirs")
                    val dirs = (0 until arr.length()).map { File(arr.getString(it)) }
                    val fanOut = json.optString("fanOut", "PRIMARY_ONLY").let {
                        WorkshopModPathStrategy.FanOutPolicy.valueOf(it)
                    }
                    // Validate that at least the primary dir still exists
                    if (dirs.isEmpty() || dirs.first().parentFile?.isDirectory != true) {
                        Timber.tag(TAG).i("Cached strategy dirs invalid, re-detecting")
                        return null
                    }
                    WorkshopModPathStrategy.SymlinkIntoDir(dirs, fanOut)
                }
                "CopyIntoDir" -> {
                    val arr = json.getJSONArray("dirs")
                    val dirs = (0 until arr.length()).map { File(arr.getString(it)) }
                    val fanOut = json.optString("fanOut", "PRIMARY_ONLY").let {
                        WorkshopModPathStrategy.FanOutPolicy.valueOf(it)
                    }
                    if (dirs.isEmpty() || dirs.first().parentFile?.isDirectory != true) return null
                    WorkshopModPathStrategy.CopyIntoDir(dirs, fanOut)
                }
                else -> return null
            }
            Timber.tag(TAG).d("Loaded cached strategy for ${gameRootDir.name}: $type [$confidence]")
            val stdSeen = json.optBoolean("stdSeen", false)
            WorkshopModPathDetector.DetectionResult(strategy, confidence, reason, stdSeen)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load cached strategy, re-detecting")
            file.delete()
            null
        }
    }

    private fun getOrDetectStrategy(
        gameRootDir: File,
        winePrefix: String,
        gameName: String,
    ): WorkshopModPathDetector.DetectionResult {
        loadCachedStrategy(gameRootDir)?.let { return it }
        val detector = WorkshopModPathDetector()
        val result = detector.detect(
            gameInstallDir = gameRootDir,
            appDataRoaming = appDataRoaming(winePrefix),
            appDataLocal = appDataLocal(winePrefix),
            appDataLocalLow = appDataLocalLow(winePrefix),
            documentsMyGames = documentsMyGames(winePrefix),
            documentsDir = documentsDir(winePrefix),
            gameName = gameName,
        )
        saveStrategyCache(gameRootDir, result)
        return result
    }

    /**
     * Creates symlinks in each steam_settings/mods/ directory found alongside
     * any steam_api.dll or steamclient.dll within the game's install directory,
     * pointing to the downloaded workshop content. This must run on every launch
     * so gbe_fork can discover the mods regardless of DLL-replacement marker state.
     *
     * After configuring gbe_fork mods, runs the auto-detection pipeline to
     * discover game-specific mod directories (e.g. RimWorld's Mods/, KOTOR2's
     * Override/, Skyrim's Data/) and symlink Workshop items into them.
     *
     * Only directories with actual content files (not just a marker) are symlinked,
     * preventing empty/failed download directories from being reported as mods.
     *
     * @param gameRootDir The game's root install directory (e.g. .../Left 4 Dead 2/)
     * @param workshopContentDir The workshop/content/<appId>/ directory with downloaded mods
     * @param items The subscribed workshop items with metadata
     * @param winePrefix The Wine prefix path (for AppData detection)
     * @param gameName The game's display name (for fuzzy AppData matching)
     */
    fun configureModSymlinks(
        gameRootDir: File,
        workshopContentDir: File,
        items: List<WorkshopItem> = emptyList(),
        winePrefix: String = "",
        gameName: String = "",
        workshopModPath: String = "",
    ) {
        if (!workshopContentDir.exists()) {
            Timber.tag(TAG).d("Workshop content dir doesn't exist yet, skipping symlink config")
            return
        }
        // Only include mod directories that have actual content
        // (not just .workshop_complete marker or .DepotDownloader metadata)
        // and that are still in the enabled items list (if provided).
        val enabledIdSet = if (items.isNotEmpty()) {
            items.map { it.publishedFileId.toString() }.toSet()
        } else {
            null // no filtering when items list is empty (backward compat)
        }
        val modDirs = workshopContentDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory && dir.name.toLongOrNull() != null &&
                    (enabledIdSet == null || dir.name in enabledIdSet) &&
                    dir.listFiles()?.any { f ->
                        !f.name.startsWith(".") && (f.isFile || f.isDirectory)
                    } == true
            }

        // Delete content directories for mods no longer in the enabled set.
        // This reclaims disk space and prevents stale mods from appearing
        // if the game scans the filesystem independently.
        if (enabledIdSet != null) {
            workshopContentDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.toLongOrNull() != null &&
                    dir.name !in enabledIdSet
                ) {
                    Timber.tag(TAG).i("Removing deselected mod content: ${dir.name}")
                    dir.deleteRecursively()
                    // Also remove .partial sibling if present
                    File(workshopContentDir, "${dir.name}.partial").deleteRecursively()
                }
            }
        }

        if (modDirs.isNullOrEmpty()) {
            Timber.tag(TAG).d("No mod directories with content in ${workshopContentDir.absolutePath}")
            return
        }

        val appId = workshopContentDir.name.toIntOrNull() ?: -1
        val isInsurgency = appId == 222880
        val isLeft4Dead2 = appId == 550
        val isSkyrim = appId == 72850 || gameName.contains("skyrim", ignoreCase = true)
        val isSourceEngine = isSourceEngine(gameRootDir)

        // ── Manual mod path override ────────────────────────────────────────
        // When the user has set a custom mod folder, symlink all workshop
        // items into that directory. mods.json is still populated for games
        // that use ISteamUGC. All automatic detection is bypassed.
        val hasManualModPath = workshopModPath.isNotEmpty()
        if (hasManualModPath) {
            val targetDir = File(workshopModPath)

            // ── Clean ALL workshop symlinks from every possible location ─────
            // When switching from one manual path to another (e.g. LocalLow→Local),
            // or from auto-detection to manual, stale symlinks in previous
            // locations must be removed to avoid mods appearing in multiple places.
            val workshopContentAbs = workshopContentDir.absolutePath
            val targetCanonical = runCatching { targetDir.canonicalPath }.getOrElse { targetDir.absolutePath }

            // Helper: remove workshop symlinks from a directory, unless it's the new target
            fun cleanWorkshopSymlinksFrom(dir: File) {
                if (!dir.isDirectory) return
                val dirCanonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
                if (dirCanonical == targetCanonical) return  // skip — we'll recreate these below
                dir.listFiles()?.forEach { entry ->
                    if (Files.isSymbolicLink(entry.toPath())) {
                        try {
                            val linkTarget = Files.readSymbolicLink(entry.toPath())
                            val resolved = if (linkTarget.isAbsolute) linkTarget
                            else entry.toPath().parent.resolve(linkTarget)
                            val resolvedStr = runCatching { resolved.toRealPath().toString() }
                                .getOrElse { resolved.normalize().toAbsolutePath().toString() }
                            if (resolvedStr.contains("workshop/content/") ||
                                resolvedStr.startsWith(workshopContentAbs)
                            ) {
                                Files.deleteIfExists(entry.toPath())
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            // 1) Clean game directory tree (shallow walk for known mod dirs)
            gameRootDir.walkTopDown().maxDepth(4).forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dir.absolutePath.contains("steam_settings")) return@forEach
                if (dir.name.lowercase() in WorkshopModPathDetector.ALL_MOD_DIR_NAMES ||
                    dir == gameRootDir
                ) {
                    cleanWorkshopSymlinksFrom(dir)
                }
            }

            // 2) Clean AppData / Documents trees (the other roots
            //    the folder picker offers)
            if (winePrefix.isNotEmpty()) {
                listOf(
                    appDataRoaming(winePrefix),
                    appDataLocal(winePrefix),
                    appDataLocalLow(winePrefix),
                    documentsDir(winePrefix),
                    documentsMyGames(winePrefix),
                ).forEach { root ->
                    if (!root.isDirectory) return@forEach
                    // Walk the root itself + up to 5 levels deep to catch
                    // symlinks at any nesting level (e.g. Documents/Dev/Game/mods/)
                    root.walkTopDown().maxDepth(5).forEach { dir ->
                        if (dir.isDirectory) {
                            cleanWorkshopSymlinksFrom(dir)
                        }
                    }
                }
            }
            Timber.tag(TAG).d("Cleaned workshop symlinks from all previous locations")

            // ── Create fresh symlinks in the chosen target ──────────────────
            try {
                if (!targetDir.isDirectory) targetDir.mkdirs()
                // Clean the target itself (in case of stale entries)
                cleanWorkshopSymlinksFrom(targetDir).also {
                    // The helper skips targetCanonical, so clean it explicitly
                    targetDir.listFiles()?.forEach { entry ->
                        if (Files.isSymbolicLink(entry.toPath())) {
                            try {
                                val linkTarget = Files.readSymbolicLink(entry.toPath())
                                val resolved = if (linkTarget.isAbsolute) linkTarget
                                else entry.toPath().parent.resolve(linkTarget)
                                val resolvedStr = runCatching { resolved.toRealPath().toString() }
                                    .getOrElse { resolved.normalize().toAbsolutePath().toString() }
                                if (resolvedStr.contains("workshop/content/") ||
                                    resolvedStr.startsWith(workshopContentAbs)
                                ) {
                                    Files.deleteIfExists(entry.toPath())
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
                // Create fresh symlinks
                modDirs.forEach { itemDir ->
                    val linkPath = targetDir.toPath().resolve(itemDir.name)
                    if (!Files.exists(linkPath)) {
                        Files.createSymbolicLink(linkPath, itemDir.toPath())
                    }
                }
                Timber.tag(TAG).i(
                    "Manual mod path: symlinked ${modDirs.size} items into ${targetDir.absolutePath}"
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to symlink mods into manual path: ${targetDir.absolutePath}")
            }
        }

        // ── Early strategy detection ────────────────────────────────────────
        // Detect whether the game reads mods from its own directory structure
        // (SymlinkIntoDir) BEFORE writing gbe_fork mods in Phases 2/4. If
        // the game scans its filesystem for mods, we must NOT also populate
        // gbe_fork's ISteamUGC mods — otherwise games like RimWorld/AoE2 HD
        // see every mod twice (once from the filesystem, once from ISteamUGC).
        //
        // Only HIGH-confidence SymlinkIntoDir games definitely scan their own
        // directories (mods/, Mods/, addons/, Modules/ etc.). MEDIUM/LOW
        // targets (maps/, levels/, resourcepacks/) may not be scanned by the
        // game — the game might rely on ISteamUGC for discovery instead
        // (e.g. Generals ZH uses ISteamUGC for workshop maps). For those,
        // keep ISteamUGC populated so the game can discover items — UNLESS
        // the game has its own steam_api.dll, in which case gbe_fork reads
        // ISteamUGC from BOTH per-DLL and global sources, and the filesystem
        // symlinks cause duplication regardless of which ISteamUGC we clear.
        // For those games (e.g. Terraria), treat as filesystem-managed and
        // clear all ISteamUGC sources.
        //
        // Source engine games are excluded because they have their own
        // VPK/GMA/BSP handler and need ISteamUGC populated for mod discovery.
        val unityModTargets by lazy { detectUnityModTargets(gameRootDir, winePrefix) }
        val modsJsonText by lazy { buildModsJson(modDirs, items).toString(2) }

        // Starbound has mods/ (HIGH confidence) but workshop items use a
        // _metadata format that doesn't work when symlinked into mods/.
        // Force ISteamUGC path regardless of detection.
        val forceStandardAppIds = setOf(211820,1468810) // Starbound,TaleofImmortal

        // When the game's binary contains ISteamUGC / GetItemInstallInfo
        // strings AND there's a HIGH-confidence mod directory, the game
        // likely uses the Steam Workshop API for content discovery — the
        // directory is for manual/non-workshop mods.  In that case we
        // populate mods.json (ISteamUGC) and suppress filesystem symlinks
        // to prevent duplication.
        //
        // This heuristic naturally separates:
        //  • Native C++ games (ISteamUGC strings in exe)  → ISteamUGC path
        //  • .NET / Unity games (no ISteamUGC in exe)     → filesystem path
        var stdSeenWithHighDir = false

        val willUseFilesystemMods = if (hasManualModPath) {
            // User chose a specific mod folder — always populate mods.json
            // alongside the manual symlinks (covers ISteamUGC games too)
            Timber.tag(TAG).i("Manual mod path set for $gameName — forcing ISteamUGC mods.json")
            false
        } else if (appId in forceStandardAppIds) {
            stdSeenWithHighDir = true // treat like stdSeen so Phase 6 + cleanup runs
            Timber.tag(TAG).i("Force-Standard override for appId $appId ($gameName)")
            false
        } else if (winePrefix.isNotEmpty() && !isSkyrim && !isSourceEngine) {
            try {
                val detection = getOrDetectStrategy(gameRootDir, winePrefix, gameName)
                Timber.tag(TAG).i(
                    "Strategy detection: ${detection.strategy::class.simpleName} " +
                        "[${detection.confidence}] stdSeen=${detection.stdSeen} — ${detection.reason}"
                )
                val isHighConfSymlink = detection.strategy is WorkshopModPathStrategy.SymlinkIntoDir &&
                    detection.confidence == WorkshopModPathDetector.Confidence.HIGH

                if (isHighConfSymlink && detection.stdSeen) {
                    stdSeenWithHighDir = true
                    Timber.tag(TAG).i(
                        "ISteamUGC binary signals + HIGH-confidence mod dir for $gameName — " +
                            "preferring ISteamUGC path (mods.json), suppressing filesystem symlinks"
                    )
                    false  // use ISteamUGC, not filesystem
                } else {
                    (isHighConfSymlink || unityModTargets.isNotEmpty())
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Strategy detection failed, defaulting to ISteamUGC path")
                false
            }
        } else {
            Timber.tag(TAG).d(
                "Strategy detection skipped (winePrefix=${winePrefix.isNotEmpty()}, " +
                    "isSkyrim=$isSkyrim, isSourceEngine=$isSourceEngine)"
            )
            false
        }
        Timber.tag(TAG).i("willUseFilesystemMods=$willUseFilesystemMods stdSeenOverride=$stdSeenWithHighDir for $gameName")

        // Find all gbe_fork DLL locations (steam_api.dll, steam_api64.dll,
        // steamclient.dll, steamclient64.dll) and create mods/ symlinks next
        // to each one, since gbe_fork reads mods relative to the DLL location.
        val dllNames = setOf(
            "steam_api.dll", "steam_api64.dll",
            "steamclient.dll", "steamclient64.dll",
        )
        // Global Steam settings are typically initialized by SteamUtils and
        // can be used to bootstrap per-DLL steam_settings directories.
        // workshopContentDir = .../Steam/steamapps/workshop/content/<appId>
        val steamRootDir = workshopContentDir.parentFile  // content/
            ?.parentFile  // workshop/
            ?.parentFile  // steamapps/
            ?.parentFile  // Steam/
        val globalSettingsDirForBootstrap =
            steamRootDir?.let { File(it, "steam_settings") }
        val workshopAppIdText = workshopContentDir.name
        var configuredCount = 0
        gameRootDir.walkTopDown().maxDepth(10).forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.lowercase() !in dllNames) return@forEach

            val settingsDir = file.parentFile?.let { File(it, "steam_settings") } ?: return@forEach
            if (!settingsDir.exists()) {
                settingsDir.mkdirs()
            }
            if (settingsDir.isDirectory) {
                val coreFiles = listOf(
                    "steam_appid.txt",
                    "configs.user.ini",
                    "configs.app.ini",
                    "configs.main.ini",
                    "depots.txt",
                    "supported_languages.txt",
                )
                val globalSettings = globalSettingsDirForBootstrap
                if (globalSettings?.isDirectory == true) {
                    coreFiles.forEach { name ->
                        val src = File(globalSettings, name)
                        val dst = File(settingsDir, name)
                        if (src.isFile && !dst.exists()) {
                            try {
                                Files.copy(src.toPath(), dst.toPath())
                            } catch (_: Exception) { }
                        }
                    }
                }
                // Always write the correct appId — the bootstrap above may
                // have copied a stale value from a previously-launched game's
                // global steam_settings, and ensureSteamSettings only writes
                // when the file is absent, so a wrong value would persist.
                val appIdFile = File(settingsDir, "steam_appid.txt")
                if (workshopAppIdText.toLongOrNull() != null) {
                    try {
                        appIdFile.writeText(workshopAppIdText)
                    } catch (_: Exception) { }
                }
            }
            val hasCoreConfig =
                File(settingsDir, "steam_appid.txt").isFile ||
                    File(settingsDir, "configs.user.ini").isFile ||
                    File(settingsDir, "configs.app.ini").isFile
            if (!settingsDir.isDirectory || !hasCoreConfig) {
                Timber.tag(TAG).d("Skipping uninitialized steam_settings at ${settingsDir.absolutePath}")
                return@forEach
            }
            val modsDir = File(settingsDir, "mods")

            try {
                // Skip writing gbe_fork mods when the game reads mods from
                // its own filesystem (SymlinkIntoDir). Writing them here
                // would cause duplicate mod entries in games like RimWorld
                // and AoE2 HD that scan both their mod dir AND ISteamUGC.
                if (!willUseFilesystemMods) {
                    if (modsDir.exists()) {
                        // Don't use deleteRecursively() — it follows symlinks and
                        // would destroy the actual workshop content files
                        clearModEntries(modsDir)
                        modsDir.delete()
                    }
                    modsDir.mkdirs()

                    modDirs.forEach { itemDir ->
                        val linkPath = modsDir.toPath().resolve(itemDir.name)
                        Files.createSymbolicLink(linkPath, itemDir.toPath())
                    }

                    // Write mods.json with titles and primary_filename so
                    // gbe_fork can serve correct metadata to games.
                    File(settingsDir, "mods.json").writeText(modsJsonText)

                    // Copy preview images into steam_settings/mod_images/<itemId>/
                    // so gbe_fork can serve them via GetQueryUGCPreviewURL.
                    copyPreviewImages(modDirs, settingsDir)

                    configuredCount++
                    Timber.tag(TAG).d(
                        "Configured ${modDirs.size} mod symlinks at ${modsDir.absolutePath}"
                    )
                    Timber.tag(TAG).d(
                        "mods.json written (${modDirs.size} entries) next to ${file.name}: " +
                            modDirs.joinToString { it.name }
                    )
                } else if (modsDir.isDirectory || File(settingsDir, "mods.json").isFile) {
                    // Filesystem-managed game: clean stale gbe_fork mods from
                    // previous runs that may have lacked this early skip.
                    if (modsDir.isDirectory) {
                        removeSymlinksIn(modsDir)
                    }
                    File(settingsDir, "mods.json").apply { if (isFile) writeText("{}") }
                    Timber.tag(TAG).d(
                        "Cleared per-DLL gbe_fork mods at ${settingsDir.absolutePath} (filesystem-managed)"
                    )
                }

                // Ensure steam_interfaces.txt has all interface versions
                // (fixes games whose original DLL used the longer
                // STEAM*_INTERFACE_VERSION* format that the initial scan missed)
                val origDll = File(file.parentFile, file.name + ".orig")
                val interfacesFile = File(file.parentFile, "steam_interfaces.txt")
                if (origDll.isFile) {
                    ensureInterfacesComplete(origDll, interfacesFile)
                }
                if (interfacesFile.isFile) {
                    Timber.tag(TAG).d(
                        "steam_interfaces.txt for ${file.name}: " +
                            interfacesFile.readText().trim().replace("\n", ", ")
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create mod symlinks at ${modsDir.absolutePath}")
            }
        }

        // Clean up any stale uninitialized steam_settings folders (mods-only)
        // created by older logic near DLL folders (e.g. bin/, bin/x64/).
        // Keep only initialized configs that include at least one core file.
        gameRootDir.walkTopDown().maxDepth(10).forEach { dir ->
            if (!dir.isDirectory || dir.name != "steam_settings") return@forEach
            val hasCoreConfig =
                File(dir, "steam_appid.txt").isFile ||
                    File(dir, "configs.user.ini").isFile ||
                    File(dir, "configs.app.ini").isFile
            if (hasCoreConfig) return@forEach
            try {
                val modsDir = File(dir, "mods")
                if (modsDir.isDirectory) {
                    modsDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                    modsDir.delete()
                }
                File(dir, "mods.json").delete()
                if (dir.listFiles()?.isEmpty() == true) {
                    dir.delete()
                    Timber.tag(TAG).d("Removed stale uninitialized steam_settings at ${dir.absolutePath}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to clean stale uninitialized steam_settings at ${dir.absolutePath}")
            }
        }
        // Clean up any bare root-level steam_settings/ that only has
        // mods/ + mods.json (no config files). These can confuse gbe_fork
        // if it searches for settings relative to the game executable.
        val rootSettingsDir = File(gameRootDir, "steam_settings")
        if (rootSettingsDir.isDirectory &&
            !File(rootSettingsDir, "steam_appid.txt").exists() &&
            !File(rootSettingsDir, "configs.app.ini").exists()) {
            try {
                val rootModsDir = File(rootSettingsDir, "mods")
                if (rootModsDir.isDirectory) {
                    rootModsDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                    rootModsDir.delete()
                }
                File(rootSettingsDir, "mods.json").delete()
                // Only delete the directory if it's now empty
                if (rootSettingsDir.listFiles()?.isEmpty() == true) {
                    rootSettingsDir.delete()
                    Timber.tag(TAG).d("Removed bare root steam_settings at ${rootSettingsDir.absolutePath}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to clean bare root steam_settings")
            }
        }

        Timber.tag(TAG).i(
            "Workshop mod symlinks configured at $configuredCount DLL locations " +
                "(${modDirs.size} mods with content)"
        )

        // The global steamclient.dll at the Wine Steam root also maintains
        // its own steam_settings/. For ColdClient games (no steam_api.dll),
        // this is the ONLY source of ISteamUGC configuration.
        //
        // To prevent duplication, we use willUseFilesystemMods:
        // - HIGH-confidence filesystem games (SymlinkIntoDir/Unity): clear
        //   mods.json so ISteamUGC returns nothing — the game discovers mods
        //   from its own directories (Mods/, addons/, AppData/).
        // - MEDIUM/LOW SymlinkIntoDir + Standard games: populate mods.json
        //   so ISteamUGC discovery works (e.g. Generals ZH needs this for
        //   workshop map discovery).
        if (steamRootDir != null) {
            val globalSettingsDir = File(steamRootDir, "steam_settings")

            // For ColdClient games (no per-game steam_api.dll), the global
            // steam_settings/ is the ONLY source of ISteamUGC. It may not
            // exist yet because ensureSteamSettings runs in beginLaunchApp
            // AFTER this workshop sync. Create and bootstrap it now so the
            // first launch also gets mods configured.
            if (!globalSettingsDir.isDirectory && !willUseFilesystemMods) {
                globalSettingsDir.mkdirs()
                Timber.tag(TAG).i(
                    "Created global steam_settings/ at ${globalSettingsDir.absolutePath} " +
                        "(ColdClient bootstrap for workshop mods)"
                )
            }

            if (globalSettingsDir.isDirectory) {
                // Always update the global steam_appid.txt to match this
                // game — the global steam_settings is shared across games
                // and may contain a stale appId from a previous launch.
                if (workshopAppIdText.toLongOrNull() != null) {
                    try {
                        File(globalSettingsDir, "steam_appid.txt")
                            .writeText(workshopAppIdText)
                    } catch (_: Exception) { }
                }
                val globalModsJson = File(globalSettingsDir, "mods.json")
                if (willUseFilesystemMods) {
                    // HIGH-confidence filesystem: clear ISteamUGC to prevent duplication
                    if (globalModsJson.isFile) {
                        globalModsJson.writeText("{}")
                    }
                    Timber.tag(TAG).d("Cleared global mods.json (filesystem-managed game)")
                } else {
                    // ISteamUGC-needed: populate so ColdClient games can discover mods
                    try {
                        globalModsJson.writeText(modsJsonText)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to write global mods.json")
                    }

                    // Also copy preview images to global mod_images/
                    try {
                        copyPreviewImages(modDirs, globalSettingsDir)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to copy global mod preview images")
                    }
                }

                // Clean up stale mods/ symlinks from previous versions that
                // created them alongside mods.json (caused duplication).
                val globalModsDir = File(globalSettingsDir, "mods")
                if (globalModsDir.isDirectory) {
                    try {
                        clearModEntries(globalModsDir)
                        if (globalModsDir.listFiles()?.isEmpty() != false) {
                            globalModsDir.delete()
                        }
                        Timber.tag(TAG).d("Cleaned global mods/ (using mods.json only)")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to clean global mods/")
                    }
                }
            }
        }

        // Source engine games: symlink VPK workshop mods into addons/
        // which is the standard directory for addon VPKs. We also revert
        // any previous gameinfo.txt patches that caused localization issues
        // and clean up old symlinks from custom/, content/, workshop_mods/.
        val hasVpkMods = modDirs.any { itemDir ->
            itemDir.listFiles()?.any { it.isFile && it.name.endsWith(".vpk", ignoreCase = true) } == true
        }
        val hasGmaMods = modDirs.any { itemDir ->
            itemDir.listFiles()?.any { it.isFile && it.name.endsWith(".gma", ignoreCase = true) } == true
        }
        // Collect the primary addons/ directory (next to the game's main
        // gameinfo.txt). VPKs should only go here — not in DLC, update,
        // engine base, or staging directories, since duplicates across
        // multiple addons/ dirs can cause slow loading or conflicts.
        val primaryAddonDirs = mutableSetOf<String>()
        val primaryContentDirs = mutableSetOf<String>()
        val skipDirNames = setOf("hl2", "episodic", "ep2", "platform")
        if (hasVpkMods || hasGmaMods) {
            gameRootDir.walkTopDown().maxDepth(5).forEach { file ->
                if (!file.isFile || file.name != "gameinfo.txt") return@forEach
                if (file.absolutePath.contains("steam_settings")) return@forEach
                if (file.absolutePath.contains(".DepotDownloader")) return@forEach
                val gameContentDir = file.parentFile ?: return@forEach
                val dirName = gameContentDir.name.lowercase()

                // Revert any previous gameinfo.txt patch
                revertGameInfoPatch(file)

                // Skip DLC, update, and engine base directories
                if (
                    dirName.contains("_dlc") ||
                    dirName == "update" ||
                    dirName == "map_publish" ||
                    dirName in skipDirNames
                ) {
                    return@forEach
                }

                // Create addons/ directory for VPK symlinks
                val addonsDir = File(gameContentDir, "addons")
                if (!addonsDir.exists()) {
                    addonsDir.mkdirs()
                }
                primaryAddonDirs.add(addonsDir.absolutePath)
                primaryContentDirs.add(gameContentDir.absolutePath)

                // Clean up old workshop files from custom/, content/, workshop_mods/
                // (symlinks from previous code AND hard-linked/copied VPKs)
                for (dirN in listOf("custom", "content", "workshop_mods")) {
                    val oldDir = File(gameContentDir, dirN)
                    if (oldDir.isDirectory) {
                        oldDir.listFiles()?.forEach { f ->
                            if (Files.isSymbolicLink(f.toPath())) {
                                Files.deleteIfExists(f.toPath())
                            } else if (f.isFile && f.name.endsWith(".vpk", ignoreCase = true)) {
                                f.delete()
                            }
                        }
                    }
                }
            }
        }

        // Insurgency's gameinfo.txt search paths include insurgency/custom/*
        // but NOT addons/ or inscustom/. Place VPKs in custom/ so the engine
        // finds and mounts them.
        if (isInsurgency) {
            primaryContentDirs.forEach { contentPath ->
                val contentDir = File(contentPath)
                val customDir = File(contentDir, "custom")
                customDir.mkdirs()

                val manifestFile = File(customDir, ".gamenative_workshop_addons")

                // Remove files placed by previous workshop syncs (tracked by manifest)
                if (manifestFile.isFile) {
                    manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                        try { Files.deleteIfExists(File(customDir, name).toPath()) } catch (_: Exception) { }
                    }
                }
                // Also remove any symlinks (from older code)
                customDir.listFiles()?.forEach { f ->
                    if (Files.isSymbolicLink(f.toPath())) {
                        Files.deleteIfExists(f.toPath())
                    }
                }

                val placedFiles = mutableListOf<String>()
                modDirs.forEach { itemDir ->
                    itemDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".vpk", ignoreCase = true) }
                        ?.forEach { vpkFile ->
                            val outFile = File(customDir, vpkFile.name)
                            if (materializeWorkshopFile(vpkFile, outFile)) {
                                placedFiles.add(vpkFile.name)
                            }
                        }
                }
                if (placedFiles.isNotEmpty()) {
                    manifestFile.writeText(placedFiles.joinToString("\n", postfix = "\n"))
                    Timber.tag(TAG).d("Placed ${placedFiles.size} VPK mods in ${customDir.absolutePath}")
                } else {
                    manifestFile.delete()
                }
            }
            // Clean up stale inscustom/ created by previous metadata-path logic
            primaryContentDirs.forEach { contentPath ->
                val inscustomDir = File(File(contentPath), "inscustom")
                if (inscustomDir.isDirectory) {
                    try {
                        inscustomDir.listFiles()?.forEach { f -> Files.deleteIfExists(f.toPath()) }
                        if (inscustomDir.listFiles()?.isEmpty() == true) inscustomDir.delete()
                    } catch (_: Exception) { }
                }
            }
        }

        if (isLeft4Dead2) {
            // Previous metadata-path logic created myl4d2addons/ trees that
            // are not required for L4D2 and can confuse addon loading.
            val cleanupBases = linkedSetOf<File>()
            cleanupBases.add(gameRootDir)
            cleanupBases.add(File(gameRootDir, "bin"))
            primaryContentDirs.forEach { cleanupBases.add(File(it)) }
            cleanupBases.forEach { base ->
                val legacyDir = File(base, "myl4d2addons")
                if (legacyDir.isDirectory) {
                    try {
                        legacyDir.listFiles()?.forEach { f ->
                            if (f.isFile || Files.isSymbolicLink(f.toPath())) {
                                Files.deleteIfExists(f.toPath())
                            }
                        }
                        if (legacyDir.listFiles()?.isEmpty() == true) {
                            legacyDir.delete()
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        primaryAddonDirs.forEach { addonPath ->
            val dir = File(addonPath)
            try {
                val manifestFile = File(dir, ".gamenative_workshop_addons")

                // Remove files placed by previous workshop syncs (tracked by manifest)
                if (manifestFile.isFile) {
                    manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                        try { Files.deleteIfExists(File(dir, name).toPath()) } catch (_: Exception) { }
                    }
                }
                // Also remove any symlinks (from older code that used symlinks)
                dir.listFiles()?.forEach { f ->
                    if (Files.isSymbolicLink(f.toPath())) {
                        Files.deleteIfExists(f.toPath())
                    }
                }

                val placedFiles = mutableListOf<String>()

                modDirs.forEach { itemDir ->
                    itemDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".vpk", ignoreCase = true) }
                        ?.forEach { vpkFile ->
                            val outFile = File(dir, vpkFile.name)
                            if (materializeWorkshopFile(vpkFile, outFile)) {
                                placedFiles.add(vpkFile.name)
                            }
                        }
                }

                // Also link .gma files (Garry's Mod addon archives) into
                // addons/ so GMod can mount them directly without relying
                // on ISteamUGC::GetItemInstallInfo paths.
                modDirs.forEach { itemDir ->
                    itemDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".gma", ignoreCase = true) }
                        ?.forEach { gmaFile ->
                            val outFile = File(dir, gmaFile.name)
                            if (materializeWorkshopFile(gmaFile, outFile)) {
                                placedFiles.add(gmaFile.name)
                            }
                        }
                }

                // Write manifest of files we placed so future syncs can clean them up
                if (placedFiles.isNotEmpty()) {
                    manifestFile.writeText(placedFiles.joinToString("\n", postfix = "\n"))
                    Timber.tag(TAG).d("Linked ${placedFiles.size} addons in ${dir.absolutePath}")
                } else {
                    manifestFile.delete()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create VPK symlinks in ${dir.absolutePath}")
            }
        }

        // Clean up stale VPK symlinks from non-primary addons/ directories
        // (DLC, update, staging, engine base) that were created by previous
        // versions of the code, as duplicates can confuse game addon loading.
        gameRootDir.walkTopDown().maxDepth(5).forEach { dir ->
            if (!dir.isDirectory || dir.name != "addons") return@forEach
            if (dir.absolutePath.contains("steam_settings")) return@forEach
            if (dir.absolutePath in primaryAddonDirs) return@forEach
            dir.listFiles()?.forEach { f ->
                if (Files.isSymbolicLink(f.toPath())) {
                    try {
                        val target = Files.readSymbolicLink(f.toPath())
                        if (target.toString().contains("workshop/content/")) {
                            Files.deleteIfExists(f.toPath())
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        // Source engine workshop maps (Portal 2, CS:GO, etc.) — games may
        // also look for BSP map files at maps/workshop/<itemId>/<file>.bsp
        // in addition to using the ISteamUGC API path from gbe_fork.
        // Create symlinks so maps are discoverable through the filesystem.
        gameRootDir.walkTopDown().maxDepth(5).forEach { dir ->
            if (!dir.isDirectory || dir.name != "maps") return@forEach
            if (dir.absolutePath.contains("steam_settings")) return@forEach
            if (dir.absolutePath.contains(".DepotDownloader")) return@forEach

            val workshopMapsDir = File(dir, "workshop")
            try {
                // Remove old workshop map symlinks
                if (workshopMapsDir.exists()) {
                    workshopMapsDir.listFiles()?.forEach { f ->
                        if (Files.isSymbolicLink(f.toPath())) {
                            Files.deleteIfExists(f.toPath())
                        } else if (f.isDirectory) {
                            // Item subdirs are fully workshop-managed — remove
                            // all files (symlinks, hard links, copies).
                            f.listFiles()?.forEach { inner ->
                                Files.deleteIfExists(inner.toPath())
                            }
                            if (f.listFiles()?.isEmpty() == true) f.delete()
                        }
                    }
                }

                var bspCount = 0
                modDirs.forEach { itemDir ->
                    val bspFiles = itemDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".bsp", ignoreCase = true) }
                        ?: return@forEach
                    if (bspFiles.isEmpty()) return@forEach

                    val itemMapDir = File(workshopMapsDir, itemDir.name)
                    itemMapDir.mkdirs()
                    bspFiles.forEach { bspFile ->
                        val outFile = File(itemMapDir, bspFile.name)
                        if (materializeWorkshopFile(bspFile, outFile)) {
                            bspCount++
                        }
                    }
                }
                if (bspCount > 0) {
                    Timber.tag(TAG).d("Linked $bspCount BSP workshop maps in ${workshopMapsDir.absolutePath}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create BSP map symlinks in ${workshopMapsDir.absolutePath}")
            }
        }

        // ── Clean up any stale detector symlinks ──────────────────────────────
        // Previous versions may have created item-directory symlinks or
        // flat-file symlinks in maps/, addons/, CookedMods/, etc. Remove
        // any symlinks in the game tree that point into the workshop content
        // directory (identified by their target containing "workshop/content/").
        // The existing VPK and BSP handlers above create their own symlinks
        // to individual files, but those point to files INSIDE primary addon
        // dirs and are re-created each run — so this cleanup is safe.
        // Use toRealPath() to resolve intermediate symlinks (e.g. xuser →
        // xuser-STEAM_*) so old symlinks created with the xuser path are
        // also recognized and cleaned.
        val workshopContentReal = runCatching {
            workshopContentDir.toPath().toRealPath().toString()
        }.getOrElse { workshopContentDir.absolutePath }
        // When the user has a manual mod path inside the game tree, skip
        // cleaning symlinks in that directory — they were just created above.
        val manualTargetCanonical = if (hasManualModPath) {
            runCatching { File(workshopModPath).canonicalPath }.getOrElse { workshopModPath }
        } else ""
        gameRootDir.walkTopDown().maxDepth(6).forEach { entry ->
            if (!Files.isSymbolicLink(entry.toPath())) return@forEach
            if (entry.absolutePath.contains("steam_settings")) return@forEach
            // Protect manual mod path symlinks from stale cleanup
            if (hasManualModPath && manualTargetCanonical.isNotEmpty()) {
                val parentCanonical = runCatching {
                    entry.parentFile?.canonicalPath ?: ""
                }.getOrElse { entry.parentFile?.absolutePath ?: "" }
                if (parentCanonical == manualTargetCanonical) return@forEach
            }
            try {
                val target = Files.readSymbolicLink(entry.toPath())
                val resolvedTarget = if (target.isAbsolute) target
                else entry.toPath().parent.resolve(target)
                val targetStr = runCatching {
                    resolvedTarget.toRealPath().toString()
                }.getOrElse {
                    resolvedTarget.normalize().toAbsolutePath().toString()
                }
                // Remove symlinks that point into this game's workshop content
                if (targetStr.startsWith(workshopContentReal)) {
                    Files.deleteIfExists(entry.toPath())
                    Timber.tag(TAG).d("Removed stale workshop symlink: ${entry.absolutePath}")
                }
            } catch (_: Exception) { }
        }

        // Delete stale strategy cache if the game is Source engine
        // (since the detector is skipped for these games)
        if (isSourceEngine) {
            strategyCacheFile(gameRootDir).delete()
        }

        if (isSkyrim && winePrefix.isNotEmpty()) {
            try {
                syncSkyrimWorkshopMods(gameRootDir, modDirs, winePrefix)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skyrim workshop sync failed")
            }
        }

        // ── Clean up stale UDK CookedMods hard links from Build 13 ──────
        // A previous build hard-linked workshop .upk files into CookedMods/,
        // which caused crashes. Remove them using the manifest file.
        if (winePrefix.isNotEmpty()) {
            val cookedModsDir = gameRootDir.walkTopDown().maxDepth(3)
                .firstOrNull { it.isDirectory && it.name.equals("CookedMods", ignoreCase = true) }
            if (cookedModsDir != null) {
                val manifestFile = File(cookedModsDir, ".gamenative_workshop_files")
                if (manifestFile.isFile) {
                    manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                        try {
                            Files.deleteIfExists(File(cookedModsDir, name).toPath())
                        } catch (_: Exception) { }
                    }
                    manifestFile.delete()
                    Timber.tag(TAG).d("Cleaned stale CookedMods hard links")
                }
            }
        }

        // ── Auto-detect game-specific mod directories and symlink into them ──
        // This handles games like RimWorld (Mods/), KOTOR2 (Override/),
        // etc. that scan their own directories for mod subdirectories
        // rather than using the Steam Workshop API.
        //
        // Skip Source engine games (those with gameinfo.txt) since they are
        // already fully handled by the VPK→addons/ and BSP→maps/workshop/
        // symlinks above. Running the detector on them causes regressions
        // (e.g. item-directory symlinks in maps/ confuse L4D2).
        if (winePrefix.isNotEmpty() && modDirs.isNotEmpty() && !isSourceEngine && !stdSeenWithHighDir && !hasManualModPath) {
            // Check if Phase 7 (Unity AppData) will handle mod directories.
            // If it does, skip Phase 6 SymlinkIntoDir for the same directory
            // names so mods aren't placed at both the install dir AND AppData.
            val unityHandledBaseNames = unityModTargets.map {
                // e.g. "UGC/Models" → "ugc"
                val parts = it.toRelativeString(it.parentFile?.parentFile ?: it).split("/", "\\")
                parts.firstOrNull()?.lowercase() ?: ""
            }.toSet()

            // Local alias for Phase 7 Unity block below
            val unityTargets = unityModTargets

            val activeItemDirs = modDirs.associate { dir ->
                (dir.name.toLongOrNull() ?: 0L) to dir
            }
            val titlesByItemId = items.associate { it.publishedFileId to it.title }

            try {
                val detection = getOrDetectStrategy(gameRootDir, winePrefix, gameName)
                Timber.tag(TAG).i(
                    "Mod path strategy for '$gameName': ${detection.strategy::class.simpleName} " +
                        "[${detection.confidence}] — ${detection.reason}"
                )

                // If Unity AppData handles certain directories, filter them out
                // from Phase 6 to avoid install-dir symlinks for Unity games
                // (which read from AppData, not the install directory).
                val effectiveStrategy = if (unityHandledBaseNames.isNotEmpty() &&
                    detection.strategy is WorkshopModPathStrategy.SymlinkIntoDir
                ) {
                    val origDirs = detection.strategy.effectiveDirs
                    val filtered = origDirs.filter { dir ->
                        dir.name.lowercase() !in unityHandledBaseNames
                    }
                    if (filtered.isEmpty()) {
                        Timber.tag(TAG).i(
                            "Skipping Phase 6 SymlinkIntoDir — Unity AppData handles: $unityHandledBaseNames"
                        )
                        // Clean up stale install-dir symlinks from previous
                        // runs that placed mods in the install dir before
                        // Unity AppData handling took over.
                        origDirs.forEach { dir ->
                            if (dir.isDirectory) {
                                dir.listFiles()?.forEach { entry ->
                                    if (Files.isSymbolicLink(entry.toPath())) {
                                        val target = Files.readSymbolicLink(entry.toPath())
                                        if (target.toString().contains(workshopContentDir.absolutePath)) {
                                            Files.deleteIfExists(entry.toPath())
                                            Timber.tag(TAG).d("Removed stale install-dir symlink: ${entry.name}")
                                        }
                                    }
                                }
                            }
                        }
                        null
                    } else {
                        WorkshopModPathStrategy.SymlinkIntoDir(filtered, detection.strategy.fanOut)
                    }
                } else {
                    detection.strategy
                }

                if (effectiveStrategy != null) {
                    // ── Mirror/merge detection ──────────────────────────────
                    // Some workshop items have subdirectories that mirror the
                    // game's own directory tree (e.g. KOTOR2's TSLRCM has
                    // lips/, modules/, override/ that should merge into the
                    // game root). Detect these and merge file-level symlinks
                    // into the matching game directories instead of creating
                    // directory symlinks in a single target dir.
                    val mirrorItemIds = mutableSetOf<Long>()
                    if (effectiveStrategy is WorkshopModPathStrategy.SymlinkIntoDir) {
                        // Use ALL targetDirs (not effectiveDirs) so mirror
                        // detection works regardless of FanOutPolicy. E.g.
                        // KOTOR2 has PRIMARY_ONLY with Modules as primary,
                        // but item 708869162 only has override/ — checking
                        // against all targetDirs catches it correctly.
                        val targetDirNamesLc = effectiveStrategy.targetDirs
                            .map { it.name.lowercase() }.toSet()
                        for ((id, itemDir) in activeItemDirs) {
                            val itemSubdirNamesLc = itemDir.listFiles()
                                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                                ?.map { it.name.lowercase() }
                                ?.toSet() ?: emptySet()
                            if (targetDirNamesLc.any { it in itemSubdirNamesLc }) {
                                mirrorItemIds.add(id)
                            }
                        }
                    }

                    if (mirrorItemIds.isNotEmpty()) {
                        Timber.tag(TAG).i(
                            "Mirror/merge: ${mirrorItemIds.size} item(s) with subdirs matching target dir names"
                        )
                        // Collect all game dirs that need merging
                        for (id in mirrorItemIds) {
                            val itemDir = activeItemDirs[id] ?: continue
                            val itemSubdirs = itemDir.listFiles()
                                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                                ?: continue
                            for (subDir in itemSubdirs) {
                                val gameDir = gameRootDir.listFiles()?.firstOrNull {
                                    it.isDirectory && it.name.equals(subDir.name, ignoreCase = true)
                                } ?: continue

                                // Remove stale file symlinks from this item
                                val itemPath = itemDir.absolutePath
                                gameDir.listFiles()?.forEach { entry ->
                                    if (Files.isSymbolicLink(entry.toPath())) {
                                        try {
                                            val target = Files.readSymbolicLink(entry.toPath())
                                            if (target.toAbsolutePath().normalize().toString()
                                                    .startsWith(itemPath)
                                            ) {
                                                Files.deleteIfExists(entry.toPath())
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }

                                // Create file symlinks for each file
                                var mergeCount = 0
                                subDir.listFiles()
                                    ?.filter { it.isFile && !it.name.startsWith(".") }
                                    ?.forEach { srcFile ->
                                        val link = File(gameDir, srcFile.name)
                                        val linkPath = link.toPath()
                                        if (!Files.exists(linkPath) || Files.isSymbolicLink(linkPath)) {
                                            if (Files.isSymbolicLink(linkPath)) {
                                                Files.deleteIfExists(linkPath)
                                            }
                                            try {
                                                Files.createSymbolicLink(
                                                    linkPath,
                                                    srcFile.toPath().toAbsolutePath(),
                                                )
                                                mergeCount++
                                            } catch (_: Exception) { }
                                        }
                                    }
                                if (mergeCount > 0) {
                                    Timber.tag(TAG).d(
                                        "Merged $mergeCount files: ${itemDir.name}/${subDir.name} -> ${gameDir.name}"
                                    )
                                }
                            }
                        }
                    }

                    // Pass non-mirror items to the symlinker
                    val regularItems = activeItemDirs.filterKeys { it !in mirrorItemIds }
                    if (regularItems.isNotEmpty() || mirrorItemIds.isEmpty()) {
                        val itemsForSync = if (mirrorItemIds.isEmpty()) activeItemDirs else regularItems
                        val symlinker = WorkshopSymlinker()
                        val result = symlinker.sync(
                            effectiveStrategy, itemsForSync, workshopContentDir, titlesByItemId,
                        )
                        if (result.hasErrors) {
                            result.errors.forEach { (k, v) ->
                                Timber.tag(TAG).w("Symlinker error [$k]: $v")
                            }
                        }
                    }
                }


            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Auto-detect mod path failed for '$gameName'")
            }

            // ── Unity AppData/LocalLow redirection ──────────────────────────
            // Unity games using Application.persistentDataPath read mods from
            // AppData/LocalLow/<company>/<product>/ rather than the install dir.
            // Detect via *_Data/app.info and create symlinks at the AppData path.
            // When multiple subdirectory targets exist (e.g. UGC/Models/ and
            // UGC/Dreams/), route each item to the correct target based on its
            // content (model files → Models/, level data → Dreams/, etc.).
            if (unityTargets.isNotEmpty()) {
                try {
                    val symlinker = WorkshopSymlinker()

                    // Clean up stale AppData subdirectories that were created
                    // by previous runs (e.g. "Companion Cube/" at AppData/UGC/
                    // which is a mod title, not a game-defined subdirectory).
                    // Preserve standard mod-category names that routing may create.
                    val standardSubdirNames = setOf(
                        "models", "dreams", "levels", "maps", "objects",
                        "mods", "addons", "plugins", "scenarios",
                    )
                    val validTargetNames = (
                        unityTargets.map { it.name.lowercase() } + standardSubdirNames
                    ).toSet()
                    unityTargets.mapNotNull { it.parentFile }.toSet().forEach { parentDir ->
                        if (parentDir.isDirectory) {
                            parentDir.listFiles()?.forEach { child ->
                                if (child.isDirectory &&
                                    child.name.lowercase() !in validTargetNames &&
                                    !child.name.startsWith(".")
                                ) {
                                    // Remove symlinks inside stale dirs, then remove dir
                                    child.listFiles()?.forEach { entry ->
                                        if (Files.isSymbolicLink(entry.toPath())) {
                                            Files.deleteIfExists(entry.toPath())
                                        }
                                    }
                                    if (child.listFiles()?.isEmpty() != false) {
                                        child.delete()
                                        Timber.tag(TAG).d("Removed stale AppData subdir: ${child.name}")
                                    }
                                }
                            }
                        }
                    }

                    // Group targets by parent to detect multi-subdirectory cases
                    val targetsByParent = unityTargets.groupBy { it.parentFile?.absolutePath ?: "" }
                    for ((_, targets) in targetsByParent) {
                        val hasModelsTarget = targets.any {
                            it.name.equals("Models", ignoreCase = true)
                        }
                        if (hasModelsTarget) {
                            // When a Models/ target exists, always use content
                            // routing — model files (.obj etc) → Models/, other
                            // content → Dreams/ (auto-created if needed).
                            routeItemsToUnityTargets(
                                targets, activeItemDirs, workshopContentDir,
                                titlesByItemId, symlinker,
                            )
                        } else if (targets.size > 1) {
                            // Multiple non-Models subdirectories: route by content
                            routeItemsToUnityTargets(
                                targets, activeItemDirs, workshopContentDir,
                                titlesByItemId, symlinker,
                            )
                        } else {
                            // Single target: all items go there
                            val result = symlinker.sync(
                                WorkshopModPathStrategy.SymlinkIntoDir(targets),
                                activeItemDirs, workshopContentDir, titlesByItemId,
                            )
                            Timber.tag(TAG).i(
                                "Unity AppData mod sync to ${targets.first().absolutePath}: created=${result.created}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Unity AppData mod path detection failed for '$gameName'")
                }
            }
        }

        // ── Clean stale Phase 6 symlinks for ISteamUGC-preferred games ────────
        // When the stdSeen heuristic suppresses filesystem symlinks, previous
        // launches may have created symlinks in the game's mod directories
        // (e.g. mods/). Remove them so the game doesn't see duplicate
        // workshop items (one from ISteamUGC/mods.json and one from the filesystem).
        if (stdSeenWithHighDir && winePrefix.isNotEmpty() && !hasManualModPath) {
            try {
                val detection = getOrDetectStrategy(gameRootDir, winePrefix, gameName)
                val strategy = detection.strategy
                if (strategy is WorkshopModPathStrategy.SymlinkIntoDir) {
                    for (dir in strategy.effectiveDirs) {
                        if (!dir.isDirectory) continue
                        dir.listFiles()?.forEach { entry ->
                            if (Files.isSymbolicLink(entry.toPath())) {
                                try {
                                    val linkTarget = Files.readSymbolicLink(entry.toPath())
                                    val resolved = if (linkTarget.isAbsolute) linkTarget
                                    else entry.toPath().parent.resolve(linkTarget)
                                    val resolvedStr = runCatching { resolved.toRealPath().toString() }
                                        .getOrElse { resolved.normalize().toAbsolutePath().toString() }
                                    if (resolvedStr.contains("workshop/content/") ||
                                        resolvedStr.startsWith(workshopContentDir.absolutePath)
                                    ) {
                                        Files.deleteIfExists(entry.toPath())
                                        Timber.tag(TAG).d(
                                            "Removed stale workshop symlink: ${entry.absolutePath}"
                                        )
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to clean stale symlinks for ISteamUGC-preferred game '$gameName'")
            }
        }


    }

    /**
     * Patches DepotDownloader's `SupportedWorkshopFileTypes` set to include
     * `EWorkshopFileType.First`, working around a JavaSteam enum bug where
     * `EWorkshopFileType.from(0)` returns `First` instead of `Community`
     * (both share code 0). Without this patch, all file_type=0 Workshop items
     * are skipped and the download hangs.
     */
    @Synchronized
    private fun patchSupportedWorkshopFileTypes() {
        if (workshopTypesPatched) return
        try {
            val field = DepotDownloader::class.java.getDeclaredField("SupportedWorkshopFileTypes")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val existingSet = field.get(null) as Set<EWorkshopFileType>
            if (!existingSet.contains(EWorkshopFileType.First)) {
                val patchedSet = LinkedHashSet(existingSet)
                patchedSet.add(EWorkshopFileType.First)
                field.set(null, patchedSet)
                Timber.tag(TAG).i(
                    "Patched SupportedWorkshopFileTypes to include First (enum bug workaround)"
                )
            }
            workshopTypesPatched = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e, "Failed to patch SupportedWorkshopFileTypes — file_type=0 items may not download"
            )
        }
    }

    private class WorkshopDownloadException(message: String) : Exception(message)

    // ────────────────────────────────────────────────────────────────
    // Shared helpers
    // ────────────────────────────────────────────────────────────────

    /** Deletes only symbolic links that are direct children of [dir]. */
    private fun removeSymlinksIn(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (Files.isSymbolicLink(f.toPath())) Files.deleteIfExists(f.toPath())
        }
    }

    /**
     * Deletes symbolic links and subdirectories (via [deleteRecursively]) that
     * are direct children of [dir]. Regular files are left untouched.
     */
    private fun clearModEntries(dir: File) {
        dir.listFiles()?.forEach { entry ->
            if (Files.isSymbolicLink(entry.toPath())) {
                Files.deleteIfExists(entry.toPath())
            } else if (entry.isDirectory) {
                entry.deleteRecursively()
            }
        }
    }

    /** Parses a comma-separated string of IDs into a [Set]. */
    fun parseEnabledIds(idsString: String?): Set<Long> =
        (idsString ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** Runs the full post-processing pipeline on downloaded workshop content. */
    suspend fun runPostProcessing(
        downloadedItems: List<WorkshopItem>?,
        allItems: List<WorkshopItem>,
        workshopContentDir: File,
        onStatus: ((String) -> Unit)? = null,
    ) {
        if (downloadedItems != null) {
            onStatus?.invoke("Fixing file names…")
            fixItemFileNames(downloadedItems, workshopContentDir)
        }
        onStatus?.invoke("Extracting archives…")
        extractCkmFiles(workshopContentDir)
        extractZipMods(workshopContentDir)
        decompressLzmaFiles(workshopContentDir) { completed, total ->
            onStatus?.invoke("Decompressing ($completed/$total)…")
        }
        onStatus?.invoke("Checking file types…")
        fixFileExtensions(workshopContentDir)
        updateMarkerTimestamps(allItems, workshopContentDir)
    }

    /** Configures mod symlinks for a given app. Public so callers don't duplicate the setup. */
    fun configureSymlinksForApp(
        context: Context,
        appId: Int,
        items: List<WorkshopItem>,
        winePrefix: String,
        workshopContentDir: File,
    ) {
        val gameRootDir = File(SteamService.getAppDirPath(appId))
        val gameName = SteamService.getAppInfoOf(appId)?.name ?: ""

        // Read the user's manual mod path override from the container
        val containerId = "STEAM_$appId"
        val modPathOverride = try {
            val container = ContainerUtils.getContainer(context, containerId)
            container.getExtra("workshopModPath", "")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read workshopModPath for appId $appId")
            ""
        }

        configureModSymlinks(
            gameRootDir = gameRootDir,
            workshopContentDir = workshopContentDir,
            items = items,
            winePrefix = winePrefix,
            gameName = gameName,
            workshopModPath = modPathOverride,
        )
    }

    /**
     * Checks available disk space against the required amount.
     * Returns an error message string if insufficient, or null if OK.
     */
    fun checkDiskSpace(dir: File, requiredBytes: Long): String? {
        val spaceDir = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }
        val availableBytes = spaceDir?.usableSpace ?: -1L
        if (requiredBytes > 0 && availableBytes >= 0 && requiredBytes > availableBytes) {
            val reqMB = String.format(java.util.Locale.US, "%.0f", requiredBytes / 1_048_576.0)
            val avlMB = String.format(java.util.Locale.US, "%.0f", availableBytes / 1_048_576.0)
            return "Not enough space (need $reqMB MB, have $avlMB MB)"
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    // Workshop download triggered from the library screen (save flow)
    // ────────────────────────────────────────────────────────────────

    private const val WORKSHOP_UPDATE_THRESHOLD = 100L * 1024 * 1024 // 100 MB

    /**
     * Starts a background workshop mod download, returning a [DownloadInfo] that
     * the library progress bar can observe.  Returns `null` when there is nothing
     * to download (all mods already up-to-date).
     *
     * The download is launched on [Dispatchers.IO]; callers should **not** await
     * the returned info — the library UI will pick it up automatically because
     * it is registered in [SteamService.downloadJobs].
     */
    fun startWorkshopDownload(
        appId: Int,
        enabledIds: Set<Long>,
        context: Context,
    ): DownloadInfo? {
        val steamClient = SteamService.instance?.steamClient ?: return null
        val steamId = SteamService.userSteamId ?: return null

        // Cancel any existing download for this app (e.g. user re-saved
        // with different mod selection while previous download was running).
        SteamService.getAppDownloadInfo(appId)?.cancel("Replaced by new workshop download")

        // Build the DownloadInfo first (synchronous) so the caller can rely
        // on it being in downloadJobs immediately after this returns.
        val info = DownloadInfo(
            jobCount = 1,
            gameId = appId,
            downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList(listOf(appId)),
        )
        info.updateStatusMessage(context.getString(R.string.workshop_checking_mods))

        SteamService.setAppDownloadInfo(appId, info)
        SteamService.workshopPausedApps.remove(appId)
        SteamService.notifyDownloadStarted(appId)

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Mark download as pending so it can be resumed if the app is killed
                SteamService.instance?.appDao?.setWorkshopDownloadPending(appId, true)

                Timber.tag(TAG).i("[WS] startWorkshopDownload: appId=$appId, enabledIds=${enabledIds.size}")

                val fetchResult = getSubscribedItems(appId, steamClient, steamId)
                val items = fetchResult.items.filter { it.publishedFileId in enabledIds }

                Timber.tag(TAG).i(
                    "[WS] Fetched ${fetchResult.items.size} subscribed items, " +
                        "${items.size} match enabledIds for appId=$appId"
                )

                if (items.isEmpty()) {
                    Timber.tag(TAG).i("Workshop download: no matching items for appId=$appId")
                    return@launch
                }

                // Ensure the container exists before downloading so that
                // workshopContentDir (which lives inside the container's
                // .wine prefix) doesn't accidentally pre-create the container
                // directory.  If that happens, ContainerManager.createContainer()
                // fails on first game launch (mkdirs returns false for an
                // existing dir), triggers the orphan-cleanup path which deletes
                // the entire directory including the just-downloaded mods.
                val containerId = "STEAM_$appId"
                try {
                    ContainerUtils.getOrCreateContainer(context, containerId)
                    Timber.tag(TAG).d("Container ensured for appId=$appId before workshop download")
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to ensure container for appId=$appId (workshop download will still proceed)")
                }

                val winePrefix = getContainerWinePrefix(context, appId)
                val workshopContentDir = getWorkshopContentDir(winePrefix, appId)

                // Clean up mods that were deselected
                if (fetchResult.isComplete) {
                    cleanupUnsubscribedItems(items, workshopContentDir)
                }

                val itemsToSync = getItemsNeedingSync(items, workshopContentDir)
                if (itemsToSync.isEmpty()) {
                    Timber.tag(TAG).i("Workshop download: all mods up-to-date for appId=$appId")
                    // Still configure symlinks for newly-enabled mods
                    configureSymlinksForApp(context, appId, items, winePrefix, workshopContentDir)
                    return@launch
                }

                // Set up byte tracking
                val totalBytes = itemsToSync.sumOf { it.fileSizeBytes }
                info.setTotalExpectedBytes(totalBytes)
                info.setWeight(0, totalBytes)

                // Check disk space (2x for decompression margin)
                val spaceError = checkDiskSpace(workshopContentDir, totalBytes * 2)
                if (spaceError != null) {
                    Timber.tag(TAG).e(spaceError)
                    info.updateStatusMessage(spaceError)
                    return@launch
                }

                val licenses = SteamService.getLicensesFromDb()
                var lastReportedBytes = 0L

                val firstName = itemsToSync.firstOrNull()?.title ?: ""
                info.updateStatusMessage("$firstName (0/${itemsToSync.size})")

                val successCount = downloadItems(
                    items = itemsToSync,
                    steamClient = steamClient,
                    licenses = licenses,
                    workshopContentDir = workshopContentDir,
                    onItemProgress = { completed, total, title ->
                        info.updateStatusMessage("$title ($completed/$total)")
                    },
                    onBytesProgress = { downloaded, _ ->
                        val delta = downloaded - lastReportedBytes
                        if (delta > 0) {
                            info.updateBytesDownloaded(delta)
                            info.emitProgressChange()
                        }
                        lastReportedBytes = downloaded
                    },
                )

                val failedCount = itemsToSync.size - successCount
                if (failedCount > 0) {
                    Timber.tag(TAG).w("$failedCount workshop mod(s) failed to download")
                }

                // Post-processing and symlinks
                info.updateStatusMessage(context.getString(R.string.workshop_processing))
                info.emitProgressChange()
                runPostProcessing(itemsToSync, items, workshopContentDir) { status ->
                    info.updateStatusMessage(status)
                    info.emitProgressChange()
                }
                configureSymlinksForApp(context, appId, items, winePrefix, workshopContentDir)
                Timber.tag(TAG).i("Workshop download complete for appId=$appId")
            } catch (e: CancellationException) {
                // Don't mark as "paused" if this was replaced by a new download
                // for the same app (the new info is already in downloadJobs).
                if (SteamService.getAppDownloadInfo(appId) === info) {
                    Timber.tag(TAG).i("Workshop download paused for appId=$appId")
                    SteamService.workshopPausedApps.add(appId)
                } else {
                    Timber.tag(TAG).i("Workshop download replaced for appId=$appId")
                }
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Workshop download failed for appId=$appId")
                info.updateStatusMessage(context.getString(R.string.workshop_download_failed))
            } finally {
                // Remove download indicator first — this must always run
                // so the UI never shows a permanently-stuck download.
                if (SteamService.getAppDownloadInfo(appId) === info) {
                    SteamService.removeDownloadJob(appId)
                }
                // Clear pending flag. Uses NonCancellable so the suspend
                // DB call completes even when the coroutine is cancelled
                // (e.g. WiFi lost, download replaced, user paused).
                if (!SteamService.workshopPausedApps.contains(appId)) {
                    try {
                        withContext(NonCancellable) {
                            SteamService.instance?.appDao?.setWorkshopDownloadPending(appId, false)
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        info.setDownloadJob(job)
        return info
    }

    /**
     * Checks for workshop mod updates at launch time. Returns the list of
     * items needing sync plus all subscribed items, or null if no updates
     * are needed (or workshop mods are disabled).
     */
    data class WorkshopUpdateCheck(
        val itemsToSync: List<WorkshopItem>,
        val allItems: List<WorkshopItem>,
        val workshopContentDir: File,
        val winePrefix: String,
        val totalUpdateBytes: Long,
    )

    suspend fun checkForWorkshopUpdates(
        appId: Int,
        enabledIds: Set<Long>,
        context: Context,
    ): WorkshopUpdateCheck? {
        val steamClient = SteamService.instance?.steamClient ?: return null
        val steamId = SteamService.userSteamId ?: return null

        val fetchResult = getSubscribedItems(appId, steamClient, steamId)

        if (!fetchResult.succeeded || !fetchResult.isComplete) {
            Timber.tag(TAG).w("Workshop fetch incomplete/failed for appId=$appId; skipping update check")
            return null
        }

        val items = fetchResult.items.filter { it.publishedFileId in enabledIds }

        val winePrefix = getContainerWinePrefix(context, appId)

        // Even if no enabled items remain, configure symlinks so stale
        // symlinks from previously-enabled mods are cleaned up.
        if (items.isEmpty()) {
            val workshopContentDir = getWorkshopContentDir(winePrefix, appId)
            configureSymlinksForApp(context, appId, emptyList(), winePrefix, workshopContentDir)
            return null
        }

        val workshopContentDir = getWorkshopContentDir(winePrefix, appId)

        cleanupUnsubscribedItems(items, workshopContentDir)

        val itemsToSync = getItemsNeedingSync(items, workshopContentDir)
        if (itemsToSync.isEmpty()) {
            // No updates, but still run post-processing and configure symlinks
            runPostProcessing(null, items, workshopContentDir)
            configureSymlinksForApp(context, appId, items, winePrefix, workshopContentDir)
            return null
        }

        Timber.tag(TAG).i(
            "[UpdateCheck] ${itemsToSync.size}/${items.size} items flagged as needing sync"
        )

        // Items flagged as "needing sync" may already have downloaded content
        // if the Steam API returned slightly different timeUpdated values
        // between the save-handler fetch and this launch-time fetch. Verify
        // that each item truly lacks content before prompting for re-download.
        val trulyMissing = itemsToSync.filter { item ->
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            val marker = File(itemDir, COMPLETE_MARKER)
            val markerExists = marker.exists()
            val hasContent = itemDir.listFiles()?.any { !it.name.startsWith(".") } == true
            val isMissing = !markerExists || !hasContent
            Timber.tag(TAG).d(
                "[UpdateCheck] Item ${item.publishedFileId} '${item.title}': " +
                    "marker=$markerExists, hasContent=$hasContent, " +
                    "dirExists=${itemDir.exists()}, isMissing=$isMissing"
            )
            isMissing
        }

        if (trulyMissing.isEmpty()) {
            // All items have content — timestamp mismatch from API, not a real update.
            // Update markers with the fresh values and configure symlinks normally.
            Timber.tag(TAG).i(
                "${itemsToSync.size} item(s) had stale timestamps but already have content — " +
                    "updating markers (API timestamp mismatch)"
            )
            updateMarkerTimestamps(items, workshopContentDir)
            runPostProcessing(null, items, workshopContentDir)
            configureSymlinksForApp(context, appId, items, winePrefix, workshopContentDir)
            return null
        }

        // If only some items truly need downloading, update markers for the
        // ones that already have content so they don't reappear next launch.
        if (trulyMissing.size < itemsToSync.size) {
            Timber.tag(TAG).i(
                "${itemsToSync.size - trulyMissing.size} item(s) had content with stale " +
                    "timestamps — markers updated, ${trulyMissing.size} truly need download"
            )
            updateMarkerTimestamps(items, workshopContentDir)
        }

        return WorkshopUpdateCheck(
            itemsToSync = trulyMissing,
            allItems = items,
            workshopContentDir = workshopContentDir,
            winePrefix = winePrefix,
            totalUpdateBytes = trulyMissing.sumOf { it.fileSizeBytes },
        )
    }

    fun getUpdateThresholdBytes(): Long = WORKSHOP_UPDATE_THRESHOLD
}
