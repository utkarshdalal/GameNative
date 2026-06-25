package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Hogwarts Legacy (Steam)
 *
 * Wipes C:\ProgramData\Hogwarts Legacy on every boot. The game caches state there
 * that occasionally leaves Denuvo / EOS in a bad mood after a previous session.
 */
val STEAM_Fix_990080: KeyedGameFix = KeyedDeleteFolderFix(
    gameSource = GameSource.STEAM,
    gameId = "990080",
    driveCRelativePaths = listOf("ProgramData/Hogwarts Legacy"),
)
