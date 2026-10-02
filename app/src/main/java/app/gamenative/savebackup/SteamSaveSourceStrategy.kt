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
 * Steam-specific automatic save-location discovery/resolution — a faithful port of the retired
 * `SteamSaveTransfer.resolveExportRoots` / `resolveImportRoots` (PR #1914 review parity).
 *
 * The game's saves may span several container roots: every Windows UFS `saveFilePattern` (other
 * than `SteamUserData`, which is scanned separately) plus the `SteamUserData` root. This strategy
 * returns the **complete set** of applicable roots as a multi-root [SaveLocation], each carrying its
 * UFS [SaveFilePattern] so export can filter to pattern-matched files (Requirement 2.7).
 *
 * Intent (Requirement 2.6):
 * - [ResolveIntent.EXPORT] (mirrors `resolveExportRoots`): a root is included only if it holds at
 *   least one **regular** (non-directory, non-symlink) file matched by its pattern.
 * - [ResolveIntent.IMPORT] (mirrors `resolveImportRoots`): every applicable root is included
 *   **regardless of whether files are present**, so a restore onto a freshly installed game resolves
 *   without prompting.
 *
 * Only roots whose [PathType] is in [SaveRoot.SUPPORTED_PATH_TYPES] are produced (Requirement 2.8);
 * `GameInstall` is in that set (Requirement 14), so install-dir patterns are kept.
 *
 * Discovery only — persistence is the resolver's job. This strategy never writes.
 *
 * Result mapping:
 * - [AutoResolveResult.Unavailable] when neither the UFS `saveFilePatterns` nor the `SteamUserData`
 *   root can be retrieved (Requirement 2.5).
 * - [AutoResolveResult.NoSavesFound] when, for EXPORT, discovery ran but no candidate root holds a
 *   pattern-matched regular file (Requirement 2.4). (IMPORT never returns NoSavesFound as long as
 *   any applicable root exists.)
 * - [AutoResolveResult.Found] with the complete set of applicable roots (Requirement 2.2).
 */
class SteamSaveSourceStrategy : SaveSourceStrategy {

    override fun resolveAutomatic(
        context: Context,
        container: Container,
        gameId: Int,
        intent: ResolveIntent,
    ): AutoResolveResult {
        val app: SteamApp? = SteamService.getAppInfoOf(gameId)

        val prefixToPath = makePrefixToPath(container, gameId)

        // Windows UFS patterns other than SteamUserData, restricted to supported roots (Req 2.8).
        // SteamUserData is handled separately below.
        val windowsPatterns: List<SaveFilePattern> = app?.ufs?.saveFilePatterns
            ?.filter { it.root.isWindows && it.root != PathType.SteamUserData }
            ?.filter { it.root in SaveRoot.SUPPORTED_PATH_TYPES }
            ?: emptyList()

        // Can we resolve the SteamUserData root at all? Null means no Steam account id could be found.
        val steamUserDataRoot: String? = prefixToPath(PathType.SteamUserData.name)

        // Req 2.5: if neither UFS saveFilePatterns nor the SteamUserData root can be retrieved,
        // automatic resolution is unavailable.
        if (windowsPatterns.isEmpty() && steamUserDataRoot == null) {
            return AutoResolveResult.Unavailable
        }

        val roots = mutableListOf<SaveRoot>()

        // 1) UFS patterns (skip SteamUserData — handled below), in declared order.
        windowsPatterns.forEach { pattern ->
            val rootPath = prefixToPath(pattern.root.name) ?: return@forEach
            val basePath = Paths.get(rootPath, pattern.substitutedPath)

            // EXPORT: include only if the root holds a pattern-matched regular file (Req 2.1).
            // IMPORT: include regardless of file presence (Req 2.6).
            if (intent == ResolveIntent.EXPORT && !rootContainsSaveFiles(basePath, pattern)) {
                return@forEach
            }

            // Build defensively: UFS `path` comes from Steam server metadata, so a pattern whose
            // substituted path escapes its root via '..' makes SaveRoot's constructor throw. Such a
            // pattern is not a usable candidate — skip it rather than aborting all resolution.
            val candidate = try {
                SaveRoot(pattern.root, pattern.substitutedPath, pattern)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Skipping Steam UFS pattern with unsafe subpath: %s", pattern.substitutedPath)
                return@forEach
            }
            roots += candidate
        }

        // 2) SteamUserData — always applicable (matches SteamSaveTransfer behavior).
        if (steamUserDataRoot != null) {
            val userDataPattern = SaveFilePattern(
                root = PathType.SteamUserData,
                path = "",
                pattern = "*",
                recursive = 5,
            )
            val include = when (intent) {
                ResolveIntent.IMPORT -> true
                ResolveIntent.EXPORT ->
                    rootContainsSaveFiles(Paths.get(steamUserDataRoot), userDataPattern)
            }
            if (include) {
                roots += SaveRoot(PathType.SteamUserData, "", userDataPattern)
            }
        }

        return if (roots.isNotEmpty()) {
            AutoResolveResult.Found(SaveLocation.of(roots))
        } else {
            // EXPORT discovery ran but no candidate root held a regular file (Req 2.4). (IMPORT
            // only reaches here if there were no applicable roots at all.)
            AutoResolveResult.NoSavesFound
        }
    }

    /**
     * A candidate root "contains save files" iff it holds at least one **regular** file
     * (non-directory, non-symlink) matched by [pattern], mirroring the existing engine's
     * `findPatternFiles` filter combined with the symlink exclusion used throughout
     * `SteamSaveTransfer` (`LinkOption.NOFOLLOW_LINKS`).
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
