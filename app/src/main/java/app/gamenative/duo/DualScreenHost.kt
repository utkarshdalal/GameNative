package app.gamenative.duo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import app.gamenative.duo.display.DisplayInventory
import app.gamenative.duo.display.DisplaySnapshot
import com.winlator.container.Container
import com.winlator.xserver.XServer
import timber.log.Timber
import java.io.File

/**
 * Entry point for GameNativeDuo's dual-screen features.
 *
 * Upstream code calls into the devkit from exactly one place: [attach], once per game session from
 * XServerScreen, right after the stock external-display controllers start. Everything else lives in
 * the `app.gamenative.duo` package so upstream merges never touch it.
 *
 * Nothing here may break a game session: every entry point catches and logs its own failures.
 */
object DualScreenHost {
    internal const val TAG = "GNDuo"

    /**
     * Starts a dual-screen session for the game that is launching. The session ends by itself when
     * [lifecycleView] (the game screen's root view) is detached from its window.
     */
    @JvmStatic
    fun attach(context: Context, lifecycleView: View, container: Container, xServer: XServer?): DualScreenSession? = try {
        DualScreenSession(context, container, xServer).also { session ->
            session.start()
            lifecycleView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}

                    override fun onViewDetachedFromWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        session.detach()
                    }
                },
            )
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).e(t, "Failed to start dual-screen session")
        null
    }
}

/**
 * One game session's dual-screen state. For now it records how Android reports the device's displays
 * (logcat tag `GNDuo`, plus `Android/data/<package>/files/duo/displays.json`) whenever they change, so
 * device profiles can be built from real data. Bottom-screen rendering, routing and panels build on this.
 */
class DualScreenSession internal constructor(
    private val context: Context,
    val container: Container,
    val xServer: XServer?,
) {
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val lastChangeKeys = mutableMapOf<Int, String>()
    private var running = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = report("display $displayId added")

        override fun onDisplayRemoved(displayId: Int) = report("display $displayId removed")

        override fun onDisplayChanged(displayId: Int) {
            // Fires often (brightness, refresh-rate hints); only report changes that matter for layout.
            val changed = DisplayInventory.snapshot(context).firstOrNull { it.id == displayId } ?: return
            if (lastChangeKeys[displayId] != changed.changeKey()) report("display $displayId changed")
        }
    }

    internal fun start() {
        if (running) return
        running = true
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        report("session start (container ${container.id})")
    }

    fun detach() {
        if (!running) return
        running = false
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to unregister display listener")
        }
        Timber.tag(DualScreenHost.TAG).i("Session ended")
    }

    private fun report(reason: String) {
        try {
            val displays = DisplayInventory.snapshot(context)
            lastChangeKeys.clear()
            displays.forEach { lastChangeKeys[it.id] = it.changeKey() }
            Timber.tag(DualScreenHost.TAG).i("Displays (%s): %d found", reason, displays.size)
            displays.forEach { Timber.tag(DualScreenHost.TAG).i("  %s", it.summary()) }
            writeReport(displays, reason)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to read displays")
        }
    }

    private fun writeReport(displays: List<DisplaySnapshot>, reason: String) {
        val json = DisplayInventory.toJson(displays, reason).toString(2)
        val dir = context.getExternalFilesDir("duo") ?: return
        Thread({
            try {
                File(dir, "displays.json").writeText(json)
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Failed to write displays.json")
            }
        }, "GNDuo-report").start()
    }
}
