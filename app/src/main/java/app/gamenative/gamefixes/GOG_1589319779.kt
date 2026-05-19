package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Moonlighter (GOG)
 */
val GOG_Fix_1589319779: KeyedGameFix = GOGDependencyFix(
    gameSource = GameSource.GOG,
    gameId = "1589319779",
    dependencyIds = listOf("MSVC2017", "MSVC2017_x64"),
)
