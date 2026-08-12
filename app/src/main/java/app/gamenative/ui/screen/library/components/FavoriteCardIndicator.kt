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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.theme.PluviaWarning

internal class FavoriteCardIndicator(
    val isFavorite: Boolean,
    val initialScale: Float,
)

@Composable
internal fun rememberFavoriteCardIndicator(
    appId: String,
    isRecommended: Boolean,
): FavoriteCardIndicator {
    val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
    val favoritesLoaded by FavoritesManager.loaded.collectAsStateWithLifecycle()
    val isFavorite = !isRecommended && appId in favorites
    val initialScale = remember { Animatable(1f) }
    var wasLoaded by remember { mutableStateOf(favoritesLoaded) }

    LaunchedEffect(favoritesLoaded) {
        if (favoritesLoaded && !wasLoaded && isFavorite) {
            initialScale.animateTo(
                targetValue = 1.03f,
                animationSpec = tween(durationMillis = 300),
            )
            initialScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300),
            )
        }
        wasLoaded = favoritesLoaded
    }

    return FavoriteCardIndicator(
        isFavorite = isFavorite,
        initialScale = initialScale.value,
    )
}

internal fun Modifier.favoriteInnerGlow(
    isFavorite: Boolean,
    shape: Shape,
): Modifier {
    if (!isFavorite) return this

    return drawWithCache {
        val wideStroke = 20.dp.toPx()
        val mediumStroke = 10.dp.toPx()
        val narrowStroke = 4.dp.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
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
            canvas.save()
            canvas.clipPath(clipPath)
            drawOutline(
                outline = outline,
                color = PluviaWarning.copy(alpha = 0.12f),
                style = Stroke(wideStroke),
            )
            drawOutline(
                outline = outline,
                color = PluviaWarning.copy(alpha = 0.2f),
                style = Stroke(mediumStroke),
            )
            drawOutline(
                outline = outline,
                color = PluviaWarning.copy(alpha = 0.3f),
                style = Stroke(narrowStroke),
            )
            canvas.restore()
        }
    }
}
