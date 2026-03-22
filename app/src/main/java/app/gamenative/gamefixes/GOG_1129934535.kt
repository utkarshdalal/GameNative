package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Mars: War Logs (GOG)
 */
val GOG_Fix_1129934535: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.GOG,
    gameId = "1129934535",
    launchArgs = "-lang=eng",
)
