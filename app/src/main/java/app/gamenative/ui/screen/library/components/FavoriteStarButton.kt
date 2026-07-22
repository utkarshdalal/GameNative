package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.component.focusRing

/**
 * A star button that shows whether a game is a favorite and toggles it when tapped.
 *
 * It observes [FavoritesManager] directly, so it can be dropped onto any card or screen without
 * threading callbacks through the surrounding composables.
 *
 * @param gameName used to build a contextual accessibility label ("Add <game> to favorites") and
 *   the removal snackbar. When null the button falls back to generic labels.
 * @param onImage when true, the icon uses a light tint and sits on a subtle circular scrim so it
 *   stays readable on top of cover art.
 */
@Composable
internal fun FavoriteStarButton(
    appId: String,
    modifier: Modifier = Modifier,
    gameName: String? = null,
    iconSize: Int = 20,
    onImage: Boolean = false,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
    val isFavorite = appId in favorites

    val tint = when {
        isFavorite -> MaterialTheme.colorScheme.primary
        onImage -> Color.White.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val contentDescription = when {
        gameName.isNullOrBlank() -> stringResource(
            if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
        )
        isFavorite -> stringResource(R.string.favorite_remove_named, gameName)
        else -> stringResource(R.string.favorite_add_named, gameName)
    }

    // Pop the star when it is turned on, but not when a card that is already a favorite first
    // appears (e.g. while scrolling) — only user-driven toggles should animate.
    val scale = remember { Animatable(1f) }
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(isFavorite) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        if (isFavorite) {
            scale.snapTo(0.6f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    // Own the interaction source so a D-pad / controller focus draws a visible ring on the button
    // (the star is often the only focusable overlay on a cover, so it needs its own affordance).
    val interactionSource = remember { MutableInteractionSource() }

    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            toggleFavoriteWithUndo(context, appId, gameName)
        },
        modifier = modifier.focusRing(interactionSource, CircleShape),
        interactionSource = interactionSource,
    ) {
        val icon = @Composable {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarOutline,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier
                    .size(iconSize.dp)
                    .scale(scale.value),
            )
        }
        if (onImage) {
            // Scrim keeps the star legible over bright or busy cover art without enlarging the
            // 48dp touch target the surrounding IconButton already provides.
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.32f), CircleShape)
                    .padding(4.dp),
            ) {
                icon()
            }
        } else {
            icon()
        }
    }
}
