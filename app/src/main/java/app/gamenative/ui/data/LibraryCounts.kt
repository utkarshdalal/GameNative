package app.gamenative.ui.data

import app.gamenative.PrefManager

/**
 * Persists the library sizes that drive skeleton loaders.
 *
 * Callers must pass sizes of already-filtered lists (after default hidden-game filtering) so the
 * persisted Steam/GOG counts never include games that are hidden by default.
 */
object LibraryCounts {
    fun persist(
        customGames: Int,
        steamGames: Int,
        gogGames: Int,
        gogInstalledGames: Int,
        epicGames: Int,
        epicInstalledGames: Int,
        amazonInstalledGames: Int,
    ) {
        PrefManager.customGamesCount = customGames
        PrefManager.steamGamesCount = steamGames
        PrefManager.gogGamesCount = gogGames
        PrefManager.gogInstalledGamesCount = gogInstalledGames
        PrefManager.epicGamesCount = epicGames
        PrefManager.epicInstalledGamesCount = epicInstalledGames
        PrefManager.amazonInstalledGamesCount = amazonInstalledGames
    }
}
