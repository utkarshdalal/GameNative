package app.gamenative.data

import app.gamenative.PrefManager

/**
 * Visibility rules for games the user has hidden on a platform.
 *
 * Hidden games are excluded from the library by default. Steam's built-in Hidden collection can
 * explicitly reveal hidden Steam games, and the "show hidden games by default" setting reveals
 * hidden games everywhere. Missing hidden metadata fails open so a game is never hidden just
 * because the metadata has not loaded yet.
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
