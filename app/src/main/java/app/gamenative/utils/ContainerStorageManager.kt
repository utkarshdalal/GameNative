package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object ContainerStorageManager {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StorageManagerDaoEntryPoint {
        fun steamAppDao(): SteamAppDao
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

    data class Entry(
        val containerId: String,
        val displayName: String,
        val gameSource: GameSource? = null,
        val containerSizeBytes: Long,
        val gameInstallSizeBytes: Long? = null,
        val status: Status,
        val installPath: String? = null,
        val canUninstallGame: Boolean = false,
        val hasContainer: Boolean = true,
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
        val known: Boolean,
    )

    private data class InstalledGame(
        val appId: String,
        val displayName: String,
        val gameSource: GameSource,
        val installPath: String,
    )

    suspend fun loadEntries(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val homeDir = File(ImageFs.find(context).rootDir, "home")
        val prefix = "${ImageFs.USER}-"
        val dirs = homeDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) }
            .orEmpty()
        val pathSizeCache = mutableMapOf<String, Long>()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            StorageManagerDaoEntryPoint::class.java,
        )
        val installedGames = loadInstalledGames(context, entryPoint)

        Timber.tag("ContainerStorageManager").i(
            "Scanning storage inventory in %s (%d container dirs, %d installed games)",
            homeDir.absolutePath,
            dirs.size,
            installedGames.size,
        )

        val containerEntries = dirs.map { dir ->
            buildContainerEntry(context, dir, prefix, pathSizeCache, installedGames)
        }
        val coveredInstalledIds = containerEntries
            .mapTo(mutableSetOf()) { normalizeContainerId(it.containerId) }

        val installedOnlyEntries = installedGames.values
            .filterNot { coveredInstalledIds.contains(it.appId) }
            .map { installedGame -> buildInstalledOnlyEntry(installedGame, pathSizeCache) }

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
                        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameId))
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
            loadSteamInstalledGames(entryPoint.steamAppDao())
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
                    InstalledGame(
                        appId = item.appId,
                        displayName = item.name.ifBlank { folder.name },
                        gameSource = GameSource.CUSTOM_GAME,
                        installPath = folder.absolutePath,
                    )
                }
        }.onSuccess { games ->
            games.forEach { installedGames[it.appId] = it }
        }.onFailure { e ->
            Timber.tag("ContainerStorageManager").w(e, "Failed to load installed Custom games")
        }

        return installedGames
    }

    private suspend fun loadSteamInstalledGames(steamAppDao: SteamAppDao): List<InstalledGame> {
        return steamAppDao.getAllOwnedAppsAsList()
            .mapNotNull { app ->
                val installPath = resolveSteamInstallPath(app) ?: return@mapNotNull null
                InstalledGame(
                    appId = "${GameSource.STEAM.name}_${app.id}",
                    displayName = app.name.ifBlank { app.id.toString() },
                    gameSource = GameSource.STEAM,
                    installPath = installPath,
                )
            }
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
        pathSizeCache: MutableMap<String, Long>,
        installedGames: Map<String, InstalledGame>,
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
                containerSizeBytes = containerSizeBytes,
                gameInstallSizeBytes = installedGame?.installPath?.let { getPathSize(it, pathSizeCache) },
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
        val displayName = installedGame?.displayName?.takeIf { it.isNotBlank() }
            ?: resolved?.name?.takeIf { it.isNotBlank() }
            ?: config.optString("name", "").takeIf { it.isNotBlank() }
            ?: containerId

        val status = when {
            installedGame != null -> Status.READY
            resolved == null || !resolved.known -> Status.ORPHANED
            installPath.isNullOrBlank() -> Status.GAME_FILES_MISSING
            !File(installPath).exists() -> Status.GAME_FILES_MISSING
            else -> Status.READY
        }

        val gameInstallSizeBytes = installPath
            ?.takeIf { status == Status.READY }
            ?.let { path -> getPathSize(path, pathSizeCache) }

        return Entry(
            containerId = containerId,
            displayName = displayName,
            gameSource = gameSource,
            containerSizeBytes = containerSizeBytes,
            gameInstallSizeBytes = gameInstallSizeBytes,
            status = status,
            installPath = installPath,
            canUninstallGame = status == Status.READY && gameSource != null && gameSource != GameSource.CUSTOM_GAME,
            hasContainer = true,
        )
    }

    private fun buildInstalledOnlyEntry(
        installedGame: InstalledGame,
        pathSizeCache: MutableMap<String, Long>,
    ): Entry {
        return Entry(
            containerId = installedGame.appId,
            displayName = installedGame.displayName,
            gameSource = installedGame.gameSource,
            containerSizeBytes = 0L,
            gameInstallSizeBytes = getPathSize(installedGame.installPath, pathSizeCache),
            status = Status.NO_CONTAINER,
            installPath = installedGame.installPath,
            canUninstallGame = installedGame.gameSource != GameSource.CUSTOM_GAME,
            hasContainer = false,
        )
    }

    private fun InstalledGame.toResolvedGame(): ResolvedGame = ResolvedGame(
        name = displayName,
        installPath = installPath,
        known = true,
    )

    private fun getPathSize(path: String, pathSizeCache: MutableMap<String, Long>): Long {
        return pathSizeCache.getOrPut(path) { getContainerDirectorySize(File(path).toPath()) }
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
        containerId.startsWith("STEAM_") -> GameSource.STEAM
        containerId.startsWith("CUSTOM_GAME_") -> GameSource.CUSTOM_GAME
        containerId.startsWith("GOG_") -> GameSource.GOG
        containerId.startsWith("EPIC_") -> GameSource.EPIC
        containerId.startsWith("AMAZON_") -> GameSource.AMAZON
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
                    known = app != null,
                )
            }

            GameSource.CUSTOM_GAME -> {
                val folderPath = CustomGameScanner.getFolderPathFromAppId(normalizedContainerId)
                ResolvedGame(
                    name = folderPath?.let { File(it).name },
                    installPath = folderPath,
                    known = folderPath != null,
                )
            }

            GameSource.GOG -> {
                val game = GOGService.getGOGGameOf(gameId.toString())
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
                    known = game != null,
                )
            }

            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId)
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
                    known = game != null,
                )
            }

            GameSource.AMAZON -> {
                val productId = AmazonService.getProductIdByAppId(gameId)
                val game = productId?.let { AmazonService.getAmazonGameOf(it) }
                ResolvedGame(
                    name = game?.title,
                    installPath = game?.installPath,
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
