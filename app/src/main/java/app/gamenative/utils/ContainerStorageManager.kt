package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.AppInfo
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.AppType
import app.gamenative.enums.OSArch
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object ContainerStorageManager {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StorageManagerDaoEntryPoint {
        fun steamAppDao(): SteamAppDao
        fun appInfoDao(): AppInfoDao
        fun gogGameDao(): GOGGameDao
        fun epicGameDao(): EpicGameDao
        fun amazonGameDao(): AmazonGameDao
    }

    enum class Status {
        READY,
        NO_CONTAINER,
        GAME_FILES_MISSING,
        ORPHANED,
        UNREADABLE,
    }

    enum class MoveTarget {
        INTERNAL,
        EXTERNAL,
    }

    enum class StorageLocation {
        INTERNAL,
        EXTERNAL,
        UNKNOWN,
    }

    data class VolumeInfo(
        val label: String,
        val freeBytes: Long,
        val totalBytes: Long,
    )

    suspend fun getInternalVolumeInfo(context: Context): VolumeInfo? = withContext(Dispatchers.IO) {
        try {
            VolumeInfo(
                label = context.getString(R.string.storage_internal),
                freeBytes = StorageUtils.getAvailableSpace(context.dataDir.path),
                totalBytes = StorageUtils.getTotalSpace(context.dataDir.path),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to query internal storage volume info")
            null
        }
    }

    suspend fun getExternalVolumeInfo(context: Context): VolumeInfo? = withContext(Dispatchers.IO) {
        if (!PrefManager.useExternalStorage) return@withContext null
        val externalPath = PrefManager.externalStoragePath
        if (externalPath.isBlank() || !File(externalPath).isDirectory) return@withContext null

        try {
            VolumeInfo(
                label = context.getString(R.string.storage_external),
                freeBytes = StorageUtils.getAvailableSpace(externalPath),
                totalBytes = StorageUtils.getTotalSpace(externalPath),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to query external storage volume info")
            null
        }
    }

    data class Entry(
        val containerId: String,
        val displayName: String,
        val gameSource: GameSource? = null,
        val appId: String? = null,
        val iconUrl: String = "",
        val containerSizeBytes: Long,
        val gameInstallSizeBytes: Long? = null,
        val status: Status,
        val installPath: String? = null,
        val canUninstallGame: Boolean = false,
        val hasContainer: Boolean = true,
        // gates "Reclaim Wine storage" button. true only when: (a) runtime=webview (html5
        // containers don't USE the wine distribution dirs -- pure waste) AND (b) at least one
        // of drive_c/{windows, Program Files, Program Files (x86)} exists on disk so there's
        // something to reclaim. wine-runtime containers actively USE those dirs; reclaiming
        // would break the next launch until ImageFsInstaller restored them.
        val canReclaimWineStorage: Boolean = false,
    ) {
        val combinedSizeBytes: Long?
            get() = when {
                gameInstallSizeBytes != null -> gameInstallSizeBytes + containerSizeBytes
                hasContainer -> containerSizeBytes
                else -> null
            }
    }

    private data class ResolvedGame(
        val name: String?,
        val installPath: String? = null,
        val iconUrl: String = "",
        val known: Boolean,
    )

    private data class InstalledGame(
        val appId: String,
        val displayName: String,
        val gameSource: GameSource,
        val installPath: String,
        val iconUrl: String = "",
        val installSizeBytes: Long? = null,
    )

    suspend fun loadEntries(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val homeDir = File(ImageFs.find(context).rootDir, "home")
        val prefix = "${ImageFs.USER}-"
        val dirs = homeDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) }
            .orEmpty()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            StorageManagerDaoEntryPoint::class.java,
        )
        val installedGames = loadInstalledGames(context, entryPoint)
        val claimedInstallPaths = installedGames.values
            .mapNotNull { it.installPath?.let(::normalizePath) }
            .toSet()

        Timber.tag("ContainerStorageManager").i(
            "Scanning storage inventory in %s (%d container dirs, %d installed games)",
            homeDir.absolutePath,
            dirs.size,
            installedGames.size,
        )

        val containerEntries = dirs.map { dir ->
            buildContainerEntry(context, dir, prefix, installedGames, claimedInstallPaths)
        }
        val coveredInstalledIds = containerEntries
            .mapTo(mutableSetOf()) { normalizeContainerId(it.containerId) }

        val installedOnlyEntries = installedGames.values
            .filterNot { coveredInstalledIds.contains(it.appId) }
            .map { installedGame -> buildInstalledOnlyEntry(installedGame) }

        val entries = (containerEntries + installedOnlyEntries)
            .sortedWith(compareByDescending<Entry> { it.combinedSizeBytes ?: 0L }.thenBy { it.displayName.lowercase() })

        Timber.tag("ContainerStorageManager").i(
            "Loaded %d storage entries (%d ready, %d no container, %d missing files, %d orphaned, %d unreadable)",
            entries.size,
            entries.count { it.status == Status.READY },
            entries.count { it.status == Status.NO_CONTAINER },
            entries.count { it.status == Status.GAME_FILES_MISSING },
            entries.count { it.status == Status.ORPHANED },
            entries.count { it.status == Status.UNREADABLE },
        )

        entries
    }

    fun isExternalStorageConfigured(): Boolean {
        return PrefManager.useExternalStorage &&
            PrefManager.externalStoragePath.isNotBlank() &&
            File(PrefManager.externalStoragePath).exists()
    }

    fun getStorageLocation(context: Context, entry: Entry): StorageLocation {
        val installPath = entry.installPath ?: return StorageLocation.UNKNOWN
        val gameSource = entry.gameSource ?: detectGameSource(normalizeContainerId(entry.containerId)) ?: return StorageLocation.UNKNOWN
        return getStorageLocation(context, gameSource, installPath)
    }

    fun canMoveToExternal(context: Context, entry: Entry): Boolean {
        return canMoveGame(entry) &&
            isExternalStorageConfigured() &&
            getStorageLocation(context, entry) == StorageLocation.INTERNAL
    }

    fun canMoveToInternal(context: Context, entry: Entry): Boolean {
        return canMoveGame(entry) &&
            getStorageLocation(context, entry) == StorageLocation.EXTERNAL
    }

    suspend fun moveGame(
        context: Context,
        entry: Entry,
        target: MoveTarget,
        onProgressUpdate: (currentFile: String, fileProgress: Float, movedFiles: Int, totalFiles: Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedContainerId = normalizeContainerId(entry.containerId)
        val gameSource = entry.gameSource ?: detectGameSource(normalizedContainerId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown game source"))

        if (!canMoveGame(entry)) {
            return@withContext Result.failure(IllegalArgumentException("This game cannot be moved"))
        }
        if (target == MoveTarget.EXTERNAL && !isExternalStorageConfigured()) {
            return@withContext Result.failure(IllegalStateException("External storage is not enabled"))
        }

        val sourceDir = File(entry.installPath.orEmpty())
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("Game install folder does not exist"))
        }

        val currentLocation = getStorageLocation(context, gameSource, sourceDir.absolutePath)
        when (target) {
            MoveTarget.EXTERNAL -> if (currentLocation != StorageLocation.INTERNAL) {
                return@withContext Result.failure(IllegalStateException("Game is not stored internally"))
            }

            MoveTarget.INTERNAL -> if (currentLocation != StorageLocation.EXTERNAL) {
                return@withContext Result.failure(IllegalStateException("Game is not stored externally"))
            }
        }

        val targetRoot = resolveTargetInstallRoot(context, gameSource, target)
            ?: return@withContext Result.failure(IllegalArgumentException("Unsupported game source"))
        val targetDir = File(targetRoot, sourceDir.name)

        if (samePath(sourceDir, targetDir)) {
            return@withContext Result.success(Unit)
        }
        if (targetDir.exists()) {
            return@withContext Result.failure(
                IllegalStateException("Destination already exists: ${targetDir.absolutePath}"),
            )
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            StorageManagerDaoEntryPoint::class.java,
        )

        val steamGameId = if (gameSource == GameSource.STEAM) {
            extractGameId(normalizedContainerId)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Steam game id"))
        } else {
            null
        }
        val gogGame = if (gameSource == GameSource.GOG) {
            val gameId = extractGameId(normalizedContainerId)?.toString()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid GOG game id"))
            entryPoint.gogGameDao().getById(gameId)
                ?: return@withContext Result.failure(IllegalStateException("GOG game not found in database"))
        } else {
            null
        }
        val epicGame = if (gameSource == GameSource.EPIC) {
            val gameId = extractGameId(normalizedContainerId)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Epic game id"))
            entryPoint.epicGameDao().getById(gameId)
                ?: return@withContext Result.failure(IllegalStateException("Epic game not found in database"))
        } else {
            null
        }
        val amazonGame = if (gameSource == GameSource.AMAZON) {
            val gameId = extractGameId(normalizedContainerId)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Amazon game id"))
            entryPoint.amazonGameDao().getByAppId(gameId)
                ?: return@withContext Result.failure(IllegalStateException("Amazon game not found in database"))
        } else {
            null
        }

        Timber.tag("ContainerStorageManager").i(
            "Moving game %s from %s to %s",
            entry.containerId,
            sourceDir.absolutePath,
            targetDir.absolutePath,
        )

        val moveResult = StorageUtils.moveDirectory(
            sourceDir = sourceDir.absolutePath,
            targetDir = targetDir.absolutePath,
            onProgressUpdate = onProgressUpdate,
        )
        if (moveResult.isFailure) {
            return@withContext moveResult
        }

        val installSize = entry.gameInstallSizeBytes ?: StorageUtils.getFolderSize(targetDir.absolutePath)
        when (gameSource) {
            GameSource.STEAM -> {
                steamGameId?.let { PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(it, GameSource.STEAM)) }
            }

            GameSource.GOG -> {
                gogGame?.let {
                    entryPoint.gogGameDao().update(it.copy(installPath = targetDir.absolutePath, installSize = installSize))
                }
            }

            GameSource.EPIC -> {
                epicGame?.let {
                    entryPoint.epicGameDao().update(it.copy(installPath = targetDir.absolutePath, installSize = installSize))
                }
            }

            GameSource.AMAZON -> {
                amazonGame?.let {
                    entryPoint.amazonGameDao().markAsInstalled(it.productId, targetDir.absolutePath, installSize, it.versionId)
                }
            }

            GameSource.CUSTOM_GAME -> {
                return@withContext Result.failure(UnsupportedOperationException("Custom games are not supported"))
            }
        }

        // html5 sidecar follow-up: WebViewContainer.installPath gets baked at fingerprint time
        // and is what WebViewScreen uses to find game assets at launch. without this, a moved
        // html5 container will fail to load (installPath points to the old location), opt-in
        // would re-run on relaunch and synthesize a NEW slug → NEW container.id → NEW origin →
        // user's saves "reset" because the WebView profile is keyed by origin. scan-by-id is
        // used (instead of recomputing the slug from store-service install path) so we don't
        // depend on the store DB update having propagated first.
        runCatching {
            val slug = app.gamenative.html5.host.WebViewScreenViewModel.slugFromAppId(normalizedContainerId)
            if (slug != null) {
                val existing = app.gamenative.runtime.WebViewContainer.load(slug)
                if (existing != null && existing.installPath != targetDir.absolutePath) {
                    app.gamenative.runtime.WebViewContainer.save(
                        slug,
                        existing.copy(installPath = targetDir.absolutePath),
                    )
                    Timber.tag("ContainerStorageManager").i(
                        "Updated html5 sidecar installPath: slug=%s old=%s new=%s",
                        slug,
                        existing.installPath,
                        targetDir.absolutePath,
                    )
                }
            }
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(
                it,
                "Failed to update html5 sidecar installPath for %s — game will fail to launch in html5 path",
                normalizedContainerId,
            )
        }

        Timber.tag("ContainerStorageManager").i(
            "Moved game %s successfully to %s",
            entry.containerId,
            targetDir.absolutePath,
        )

        Result.success(Unit)
    }

    suspend fun removeContainer(context: Context, containerId: String): Boolean = withContext(Dispatchers.IO) {
        val homeDir = File(ImageFs.find(context).rootDir, "home")
        val containerDir = File(homeDir, "${ImageFs.USER}-$containerId")
        if (!containerDir.exists()) {
            Timber.tag("ContainerStorageManager").w("Remove requested for missing container: %s", containerId)
            return@withContext false
        }

        Timber.tag("ContainerStorageManager").i(
            "Removing container %s at %s (exists=%s)",
            containerId,
            containerDir.absolutePath,
            containerDir.exists(),
        )

        // this path bypasses ContainerUtils.deleteContainer entirely
        // (FileUtils.delete on the dir directly), so the orphan-ControlsProfile cleanup added
        // in ec8a6405c never fires for Steam/GOG/Epic/Amazon library uninstalls routed through
        // the storage manager UI. fire it BEFORE FileUtils.delete so the html5-containers
        // JSON dir scan still resolves THIS container's profileId.
        val normalizedId = normalizeContainerId(containerId)
        runCatching {
            ContainerUtils.deleteHtml5ControlsProfileIfOrphan(context, normalizedId)
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(it, "html5 controls profile cleanup failed containerId=%s", containerId)
        }

        // origin-scoped LS+IDB scrub mirrors ContainerUtils.deleteContainer's call so
        // storage-manager uninstalls don't leave stale chromium state in the shared
        // app_webview/Default profile.
        runCatching {
            ContainerUtils.deleteHtml5OriginStorage(context, normalizedId)
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(it, "html5 origin storage cleanup failed containerId=%s", containerId)
        }

        // delete the html5-containers/<slug>/ JSON dir. mirrors ContainerUtils.deleteContainer.
        // runs AFTER deleteHtml5ControlsProfileIfOrphan above so the slug→profileId scan still
        // resolves THIS container before the JSON is gone.
        runCatching {
            ContainerUtils.deleteHtml5JsonDir(context, normalizedId)
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(it, "html5 json dir cleanup failed containerId=%s", containerId)
        }

        val deleted = try {
            FileUtils.delete(containerDir)
        } catch (e: Exception) {
            Timber.tag("ContainerStorageManager").e(e, "Failed to delete container directory: %s", containerId)
            false
        }

        if (deleted) {
            relinkActiveSymlinkIfNeeded(homeDir, containerDir)
            Timber.tag("ContainerStorageManager").i("Removed container %s successfully", containerId)
        } else {
            Timber.tag("ContainerStorageManager").w("Container removal reported failure for %s", containerId)
        }

        deleted
    }

    // Surgical wipe of Wine's distribution directories under the container's drive_c.
    // Reclaims ~1-1.5GB per container of `windows/`, `Program Files/`, `Program Files (x86)/` --
    // all of which are restored from imagefs on first launch via ImageFsInstaller. clearing
    // the `appVersion` extra is what trips ImageFsInstaller into "first launch" mode on the
    // next boot (see XServerScreen.kt:4287 firstBoot detection).
    //
    // user data (saves, registry tweaks, drive_c/users/, drive_c/ProgramData/, and
    // drive_c/Program Files (x86)/Steam/userdata/ -- Steam Cloud's local cache, recreated by
    // SteamAutoCloud on launch) is preserved. applies to both wine and html5 containers --
    // html5 containers still keep a wine prefix shell that accumulates the same junk.
    //
    // returns bytes reclaimed; null entries (no wine prefix, e.g. never launched) → 0.
    suspend fun reclaimWineStorage(context: Context, containerId: String): Result<Long> = withContext(Dispatchers.IO) {
        val normalizedId = normalizeContainerId(containerId)
        val homeDir = File(ImageFs.find(context).rootDir, "home")
        val containerDir = File(homeDir, "${ImageFs.USER}-$normalizedId")
        val driveC = File(containerDir, ".wine/drive_c")
        if (!driveC.exists() || !driveC.isDirectory) {
            Timber.tag("ContainerStorageManager").i(
                "Reclaim skipped for %s — drive_c absent (wine prefix never initialized)",
                normalizedId,
            )
            return@withContext Result.success(0L)
        }

        val targets = listOf("windows", "Program Files", "Program Files (x86)")
            .map { File(driveC, it) }
            .filter { it.exists() && it.isDirectory }

        if (targets.isEmpty()) {
            Timber.tag("ContainerStorageManager").i(
                "Reclaim no-op for %s — nothing to reclaim",
                normalizedId,
            )
            return@withContext Result.success(0L)
        }

        var totalBytes = 0L
        for (target in targets) {
            // Program Files (x86)/Steam/userdata/ is Steam Cloud's local-save cache, not wine
            // bloat. wiping it would force a full re-download on next launch and risks data
            // loss if the cloud copy is stale. preserve it alongside drive_c/users/.
            val preserveUserdata = target.name == "Program Files (x86)" &&
                File(target, "Steam/userdata").isDirectory
            val bytes = if (preserveUserdata) {
                deleteExceptSteamUserdata(target)
            } else {
                val s = runCatching { StorageUtils.getFolderSize(target.absolutePath) }.getOrDefault(0L)
                val deleted = runCatching { FileUtils.delete(target) }
                    .onFailure {
                        Timber.tag("ContainerStorageManager").w(it, "Failed to delete %s", target.absolutePath)
                    }
                    .getOrDefault(false)
                if (deleted) s else 0L
            }
            if (bytes > 0L) {
                totalBytes += bytes
                Timber.tag("ContainerStorageManager").i(
                    "Reclaimed %s from %s",
                    StorageUtils.formatBinarySize(bytes),
                    target.absolutePath,
                )
            }
        }

        // clear appVersion extra so the next wine launch sees firstBoot=true and ImageFsInstaller
        // restores the distribution dirs we just wiped. mirrors ImageFsInstaller.java:60
        // which does the same when re-installing imagefs from scratch.
        runCatching {
            val container = ContainerUtils.getContainer(context, normalizedId)
            container.putExtra("appVersion", null)
            container.saveData()
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(
                it,
                "Failed to clear appVersion for %s — next launch will skip imagefs restore, container will be unbootable",
                normalizedId,
            )
        }

        Timber.tag("ContainerStorageManager").i(
            "Reclaimed total %s from %s wine prefix",
            StorageUtils.formatBinarySize(totalBytes),
            normalizedId,
        )
        Result.success(totalBytes)
    }

    suspend fun uninstallGameAndContainer(context: Context, entry: Entry): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedContainerId = normalizeContainerId(entry.containerId)
        val gameSource = detectGameSource(normalizedContainerId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown game source"))
        val gameId = extractGameId(normalizedContainerId)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid container id"))

        Timber.tag("ContainerStorageManager").i(
            "Uninstalling game+container for %s (source=%s, gameId=%d, displayName=%s, hasContainer=%s)",
            entry.containerId,
            gameSource,
            gameId,
            entry.displayName,
            entry.hasContainer,
        )

        try {
            val result = when (gameSource) {
                GameSource.STEAM -> {
                    val deleted = SteamService.deleteApp(gameId)
                    if (!deleted) {
                        Result.failure(Exception("Failed to uninstall Steam game"))
                    } else {
                        if (entry.hasContainer) {
                            removeContainer(context, entry.containerId)
                        }
                        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameId, GameSource.STEAM))
                        Result.success(Unit)
                    }
                }

                GameSource.GOG -> {
                    val result = GOGService.deleteGame(
                        context,
                        LibraryItem(
                            appId = normalizedContainerId,
                            name = entry.displayName,
                            gameSource = GameSource.GOG,
                        ),
                    )
                    if (result.isSuccess && entry.hasContainer) removeContainer(context, entry.containerId)
                    result
                }

                GameSource.EPIC -> {
                    val result = EpicService.deleteGame(context, gameId)
                    if (result.isSuccess && entry.hasContainer) removeContainer(context, entry.containerId)
                    result
                }

                GameSource.AMAZON -> {
                    val productId = AmazonService.getProductIdByAppId(gameId)
                        ?: return@withContext Result.failure(Exception("Amazon product id not found"))
                    val result = AmazonService.deleteGame(context, productId)
                    if (result.isSuccess && entry.hasContainer) removeContainer(context, entry.containerId)
                    result
                }

                GameSource.CUSTOM_GAME -> Result.failure(UnsupportedOperationException("Custom games are not supported"))
            }

            if (result.isSuccess) {
                Timber.tag("ContainerStorageManager").i("Uninstall game+container succeeded for %s", entry.containerId)
            } else {
                Timber.tag("ContainerStorageManager").w(
                    "Uninstall game+container failed for %s: %s",
                    entry.containerId,
                    result.exceptionOrNull()?.message,
                )
            }

            result
        } catch (e: Exception) {
            Timber.tag("ContainerStorageManager").e(e, "Failed to uninstall game and container: %s", entry.containerId)
            Result.failure(e)
        }
    }

    private suspend fun loadInstalledGames(
        context: Context,
        entryPoint: StorageManagerDaoEntryPoint,
    ): Map<String, InstalledGame> {
        val installedGames = linkedMapOf<String, InstalledGame>()

        runCatching {
            loadSteamInstalledGames(entryPoint.steamAppDao(), entryPoint.appInfoDao())
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed Steam games")
        }

        runCatching {
            entryPoint.gogGameDao().getAllAsList()
                .asSequence()
                .filter { it.isInstalled && it.installPath.isNotBlank() }
                .mapNotNull { game ->
                    val installDir = File(game.installPath)
                    if (!installDir.exists()) return@mapNotNull null
                    InstalledGame(
                        appId = "${GameSource.GOG.name}_${game.id}",
                        displayName = game.title.ifBlank { game.id },
                        gameSource = GameSource.GOG,
                        installPath = installDir.absolutePath,
                        iconUrl = game.iconUrl.ifEmpty { game.imageUrl },
                        installSizeBytes = game.installSize.takeIf { it > 0L },
                    )
                }
                .toList()
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed GOG games")
        }

        runCatching {
            entryPoint.epicGameDao().getAllAsList()
                .asSequence()
                .filter { it.isInstalled && it.installPath.isNotBlank() }
                .mapNotNull { game ->
                    val installDir = File(game.installPath)
                    if (!installDir.exists()) return@mapNotNull null
                    InstalledGame(
                        appId = "${GameSource.EPIC.name}_${game.id}",
                        displayName = game.title.ifBlank { game.appName.ifBlank { game.id.toString() } },
                        gameSource = GameSource.EPIC,
                        installPath = installDir.absolutePath,
                        iconUrl = game.iconUrl,
                        installSizeBytes = game.installSize.takeIf { it > 0L },
                    )
                }
                .toList()
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed Epic games")
        }

        runCatching {
            entryPoint.amazonGameDao().getAllAsList()
                .asSequence()
                .filter { it.isInstalled && it.installPath.isNotBlank() }
                .mapNotNull { game ->
                    val installDir = File(game.installPath)
                    if (!installDir.exists()) return@mapNotNull null
                    InstalledGame(
                        appId = "${GameSource.AMAZON.name}_${game.appId}",
                        displayName = game.title.ifBlank { game.productId },
                        gameSource = GameSource.AMAZON,
                        installPath = installDir.absolutePath,
                        iconUrl = game.artUrl,
                        installSizeBytes = game.installSize.takeIf { it > 0L },
                    )
                }
                .toList()
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed Amazon games")
        }

        runCatching {
            CustomGameScanner.scanAsLibraryItems(query = "")
                .mapNotNull { item ->
                    val folderPath = CustomGameScanner.getFolderPathFromAppId(item.appId) ?: return@mapNotNull null
                    val folder = File(folderPath)
                    if (!folder.exists() || !folder.isDirectory) return@mapNotNull null
                    // unlike Steam/GOG/Epic/Amazon, custom games have no DAO row holding install
                    // size -- they're sideloaded folders. compute on the fly so the storage card
                    // shows real numbers instead of just the (tiny) container dir.
                    val size = runCatching { StorageUtils.getFolderSize(folder.absolutePath) }
                        .getOrDefault(0L)
                        .takeIf { it > 0L }
                    InstalledGame(
                        appId = item.appId,
                        displayName = item.name.ifBlank { folder.name },
                        gameSource = GameSource.CUSTOM_GAME,
                        installPath = folder.absolutePath,
                        iconUrl = item.clientIconUrl,
                        installSizeBytes = size,
                    )
                }
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed Custom games")
        }

        return installedGames
    }

    private suspend fun loadSteamInstalledGames(steamAppDao: SteamAppDao, appInfoDao: AppInfoDao): List<InstalledGame> {
        val appInfosById = appInfoDao.getAll().associateBy { it.id }
        val recoveredAppInfos = mutableListOf<AppInfo>()

        val ownerByPath = steamAppDao.getAllOwnedAppsAsList()
            .mapNotNull { app -> resolveSteamInstallPath(app)?.let { app to it } }
            .groupBy { (_, path) -> normalizePath(path) }
            .mapValues { (_, group) ->
                group.minWith(
                    compareBy(
                        { (app, _) -> if (app.type == AppType.game) 0 else 1 },
                        { (app, _) -> if (appInfosById[app.id]?.downloadedDepots?.isNotEmpty() == true) 0 else 1 },
                        { (app, _) -> app.id },
                    ),
                )
            }

        val installedGames = ownerByPath.values.map { (app, installPath) ->
            val appInfo = appInfosById[app.id]
            val installSizeBytes = estimateSteamInstallSize(app)
                ?: appInfo?.recoveredInstallSizeBytes?.takeIf { it > 0L }
                ?: getDirectorySizeIfPresent(installPath)?.also { recoveredSizeBytes ->
                    buildRecoveredSteamInstallSizeAppInfo(appInfo, app.id, recoveredSizeBytes)?.let { updatedAppInfo ->
                        recoveredAppInfos += updatedAppInfo
                    }
                    Timber.tag("ContainerStorageManager").i(
                        "Recovered and cached on-disk Steam size for %s (%s) because installed depot metadata is unavailable",
                        app.id,
                        installPath,
                    )
                }
            InstalledGame(
                appId = "${GameSource.STEAM.name}_${app.id}",
                displayName = app.name.ifBlank { app.id.toString() },
                gameSource = GameSource.STEAM,
                installPath = installPath,
                iconUrl = app.clientIconUrl.takeIf { app.clientIconHash.isNotEmpty() }.orEmpty(),
                installSizeBytes = installSizeBytes,
            )
        }

        if (recoveredAppInfos.isNotEmpty()) {
            appInfoDao.insertAll(recoveredAppInfos)
        }

        return installedGames
    }

    private fun resolveSteamInstallPath(app: SteamApp): String? {
        val installNames = listOf(
            SteamService.getAppDirName(app),
            app.name,
        )
            .filter { it.isNotBlank() }
            .distinct()

        if (installNames.isEmpty()) return null

        val searchRoots = buildList {
            add(SteamService.internalAppInstallPath)
            add(SteamService.externalAppInstallPath)
            addAll(
                DownloadService.externalVolumePaths.map { volumePath ->
                    Paths.get(volumePath, "Steam", "steamapps", "common").toString()
                },
            )
        }
            .distinct()

        return searchRoots.asSequence()
            .flatMap { root -> installNames.asSequence().map { name -> File(root, name) } }
            .firstOrNull { it.exists() && it.isDirectory }
            ?.absolutePath
    }

    private suspend fun buildContainerEntry(
        context: Context,
        dir: File,
        prefix: String,
        installedGames: Map<String, InstalledGame>,
        claimedInstallPaths: Set<String>,
    ): Entry {
        val containerId = dir.name.removePrefix(prefix)
        val normalizedContainerId = normalizeContainerId(containerId)
        val installedGame = installedGames[normalizedContainerId]
        val gameSource = installedGame?.gameSource ?: detectGameSource(normalizedContainerId)
        val containerSizeBytes = getContainerDirectorySize(dir.toPath())
        val configFile = File(dir, ".container")

        val config = readConfig(configFile)
        if (config == null) {
            return Entry(
                containerId = containerId,
                displayName = installedGame?.displayName ?: containerId,
                gameSource = gameSource,
                appId = installedGame?.appId ?: normalizedContainerId.takeIf { gameSource != null },
                iconUrl = installedGame?.iconUrl.orEmpty(),
                containerSizeBytes = containerSizeBytes,
                gameInstallSizeBytes = resolveInstallSizeBytes(installedGame?.installSizeBytes),
                status = Status.UNREADABLE,
                installPath = installedGame?.installPath,
                canUninstallGame = installedGame != null && installedGame.gameSource != GameSource.CUSTOM_GAME,
                hasContainer = true,
            )
        }

        val resolved = installedGame?.toResolvedGame() ?: run {
            val gameId = extractGameId(normalizedContainerId)
            if (gameSource != null && gameId != null) {
                resolveGame(context, gameSource, gameId, normalizedContainerId)
            } else {
                null
            }
        }

        val installPath = resolved?.installPath?.takeIf { it.isNotBlank() }
        val installPathOwnedByOtherApp = installedGame == null &&
            installPath != null &&
            normalizePath(installPath) in claimedInstallPaths
        val displayName = installedGame?.displayName?.takeIf { it.isNotBlank() }
            ?: resolved?.name?.takeIf { it.isNotBlank() }
            ?: config.optString("name", "").takeIf { it.isNotBlank() }
            ?: containerId

        val status = when {
            installedGame != null -> Status.READY
            installPathOwnedByOtherApp -> Status.ORPHANED
            resolved == null || !resolved.known -> Status.ORPHANED
            installPath.isNullOrBlank() -> Status.GAME_FILES_MISSING
            !File(installPath).exists() -> Status.GAME_FILES_MISSING
            else -> Status.READY
        }

        val gameInstallSizeBytes = if (status == Status.READY) {
            resolveInstallSizeBytes(installedGame?.installSizeBytes)
        } else {
            null
        }

        val runtime = config.optString("runtime", com.winlator.container.Container.RUNTIME_WINE)
        val isWebviewRuntime = runtime.equals(com.winlator.container.Container.RUNTIME_WEBVIEW, ignoreCase = true)
        val canReclaimWineStorage = isWebviewRuntime && hasReclaimableWineDirs(dir)

        return Entry(
            containerId = containerId,
            displayName = displayName,
            gameSource = gameSource,
            appId = installedGame?.appId ?: normalizedContainerId.takeIf { gameSource != null },
            iconUrl = installedGame?.iconUrl ?: resolved?.iconUrl.orEmpty(),
            containerSizeBytes = containerSizeBytes,
            gameInstallSizeBytes = gameInstallSizeBytes,
            status = status,
            installPath = installPath,
            canUninstallGame = (status == Status.READY || status == Status.GAME_FILES_MISSING) &&
                gameSource != null && gameSource != GameSource.CUSTOM_GAME,
            hasContainer = true,
            canReclaimWineStorage = canReclaimWineStorage,
        )
    }

    // delete everything under Program Files (x86)/ except Steam/userdata/. returns bytes freed.
    private suspend fun deleteExceptSteamUserdata(pfx86: File): Long {
        var freed = 0L
        val children = pfx86.listFiles() ?: return 0L
        for (child in children) {
            if (child.name == "Steam") continue
            val s = runCatching { StorageUtils.getFolderSize(child.absolutePath) }.getOrDefault(0L)
            val ok = runCatching { FileUtils.delete(child) }
                .onFailure { Timber.tag("ContainerStorageManager").w(it, "Failed to delete %s", child.absolutePath) }
                .getOrDefault(false)
            if (ok) freed += s
        }
        val steam = File(pfx86, "Steam").takeIf { it.isDirectory } ?: return freed
        val steamChildren = steam.listFiles() ?: return freed
        for (child in steamChildren) {
            if (child.name == "userdata") continue
            val s = runCatching { StorageUtils.getFolderSize(child.absolutePath) }.getOrDefault(0L)
            val ok = runCatching { FileUtils.delete(child) }
                .onFailure { Timber.tag("ContainerStorageManager").w(it, "Failed to delete %s", child.absolutePath) }
                .getOrDefault(false)
            if (ok) freed += s
        }
        return freed
    }

    // UI gate -- don't show "Reclaim" when nothing meaningful is reclaimable.
    // windows/ and Program Files/ are pure wine bloat; existence is enough.
    // Program Files (x86)/ is recreated by Steam Cloud sync writing to
    // Steam/userdata/<accountId>/... (PathType.SteamUserData / DEFAULT), so existence
    // alone is a false positive -- only count it when there's something OTHER than
    // Steam/userdata/ inside.
    private fun hasReclaimableWineDirs(containerDir: File): Boolean {
        val driveC = File(containerDir, ".wine/drive_c")
        if (!driveC.isDirectory) return false
        if (File(driveC, "windows").isDirectory) return true
        if (File(driveC, "Program Files").isDirectory) return true
        val pfx86 = File(driveC, "Program Files (x86)")
        if (!pfx86.isDirectory) return false
        val children = pfx86.listFiles() ?: return false
        if (children.any { it.name != "Steam" }) return true
        val steam = File(pfx86, "Steam").takeIf { it.isDirectory } ?: return false
        val steamChildren = steam.listFiles() ?: return false
        return steamChildren.any { it.name != "userdata" }
    }

    private fun buildInstalledOnlyEntry(
        installedGame: InstalledGame,
    ): Entry {
        return Entry(
            containerId = installedGame.appId,
            displayName = installedGame.displayName,
            gameSource = installedGame.gameSource,
            appId = installedGame.appId,
            iconUrl = installedGame.iconUrl,
            containerSizeBytes = 0L,
            gameInstallSizeBytes = resolveInstallSizeBytes(installedGame.installSizeBytes),
            status = Status.NO_CONTAINER,
            installPath = installedGame.installPath,
            canUninstallGame = installedGame.gameSource != GameSource.CUSTOM_GAME,
            hasContainer = false,
        )
    }

    private fun canMoveGame(entry: Entry): Boolean {
        return (entry.status == Status.READY || entry.status == Status.NO_CONTAINER) &&
            entry.gameSource != null &&
            entry.gameSource != GameSource.CUSTOM_GAME &&
            !entry.installPath.isNullOrBlank()
    }

    private fun resolveTargetInstallRoot(context: Context, gameSource: GameSource, target: MoveTarget): String? {
        return when (gameSource) {
            GameSource.STEAM -> when (target) {
                MoveTarget.INTERNAL -> SteamService.internalAppInstallPath
                MoveTarget.EXTERNAL -> SteamService.externalAppInstallPath
            }

            GameSource.GOG -> when (target) {
                MoveTarget.INTERNAL -> GOGConstants.internalGOGGamesPath
                MoveTarget.EXTERNAL -> GOGConstants.externalGOGGamesPath
            }

            GameSource.EPIC -> when (target) {
                MoveTarget.INTERNAL -> EpicConstants.internalEpicGamesPath(context)
                MoveTarget.EXTERNAL -> EpicConstants.externalEpicGamesPath()
            }

            GameSource.AMAZON -> when (target) {
                MoveTarget.INTERNAL -> AmazonConstants.internalAmazonGamesPath(context)
                MoveTarget.EXTERNAL -> AmazonConstants.externalAmazonGamesPath()
            }

            GameSource.CUSTOM_GAME -> null
        }
    }

    private fun getStorageLocation(context: Context, gameSource: GameSource, installPath: String): StorageLocation {
        val normalizedPath = normalizePath(installPath)

        val internalRoots = when (gameSource) {
            GameSource.STEAM -> listOf(SteamService.internalAppInstallPath)
            GameSource.GOG -> listOf(GOGConstants.internalGOGGamesPath)
            GameSource.EPIC -> listOf(EpicConstants.internalEpicGamesPath(context))
            GameSource.AMAZON -> listOf(AmazonConstants.internalAmazonGamesPath(context))
            GameSource.CUSTOM_GAME -> emptyList()
        }

        if (internalRoots.any { root -> isPathWithin(normalizedPath, root) }) {
            return StorageLocation.INTERNAL
        }

        val externalRoots = buildList {
            when (gameSource) {
                GameSource.STEAM -> {
                    if (PrefManager.externalStoragePath.isNotBlank()) {
                        add(SteamService.externalAppInstallPath)
                    }
                    addAll(
                        DownloadService.externalVolumePaths.map { volumePath ->
                            Paths.get(volumePath, "Steam", "steamapps", "common").toString()
                        },
                    )
                }

                GameSource.GOG -> if (PrefManager.externalStoragePath.isNotBlank()) add(GOGConstants.externalGOGGamesPath)
                GameSource.EPIC -> if (PrefManager.externalStoragePath.isNotBlank()) add(EpicConstants.externalEpicGamesPath())
                GameSource.AMAZON -> if (PrefManager.externalStoragePath.isNotBlank()) add(AmazonConstants.externalAmazonGamesPath())
                GameSource.CUSTOM_GAME -> Unit
            }

            inferExternalInstallRoot(gameSource, normalizedPath)?.let(::add)
        }
            .filter { it.isNotBlank() }
            .distinct()

        if (externalRoots.any { root -> isPathWithin(normalizedPath, root) }) {
            return StorageLocation.EXTERNAL
        }

        return StorageLocation.UNKNOWN
    }

    private fun inferExternalInstallRoot(gameSource: GameSource, installPath: String): String? {
        val expectedRootSegments = when (gameSource) {
            GameSource.STEAM -> listOf("Steam", "steamapps", "common")
            GameSource.GOG -> listOf("GOG", "games", "common")
            GameSource.EPIC -> listOf("Epic", "games")
            GameSource.AMAZON -> listOf("Amazon", "games")
            GameSource.CUSTOM_GAME -> emptyList()
        }
        if (expectedRootSegments.isEmpty()) return null

        val candidateRoot = File(installPath).parentFile?.path?.let(::normalizePath) ?: return null
        return candidateRoot.takeIf { pathEndsWithSegments(it, expectedRootSegments) }
    }

    private fun pathEndsWithSegments(path: String, expectedSegments: List<String>): Boolean {
        val normalizedPath = runCatching { Paths.get(path) }.getOrNull() ?: return false
        if (normalizedPath.nameCount < expectedSegments.size) return false

        return expectedSegments.indices.all { index ->
            normalizedPath.getName(normalizedPath.nameCount - expectedSegments.size + index).toString() == expectedSegments[index]
        }
    }

    private fun samePath(first: File, second: File): Boolean {
        return normalizePath(first.path) == normalizePath(second.path)
    }

    private fun isPathWithin(path: String, root: String): Boolean {
        if (root.isBlank()) return false
        val normalizedRoot = normalizePath(root)
        return path == normalizedRoot || path.startsWith(normalizedRoot + File.separator)
    }

    private fun normalizePath(path: String): String {
        return runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
    }

    private fun InstalledGame.toResolvedGame(): ResolvedGame = ResolvedGame(
        name = displayName,
        installPath = installPath,
        iconUrl = iconUrl,
        known = true,
    )

    private fun resolveInstallSizeBytes(persistedSize: Long?): Long? {
        return persistedSize?.takeIf { it > 0L }
    }

    private fun estimateSteamInstallSize(app: SteamApp): Long? {
        val installedDepotIds = SteamService.getInstalledDepotsOf(app.id).orEmpty().toSet()
        if (installedDepotIds.isEmpty()) return null

        return app.depots.values
            .filter { depot -> installedDepotIds.contains(depot.depotId) }
            .sumOf { depot -> depot.manifests["public"]?.size ?: depot.manifests.values.firstOrNull()?.size ?: 0L }
            .takeIf { it > 0L }
    }

    private fun getDirectorySizeIfPresent(path: String): Long? {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return null
        return getContainerDirectorySize(dir.toPath()).takeIf { it > 0L }
    }

    private fun buildRecoveredSteamInstallSizeAppInfo(
        existingAppInfo: AppInfo?,
        appId: Int,
        recoveredSizeBytes: Long,
    ): AppInfo? {
        if (recoveredSizeBytes <= 0L || existingAppInfo?.recoveredInstallSizeBytes == recoveredSizeBytes) return null

        return existingAppInfo?.copy(
            isDownloaded = true,
            recoveredInstallSizeBytes = recoveredSizeBytes,
        )
    }

    private fun readConfig(configFile: File): JSONObject? {
        if (!configFile.exists() || !configFile.isFile) return null
        return try {
            val content = configFile.readText().trim()
            if (content.isEmpty()) null else JSONObject(content)
        } catch (e: Exception) {
            Timber.tag("ContainerStorageManager").w(e, "Failed to read config: ${configFile.absolutePath}")
            null
        }
    }

    private fun getContainerDirectorySize(root: Path): Long {
        if (!Files.isDirectory(root)) return 0L

        var totalBytes = 0L
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        totalBytes += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                        Timber.tag("ContainerStorageManager").w(exc, "Failed to size file: $file")
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(it, "Failed to calculate size for $root")
        }

        return totalBytes
    }

    internal fun normalizeContainerId(containerId: String): String = containerId.substringBefore("(")

    internal fun detectGameSource(containerId: String): GameSource? = when {
        GameSource.STEAM.matches(containerId) -> GameSource.STEAM
        GameSource.CUSTOM_GAME.matches(containerId) -> GameSource.CUSTOM_GAME
        GameSource.GOG.matches(containerId) -> GameSource.GOG
        GameSource.EPIC.matches(containerId) -> GameSource.EPIC
        GameSource.AMAZON.matches(containerId) -> GameSource.AMAZON
        else -> null
    }

    internal fun extractGameId(containerId: String): Int? = containerId.substringAfterLast('_').toIntOrNull()

    private fun resolveGame(
        context: Context,
        gameSource: GameSource,
        gameId: Int,
        normalizedContainerId: String,
    ): ResolvedGame {
        return when (gameSource) {
            GameSource.STEAM -> {
                val app = SteamService.getAppInfoOf(gameId)
                ResolvedGame(
                    name = app?.name,
                    installPath = SteamService.getAppDirPath(gameId),
                    iconUrl = app?.clientIconUrl?.takeIf { app.clientIconHash.isNotEmpty() }.orEmpty(),
                    known = app != null,
                )
            }

            GameSource.CUSTOM_GAME -> {
                val folderPath = CustomGameScanner.getFolderPathFromAppId(normalizedContainerId)
                ResolvedGame(
                    name = folderPath?.let { File(it).name },
                    installPath = folderPath,
                    iconUrl = if (folderPath != null) {
                        LibraryItem(appId = normalizedContainerId, gameSource = GameSource.CUSTOM_GAME).clientIconUrl
                    } else {
                        ""
                    },
                    known = folderPath != null,
                )
            }

            GameSource.GOG -> {
                val game = GOGService.getGOGGameOf(gameId.toString())
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
                    iconUrl = game?.iconUrl?.ifEmpty { game.imageUrl }.orEmpty(),
                    known = game != null,
                )
            }

            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId)
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
                    iconUrl = game?.iconUrl.orEmpty(),
                    known = game != null,
                )
            }

            GameSource.AMAZON -> {
                val productId = AmazonService.getProductIdByAppId(gameId)
                val game = productId?.let { AmazonService.getAmazonGameOf(it) }
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
                    iconUrl = game?.artUrl.orEmpty(),
                    known = game != null,
                )
            }
        }
    }

    private fun relinkActiveSymlinkIfNeeded(homeDir: File, deletedContainerDir: File) {
        val activeLink = File(homeDir, ImageFs.USER)
        val pointsToDeleted = runCatching {
            activeLink.exists() && activeLink.canonicalFile == deletedContainerDir.canonicalFile
        }.getOrDefault(false)

        if (!pointsToDeleted) return

        runCatching {
            activeLink.delete()
            homeDir.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.startsWith("${ImageFs.USER}-") }
                ?.let { fallback ->
                    FileUtils.symlink("./${fallback.name}", activeLink.path)
                }
        }.onFailure {
            Timber.tag("ContainerStorageManager").w(it, "Failed to relink active container symlink")
        }
    }
}
