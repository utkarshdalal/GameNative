package app.gamenative.savebackup

import android.content.Context
import com.winlator.container.Container

/**
 * Isolates the only source-specific behavior of the save-backup engine: discovering a game's save
 * location automatically without prompting the user.
 *
 * Steam games can be resolved automatically from the game's UFS `saveFilePatterns` and the
 * `SteamUserData` root (see [SteamSaveSourceStrategy], task 4.2). Non-Steam sources (GOG, Epic,
 * Amazon) have no automatic discovery and always defer to the in-app container browser (see
 * [GenericSaveSourceStrategy], task 4.3), returning [AutoResolveResult.NoAutomaticResolution].
 */
interface SaveSourceStrategy {
    /**
     * Attempt to resolve a [SaveLocation] for the given game automatically.
     *
     * @param context the Android context used to read UFS/save metadata.
     * @param container the resolved Wine container for the game.
     * @param gameId the numeric game identifier (source-independent).
     * @return an [AutoResolveResult] describing the outcome.
     */
    fun resolveAutomatic(context: Context, container: Container, gameId: Int): AutoResolveResult
}

/**
 * The outcome of an attempt to resolve a [SaveLocation] automatically via a [SaveSourceStrategy].
 */
sealed interface AutoResolveResult {
    /**
     * At least one candidate save root contained a regular file, so a [SaveLocation] was resolved
     * (Requirement 2.2). The engine persists it (Requirement 2.3) and uses it.
     */
    data class Found(val location: SaveLocation) : AutoResolveResult

    /**
     * Automatic resolution ran but found no candidate root containing save files
     * (Requirement 2.4). The engine prompts the user through the container browser.
     */
    data object NoSavesFound : AutoResolveResult

    /**
     * The information required for automatic resolution could not be retrieved — e.g. the game's
     * UFS `saveFilePatterns` and `SteamUserData` root are unavailable (Requirement 2.5). The engine
     * prompts the user through the container browser and reports that automatic resolution was
     * unavailable.
     */
    data object Unavailable : AutoResolveResult

    /**
     * The source has no automatic resolution (GOG, Epic, Amazon). The engine always defers to the
     * container browser when the save location is unset.
     */
    data object NoAutomaticResolution : AutoResolveResult
}
