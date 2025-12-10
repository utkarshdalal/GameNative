package app.gamenative.theme.perf

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * PerfOverlay: dev-only FPS and frame-time overlay for quick diagnostics.
 *
 * Usage (debug builds only):
 *   if (BuildConfig.DEBUG) {
 *       PerfOverlay.Host(videoActive = isVideoPreviewPlaying)
 *   }
 *
 * The overlay is gated by [PerfConfig.enabled] and [PerfConfig.overlayEnabled]. Provide your own toggle in
 * a dev menu, or tap the overlay panel to toggle visibility at runtime.
 */
object PerfOverlay {

    /** Exposed helper to toggle overlay programmatically (debug only). */
    fun toggle() { if (BuildConfig.DEBUG) PerfConfig.overlayEnabled = !PerfConfig.overlayEnabled }
    fun enable() { if (BuildConfig.DEBUG) PerfConfig.overlayEnabled = true }
    fun disable() { if (BuildConfig.DEBUG) PerfConfig.overlayEnabled = false }

    private val sampler = FrameSampler(maxSamples = 300)

    @Composable
    fun Host(modifier: Modifier = Modifier, videoActive: Boolean = false) {
        if (!BuildConfig.DEBUG || !PerfConfig.enabled) return

        // Start/stop sampler with composition lifecycle
        LaunchedEffect(Unit) {
            sampler.start()
        }
        DisposableEffect(Unit) {
            onDispose { sampler.stop() }
        }

        val times by sampler.frameTimes.collectAsState()
        val stats = remember(times) { computeStats(times) }

        // Allow tapping the panel itself to toggle visibility
        var visible by remember { mutableStateOf(PerfConfig.overlayEnabled) }
        LaunchedEffect(PerfConfig.overlayEnabled) { visible = PerfConfig.overlayEnabled }

        if (!visible) return

        OverlayPanel(
            modifier = modifier,
            stats = stats,
            sampleCount = times.size,
            videoActive = videoActive,
            onToggle = { toggle() }
        )
    }

    // --- UI ---
    @Composable
    private fun OverlayPanel(
        modifier: Modifier,
        stats: Stats,
        sampleCount: Int,
        videoActive: Boolean,
        onToggle: () -> Unit,
    ) {
        val density = LocalDensity.current
        // Keep it small and unobtrusive, top-right corner
        Box(
            modifier = modifier,
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                modifier = Modifier
                    .padding(8.dp)
                    .alpha(0.92f)
                    .clickable { onToggle() },
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.small,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(text = "Perf (n=$sampleCount)", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text(text = "FPS: ${stats.fps}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(text = "ms avg/p50/p95/p99: ${fmt(stats.avgMs)}/${fmt(stats.p50)}/${fmt(stats.p95)}/${fmt(stats.p99)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    val videoTxt = if (videoActive) "video:on" else "video:off"
                    Text(text = videoTxt, color = if (videoActive) Color(0xFF80FF80) else Color(0xFFFFC080), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    private fun fmt(v: Float): String = "%.1f".format(v)

    data class Stats(
        val avgMs: Float,
        val p50: Float,
        val p95: Float,
        val p99: Float,
        val fps: Int,
    )

    private fun computeStats(samples: List<Float>): Stats {
        if (samples.isEmpty()) return Stats(0f,0f,0f,0f,0)
        val avg = samples.average().toFloat()
        val sorted = samples.sorted()
        fun pct(p: Int): Float {
            if (sorted.isEmpty()) return 0f
            val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
        val fps = if (avg <= 0f) 0 else (1000f / avg).roundToInt()
        return Stats(avg, pct(50), pct(95), pct(99), fps)
    }

    // --- Frame sampling ---
    private class FrameSampler(private val maxSamples: Int) : Choreographer.FrameCallback {
        private val choreographer: Choreographer = Choreographer.getInstance()
        private var running: Boolean = false
        private var lastNanos: Long = 0L
        private val _frameTimes = MutableStateFlow<List<Float>>(emptyList())
        val frameTimes: StateFlow<List<Float>> = _frameTimes.asStateFlow()

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastNanos) / 1_000_000f
                // Update buffer
                _frameTimes.update { prev ->
                    val arr = if (prev.isEmpty()) ArrayList<Float>(maxSamples) else ArrayList(prev)
                    arr.add(deltaMs)
                    if (arr.size > maxSamples) arr.removeAt(0)
                    arr
                }
            }
            lastNanos = frameTimeNanos
            choreographer.postFrameCallback(this)
        }

        fun start() {
            if (running) return
            running = true
            lastNanos = 0L
            choreographer.postFrameCallback(this)
        }

        fun stop() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(this)
        }
    }
}
