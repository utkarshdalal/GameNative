package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Call of Duty: Black Ops (Steam)
 *
 * Steam CEG protected: with the emulated Steam client / Steamless it never gets past a black screen.
 */
val STEAM_Fix_42700: KeyedGameFix = KeyedSteamClientRequiredFix(
    gameSource = GameSource.STEAM,
    gameId = "42700",
)
