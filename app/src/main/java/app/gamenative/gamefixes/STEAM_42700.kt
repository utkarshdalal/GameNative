package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Call of Duty: Black Ops (Steam)
 *
 * - Steam CEG protected: the emulated Steam client / Steamless never get past a black screen, and on
 *   first launch Steam must regenerate the exe ("Updating executable"), so default to the real Steam
 *   client in headless mode, the configuration known to run it.
 * - 32-bit D3D9 under WoW64, so address space is tight: use DXVK rather than WineD3D's GL -> zink
 *   chain, report 1 GB of VRAM (d3d9.maxAvailableMemory) so the engine sizes its pools for a 32-bit
 *   process, and make the process large-address-aware (4 GB instead of 2 GB).
 * - X11 display server: under the gamescope session (the Adreno default) the game's Vulkan bypasses the
 *   wrapper ICD, so a 32-bit process gets memory mappings above 4 GB ("vkMapMemory2KHR returned mapping
 *   ... does not fit 32-bit pointer") and DXVK fails with "DirectX encountered an unrecoverable error".
 *   The X11 path goes through the wrapper, which places mappings for 32-bit processes.
 * - ALSA audio: right after XAudio2 opens its device through winepulse.drv the game asks for a 2.7 GB
 *   allocation (err:virtual:allocate_virtual_memory ... size aa010000, identical across runs and
 *   renderers), which a 32-bit process can't satisfy; audio init fails and the game hangs (no audio,
 *   frozen title screen). winealsa avoids the winepulse path.
 * - Wine's WMA decoder: when the title-screen music starts, the native wmadmod.dll installed by the
 *   "wmdecoder" Windows component calls mfplat.MFCreateLegacyMediaBufferOnMFMediaBuffer, which this
 *   Wine doesn't implement; the thread aborts and the game deadlocks on the title screen. wmdecoder=0
 *   restores Wine's builtin decoder.
 */
val STEAM_Fix_42700: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "42700",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("dxvk", mapOf("maxDeviceMemory" to "1024")),
        WineEnvVarFix(mapOf("WINE_LARGE_ADDRESS_AWARE" to "1")),
        ContainerSettingDefaultFix.displayServer(Container.DISPLAY_SERVER_X11),
        ContainerSettingDefaultFix.audioDriver("alsa"),
        ContainerSettingDefaultFix.winComponent("wmdecoder", "0"),
    ),
)
