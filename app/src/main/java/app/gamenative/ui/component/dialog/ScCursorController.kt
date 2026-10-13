package app.gamenative.ui.component.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Draws a pad-mouse cursor over the currently-open Steam-Controller dialog and injects taps at the cursor location,
 * so the right trackpad can drive arbitrary Compose UI (lists, chips, dropdowns, text fields) that d-pad focus
 * traversal can't reliably reach. The BLE Triton isn't an Android input device, so the [ProfileInterpreter] emits
 * pixel deltas / clicks through [app.gamenative.steamcontroller.ScUiBridge] and the bridge calls into this.
 *
 * The cursor is a small View added to the **top dialog window's decor** (so it floats above the Compose content);
 * coordinates are tracked in that decor's space and taps are dispatched to the decor, which hit-tests down to the
 * Compose view beneath the (non-clickable) cursor. When the top dialog changes the cursor re-attaches and recenters.
 */
class ScCursorController(private val context: Context) {
    private var cursorView: View? = null
    private var host: ViewGroup? = null
    private var x = 0f
    private var y = 0f

    /** Move the cursor by a pixel delta over [topView]'s window, attaching/recentering if the top window changed. */
    fun move(topView: View?, dx: Int, dy: Int) {
        val decor = topView?.rootView as? ViewGroup ?: run { detach(); return }
        if (host !== decor || cursorView == null) attach(decor)
        val cv = cursorView ?: return
        val w = decor.width.coerceAtLeast(1)
        val h = decor.height.coerceAtLeast(1)
        x = (x + dx).coerceIn(0f, (w - 1).toFloat())
        y = (y + dy).coerceIn(0f, (h - 1).toFloat())
        // The arrow's tip is at the view's top-left, so align the tip (the click hotspot) to the tracked position.
        cv.translationX = x
        cv.translationY = y
        cv.visibility = View.VISIBLE
        cv.bringToFront()
    }

    /** Inject a tap (down+up) at the current cursor position into the top dialog. */
    fun tap(topView: View?) {
        val decor = topView?.rootView as? ViewGroup ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 12, MotionEvent.ACTION_UP, x, y, 0)
        decor.dispatchTouchEvent(down)
        decor.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun attach(decor: ViewGroup) {
        detach()
        val arrow = PointerCursorView(context)
        decor.addView(arrow, FrameLayout.LayoutParams(arrow.viewW, arrow.viewH))
        cursorView = arrow
        host = decor
        // Start centered in the window.
        x = decor.width / 2f
        y = decor.height / 2f
    }

    /**
     * A classic arrow-pointer glyph (white fill, dark outline) drawn with a [Path] so it's crisp at any density and
     * needs no drawable resource. The tip sits at the view's top-left (0,0) — that's the click hotspot the parent
     * aligns to the tracked position. Replaces the old white-dot cursor.
     */
    private class PointerCursorView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val s = d * 1.5f  // glyph scale (arrow "units" → px)
        // Arrow outline in units (tip at 0,0), classic pointer shape.
        private val pts = floatArrayOf(0f, 0f, 0f, 12f, 2.8f, 9.4f, 4.7f, 13.9f, 6.3f, 13.1f, 4.4f, 8.7f, 7.5f, 8.7f)
        val viewW = Math.ceil((7.5f * s + 2 * d).toDouble()).toInt()
        val viewH = Math.ceil((13.9f * s + 2 * d).toDouble()).toInt()

        private val path = Path().apply {
            moveTo(pts[0] * s, pts[1] * s)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
            close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(235, 20, 20, 20)
            strokeJoin = Paint.Join.ROUND
        }

        init { isClickable = false; isFocusable = false }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }

    /** Remove the cursor (top window closed / capture ended). Safe to call repeatedly. */
    fun detach() {
        val cv = cursorView
        val h = host
        if (cv != null && h != null) h.removeView(cv)
        cursorView = null
        host = null
    }
}
