package app.gamenative.ui.screen.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.gamenative.Constants
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.StorageUtils
import com.google.android.play.core.splitcompat.SplitCompat
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import app.gamenative.utils.SteamUtils
import com.winlator.container.ContainerData
import com.winlator.xenvironment.ImageFsInstaller
import com.winlator.fexcore.FEXCoreManager
import app.gamenative.ui.screen.library.appscreen.SteamAppScreen
import app.gamenative.ui.screen.library.appscreen.CustomGameAppScreen
import app.gamenative.ui.screen.library.appscreen.GOGAppScreen
import app.gamenative.ui.screen.library.appscreen.EpicAppScreen
import app.gamenative.ui.screen.library.appscreen.AmazonAppScreen
import app.gamenative.ui.data.GameDisplayInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import app.gamenative.service.SteamService.Companion.getAppDirPath
import com.posthog.PostHog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import app.gamenative.PrefManager
import app.gamenative.service.DownloadService
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlin.math.roundToInt
import app.gamenative.enums.PathType
import com.winlator.container.ContainerManager
import app.gamenative.enums.SyncResult
import android.widget.Toast
import app.gamenative.enums.Marker
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.createPinnedShortcut
import kotlinx.coroutines.withContext

// https://partner.steamgames.com/doc/store/assets/libraryassets#4

@Composable
private fun SkeletonText(
    modifier: Modifier = Modifier,
    lines: Int = 1,
    lineHeight: Int = 16
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    Column(modifier = modifier) {
        repeat(lines) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.7f else 1f)
                    .height(lineHeight.dp)
                    .background(
                        color = color,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            if (index < lines - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    libraryItem: LibraryItem,
    onClickPlay: (Boolean) -> Unit,
    onTestGraphics: () -> Unit,
    onBack: () -> Unit,
) {
    // Get the appropriate screen model based on game source
    val screenModel = remember(libraryItem.gameSource) {
        when (libraryItem.gameSource) {
            app.gamenative.data.GameSource.STEAM -> SteamAppScreen()
            app.gamenative.data.GameSource.CUSTOM_GAME -> CustomGameAppScreen()
            app.gamenative.data.GameSource.GOG -> GOGAppScreen()
            app.gamenative.data.GameSource.EPIC -> EpicAppScreen()
            app.gamenative.data.GameSource.AMAZON -> AmazonAppScreen()
        }
    }

    // Render the content using the model
    screenModel.Content(
        libraryItem = libraryItem,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onBack = onBack,
    )
}

/**
 * Formats bytes into a human-readable string (KB, MB, GB).
 * Uses binary units (1024 base).
 */
private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

@Composable
internal fun AppScreenContent(
    modifier: Modifier = Modifier,
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isValidToDownload: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    hasPartialDownload: Boolean,
    isUpdatePending: Boolean,
    downloadInfo: app.gamenative.data.DownloadInfo? = null,
    onDownloadInstallClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onDeleteDownloadClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onBack: () -> Unit = {},
    vararg optionsMenu: AppMenuOption,
) {
    // Determine Wi-Fi connectivity for 'Wi-Fi only' preference
    val context = LocalContext.current
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val wifiConnected = capabilities?.run {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } == true
    val wifiAllowed = !PrefManager.downloadOnWifiOnly || wifiConnected
    val scrollState = rememberScrollState()

    var optionsMenuVisible by remember { mutableStateOf(false) }

    LaunchedEffect(displayInfo.appId) {
        scrollState.animateScrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start,
    ) {
        // Hero Section with Game Image Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        ) {
            // Hero background image
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
                        // Gradient background as fallback
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary
                        ) { }
                    }
                },
                previewPlaceholder = painterResource(R.drawable.testhero),
            )
            } else {
                // Fallback gradient background when no hero image
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary
                ) { }
            }

            // Gradient overlay
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

            // Compatibility status overlay (bottom center)
            // Must be after gradient but before title to ensure visibility
            if (displayInfo.compatibilityMessage != null && displayInfo.compatibilityColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = displayInfo.compatibilityMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(displayInfo.compatibilityColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Back button (top left)
            Box(
                modifier = Modifier
                    .padding(20.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                BackButton(onClick = onBack)
            }

            // Settings/options button (top right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
            ) {
                IconButton(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    onClick = { optionsMenuVisible = !optionsMenuVisible },
                    content = {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    },
                )

                DropdownMenu(
                    expanded = optionsMenuVisible,
                    onDismissRequest = { optionsMenuVisible = false },
                ) {
                    optionsMenu.forEach { menuOption ->
                        DropdownMenuItem(
                            text = { Text(menuOption.optionType.text) },
                            onClick = {
                                menuOption.onClick()
                                optionsMenuVisible = false
                            },
                        )
                    }
                }
            }

            // Game title and subtitle
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
                    text = "${displayInfo.developer} • ${remember(displayInfo.releaseDate) {
                        if (displayInfo.releaseDate > 0) {
                            SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                        } else {
                            ""
                        }
                    }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }

        // Content section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pause/Resume and Delete when downloading or paused
                // Use hasPartialDownload from BaseAppScreen (implemented per game source)
                // Disable resume when Wi-Fi only is enabled and there's no Wi-Fi
                val isResume = !isDownloading && hasPartialDownload
                val pauseResumeEnabled = if (isResume) wifiAllowed else true
                if (isDownloading || hasPartialDownload) {
                    // Pause or Resume
                    Button(
                        enabled = pauseResumeEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onPauseResumeClick,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = if (isDownloading) stringResource(R.string.pause_download)
                                   else stringResource(R.string.resume_download),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    // Delete (Cancel) download data
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDeleteDownloadClick,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Text(stringResource(R.string.delete_app), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    }
                } else {
                    // Disable install when Wi-Fi only is enabled and there's no Wi-Fi
                    val isInstall = !isInstalled
                    val installEnabled = if (isInstall) wifiAllowed && hasInternet else true
                    // For installed games, button should always be enabled (regardless of isValidToDownload)
                    // For games that need installation, check isValidToDownload
                    val buttonEnabled = if (isInstalled) {
                        installEnabled // Installed games can always be played
                    } else {
                        installEnabled && isValidToDownload // Only check download validity when not installed
                    }
                    // Install or Play button
                    Button(
                        enabled = buttonEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDownloadInstallClick()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        val text = when {
                            isInstalled -> stringResource(R.string.run_app)
                            !hasInternet -> stringResource(R.string.library_need_internet)
                            !wifiConnected && PrefManager.downloadOnWifiOnly -> stringResource(R.string.library_wifi_only_enabled)
                            else -> stringResource(R.string.install_app)
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    // Uninstall/Delete button if already installed
                    // This is shared functionality - all game types show delete button when installed
                    // The action is handled by onDeleteDownloadClick which is implemented per game source
                    if (isInstalled) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onDeleteDownloadClick() },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            val buttonText = if (ContainerUtils.extractGameSourceFromContainerId(displayInfo.appId) == app.gamenative.data.GameSource.CUSTOM_GAME) {
                                stringResource(R.string.remove)
                            } else {
                                stringResource(R.string.uninstall)
                            }
                            Text(
                                text = buttonText,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Download progress section
            if (isDownloading) {
                // downloadInfo passed from BaseAppScreen based on game source
                val statusMessageFlow = downloadInfo?.getStatusMessageFlow()
                val statusMessageState = statusMessageFlow?.collectAsState(initial = statusMessageFlow.value)
                val statusMessage = statusMessageState?.value

                // Use DownloadInfo's byte-based ETA when available for more stable estimates
                val timeLeftText = remember(displayInfo.appId, downloadProgress, downloadInfo, statusMessage) {
                    val etaMs = downloadInfo?.getEstimatedTimeRemaining()
                    if (etaMs != null && etaMs > 0L) {
                        val totalSeconds = etaMs / 1000
                        val minutesLeft = totalSeconds / 60
                        val secondsPart = totalSeconds % 60
                        "${minutesLeft}m ${secondsPart}s left"
                    } else if (downloadProgress in 0f..1f && downloadProgress < 1f) {
                        val statusText = statusMessage?.takeUnless { it.isBlank() }
                        statusText ?: "Calculating..."
                    } else {
                        ""
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.installation_progress),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${(downloadProgress * 100f).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show download size and ETA
                    val downloadingText = stringResource(R.string.downloading)
                    val sizeText = remember(displayInfo.gameId, downloadProgress, downloadInfo) {
                        val (bytesDone, bytesTotal) = downloadInfo?.getBytesProgress() ?: (0L to 0L)
                        if (bytesTotal > 0L) {
                            "${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}"
                        } else if (bytesDone > 0L) {
                            formatBytes(bytesDone)
                        } else {
                            downloadingText
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeLeftText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            if (isUpdatePending) {
                // Update banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0x1A06B6D4),
                                    Color(0x1AA21CAF)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(1000f, 1000f)
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("↑", color = MaterialTheme.colorScheme.onTertiary, fontSize = 14.sp)
                            }
                            Text(
                                stringResource(R.string.update_available),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onUpdateClick,
                            modifier = Modifier.align(Alignment.Start),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Text(stringResource(R.string.update_now), color = MaterialTheme.colorScheme.onTertiary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Game information card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Colored top border
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                    )

                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.game_information),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            // Setting a fixed height to avoid nested scrolling issues
                            modifier = Modifier.height(220.dp)
                        ) {
                            // Status item
                            item {
                                Column {
                                    Text(
                                        text = stringResource(R.string.status),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        shape = CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = when {
                                                    isInstalled -> stringResource(R.string.installed)
                                                    isDownloading -> stringResource(R.string.installing)
                                                    else -> stringResource(R.string.not_installed)
                                                },
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                }
                            }

                            // Size item
                            item {
                                Column {
                                    Text(
                                        text = stringResource(R.string.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    // Show size from displayInfo
                                    Text(
                                        text = when {
                                            isInstalled && displayInfo.sizeOnDisk != null -> displayInfo.sizeOnDisk
                                            !isInstalled && displayInfo.sizeFromStore != null -> displayInfo.sizeFromStore
                                            else -> "Unknown"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }

                            // Location item
                            if (isInstalled) {
                                item (span = { GridItemSpan(maxLineSpan) }) {

                                    Column {
                                        Text(
                                            text = stringResource(R.string.location),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                                        ) {
                                            Text(
                                                text = displayInfo.installLocation ?: "Unknown",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // Developer item
                            item {
                                Column {
                                    Text(
                                        text = stringResource(R.string.developer),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = displayInfo.developer,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }

                            // Release Date item
                            item {
                                Column {
                                    Text(
                                        text = stringResource(R.string.release_date),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = remember(displayInfo.releaseDate) {
                                            if (displayInfo.releaseDate > 0) {
                                                val date = Date(displayInfo.releaseDate * 1000)
                                            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
                                            } else {
                                                "Unknown"
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
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

@Composable
internal fun GameMigrationDialog(
    progress: Float,
    currentFile: String,
    movedFiles: Int,
    totalFiles: Int,
) {
    AlertDialog(
        onDismissRequest = {
            // We don't allow dismissal during move.
        },
        icon = { Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null) },
        title = { Text(text = stringResource(R.string.moving_files)) },
        text = {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.library_file_count, movedFiles + 1, totalFiles),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentFile,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { progress },
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
    )
}


/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    device = "spec:width=1920px,height=1080px,dpi=440",
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
) // Odin2 Mini
@Composable
private fun Preview_AppScreen() {
    val context = LocalContext.current
    PrefManager.init(context)
    val intent = Intent(context, SteamService::class.java)
    context.startForegroundService(intent)
    var isDownloading by remember { mutableStateOf(false) }
    val fakeApp = fakeAppInfo(1)
    val displayInfo = GameDisplayInfo(
        name = fakeApp.name,
        developer = fakeApp.developer,
        releaseDate = fakeApp.releaseDate,
        heroImageUrl = fakeApp.getHeroUrl(),
        iconUrl = fakeApp.iconUrl,
        gameId = fakeApp.id,
        appId = "STEAM_${fakeApp.id}",
        installLocation = null,
        sizeOnDisk = null,
        sizeFromStore = null,
        lastPlayedText = null,
        playtimeText = null,
    )
    PluviaTheme {
        Surface {
            AppScreenContent(
                displayInfo = displayInfo,
                isInstalled = false,
                isValidToDownload = true,
                isDownloading = isDownloading,
                downloadProgress = .50f,
                hasPartialDownload = false,
                isUpdatePending = false,
                downloadInfo = null,
                onDownloadInstallClick = { isDownloading = !isDownloading },
                onPauseResumeClick = { },
                onDeleteDownloadClick = { },
                onUpdateClick = { },
                optionsMenu = AppOptionMenuType.entries.map {
                    AppMenuOption(
                        optionType = it,
                        onClick = { },
                    )
                }.toTypedArray(),
            )
        }
    }
}
