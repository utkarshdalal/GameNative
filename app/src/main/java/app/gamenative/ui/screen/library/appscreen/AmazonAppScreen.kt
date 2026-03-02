package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.AmazonGame
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.component.dialog.AmazonInstallDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DateTimeUtils
import com.winlator.container.ContainerData
import com.winlator.core.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Amazon-specific [BaseAppScreen] implementation. */
class AmazonAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "AmazonAppScreen"

        // Shared state for uninstall dialog — list of appIds that should show the dialog
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        /** State for the full-screen install confirmation dialog. */
        data class AmazonInstallDialogData(
            val downloadSize: String,
            val installSize: String,
            val availableSpace: String,
            val installEnabled: Boolean,
        )

        private val installDialogDataMap = mutableStateMapOf<String, AmazonInstallDialogData>()

        fun showAmazonInstallDialog(appId: String, data: AmazonInstallDialogData) {
            installDialogDataMap[appId] = data
        }

        fun hideAmazonInstallDialog(appId: String) {
            installDialogDataMap.remove(appId)
        }

        fun getAmazonInstallDialogData(appId: String): AmazonInstallDialogData? =
            installDialogDataMap[appId]

        fun showUninstallDialog(appId: String) {
            Timber.tag(TAG).d("showUninstallDialog: appId=$appId")
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
            }
        }

        fun hideUninstallDialog(appId: String) {
            Timber.tag(TAG).d("hideUninstallDialog: appId=$appId")
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean =
            uninstallDialogAppIds.contains(appId)

        /** Resolve productId from a library item's appId-backed gameId. */
        fun productIdOf(libraryItem: LibraryItem): String =
            AmazonService.getProductIdByAppId(libraryItem.gameId) ?: ""

    }

    // ── BaseAppScreen contract ─────────────────────────────────────────────

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        val productId = productIdOf(libraryItem)
        Timber.tag(TAG).d(
            "getGameDisplayInfo: productId=$productId name=${libraryItem.name} " +
                "gameId=${libraryItem.gameId}"
        )

        var game by remember(productId) { mutableStateOf<AmazonGame?>(null) }

        // Refresh key — incremented when install status changes so we re-fetch from DB.
        // This ensures size/installPath/etc. are up-to-date after download completes.
        var refreshKey by remember(productId) { mutableStateOf(0) }

        androidx.compose.runtime.DisposableEffect(productId) {
            val listener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == libraryItem.gameId) {
                    Timber.tag(TAG).d("[REFRESH] Install status changed for $productId — refreshing game info")
                    refreshKey++
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            onDispose {
                app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            }
        }

        LaunchedEffect(productId, refreshKey) {
            game = AmazonService.getAmazonGameOf(productId)
            Timber.tag(TAG).d(
                "Loaded game: title=${game?.title}, developer=${game?.developer}, " +
                    "releaseDate=${game?.releaseDate}, artUrl=${game?.artUrl?.take(60)}, " +
                    "heroUrl=${game?.heroUrl?.take(60)}, downloadSize=${game?.downloadSize}, " +
                    "installSize=${game?.installSize}, isInstalled=${game?.isInstalled}"
            )
            // Proactively fetch size from manifest if not yet cached
            val g = game
            if (g != null && (g.downloadSize <= 0L) && !g.isInstalled) {
                val size = AmazonService.fetchDownloadSize(productId)
                if (size != null && size > 0L) {
                    // Re-read from DB to pick up the cached size
                    game = AmazonService.getAmazonGameOf(productId)
                }
            }
        }

        val g = game

        // Artwork — use heroUrl for the backdrop, artUrl/iconHash for the icon
        val heroImageUrl = g?.heroUrl?.takeIf { it.isNotEmpty() }
            ?: g?.artUrl?.takeIf { it.isNotEmpty() }   // fall back to art if no hero
            ?: libraryItem.iconHash.takeIf { it.isNotEmpty() }

        val iconUrl = g?.artUrl?.takeIf { it.isNotEmpty() }
            ?: libraryItem.iconHash.takeIf { it.isNotEmpty() }

        // Metadata
        val developer = g?.developer?.takeIf { it.isNotEmpty() }
            ?: g?.publisher?.takeIf { it.isNotEmpty() }
            ?: ""

        val releaseDateTs = g?.releaseDate?.let { DateTimeUtils.parseStoreReleaseDateToEpochSeconds(it) } ?: 0L

        val sizeFromStore = if ((g?.downloadSize ?: 0L) > 0L) {
            StringUtils.formatBytes(g!!.downloadSize)
        } else {
            null
        }

        return GameDisplayInfo(
            name = g?.title ?: libraryItem.name,
            iconUrl = iconUrl,
            heroImageUrl = heroImageUrl,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            releaseDate = releaseDateTs,
            developer = developer,
            installLocation = if (g?.isInstalled == true && g.installPath.isNotEmpty()) {
                g.installPath
            } else {
                null
            },
            sizeOnDisk = if ((g?.installSize ?: 0L) > 0L) StringUtils.formatBytes(g!!.installSize) else null,
            sizeFromStore = sizeFromStore,
            lastPlayedText = null,
            playtimeText = null,
        )
    }

override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean =
        AmazonService.isGameInstalledByAppId(libraryItem.gameId)

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean =
        !isInstalled(context, libraryItem) &&
            AmazonService.getDownloadInfoByAppId(libraryItem.gameId) == null

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean =
        AmazonService.getDownloadInfoByAppId(libraryItem.gameId) != null

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float =
        AmazonService.getDownloadInfoByAppId(libraryItem.gameId)?.getProgress() ?: 0f

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        return AmazonService.hasPartialDownloadByAppId(context, libraryItem.gameId)
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        val productId = productIdOf(libraryItem)
        val installed = isInstalled(context, libraryItem)
        val downloading = isDownloading(context, libraryItem)
        val hasPartial = hasPartialDownload(context, libraryItem)

        Timber.tag(TAG).d(
            "onDownloadInstallClick: productId=$productId, installed=$installed, downloading=$downloading, partial=$hasPartial"
        )

        if (downloading) {
            Timber.tag(TAG).i("Download already in progress for $productId — ignoring click")
            return
        }

        if (hasPartial) {
            // Resume directly — no confirmation dialog needed (mirrors Steam/Epic behaviour)
            Timber.tag(TAG).i("Resuming partial download for: ${libraryItem.appId}")
            performDownload(context, libraryItem)
            return
        }

        if (installed) {
            Timber.tag(TAG).i("Game already installed, launching: $productId")
            onClickPlay(false)
            return
        }

        // Show full-screen install confirmation (matches Steam GameManagerDialog style)
        Timber.tag(TAG).i("Showing install confirmation for: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val game = AmazonService.getAmazonGameOf(productId)

                // Resolve download size — use cached value first, fall back to manifest fetch
                var downloadBytes = game?.downloadSize ?: 0L
                if (downloadBytes <= 0L) {
                    Timber.tag(TAG).d("Download size not cached, fetching manifest for $productId…")
                    downloadBytes = AmazonService.fetchDownloadSize(productId) ?: 0L
                }
                val installBytes = game?.installSize ?: 0L

                val downloadSize = if (downloadBytes > 0L)
                    app.gamenative.utils.StorageUtils.formatBinarySize(downloadBytes)
                else "Unknown"
                val installSize = if (installBytes > 0L)
                    app.gamenative.utils.StorageUtils.formatBinarySize(installBytes)
                else "Unknown"

                val installDir = AmazonConstants.getGameInstallPath(context, game?.title ?: libraryItem.name)
                val availableBytes = app.gamenative.utils.StorageUtils.getAvailableSpace(AmazonConstants.defaultAmazonGamesPath(context))
                val availableSpace = app.gamenative.utils.StorageUtils.formatBinarySize(availableBytes)

                withContext(Dispatchers.Main) {
                    showAmazonInstallDialog(
                        libraryItem.appId,
                        AmazonInstallDialogData(
                            downloadSize = downloadSize,
                            installSize = installSize,
                            availableSpace = availableSpace,
                            installEnabled = availableBytes >= installBytes || installBytes <= 0L,
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to show install confirmation for: ${libraryItem.appId}")
            }
        }
    }

    private fun performDownload(context: Context, libraryItem: LibraryItem) {
        val productId = productIdOf(libraryItem)
        CoroutineScope(Dispatchers.IO).launch {
            val game = AmazonService.getAmazonGameOf(productId) ?: run {
                Timber.tag(TAG).w("performDownload: game not found for $productId")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Game not found — try syncing library", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val installPath = AmazonConstants.getGameInstallPath(context, game.title)
            Timber.tag(TAG).i("Downloading '${game.title}' → $installPath")

            val result = AmazonService.downloadGame(context, productId, installPath)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                Timber.tag(TAG).e("downloadGame failed: $msg")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to start download: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val appId = libraryItem.gameId
        if (AmazonService.getDownloadInfoByAppId(appId) != null) {
            Timber.tag(TAG).i("Cancelling download for appId=$appId")
            AmazonService.cancelDownloadByAppId(appId)
        } else {
            // Resume paused/cancelled download directly — no confirmation dialog
            performDownload(context, libraryItem)
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val productId = productIdOf(libraryItem)
        Timber.tag(TAG).i("onDeleteDownloadClick: productId=$productId")

        if (isDownloading(context, libraryItem) || hasPartialDownload(context, libraryItem)) {
            showInstallDialog(
                libraryItem.appId,
                MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.epic_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                )
            )
        } else if (isInstalled(context, libraryItem)) {
            // Show uninstall confirmation dialog (debounces multi-tap)
            Timber.tag(TAG).i("Showing uninstall dialog for: ${libraryItem.appId}")
            showUninstallDialog(libraryItem.appId)
        }
    }

    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        val productId = productIdOf(libraryItem)
        Timber.tag(TAG).i("performUninstall: deleting game $productId")
        CoroutineScope(Dispatchers.IO).launch {
            val result = AmazonService.deleteGame(context, productId)
            if (result.isSuccess) {
                Timber.tag(TAG).i("Uninstall succeeded for $productId")
            } else {
                Timber.tag(TAG).e("Uninstall failed for $productId: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onUpdateClick: re-downloading ${productIdOf(libraryItem)}")
        performDownload(context, libraryItem)
    }

    override suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        return AmazonService.isUpdatePendingByAppId(libraryItem.gameId)
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        return AmazonService.getInstallPathByAppId(libraryItem.gameId)
    }

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        val gameId = libraryItem.gameId
        val productId = productIdOf(libraryItem)
        Timber.tag(TAG).d("[OBSERVE] Setting up observeGameState for productId=$productId, gameId=$gameId")

        val disposables = mutableListOf<() -> Unit>()
        var currentProgressListener: ((Float) -> Unit)? = null
        var currentDownloadInfo: app.gamenative.data.DownloadInfo? = null

        // If download is already in progress, attach listener immediately
        val existingDownloadInfo = AmazonService.getDownloadInfo(productId)
        if (existingDownloadInfo != null && (existingDownloadInfo.getProgress() ?: 0f) < 1f) {
            Timber.tag(TAG).d("[OBSERVE] Download already in progress for $productId, attaching progress listener")
            val progressListener: (Float) -> Unit = { progress ->
                onProgressChanged(progress)
            }
            existingDownloadInfo.addProgressListener(progressListener)
            currentDownloadInfo = existingDownloadInfo
            currentProgressListener = progressListener
            disposables += {
                currentProgressListener?.let { listener ->
                    currentDownloadInfo?.removeProgressListener(listener)
                    currentProgressListener = null
                    currentDownloadInfo = null
                }
            }
            existingDownloadInfo.getProgress()?.let { onProgressChanged(it) }
        }

        // Listen for download status changes (events use productId.hashCode() as appId)
        val downloadStatusListener: (app.gamenative.events.AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            if (event.appId == gameId) {
                Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged for $productId, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    val downloadInfo = AmazonService.getDownloadInfo(productId)
                    if (downloadInfo != null) {
                        currentProgressListener?.let { listener ->
                            currentDownloadInfo?.removeProgressListener(listener)
                        }
                        val progressListener: (Float) -> Unit = { progress ->
                            Timber.tag(TAG).v("[OBSERVE] Progress for $productId: $progress")
                            onProgressChanged(progress)
                        }
                        downloadInfo.addProgressListener(progressListener)
                        currentDownloadInfo = downloadInfo
                        currentProgressListener = progressListener
                        disposables += {
                            currentProgressListener?.let { listener ->
                                currentDownloadInfo?.removeProgressListener(listener)
                                currentProgressListener = null
                                currentDownloadInfo = null
                            }
                        }
                    }
                } else {
                    currentProgressListener?.let { listener ->
                        currentDownloadInfo?.removeProgressListener(listener)
                        currentProgressListener = null
                        currentDownloadInfo = null
                    }
                    // If not installed after download stopped → paused/cancelled: show Resume state
                    val nowInstalled = AmazonService.isGameInstalledByAppId(gameId)
                    onHasPartialDownloadChanged?.invoke(!nowInstalled && hasPartialDownload(context, libraryItem))
                }
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables += { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        // Listen for install status changes
        val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            if (event.appId == gameId) {
                Timber.tag(TAG).d("[OBSERVE] Install status changed for $productId")
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables += { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener) }

        return { disposables.forEach { it() } }
    }

    override fun getExportFileExtension(): String = ".amazon"

    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        val options = mutableListOf<AppMenuOption>()
        if (isInstalled) {
            options.add(getVerifyFilesOption(context, libraryItem))
        }
        return options
    }

    @Composable
    private fun getVerifyFilesOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption {
        var showDialog by remember { mutableStateOf(false) }
        var verifyResult by remember { mutableStateOf<String?>(null) }
        var isVerifying by remember { mutableStateOf(false) }

        // Confirmation dialog before verifying
        if (showDialog && !isVerifying && verifyResult == null) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.amazon_verify_files_title)) },
                text = { Text(stringResource(R.string.amazon_verify_files_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isVerifying = true
                            val productId = productIdOf(libraryItem)
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = AmazonService.verifyGame(context, productId)
                                withContext(Dispatchers.Main) {
                                    isVerifying = false
                                    verifyResult = if (result.isSuccess) {
                                        val v = result.getOrNull()!!
                                        if (v.isValid) {
                                            context.getString(
                                                R.string.amazon_verify_success,
                                                v.verifiedOk,
                                                v.totalFiles,
                                            )
                                        } else {
                                            context.getString(
                                                R.string.amazon_verify_failed_detail,
                                                v.verifiedOk,
                                                v.totalFiles,
                                                v.missingFiles,
                                                v.sizeMismatch,
                                                v.hashMismatch,
                                            )
                                        }
                                    } else {
                                        context.getString(
                                            R.string.amazon_verify_error,
                                            result.exceptionOrNull()?.message ?: "Unknown error",
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.amazon_verify_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Progress dialog while verifying
        if (isVerifying) {
            AlertDialog(
                onDismissRequest = { /* non-dismissable while verifying */ },
                title = { Text(stringResource(R.string.amazon_verify_files_title)) },
                text = { Text(stringResource(R.string.amazon_verify_in_progress)) },
                confirmButton = {},
            )
        }

        // Result dialog
        if (verifyResult != null) {
            AlertDialog(
                onDismissRequest = {
                    verifyResult = null
                    showDialog = false
                },
                title = { Text(stringResource(R.string.amazon_verify_files_title)) },
                text = { Text(verifyResult!!) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            verifyResult = null
                            showDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        return AppMenuOption(
            optionType = AppOptionMenuType.VerifyFiles,
            onClick = { showDialog = true },
        )
    }

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        var showDialog by remember { mutableStateOf(false) }

        if (showDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showDialog = false },
            )
        }

        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = { showDialog = true },
        )
    }

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val appId = libraryItem.appId
        val productId = productIdOf(libraryItem)

        // ── Cancel-download / delete-partial confirmation dialog ──
        var installDialogState by remember(appId) {
            mutableStateOf(BaseAppScreen.getInstallDialogState(appId) ?: MessageDialogState(false))
        }
        LaunchedEffect(appId) {
            snapshotFlow { BaseAppScreen.getInstallDialogState(appId) }
                .collect { state ->
                    installDialogState = state ?: MessageDialogState(false)
                }
        }

        if (installDialogState.visible) {
            val onConfirmClick: (() -> Unit)? = when (installDialogState.type) {
                DialogType.CANCEL_APP_DOWNLOAD -> {
                    {
                        Timber.tag(TAG).i("Confirmed cancel/delete download for: $productId")
                        val downloadInfo = AmazonService.getDownloadInfo(productId)
                        downloadInfo?.cancel()
                        scope.launch {
                            downloadInfo?.awaitCompletion()
                            AmazonService.deleteGame(context, productId)
                            withContext(Dispatchers.Main) {
                                BaseAppScreen.hideInstallDialog(appId)
                                val gameId = libraryItem.gameId
                                PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameId, false))
                                PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameId))
                            }
                        }
                    }
                }
                else -> null
            }
            MessageDialog(
                visible = installDialogState.visible,
                onDismissRequest = { BaseAppScreen.hideInstallDialog(appId) },
                onConfirmClick = onConfirmClick,
                onDismissClick = { BaseAppScreen.hideInstallDialog(appId) },
                confirmBtnText = installDialogState.confirmBtnText,
                dismissBtnText = installDialogState.dismissBtnText,
                title = installDialogState.title,
                message = installDialogState.message,
            )
        }

        // ── Install confirmation (full-screen, matches Steam GameManagerDialog style) ──
        var amazonInstallData by remember(appId) {
            mutableStateOf(getAmazonInstallDialogData(appId))
        }
        LaunchedEffect(appId) {
            snapshotFlow { getAmazonInstallDialogData(appId) }
                .collect { data -> amazonInstallData = data }
        }

        val currentInstallData = amazonInstallData
        if (currentInstallData != null) {
            val displayInfo = getGameDisplayInfo(context, libraryItem)
            AmazonInstallDialog(
                visible = true,
                displayInfo = displayInfo,
                downloadSize = currentInstallData.downloadSize,
                installSize = currentInstallData.installSize,
                availableSpace = currentInstallData.availableSpace,
                installEnabled = currentInstallData.installEnabled,
                onInstall = {
                    hideAmazonInstallDialog(appId)
                    performDownload(context, libraryItem)
                },
                onDismiss = {
                    hideAmazonInstallDialog(appId)
                },
            )
        }

        // ── Uninstall confirmation dialog ──
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(libraryItem.appId)) }
        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowUninstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showUninstallDialog = shouldShow
                }
        }

        if (showUninstallDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideUninstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.amazon_uninstall_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.amazon_uninstall_confirmation_message,
                            libraryItem.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                            performUninstall(context, libraryItem)
                        },
                    ) {
                        Text(stringResource(R.string.uninstall))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(
        context: Context,
        libraryItem: LibraryItem,
        config: ContainerData,
    ) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true
}
