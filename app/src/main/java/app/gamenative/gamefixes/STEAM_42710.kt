package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Call of Duty: Black Ops - Multiplayer (Steam)
 *
 * - Steam CEG protected: the emulated Steam client / Steamless never get past a black screen, and on
 *   first launch Steam must regenerate the exe ("Updating executable"), so default to the real Steam
 *   client in headless mode, the configuration known to run it.
 * - 32-bit D3D9 under WoW64: through WineD3D (GL -> zink -> Vulkan) it ran out of address space right
 *   after the intro (a 2.7 GB allocation) and deadlocked on a black screen with audio. Use DXVK, report
 *   1 GB of VRAM (d3d9.maxAvailableMemory) so the engine sizes its pools for a 32-bit process, and make
 *   the process large-address-aware (4 GB instead of 2 GB).
 * - X11 display server: under the gamescope session (the Adreno default) the game's Vulkan bypasses the
 *   wrapper ICD, so a 32-bit process gets memory mappings above 4 GB ("vkMapMemory2KHR returned mapping
 *   ... does not fit 32-bit pointer") and DXVK fails with "DirectX encountered an unrecoverable error".
 *   The X11 path goes through the wrapper, which places mappings for 32-bit processes.
 */
val STEAM_Fix_42710: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "42710",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("dxvk", mapOf("maxDeviceMemory" to "1024")),
        WineEnvVarFix(mapOf("WINE_LARGE_ADDRESS_AWARE" to "1")),
        DisplayServerDefaultFix(Container.DISPLAY_SERVER_X11),
    ),
)
