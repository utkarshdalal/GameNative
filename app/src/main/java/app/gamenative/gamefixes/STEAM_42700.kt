package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Call of Duty: Black Ops (Steam)
 *
 * - Steam CEG protected: the emulated Steam client / Steamless never get past a black screen, and on
 *   first launch Steam must regenerate the exe ("Updating executable"), so default to the real Steam
 *   client in headless mode, the configuration known to run it.
 * - 32-bit D3D9 under WoW64: through WineD3D (GL -> zink -> Vulkan) it ran out of address space right
 *   after the intro (a 2.7 GB allocation) and deadlocked on a black screen with audio. Use DXVK, report
 *   1 GB of VRAM (d3d9.maxAvailableMemory) so the engine sizes its pools for a 32-bit process, and make
 *   the process large-address-aware (4 GB instead of 2 GB).
 */
val STEAM_Fix_42700: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "42700",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("dxvk", mapOf("maxDeviceMemory" to "1024")),
        WineEnvVarFix(mapOf("WINE_LARGE_ADDRESS_AWARE" to "1")),
    ),
)
