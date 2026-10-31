package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Call of Duty: Black Ops - Multiplayer (Steam)
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
 * - Wine's DirectSound: the native dsound.dll installed by the "directsound" Windows component failed
 *   in its DllMain on a fresh container ("DSOUND.dll" failed to initialize, c0000005), so BlackOps.exe
 *   aborted before opening a window (black screen). directsound=0 uses Wine's builtin DirectSound.
 * - The PC version has no controller support, so a physical controller defaults to the bundled
 *   "Call of Duty (Console Layout)" profile, which maps the console layout onto keyboard/mouse.
 * - No virtual gamepad inside Wine (EVSHIM_MAX_PLAYERS=0): the game still polls XInput, and loading
 *   into a map its main thread hung while holding xinput1_3's controller lock ("controllers[0].crit
 *   wait timed out ... blocked by" the game thread), freezing and then crashing the game. The profile
 *   above drives the game through keyboard/mouse, so the game loses nothing without the pad.
 * - No 3D for WineD3D in BlackOpsMP.exe (AppDefaults renderer=no3d): the game renders through DXVK's
 *   d3d9, but it also opens DirectDraw, which brings up WineD3D's OpenGL renderer (GL over zink)
 *   inside the 32-bit process, and while loading a map it keeps creating new GL contexts ("threaded
 *   context created" / wined3d_cs_create). Loading Ascension froze the game every time and the app
 *   stopped responding. no3d keeps DirectDraw working for its 2D/caps queries without any GL.
 * - No Steam overlay in the game (gameoverlayrenderer disabled for the exe): it is injected into the
 *   32-bit game, hooks every graphics API by creating throwaway devices (the likely source of the
 *   DirectDraw/GL contexts above), and its UI process (gameoverlayui64) crashed on every run.
 *   It can't be opened from a touch screen anyway.
 */
val STEAM_Fix_42710: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "42710",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("dxvk", mapOf("maxDeviceMemory" to "1024")),
        WineEnvVarFix(mapOf("WINE_LARGE_ADDRESS_AWARE" to "1", "EVSHIM_MAX_PLAYERS" to "0")),
        ContainerSettingDefaultFix.displayServer(Container.DISPLAY_SERVER_X11),
        ContainerSettingDefaultFix.audioDriver("alsa"),
        ContainerSettingDefaultFix.winComponent("wmdecoder", "0"),
        ContainerSettingDefaultFix.winComponent("directsound", "0"),
        WineAppDefaultsFix("BlackOpsMP.exe", "Direct3D", mapOf("renderer" to "no3d")),
        WineAppDefaultsFix("BlackOpsMP.exe", "DllOverrides", mapOf("gameoverlayrenderer" to "")),
        ControlProfileDefaultFix("Call of Duty (Console Layout)"),
    ),
)
