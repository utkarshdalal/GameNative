package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.gamenative.R
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer

class SwapInputOverlayView(
    context: Context,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView? = { null },
    private val useHub: Boolean = false,
) : FrameLayout(context) {

    private var mode: ExternalDisplayInputController.Mode = ExternalDisplayInputController.Mode.OFF

    // VOTV-only path: a single menu/hub replaces the generic touchpad+keyboard views below.
    // Left null (and never built) when useHub is false, so the regular GameNative app's
    // external-display feature is unaffected.
    private val hubLayout: HubLayout? = if (useHub) {
        HubLayout(context, xServer, touchpadViewProvider).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
    } else {
        null
    }

    private val hintIcon: ImageView? = if (useHub) {
        null
    } else {
        ImageView(context).apply {
            val density = resources.displayMetrics.density
            val sizePx = (128 * density).toInt()
            layoutParams = LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.icon_keyboard)
            setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
    }

    private val keyboardView: ExternalOnScreenKeyboardView? = if (useHub) {
        null
    } else {
        ExternalOnScreenKeyboardView(context, xServer).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
            }
            visibility = View.GONE
        }
    }

    private val keyboardToggleButton: ImageButton? = if (useHub) {
        null
    } else {
        ImageButton(context).apply {
            val density = resources.displayMetrics.density
            val sizePx = (56 * density).toInt()
            val marginPx = (16 * density).toInt()
            layoutParams = LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.external_display_key_background))
            }
            setImageResource(R.drawable.icon_keyboard)
            setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
            visibility = View.GONE
            setOnClickListener { toggleKeyboard() }
        }
    }

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isClickable = false
        isFocusable = false

        if (useHub) {
            hubLayout?.let(::addView)
        } else {
            hintIcon?.let(::addView)
            keyboardView?.let(::addView)
            keyboardToggleButton?.let(::addView)

            keyboardView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateToggleButtonPosition()
            }
        }
    }

    fun setMode(mode: ExternalDisplayInputController.Mode) {
        this.mode = mode

        if (useHub) {
            hubLayout?.visibility = if (mode == ExternalDisplayInputController.Mode.OFF) View.GONE else View.VISIBLE
            return
        }

        when (mode) {
            ExternalDisplayInputController.Mode.KEYBOARD -> {
                hintIcon?.visibility = View.VISIBLE
                keyboardToggleButton?.visibility = View.GONE
                keyboardView?.visibility = View.VISIBLE
                updateToggleButtonPosition()
            }
            ExternalDisplayInputController.Mode.HYBRID -> {
                hintIcon?.visibility = View.GONE
                keyboardToggleButton?.visibility = View.VISIBLE
                keyboardView?.visibility = View.GONE
                updateToggleButtonPosition()
            }
            else -> {
                hintIcon?.visibility = View.GONE
                keyboardToggleButton?.visibility = View.GONE
                keyboardView?.visibility = View.GONE
                updateToggleButtonPosition()
            }
        }
    }

    private fun toggleKeyboard() {
        if (mode != ExternalDisplayInputController.Mode.HYBRID) return
        keyboardView?.visibility = if (keyboardView?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        post { updateToggleButtonPosition() }
    }

    private fun updateToggleButtonPosition() {
        keyboardToggleButton?.translationY = if (keyboardView?.visibility == View.VISIBLE) {
            -(keyboardView?.height ?: 0).toFloat()
        } else {
            0f
        }
    }
}
