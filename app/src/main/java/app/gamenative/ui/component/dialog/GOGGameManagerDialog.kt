package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.GOGGame
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.gog.GogDlcKind
import app.gamenative.service.gog.GogDlcOption
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import com.winlator.core.StringUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GOGGameManagerDialog(
    visible: Boolean,
    isInstalled: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onGetLanguage: () -> String,
    onNoDlc: () -> Unit,
    onInstall: (Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId.toString()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var baseGame by remember { mutableStateOf<GOGGame?>(null) }
    var availableBytes by remember { mutableLongStateOf(0L) }
    var options by remember { mutableStateOf<List<GogDlcOption>>(emptyList()) }
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(visible, reloadKey) {
        if (!visible) return@LaunchedEffect
        loading = true
        loadError = null
        scrollState.animateScrollTo(0)

        val (game, space, result) = withContext(Dispatchers.IO) {
            val path = GOGService.getInstallPath(gameId) ?: GOGConstants.defaultGOGGamesPath
            val freeBytes = try {
                StorageUtils.getAvailableSpace(path)
            } catch (e: Exception) {
                0L
            }
            val dlcResult = try {
                GOGService.getDlcOptions(context, gameId, onGetLanguage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            Triple(GOGService.getGOGGameOf(gameId), freeBytes, dlcResult)
        }

        baseGame = game
        availableBytes = space
        val loaded = result.getOrNull()
        if (loaded != null && loaded.isEmpty() && !isInstalled) {
            onNoDlc()
            return@LaunchedEffect
        }
        options = loaded.orEmpty()
        loadError = result.exceptionOrNull()?.let { it.message.orEmpty() }
        selectedIds.clear()
        options.forEach { selectedIds[it.productId] = it.installed || it.isSelectable() }
        loading = false
    }

    val selectableIds = options.filter { it.isSelectable() && !it.installed }.map { it.productId }
    val allSelectableSelected = selectableIds.isNotEmpty() && selectableIds.all { selectedIds[it] == true }
    val newSelections = options.filter { it.isSelectable() && !it.installed && selectedIds[it.productId] == true }
    val unresolvedTitles = options.filter { it.kind == GogDlcKind.UNKNOWN }.map { it.title }

    val baseDownloadBytes = if (isInstalled) 0L else baseGame?.downloadSize ?: 0L
    val baseInstallBytes = if (isInstalled) 0L else baseGame?.installSize ?: 0L
    val totalDownloadBytes = baseDownloadBytes + newSelections.sumOf { it.downloadSize }
    val totalInstallBytes = maxOf(baseInstallBytes, baseDownloadBytes) +
        newSelections.sumOf { maxOf(it.installSize, it.downloadSize) }

    val installButtonEnabled = availableBytes >= totalInstallBytes &&
        (!isInstalled || newSelections.isNotEmpty())

    if (!visible) return

    if (loading) {
        LoadingDialog(
            visible = true,
            onDismissRequest = onDismissRequest,
            progress = -1f,
            message = stringResource(R.string.main_loading),
        )
        return
    }

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
                            text = "${displayInfo.developer} • ${
                                remember(displayInfo.releaseDate) {
                                    if (displayInfo.releaseDate > 0) {
                                        SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                                    } else {
                                        ""
                                    }
                                }
                            }",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val retryMessage = loadError ?: unresolvedTitles.takeIf { it.isNotEmpty() }?.joinToString()
                    retryMessage?.let { error ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.gog_dlc_load_failed, error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { reloadKey++ }) {
                                Text(stringResource(R.string.connection_retry))
                            }
                        }
                    }

                    if (selectableIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    val newState = !allSelectableSelected
                                    selectableIds.forEach { selectedIds[it] = newState }
                                }
                            ) {
                                Text(
                                    text = if (allSelectableSelected) {
                                        stringResource(R.string.gog_dlc_deselect_all)
                                    } else {
                                        stringResource(R.string.nexus_select_all)
                                    }
                                )
                            }
                        }
                    }

                    GOGContentRow(
                        title = displayInfo.name,
                        downloadSize = baseDownloadBytes,
                        installSize = baseInstallBytes,
                        installed = isInstalled,
                        note = null,
                        checked = true,
                        enabled = false,
                        onCheckedChange = {},
                    )

                    options.forEach { option ->
                        val checked = selectedIds[option.productId] ?: false
                        val enabled = option.isSelectable() && !option.installed
                        GOGContentRow(
                            title = option.title,
                            downloadSize = option.downloadSize,
                            installSize = option.installSize,
                            installed = option.installed,
                            note = when (option.kind) {
                                GogDlcKind.NONE -> stringResource(R.string.gog_dlc_not_installable)
                                GogDlcKind.UNKNOWN -> stringResource(R.string.gog_dlc_unresolved)
                                GogDlcKind.INSTALLER -> if (option.installed) null else stringResource(R.string.gog_dlc_installs_on_launch)
                                else -> null
                            },
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = { selectedIds[option.productId] = it },
                        )
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
                            text = stringResource(
                                R.string.steam_install_space,
                                StringUtils.formatBytes(totalDownloadBytes),
                                StringUtils.formatBytes(totalInstallBytes),
                                StringUtils.formatBytes(availableBytes)
                            )
                        )

                        Button(
                            enabled = installButtonEnabled,
                            onClick = {
                                val dlcIds = options
                                    .filter { it.installed || (it.isSelectable() && selectedIds[it.productId] == true) }
                                    .map { it.productId }
                                    .toSet()
                                onInstall(dlcIds)
                            }
                        ) {
                            Text(stringResource(R.string.install))
                        }
                    }
                }
            }
        },
    )
}

private fun GogDlcOption.isSelectable(): Boolean = installable && kind != GogDlcKind.UNKNOWN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GOGContentRow(
    title: String,
    downloadSize: Long,
    installSize: Long,
    installed: Boolean,
    note: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Column {
                Text(text = title)
                if (downloadSize > 0 || installSize > 0) {
                    Text(
                        text = if (installSize > 0) {
                            stringResource(
                                R.string.gog_dlc_sizes,
                                StringUtils.formatBytes(downloadSize),
                                StringUtils.formatBytes(installSize)
                            )
                        } else {
                            stringResource(R.string.gog_dlc_download_size, StringUtils.formatBytes(downloadSize))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (installed) {
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
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable(enabled = enabled) {
            onCheckedChange(!checked)
        }
    )

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}
