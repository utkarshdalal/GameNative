package app.gamenative.steamcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Step-6 menu HUD renderer: a transparent, non-interactive overlay View that draws a Radial ring or a Touch grid
 * with per-slot labels and highlights the selected slot. Driven by [ProfileInterpreter] via the [ScMenuOverlay]
 * interface; updates arrive on the controller thread, so state is held in a `@Volatile` field and the View is
 * refreshed with [postInvalidate] (thread-safe). The View ignores touches so it never steals game input.
 *
 * UNTESTED ON DEVICE (built 2026-06-20) — see docs/RISKS.md §overlay and docs/TESTING-GUIDE.md.
 */
class ScMenuOverlayView(context: Context) : View(context), ScMenuOverlay {

    @Volatile private var spec: ScMenuSpec? = null
    // Placement + size (scale, center fraction); set from ScOverlayStore. Default is intentionally smaller
    // than 1.0 (user feedback: the HUD was a bit large) and centered. Used directly by the editor preview (which
    // pushes an explicit layout via setLayout); the live game instead resolves per-menu (see [gameKey]).
    @Volatile private var layout = ScOverlayLayout()
    // Live game: resolve each menu's placement per its [ScMenuSpec.menuId] via ScOverlayStore.forMenu, keyed by
    // this game. Null = editor/preview mode, which uses the pushed [layout] instead. Cache avoids a prefs read
    // per frame; refreshLayouts() clears it after an in-game overlay edit so the change applies live.
    @Volatile var gameKey: String? = null
    private val layoutCache = java.util.concurrent.ConcurrentHashMap<String, ScOverlayLayout>()
    fun refreshLayouts() { layoutCache.clear(); postInvalidate() }
    // Fade behaviour: the HUD is full-opacity while the control is being actively used (showMenu called each
    // report), and fades out over FADE_MS once interaction stops (hideMenu) — per the SC overlay UX.
    @Volatile private var fadingOut = false
    private var fadeStartMs = 0L
    private val fadeMs = 180L

    // Local backdrop (a soft halo just behind the ring/grid) — replaces the old full-screen dim so a held
    // movement radial no longer tints the whole game.
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 40, 44, 52); style = Paint.Style.FILL }
    private val slotFillHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 150, 243); style = Paint.Style.FILL }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 44f }
    // Thumb-position cursor dot (amber, matching the keyboard) so you can see exactly where the stick points.
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 193, 7); style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 2f }

    // Transient status toast (e.g. action-set name on a switch); independent of the menu, auto-fades.
    @Volatile private var toastText: String? = null
    private var toastStartMs = 0L
    private val toastMs = 1300L
    private val toastBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0); style = Paint.Style.FILL }
    private val toastTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 42f }

    init {
        isClickable = false
        isFocusable = false
    }

    // Never consume touch events — input must pass through to the game.
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    /** Apply a placement/size layout (from [ScOverlayStore]); takes effect on the next draw. */
    fun setLayout(l: ScOverlayLayout) { layout = l.clamped(); postInvalidate() }

    override fun showMenu(spec: ScMenuSpec) {
        this.spec = spec
        fadingOut = false // active interaction -> stay at full opacity
        postInvalidate()
    }

    override fun hideMenu() {
        // Begin a fade-out rather than vanishing instantly; keep the spec until the fade completes.
        if (spec != null && !fadingOut) { fadingOut = true; fadeStartMs = System.currentTimeMillis() }
        postInvalidate()
    }

    override fun toast(text: String) {
        toastText = text
        toastStartMs = System.currentTimeMillis()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawToast(canvas) // independent of the menu — may show with nothing else on screen
        val s = spec ?: return
        if (s.labels.isEmpty()) return
        val alpha: Float = if (fadingOut) {
            val t = (System.currentTimeMillis() - fadeStartMs).toFloat() / fadeMs
            if (t >= 1f) { spec = null; fadingOut = false; return } // fully faded -> stop drawing
            (1f - t).coerceIn(0f, 1f)
        } else 1f

        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        // Live game: per-menu placement (cached); editor/preview: the pushed layout.
        val l = gameKey?.let { gk -> layoutCache.getOrPut(s.menuId) { ScOverlayStore.forMenu(context, gk, s.menuId) } } ?: layout
        when (s.kind) {
            ScMenuSpec.Kind.RADIAL -> drawRadial(canvas, s, l)
            ScMenuSpec.Kind.GRID -> drawGrid(canvas, s, l)
        }
        canvas.restoreToCount(layer)
        if (fadingOut) postInvalidateOnAnimation() // keep animating the fade
    }

    private fun drawToast(canvas: Canvas) {
        val txt = toastText ?: return
        val t = (System.currentTimeMillis() - toastStartMs).toFloat() / toastMs
        if (t >= 1f) { toastText = null; return }
        val alpha = (1f - t).coerceIn(0f, 1f)
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        val cx = width / 2f
        val cy = height * 0.16f
        val tw = toastTextPaint.measureText(txt)
        val padX = 28f
        val r = RectF(cx - tw / 2 - padX, cy - 44f, cx + tw / 2 + padX, cy + 20f)
        canvas.drawRoundRect(r, 18f, 18f, toastBg)
        canvas.drawText(txt, cx, cy, toastTextPaint)
        canvas.restoreToCount(layer)
        postInvalidateOnAnimation()
    }

    private fun drawRadial(canvas: Canvas, s: ScMenuSpec, l: ScOverlayLayout) {
        val base = min(width, height)
        val cx = width * l.cx
        val cy = height * l.cy
        val n = s.labels.size.coerceAtLeast(1)
        val ringR = base * 0.27f * l.scale
        // Slot radius: as big as fits without adjacent slots overlapping (tangent spacing = 2·ringR·sin(π/n)),
        // capped at a comfortable max — so a dense ring shrinks its slots instead of colliding. The tangent limit
        // only applies with ≥2 slots (sin(π/1)=0 would otherwise collapse a single slot to the floor).
        val maxSlotR = base * 0.095f * l.scale
        val tangentLimit = if (n >= 2) (ringR * sin(Math.PI / n) * 0.9).toFloat() else Float.MAX_VALUE
        val slotR = minOf(maxSlotR, tangentLimit).coerceAtLeast(base * 0.03f * l.scale)
        text.textSize = (slotR * 0.7f).coerceAtMost(44f * l.scale)
        // Center hub fills the middle void; the more ring buttons, the bigger the hub (minimize wasted space).
        // [voidR] is the empty middle radius (to the ring slots' inner edges); never let the hub overlap them.
        val voidR = ringR - slotR
        val fillFrac = ((n - 2) / 7f).coerceIn(0f, 1f) * 0.6f + 0.28f // n≤2 → small hub, n≥9 → fills most of the void
        val centerR = (voidR * fillFrac).coerceIn(slotR * 0.6f, voidR - slotR * 0.3f)
        // Local halo behind the ring (replaces the old full-screen dim).
        canvas.drawCircle(cx, cy, ringR + slotR * 1.4f, backdrop)
        for (i in 0 until n) {
            // slot 0 at top (12 o'clock), clockwise — matches the interpreter's angle mapping.
            val ang = Math.toRadians((360.0 / n) * i - 90.0)
            val x = cx + (ringR * cos(ang)).toFloat()
            val y = cy + (ringR * sin(ang)).toFloat()
            val hi = i == s.highlighted
            canvas.drawCircle(x, y, slotR, if (hi) slotFillHi else slotFill)
            canvas.drawCircle(x, y, slotR, slotStroke)
            drawLabel(canvas, s.labels[i], x, y)
        }
        // Cursor magnitude (0 at center .. 1 at the rim); used to highlight the center hub when the thumb rests there.
        val hasCursor = !s.cursorX.isNaN() && !s.cursorY.isNaN()
        val mag = if (hasCursor) min(1f, hypot(s.cursorX, s.cursorY)) else 1f
        // Center button (Steam radial_menu button_0, e.g. "Wait") — the neutral hub. Highlighted while the thumb
        // rests over it (within the center dead-zone), so you can see it's the active selection before clicking.
        s.centerLabel?.let {
            val overCenter = hasCursor && mag < 0.30f
            canvas.drawCircle(cx, cy, centerR, if (overCenter) slotFillHi else slotFill)
            canvas.drawCircle(cx, cy, centerR, slotStroke)
            drawLabel(canvas, it, cx, cy)
        }
        // Thumb cursor dot: sits at the stick deflection (center when idle, out toward a slot when pushed).
        if (hasCursor) {
            val px = cx + s.cursorX * ringR
            val py = cy - s.cursorY * ringR // screen Y is inverted (cursorY up+)
            canvas.drawCircle(px, py, slotR * 0.42f, cursorFill)
            canvas.drawCircle(px, py, slotR * 0.42f, cursorStroke)
        }
    }

    private fun drawGrid(canvas: Canvas, s: ScMenuSpec, l: ScOverlayLayout) {
        val cols = s.cols.coerceAtLeast(1)
        val rows = s.rows.coerceAtLeast(1)
        val gridW = min(width, height) * 0.7f * l.scale
        val gridH = gridW * rows / cols
        val left = width * l.cx - gridW / 2f
        val top = height * l.cy - gridH / 2f
        val cellW = gridW / cols
        val cellH = gridH / rows
        val pad = 6f * l.scale
        // Scale the label to the cell instead of a fixed 44px, so multi-column grids don't collide/overflow the slots.
        val inner = (min(cellW, cellH) - 2f * pad).coerceAtLeast(1f)
        text.textSize = (inner * 0.34f).coerceAtMost(44f * l.scale).coerceAtLeast(12f)
        // Local halo behind the grid.
        canvas.drawRoundRect(RectF(left - pad, top - pad, left + gridW + pad, top + gridH + pad), 16f, 16f, backdrop)
        for (i in s.labels.indices) {
            val col = i % cols
            val row = i / cols
            val r = RectF(left + col * cellW + pad, top + row * cellH + pad, left + (col + 1) * cellW - pad, top + (row + 1) * cellH - pad)
            val hi = i == s.highlighted
            canvas.drawRoundRect(r, 12f, 12f, if (hi) slotFillHi else slotFill)
            canvas.drawRoundRect(r, 12f, 12f, slotStroke)
            drawLabel(canvas, s.labels[i], r.centerX(), r.centerY())
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        if (label.isBlank()) return
        // truncate long labels to keep them inside the slot
        val shown = if (label.length > 10) label.take(9) + "…" else label
        canvas.drawText(shown, x, y + text.textSize / 3f, text)
    }
}
