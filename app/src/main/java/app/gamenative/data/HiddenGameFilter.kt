package app.gamenative.data

import app.gamenative.PrefManager

/**
 * Visibility rules for games the user has hidden on a platform.
 *
 * Hidden games stay visible by default so an update never makes existing library entries
 * disappear. Turning off the "show hidden games by default" setting excludes them again, and
 * Steam's built-in Hidden collection can still explicitly reveal hidden Steam games. Missing
 * hidden metadata fails open so a game is never hidden just because the metadata has not loaded.
 */
object HiddenGameFilter {
    /**
     * Whether a Steam app should appear in the library.
     *
     * @param appId Steam app ID to test.
     * @param hiddenAppIds IDs from the Steam Hidden collection; empty when collections are unloaded.
     * @param showHiddenByDefault Value of [PrefManager.showHiddenGamesByDefault].
     * @param hiddenCollectionSelected Whether the Hidden collection is among the selected collection IDs.
     */
    fun passesSteam(
        appId: Int,
        hiddenAppIds: Set<Int>,
        showHiddenByDefault: Boolean,
        hiddenCollectionSelected: Boolean,
    ): Boolean = showHiddenByDefault || hiddenCollectionSelected || appId !in hiddenAppIds

    /**
     * Whether a GOG game should appear in the library.
     *
     * @param isHidden Whether the GOG row is flagged hidden (rows default to false until the first
     * hidden-metadata refresh, which fails open).
     * @param showHiddenByDefault Value of [PrefManager.showHiddenGamesByDefault].
     */
    fun passesGog(
        isHidden: Boolean,
        showHiddenByDefault: Boolean,
    ): Boolean = showHiddenByDefault || !isHidden
}
