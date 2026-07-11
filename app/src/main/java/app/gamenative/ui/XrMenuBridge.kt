package app.gamenative.ui

import android.view.View
import java.util.function.IntConsumer

/**
 * Bridge that lets the XR controller (com.winlator.xr) drive the REAL Compose QuickMenu instead of
 * the fork's simple XrDialog. XServerScreen publishes these; XrController calls toggleMenu on the
 * menu button and routes nav while menuOpen; XrRenderer captures overlayView onto the VR quad while
 * menuOpen. Opening the QuickMenu also pauses the game (its onAnimationComplete -> pauseForOverlay).
 *
 * All fields are @Volatile: they are written on the Compose UI thread but read on the GL/XR render
 * thread, so without volatile the render thread never sees menuOpen flip and the overlay never draws.
 */
object XrMenuBridge {
    /** Open/close the real QuickMenu (pause/resume is tied to it). Must be run on the UI thread. */
    @Volatile
    @JvmField
    var toggleMenu: Runnable? = null

    /** The game-root Compose view, captured onto the VR quad while the menu is open. */
    @Volatile
    @JvmField
    var overlayView: View? = null

    /** Whether the QuickMenu is currently open. */
    @Volatile
    @JvmField
    var menuOpen: Boolean = false

    /** Whether the performance HUD is enabled — render the overlay (which captures the HUD view) even
     *  while the menu is closed and the game is running, so the HUD shows on the quad during play. */
    @Volatile
    @JvmField
    var hudVisible: Boolean = false

    /** Send a nav key (android.view.KeyEvent.KEYCODE_*) into the menu. Must be run on the UI thread. */
    @Volatile
    @JvmField
    var sendKey: IntConsumer? = null
}
