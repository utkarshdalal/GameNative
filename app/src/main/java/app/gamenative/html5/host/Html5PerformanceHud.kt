package app.gamenative.html5.host

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.PrefManager
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.widget.FrameTimeRingBuffer
import app.gamenative.ui.widget.PerformanceHudView
import kotlin.math.roundToInt

// performance HUD frame source. Window.OnFrameMetricsAvailableListener fires once per
// Activity-Window frame and yields per-frame TOTAL_DURATION ns -- exactly what we need for
// both FPS (count frames per 500ms window) and frametime / percentile lows (push deltas
// into the ring buffer). only attached while HUD is enabled. fallback path: if the host
// is not a ComponentActivity (shouldn't happen, but defensive), use ViewTreeObserver
// pre-draw counter for FPS and skip frametime.
@Composable
internal fun rememberHtml5FpsCounter(
    enabled: Boolean,
    context: android.content.Context,
    webView: WebView,
    webViewFps: MutableFloatState,
    frameTimeBuffer: FrameTimeRingBuffer,
) {
    DisposableEffect(enabled) {
        if (!enabled) {
            webViewFps.floatValue = 0f
            frameTimeBuffer.reset()
            return@DisposableEffect onDispose {}
        }
        val activity = context as? ComponentActivity
        if (activity != null) {
            val handler = Handler(Looper.getMainLooper())
            var frames = 0
            var windowStart = SystemClock.elapsedRealtime()
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames++
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStart
                if (elapsed >= 500L) {
                    webViewFps.floatValue = frames * 1000f / elapsed
                    frames = 0
                    windowStart = now
                }
                val totalNs = runCatching { metrics.getMetric(FrameMetrics.TOTAL_DURATION) }.getOrDefault(0L)
                if (totalNs > 0L) frameTimeBuffer.add(totalNs)
            }
            runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, handler) }
            onDispose {
                runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
                frameTimeBuffer.reset()
            }
        } else {
            var frames = 0
            var windowStart = SystemClock.elapsedRealtime()
            val listener = ViewTreeObserver.OnPreDrawListener {
                frames++
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStart
                if (elapsed >= 500L) {
                    webViewFps.floatValue = frames * 1000f / elapsed
                    frames = 0
                    windowStart = now
                }
                true
            }
            runCatching { webView.viewTreeObserver.addOnPreDrawListener(listener) }
            onDispose {
                runCatching { webView.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
        }
    }
}

// performance HUD overlay. mounted last so it sits above WebView + ICV. drag updates
// fraction state (internal) and persists on drag-end. invalid sentinel (-1) maps to top-left
// until the user moves it for the first time. host{Width,Height} are the Box's measured size.
@Composable
internal fun BoxScope.Html5PerformanceHudOverlay(
    config: PerformanceHudConfig,
    fpsProvider: () -> Float,
    frameTimeBuffer: FrameTimeRingBuffer,
    hostWidth: Int,
    hostHeight: Int,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    var offsetXFraction by remember { mutableFloatStateOf(PrefManager.performanceHudXFraction) }
    var offsetYFraction by remember { mutableFloatStateOf(PrefManager.performanceHudYFraction) }
    AndroidView(
        factory = { ctx ->
            PerformanceHudView(
                context = ctx,
                fpsProvider = fpsProvider,
                frameTimeBuffer = frameTimeBuffer,
                initialConfig = config,
                initialCompactMode = PrefManager.performanceHudCompactMode,
            )
        },
        update = { hud -> hud.setConfig(config) },
        modifier = Modifier
            .align(Alignment.TopStart)
            .onSizeChanged {
                widthPx = it.width
                heightPx = it.height
            }
            .offset {
                val maxX = (hostWidth - widthPx).coerceAtLeast(0)
                val maxY = (hostHeight - heightPx).coerceAtLeast(0)
                val fx = if (offsetXFraction in 0f..1f) offsetXFraction else 0f
                val fy = if (offsetYFraction in 0f..1f) offsetYFraction else 0f
                IntOffset((maxX * fx).roundToInt(), (maxY * fy).roundToInt())
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        PrefManager.performanceHudXFraction = offsetXFraction
                        PrefManager.performanceHudYFraction = offsetYFraction
                    },
                ) { _, drag ->
                    val maxX = (hostWidth - widthPx).coerceAtLeast(0).toFloat()
                    val maxY = (hostHeight - heightPx).coerceAtLeast(0).toFloat()
                    val curX = if (offsetXFraction in 0f..1f) offsetXFraction else 0f
                    val curY = if (offsetYFraction in 0f..1f) offsetYFraction else 0f
                    offsetXFraction = if (maxX > 0f) {
                        (curX + drag.x / maxX).coerceIn(0f, 1f)
                    } else {
                        curX
                    }
                    offsetYFraction = if (maxY > 0f) {
                        (curY + drag.y / maxY).coerceIn(0f, 1f)
                    } else {
                        curY
                    }
                }
            },
    )
}
