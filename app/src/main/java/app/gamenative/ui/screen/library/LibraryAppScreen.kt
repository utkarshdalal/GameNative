package app.gamenative.ui.screen.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.service.GameManagerService
import app.gamenative.service.GOG.GOGService
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirPath
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.internal.LocalGameManagerService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.StorageUtils
import com.google.android.play.core.splitcompat.SplitCompat
import com.posthog.PostHog
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFsInstaller
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    libraryItem: LibraryItem,
    onClickPlay: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gameId = libraryItem.gameId
    val appId = libraryItem.appId

    var downloadInfo by remember(appId) {
        mutableStateOf(GameManagerService.getDownloadInfo(libraryItem))
    }
    var downloadProgress by remember(appId) {
        mutableFloatStateOf(downloadInfo?.getProgress() ?: 0f)
    }
    var isInstalled by remember(appId) {
        mutableStateOf(GameManagerService.isGameInstalled(context, libraryItem))
    }

    val isValidToDownload by remember(appId) {
        mutableStateOf(GameManagerService.isValidToDownload(libraryItem))
    }

    val isDownloading: () -> Boolean = { downloadInfo != null && downloadProgress < 1f }

    var loadingDialogVisible by rememberSaveable { mutableStateOf(false) }
    var loadingProgress by rememberSaveable { mutableFloatStateOf(0f) }

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }

    var showConfigDialog by rememberSaveable { mutableStateOf(false) }

    var containerData by rememberSaveable(stateSaver = ContainerData.Saver) {
        mutableStateOf(ContainerData())
    }

    val showEditConfigDialog: () -> Unit = {
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        containerData = ContainerUtils.toContainerData(container)
        showConfigDialog = true
    }

    DisposableEffect(downloadInfo) {
        val onDownloadProgress: (Float) -> Unit = {
            if (it >= 1f) {
                // Download completed - update markers
                val appDirPath = GameManagerService.getAppDirPath(libraryItem.appId)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                
                isInstalled = GameManagerService.isGameInstalled(context, libraryItem)
                downloadInfo = null
                isInstalled = true
            }
            downloadProgress = it
        }

        downloadInfo?.addProgressListener(onDownloadProgress)

        onDispose {
            downloadInfo?.removeProgressListener(onDownloadProgress)
        }
    }

    LaunchedEffect(appId) {
        Timber.d("Selected app ${libraryItem.appId} (${libraryItem.gameSource})")
    }

    val oldGamesDirectory by remember {
        val path = Paths.get(context.dataDir.path, "Steam")
        mutableStateOf(path)
    }
    var showMoveDialog by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf("") }
    var total by remember { mutableIntStateOf(0) }
    var moved by remember { mutableIntStateOf(0) }

    val permissionMovingExternalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permission ->
            scope.launch {
                showMoveDialog = true
                StorageUtils.moveGamesFromOldPath(
                    oldGamesDirectory.pathString,
                    Paths.get(Environment.getExternalStorageDirectory().absolutePath, "GameNative", "Steam").pathString,
                    onProgressUpdate = { currentFile, fileProgress, movedFiles, totalFiles ->
                        current = currentFile
                        progress = fileProgress
                        moved = movedFiles
                        total = totalFiles
                    },
                    onComplete = {
                        showMoveDialog = false
                    },
                )
            }
        },
    )

    val permissionMovingInternalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permission ->
            scope.launch {
                showMoveDialog = true
                StorageUtils.moveGamesFromOldPath(
                    Paths.get(Environment.getExternalStorageDirectory().absolutePath, "GameNative", "Steam").pathString,
                    oldGamesDirectory.pathString,
                    onProgressUpdate = { currentFile, fileProgress, movedFiles, totalFiles ->
                        current = currentFile
                        progress = fileProgress
                        moved = movedFiles
                        total = totalFiles
                    },
                    onComplete = {
                        showMoveDialog = false
                    },
                )
            }
        },
    )

    if (showMoveDialog) {
        GameMigrationDialog(
            progress = progress,
            currentFile = current,
            movedFiles = moved,
            totalFiles = total,
        )
    }

    val windowWidth = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass

    /** Storage Permission **/
    var hasStoragePermission by remember(appId) {
        val result = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        mutableStateOf(result)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val writePermissionGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
            val readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

            if (writePermissionGranted && readPermissionGranted) {
                hasStoragePermission = true
                msgDialogState = GameManagerService.getInstallInfoDialog(context, libraryItem)
            } else {
                // Snack bar this?
                Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
            }
        },
    )

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    when (msgDialogState.type) {
        DialogType.CANCEL_APP_DOWNLOAD -> {
            onConfirmClick = {
                PostHog.capture(
                    event = "game_install_cancelled",
                    properties = mapOf(
                        "game_name" to libraryItem.name,
                    ),
                )
                // Cancel the download properly based on game source
                if (libraryItem.gameSource == GameSource.GOG) {
                    GOGService.cancelDownload(libraryItem.appId)
                } else {
                    downloadInfo?.cancel()
                }
                GameManagerService.deleteGame(context, libraryItem)
                downloadInfo = null
                downloadProgress = 0f
                isInstalled = GameManagerService.isGameInstalled(context, libraryItem)
                msgDialogState = MessageDialogState(false)
            }
            onDismissRequest = { msgDialogState = MessageDialogState(false) }
            onDismissClick = { msgDialogState = MessageDialogState(false) }
        }

        DialogType.NOT_ENOUGH_SPACE -> {
            onDismissRequest = { msgDialogState = MessageDialogState(false) }
            onConfirmClick = { msgDialogState = MessageDialogState(false) }
            onDismissClick = null
        }

        DialogType.INSTALL_APP -> {
            onDismissRequest = { msgDialogState = MessageDialogState(false) }
            onConfirmClick = {
                PostHog.capture(
                    event = "game_install_started",
                    properties = mapOf(
                        "game_name" to libraryItem.name,
                    ),
                )
                CoroutineScope(Dispatchers.IO).launch {
                    downloadProgress = 0f
                    val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                    if (result.isSuccess) {
                        downloadInfo = result.getOrNull()
                    } else {
                        // Download failed - show error message
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                        }
                    }
                    msgDialogState = MessageDialogState(false)
                }
            }
            onDismissClick = { msgDialogState = MessageDialogState(false) }
        }

        DialogType.DELETE_APP -> {
            onConfirmClick = {
                GameManagerService.deleteGame(context, libraryItem)
                // Also delete the associated container so it will be recreated on next launch
                ContainerUtils.deleteContainer(context, appId)
                msgDialogState = MessageDialogState(false)

                isInstalled = GameManagerService.isGameInstalled(context, libraryItem)
            }
            onDismissRequest = { msgDialogState = MessageDialogState(false) }
            onDismissClick = { msgDialogState = MessageDialogState(false) }
        }

        DialogType.INSTALL_IMAGEFS -> {
            onDismissRequest = { msgDialogState = MessageDialogState(false) }
            onDismissClick = { msgDialogState = MessageDialogState(false) }
            onConfirmClick = {
                loadingDialogVisible = true
                msgDialogState = MessageDialogState(false)
                CoroutineScope(Dispatchers.IO).launch {
                    if (!SteamService.isImageFsInstallable(context)) {
                        SteamService.downloadImageFs(
                            onDownloadProgress = { loadingProgress = it },
                            this,
                        ).await()
                    }
                    if (!SteamService.isImageFsInstalled(context)) {
                        SplitCompat.install(context)
                        ImageFsInstaller.installIfNeededFuture(context, context.assets) {
                            // Log.d("XServerScreen", "$progress")
                            loadingProgress = it / 100f
                        }.get()
                    }
                    loadingDialogVisible = false
                    showEditConfigDialog()
                }
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    MessageDialog(
        visible = msgDialogState.visible,
        onDismissRequest = onDismissRequest,
        onConfirmClick = onConfirmClick,
        confirmBtnText = msgDialogState.confirmBtnText,
        onDismissClick = onDismissClick,
        dismissBtnText = msgDialogState.dismissBtnText,
        icon = msgDialogState.type.icon,
        title = msgDialogState.title,
        message = msgDialogState.message,
    )

    ContainerConfigDialog(
        visible = showConfigDialog,
        title = "${libraryItem.name} Config",
        initialConfig = containerData,
        onDismissRequest = { showConfigDialog = false },
        onSave = {
            showConfigDialog = false
            ContainerUtils.applyToContainer(context, appId, it)
        },
    )

    LoadingDialog(
        visible = loadingDialogVisible,
        progress = loadingProgress,
    )

    Scaffold {
        AppScreenContent(
            modifier = Modifier.padding(it),
            libraryItem = libraryItem,
            isInstalled = isInstalled,
            isValidToDownload = isValidToDownload,
            isDownloading = isDownloading(),
            downloadProgress = downloadProgress,
            onDownloadInstallClick = {
                if (isDownloading()) {
                    // Prompt to cancel ongoing download
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.CANCEL_APP_DOWNLOAD,
                        title = context.getString(R.string.cancel_download_prompt_title),
                        message = "Are you sure you want to cancel the download of the app?",
                        confirmBtnText = context.getString(R.string.yes),
                        dismissBtnText = context.getString(R.string.no),
                    )
                } else if (GameManagerService.hasPartialDownload(libraryItem)) {
                    // Resume incomplete download
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                        if (result.isSuccess) {
                            downloadInfo = result.getOrNull()
                        } else {
                            CoroutineScope(Dispatchers.Main).launch {
                                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else if (!isInstalled) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    )
                } else {
                    // Already installed: launch app
                    PostHog.capture(
                        event = "game_launched",
                        properties = mapOf(
                            "game_name" to libraryItem.name,
                        ),
                    )
                    onClickPlay(false)
                }
            },
            onPauseResumeClick = {
                if (isDownloading()) {
                    // Cancel the download properly based on game source
                    if (libraryItem.gameSource == GameSource.GOG) {
                        GOGService.cancelDownload(libraryItem.appId)
                    } else {
                        downloadInfo?.cancel()
                    }
                    downloadInfo = null
                } else {
                    val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                    if (result.isSuccess) {
                        downloadInfo = result.getOrNull()
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDeleteDownloadClick = {
                msgDialogState = MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = "Delete all downloaded data for this game?",
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                )
            },
            onUpdateClick = {
                CoroutineScope(Dispatchers.IO).launch { 
                    val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                    if (result.isSuccess) {
                        downloadInfo = result.getOrNull()
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onBack = onBack,
            optionsMenu = arrayOf(
                AppMenuOption(
                    optionType = AppOptionMenuType.StorePage,
                    onClick = {
                        // TODO add option to view web page externally or internally
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            GameManagerService.getStoreUrl(libraryItem),
                        )
                        context.startActivity(browserIntent)
                    },
                ),
                AppMenuOption(
                    optionType = AppOptionMenuType.EditContainer,
                    onClick = {
                        // For Steam games, check ImageFS requirements
                        if (libraryItem.gameSource == GameSource.STEAM) {
                            if (!SteamService.isImageFsInstalled(context)) {
                                if (!SteamService.isImageFsInstallable(context)) {
                                    msgDialogState = MessageDialogState(
                                        visible = true,
                                        type = DialogType.INSTALL_IMAGEFS,
                                        title = "Download & Install ImageFS",
                                        message = "The Ubuntu image needs to be downloaded and installed before " +
                                            "being able to edit the configuration. This operation might take " +
                                            "a few minutes. Would you like to continue?",
                                        confirmBtnText = "Proceed",
                                        dismissBtnText = "Cancel",
                                    )
                                } else {
                                    msgDialogState = MessageDialogState(
                                        visible = true,
                                        type = DialogType.INSTALL_IMAGEFS,
                                        title = "Install ImageFS",
                                        message = "The Ubuntu image needs to be installed before being able to edit " +
                                            "the configuration. This operation might take a few minutes. " +
                                            "Would you like to continue?",
                                        confirmBtnText = "Proceed",
                                        dismissBtnText = "Cancel",
                                    )
                                }
                            } else {
                                showEditConfigDialog()
                            }
                        } else {
                            // For GOG games, directly show the config dialog (no ImageFS requirement)
                            showEditConfigDialog()
                        }
                    },
                ),
                *(
                    if (isInstalled) {
                        arrayOf(
                            AppMenuOption(
                                AppOptionMenuType.RunContainer,
                                onClick = {
                                    PostHog.capture(
                                        event = "container_opened",
                                        properties = mapOf(
                                            "game_name" to libraryItem.name,
                                        ),
                                    )
                                    onClickPlay(true)
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.ResetDrm,
                                onClick = {
                                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                                    container.isNeedsUnpacking = true
                                    container.saveData()
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.VerifyFiles,
                                onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                                        if (result.isSuccess) {
                                            downloadInfo = result.getOrNull()
                                        } else {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.Update,
                                onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = GameManagerService.downloadGameWithResult(context, libraryItem)
                                        if (result.isSuccess) {
                                            downloadInfo = result.getOrNull()
                                        } else {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                            ),
//                            *(
//                                    if(File(context.dataDir, "Steam").exists()) {
//                                        arrayOf(
//                                            AppMenuOption(
//                                                AppOptionMenuType.MoveToExternalStorage,
//                                                onClick = {
//                                                    permissionMovingExternalLauncher.launch(
//                                                        arrayOf(
//                                                            Manifest.permission.READ_EXTERNAL_STORAGE,
//                                                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                                                        ),
//                                                    )
//                                                },
//                                            ),
//                                        )
//                                    } else {
//                                        arrayOf(
//                                            AppMenuOption(
//                                                AppOptionMenuType.MoveToInternalStorage,
//                                                onClick = {
//                                                    permissionMovingInternalLauncher.launch(
//                                                        arrayOf(
//                                                            Manifest.permission.READ_EXTERNAL_STORAGE,
//                                                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                                                        ),
//                                                    )
//                                                },
//                                            ),
//                                        )
//                                    }
//                            ),
                            AppMenuOption(
                                AppOptionMenuType.Uninstall,
                                onClick = {
                                    // TODO: show loading screen of delete progress
                                    msgDialogState = MessageDialogState(
                                        visible = true,
                                        type = DialogType.DELETE_APP,
                                        title = context.getString(R.string.delete_prompt_title),
                                        message = "Are you sure you want to delete this app?",
                                        confirmBtnText = context.getString(R.string.delete_app),
                                        dismissBtnText = context.getString(R.string.cancel),
                                    )
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.ForceCloudSync,
                                onClick = {
                                    PostHog.capture(
                                        event = "cloud_sync_forced",
                                        properties = mapOf(
                                            "game_name" to libraryItem.name,
                                        ),
                                    )
                                    CoroutineScope(Dispatchers.IO).launch {
                                        // Activate container before sync (required for proper path resolution)
                                        val containerManager = ContainerManager(context)
                                        val container = ContainerUtils.getOrCreateContainer(context, appId)
                                        containerManager.activateContainer(container)

                                        val prefixToPath: (String) -> String = { prefix ->
                                            PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                                        }
                                        val syncResult = SteamService.forceSyncUserFiles(
                                            appId = gameId,
                                            prefixToPath = prefixToPath,
                                        ).await()

                                        // Handle result on main thread
                                        scope.launch(Dispatchers.Main) {
                                            when (syncResult.syncResult) {
                                                SyncResult.Success -> {
                                                    Toast.makeText(context, "Cloud sync completed successfully", Toast.LENGTH_SHORT).show()
                                                }
                                                SyncResult.UpToDate -> {
                                                    Toast.makeText(context, "Save files are already up to date", Toast.LENGTH_SHORT).show()
                                                }
                                                else -> {
                                                    Toast.makeText(context, "Cloud sync failed: ${syncResult.syncResult}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.ForceDownloadRemote,
                                onClick = {
                                    PostHog.capture(
                                        event = "force_download_remote",
                                        properties = mapOf(
                                            "game_name" to libraryItem.name,
                                        ),
                                    )
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val containerManager = ContainerManager(context)
                                        val container = ContainerUtils.getOrCreateContainer(context, appId)
                                        containerManager.activateContainer(container)

                                        val prefixToPath: (String) -> String = { prefix ->
                                            PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                                        }
                                        val syncResult = SteamService.forceSyncUserFiles(
                                            appId = gameId,
                                            prefixToPath = prefixToPath,
                                            preferredSave = SaveLocation.Remote,
                                            overrideLocalChangeNumber = -1L,
                                        ).await()

                                        scope.launch(Dispatchers.Main) {
                                            when (syncResult.syncResult) {
                                                SyncResult.Success -> {
                                                    Toast.makeText(context, "Remote saves downloaded successfully", Toast.LENGTH_SHORT).show()
                                                }
                                                else -> {
                                                    Toast.makeText(context, "Download failed: ${syncResult.syncResult}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                            ),
                            AppMenuOption(
                                AppOptionMenuType.ForceUploadLocal,
                                onClick = {
                                    PostHog.capture(
                                        event = "force_upload_local",
                                        properties = mapOf(
                                            "game_name" to libraryItem.name,
                                        ),
                                    )
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val containerManager = ContainerManager(context)
                                        val container = ContainerUtils.getOrCreateContainer(context, appId)
                                        containerManager.activateContainer(container)

                                        val prefixToPath: (String) -> String = { prefix ->
                                            PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                                        }
                                        val syncResult = SteamService.forceSyncUserFiles(
                                            appId = gameId,
                                            prefixToPath = prefixToPath,
                                            preferredSave = SaveLocation.Local,
                                        ).await()

                                        scope.launch(Dispatchers.Main) {
                                            when (syncResult.syncResult) {
                                                SyncResult.Success -> {
                                                    Toast.makeText(context, "Local saves uploaded successfully", Toast.LENGTH_SHORT).show()
                                                }
                                                else -> {
                                                    Toast.makeText(context, "Upload failed: ${syncResult.syncResult}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                            ),
                        )
                    } else {
                        emptyArray()
                    }
                    ),
                (
                    AppMenuOption(
                        optionType = AppOptionMenuType.GetSupport,
                        onClick = {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                ("https://discord.gg/2hKv4VfZfE").toUri(),
                            )
                            context.startActivity(browserIntent)
                        },
                    )
                    ),
            ),
        )
    }
}

@Composable
private fun AppScreenContent(
    modifier: Modifier = Modifier,
    libraryItem: LibraryItem,
    isInstalled: Boolean,
    isValidToDownload: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
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
    val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val wifiAllowed = !PrefManager.downloadOnWifiOnly || wifiConnected
    val scrollState = rememberScrollState()

    var optionsMenuVisible by remember { mutableStateOf(false) }

    LaunchedEffect(libraryItem.appId) {
        scrollState.animateScrollTo(0)
    }

    var appSizeOnDisk by remember { mutableStateOf("") }

    var appSizeDisplayed by remember { mutableStateOf(true) }
    // Fatass disk size call - needs to stop if we do something important like launch the app
    LaunchedEffect(appSizeDisplayed) {
        if (isInstalled) {
            appSizeOnDisk = GameManagerService.getGameDiskSize(context, libraryItem)
        }
    }

    // Check if an update is pending
    var isUpdatePending by remember(libraryItem) { mutableStateOf(false) }
    LaunchedEffect(libraryItem) {
        isUpdatePending = GameManagerService.isUpdatePending(libraryItem)
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
                .height(250.dp),
        ) {
            // Hero background image
            CoilImage(
                modifier = Modifier.fillMaxSize(),
                imageModel = { GameManagerService.getHeroImage(libraryItem) },
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
                            color = MaterialTheme.colorScheme.primary,
                        ) { }
                    }
                },
                previewPlaceholder = painterResource(R.drawable.testhero),
            )

            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                    ),
            )

            // Back button (top left)
            Box(
                modifier = Modifier
                    .padding(20.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                BackButton(onClick = onBack)
            }

            // Settings/options button (top right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp),
            ) {
                IconButton(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    onClick = { optionsMenuVisible = !optionsMenuVisible },
                    content = {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Settings",
                            tint = Color.White,
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
                    .padding(20.dp),
            ) {
                Text(
                    text = libraryItem.name,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(0f, 2f),
                            blurRadius = 10f,
                        ),
                    ),
                    color = Color.White,
                )

                val developer = GameManagerService.getAppInfo(libraryItem)?.developer ?: "Unknown"
                val releaseDate = GameManagerService.getReleaseDate(libraryItem)

                Text(
                    text = "$developer • $releaseDate",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }

        // Content section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Pause/Resume and Delete when downloading or paused
                // Determine if there's a partial download (in-session or from ungraceful close)
                val isPartiallyDownloaded =
                    (downloadProgress > 0f && downloadProgress < 1f) || GameManagerService.hasPartialDownload(libraryItem)
                // Disable resume when Wi-Fi only is enabled and there's no Wi-Fi
                val isResume = !isDownloading && isPartiallyDownloaded
                val pauseResumeEnabled = if (isResume) wifiAllowed else true
                if (isDownloading || isPartiallyDownloaded) {
                    // Pause or Resume
                    Button(
                        enabled = pauseResumeEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onPauseResumeClick,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = if (isDownloading) {
                                stringResource(R.string.pause_download)
                            } else {
                                stringResource(R.string.resume_download)
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    // Delete (Cancel) download data
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDeleteDownloadClick,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(stringResource(R.string.delete_app), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    }
                } else {
                    // Disable install when Wi-Fi only is enabled and there's no Wi-Fi
                    val isInstall = !isInstalled
                    val installEnabled = if (isInstall) wifiAllowed && hasInternet else true
                    // Install or Play button
                    Button(
                        enabled = installEnabled && isValidToDownload,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // Stop heavy operations first
                            appSizeDisplayed = false
                            onDownloadInstallClick()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        val text = when {
                            isInstalled -> stringResource(R.string.run_app)
                            !hasInternet -> "Need internet to install"
                            !wifiConnected && PrefManager.downloadOnWifiOnly -> "Install over WiFi only enabled"
                            else -> stringResource(R.string.install_app)
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    // Uninstall if already installed
                    if (isInstalled) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { optionsMenu.find { it.optionType == AppOptionMenuType.Uninstall }?.onClick?.invoke() },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.uninstall),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Download progress section
            if (isDownloading) {
                // Track download start time and estimate remaining time
                var downloadStartTime by remember { mutableStateOf<Long?>(null) }
                LaunchedEffect(downloadProgress) {
                    if (downloadProgress > 0f && downloadStartTime == null) {
                        downloadStartTime = System.currentTimeMillis()
                    }
                }
                val timeLeftText = remember(downloadProgress, downloadStartTime) {
                    if (downloadProgress in 0f..1f && downloadStartTime != null && downloadProgress < 1f) {
                        val elapsed = System.currentTimeMillis() - downloadStartTime!!
                        val totalEst = (elapsed / downloadProgress).toLong()
                        val remaining = totalEst - elapsed
                        val secondsLeft = remaining / 1000
                        val minutesLeft = secondsLeft / 60
                        val secondsPart = secondsLeft % 60
                        "${minutesLeft}m ${secondsPart}s left"
                    } else {
                        "Calculating..."
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Installation Progress",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${(downloadProgress * 100f).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
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
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // This is placeholder text since we don't have exact size info in the state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Downloading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = timeLeftText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Color(0x1AA21CAF),
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(1000f, 1000f),
                            ),
                        )
                        .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("↑", color = MaterialTheme.colorScheme.onTertiary, fontSize = 14.sp)
                            }
                            Text(
                                "Update Available",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onUpdateClick,
                            modifier = Modifier.align(Alignment.Start),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Text("Update Now", color = MaterialTheme.colorScheme.onTertiary)
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
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
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                            ),
                    )

                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "Game Information",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            // Setting a fixed height to avoid nested scrolling issues
                            modifier = Modifier.height(220.dp),
                        ) {
                            // Status item
                            item {
                                Column {
                                    Text(
                                        text = "Status",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
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
                                                        shape = CircleShape,
                                                    ),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = when {
                                                    isInstalled -> "Installed"
                                                    isDownloading -> "Installing"
                                                    else -> "Not Installed"
                                                },
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                }
                            }

                            // Size item
                            item {
                                Column {
                                    Text(
                                        text = "Size",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    // Show skeleton while calculating disk size, otherwise show actual text
                                    if (isInstalled && (appSizeOnDisk.isEmpty() || appSizeOnDisk == " ...")) {
                                        SkeletonText(lines = 1, lineHeight = 20)
                                    } else {
                                        if (!isInstalled) {
                                            Text(
                                                text = GameManagerService.getDownloadSize(libraryItem),
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            )
                                        } else {
                                            Text(
                                                text = appSizeOnDisk,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            )
                                        }
                                    }
                                }
                            }

                            // Location item
                            if (isInstalled) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column {
                                        Text(
                                            text = "Location",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                                        ) {
                                            Text(
                                                text = GameManagerService.getAppDirPath(libraryItem.appId),
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
                                        text = "Developer",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = GameManagerService.getAppInfo(libraryItem)?.developer ?: "Unknown",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                            }

                            // Release Date item
                            item {
                                Column {
                                    Text(
                                        text = "Release Date",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = GameManagerService.getReleaseDate(libraryItem),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
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
        title = { Text(text = "Moving Files") },
        text = {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "File ${movedFiles + 1} of $totalFiles",
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
    val gameManagerService = LocalGameManagerService.current
    gameManagerService.ensureInitialized()

    PluviaTheme {
        Surface {
            AppScreenContent(
                libraryItem = LibraryItem(
                    index = 0,
                    appId = "STEAM_1",
                    name = "Test Game",
                    iconHash = "",
                    isShared = false,
                    gameSource = GameSource.STEAM,
                ),
                isInstalled = false,
                isValidToDownload = true,
                isDownloading = isDownloading,
                downloadProgress = .50f,
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
