package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Half-Life 2 (Steam)
 */
val STEAM_Fix_220: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "220",
    launchArgs = "-game hl2_complete",
)
