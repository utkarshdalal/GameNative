package app.gamenative.savebackup

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.FileUtils
import com.winlator.container.Container
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.pathString
import timber.log.Timber

/**
 * Steam-specific automatic save-location discovery.
 *
 * Ports the discovery half of the existing `SteamSaveTransfer.resolveExportRoots`: it walks the
 * game's UFS `saveFilePatterns` (the Windows roots, excluding `SteamUserData`) and always also
 * scans the `SteamUserData` root recursively. A candidate root "contains save files" iff it holds
 * at least one **regular** file (non-directory, non-symbolic-link), checked with
 * `java.nio.file` + [LinkOption.NOFOLLOW_LINKS], exactly as the existing engine does.
 *
 * Discovery only — persistence is the resolver's job (task 5.1). This strategy never writes.
 *
 * Result mapping (Requirements 2.1, 2.2, 2.4, 2.5):
 * - [AutoResolveResult.Unavailable] when neither the UFS `saveFilePatterns` nor the
 *   `SteamUserData` root can be retrieved (e.g. the Steam app info is not available, or no Steam
 *   account id can be resolved and the game declares no other Windows save patterns).
 * - [AutoResolveResult.NoSavesFound] when discovery ran but no candidate root holds a regular file.
 * - [AutoResolveResult.Found] when at least one candidate root holds a regular file. The returned
 *   [SaveLocation] is the first such root in the existing engine's discovery order (UFS patterns
 *   first, then `SteamUserData`), expressed as a supported [PathType] plus a relative subpath.
 */
class SteamSaveSourceStrategy : SaveSourceStrategy {

    override fun resolveAutomatic(
        context: Context,
        container: Container,
        gameId: Int,
    ): AutoResolveResult {
        // If the Steam app info (which carries the UFS saveFilePatterns) is not available, we
        // cannot discover from patterns. We may still be able to scan SteamUserData, but the
        // existing engine keys all discovery off the app info, so treat a missing app as the
        // "UFS unavailable" branch and fall back to the SteamUserData-only probe below.
        val app: SteamApp? = SteamService.getAppInfoOf(gameId)

        val prefixToPath = makePrefixToPath(container, gameId)

        // Windows UFS patterns other than SteamUserData (SteamUserData is scanned separately).
        val windowsPatterns: List<SaveFilePattern> = app?.ufs?.saveFilePatterns
            ?.filter { it.root.isWindows && it.root != PathType.SteamUserData }
            ?: emptyList()

        // Can we resolve the SteamUserData root at all? Null means no Steam account id could be
        // found for this game — the same null-return condition the existing resolveRootBasePath
        // uses.
        val steamUserDataRoot: String? = prefixToPath(PathType.SteamUserData.name)

        // Req 2.5: if neither UFS saveFilePatterns nor the SteamUserData root can be retrieved,
        // automatic resolution is unavailable.
        if (windowsPatterns.isEmpty() && steamUserDataRoot == null) {
            return AutoResolveResult.Unavailable
        }

        // 1) UFS patterns (skip SteamUserData — handled below), in declared order.
        windowsPatterns.forEach { pattern ->
            val rootPath = prefixToPath(pattern.root.name) ?: return@forEach
            val basePath = Paths.get(rootPath, pattern.substitutedPath)
            if (rootContainsSaveFiles(basePath, pattern)) {
                // The pattern's root is a supported PathType (isWindows && != SteamUserData maps to
                // WinMyDocuments/WinAppData*/WinSavedGames/WinProgramData/Root — all in
                // SaveLocation.SUPPORTED_PATH_TYPES). The relative subpath is the pattern's
                // substituted path under that root; SaveLocation normalizes it.
                return AutoResolveResult.Found(
                    SaveLocation(pattern.root, pattern.substitutedPath),
                )
            }
        }

        // 2) SteamUserData — always scanned recursively (matches SteamSaveTransfer behavior).
        if (steamUserDataRoot != null) {
            val userDataPath = Paths.get(steamUserDataRoot)
            val userDataPattern = SaveFilePattern(
                root = PathType.SteamUserData,
                path = "",
                pattern = "*",
                recursive = 5,
            )
            if (rootContainsSaveFiles(userDataPath, userDataPattern)) {
                // SteamUserData root maps directly; the resolved base path is the root itself, so
                // the relative subpath is empty.
                return AutoResolveResult.Found(
                    SaveLocation(PathType.SteamUserData, ""),
                )
            }
        }

        // Discovery ran but no candidate root held a regular file (Req 2.4).
        return AutoResolveResult.NoSavesFound
    }

    /**
     * A candidate root "contains save files" iff it holds at least one **regular** file
     * (non-directory, non-symlink) matched by [pattern], mirroring the existing engine's
     * `findPatternFiles` filter (`Files.isRegularFile`) combined with the symlink exclusion used
     * throughout `SteamSaveTransfer` (`LinkOption.NOFOLLOW_LINKS`).
     */
    private fun rootContainsSaveFiles(basePath: Path, pattern: SaveFilePattern): Boolean {
        if (!Files.exists(basePath)) return false
        val depth = if (pattern.recursive > 0) pattern.recursive else 5
        return FileUtils.findFilesRecursive(
            rootPath = basePath,
            pattern = pattern.pattern,
            maxDepth = depth,
        )
            .filter { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path)
            }
            .findFirst()
            .isPresent
    }

    // -- Root base-path resolution (ported from SteamSaveTransfer) --------------

    private fun makePrefixToPath(container: Container, appId: Int): (String) -> String? = { prefix ->
        resolveRootBasePath(container, appId, PathType.from(prefix))?.pathString
    }

    private fun resolveRootBasePath(
        container: Container,
        appId: Int,
        rootType: PathType,
    ): Path? {
        val accountId = when (rootType) {
            PathType.SteamUserData -> resolveSteamAccountId(container, appId) ?: return null
            else -> 0L
        }
        return Paths.get(rootType.toAbsPath(container, appId, accountId))
    }

    private fun resolveSteamAccountId(container: Container, appId: Int): Long? {
        SteamService.userSteamId?.accountID?.toLong()?.let { return it }
        PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()?.let { return it }

        val userdataRoot = Paths.get(
            container.rootDir.absolutePath,
            ".wine/drive_c/Program Files (x86)/Steam/userdata",
        )
        if (!Files.isDirectory(userdataRoot)) return null

        val matchingAccountIds = Files.list(userdataRoot).use { children ->
            children
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { candidate -> candidate.all(Char::isDigit) }
                .filter { candidate ->
                    val appRoot = userdataRoot.resolve(candidate).resolve(appId.toString())
                    Files.isDirectory(appRoot)
                }
                .sorted()
                .collect(Collectors.toList())
        }

        if (matchingAccountIds.size > 1) {
            Timber.w(
                "Multiple Steam userdata accounts found for appId=$appId; using ${matchingAccountIds.first()} for save discovery",
            )
        }

        return matchingAccountIds.firstOrNull()?.toLongOrNull()
    }
}
