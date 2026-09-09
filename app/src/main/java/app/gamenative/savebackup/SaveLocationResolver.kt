package app.gamenative.savebackup

import app.gamenative.PrefManager
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import com.winlator.container.Container
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import timber.log.Timber

/**
 * Pure resolution of a persisted [SaveLocation] to an absolute filesystem path inside a
 * [Container].
 *
 * `PathType.toAbsPath(container, appId, accountId)` is **non-nullable** and silently falls back to
 * the game install directory for any [PathType] it does not handle explicitly. Because of that,
 * resolution failure (Requirement 1.7) cannot be detected from a null return of `toAbsPath`. It is
 * instead detected via the only two conditions where resolution is genuinely impossible:
 *
 *  a) the persisted [PathType] is outside [SaveLocation.SUPPORTED_PATH_TYPES], or
 *  b) the location is a [PathType.SteamUserData] root for which no Steam account id can be
 *     resolved — mirroring the null-return condition of the existing
 *     `SteamSaveTransfer.resolveRootBasePath` / `resolveSteamAccountId`.
 *
 * In either failure case the resolver returns [SaveLocationResult.Unresolved] and returns no
 * absolute path; it never mutates the persisted [SaveLocation] (Requirement 1.7, Property 3).
 */
object SaveLocationResolver {

    /**
     * Resolve [saveLocation] to an absolute path inside [container].
     *
     * @param container the Wine container to resolve within
     * @param appId the numeric game id, passed to [PathType.toAbsPath]
     * @param saveLocation the persisted location to resolve
     * @return [SaveLocationResult.Resolved] with the joined absolute path, or
     *   [SaveLocationResult.Unresolved] when the [PathType] is unsupported or a `SteamUserData`
     *   root has no resolvable Steam account id.
     */
    fun resolve(
        container: Container,
        appId: Int,
        saveLocation: SaveLocation,
    ): SaveLocationResult {
        // (a) A PathType outside the supported set can never be resolved (Req 1.7).
        if (saveLocation.pathType !in SaveLocation.SUPPORTED_PATH_TYPES) {
            return SaveLocationResult.Unresolved(
                "PathType ${saveLocation.pathType} is not a supported save root",
            )
        }

        // (b) SteamUserData requires a resolvable Steam account id (Req 1.7).
        val accountId = when (saveLocation.pathType) {
            PathType.SteamUserData -> resolveSteamAccountId(container, appId)
                ?: return SaveLocationResult.Unresolved(
                    "No Steam account id could be resolved for SteamUserData root (appId=$appId)",
                )
            else -> 0L
        }

        // toAbsPath returns a trailing-slash path (Req 1.3). Join with the relative subpath.
        val rootAbs = saveLocation.pathType.toAbsPath(container, appId, accountId)
        val absolutePath = joinRoot(rootAbs, saveLocation.relativeSubpath)
        return SaveLocationResult.Resolved(absolutePath, saveLocation)
    }

    /**
     * Join a `toAbsPath` root (always trailing-slash) with a normalized relative subpath.
     * An empty subpath yields the unmodified root (Requirement 1.8).
     */
    private fun joinRoot(rootAbs: String, relativeSubpath: String): Path {
        val root = Paths.get(rootAbs)
        return if (relativeSubpath.isEmpty()) root else root.resolve(relativeSubpath)
    }

    /**
     * Mirror of `SteamSaveTransfer.resolveSteamAccountId`: resolve the Steam account id for a
     * `SteamUserData` root, or return null when none can be found.
     */
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
                "Multiple Steam userdata accounts found for appId=$appId; " +
                    "using ${matchingAccountIds.first()} for save-location resolution",
            )
        }

        return matchingAccountIds.firstOrNull()?.toLongOrNull()
    }
}
