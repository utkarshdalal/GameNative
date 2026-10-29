package app.gamenative.display

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Surface
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import com.winlator.widget.FrameRating

/** JNI binding for `libgamenative_drm.so` (Gamescope display-server mode); failures are logged under `GN_DRM`. */
object GameNativeDrm {
    init {
        System.loadLibrary("gamenative_drm")
    }

    /** [nativeRetargetSurface] return when no daemon session is running. */
    const val E_NO_SESSION: Int = -2

    /**
     * Called from native (`libgamenative_drm.so`) on the `PRESENTED_FRAMES` 0->1 edge — the first
     * real frame on the panel.
     */
    @JvmStatic
    fun clearBootingSplash() {
        mainHandler.post { PluviaApp.events.emit(AndroidEvent.ClearBootingSplash) }
    }

    /**
     * Tell the daemon the panel's real refresh rate in whole hertz, before [nativeStart]: it
     * becomes the rate of the mode the daemon exposes, i.e.
     */
    @JvmStatic
    external fun nativeSetPanelRefreshHz(refreshHz: Int)

    /** Start the daemon: create the SurfaceControl layer on [surface], then serve [socketName]. */
    @JvmStatic
    external fun nativeStart(socketName: String, surface: Surface, width: Int, height: Int): Int

    /** Stop the daemon (sockets shut down, server thread joined, publish layer released). */
    @JvmStatic
    external fun nativeStop()

    /** The scan-out pool layout the running session chose, "bgra" or "rgba"; valid after [nativeStart]. */
    @JvmStatic
    external fun nativeScanoutLayout(): String

    /**
     * Adopt a new display window for a RUNNING session without ending it: rebuild the publish
     * layer from [surface] and re-present the retained last frame, so a display power cycle keeps
     * the daemon, its sockets and the gamescope connection alive.
     */
    @JvmStatic
    external fun nativeRetargetSurface(surface: Surface, width: Int, height: Int): Int

    /** The live widget the daemon's per-published-frame upcall feeds. */
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var frameRating: FrameRating? = null

    /** Registers (or, with `null`, clears) the widget that [onFramePublished] feeds. */
    @JvmStatic
    fun setFrameRating(frameRating: FrameRating?) {
        this.frameRating = frameRating
    }

    /** Main-thread handler the native upcalls marshal UI events through. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called from native (`libgamenative_drm.so`) on each new frame the daemon publishes
     * (gamescope's frames never tick the X11 present hook).
     */
    @JvmStatic
    fun onFramePublished() {
        frameRating?.update()
    }

    // -- input injector lifecycle -------------------------------------------

    /** Starts the wayland injector thread under <xdgRuntimeDir>/<waylandDisplay>; idempotent, returns 0 or a negative code. */
    @JvmStatic
    external fun nativeInjectorStart(xdgRuntimeDir: String, waylandDisplay: String): Int

    /** Cap gamescope's frame rate for this app (0 = uncapped). */
    @JvmStatic
    external fun nativeSetFpsLimit(fps: Int)

    /**
     * Frame-generation settings the KMS presenter applies: [enabled] is the user's switch — an
     * explicit `false` stops generation where [multiplier] alone cannot — then [multiplier]
     * (2-4), [flowPerMille] (flow scale x 1000), [performance] (lower-cost shader set) and
     * [fpsLimit] (the cap on the **source** rate; the presenter presents at
     * `fpsLimit * multiplier`, 0 = uncapped) — and [dllPath], the container's
     * `Lossless.dll`, which the presenter needs because the container's
     * environment does not reach the app's process.
     */
    @JvmStatic
    external fun nativeSetLsfgConfig(
        enabled: Boolean,
        multiplier: Int,
        flowPerMille: Int,
        performance: Boolean,
        fpsLimit: Int,
        dllPath: String,
    )

    /** Load and enable a ReShade effect by file name (e.g. "GNVibrance.fx"); applied by the injector. */
    @JvmStatic
    external fun nativeReshadeSetEffect(name: String)

    /** Turn the loaded ReShade effect off. */
    @JvmStatic
    external fun nativeReshadeDisable()

    /** Run one allowlisted gamescope concommand ("name [value]"); 0 when queued, negative when not allowlisted. */
    @JvmStatic
    external fun nativeGamescopeCommand(command: String): Int

    /** gamescope's feature_support + active_display_info as "features=0x<hex>;connector=<name>;display_flags=0x<hex>;rates=<r>,<r>"; empty fields until gamescope answers. */
    @JvmStatic
    external fun nativeDisplayInfo(): String

    /** Stop the injector thread (no-op when not running). */
    @JvmStatic
    external fun nativeInjectorStop()

    /** Mark the session suspended (`true`) or resumed (`false`). */
    @JvmStatic
    external fun nativeInjectorSetSuspended(suspended: Boolean)

    // -- input injector (no-ops until the injector connects to gamescope) ---

    /** Absolute pointer position in output coordinates. */
    @JvmStatic
    external fun nativePointerAbsolute(x: Int, y: Int)

    @JvmStatic
    external fun nativePointerMotion(dx: Int, dy: Int)

    /** Pointer button (evdev BTN_* code). */
    @JvmStatic
    external fun nativePointerButton(button: Int, pressed: Boolean)

    /** Scroll axis: 0 = vertical, 1 = horizontal; value in detents. */
    @JvmStatic
    external fun nativePointerAxis(axis: Int, value: Float)

    /** evdev key code. */
    @JvmStatic
    external fun nativeKey(keyCode: Int, pressed: Boolean)
}
