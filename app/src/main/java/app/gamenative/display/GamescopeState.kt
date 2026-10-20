package app.gamenative.display

import com.winlator.container.Container
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
