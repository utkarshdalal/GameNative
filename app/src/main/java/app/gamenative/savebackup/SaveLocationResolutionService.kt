package app.gamenative.savebackup

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber

/**
 * The terminal outcome of combined save-location resolution (store + strategy).
 *
 * This is distinct from [SaveLocationResult] (the *pure* absolute-path resolution outcome produced
 * by [SaveLocationResolver.resolve]). [ResolveOutcome] layers the store lookup and the automatic
 * source strategy on top of that pure resolution and adds an explicit "prompt the browser" signal,
 * so callers never have to interpret an [SaveLocationResult.Unset] plus a strategy result
 * themselves.
 */
sealed interface ResolveOutcome {
    /**
     * A save location was resolved to an absolute path inside the container — either from a
     * previously persisted location, or from a freshly discovered-and-persisted one.
     */
    data class Resolved(
        val absolutePath: java.nio.file.Path,
        val saveLocation: SaveLocation,
    ) : ResolveOutcome

    /**
     * A save location is persisted but cannot be resolved to an absolute path (Requirement 1.7).
     * The persisted value is left unchanged; the caller surfaces the [reason].
     */
    data class Unresolved(val reason: String) : ResolveOutcome

    /**
     * No usable save location could be determined automatically, so the caller must prompt the
     * user to pick one through the in-app container browser (Requirements 2.4, 2.5, 6.6).
     *
     * [automaticUnavailable] is `true` only when automatic resolution *could not run* because the
     * required source metadata was unavailable (Requirement 2.5) — the UI reports "automatic
     * resolution unavailable" in that case. It is `false` when automatic resolution ran but simply
     * found no saves, or when the source has no automatic resolution at all (GOG/Epic/Amazon).
     */
    data class NeedsBrowser(val automaticUnavailable: Boolean) : ResolveOutcome
}

/**
 * Combines the [SaveLocationStore] and a [SaveSourceStrategy] into the full save-location
 * resolution flow, applying the same rules across every [GameSource] (Requirement 6.6).
 *
 * Resolution flow (design "SaveLocationResolver" section):
 * 1. **Store hit** → delegate to the pure [SaveLocationResolver.resolve] and map its result to
 *    [ResolveOutcome.Resolved] / [ResolveOutcome.Unresolved] (Requirement 1.7).
 * 2. **Store miss** → run the source strategy:
 *    - [AutoResolveResult.Found] → persist the discovered location (Requirement 2.3) via the store,
 *      then delegate to the pure resolver and return its outcome (Requirement 2.2).
 *    - [AutoResolveResult.NoSavesFound] / [AutoResolveResult.NoAutomaticResolution] →
 *      [ResolveOutcome.NeedsBrowser] with `automaticUnavailable = false` (Requirement 2.4, 6.6).
 *    - [AutoResolveResult.Unavailable] → [ResolveOutcome.NeedsBrowser] with
 *      `automaticUnavailable = true` (Requirement 2.5).
 *
 * This service **delegates the pure absolute-path resolution to [SaveLocationResolver.resolve]** so
 * that the pre-existing pure resolver (and its property tests) are untouched. It never re-implements
 * path joining or the `SteamUserData` account-id logic.
 *
 * @param store the per-game persisted [SaveLocation] store.
 * @param strategySelector selects the [SaveSourceStrategy] for a [GameSource]; injectable for
 *   testing. Defaults to [defaultStrategySelector] (Steam → [SteamSaveSourceStrategy], everything
 *   else → [GenericSaveSourceStrategy]).
 */
class SaveLocationResolutionService(
    private val store: SaveLocationStore,
    private val strategySelector: (GameSource) -> SaveSourceStrategy = defaultStrategySelector,
) {

    /**
     * Resolve the save location for [item] inside [container].
     *
     * @param context Android context used by the source strategy to read save metadata.
     * @param container the resolved Wine container for the game.
     * @param item the library item; supplies the source-prefixed [LibraryItem.appId] (store key),
     *   the numeric [LibraryItem.gameId] (passed to the pure resolver), and the [GameSource]
     *   (strategy selection).
     */
    suspend fun resolve(
        context: Context,
        container: Container,
        item: app.gamenative.data.LibraryItem,
    ): ResolveOutcome = resolve(
        context = context,
        container = container,
        gameSource = item.gameSource,
        appId = item.appId,
        gameId = item.gameId,
    )

    /**
     * Resolve the save location from primitive identifiers. This overload keeps the service usable
     * from call sites that do not have a [LibraryItem] and makes the exact inputs explicit for
     * tests.
     *
     * @param appId source-prefixed app id used as the store key (e.g. `STEAM_440`).
     * @param gameId numeric game id passed to [SaveLocationResolver.resolve] and the strategy.
     */
    suspend fun resolve(
        context: Context,
        container: Container,
        gameSource: GameSource,
        appId: String,
        gameId: Int,
    ): ResolveOutcome {
        // 1. Store hit → delegate to the pure resolver (Requirement 1.7).
        val persisted = store.get(appId)
        if (persisted != null) {
            return mapPureResult(SaveLocationResolver.resolve(container, gameId, persisted))
        }

        // 2. Store miss → run the source strategy for this GameSource (Requirement 6.6).
        val strategy = strategySelector(gameSource)
        return when (val auto = strategy.resolveAutomatic(context, container, gameId)) {
            is AutoResolveResult.Found -> {
                // Persist first (Requirement 2.3), then delegate to the pure resolver and use it
                // (Requirement 2.2).
                store.put(appId, auto.location)
                mapPureResult(SaveLocationResolver.resolve(container, gameId, auto.location))
            }
            // Ran but found nothing / no automatic step for this source → prompt browser.
            AutoResolveResult.NoSavesFound,
            AutoResolveResult.NoAutomaticResolution,
            ->
                ResolveOutcome.NeedsBrowser(automaticUnavailable = false)
            // Could not run at all → prompt browser + report unavailable (Requirement 2.5).
            AutoResolveResult.Unavailable -> {
                Timber.w("Automatic save-location resolution unavailable for appId=$appId")
                ResolveOutcome.NeedsBrowser(automaticUnavailable = true)
            }
        }
    }

    /** Map the pure resolver's [SaveLocationResult] onto a combined [ResolveOutcome]. */
    private fun mapPureResult(result: SaveLocationResult): ResolveOutcome = when (result) {
        is SaveLocationResult.Resolved ->
            ResolveOutcome.Resolved(result.absolutePath, result.saveLocation)
        is SaveLocationResult.Unresolved -> ResolveOutcome.Unresolved(result.reason)
        // The pure resolver only returns Unset when handed a null location; we only ever call it
        // with a concrete location, so this branch is defensive. Treat it as "needs browser".
        SaveLocationResult.Unset -> ResolveOutcome.NeedsBrowser(automaticUnavailable = false)
    }

    companion object {
        /**
         * Default strategy selection: Steam uses automatic discovery; every other source
         * (GOG/Epic/Amazon/CustomGame) defers to the container browser.
         */
        val defaultStrategySelector: (GameSource) -> SaveSourceStrategy = { source ->
            when (source) {
                GameSource.STEAM -> SteamSaveSourceStrategy()
                else -> GenericSaveSourceStrategy()
            }
        }
    }
}
