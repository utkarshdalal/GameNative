package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Call of Duty: Black Ops - Multiplayer (Steam)
 *
 * Steam CEG protected, see [STEAM_Fix_42700].
 */
val STEAM_Fix_42710: KeyedGameFix = KeyedSteamClientRequiredFix(
    gameSource = GameSource.STEAM,
    gameId = "42710",
)
