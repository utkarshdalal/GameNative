package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Kingdom Hearts III (Epic)
 */
val EPIC_Fix_e345fdb9186645a48d30c3f85a8951dc: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.EPIC,
    gameId = "e345fdb9186645a48d30c3f85a8951dc",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "mf=n;mfmediaengine=n",
    ),
)
