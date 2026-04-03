package app.gamenative.ui.component.dialog

import app.gamenative.R
import app.gamenative.utils.SteamGridDB
import app.gamenative.ui.component.Scrollbar
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun ShortcutIconChooserDialog(
    openDialog: Boolean,
    icons: List<SteamGridDB.ShortcutIconOption>,
    defaultIconUrl: String?,
    isLoading: Boolean,
    emptyMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    if (!openDialog) return

    var selectedIconUrl by remember(openDialog) { mutableStateOf<String?>(null) }
    var previewIcon by remember(openDialog) { mutableStateOf<SteamGridDB.ShortcutIconOption?>(null) }

    LaunchedEffect(openDialog, defaultIconUrl, icons) {
        if (!openDialog) return@LaunchedEffect
        val selectionStillExists = selectedIconUrl != null && icons.any { it.url == selectedIconUrl }
        if (!selectionStillExists) {
            selectedIconUrl = icons.firstOrNull()?.url ?: defaultIconUrl
        }
    }

    previewIcon?.let { icon ->
        ShortcutIconPreviewDialog(
            icon = icon,
            onDismiss = { previewIcon = null },
            onChoose = {
                selectedIconUrl = icon.url
                previewIcon = null
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.shortcut_icon_dialog_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            ShortcutIconChooserContent(
                icons = icons,
                isLoading = isLoading,
                emptyMessage = emptyMessage,
                defaultIconUrl = defaultIconUrl,
                selectedIconUrl = selectedIconUrl,
                onPreviewIcon = { previewIcon = it },
                onSelectIcon = { selectedIconUrl = it },
            )
        },
        confirmButton = {
            Button(
                enabled = !isLoading,
                onClick = { onConfirm(selectedIconUrl) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.create_shortcut))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun ShortcutIconPreviewDialog(
    icon: SteamGridDB.ShortcutIconOption,
    onDismiss: () -> Unit,
    onChoose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.shortcut_icon_dialog_preview),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            ShortcutIconPreviewContent(icon = icon)
        },
        confirmButton = {
            Button(onClick = onChoose) {
                Text(stringResource(R.string.shortcut_icon_dialog_choose))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun ShortcutIconPreviewContent(
    icon: SteamGridDB.ShortcutIconOption,
) {
    val inspectionMode = LocalInspectionMode.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = CircleShape,
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (inspectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_icon_dialog_option).first().toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                CoilImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    imageModel = { icon.url },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    loading = {
                        CircularProgressIndicator()
                    },
                    failure = {
                        Text("?", style = MaterialTheme.typography.headlineMedium)
                    },
                )
            }
        }
    }
}

@Composable
fun ShortcutIconChooserContent(
    icons: List<SteamGridDB.ShortcutIconOption>,
    isLoading: Boolean,
    emptyMessage: String,
    defaultIconUrl: String?,
    selectedIconUrl: String?,
    onPreviewIcon: (SteamGridDB.ShortcutIconOption) -> Unit,
    onSelectIcon: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val inspectionMode = LocalInspectionMode.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.shortcut_icon_dialog_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            icons.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp),
                ) {
                    Scrollbar(
                        listState = gridState,
                        modifier = Modifier.fillMaxSize(),
                        hideDelay = 2000L,
                        thumbWidthCollapsed = 6.dp,
                        thumbWidthExpanded = 10.dp,
                    ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                count = icons.size,
                                key = { index -> icons[index].url },
                            ) { index ->
                                val icon = icons[index]
                                val isSelected = selectedIconUrl == icon.url
                                val isDefault = defaultIconUrl == icon.url

                                Column(
                                    modifier = Modifier
                                        .size(112.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(16.dp),
                                        )
                                        .clickable { onPreviewIcon(icon) }
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (inspectionMode) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) {
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                                        } else {
                                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                                        },
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = icon.style.takeIf { it.isNotBlank() }?.firstOrNull()?.uppercase()
                                                        ?: stringResource(R.string.shortcut_icon_dialog_option).first().toString(),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        } else {
                                            CoilImage(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape),
                                                imageModel = { icon.url },
                                                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                                loading = {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(18.dp),
                                                            strokeWidth = 2.dp,
                                                        )
                                                    }
                                                },
                                                failure = {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text("?", style = MaterialTheme.typography.titleMedium)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isDefault) {
                                            stringResource(R.string.shortcut_icon_dialog_default)
                                        } else {
                                            stringResource(R.string.shortcut_icon_dialog_option)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 260, heightDp = 260)
@Composable
private fun ShortcutIconPreviewDialogPreview() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            ShortcutIconPreviewContent(
                icon = SteamGridDB.ShortcutIconOption(
                    url = "preview://white",
                    style = "White",
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 420)
@Composable
private fun ShortcutIconPreviewDialogShellPreview() {
    PluviaTheme {
        ShortcutIconPreviewDialog(
            icon = SteamGridDB.ShortcutIconOption(
                url = "preview://white",
                style = "White",
            ),
            onDismiss = {},
            onChoose = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ShortcutIconChooserContentPreview() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        text = stringResource(R.string.shortcut_icon_dialog_title),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                },
                text = {
                    ShortcutIconChooserContent(
                        icons = listOf(
                            SteamGridDB.ShortcutIconOption(
                                url = "preview://white",
                                style = "White",
                            ),
                            SteamGridDB.ShortcutIconOption(
                                url = "preview://color",
                                style = "Color",
                            ),
                            SteamGridDB.ShortcutIconOption(
                                url = "preview://mono",
                                style = "Mono",
                            ),
                        ),
                        isLoading = false,
                        emptyMessage = "No icons found",
                        defaultIconUrl = "preview://white",
                        selectedIconUrl = "preview://white",
                        onPreviewIcon = {},
                        onSelectIcon = {},
                    )
                },
                confirmButton = {
                    Button(onClick = {}) {
                        Text(stringResource(R.string.create_shortcut))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {}) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShortcutIconChooserDialogPreview() {
    PluviaTheme {
        ShortcutIconChooserDialog(
            openDialog = true,
            icons = listOf(
                SteamGridDB.ShortcutIconOption(
                    url = "preview://white",
                    style = "White",
                ),
                SteamGridDB.ShortcutIconOption(
                    url = "preview://color",
                    style = "Color",
                ),
                SteamGridDB.ShortcutIconOption(
                    url = "preview://mono",
                    style = "Mono",
                ),
            ),
            defaultIconUrl = "preview://white",
            isLoading = false,
            emptyMessage = "No icons found",
            onDismiss = {},
            onConfirm = {},
        )
    }
}
