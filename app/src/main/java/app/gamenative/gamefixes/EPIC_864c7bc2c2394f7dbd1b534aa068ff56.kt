package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Hogwarts Legacy (Epic)
 *
 * Wipes C:\ProgramData\Hogwarts Legacy on every boot. The game caches state there
 * that occasionally leaves Denuvo / EOS in a bad mood after a previous session.
 */
val EPIC_Fix_864c7bc2c2394f7dbd1b534aa068ff56: KeyedGameFix = KeyedDeleteFolderFix(
    gameSource = GameSource.EPIC,
    gameId = "864c7bc2c2394f7dbd1b534aa068ff56",
    driveCRelativePaths = listOf("ProgramData/Hogwarts Legacy"),
)
