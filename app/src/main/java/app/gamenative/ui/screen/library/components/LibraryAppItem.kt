package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ListItemImage

@Composable
internal fun AppItem(
    modifier: Modifier = Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
) {
    // Determine download and install state
    val downloadInfo = remember(appInfo.appId) { SteamService.getAppDownloadInfo(appInfo.appId) }
    val downloadProgress = remember(downloadInfo) { downloadInfo?.getProgress() ?: 0f }
    val isDownloading = downloadInfo != null && downloadProgress < 1f
    val isInstalled = remember(appInfo.appId) {
        SteamService.isAppInstalled(appInfo.appId)
    }

    var appSizeOnDisk by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (isInstalled) {
            appSizeOnDisk = "..."
            DownloadService.getSizeOnDiskDisplay(appInfo.appId) {  appSizeOnDisk = it }
        }
    }

    // Modern card-style item with gradient hover effect
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Game icon
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                ListItemImage(
                    modifier = Modifier.size(56.dp),
                    imageModifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    image = { appInfo.clientIconUrl }
                )
            }

            // Game info
            Column(
                modifier = Modifier.weight(1f)
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
                    // Status indicator: Installing / Installed / Not installed
                    val statusText = when {
                        isDownloading -> "Installing"
                        isInstalled -> "Installed"
                        else -> "Not installed"
                    }
                    val statusColor = when {
                        isDownloading || isInstalled -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

                    // Game size on its own line for installed games
                    if (isInstalled) {
                        Text(
                            text = "$appSizeOnDisk",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Family share indicator on its own line if needed
                    if (appInfo.isShared) {
                        Text(
                            text = "Family Shared",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

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
                    text = "Open",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_AppItem() {
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
                            appId = item.id,
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
