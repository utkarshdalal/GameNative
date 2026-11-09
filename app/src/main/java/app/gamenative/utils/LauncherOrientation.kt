package app.gamenative.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet

/**
 * Launcher screen rotation driven by the System menu's rotate button.
 *
 * The button is a 5-state ring anchored to the device's natural orientation, so the first press
 * always turns the picture 90 degrees instead of landing on the orientation already on screen:
 *
 * - portrait-natural (phones): landscape, reverse portrait, reverse landscape, portrait, auto
 * - landscape-natural (handhelds): portrait, reverse landscape, reverse portrait, landscape, auto
 *
 * The ring is a table, never derived from the live display rotation: on landscape-natural panels
 * `display.rotation` does not map onto portrait-natural [ActivityInfo] constants, which used to
 * make presses skip states or repeat the current one.
 *
 * The actual `requestedOrientation` write stays in [MainActivity.setOrientationTo] so the sensor
 * orientator, the game's allowed orientations and this preference share one path.
 */
object LauncherOrientation {
    private val AUTO = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** Quarter turns of a portrait-natural panel, starting at its natural orientation. */
    private val PORTRAIT_NATURAL_RING = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    )

    /** Quarter turns of a landscape-natural panel, starting at its natural orientation. */
    private val LANDSCAPE_NATURAL_RING = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
    )

    /**
     * Press order for the device: auto shows the natural orientation, so the ring starts one
     * quarter turn after it, ends back on the natural orientation, then releases to auto.
     */
    fun ring(naturalPortrait: Boolean): IntArray {
        val visual = if (naturalPortrait) PORTRAIT_NATURAL_RING else LANDSCAPE_NATURAL_RING
        return intArrayOf(visual[1], visual[2], visual[3], visual[0], AUTO)
    }

    /**
     * True when the device's natural orientation is portrait, derived from the current screen
     * shape plus how far the display has been rotated away from natural.
     */
    fun naturalPortrait(activity: Activity): Boolean {
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        return if (sideways) !currentlyPortrait(activity) else currentlyPortrait(activity)
    }

    /**
     * True when the screen is currently portrait-shaped. Reliable on every panel, unlike
     * `display.rotation`, which is relative to the panel's natural orientation.
     */
    fun currentlyPortrait(activity: Activity): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** True for the two portrait-shaped orientations. */
    fun isPortraitOrientation(value: Int): Boolean =
        value == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            value == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT

    /**
     * True when the caller does not enforce any orientation, i.e. the launcher owns the screen.
     * Games always request a concrete set, including portrait-mode containers.
     */
    fun isAuto(conformTo: EnumSet<Orientation>): Boolean =
        conformTo.isEmpty() || (conformTo.size == 1 && conformTo.contains(Orientation.UNSPECIFIED))

    /**
     * Orientation the launcher itself should hold: the stored lock, or
     * [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED] when the user cycled back to auto.
     * Only meaningful while no container is running; games keep their own orientation.
     */
    fun launcherTarget(stored: Int = stored()): Int =
        if (isConcrete(stored)) stored else AUTO

    /** Last orientation picked with the rotate button; UNSPECIFIED means "follow the sensor". */
    fun stored(): Int = observed.value ?: PrefManager.launcherOrientation.also { observed.value = it }

    fun store(value: Int) {
        observed.value = value
        PrefManager.launcherOrientation = value
    }

    /**
     * Compose-observable mirror of [stored], following the `mutableStateOf` pattern used by
     * PrefManager, so the rotate button can highlight itself while the launcher is in auto.
     */
    private val observed = mutableStateOf<Int?>(null)

    /** True for the four locked orientations; false for auto (UNSPECIFIED). */
    fun isConcrete(value: Int): Boolean =
        value == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            value == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            value == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ||
            value == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

    /**
     * Next state of the rotate button.
     *
     * While the launcher is locked the press walks the ring. From auto (or an unknown value) it
     * enters at the first entry whose shape differs from what is on screen: with system rotation
     * locked to a non-natural orientation, always entering at `ring[0]` could otherwise request
     * the orientation already displayed and look like a dead press.
     */
    fun nextState(current: Int, naturalPortrait: Boolean, portraitOnScreen: Boolean): Int {
        val states = ring(naturalPortrait)
        val index = states.indexOf(current)

        // A locked launcher owns the request, so the screen follows it and the walk is a plain
        // ring step. Auto has no orientation of its own, so it enters at a fresh entry instead.
        if (index >= 0 && isConcrete(current)) return states[(index + 1) % states.size]

        val entry = states.firstOrNull {
            isConcrete(it) && isPortraitOrientation(it) != portraitOnScreen
        }
        return entry ?: states[0]
    }

    /** Applies the stored orientation before the first frame, to avoid a rotation flash. */
    fun applyStored(activity: Activity) {
        if (MainActivity.isHeadset(activity)) return

        // A container can outlive an activity recreation; it keeps owning the orientation then.
        if (PluviaApp.xEnvironment != null) return

        val target = launcherTarget()
        if (activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }
}
