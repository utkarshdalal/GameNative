package app.gamenative.steamcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Renders the split-trackpad keyboard ([ScKeyboard]) — left grid + right grid with both cursors + shift state.
 * Transparent, ignores touch (input passes to the game), thread-safe via a `@Volatile` spec + [postInvalidate].
 * The layout is static ([ScKeyboardLayout]); the spec carries the live cursors/shift.
 *
 * UNTESTED ON DEVICE (built 2026-06-20) — see docs/RISKS.md §A (overlay z-order) + docs/SC-KEYBOARD.md.
 */
class ScKeyboardOverlayView(context: Context) : View(context), ScKeyboardOverlay {

    @Volatile private var spec: ScKeyboardSpec? = null
    // Placement + size (from ScOverlayStore keyboard namespace); default = full-size, centered low.
    @Volatile private var layout = ScOverlayStore.KEYBOARD_DEFAULT

    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 40, 44, 52); style = Paint.Style.FILL }
    private val cellHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 33, 150, 243); style = Paint.Style.FILL }
    private val cellShiftOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 76, 175, 80); style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 30f }
    // Finger-position cursor (a bright dot per half) so you can see exactly where your thumb points, not just
    // which cell is highlighted.
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 193, 7); style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 2f }

    init { isClickable = false; isFocusable = false }
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    /** Apply a placement/size layout (from [ScOverlayStore.forKeyboard]); takes effect on the next draw. */
    fun setLayout(l: ScOverlayLayout) { layout = l.clamped(); postInvalidate() }

    override fun show(spec: ScKeyboardSpec) { this.spec = spec; postInvalidate() }
    override fun hide() { this.spec = null; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val l = layout
        // Scaled, repositionable keyboard bounds (base = 92% width × 41% height, original split + gap preserved).
        val kbW = width * 0.92f * l.scale
        val kbH = height * 0.41f * l.scale
        val leftEdge = width * l.cx - kbW / 2f
        val rightEdge = width * l.cx + kbW / 2f
        val top = height * l.cy - kbH / 2f
        val bottom = height * l.cy + kbH / 2f
        val halfW = kbW * 0.4783f // each half is 0.44/0.92 of the total; leaves the centered gap
        val pad = 10f
        // Local backdrop behind the keyboard (no full-screen dim).
        canvas.drawRoundRect(RectF(leftEdge - pad, top - pad, rightEdge + pad, bottom + pad), 18f, 18f, backdrop)
        text.textSize = 30f * l.scale
        drawHalf(canvas, ScKeyboardLayout.leftFor(s.symbols), leftEdge, leftEdge + halfW, top, bottom, s.leftCursor, s.shift, s.leftX, s.leftY)
        drawHalf(canvas, ScKeyboardLayout.rightFor(s.symbols), rightEdge - halfW, rightEdge, top, bottom, s.rightCursor, s.shift, s.rightX, s.rightY)
    }

    private fun drawHalf(
        canvas: Canvas, grid: List<KbKey>, left: Float, right: Float, top: Float, bottom: Float,
        cursor: Int, shift: Boolean, posX: Float, posY: Float,
    ) {
        val cols = ScKeyboardLayout.COLS
        val rows = ScKeyboardLayout.ROWS
        val cw = (right - left) / cols
        val ch = (bottom - top) / rows
        val pad = 4f
        for (i in grid.indices) {
            val key = grid[i]
            if (key is KbKey.Empty) continue
            val col = i % cols
            val row = i / cols // row 0 = top
            val r = RectF(left + col * cw + pad, top + row * ch + pad, left + (col + 1) * cw - pad, top + (row + 1) * ch - pad)
            val paint = when {
                i == cursor -> cellHi
                key is KbKey.Shift && shift -> cellShiftOn
                else -> cell
            }
            canvas.drawRoundRect(r, 8f, 8f, paint)
            canvas.drawRoundRect(r, 8f, 8f, stroke)
            val label = if (shift && key is KbKey.Chr && key.label.length == 1) key.label.uppercase() else key.label
            if (label.isNotBlank()) canvas.drawText(label, r.centerX(), r.centerY() + text.textSize / 3f, text)
        }
        // Finger-position cursor dot (only while this pad is touched).
        if (posX in 0f..1f && posY in 0f..1f) {
            val px = left + posX * (right - left)
            val py = top + posY * (bottom - top)
            canvas.drawCircle(px, py, 11f, cursorFill)
            canvas.drawCircle(px, py, 11f, cursorStroke)
        }
    }
}
