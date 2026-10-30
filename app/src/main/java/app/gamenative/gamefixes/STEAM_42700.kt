package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Call of Duty: Black Ops (Steam)
 *
 * - Steam CEG protected: the emulated Steam client / Steamless never get past a black screen, and on
 *   first launch Steam must regenerate the exe ("Updating executable"), so default to the real Steam
 *   client in headless mode, the configuration known to run it.
 * - Under DXVK's d3d9 it commonly shows a black screen with working audio (ProtonDB 42700 reports, fixed
 *   there with PROTON_USE_WINED3D=1), so default to WineD3D.
 */
val STEAM_Fix_42700: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "42700",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("wined3d"),
    ),
)
