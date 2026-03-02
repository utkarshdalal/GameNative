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
import com.winlator.xserver.XServer

class SwapInputOverlayView(
    context: Context,
    private val xServer: XServer,
) : FrameLayout(context) {

    private var mode: ExternalDisplayInputController.Mode = ExternalDisplayInputController.Mode.OFF

    private val hintIcon: ImageView = ImageView(context).apply {
        val density = resources.displayMetrics.density
        val sizePx = (128 * density).toInt()
        layoutParams = LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.CENTER
        }
        setImageResource(R.drawable.icon_keyboard)
        alpha = 0.35f
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    private val keyboardView: ExternalOnScreenKeyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        visibility = View.GONE
    }

    private val keyboardToggleButton: ImageButton = ImageButton(context).apply {
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
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
        visibility = View.GONE
        setOnClickListener { toggleKeyboard() }
    }

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isClickable = false
        isFocusable = false

        addView(hintIcon)
        addView(keyboardView)
        addView(keyboardToggleButton)

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToggleButtonPosition()
        }
    }

    fun setMode(mode: ExternalDisplayInputController.Mode) {
        this.mode = mode
        when (mode) {
            ExternalDisplayInputController.Mode.KEYBOARD -> {
                hintIcon.visibility = View.VISIBLE
                keyboardToggleButton.visibility = View.GONE
                keyboardView.visibility = View.VISIBLE
                updateToggleButtonPosition()
            }
            ExternalDisplayInputController.Mode.HYBRID -> {
                hintIcon.visibility = View.GONE
                keyboardToggleButton.visibility = View.VISIBLE
                keyboardView.visibility = View.GONE
                updateToggleButtonPosition()
            }
            else -> {
                hintIcon.visibility = View.GONE
                keyboardToggleButton.visibility = View.GONE
                keyboardView.visibility = View.GONE
                updateToggleButtonPosition()
            }
        }
    }

    private fun toggleKeyboard() {
        if (mode != ExternalDisplayInputController.Mode.HYBRID) return
        keyboardView.visibility = if (keyboardView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
