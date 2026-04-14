package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Dome Keeper (GOG)
 */
val GOG_Fix_1635627436: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.GOG,
    gameId = "1635627436",
    launchArgs = "--rendering-driver vulkan",
)

