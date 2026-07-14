package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.data.FavoritesManager

/**
 * A star button that shows whether a game is a favorite and toggles it when tapped.
 *
 * It observes [FavoritesManager] directly, so it can be dropped onto any card or screen without
 * threading callbacks through the surrounding composables.
 *
 * @param onImage when true, the icon uses a light tint so it stays readable on top of cover art.
 */
@Composable
internal fun FavoriteStarButton(
    appId: String,
    modifier: Modifier = Modifier,
    iconSize: Int = 20,
    onImage: Boolean = false,
) {
    val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
    val isFavorite = appId in favorites

    val tint = when {
        isFavorite -> MaterialTheme.colorScheme.primary
        onImage -> Color.White.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = { FavoritesManager.toggle(appId) },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarOutline,
            contentDescription = stringResource(
                if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
            ),
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}
