package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Portal (Steam)
 */
val STEAM_Fix_400: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "400",
    launchArgs = "-game portal",
)

