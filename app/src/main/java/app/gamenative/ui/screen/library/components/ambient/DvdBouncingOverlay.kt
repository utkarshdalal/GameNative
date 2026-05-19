package app.gamenative.ui.screen.library.components.ambient

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.DVD_COLOR_CROSSFADE_MS
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.DVD_ICON_SIZE_DP
import app.gamenative.ui.screen.library.components.ambient.AmbientModeConstants.DVD_SPEED_DP_PER_SEC
import app.gamenative.ui.util.ListItemImage
import kotlinx.coroutines.isActive
import kotlin.math.abs

private val DvdPalette = listOf(
    Color.Red,
    Color.Green,
    Color.Yellow,
    Color.Blue,
    Color.Magenta,
    Color.Cyan,
    Color.White,
)

@Composable
internal fun DvdBouncingOverlay(
    gameName: String,
    downloadProgress: Float,
    iconUrl: String?,
) {
    val density = LocalDensity.current

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var elementSize by remember { mutableStateOf(IntSize.Zero) }

    var posX by remember { mutableFloatStateOf(0f) }
    var posY by remember { mutableFloatStateOf(0f) }
    var velX by remember { mutableFloatStateOf(1f) }
    var velY by remember { mutableFloatStateOf(1f) }
    var colorIndex by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }

    val targetColor = DvdPalette[colorIndex % DvdPalette.size]
    val currentColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(DVD_COLOR_CROSSFADE_MS),
        label = "dvdColor",
    )

    val speedPx = with(density) { DVD_SPEED_DP_PER_SEC.dp.toPx() }

    LaunchedEffect(containerSize, elementSize, speedPx) {
        if (containerSize == IntSize.Zero || elementSize == IntSize.Zero) return@LaunchedEffect

        val maxX = (containerSize.width - elementSize.width).toFloat().coerceAtLeast(0f)
        val maxY = (containerSize.height - elementSize.height).toFloat().coerceAtLeast(0f)
        if (maxX <= 0f || maxY <= 0f) return@LaunchedEffect

        if (!initialized) {
            posX = maxX * 0.3f
            posY = maxY * 0.25f
            initialized = true
        } else {
            posX = posX.coerceIn(0f, maxX)
            posY = posY.coerceIn(0f, maxY)
        }

        var prevMillis = withFrameMillis { it }

        while (isActive) {
            withFrameMillis { frameMillis ->
                val dt = (frameMillis - prevMillis) / 1_000f
                prevMillis = frameMillis

                var newX = posX + velX * speedPx * dt
                var newY = posY + velY * speedPx * dt
                var bounced = false

                if (newX <= 0f) {
                    newX = 0f
                    velX = abs(velX)
                    bounced = true
                } else if (newX >= maxX) {
                    newX = maxX
                    velX = -abs(velX)
                    bounced = true
                }

                if (newY <= 0f) {
                    newY = 0f
                    velY = abs(velY)
                    bounced = true
                } else if (newY >= maxY) {
                    newY = maxY
                    velY = -abs(velY)
                    bounced = true
                }

                if (bounced) {
                    var next = colorIndex
                    while (next == colorIndex) {
                        next = (0 until DvdPalette.size).random()
                    }
                    colorIndex = next
                }

                posX = newX
                posY = newY
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .onSizeChanged { elementSize = it }
                .graphicsLayer {
                    translationX = posX
                    translationY = posY
                },
        ) {
            if (iconUrl != null) {
                ListItemImage(
                    image = { iconUrl },
                    size = DVD_ICON_SIZE_DP.dp,
                    imageModifier = Modifier.clip(CircleShape),
                )
            }
            Column {
                Text(
                    text = gameName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = currentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = currentColor,
                )
            }
        }
    }
}
