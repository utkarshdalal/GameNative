package app.gamenative.externaldisplay

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.winlator.widget.XServerView

class ExternalDisplaySwapController(
    private val context: Context,
    private val xServerViewProvider: () -> XServerView?,
    private val internalGameHostProvider: () -> ViewGroup?,
    private val onGameOnExternalChanged: (Boolean) -> Unit = {},
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: GamePresentation? = null
    private var swapEnabled: Boolean = false
    private var gameOnExternal: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                updatePresentation()
            }
        }
    }

    fun start() {
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updatePresentation()
    }

    fun stop() {
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
    }

    fun setSwapEnabled(enabled: Boolean) {
        if (swapEnabled == enabled) return
        swapEnabled = enabled
        updatePresentation()
    }

    private fun updatePresentation() {
        val targetDisplay = if (swapEnabled) findPresentationDisplay() else null
        if (targetDisplay == null) {
            moveGameToInternal()
            dismissPresentation()
            return
        }

        val needsNewPresentation = presentation?.display?.displayId != targetDisplay.displayId
        if (presentation == null || needsNewPresentation) {
            dismissPresentation()
            presentation = GamePresentation(context, targetDisplay).also { it.show() }
        }
        moveGameToExternal()
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        setGameOnExternal(false)
    }

    private fun moveGameToExternal() {
        val xServerView = xServerViewProvider() ?: return
        val root = presentation?.root ?: return
        val parent = xServerView.parent as? ViewGroup
        if (parent != null && parent != root) parent.removeView(xServerView)
        if (xServerView.parent == null) {
            xServerView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            root.addView(xServerView)
        }
        setGameOnExternal(true)
    }

    private fun moveGameToInternal() {
        val xServerView = xServerViewProvider() ?: return
        val internalHost = internalGameHostProvider() ?: return
        val parent = xServerView.parent as? ViewGroup
        if (parent != null && parent != internalHost) parent.removeView(xServerView)
        if (xServerView.parent == null) {
            xServerView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            internalHost.addView(xServerView)
        }
        setGameOnExternal(false)
    }

    private fun setGameOnExternal(value: Boolean) {
        if (gameOnExternal == value) return
        gameOnExternal = value
        onGameOnExternalChanged(value)
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = context.display ?: return null
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }
}

private class GamePresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {
    val root: FrameLayout by lazy {
        FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        setContentView(root)
    }
}
