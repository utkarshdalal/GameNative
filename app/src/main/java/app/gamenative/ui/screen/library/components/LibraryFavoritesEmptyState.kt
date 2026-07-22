package app.gamenative.ui.screen.library.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gamenative.R

/**
 * Empty state shown on the Favorites tab. Explains how favorites work and, when the user simply
 * hasn't added any yet, offers a way back to the full library so the tab never reads as broken or
 * still loading.
 *
 * @param titleResId headline shown in bold.
 * @param messageResId supporting line explaining what to do (or why nothing is shown).
 * @param actionLabelResId optional button label; when null no button is shown.
 * @param onAction invoked when the optional button is pressed.
 */
@Composable
internal fun LibraryFavoritesEmptyState(
    @StringRes titleResId: Int,
    @StringRes messageResId: Int,
    modifier: Modifier = Modifier,
    @StringRes actionLabelResId: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.StarOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(titleResId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(messageResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabelResId != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(stringResource(actionLabelResId))
            }
        }
    }
}
