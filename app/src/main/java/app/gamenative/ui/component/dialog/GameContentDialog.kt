package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.RingButton
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.component.focusOnOpen
import app.gamenative.ui.component.ringClickable
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.data.developerAndYear
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

data class GameContentRow<K>(
    val key: K,
    val title: String,
    val downloadSize: Long,
    val installSize: Long,
    val note: String? = null,
    val installed: Boolean = false,
    val checked: Boolean,
    val enabled: Boolean,
)

/**
 * Full-screen picker for a game and its DLC, shared by the store dialogs.
 * The caller owns loading, selection and store rules; this only draws them.
 */
@Composable
fun <K> GameContentDialog(
    displayInfo: GameDisplayInfo,
    scrollState: ScrollState,
    loading: Boolean,
    rows: List<GameContentRow<K>>,
    onCheckedChange: (key: K, checked: Boolean) -> Unit,
    formatSize: (Long) -> String,
    footerText: String,
    installEnabled: Boolean,
    onInstall: () -> Unit,
    onDismissRequest: () -> Unit,
    headerNote: String? = null,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    showSelectAll: Boolean = false,
    allSelected: Boolean = false,
    onToggleSelectAll: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.Start,
            ) {
                GameContentHero(displayInfo = displayInfo, onDismissRequest = onDismissRequest)

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (headerNote != null) {
                        Text(
                            text = headerNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    if (loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = PluviaTheme.progressColor)
                        }
                    } else {
                        if (errorMessage != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                if (onRetry != null) {
                                    RingTextButton(onClick = onRetry) {
                                        Text(stringResource(R.string.connection_retry))
                                    }
                                }
                            }
                        }

                        if (showSelectAll) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                RingButton(onClick = onToggleSelectAll) {
                                    Text(
                                        text = if (allSelected) {
                                            stringResource(R.string.gog_dlc_deselect_all)
                                        } else {
                                            stringResource(R.string.nexus_select_all)
                                        }
                                    )
                                }
                            }
                        }

                        rows.forEach { row ->
                            key(row.key) {
                                GameContentRowItem(
                                    row = row,
                                    formatSize = formatSize,
                                    onCheckedChange = { onCheckedChange(row.key, it) },
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 8.dp, bottom = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(0.5f),
                            text = if (loading) "" else footerText
                        )

                        RingButton(
                            enabled = installEnabled && !loading,
                            onClick = onInstall
                        ) {
                            Text(stringResource(R.string.install))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun GameContentHero(
    displayInfo: GameDisplayInfo,
    onDismissRequest: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        if (displayInfo.heroImageUrl != null) {
            CoilImage(
                modifier = Modifier.fillMaxSize(),
                imageModel = { displayInfo.heroImageUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                loading = { LoadingScreen() },
                failure = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary
                        ) { }
                    }
                },
                previewPlaceholder = painterResource(R.drawable.testhero),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary
            ) { }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .padding(20.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .focusOnOpen()
        ) {
            BackButton(onClick = onDismissRequest)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            Text(
                text = displayInfo.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(0f, 2f),
                        blurRadius = 10f
                    )
                ),
                color = Color.White
            )

            Text(
                text = remember(displayInfo.developer, displayInfo.releaseDate) { displayInfo.developerAndYear() },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <K> GameContentRowItem(
    row: GameContentRow<K>,
    formatSize: (Long) -> String,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Column {
                Text(text = row.title)
                if (row.installSize > 0) {
                    Text(
                        text = stringResource(
                            R.string.gog_dlc_sizes,
                            formatSize(row.downloadSize),
                            formatSize(row.installSize)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else if (row.downloadSize > 0) {
                    Text(
                        text = stringResource(R.string.gog_dlc_download_size, formatSize(row.downloadSize)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (row.note != null) {
                    Text(
                        text = row.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (row.installed) {
                    Text(
                        text = stringResource(R.string.installed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        trailingContent = {
            Checkbox(
                checked = row.checked,
                enabled = row.enabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.ringClickable(enabled = row.enabled) {
            onCheckedChange(!row.checked)
        }
    )

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}
