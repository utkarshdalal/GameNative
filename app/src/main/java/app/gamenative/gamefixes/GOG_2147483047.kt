package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Moonlighter (GOG)
 */
val GOG_Fix_2147483047: KeyedGameFix = GOGDependencyFix(
    gameSource = GameSource.GOG,
    gameId = "2147483047",
    dependencyIds = listOf("MSVC2017", "MSVC2017_x64"),
)
