package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Moonlighter (GOG): manifest does not list MSVC2017_x64; ensure it is downloaded and available
 * in _CommonRedist so the fix can run VC_redist.x64.exe.
 */
val GOG_Fix_1589319779: KeyedGameFix = GOGDependencyFix(
    gameSource = GameSource.GOG,
    gameId = "1589319779",
    dependencyIds = listOf("MSVC2017", "MSVC2017_x64"),
)
