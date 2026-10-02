package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.winlator.container.Container
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Maps a container-absolute directory to the most specific supported [SaveRoot] (longest-prefix
 * match), or `null` when it falls under no supported [PathType] root.
 *
 * This is the single longest-prefix implementation shared by:
 * - the container browser confirm mapping (Requirement 3.8), and
 * - the Epic/GOG known-folder strategies (Requirement 15.3), which resolve an **absolute** save
 *   folder and must express it as a `PathType` + relative subpath (the model never stores an
 *   absolute path — Requirement 1.4).
 *
 * The supported Windows roots nest — `Root` resolves to `.wine/drive_c/users/<user>/`, a prefix of
 * `WinMyDocuments`, `WinSavedGames`, and the `WinAppData*` roots — so a first-match rule would be
 * order-dependent. Matching selects the root with the **longest** path that is a (segment-aware)
 * prefix of the target. Prefix matching uses normalized [Path.startsWith], so `Documents` never
 * matches `Documents2`.
 */
object SaveRootMatcher {

    /**
     * The supported [PathType] → absolute root path map for [container], derived exactly as
     * [PathType.toAbsPath] does so a mapped root round-trips to the same absolute path the resolver
     * produces.
     *
     * `SteamUserData` is excluded by default: its `toAbsPath` embeds a concrete
     * `userdata/<accountId>/<appId>/remote` path requiring a resolvable account id, and it is not a
     * directory the browser/known-folder flows navigate into. Pass [includeSteamUserData] = true
     * (with a valid [accountId]) to include it.
     */
    fun candidateRoots(
        container: Container,
        appId: Int,
        accountId: Long = 0L,
        includeSteamUserData: Boolean = false,
    ): Map<PathType, Path> {
        val types = if (includeSteamUserData) {
            SaveRoot.SUPPORTED_PATH_TYPES
        } else {
            SaveRoot.SUPPORTED_PATH_TYPES - PathType.SteamUserData
        }
        return types.associateWith { pathType ->
            Paths.get(pathType.toAbsPath(container, appId, accountId)).normalize()
        }
    }

    /**
     * Pure longest-prefix mapping: given supported [PathType] → absolute root paths and a target
     * [absoluteDir], return the most-specific-root [SaveRoot] (no pattern), or `null` when the
     * directory is under no supported root.
     */
    fun match(candidateRoots: Map<PathType, Path>, absoluteDir: Path): SaveRoot? {
        val target = absoluteDir.normalize()
        val best = candidateRoots.entries
            .asSequence()
            .filter { (pathType, _) -> pathType in SaveRoot.SUPPORTED_PATH_TYPES }
            .map { (pathType, root) -> pathType to root.normalize() }
            .filter { (_, root) -> target.startsWith(root) }
            .maxByOrNull { (_, root) -> root.nameCount }
            ?: return null

        val (pathType, root) = best
        val remainder = root.relativize(target).toString()
        return try {
            SaveRoot(pathType, remainder)
        } catch (e: IllegalArgumentException) {
            // A remainder that normalizes to a traversal escape is not a usable root.
            null
        }
    }

    /**
     * Container-aware convenience: derive candidate roots from [container] and [match] [absoluteDir]
     * against them. Includes `SteamUserData` only when [accountId] is non-zero.
     */
    fun matchWithinContainer(
        container: Container,
        appId: Int,
        accountId: Long,
        absoluteDir: Path,
    ): SaveRoot? = match(
        candidateRoots(
            container = container,
            appId = appId,
            accountId = accountId,
            includeSteamUserData = accountId != 0L,
        ),
        absoluteDir,
    )
}
