package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.winlator.container.Container
import java.nio.file.Path
import java.nio.file.Paths
import timber.log.Timber

/**
 * The outcome of confirming a directory selection in the [ContainerBrowser] (Requirement 3.8, 3.9).
 *
 * Confirm maps a browsed absolute directory back to a [SaveLocation] — a supported [PathType] root
 * plus the relative subpath from that root to the directory — or rejects the directory when it does
 * not fall within any supported [PathType] root.
 */
sealed interface ConfirmResult {
    /**
     * The directory falls within a supported [PathType] root; [saveLocation] is the
     * most-specific-root mapping (Requirement 3.8).
     */
    data class Selected(val saveLocation: SaveLocation) : ConfirmResult

    /**
     * The directory does not fall within any supported [PathType] root (e.g. under `Program Files`,
     * which no [PathType] covers); no [SaveLocation] is returned (Requirement 3.9).
     */
    data class Rejected(val reason: String) : ConfirmResult
}

/**
 * The outcome of confirming a directory selection **and persisting it** (Requirement 3.10, 3.11).
 */
sealed interface ConfirmPersistResult {
    /**
     * The directory mapped to a supported root and the resulting [saveLocation] was durably
     * persisted (Requirement 3.10).
     */
    data class Persisted(val saveLocation: SaveLocation) : ConfirmPersistResult

    /**
     * The directory does not fall within any supported [PathType] root; nothing was persisted and
     * any previously persisted value is unchanged (Requirement 3.9).
     */
    data class Rejected(val reason: String) : ConfirmPersistResult

    /**
     * The directory mapped to a supported root but persisting failed. The mapped [saveLocation] is
     * reported for context; any previously persisted value is left unchanged (Requirement 3.11).
     */
    data class PersistFailed(val saveLocation: SaveLocation, val reason: String) : ConfirmPersistResult
}

/**
 * Maps a confirmed absolute directory back to a [SaveLocation] using the **longest (most specific)
 * matching supported [PathType] root** (Requirement 3.8, 3.9; Design "longest matching root").
 *
 * The supported Windows roots nest — `Root` resolves to `.wine/drive_c/users/<user>/`, which is a
 * path prefix of `WinMyDocuments` (`.../Documents/`), `WinSavedGames` (`.../Saved Games/`) and the
 * `WinAppData*` roots. A first-match rule would therefore be order-dependent and could map a
 * directory under `Documents` to `Root`. Confirm instead selects, among all supported roots whose
 * absolute path is a path prefix of the confirmed directory, the one with the **longest** path,
 * then computes the relative subpath as the remainder from that root.
 *
 * Prefix matching is performed on normalized [Path]s via [Path.startsWith], which is **segment
 * aware** — `Documents` never matches `Documents2` the way a raw string `startsWith` would.
 *
 * The core [map] takes the candidate roots directly (a [PathType] → absolute [Path] map) so it is
 * trivially unit-testable with temporary directories (Task 8.3). [candidateRoots] and
 * [mapWithinContainer] layer the container-derived roots on top for production use.
 */
object ContainerBrowserConfirm {

    const val NOT_SUPPORTED_ROOT_REASON = "location is not a supported save root"

    /**
     * Pure longest-prefix mapping. Given the [candidateRoots] (each supported [PathType] mapped to
     * its absolute root [Path] within a container) and the [confirmedDir], return the
     * most-specific-root [SaveLocation] or a rejection.
     *
     * @param candidateRoots supported [PathType] → absolute root path. Only [PathType]s in
     *   [SaveLocation.SUPPORTED_PATH_TYPES] should be supplied; others are ignored.
     * @param confirmedDir the absolute directory the user confirmed.
     */
    fun map(candidateRoots: Map<PathType, Path>, confirmedDir: Path): ConfirmResult {
        // Delegate to the single shared longest-prefix matcher (Design "one longest-prefix
        // implementation"). A browser-confirmed root carries no pattern — its whole subtree is
        // treated as saves.
        val root = SaveRootMatcher.match(candidateRoots, confirmedDir)
            ?: return ConfirmResult.Rejected(NOT_SUPPORTED_ROOT_REASON)
        return ConfirmResult.Selected(SaveLocation.of(root))
    }

    /**
     * Whether [confirmedDir] is confirmable — i.e. it maps to a supported [PathType] root. The
     * container-side picker UI uses this to make **listable ≠ selectable** explicit: the seven
     * common roots (including `Program Files`, which no [PathType] covers) are listed for
     * navigation, but the "Select this folder" affordance is disabled/explained for directories
     * that would be rejected (Requirement 3.9; Design "listable ≠ selectable").
     */
    fun isConfirmable(candidateRoots: Map<PathType, Path>, confirmedDir: Path): Boolean =
        map(candidateRoots, confirmedDir) is ConfirmResult.Selected

    /**
     * Compute the supported [PathType] → absolute root path map for [container], deriving each root
     * exactly the way [PathType.toAbsPath] does (so a confirm round-trips to the same absolute path
     * the resolver would produce).
     *
     * `SteamUserData` is intentionally **excluded**: its `toAbsPath` embeds a concrete
     * `userdata/<accountId>/<appId>/remote` path that requires a resolvable account id, and it is
     * not a directory the container browser navigates into. The browser therefore maps only the
     * Windows filesystem roots reachable under `drive_c`.
     *
     * @param container the container being browsed.
     * @param appId the numeric game id (passed through to [PathType.toAbsPath]).
     * @param accountId the Steam account id (unused by the included roots; accepted for symmetry).
     */
    fun candidateRoots(container: Container, appId: Int, accountId: Long): Map<PathType, Path> =
        SaveRootMatcher.candidateRoots(container, appId, accountId, includeSteamUserData = false)

    /**
     * Container-aware confirm: derive the candidate roots from [container] (via [candidateRoots])
     * and [map] [confirmedDir] against them (Requirement 3.8, 3.9).
     */
    fun mapWithinContainer(
        container: Container,
        appId: Int,
        accountId: Long,
        confirmedDir: Path,
    ): ConfirmResult = map(candidateRoots(container, appId, accountId), confirmedDir)

    /**
     * Confirm-and-persist (Requirement 3.10, 3.11). On a [ConfirmResult.Selected] mapping, persist
     * the [SaveLocation] via [store]; on a persist failure report it and leave any previously
     * persisted value unchanged (the store guarantees the prior value is intact on failure).
     *
     * Kept separate from the pure [map] so the mapping stays unit-testable without a store
     * (Task 8.3) and persistence layers cleanly on top.
     *
     * @param appId the source-prefixed `LibraryItem.appId` the store keys on.
     */
    suspend fun confirmAndPersist(
        store: SaveLocationStore,
        appId: String,
        candidateRoots: Map<PathType, Path>,
        confirmedDir: Path,
    ): ConfirmPersistResult =
        when (val result = map(candidateRoots, confirmedDir)) {
            is ConfirmResult.Rejected -> ConfirmPersistResult.Rejected(result.reason)
            is ConfirmResult.Selected -> {
                try {
                    store.put(appId, result.saveLocation)
                    ConfirmPersistResult.Persisted(result.saveLocation)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Don't swallow coroutine cancellation as a persist failure.
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to persist confirmed SaveLocation for appId=$appId")
                    ConfirmPersistResult.PersistFailed(
                        result.saveLocation,
                        e.message ?: "failed to persist save location",
                    )
                }
            }
        }
}
