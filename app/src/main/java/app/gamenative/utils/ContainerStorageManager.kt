package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object ContainerStorageManager {
    enum class Status {
        READY,
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
    ) {
        val combinedSizeBytes: Long?
            get() = gameInstallSizeBytes?.plus(containerSizeBytes)
    }

    private data class ResolvedGame(
        val name: String?,
        val installPath: String? = null,
        val known: Boolean,
    )

    suspend fun loadEntries(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val homeDir = File(ImageFs.find(context).rootDir, "home")
        val prefix = "${ImageFs.USER}-"
        val dirs = homeDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) }
            .orEmpty()
        val pathSizeCache = mutableMapOf<String, Long>()

        Timber.tag("ContainerStorageManager").i("Scanning container inventory in %s (%d candidate dirs)", homeDir.absolutePath, dirs.size)

        val entries = dirs.map { dir -> buildEntry(context, dir, prefix, pathSizeCache) }
            .sortedWith(compareByDescending<Entry> { it.combinedSizeBytes ?: it.containerSizeBytes }.thenBy { it.displayName.lowercase() })

        Timber.tag("ContainerStorageManager").i(
            "Loaded %d container entries (%d ready, %d missing files, %d orphaned, %d unreadable)",
            entries.size,
            entries.count { it.status == Status.READY },
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
            "Uninstalling game+container for %s (source=%s, gameId=%d, displayName=%s)",
            entry.containerId,
            gameSource,
            gameId,
            entry.displayName,
        )

        try {
            val result = when (gameSource) {
                GameSource.STEAM -> {
                    val deleted = SteamService.deleteApp(gameId)
                    if (!deleted) {
                        Result.failure(Exception("Failed to uninstall Steam game"))
                    } else {
                        removeContainer(context, entry.containerId)
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
                    if (result.isSuccess) removeContainer(context, entry.containerId)
                    result
                }

                GameSource.EPIC -> {
                    val result = EpicService.deleteGame(context, gameId)
                    if (result.isSuccess) removeContainer(context, entry.containerId)
                    result
                }

                GameSource.AMAZON -> {
                    val productId = AmazonService.getProductIdByAppId(gameId)
                        ?: return@withContext Result.failure(Exception("Amazon product id not found"))
                    val result = AmazonService.deleteGame(context, productId)
                    if (result.isSuccess) removeContainer(context, entry.containerId)
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

    private suspend fun buildEntry(
        context: Context,
        dir: File,
        prefix: String,
        pathSizeCache: MutableMap<String, Long>,
    ): Entry {
        val containerId = dir.name.removePrefix(prefix)
        val normalizedContainerId = normalizeContainerId(containerId)
        val gameSource = detectGameSource(normalizedContainerId)
        val containerSizeBytes = getContainerDirectorySize(dir.toPath())
        val configFile = File(dir, ".container")

        val config = readConfig(configFile)
        if (config == null) {
            return Entry(
                containerId = containerId,
                displayName = containerId,
                gameSource = gameSource,
                containerSizeBytes = containerSizeBytes,
                status = Status.UNREADABLE,
            )
        }

        val gameId = extractGameId(normalizedContainerId)
        val resolved = if (gameSource != null && gameId != null) {
            resolveGame(context, gameSource, gameId, normalizedContainerId)
        } else {
            null
        }

        val installPath = resolved?.installPath?.takeIf { it.isNotBlank() }
        val displayName = resolved?.name?.takeIf { it.isNotBlank() }
            ?: config.optString("name", "").takeIf { it.isNotBlank() }
            ?: containerId

        val status = when {
            resolved == null || !resolved.known -> Status.ORPHANED
            installPath.isNullOrBlank() -> Status.GAME_FILES_MISSING
            !File(installPath).exists() -> Status.GAME_FILES_MISSING
            else -> Status.READY
        }

        val gameInstallSizeBytes = installPath
            ?.takeIf { status == Status.READY }
            ?.let { path -> pathSizeCache.getOrPut(path) { getContainerDirectorySize(File(path).toPath()) } }

        return Entry(
            containerId = containerId,
            displayName = displayName,
            gameSource = gameSource,
            containerSizeBytes = containerSizeBytes,
            gameInstallSizeBytes = gameInstallSizeBytes,
            status = status,
            installPath = installPath,
            canUninstallGame = status == Status.READY && gameSource != null && gameSource != GameSource.CUSTOM_GAME,
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
