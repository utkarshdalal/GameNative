@file:OptIn(ExperimentalFoundationApi::class)

package app.gamenative.ui.screen.library

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.gamenative.ui.component.GradientProgressBar
import app.gamenative.ui.component.InfoCard
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.Achievement
import app.gamenative.ui.screen.library.components.ambient.AmbientDownloadOverlay
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.NetworkMonitor
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.service.SteamService
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.InfoCard
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.ScreenshotsPreviewStrip
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.DownloadDisplayDetails
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.screen.library.appscreen.AmazonAppScreen
import app.gamenative.ui.screen.library.appscreen.CustomGameAppScreen
import app.gamenative.ui.screen.library.appscreen.EpicAppScreen
import app.gamenative.ui.screen.library.appscreen.GOGAppScreen
import app.gamenative.ui.screen.library.appscreen.SteamAppScreen
import app.gamenative.ui.screen.library.components.GameOptionsPanel
import app.gamenative.utils.HltbService
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

// https://partner.steamgames.com/doc/store/assets/libraryassets#4

@Composable
private fun SkeletonText(
    modifier: Modifier = Modifier,
    lines: Int = 1,
    lineHeight: Int = 16,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
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
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
            if (index < lines - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInstalled: Boolean = false,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onProgressBarPositioned: ((Rect) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "primaryActionScale",
    )

    val buttonColor = when {
        isDownloading -> PluviaTheme.colors.statusDownloading
        isInstalled -> PluviaTheme.colors.statusInstalled
        else -> PluviaTheme.colors.statusAvailable
    }

    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) buttonColor else buttonColor.copy(alpha = 0.5f),
            )
            .focusRing(interactionSource, RoundedCornerShape(8.dp), width = 2.dp)
            .focusRequester(focusRequester)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isDownloading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .width(80.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .then(
                            if (onProgressBarPositioned != null) {
                                Modifier.onGloballyPositioned { coordinates ->
                                    onProgressBarPositioned(coordinates.boundsInRoot())
                                }
                            } else {
                                Modifier
                            },
                        ),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (isInstalled) Icons.Default.PlayArrow else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Icon-only action button for the overlay action bar
 */
@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "actionIconScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) {
                    Color.White.copy(alpha = 0.2f)
                } else {
                    Color.White.copy(alpha = 0.1f)
                },
            )
            .focusRing(interactionSource, RoundedCornerShape(8.dp), width = 2.dp)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun HltbInfoBar(
    stats: HltbService.Stats,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val items = listOf(
        stringResource(R.string.hltb_main_story) to stats.mainHours,
        stringResource(R.string.hltb_main_plus_extras) to stats.mainPlusHours,
        stringResource(R.string.hltb_completionist) to stats.completeHours,
        stringResource(R.string.hltb_all_styles) to stats.allStylesHours,
    )
    val canOpenHltb = stats.gameId > 0
    val hltbShape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(hltbShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(interaction, hltbShape)
            .clickable(
                interactionSource = interaction,
                // No ripple; focus shown by the ring.
                indication = null,
                enabled = canOpenHltb,
            ) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("${HltbService.GAME_URL}${stats.gameId}")),
                    )
                } catch (e: ActivityNotFoundException) {
                    Timber.tag("HLTB").w(e, "No handler for HLTB game URL")
                }
            }
            .padding(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.hltb_section_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (canOpenHltb) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = maxWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { (label, hours) ->
                    Column(
                        modifier = Modifier
                            .widthIn(min = 48.dp)
                            .padding(horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (hours == HltbService.UNKNOWN_HOURS) {
                                HltbService.UNKNOWN_HOURS
                            } else {
                                stringResource(R.string.hltb_hours_value, hours)
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
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
    onPlayWithDiagnostics: () -> Unit,
    onAiDebugRun: () -> Unit,
    onBack: () -> Unit,
    onViewScreenshots: () -> Unit = {},
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
        onPlayWithDiagnostics = onPlayWithDiagnostics,
        onAiDebugRun = onAiDebugRun,
        onBack = onBack,
        onViewScreenshots = onViewScreenshots,
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

internal data class ImmersiveModeUiState(
    val isSupported: Boolean = false,
    val isEnabled: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
)

@Composable
internal fun AppScreenContent(
    modifier: Modifier = Modifier,
    displayInfo: GameDisplayInfo,
    downloadDisplayDetails: DownloadDisplayDetails,
    downloadInfo: app.gamenative.data.DownloadInfo? = null,
    onDownloadInstallClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onDeleteDownloadClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onBack: () -> Unit = {},
    achievements: List<Achievement>? = null,
    onViewScreenshots: () -> Unit = {},
    optionsMenu: List<AppMenuOption>,
    dialogOpen: Boolean = false,
    immersiveMode: ImmersiveModeUiState = ImmersiveModeUiState(),
) {
    // Unpacked so the body below is unchanged; bundling the params avoids a Compose VerifyError.
    val isInstalled = downloadDisplayDetails.isInstalled
    val isValidToDownload = downloadDisplayDetails.isValidToDownload
    val isDownloading = downloadDisplayDetails.isDownloading
    val downloadProgress = downloadDisplayDetails.downloadProgress
    val hasPartialDownload = downloadDisplayDetails.hasPartialDownload
    val hasLeftoverInstall = downloadDisplayDetails.hasLeftoverInstall
    val isUpdatePending = downloadDisplayDetails.isUpdatePending
    val context = LocalContext.current
    // reactive — recomposes when network state changes
    val hasInternet by NetworkMonitor.hasInternet.collectAsState()
    val hasWifiOrEthernet by NetworkMonitor.hasWifiOrEthernet.collectAsState()
    val downloadAllowed = !PrefManager.downloadOnWifiOnly || hasWifiOrEthernet
    val scrollState = rememberScrollState()

    var optionsMenuVisible by remember { mutableStateOf(false) }

    // Track the original progress bar bounds for ambient mode morph animation
    var progressBarBounds by remember { mutableStateOf<Rect?>(null) }
    var ambientInteractionCounter by remember { mutableStateOf(0) }

    // Focus requesters for gamepad navigation
    val playButtonFocusRequester = remember { FocusRequester() }

    // Calculate parallax offset based on scroll
    val parallaxOffset = scrollState.value * 0.5f

    var downloadTimeLeftText by remember { mutableStateOf("")}

    val progressListener: (Float) -> Unit = {
        val downloadStatusMessage = downloadInfo?.getCurrentStatusMessage()

        downloadTimeLeftText = run {
            val etaMs = downloadInfo?.getEstimatedTimeRemaining()
            if (etaMs != null && etaMs > 0L) {
                val totalSeconds = etaMs / 1000
                val minutesLeft = totalSeconds / 60
                val secondsPart = totalSeconds % 60
                "${minutesLeft}m ${secondsPart}s left"
            } else if (isDownloading && downloadProgress >= 1f) {
                "Unpacking..."
            } else if (downloadProgress in 0f..1f && downloadProgress < 1f) {
                downloadStatusMessage?.takeUnless { it.isBlank() } ?: ""
            } else {
                ""
            }
        }
    }

    LaunchedEffect(displayInfo.appId) {
        scrollState.animateScrollTo(0)
    }

    LaunchedEffect(Unit) {
        playButtonFocusRequester.requestFocus()
    }

    LaunchedEffect(downloadInfo) {
        downloadInfo?.addProgressListener(progressListener)
    }

    DisposableEffect(Unit) {
        onDispose {
            downloadInfo?.removeProgressListener(progressListener)
        }
    }

    // Restore focus when options menu, dialogs
    LaunchedEffect(optionsMenuVisible, dialogOpen) {
        if (!optionsMenuVisible && !dialogOpen) {
            kotlinx.coroutines.delay(100) // Brief delay for menu/dialog animation
            try {
                playButtonFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // FocusRequester not attached
            }
        }
    }

    // Button state calculations (needed by key event handler)
    val isResume = !isDownloading && hasPartialDownload
    val pauseResumeEnabled = if (isResume) downloadAllowed else true
    val isInstall = !isInstalled
    val installEnabled = if (isInstall) downloadAllowed && hasInternet else true
    val buttonEnabled = if (isInstalled) {
        installEnabled
    } else {
        installEnabled && isValidToDownload
    }
    val startActionEnabled = if (isDownloading || hasPartialDownload) {
        pauseResumeEnabled
    } else {
        buttonEnabled
    }
    val onStartAction = {
        if (isDownloading || hasPartialDownload) {
            onPauseResumeClick()
        } else {
            onDownloadInstallClick()
        }
    }

    val downloadingLabel = stringResource(R.string.downloading)
    val downloadSizeText = remember(displayInfo.gameId, downloadProgress, downloadInfo) {
        val (bytesDone, bytesTotal) = downloadInfo?.getBytesProgress() ?: (0L to 0L)
        if (bytesTotal > 0L) {
            "${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}"
        } else if (bytesDone > 0L) {
            formatBytes(bytesDone)
        } else {
            downloadingLabel
        }
    }

    // Handle gamepad button presses
    val handleKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // SELECT button - open options menu
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    optionsMenuVisible = true
                    true
                }

                // START button - primary action (play/download/pause)
                KeyEvent.KEYCODE_BUTTON_START -> {
                    if (!optionsMenuVisible && startActionEnabled) {
                        onStartAction()
                    }
                    true
                }

                // B button - back
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (optionsMenuVisible) {
                        optionsMenuVisible = false
                    } else {
                        onBack()
                    }
                    true
                }

                else -> false
            }
        } else {
            false
        }
    }

    // Handle back press when options panel is open
    BackHandler(enabled = optionsMenuVisible) {
        optionsMenuVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                        if (pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) {
                            ambientInteractionCounter++
                        }
                    }
                }
            }
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    ambientInteractionCounter++
                }
                handleKeyEvent(it.nativeKeyEvent)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // Hero Section (Parallax)
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                // Hero background image
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationY = parallaxOffset
                        },
                ) {
                    if (displayInfo.heroImageUrl != null) {
                        CoilImage(
                            modifier = Modifier.fillMaxSize(),
                            imageModel = { displayInfo.heroImageUrl },
                            imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                            loading = { LoadingScreen() },
                            failure = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                ),
                                            ),
                                        ),
                                )
                            },
                            previewPlaceholder = painterResource(R.drawable.testhero),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                    ),
                                ),
                        )
                    }
                }

                // Gradient overlay (bottom, for title/action bar)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.3f),
                                    Color.Black.copy(alpha = 0.85f),
                                ),
                                startY = 0f,
                                endY = Float.POSITIVE_INFINITY,
                            ),
                        ),
                )

                // Top gradient overlay (so back button is visible on light/white images)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Black.copy(alpha = 0.15f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = Float.POSITIVE_INFINITY,
                            ),
                        ),
                )

                // Back button (top left).
                // The hero image is intentionally drawn full-bleed through the status bar
                // and any display cutout (notch / hole-punch / side cutout). The button
                // itself, however, has to stay tappable, so it's pushed inwards by whichever
                // is larger of the status bar inset or the cutout inset on each affected
                // edge before the visual 16dp padding is applied.
                ActionIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    onClick = onBack,
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(16.dp),
                )

                // Bottom overlay with title and action bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 128.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
                ) {
                    // Game title
                    Text(
                        text = displayInfo.name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.6f),
                                offset = Offset(0f, 2f),
                                blurRadius = 8f,
                            ),
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Developer and year
                    val releaseYear = remember(displayInfo.releaseDate) {
                        if (displayInfo.releaseDate > 0) {
                            SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                        } else {
                            ""
                        }
                    }
                    Text(
                        text = "${displayInfo.developer} • $releaseYear",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Integrated action bar - overlaid on hero
                    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(12.dp),
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Primary action button (left-aligned)
                        if (isDownloading || hasPartialDownload) {
                            PrimaryActionButton(
                                text = if (isDownloading) {
                                    stringResource(R.string.pause_download)
                                } else {
                                    stringResource(R.string.resume_download)
                                },
                                onClick = onPauseResumeClick,
                                enabled = pauseResumeEnabled,
                                isInstalled = false,
                                isDownloading = isDownloading,
                                downloadProgress = downloadProgress,
                                focusRequester = playButtonFocusRequester,
                                onProgressBarPositioned = { progressBarBounds = it },
                            )
                        } else {
                            val text = when {
                                isInstalled -> stringResource(R.string.run_app)
                                !hasInternet -> stringResource(R.string.library_need_internet)
                                !hasWifiOrEthernet && PrefManager.downloadOnWifiOnly -> stringResource(R.string.library_wifi_only_enabled)
                                else -> stringResource(R.string.install_app)
                            }
                            PrimaryActionButton(
                                text = text,
                                onClick = onDownloadInstallClick,
                                enabled = buttonEnabled,
                                isInstalled = isInstalled,
                                focusRequester = playButtonFocusRequester,
                            )
                        }

                        // Download size / ETA text — inline only in landscape
                        if (isDownloading && !isPortrait) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (downloadSizeText.isNotEmpty()) {
                                    Text(
                                        text = downloadSizeText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (downloadTimeLeftText.isNotEmpty()) {
                                    Text(
                                        text = downloadTimeLeftText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.65f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // Secondary action icons (right-aligned)
                        ActionIconButton(
                            icon = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.options),
                            onClick = { optionsMenuVisible = true },
                        )

                        if (isInstalled || hasPartialDownload || hasLeftoverInstall) {
                            ActionIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = if (isInstalled || hasLeftoverInstall) stringResource(R.string.uninstall) else stringResource(R.string.delete_app),
                                onClick = onDeleteDownloadClick,
                            )
                        }
                    }

                    if (immersiveMode.isSupported && isInstalled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clickable { immersiveMode.onChange(!immersiveMode.isEnabled) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = immersiveMode.isEnabled,
                                onCheckedChange = immersiveMode.onChange,
                                colors = CheckboxDefaults.colors(
                                    uncheckedColor = Color.White.copy(alpha = 0.7f),
                                ),
                            )
                            Text(
                                text = stringResource(R.string.launch_immersive_mode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }

                    if (isDownloading && isPortrait) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (downloadSizeText.isNotEmpty()) {
                                Text(
                                    text = downloadSizeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                )
                            }
                            if (downloadTimeLeftText.isNotEmpty()) {
                                Text(
                                    text = downloadTimeLeftText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    }

                    // Compatibility status (if applicable)
                    if (displayInfo.compatibilityMessage != null && displayInfo.compatibilityColor != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = displayInfo.compatibilityMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(displayInfo.compatibilityColor),
                        )
                    }
                }
            }

            // Content section below hero with solid background
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(20.dp),
            ) {
                // Update available banner
                if (isUpdatePending) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.update_available),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Button(
                                onClick = onUpdateClick,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(stringResource(R.string.update_now))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Game information section
                Text(
                    text = stringResource(R.string.game_information),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Info cards in 2-column grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val statusText = when {
                        isInstalled -> stringResource(R.string.installed)
                        isDownloading -> stringResource(R.string.installing)
                        else -> stringResource(R.string.not_installed)
                    }
                    val statusColor = when {
                        isInstalled -> PluviaTheme.colors.statusInstalled
                        isDownloading -> MaterialTheme.colorScheme.tertiary
                        else -> null
                    }
                    InfoCard(
                        label = stringResource(R.string.status),
                        value = statusText,
                        statusColor = statusColor,
                        isCompact = true,
                        modifier = Modifier.weight(1f),
                        focusableForNavigation = true,
                    )
                    InfoCard(
                        label = stringResource(R.string.size),
                        value = when {
                            isInstalled && displayInfo.sizeOnDisk != null -> displayInfo.sizeOnDisk
                            !isInstalled && displayInfo.sizeFromStore != null -> displayInfo.sizeFromStore
                            else -> stringResource(R.string.library_compatibility_unknown)
                        },
                        isCompact = true,
                        modifier = Modifier.weight(1f),
                        focusableForNavigation = true,
                    )
                }

                displayInfo.hltbStats?.let { hltb ->
                    Spacer(modifier = Modifier.height(10.dp))
                    HltbInfoBar(hltb)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoCard(
                        label = stringResource(R.string.developer),
                        value = displayInfo.developer,
                        isCompact = true,
                        modifier = Modifier.weight(1f),
                        focusableForNavigation = true,
                    )
                    InfoCard(
                        label = stringResource(R.string.release_date),
                        value = remember(displayInfo.releaseDate) {
                            if (displayInfo.releaseDate > 0) {
                                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                    .format(Date(displayInfo.releaseDate * 1000))
                            } else {
                                context.getString(R.string.library_compatibility_unknown)
                            }
                        },
                        isCompact = true,
                        modifier = Modifier.weight(1f),
                        focusableForNavigation = true,
                    )
                }

                // Install location (when installed)
                if (isInstalled && displayInfo.installLocation != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    InfoCard(
                        label = stringResource(R.string.location),
                        value = displayInfo.installLocation,
                        isCompact = true,
                        modifier = Modifier.fillMaxWidth(),
                        focusableForNavigation = true,
                    )
                }

                // Play time and last played
                if (displayInfo.playtimeText != null || displayInfo.lastPlayedText != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (displayInfo.playtimeText != null) {
                            InfoCard(
                                label = stringResource(R.string.play_time),
                                value = displayInfo.playtimeText,
                                isCompact = true,
                                modifier = Modifier.weight(1f),
                                focusableForNavigation = true,
                            )
                        }
                        if (displayInfo.lastPlayedText != null) {
                            InfoCard(
                                label = stringResource(R.string.last_played),
                                value = displayInfo.lastPlayedText,
                                isCompact = true,
                                modifier = Modifier.weight(1f),
                                focusableForNavigation = true,
                            )
                        }
                    }
                }

                // Achievements
                if (!achievements.isNullOrEmpty()) {
                    AchievementsRow(achievements = achievements)
                }
                // Screenshots preview (hidden when the game has none)
                Spacer(modifier = Modifier.height(10.dp))
                ScreenshotsPreviewStrip(
                    appId = displayInfo.appId,
                    onClick = onViewScreenshots,
                )

            }
        }

        GamepadActionBar(
            actions = listOf(
                if (isInstalled) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.run_app,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else if (isDownloading) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.pause_download,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else if (hasPartialDownload) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.resume_download,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.install_app,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                },
                GamepadAction(
                    button = GamepadButton.SELECT,
                    labelResId = R.string.options,
                    onClick = { optionsMenuVisible = true },
                ),
                GamepadAction(
                    button = GamepadButton.B,
                    labelResId = R.string.back,
                    onClick = onBack,
                ),
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = !optionsMenuVisible,
        )

        // Options panel - slides in from right
        GameOptionsPanel(
            isOpen = optionsMenuVisible,
            onDismiss = { optionsMenuVisible = false },
            options = optionsMenu.toList(),
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        // Ambient mode during downloads
        if (isDownloading) {
            AmbientDownloadOverlay(
                gameName = displayInfo.name,
                downloadProgress = downloadProgress,
                iconUrl = displayInfo.iconUrl,
                originBounds = progressBarBounds,
                userInteractionCounter = ambientInteractionCounter,
            )
        }
    }
}

@Composable
fun GameMigrationDialog(
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


// Shared grayscale filter for locked achievement icons.
private val grayMatrix = ColorMatrix().apply { setToSaturation(0f) }

// Steam leaves some localized names blank.
private val Achievement.label: String
    get() = displayName.ifEmpty { name ?: "" }

private fun Achievement.previewIconUrl(): String? =
    if (isUnlocked) icon.ifEmpty { iconGray } else iconGray ?: icon.ifEmpty { null }

// A still-locked secret achievement, whose details Steam keeps hidden.
private val Achievement.isHiddenLocked: Boolean
    get() = hidden && !isUnlocked

// Achievement icon, grayed while locked. Pass masked = true to hide the art of a secret achievement.
@Composable
private fun AchievementIcon(ach: Achievement, size: Dp, corner: Dp, masked: Boolean = false) {
    val box = Modifier
        .size(size)
        .clip(RoundedCornerShape(corner))
        .background(MaterialTheme.colorScheme.surfaceContainer)
    if (masked) {
        Box(box, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    } else {
        val iconUrl = ach.previewIconUrl()
        CoilImage(
            imageModel = { iconUrl ?: "" },
            imageOptions = ImageOptions(
                contentScale = ContentScale.Crop,
                contentDescription = ach.label,
                colorFilter = if (ach.isUnlocked) null else ColorFilter.colorMatrix(grayMatrix),
            ),
            modifier = box,
        )
    }
}

// Progress bar plus "current / max" for stat-linked achievements.
@Composable
private fun AchievementProgressBar(current: Float, max: Float, textStyle: TextStyle) {
    val fraction = if (max > 0f) (current / max).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradientProgressBar(
            progress = fraction,
            modifier = Modifier.weight(1f),
            height = 5.dp,
        )
        Text(
            text = "${current.toInt()} / ${max.toInt()}",
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// Focusable, clickable achievement row.
@Composable
private fun AchievementRow(ach: Achievement, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusRing(interactionSource, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AchievementIcon(ach = ach, size = 40.dp, corner = 6.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ach.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ach.description.isNotEmpty()) {
                    Text(
                        text = ach.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val unlockedAt = ach.getFormattedUnlockDateTime()
                if (ach.isUnlocked && unlockedAt != null) {
                    Text(
                        text = stringResource(R.string.achievements_unlocked_at, unlockedAt.first, unlockedAt.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = PluviaTheme.colors.statusInstalled,
                    )
                } else if (ach.hasProgress) {
                    AchievementProgressBar(
                        current = ach.progressCurrent ?: 0f,
                        max = ach.progressMax ?: 0f,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

// Collapsed row standing in for still-locked secret achievements.
@Composable
private fun HiddenAchievementsSummary(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusRing(interactionSource, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.achievements_hidden_remaining, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.achievements_hidden_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Full details for one achievement.
@Composable
private fun AchievementDetailDialog(ach: Achievement, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        icon = {
            AchievementIcon(ach = ach, size = 64.dp, corner = 10.dp)
        },
        title = {
            Text(
                text = ach.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ach.description.isNotEmpty()) {
                    Text(
                        text = ach.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val unlockedAt = ach.getFormattedUnlockDateTime()
                if (ach.isUnlocked && unlockedAt != null) {
                    Text(
                        text = stringResource(R.string.achievements_unlocked_at, unlockedAt.first, unlockedAt.second),
                        style = MaterialTheme.typography.labelMedium,
                        color = PluviaTheme.colors.statusInstalled,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (ach.hasProgress) {
                    AchievementProgressBar(
                        current = ach.progressCurrent ?: 0f,
                        max = ach.progressMax ?: 0f,
                        textStyle = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.achievements_locked),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

@Composable
private fun AchievementsRow(
    achievements: List<Achievement>,
) {
    val unlockedCount = achievements.count { it.isUnlocked }
    val totalCount = achievements.size
    var showDialog by remember { mutableStateOf(false) }

    val sortedAchievements = remember(achievements) {
        achievements.sortedWith(
            compareByDescending<Achievement> { it.isUnlocked }
                .thenByDescending { it.unlockTimestamp },
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    InfoCard(
        label = stringResource(R.string.achievements),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 36.dp),
        isCompact = true,
        onClick = { showDialog = true },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Fit as many icons as the width allows.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val minIconSize = 48.dp
                val spacing = 8.dp
                val total = sortedAchievements.size
                val slotsByWidth = ((maxWidth + spacing) / (minIconSize + spacing))
                    .toInt()
                    .coerceAtLeast(1)
                val slots = slotsByWidth.coerceAtMost(total)
                // A full row divides the width evenly so the strip ends flush with the bar below.
                val iconSize = if (total >= slotsByWidth) {
                    (maxWidth - spacing * (slots - 1)) / slots
                } else {
                    minIconSize
                }
                // Reserve the last slot for a "+N" stack when more achievements exist than fit.
                val showStack = total > slots
                val iconCount = if (showStack) (slots - 1).coerceAtLeast(0) else slots
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    sortedAchievements.take(iconCount).forEach { ach ->
                        AchievementIcon(
                            ach = ach,
                            size = iconSize,
                            corner = 8.dp,
                            masked = ach.isHiddenLocked,
                        )
                    }
                    if (showStack) {
                        val next = sortedAchievements[iconCount]
                        Box(
                            modifier = Modifier
                                .size(iconSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!next.isHiddenLocked) {
                                val nextUrl = next.previewIconUrl()
                                CoilImage(
                                    imageModel = { nextUrl ?: "" },
                                    imageOptions = ImageOptions(
                                        contentScale = ContentScale.Crop,
                                        contentDescription = next.label,
                                        colorFilter = ColorFilter.colorMatrix(grayMatrix),
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+${total - iconCount}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GradientProgressBar(
                    progress = if (totalCount > 0) unlockedCount.toFloat() / totalCount else 0f,
                    modifier = Modifier.weight(1f),
                    height = 8.dp,
                )
                // Floors, so only a full set reads as 100%.
                val percent = if (totalCount > 0) unlockedCount * 100 / totalCount else 0
                Text(
                    text = stringResource(R.string.achievements_progress_count, unlockedCount, totalCount, percent),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                // Give them a star for getting 100% completion
                if (totalCount >= 1 && unlockedCount == totalCount) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.achievements_complete),
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (showDialog) {
        AchievementsDialog(
            achievements = sortedAchievements,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun AchievementsDialog(
    achievements: List<Achievement>,
    onDismiss: () -> Unit,
) {
    // Dialog destinations don't animate; fade/slide the content in and play the exit before
    // dismissing, matching the screenshot gallery.
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState) onDismiss()
    }
    val dismiss = { visibleState.targetState = false }

    // Secret achievements collapse into one row until revealed. Reveal is session-only.
    val (hiddenLocked, visibleAchievements) = remember(achievements) {
        achievements.partition { it.isHiddenLocked }
    }
    var revealHidden by remember { mutableStateOf(false) }
    var showRevealConfirm by remember { mutableStateOf(false) }
    var detailAchievement by remember { mutableStateOf<Achievement?>(null) }
    // Keep focus on the freshly revealed achievements instead of jumping to the list top.
    val revealedFocusRequester = remember { FocusRequester() }
    LaunchedEffect(revealHidden) {
        if (revealHidden) {
            repeat(5) {
                try {
                    if (revealedFocusRequester.requestFocus()) return@LaunchedEffect
                } catch (_: IllegalStateException) {
                }
                delay(32)
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Drop the window dim so the entrance animation has no scrim flash.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(200)) +
                slideInVertically(animationSpec = tween(200)) { it / 12 },
            exit = fadeOut(animationSpec = tween(150)) +
                slideOutVertically(animationSpec = tween(150)) { it / 12 },
        ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .navigationBarsPadding(),
            ) {
                // Header: back + title, mirroring the screenshot gallery.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BackButton(onClick = dismiss)
                    Text(
                        text = stringResource(R.string.achievements_all_title),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleAchievements) { ach ->
                        AchievementRow(ach) { detailAchievement = ach }
                    }
                    if (hiddenLocked.isNotEmpty()) {
                        if (revealHidden) {
                            itemsIndexed(hiddenLocked) { index, ach ->
                                AchievementRow(
                                    ach = ach,
                                    focusRequester = if (index == 0) revealedFocusRequester else null,
                                ) { detailAchievement = ach }
                            }
                        } else {
                            item {
                                HiddenAchievementsSummary(count = hiddenLocked.size) {
                                    showRevealConfirm = true
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showRevealConfirm) {
        AlertDialog(
            onDismissRequest = { showRevealConfirm = false },
            title = { Text(stringResource(R.string.achievements_reveal_title)) },
            text = { Text(stringResource(R.string.achievements_reveal_message)) },
            confirmButton = {
                TextButton(onClick = {
                    revealHidden = true
                    showRevealConfirm = false
                }) { Text(stringResource(R.string.achievements_reveal_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevealConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    detailAchievement?.let { ach ->
        AchievementDetailDialog(ach) { detailAchievement = null }
    }
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
                downloadDisplayDetails = DownloadDisplayDetails(
                    isInstalled = false,
                    isValidToDownload = true,
                    isDownloading = isDownloading,
                    downloadProgress = .50f,
                    hasPartialDownload = false,
                    isUpdatePending = false,
                ),
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
                },
            )
        }
    }
}
