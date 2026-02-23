package app.gamenative.externaldisplay

import android.app.Presentation
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.gamenative.R
import com.winlator.container.Container
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer

private const val EXTERNAL_SURFACE_BG_RES: Int = R.color.external_display_surface_background
private const val EXTERNAL_KEY_BG_RES: Int = R.color.external_display_key_background

class ExternalDisplayInputController(
    private val context: Context,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) {
    enum class Mode { OFF, TOUCHPAD, KEYBOARD, HYBRID }

    companion object {
        fun fromConfig(value: String?): Mode = when (value?.lowercase()) {
            Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD -> Mode.TOUCHPAD
            Container.EXTERNAL_DISPLAY_MODE_KEYBOARD -> Mode.KEYBOARD
            Container.EXTERNAL_DISPLAY_MODE_HYBRID -> Mode.HYBRID
            else -> Mode.OFF
        }
    }

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: ExternalInputPresentation? = null
    private var mode: Mode = Mode.OFF

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updatePresentation()
        }

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

    fun setMode(mode: Mode) {
        this.mode = mode
        updatePresentation()
    }

    private fun updatePresentation() {
        if (mode == Mode.OFF) {
            dismissPresentation()
            return
        }

        val targetDisplay = findPresentationDisplay() ?: run {
            dismissPresentation()
            return
        }

        val needsNewPresentation = presentation?.display?.displayId != targetDisplay.displayId
        if (presentation == null || needsNewPresentation) {
            dismissPresentation()
            presentation = ExternalInputPresentation(
                context = context,
                display = targetDisplay,
                mode = mode,
                xServer = xServer,
                touchpadViewProvider = touchpadViewProvider,
            )
            presentation?.show()
        } else {
            presentation?.updateMode(mode)
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = context.display ?: return null
        // Required detection logic for external presentation displays
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }
}

private class ExternalInputPresentation(
    context: Context,
    display: Display,
    private var mode: ExternalDisplayInputController.Mode,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        renderContent()
    }

    fun updateMode(newMode: ExternalDisplayInputController.Mode) {
        if (mode != newMode) {
            mode = newMode
            renderContent()
        }
    }

    private fun renderContent() {
        when (mode) {
            ExternalDisplayInputController.Mode.TOUCHPAD -> {
                val pad = TouchpadView(context, xServer, false).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, EXTERNAL_SURFACE_BG_RES))
                    touchpadViewProvider()?.let { primary ->
                        setSimTouchScreen(primary.isSimTouchScreen)
                    }
                }
                setContentView(pad)
            }
            ExternalDisplayInputController.Mode.KEYBOARD -> {
                val root = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, EXTERNAL_SURFACE_BG_RES))
                }

                val hintIcon = ImageView(context).apply {
                    val density = resources.displayMetrics.density
                    val sizePx = (128 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.icon_keyboard)
                    alpha = 0.35f
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }
                }

                root.addView(hintIcon)
                root.addView(keyboardView)
                setContentView(root)
            }
            ExternalDisplayInputController.Mode.HYBRID -> {
                val hybrid = HybridInputLayout(
                    context = context,
                    xServer = xServer,
                    touchpadViewProvider = touchpadViewProvider,
                )
                setContentView(hybrid)
            }
            else -> {
                setContentView(FrameLayout(context))
            }
        }
    }
}

private class HybridInputLayout(
    context: Context,
    xServer: XServer,
    touchpadViewProvider: () -> TouchpadView?,
) : FrameLayout(context) {

    private val touchpad = TouchpadView(context, xServer, false).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(ContextCompat.getColor(context, EXTERNAL_SURFACE_BG_RES))
        touchpadViewProvider()?.let { primary ->
            setSimTouchScreen(primary.isSimTouchScreen)
        }
    }
    private val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        visibility = View.GONE
    }

    private val keyboardToggleButton = ImageButton(context).apply {
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val marginPx = (16 * density).toInt()
        layoutParams = LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, EXTERNAL_KEY_BG_RES))
        }
        setImageResource(R.drawable.icon_keyboard)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
        setOnClickListener { toggleKeyboard() }
    }

    init {
        addView(touchpad)
        addView(keyboardView)
        addView(keyboardToggleButton)

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToggleButtonPosition()
        }
    }

    private fun toggleKeyboard() {
        val shouldShow = keyboardView.visibility != View.VISIBLE
        keyboardView.visibility = if (shouldShow) View.VISIBLE else View.GONE
        post { updateToggleButtonPosition() }
    }
    private fun updateToggleButtonPosition() {
        keyboardToggleButton.translationY = if (keyboardView.visibility == View.VISIBLE) {
            -keyboardView.height.toFloat()
        } else {
            0f
        }
    }
}
