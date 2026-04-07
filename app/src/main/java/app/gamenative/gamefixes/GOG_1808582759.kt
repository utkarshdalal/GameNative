package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Jazz Jackrabbit Collection (GOG)
 */
val GOG_Fix_1808582759: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.GOG,
    gameId = "1808582759",
    launchArgs = "-conf \"..\\dosbox_jazz.conf\" -conf \"..\\dosbox_jazz_single.conf\" -noconsole -c \"exit\"",
)
