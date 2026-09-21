package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/** Bottom-screen hub: a menu page plus a page for each input mode. */
internal class HubLayout(
    context: Context,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) : FrameLayout(context) {

    // Edit this list to change the hotkey buttons (label to key).
    private val hotkeys = listOf(
        "Esc" to XKeycode.KEY_ESC,
        "Tab" to XKeycode.KEY_TAB,
        "E" to XKeycode.KEY_E,
        "F" to XKeycode.KEY_F,
        "Space" to XKeycode.KEY_SPACE,
        "Enter" to XKeycode.KEY_ENTER,
    )

    // Menu entries that just show a placeholder page until their real content is built.
    private val placeholderPages = listOf(
        "Map" to "Map — coming soon",
        "Terminal" to "Terminal — coming soon",
        "Inventory" to "Inventory — coming soon",
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(Color.parseColor("#101418"))
        showMenu()
    }

    private fun showMenu() {
        removeAllViews()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        column.addView(TextView(context).apply {
            text = "Voices of the Void"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        column.addView(menuButton("Mouse + Keyboard") {
            showPage(HybridInputLayout(context, xServer, touchpadViewProvider))
        })
        column.addView(menuButton("Hotkeys") { showPage(buildHotkeyPage()) })
        placeholderPages.forEach { (label, message) ->
            column.addView(menuButton(label) { showPage(placeholderPage(message)) })
        }
        addView(
            column,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun menuButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 20f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
            setMargins(0, dp(12), 0, 0)
        }
    }

    private fun showPage(page: View) {
        removeAllViews()
        addView(
            page,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val back = Button(context).apply {
            text = "Menu"
            setOnClickListener { showMenu() }
        }
        addView(
            back,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(8), dp(8), 0, 0)
            },
        )
    }

    private fun placeholderPage(message: String): View = FrameLayout(context).apply {
        addView(
            TextView(context).apply {
                text = message
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    private fun buildHotkeyPage(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(56), dp(16), dp(16))
        }
        hotkeys.chunked(3).forEach { rowKeys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { (label, key) ->
                row.addView(
                    hotkeyButton(label, key),
                    LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    },
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return column
    }

    // Press while touched, release on lift, so holding a button holds the key.
    private fun hotkeyButton(label: String, key: XKeycode) = Button(context).apply {
        text = label
        textSize = 20f
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    xServer.injectKeyPress(key)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    xServer.injectKeyRelease(key)
                }
            }
            true
        }
    }
}
