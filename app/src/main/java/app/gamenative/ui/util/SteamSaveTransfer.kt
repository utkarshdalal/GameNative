package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.FileUtils
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.pathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

object SteamSaveTransfer {
    private const val ARCHIVE_VERSION = 5
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Serializable
    private data class SaveArchiveManifest(
        val version: Int = ARCHIVE_VERSION,
        val steamAppId: Int,
        val gameName: String,
        val exportedAt: Long,
        val roots: List<SaveRoot>,
    )

    @Serializable
    private data class SaveRoot(
        val rootId: String,
        val path: String,
    )

    private data class ResolvedSaveRoot(
        val rootId: String,
        val absolutePath: Path,
        val files: List<Path>,
    )

    suspend fun exportSaves(
        context: Context,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val prefixToPath = makePrefixToPath(context, steamAppId)
            val roots = withContext(Dispatchers.IO) { resolveExportRoots(app, prefixToPath) }

            if (roots.isEmpty()) {
                SnackbarManager.show(context.getString(R.string.steam_save_export_no_saves_found))
                return false
            }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    ZipOutputStream(outputStream.buffered()).use { zip ->
                        val manifestRoots = roots.map { SaveRoot(rootId = it.rootId, path = it.absolutePath.pathString) }
                        val manifest = SaveArchiveManifest(
                            steamAppId = steamAppId,
                            gameName = app.name,
                            exportedAt = System.currentTimeMillis(),
                            roots = manifestRoots,
                        )

                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()

                        roots.forEach { root ->
                            root.files.forEach { file ->
                                if (!Files.isRegularFile(file)) return@forEach
                                val relativePath = normalizeRelativePath(root.absolutePath.relativize(file).toString())
                                zip.putNextEntry(ZipEntry("$FILES_PREFIX${root.rootId}/$relativePath"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: throw IOException("Unable to open destination")
            }

            SnackbarManager.show(context.getString(R.string.steam_save_export_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to export Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_export_failed, e.message ?: "Unknown error"))
            false
        }
    }

    suspend fun importSaves(
        context: Context,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val archiveManifest = withContext(Dispatchers.IO) { readArchiveManifest(context, uri) }
            if (archiveManifest.steamAppId != steamAppId) {
                throw IOException("Archive is for Steam app ${archiveManifest.steamAppId}, expected $steamAppId")
            }
            if (archiveManifest.roots.isEmpty()) {
                throw IOException("Archive does not declare any save roots")
            }

            val prefixToPath = makePrefixToPath(context, steamAppId)
            val rootMap = withContext(Dispatchers.IO) {
                resolveImportRoots(app, archiveManifest.roots, prefixToPath)
            }

            var importedFileCount = 0
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            try {
                                if (entry.isDirectory || entry.name == MANIFEST_ENTRY || !entry.name.startsWith(FILES_PREFIX)) {
                                    continue
                                }
                                if (writeArchiveEntry(zip, entry.name, rootMap)) {
                                    importedFileCount += 1
                                }
                            } finally {
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: throw IOException("Unable to open archive")
            }

            if (importedFileCount == 0) {
                throw IOException("No save files found in archive")
            }

            SnackbarManager.show(context.getString(R.string.steam_save_import_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to import Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_import_failed, e.message ?: "Unknown error"))
            false
        }
    }

    // -- Root resolution -------------------------------------------------------

    /**
     * Mirrors the file discovery logic in `SteamAutoCloud.getLocalUserFilesAsPrefixMap`:
     * 1. Walk UFS saveFilePatterns (GameInstall, WinMyDocuments, WinAppData*, etc.)
     * 2. Always scan SteamUserData recursively
     *
     * Returns only roots that have at least one file.
     */
    private fun resolveExportRoots(
        app: app.gamenative.data.SteamApp,
        prefixToPath: (String) -> String,
    ): List<ResolvedSaveRoot> {
        val accountId = SteamService.userSteamId?.accountID?.toLong()
            ?: throw IOException("Steam not logged in")
        val result = mutableListOf<ResolvedSaveRoot>()

        // 1) UFS patterns (skip SteamUserData — handled below)
        val savePatterns = app.ufs.saveFilePatterns.filter { it.root.isWindows }
        savePatterns
            .filter { it.root != PathType.SteamUserData }
            .forEach { pattern ->
                val basePath = Paths.get(prefixToPath(pattern.root.name), pattern.substitutedPath)
                val files = findPatternFiles(basePath, pattern)
                if (files.isNotEmpty()) {
                    result += ResolvedSaveRoot(
                        rootId = patternRootId(pattern),
                        absolutePath = basePath,
                        files = files,
                    )
                }
            }

        // 2) SteamUserData — always scanned recursively (matches SteamAutoCloud behavior)
        val userDataPath = Paths.get(prefixToPath(PathType.SteamUserData.name))
        val userDataFiles = findPatternFiles(userDataPath, SaveFilePattern(root = PathType.SteamUserData, path = "", pattern = "*", recursive = 5))
        if (userDataFiles.isNotEmpty()) {
            result += ResolvedSaveRoot(
                rootId = PathType.SteamUserData.name.lowercase(),
                absolutePath = userDataPath,
                files = userDataFiles,
            )
        }

        return result
    }

    /**
     * For import: resolve each manifest root back to an absolute path.
     * Matches by rootId against known UFS patterns + SteamUserData.
     */
    private fun resolveImportRoots(
        app: app.gamenative.data.SteamApp,
        manifestRoots: List<SaveRoot>,
        prefixToPath: (String) -> String,
    ): Map<String, Path> {
        val accountId = SteamService.userSteamId?.accountID?.toLong()
            ?: throw IOException("Steam not logged in")

        val knownRoots = mutableMapOf<String, Path>()

        // Build lookup from UFS patterns
        app.ufs.saveFilePatterns
            .filter { it.root.isWindows }
            .filter { it.root != PathType.SteamUserData }
            .forEach { pattern ->
                val id = patternRootId(pattern)
                knownRoots[id] = Paths.get(prefixToPath(pattern.root.name), pattern.substitutedPath)
            }

        // SteamUserData
        knownRoots[PathType.SteamUserData.name.lowercase()] = Paths.get(prefixToPath(PathType.SteamUserData.name))

        // Resolve manifest roots against known roots, fall back to stored path
        return manifestRoots.associate { mr ->
            mr.rootId to (knownRoots[mr.rootId] ?: Paths.get(mr.path))
        }
    }

    private fun findPatternFiles(basePath: Path, pattern: SaveFilePattern): List<Path> {
        if (!Files.exists(basePath)) return emptyList()
        val depth = if (pattern.recursive > 0) pattern.recursive else 5
        return FileUtils.findFilesRecursive(
            rootPath = basePath,
            pattern = pattern.pattern,
            maxDepth = depth,
        )
            .filter { Files.isRegularFile(it) }
            .toList()
    }

    private fun patternRootId(pattern: SaveFilePattern): String {
        val root = pattern.root.name.lowercase()
        val normalizedPath = pattern.path
            .replace('\\', '/')
            .ifBlank { "root" }
            .lowercase()
        return "$root/$normalizedPath"
    }

    // -- Archive I/O -----------------------------------------------------------

    private fun readArchiveManifest(context: Context, uri: Uri): SaveArchiveManifest {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                            return json.decodeFromString(
                                SaveArchiveManifest.serializer(),
                                zip.readBytes().decodeToString(),
                            )
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw IOException("Unable to open archive")

        throw IOException("Missing save archive manifest")
    }

    private fun writeArchiveEntry(
        zip: ZipInputStream,
        entryName: String,
        rootMap: Map<String, Path>,
    ): Boolean {
        val relativeEntry = entryName.removePrefix(FILES_PREFIX).replace('\\', '/')
        val slashIndex = relativeEntry.indexOf('/')
        if (slashIndex <= 0) return false

        val rootId = relativeEntry.substring(0, slashIndex)
        val relativePath = normalizeRelativePath(relativeEntry.substring(slashIndex + 1))
        if (relativePath.isBlank()) return false

        val destinationRoot = rootMap[rootId]
            ?: throw IOException("Archive save root not available: $rootId")
        val normalizedRoot = destinationRoot.normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw IOException("Archive entry escapes save root: $entryName")
        }

        destination.parent?.createDirectories()
        if (Files.isSymbolicLink(destination)) {
            throw IOException("Archive entry is a symlink: $entryName")
        }

        Files.newByteChannel(
            destination,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).use { output ->
                zip.copyTo(output)
            }
        }
        return true
    }

    // -- Helpers ---------------------------------------------------------------

    private fun makePrefixToPath(context: Context, appId: Int): (String) -> String = { prefix ->
        PathType.from(prefix).toAbsPath(context, appId, SteamService.userSteamId!!.accountID)
    }

    private fun normalizeRelativePath(value: String): String =
        value.replace('\\', '/').trimStart('/').trim()
}
