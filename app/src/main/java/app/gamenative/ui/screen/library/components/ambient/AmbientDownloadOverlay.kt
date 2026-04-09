package app.gamenative.ui.screen.library.components.ambient

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.BAR_HEIGHT_DP
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.DRIFT_AMPLITUDE_PX
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.DRIFT_PERIOD_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.ENTER_DURATION_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.EXIT_DURATION_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.IDLE_TIMEOUT_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.BAR_BASE_ALPHA
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.BAR_TRACK_ALPHA
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.SHIMMER_PERIOD_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.SHIMMER_WIDTH_FRACTION
import app.gamenative.ui.theme.BrandGradient
import app.gamenative.utils.ShakeDetector
import kotlinx.coroutines.delay

/**
 * Only compose while a download is active. Brightness is restored on disposal.
 */
@Composable
internal fun AmbientDownloadOverlay(
    gameName: String,
    downloadProgress: Float,
    iconUrl: String? = null,
    originBounds: Rect? = null,
    userInteractionCounter: Int = 0,
) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val context = LocalContext.current

    var interactionCounter by remember { mutableIntStateOf(0) }
    var isIdle by remember { mutableStateOf(false) }
    var isDvdMode by remember { mutableStateOf(false) }
    val registerInteraction = {
        interactionCounter++
        if (isIdle) isIdle = false
        isDvdMode = false
    }

    LaunchedEffect(interactionCounter) {
        delay(IDLE_TIMEOUT_MS)
        isIdle = true
    }

    LaunchedEffect(userInteractionCounter) {
        if (userInteractionCounter > 0) {
            registerInteraction()
        }
    }

    DisposableEffect(Unit) {
        val keyListener: (AndroidEvent.KeyEvent) -> Boolean = {
            if (it.event.action == AndroidKeyEvent.ACTION_DOWN) {
                registerInteraction()
            }
            false
        }
        val motionListener: (AndroidEvent.MotionEvent) -> Boolean = { motionEvent ->
            val event = motionEvent.event
            if (event != null) {
                registerInteraction()
            }
            false
        }

        PluviaApp.events.on(keyListener)
        PluviaApp.events.on(motionListener)

        onDispose {
            PluviaApp.events.off(keyListener)
            PluviaApp.events.off(motionListener)
        }
    }

    // Shake detector — only active during ambient idle
    DisposableEffect(isIdle) {
        if (!isIdle) return@DisposableEffect onDispose {}

        val shakeDetector = ShakeDetector(context) {
            isDvdMode = !isDvdMode
        }
        shakeDetector.start()

        onDispose { shakeDetector.stop() }
    }

    val targetAlpha = if (isIdle) 1f else 0f
    val ambientAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = if (targetAlpha == 1f) ENTER_DURATION_MS else EXIT_DURATION_MS,
            easing = EaseInOutCubic,
        ),
        label = "ambientAlpha",
    )

    if (ambientAlpha > 0f) {
        val infiniteTransition = rememberInfiniteTransition(label = "ambient")

        val driftOffset by infiniteTransition.animateFloat(
            initialValue = -DRIFT_AMPLITUDE_PX,
            targetValue = DRIFT_AMPLITUDE_PX,
            animationSpec = infiniteRepeatable(
                animation = tween(DRIFT_PERIOD_MS, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "drift",
        )

        val shimmerPosition by infiniteTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(SHIMMER_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )

        val barHeightPx = with(LocalDensity.current) { BAR_HEIGHT_DP.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                            if (pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) {
                                registerInteraction()
                            }
                        }
                    }
                }
                .background(Color.Black.copy(alpha = ambientAlpha)),
        ) {
            if (isDvdMode && ambientAlpha == 1f) {
                DvdBouncingOverlay(
                    gameName = gameName,
                    downloadProgress = downloadProgress,
                    iconUrl = iconUrl,
                )
            } else {
                val textAlpha =
                    ((ambientAlpha - 0.7f) / 0.3f).coerceIn(0f, 1f) * AmbientModeConstants.TEXT_MAX_ALPHA

                if (textAlpha > 0f) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                translationY = driftOffset - DRIFT_AMPLITUDE_PX -
                                    AmbientModeConstants.TEXT_BOTTOM_OFFSET_DP.dp.toPx()
                            }
                            .alpha(textAlpha),
                    ) {
                        Text(
                            text = gameName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp,
                            ),
                            color = Color.White,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    }
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val progress = downloadProgress.coerceIn(0f, 1f)
                val t = if (originBounds != null) ambientAlpha else 1f

                val destX = 0f
                val destY = size.height - barHeightPx - DRIFT_AMPLITUDE_PX + (driftOffset * t)
                val destWidth = size.width

                val barX: Float
                val barY: Float
                val barWidth: Float
                if (originBounds != null) {
                    barX = lerp(originBounds.left, destX, t)
                    barY = lerp(originBounds.top, destY, t)
                    barWidth = lerp(originBounds.width, destWidth, t)
                } else {
                    barX = destX
                    barY = destY
                    barWidth = destWidth
                }
                val filledWidth = barWidth * progress

                drawRect(
                    color = Color.White.copy(alpha = BAR_TRACK_ALPHA * ambientAlpha),
                    topLeft = Offset(barX, barY),
                    size = Size(barWidth, barHeightPx),
                )

                if (filledWidth > 0f) {
                    drawRect(
                        color = Color.White.copy(alpha = BAR_BASE_ALPHA * ambientAlpha),
                        topLeft = Offset(barX, barY),
                        size = Size(filledWidth, barHeightPx),
                    )

                    val shimmerCenter = shimmerPosition * filledWidth
                    val shimmerHalfWidth = barWidth * SHIMMER_WIDTH_FRACTION
                    val shimmerStartX = barX + shimmerCenter - shimmerHalfWidth
                    val shimmerEndX = barX + shimmerCenter + shimmerHalfWidth

                    if (shimmerEndX > barX && shimmerStartX < barX + filledWidth) {
                        val shimmerColor = gradientColorAtPosition(
                            fraction = (shimmerCenter / barWidth).coerceIn(0f, 1f),
                            gradient = BrandGradient,
                        )

                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    shimmerColor.copy(alpha = ambientAlpha),
                                    Color.Transparent,
                                ),
                                startX = shimmerStartX,
                                endX = shimmerEndX,
                            ),
                            topLeft = Offset(barX, barY),
                            size = Size(filledWidth, barHeightPx),
                        )
                    }
                }
            }
        }
    }
}

private fun gradientColorAtPosition(fraction: Float, gradient: List<Color>): Color {
    if (gradient.isEmpty()) return Color.Transparent
    if (gradient.size == 1) return gradient[0]

    val clamped = fraction.coerceIn(0f, 1f)
    val segments = gradient.size - 1
    val segmentPosition = clamped * segments
    val segmentIndex = segmentPosition.toInt().coerceAtMost(segments - 1)
    val segmentFraction = segmentPosition - segmentIndex

    return lerp(gradient[segmentIndex], gradient[segmentIndex + 1], segmentFraction)
}
