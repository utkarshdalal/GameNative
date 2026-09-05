package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.theme.PluviaWarning

internal class FavoriteCardIndicator(
    val isFavorite: Boolean,
    val glowAlpha: Float,
)

@Composable
internal fun rememberFavoriteCardIndicator(
    appId: String,
    isRecommended: Boolean,
): FavoriteCardIndicator {
    val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
    val favoritesLoaded by FavoritesManager.loaded.collectAsStateWithLifecycle()
    val isFavorite = !isRecommended && appId in favorites
    val glowAlpha = remember { Animatable(1f) }
    var wasReady by remember { mutableStateOf(false) }
    var previousIsFavorite by remember { mutableStateOf(isFavorite) }

    LaunchedEffect(favoritesLoaded, isFavorite) {
        if (!favoritesLoaded) return@LaunchedEffect

        if (wasReady && !previousIsFavorite && isFavorite) {
            glowAlpha.snapTo(0.45f)
            glowAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240),
            )
        }

        previousIsFavorite = isFavorite
        wasReady = true
    }

    return FavoriteCardIndicator(
        isFavorite = isFavorite,
        glowAlpha = glowAlpha.value,
    )
}

internal fun Modifier.favoriteInnerGlow(
    isFavorite: Boolean,
    glowAlpha: Float,
    shape: Shape,
): Modifier {
    if (!isFavorite) return this

    return drawWithCache {
        val strokePx = 4.dp.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val bounds = Rect(Offset.Zero, size)
        val gradient = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to PluviaWarning.copy(alpha = 0.36f * glowAlpha),
                0.5f to PluviaWarning.copy(alpha = 0.28f * glowAlpha),
                0.82f to PluviaWarning.copy(alpha = 0.14f * glowAlpha),
                1f to PluviaWarning.copy(alpha = 0.04f * glowAlpha),
            ),
        )
        val layerPaint = Paint()
        val clipPath = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }

        onDrawWithContent {
            drawContent()
            val canvas = drawContext.canvas
            canvas.saveLayer(bounds, layerPaint)
            canvas.clipPath(clipPath)
            drawOutline(outline, color = androidx.compose.ui.graphics.Color.Black, style = Stroke(strokePx * 2f))
            drawRect(brush = gradient, blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
    }
}
