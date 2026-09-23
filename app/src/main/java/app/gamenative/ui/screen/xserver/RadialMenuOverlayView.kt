package app.gamenative.ui.screen.xserver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.gamenative.R
import com.winlator.inputcontrols.RadialMenu
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class RadialMenuOverlayView(context: Context) : View(context) {
    interface Listener {
        fun onPointerPosition(x: Float, y: Float)
        fun onPointerRelease(commit: Boolean)
    }

    private val density = resources.displayMetrics.density
    val radiusPx: Float = 132f * density
    val innerRadiusPx: Float = 38f * density
    private val textRadiusPx: Float = 86f * density
    private val textSizePx: Float = 12f * density
    private val arcBounds = RectF()
    private val innerArcBounds = RectF()
    private val slicePath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    var listener: Listener? = null
    private var menu: RadialMenu? = null
    private var centerX = 0f
    private var centerY = 0f
    private var selectedIndex = -1
    private var primaryColor = Color.argb(102, 255, 255, 255)
    private var secondaryColor = Color.argb(102, 2, 119, 189)
    private val centerLabel = context.getString(R.string.radial_menu_cancel)

    init {
        visibility = GONE
        isClickable = true
        isFocusable = false
        setWillNotDraw(false)
    }

    fun show(menu: RadialMenu, x: Float, y: Float) {
        this.menu = menu
        centerX = x
        centerY = y
        selectedIndex = -1
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    fun hide() {
        visibility = GONE
        menu = null
        selectedIndex = -1
        invalidate()
    }

    fun selectedIndex(): Int = selectedIndex

    fun setControlsStyle(primaryColor: Int, secondaryColor: Int) {
        if (this.primaryColor == primaryColor && this.secondaryColor == secondaryColor) return
        this.primaryColor = primaryColor
        this.secondaryColor = secondaryColor
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        if (selectedIndex == index) return
        selectedIndex = index
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> listener?.onPointerPosition(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                listener?.onPointerPosition(event.x, event.y)
                listener?.onPointerRelease(true)
            }
            MotionEvent.ACTION_CANCEL -> listener?.onPointerRelease(false)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val slots = menu?.enabledSlots.orEmpty()
        if (visibility != VISIBLE || slots.isEmpty()) return

        val count = slots.size
        val sweep = 360f / count
        val clampedCenterX = clampCenterAxis(centerX, width.toFloat())
        val clampedCenterY = clampCenterAxis(centerY, height.toFloat())
        arcBounds.set(
            clampedCenterX - radiusPx,
            clampedCenterY - radiusPx,
            clampedCenterX + radiusPx,
            clampedCenterY + radiusPx,
        )
        innerArcBounds.set(
            clampedCenterX - innerRadiusPx,
            clampedCenterY - innerRadiusPx,
            clampedCenterX + innerRadiusPx,
            clampedCenterY + innerRadiusPx,
        )

        for (index in slots.indices) {
            val active = index == selectedIndex
            val startAngle = -90f - (sweep / 2f) + index * sweep
            drawSlice(canvas, startAngle, sweep, active)
            drawSlotLabel(canvas, slots[index], index, sweep, clampedCenterX, clampedCenterY, active)
        }

        fillPaint.color = scaledAlphaColor(primaryColor, 0.18f)
        canvas.drawCircle(clampedCenterX, clampedCenterY, innerRadiusPx, fillPaint)
        strokePaint.color = scaledAlphaColor(primaryColor, 1.15f)
        strokePaint.strokeWidth = 1.4f * density
        canvas.drawCircle(clampedCenterX, clampedCenterY, innerRadiusPx, strokePaint)

        textPaint.color = readableTextColor(primaryColor)
        textPaint.textSize = min(textSizePx * 1.05f, innerRadiusPx * 0.42f)
        textPaint.isFakeBoldText = true
        canvas.drawText(centerLabel, clampedCenterX, centeredTextBaseline(clampedCenterY, textPaint), textPaint)
    }

    private fun drawSlice(canvas: Canvas, startAngle: Float, sweep: Float, active: Boolean) {
        if (sweep >= 359.9f) {
            drawFullRing(canvas, active)
            return
        }

        slicePath.rewind()
        slicePath.fillType = Path.FillType.WINDING
        slicePath.arcTo(arcBounds, startAngle, sweep)
        slicePath.arcTo(innerArcBounds, startAngle + sweep, -sweep)
        slicePath.close()

        drawSlicePath(canvas, active)
    }

    private fun drawFullRing(canvas: Canvas, active: Boolean) {
        slicePath.rewind()
        slicePath.fillType = Path.FillType.EVEN_ODD
        slicePath.addOval(arcBounds, Path.Direction.CW)
        slicePath.addOval(innerArcBounds, Path.Direction.CW)
        drawSlicePath(canvas, active)
    }

    private fun drawSlicePath(canvas: Canvas, active: Boolean) {
        fillPaint.color = if (active) {
            scaledAlphaColor(secondaryColor, 1.15f)
        } else {
            scaledAlphaColor(primaryColor, 0.22f)
        }
        canvas.drawPath(slicePath, fillPaint)

        strokePaint.color = if (active) {
            scaledAlphaColor(secondaryColor, 1.65f)
        } else {
            scaledAlphaColor(primaryColor, 1f)
        }
        strokePaint.strokeWidth = if (active) 2.4f * density else 1.2f * density
        canvas.drawPath(slicePath, strokePaint)
    }

    private fun drawSlotLabel(
        canvas: Canvas,
        slot: RadialMenu.Slot,
        index: Int,
        sweep: Float,
        centerX: Float,
        centerY: Float,
        active: Boolean,
    ) {
        val angleRad = Math.toRadians((-90.0 + index * sweep).toDouble())
        val textX = centerX + cos(angleRad).toFloat() * textRadiusPx
        val textY = centerY + sin(angleRad).toFloat() * textRadiusPx
        textPaint.color = readableTextColor(primaryColor)
        textPaint.textSize = textSizePx
        textPaint.isFakeBoldText = active
        val sliceWidth = 2f * textRadiusPx * sin(Math.toRadians((sweep * 0.5f).toDouble())).toFloat()
        val maxLabelWidth = min(radiusPx * 0.62f, (sliceWidth - 8f * density).coerceAtLeast(24f * density))
        val label = fitLabel(slot.displayLabel.take(14), maxLabelWidth)
        canvas.drawText(label, textX, centeredTextBaseline(textY, textPaint), textPaint)
    }

    private fun fitLabel(label: String, maxWidth: Float): String {
        var adjusted = label
        while (textPaint.measureText(adjusted) > maxWidth && adjusted.length > 3) {
            adjusted = adjusted.dropLast(1)
        }
        return if (adjusted.length < label.length && adjusted.length > 1) {
            adjusted.dropLast(1) + "."
        } else {
            adjusted
        }
    }

    private fun clampCenterAxis(value: Float, size: Float): Float {
        val margin = radiusPx + 8f * density
        return if (size <= margin * 2f) size * 0.5f else value.coerceIn(margin, size - margin)
    }

    private fun scaledAlphaColor(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun readableTextColor(color: Int): Int {
        val alpha = (Color.alpha(color) * 2.1f).toInt().coerceIn(160, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun centeredTextBaseline(centerY: Float, paint: Paint): Float {
        return centerY - (paint.descent() + paint.ascent()) * 0.5f
    }
}
