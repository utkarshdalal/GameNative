package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Stardew Valley (Steam)
 */
val STEAM_Fix_413150: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "413150",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "icu=n",
    ),
)
