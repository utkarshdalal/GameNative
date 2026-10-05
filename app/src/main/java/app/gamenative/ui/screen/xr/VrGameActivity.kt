package app.gamenative.ui.screen.xr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.view.WindowManager
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.service.SteamService
import androidx.core.graphics.PathParser
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import timber.log.Timber

/** Owns only the OpenXR session; the game keeps running in MainActivity. */
class VrGameActivity : Activity() {
    private var xrHandle = 0L
    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null
    private val statusBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VrLaunchCoordinator.runtime == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}
        }
        VrLaunchCoordinator.onVrActivityCreated(this)
        Timber.i("VR launch: headset activity created")
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isImmersiveActivityResumed = true
        PluviaApp.isActivityInForeground = true
        if (SteamService.keepAlive && PluviaApp.hasValidSuspendPolicyState() && PluviaApp.xEnvironment != null) {
            when {
                PluviaApp.isNeverSuspendMode() -> Unit
                PluviaApp.isOverlayPaused && PluviaApp.isManualSuspendMode() -> Unit
                else -> PluviaApp.xEnvironment?.onResume()
            }
        }
        startXrSession()
    }

    override fun onPause() {
        PluviaApp.isImmersiveActivityResumed = false
        // Not finishing means the headset is going to sleep: suspend like the immersive activity.
        if (!isFinishing && SteamService.keepAlive && PluviaApp.hasValidSuspendPolicyState() &&
            PluviaApp.xEnvironment != null && !PluviaApp.isNeverSuspendMode()
        ) {
            PluviaApp.isActivityInForeground = false
            PluviaApp.xEnvironment?.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopXrSession()
        if (VrLaunchCoordinator.activity === this) VrLaunchCoordinator.onVrActivityDestroyed()
        Timber.i("VR launch: headset activity destroyed")
        super.onDestroy()
    }

    @Deprecated("Back must not leave VR; the only way out is quitting the game.")
    override fun onBackPressed() = Unit

    private fun startXrSession() {
        if (xrHandle != 0L) return
        xrHandle = try {
            XrNative.nativeCreate(this, statusBitmap.width, statusBitmap.height, VrLaunchCoordinator.refreshRateHz.toFloat())
        } catch (t: Throwable) {
            Timber.e(t, "VR launch: native OpenXR module unavailable")
            finish()
            return
        }
        VrLaunchCoordinator.runtime?.attachSession(xrHandle)
        polling.set(true)
        pollThread = thread(name = "VrGamePoll") { pollLoop(xrHandle) }
    }

    private fun stopXrSession() {
        polling.set(false)
        pollThread?.interrupt()
        pollThread?.join(500)
        pollThread = null
        if (xrHandle != 0L) {
            VrLaunchCoordinator.runtime?.detachSession()
            XrNative.nativeRequestStop(xrHandle)
            XrNative.nativeJoinAndDestroy(xrHandle)
            xrHandle = 0L
        }
    }

    private fun pollLoop(handle: Long) {
        val startedMs = System.currentTimeMillis()
        var lastPanelMs = 0L
        var passthrough = false
        while (polling.get()) {
            val stereo = XrNative.nativeIsWindowsStereoActive(handle)
            if (passthrough == stereo) {
                passthrough = !stereo
                XrNative.nativeSetPassthroughEnabled(handle, passthrough)
            }
            val now = System.currentTimeMillis()
            if (!stereo && now - lastPanelMs >= PANEL_FRAME_MS) {
                lastPanelMs = now
                drawWaitingCard(now - startedMs)
                XrNative.nativeSubmitFrame(handle, statusBitmap)
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 9, 9, 11) }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 139, 92, 246)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 250, 250)
        textSize = 50f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 212, 216)
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 139, 92, 246) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val headsetIcon: Path by lazy {
        // Material Symbols head_mounted_device; its viewBox starts at y = -960.
        PathParser.createPathFromPathData(HEADSET_PATH).apply {
            transform(Matrix().apply { setTranslate(0f, 960f); postScale(0.125f, 0.125f) })
        }
    }

    private fun drawWaitingCard(elapsedMs: Long) {
        val canvas = Canvas(statusBitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val width = statusBitmap.width.toFloat()
        val card = RectF(170f, 170f, width - 170f, 530f)
        canvas.drawRoundRect(card, 40f, 40f, cardPaint)
        canvas.drawRoundRect(card, 40f, 40f, cardBorderPaint)
        val centerX = width / 2f

        canvas.save()
        canvas.translate(centerX - 60f, card.top + 30f)
        canvas.drawPath(headsetIcon, iconPaint)
        canvas.restore()

        val title = VrLaunchCoordinator.gameTitle.ifBlank { getString(R.string.vr_game_starting) }
        canvas.drawText(ellipsize(title, titlePaint, card.width() - 80f), centerX, card.top + 200f, titlePaint)
        val dots = ".".repeat(((elapsedMs / 450) % 4).toInt())
        canvas.drawText(getString(R.string.vr_game_waiting).trimEnd('…', '.') + dots, centerX, card.top + 258f, bodyPaint)

        val track = RectF(card.left + 150f, card.top + 300f, card.right - 150f, card.top + 308f)
        canvas.drawRoundRect(track, 4f, 4f, trackPaint)
        val sweep = track.width() * 0.3f
        val phase = (elapsedMs % 1600L) / 1600f
        val start = track.left - sweep + (track.width() + sweep) * phase
        val bar = RectF(start.coerceAtLeast(track.left), track.top, (start + sweep).coerceAtMost(track.right), track.bottom)
        if (bar.width() > 0f) canvas.drawRoundRect(bar, 4f, 4f, barPaint)

    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 16L
        const val PANEL_FRAME_MS = 66L
        val ACCENT = Color.rgb(139, 92, 246)
        const val HEADSET_PATH = "M300-240q-66 0-113-47t-47-113v-163q0-51 32-89.5t82-47.5q57-11 113-15.5t113-4.5q57 0 113.5 4.5T706-700q50 10 82 48t32 89v163q0 66-47 113t-113 47h-40q-13 0-26-1.5t-25-6.5l-64-22q-12-5-25-5t-25 5l-64 22q-12 5-25 6.5t-26 1.5h-40Zm0-80h40q7 0 13.5-1t12.5-3q29-9 56.5-19t57.5-10q30 0 58 9.5t56 19.5q6 2 12.5 3t13.5 1h40q33 0 56.5-23.5T740-400v-163q0-22-14-38t-35-21q-52-11-104.5-14.5T480-640q-54 0-106 4t-105 14q-21 4-35 20.5T220-563v163q0 33 23.5 56.5T300-320ZM70-400q-13 0-21.5-8.5T40-430v-100q0-13 8.5-21.5T70-560q13 0 21.5 8.5T100-530v100q0 13-8.5 21.5T70-400Zm820 0q-13 0-21.5-8.5T860-430v-100q0-13 8.5-21.5T890-560q13 0 21.5 8.5T920-530v100q0 13-8.5 21.5T890-400Zm-590 80q-33 0-56.5-23.5T220-400v-163q0-22 14-38.5t35-20.5q53-10 105-14t106-4q54 0 106.5 3.5T691-622q21 5 35 21t14 38v163q0 33-23.5 56.5T660-320h-40q-7 0-13.5-1t-12.5-3q-28-10-56-19.5t-58-9.5q-30 0-57.5 10T366-324q-6 2-12.5 3t-13.5 1h-40Z"
    }
}
