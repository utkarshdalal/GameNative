package app.gamenative.steamcontroller

/**
 * The app-layer seam the [ProfileInterpreter] uses to drive GameNative's own UI (the QuickMenu + the in-game
 * Steam-Controller editors) from the BLE controller. The BLE Triton is NOT an Android input device, so its input
 * never reaches the Compose focus system on its own — this bridge lets the interpreter (a) open the QuickMenu and
 * (b) translate controller movement/buttons into Android focus-nav key events while a menu/editor is up.
 *
 * Kept Android-free (no [android.view.KeyEvent]) so the engine stays unit-testable; [ScNavKey] is mapped to real
 * Android keycodes in the XServerScreen implementation. Defaults to [NoOpScUiBridge] so headless tests and the
 * no-overlay path run unchanged.
 */
interface ScUiBridge {
    /** True while any controller-capturing GameNative overlay is up (QuickMenu OR an SC editor dialog / element
     *  editor / edit mode). While true the interpreter suppresses game output and routes input to [nav]. */
    fun isMenuCapturing(): Boolean

    /** Open the in-game QuickMenu (press edge of an [ScOutput.OpenQuickMenu] binding). Marshals to the UI thread. */
    fun openQuickMenu()

    /** Dispatch one focus-nav key to the Compose UI (marshals to the UI thread). */
    fun nav(key: ScNavKey)

    /** Move the on-screen pad-mouse cursor by a pixel delta (right trackpad while a menu/editor is captured). The
     *  bridge draws the cursor over the top dialog and clamps it to that window. Marshals to the UI thread. */
    fun moveCursor(dx: Int, dy: Int) {}

    /** Inject a tap (down+up) at the current cursor position into the top dialog (right-pad click). UI thread. */
    fun cursorTap() {}

    /** Remove the on-screen pad-mouse cursor (called when menu/editor capture ends so the dot doesn't stick). */
    fun hideCursor() {}
}

/** Direction/selection intents emitted by the interpreter while a menu is captured; mapped to Android d-pad,
 *  DPAD_CENTER and back keycodes by the bridge implementation. [TAB_PREV]/[TAB_NEXT] (bumpers) flip between the
 *  command-picker tabs (Keyboard / Numpad / Mouse / Gamepad / …). [ZOOM_IN]/[ZOOM_OUT] (right/left trigger) resize
 *  the overlay in the placement editor; ignored elsewhere. */
enum class ScNavKey { UP, DOWN, LEFT, RIGHT, SELECT, BACK, TAB_PREV, TAB_NEXT, HELP, CLOSE, ZOOM_IN, ZOOM_OUT }

/** No-op bridge: no overlay attached / headless tests. */
object NoOpScUiBridge : ScUiBridge {
    override fun isMenuCapturing(): Boolean = false
    override fun openQuickMenu() {}
    override fun nav(key: ScNavKey) {}
}
