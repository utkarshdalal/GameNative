package app.gamenative.savebackup

import app.gamenative.enums.PathType

/**
 * The cross-tool interoperability expectation shown to the user, before an external location is
 * selected, when they choose a Raw-tree export (Requirement 12.1, 12.2, 12.3).
 *
 * The hint is derived purely from the resolved [SaveLocation]'s [PathType]; see
 * [InteroperabilityHintClassifier.classify]. It carries no user-facing text — mapping a hint to a
 * localized message is the UI layer's job (see `BaseAppScreen`), keeping this classification free of
 * any Android dependency so it can be exercised directly by Property 14.
 */
enum class InteroperabilityHint {
    /**
     * The resolved save root is one of the common Windows user roots, so a Raw-tree export is
     * expected to line up with a desktop launcher prefix (Requirement 12.1).
     */
    INTEROPERABLE,

    /**
     * The resolved save root is Steam's `userdata` remote storage, whose layout is not expected to
     * match a desktop launcher prefix (Requirement 12.2).
     */
    NOT_EXPECTED_TO_LINE_UP,

    /**
     * The resolved save root is neither in the interoperable set nor `SteamUserData`, so
     * interoperability with a desktop launcher prefix cannot be guaranteed (Requirement 12.3).
     */
    NOT_GUARANTEED,
}

/**
 * Pure classification of a [PathType] into an [InteroperabilityHint] (Property 14).
 *
 * This object has no Android dependency and performs no I/O, so it can be tested directly by the
 * Property 14 test in task 12.3. The classification exactly mirrors the design's "Interoperability
 * hint (Req 12)" section:
 *
 * - `WinMyDocuments`, `WinAppDataLocal`, `WinAppDataLocalLow`, `WinAppDataRoaming`, `WinSavedGames`
 *   → [InteroperabilityHint.INTEROPERABLE] (Requirement 12.1)
 * - `SteamUserData` → [InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP] (Requirement 12.2)
 * - any other [PathType] → [InteroperabilityHint.NOT_GUARANTEED] (Requirement 12.3)
 */
object InteroperabilityHintClassifier {

    /** The set of [PathType] roots whose Raw-tree export is expected to be interoperable (Req 12.1). */
    val INTEROPERABLE_PATH_TYPES: Set<PathType> = setOf(
        PathType.WinMyDocuments,
        PathType.WinAppDataLocal,
        PathType.WinAppDataLocalLow,
        PathType.WinAppDataRoaming,
        PathType.WinSavedGames,
    )

    fun classify(pathType: PathType): InteroperabilityHint = when (pathType) {
        in INTEROPERABLE_PATH_TYPES -> InteroperabilityHint.INTEROPERABLE
        PathType.SteamUserData -> InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP
        else -> InteroperabilityHint.NOT_GUARANTEED
    }
}
