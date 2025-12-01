package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import app.gamenative.data.GameSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face4
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ListItemImage
import app.gamenative.utils.CustomGameScanner
import java.io.File
import android.net.Uri

@Composable
internal fun AppItem(
    modifier: Modifier = Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
    paneType: PaneType = PaneType.LIST,
    onFocus: () -> Unit = {},
    isRefreshing: Boolean = false,
    imageRefreshCounter: Long = 0L,
) {
    val context = LocalContext.current
    var hideText by remember { mutableStateOf(true) }
    var alpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(paneType) {
        hideText = true
        alpha = 1f
    }

    // Reset alpha and hideText when image URL changes (e.g., when new images are fetched)
    LaunchedEffect(imageRefreshCounter) {
        if (paneType != PaneType.LIST) {
            hideText = true
            alpha = 1f
        }
    }

    // True when selected, e.g. with controller
    var isFocused by remember { mutableStateOf(false) }

    // Border is used to highlight selected card
    val border = if (isFocused) {
        androidx.compose.foundation.BorderStroke(
            width = 3.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
            )
        )
    } else {
        androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        )
    }

    // Modern card-style item with gradient hover effect
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (isFocused) {
                    onFocus()
                }
            }
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = border,
    ) {
        val outerPadding = if (paneType == PaneType.LIST) {
            // Padding to make text easy to read
            16.dp
        } else {
            0.dp
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(outerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Game icon
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                if (paneType == PaneType.LIST) {
                    val iconUrl = remember(appInfo.appId) {
                        if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
                            val path = CustomGameScanner.findIconFileForCustomGame(context, appInfo.appId)
                            if (!path.isNullOrEmpty()) {
                                if (path.startsWith("file://")) path else "file://$path"
                            } else {
                                appInfo.clientIconUrl
                            }
                        } else appInfo.clientIconUrl
                    }
                    ListItemImage(
                        modifier = Modifier.size(56.dp),
                        imageModifier = Modifier.clip(RoundedCornerShape(10.dp)),
                        image = { iconUrl }
                    )
                } else {
                    val aspectRatio = if (paneType == PaneType.GRID_CAPSULE) { 2/3f } else { 460/215f }

                    // Helper function to find SteamGridDB images for Custom Games
                    fun findSteamGridDBImage(imageType: String): String? {
                        if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
                            val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
                            gameFolderPath?.let { path ->
                                val folder = java.io.File(path)
                                val imageFile = folder.listFiles()?.firstOrNull { file ->
                                    file.name.startsWith("steamgriddb_$imageType") &&
                                    (file.name.endsWith(".png", ignoreCase = true) ||
                                     file.name.endsWith(".jpg", ignoreCase = true) ||
                                     file.name.endsWith(".webp", ignoreCase = true))
                                }
                                return imageFile?.let { android.net.Uri.fromFile(it).toString() }
                            }
                        }
                        return null
                    }

                    val imageUrl = remember(appInfo.appId, paneType, imageRefreshCounter) {
                        if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
                            // For Custom Games, use SteamGridDB images
                            when (paneType) {
                                PaneType.GRID_CAPSULE -> {
                                    // Vertical grid for capsule
                                    findSteamGridDBImage("grid_capsule")
                                        ?: "https://shared.steamstatic.com/store_item_assets/steam/apps/" + appInfo.gameId + "/library_600x900.jpg"
                                }
                                PaneType.GRID_HERO -> {
                                    // Horizontal grid for hero view
                                    findSteamGridDBImage("grid_hero")
                                        ?: "https://shared.steamstatic.com/store_item_assets/steam/apps/" + appInfo.gameId + "/header.jpg"
                                }
                                else -> {
                                    // For list view, use heroes endpoint (not grid_hero)
                                    val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
                                    val heroUrl = gameFolderPath?.let { path ->
                                        val folder = java.io.File(path)
                                        val heroFile = folder.listFiles()?.firstOrNull { file ->
                                            file.name.startsWith("steamgriddb_hero") &&
                                            !file.name.contains("grid") &&
                                            (file.name.endsWith(".png", ignoreCase = true) ||
                                             file.name.endsWith(".jpg", ignoreCase = true) ||
                                             file.name.endsWith(".webp", ignoreCase = true))
                                        }
                                        heroFile?.let { android.net.Uri.fromFile(it).toString() }
                                    }
                                    heroUrl ?: "https://shared.steamstatic.com/store_item_assets/steam/apps/" + appInfo.gameId + "/header.jpg"
                                }
                            }
                        } else {
                            // For Steam games, use standard Steam URLs
                            if (paneType == PaneType.GRID_CAPSULE) {
                                "https://shared.steamstatic.com/store_item_assets/steam/apps/" + appInfo.gameId + "/library_600x900.jpg"
                            } else {
                                "https://shared.steamstatic.com/store_item_assets/steam/apps/" + appInfo.gameId + "/header.jpg"
                            }
                        }
                    }

                    // Reset alpha and hideText when image URL changes (e.g., when new images are fetched)
                    LaunchedEffect(imageUrl) {
                        if (paneType != PaneType.LIST) {
                            hideText = true
                            alpha = 1f
                        }
                    }

                    ListItemImage(
                        modifier = Modifier.aspectRatio(aspectRatio),
                        imageModifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .alpha(alpha),
                        image = { imageUrl },
                        onFailure = {
                            hideText = false
                            alpha = 0.1f
                        }
                    )

                    // Only display text if the image loading has failed
                    if (! hideText) {
                        GameInfoBlock(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                            appInfo = appInfo,
                            isRefreshing = isRefreshing,
                        )
                    } else {
                        var isInstalled by remember(appInfo.appId, appInfo.gameSource) {
                            when (appInfo.gameSource) {
                                GameSource.STEAM -> mutableStateOf(SteamService.isAppInstalled(appInfo.gameId))
                                GameSource.CUSTOM_GAME -> mutableStateOf(true) // Custom Games are always considered installed
                                else -> mutableStateOf(false)
                            }
                        }
                        // Update installation status when refresh completes
                        LaunchedEffect(isRefreshing) {
                            if (!isRefreshing) {
                                // Refresh just completed, check installation status
                                isInstalled = when (appInfo.gameSource) {
                                    GameSource.STEAM -> SteamService.isAppInstalled(appInfo.gameId)
                                    GameSource.CUSTOM_GAME -> true
                                    else -> false
                                }
                            }
                        }

                        // Calculate padding for text to prevent overlap with icons
                        val hasIcons = isInstalled || appInfo.isShared
                        val iconWidth = when {
                            isInstalled && appInfo.isShared -> 44.dp // Two icons + spacing
                            hasIcons -> 22.dp // One icon + spacing
                            else -> 0.dp
                        }

                        // Black footer overlay with game title
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = appInfo.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(end = iconWidth)
                            )

                            // Status icons for install status/family share
                            if (hasIcons) {
                                Row(
                                    modifier = Modifier.align(alignment = Alignment.CenterEnd),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isInstalled) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.library_installed),
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    if (appInfo.isShared) {
                                        Icon(
                                            Icons.Filled.Face4,
                                            contentDescription = stringResource(R.string.library_family_shared),
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (paneType == PaneType.LIST) {
                GameInfoBlock(
                    modifier = Modifier.weight(1f),
                    appInfo = appInfo,
                    isRefreshing = isRefreshing,
                )

                // Play/Open button
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = stringResource(R.string.library_open),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun GameInfoBlock(
    modifier: Modifier,
    appInfo: LibraryItem,
    isRefreshing: Boolean = false,
) {
    // For text displayed in list view, or as override if image loading fails

    // Determine download and install state for Steam games only
    val isSteam = appInfo.gameSource == GameSource.STEAM
    val downloadInfo = remember(appInfo.appId) { if (isSteam) SteamService.getAppDownloadInfo(appInfo.gameId) else null }
    var downloadProgress by remember(downloadInfo) { mutableFloatStateOf(downloadInfo?.getProgress() ?: 0f) }
    val isDownloading = downloadInfo != null && downloadProgress < 1f
    var isInstalledSteam by remember(appInfo.appId) { mutableStateOf(if (isSteam) SteamService.isAppInstalled(appInfo.gameId) else false) }

    // Update installation status when refresh completes
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            if (isSteam) {
                // Refresh just completed, check installation status
                isInstalledSteam = SteamService.isAppInstalled(appInfo.gameId)
            }
        }
    }

    // Function to refresh progress from downloadInfo - can be called from remember and LaunchedEffect
    val refreshProgress: () -> Unit = {
        downloadProgress = downloadInfo?.getProgress() ?: 0f
    }

    // Refresh progress when list reloads (for downloading games) or when downloadInfo changes
    LaunchedEffect(appInfo.appId, downloadInfo, isRefreshing) {
        if (downloadInfo != null) {
            refreshProgress()
        }
    }

    // Listen to real-time progress updates via listener
    DisposableEffect(downloadInfo) {
        val onDownloadProgress: (Float) -> Unit = { progress ->
            downloadProgress = progress
        }
        downloadInfo?.addProgressListener(onDownloadProgress)

        onDispose {
            downloadInfo?.removeProgressListener(onDownloadProgress)
        }
    }

    var appSizeOnDisk by remember { mutableStateOf("") }

    var hideText by remember { mutableStateOf(true) }
    var alpha = remember(Int) {1f}

    LaunchedEffect(isSteam, isInstalledSteam) {
        if (isSteam && isInstalledSteam) {
            appSizeOnDisk = "..."
            DownloadService.getSizeOnDiskDisplay(appInfo.gameId) {  appSizeOnDisk = it }
        }
    }

    // Game info
    Column(
        modifier = modifier,
    ) {
        Text(
            text = appInfo.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Status indicator
            val (statusText, statusColor) = if (isSteam) {
                val text = when {
                    isDownloading -> stringResource(R.string.library_installing)
                    isInstalled -> stringResource(R.string.library_installed)
                    else -> stringResource(R.string.library_not_installed)
                }
                val color = when {
                    isDownloading || isInstalledSteam -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
                text to color
            } else {
                // Custom Games are considered ready (no Steam install tracking)
                stringResource(R.string.library_status_ready) to MaterialTheme.colorScheme.tertiary
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = statusColor, shape = CircleShape)
                )
                // Status text
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
                // Download percentage when installing
                if (isDownloading) {
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                }
            }

            // Game size on its own line for installed Steam games only
            if (isSteam && isInstalledSteam) {
                Text(
                    text = "$appSizeOnDisk",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Family share indicator on its own line if needed
            if (appInfo.isShared) {
                Text(
                    text = stringResource(R.string.library_family_shared),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_AppItem() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        Surface {
            LazyColumn(
                modifier = Modifier.padding(16.dp)
            ) {
                items(
                    items = List(5) { idx ->
                        val item = fakeAppInfo(idx)
                        LibraryItem(
                            index = idx,
                            appId = "${GameSource.STEAM.name}_${item.id}",
                            name = item.name,
                            iconHash = item.iconHash,
                            isShared = idx % 2 == 0,
                        )
                    },
                    itemContent = {
                        AppItem(appInfo = it, onClick = {})
                    },
                )
            }
        }
    }
}

@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_AppItemGrid() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        Surface {
            Column {
                val appInfoList = List(4) { idx ->
                    val item = fakeAppInfo(idx)
                    LibraryItem(
                        index = idx,
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.iconHash,
                        isShared = idx % 2 == 0,
                        gameSource = GameSource.STEAM,
                    )
                }

                // Hero
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 72.dp
                    ),
                ) {
                    items(items = appInfoList, key = { it.index }) { item ->
                        AppItem(
                            appInfo = item,
                            onClick = { },
                            paneType = PaneType.GRID_HERO,
                        )
                    }
                }

                // Capsule
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 72.dp
                    ),
                ) {
                    items(items = appInfoList, key = { it.index }) { item ->
                        AppItem(
                            appInfo = item,
                            onClick = { },
                            paneType = PaneType.GRID_CAPSULE,
                        )
                    }
                }
            }
        }
    }
}
