package app.gamenative.display

import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.core.AppUtils
import java.io.File

/** The gamescope effects a session should have in effect: one value per effect domain. */
data class GamescopeState(
    val filter: GamescopeScalingFilter? = null,
    val sharpness: Int? = null,
    val look: GamescopeLook? = null,
    val scaler: GamescopeScalingMode? = null,
)

/** Where a container's last-used gamescope effects live, plus the bundled LUT location. */
object GamescopeSettings {
    const val EXTRA_FILTER = "gamescopeScalingFilter"
    const val EXTRA_SHARPNESS = "gamescopeScalingSharpness"
    const val EXTRA_LOOK = "gamescopeLook"

    /** ReShade effect file name last chosen for the container; empty = off. */
    const val EXTRA_RESHADE = "gamescopeReshade"
    const val EXTRA_SCALER = "gamescopeScalingMode"

    /** Output resolution the container presents at, as "WxH", [OUTPUT_RESOLUTION_DEVICE] or [OUTPUT_RESOLUTION_OFF]. */
    const val EXTRA_OUTPUT_RESOLUTION = "gamescopeOutputResolution"

    /** 1080p — the output resolution a session presents at until the container picks another. */
    const val OUTPUT_RESOLUTION_DEFAULT = "1920x1080"

    /** `-W`/`-H` follow the container's screen size: the game presents 1:1, nothing is resampled. */
    const val OUTPUT_RESOLUTION_OFF = "off"

    /** `-W`/`-H` follow the panel's real pixel size (the pre-setting behaviour). */
    const val OUTPUT_RESOLUTION_DEVICE = "device"

    // Sharpness is stored in the UI's own units: 0..SHARPNESS_STEPS, higher = sharper — the scale the
    // QuickMenu row shows.
    const val SHARPNESS_STEPS = 5
    const val SHARPNESS_DEFAULT = SHARPNESS_STEPS

    /** The vblank grid a gamescope session is launched with (`-r`), in Hz. */
    const val SESSION_GRID_HZ = 1200

    /** gamescope's raw `scaling_sharpness` (0 = maximum sharpening) for a stored UI value. */
    fun sharpnessRawValue(ui: Int): Int =
        (SHARPNESS_STEPS - ui.coerceIn(0, SHARPNESS_STEPS)) * 4

    /** The `--scaler <mode>` argument for a session's launch command, or "" for the default. */
    @JvmStatic
    fun scalerArg(container: Container?): String {
        val mode = GamescopeScalingMode.fromId(container?.getExtra(EXTRA_SCALER, ""))
            ?: GamescopeScalingMode.DEFAULT
        return if (mode == GamescopeScalingMode.DEFAULT) "" else " --scaler ${mode.id}"
    }

    /**
     * The `-r <grid>` argument for a session's launch command, holding the grid at
     * [SESSION_GRID_HZ] so `g_nNestedRefresh` wins over the panel rate the daemon advertises as
     * the mode (see [SESSION_GRID_HZ]).
     */
    @JvmStatic
    fun gridArg(): String = " -r $SESSION_GRID_HZ"

    /**
     * The output resolution a session presents at ("WxH"). [OUTPUT_RESOLUTION_OFF] resolves to the
     * container's screen size and [OUTPUT_RESOLUTION_DEVICE] to the panel's real pixel size; "" only
     * when the resolved size can't be read, which leaves the pair off the launch command.
     */
    @JvmStatic
    fun outputResolution(container: Container?): String {
        val stored = container?.getExtra(EXTRA_OUTPUT_RESOLUTION, "").orEmpty()
        val resolution = stored.ifEmpty { OUTPUT_RESOLUTION_DEFAULT }
        return when {
            resolution.equals(OUTPUT_RESOLUTION_OFF, ignoreCase = true) ->
                container?.screenSize.orEmpty()
            resolution.equals(OUTPUT_RESOLUTION_DEVICE, ignoreCase = true) ->
                PluviaApp.getNativeDisplaySize().orEmpty()
            else -> resolution
        }
    }

    /** The `-W <w> -H <h>` pair for [outputResolution], or "" when it is off or unreadable. */
    @JvmStatic
    fun outputGeometryArg(container: Container?): String {
        val size = AppUtils.parseScreenSize(outputResolution(container)) ?: return ""
        return " -W ${size[0]} -H ${size[1]}"
    }

    /**
     * The session's X socket — gamescope's own Xwayland, whose root window the live scaling-mode
     * property is set on.
     */
    @JvmStatic
    fun x11DisplayPath(imageFsRootDir: File): File =
        File(imageFsRootDir, "usr/tmp/.X11-unix/X0")

    /** Directory the bundled gamescope payload is extracted under (`filesDir/gamescope`). */
    @JvmStatic
    fun gamescopeDir(filesDir: File): File = File(filesDir, "gamescope")

    /** Absolute path of the bundled Gamma-2.2 LUT for [look] under [gamescopeDir]. */
    fun lutPath(gamescopeDir: File, look: GamescopeLook): String =
        File(gamescopeDir, "share/gamescope/looks/${look.lutFileName}").absolutePath

    /** The effects last used for [container], for seeding a session at start. */
    @JvmStatic
    fun fromContainer(container: Container?): GamescopeState {
        if (container == null) return GamescopeState()
        val filter = GamescopeScalingFilter.fromId(container.getExtra(EXTRA_FILTER, ""))
        // Sharpness is always seeded: the default is maximum sharpening, so a container that never opened
        // the tab still runs the value the tab shows as its default instead of gamescope's own built-in 2.
        val sharpness = container.getExtra(EXTRA_SHARPNESS, "").orEmpty()
            .toIntOrNull()?.coerceIn(0, SHARPNESS_STEPS) ?: SHARPNESS_DEFAULT
        val look = container.getExtra(EXTRA_LOOK, "").orEmpty()
            .takeIf { it.isNotEmpty() }?.let(GamescopeLook::fromId)
        val scaler = GamescopeScalingMode.fromId(container.getExtra(EXTRA_SCALER, ""))
        return GamescopeState(filter = filter, sharpness = sharpness, look = look, scaler = scaler)
    }
}
