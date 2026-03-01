package app.gamenative.ui.screen.library.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face4
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.service.SteamService
import app.gamenative.ui.component.CompatibilityBadge
import app.gamenative.ui.util.ListItemImage
import app.gamenative.utils.CustomGameScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * List view card with compact layout.
 */
@Composable
internal fun ListViewCard(
    modifier: Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    isRefreshing: Boolean,
    compatibilityStatus: GameCompatibilityStatus?,
    context: Context,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isItemFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isItemFocused) {
        onFocusChanged(isItemFocused)
        if (isItemFocused) onFocus()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null,
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            },
        ),
        border = if (isFocused) {
            BorderStroke(
                2.dp,
                Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    ),
                ),
            )
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Game icon
            val iconUrl by produceState(
                initialValue = appInfo.clientIconUrl,
                key1 = appInfo.appId,
                key2 = appInfo.clientIconUrl,
            ) {
                value = withContext(Dispatchers.IO) {
                    getListIconUrl(context, appInfo)
                }
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ListItemImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    image = { iconUrl },
                )
            }

            // Game info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = appInfo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Status row with compact badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InstallStatusBadge(appInfo = appInfo, isRefreshing = isRefreshing)

                    // Family share indicator
                    if (appInfo.isShared) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Face4,
                                contentDescription = stringResource(R.string.library_family_shared),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = stringResource(R.string.library_shared_short),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }

            // Compatibility badge
            compatibilityStatus?.let { status ->
                CompatibilityBadge(
                    status = status,
                    showLabel = true,
                )
            }
        }
    }
}

/**
 * Compact install status badge for list view.
 */
@Composable
private fun InstallStatusBadge(
    appInfo: LibraryItem,
    isRefreshing: Boolean,
) {
    val isSteam = appInfo.gameSource == GameSource.STEAM
    val downloadInfo = remember(appInfo.appId) {
        if (isSteam) SteamService.getAppDownloadInfo(appInfo.gameId) else null
    }
    var downloadProgress by remember(downloadInfo) {
        mutableFloatStateOf(downloadInfo?.getProgress() ?: 0f)
    }
    val isDownloading = downloadInfo != null && downloadProgress < 1f
    var isInstalled by remember(appInfo.appId) {
        mutableStateOf(
            if (isSteam) {
                SteamService.isAppInstalled(appInfo.gameId)
            } else {
                true // Custom Games always installed
            },
        )
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && isSteam) {
            isInstalled = SteamService.isAppInstalled(appInfo.gameId)
        }
    }

    DisposableEffect(downloadInfo) {
        val onProgress: (Float) -> Unit = { downloadProgress = it }
        downloadInfo?.addProgressListener(onProgress)
        onDispose { downloadInfo?.removeProgressListener(onProgress) }
    }

    val (text, color) = when {
        !isSteam -> stringResource(R.string.library_status_ready) to MaterialTheme.colorScheme.tertiary

        isDownloading -> "${(downloadProgress * 100).toInt()}%" to MaterialTheme.colorScheme.primary

        isInstalled -> stringResource(R.string.library_installed) to MaterialTheme.colorScheme.tertiary

        else -> stringResource(R.string.library_not_installed) to MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = 0.6f,
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Gets the icon URL for a game in list view.
 */
private fun getListIconUrl(context: Context, appInfo: LibraryItem): String {
    return if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
        val path = CustomGameScanner.findIconFileForCustomGame(context, appInfo.appId)
        if (!path.isNullOrEmpty()) {
            if (path.startsWith("file://")) path else "file://$path"
        } else {
            appInfo.clientIconUrl
        }
    } else {
        appInfo.clientIconUrl
    }
}
