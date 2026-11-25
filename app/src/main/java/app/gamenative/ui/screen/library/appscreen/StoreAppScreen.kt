package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.StoreGame
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import app.gamenative.service.store.StoreGames
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.component.dialog.GameContentDialog
import app.gamenative.ui.component.dialog.GameContentRow
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedAlertDialog
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DateTimeUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The app screen of every [app.gamenative.service.store.StoreAdapter] store, driven by [StoreGames]. */
class StoreAppScreen(private val source: GameSource) : BaseAppScreen() {
    companion object {
        private const val TAG = "StoreAppScreen"
        private val installDialogAppIds = mutableStateListOf<String>()
        private val uninstallDialogAppIds = mutableStateListOf<String>()
        private var showDeletingDialog by mutableStateOf(false)
    }

    private fun game(libraryItem: LibraryItem): StoreGame? = StoreGames.game(libraryItem.gameId)

    @Composable
    override fun getGameDisplayInfo(context: Context, libraryItem: LibraryItem): GameDisplayInfo {
        val games by StoreGames.all.collectAsState()
        val game = games.firstOrNull { it.appId == libraryItem.gameId } ?: game(libraryItem)
        val hero = game?.heroUrl?.ifEmpty { null } ?: game?.coverUrl?.ifEmpty { null } ?: libraryItem.heroImageUrl.ifEmpty { null }
        val icon = game?.iconUrl?.ifEmpty { null } ?: game?.coverUrl?.ifEmpty { null } ?: libraryItem.iconHash.ifEmpty { null }
        return GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            developer = game?.developer.orEmpty(),
            releaseDate = game?.releaseDate?.takeIf { it.isNotEmpty() }?.let { DateTimeUtils.parseStoreReleaseDateToEpochSeconds(it) } ?: 0L,
            heroImageUrl = hero,
            iconUrl = icon,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = game?.takeIf { it.isInstalled }?.installPath,
            sizeOnDisk = game?.installSize?.takeIf { it > 0L }?.let { StorageUtils.formatBinarySize(it) },
            sizeFromStore = game?.downloadSize?.takeIf { it > 0L }?.let { StorageUtils.formatBinarySize(it) },
            capsuleUrl = game?.coverUrl?.ifEmpty { null },
            headerUrl = hero,
            runtime = app.gamenative.html5.host.Html5Routing.rememberRuntime(context, libraryItem.appId, libraryItem.gameId),
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean = StoreGames.isInstalled(libraryItem.gameId)

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean =
        !isInstalled(context, libraryItem) && StoreGames.downloadInfo(libraryItem.gameId) == null

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean = StoreGames.downloadInfo(libraryItem.gameId) != null

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float =
        StoreGames.downloadInfo(libraryItem.gameId)?.getProgress() ?: 0f

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean = StoreGames.hasPartialDownload(libraryItem.gameId)

    override suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean =
        StoreGames.checkForUpdate(context, libraryItem.gameId)

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        when {
            isDownloading(context, libraryItem) -> Unit
            hasPartialDownload(context, libraryItem) -> startDownload(context, libraryItem)
            isInstalled(context, libraryItem) -> onClickPlay(false)
            !installDialogAppIds.contains(libraryItem.appId) -> installDialogAppIds.add(libraryItem.appId)
        }
    }

    private fun startDownload(context: Context, libraryItem: LibraryItem) {
        StoreGames.download(context, libraryItem.gameId).onFailure {
            Timber.tag(TAG).e(it, "Download did not start for ${libraryItem.appId}")
            SnackbarManager.show(context.getString(R.string.library_download_start_failed, it.message ?: it.javaClass.simpleName))
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        if (!StoreGames.cancelDownload(libraryItem.gameId)) startDownload(context, libraryItem)
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        if (isDownloading(context, libraryItem) || hasPartialDownload(context, libraryItem)) {
            showInstallDialog(
                libraryItem.appId,
                MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.library_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                ),
            )
        } else if (isInstalled(context, libraryItem) && !uninstallDialogAppIds.contains(libraryItem.appId)) {
            uninstallDialogAppIds.add(libraryItem.appId)
        }
    }

    private fun uninstall(context: Context, libraryItem: LibraryItem) {
        showDeletingDialog = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val root = getInstallPath(context, libraryItem)?.let(::File)
                val result = StoreGames.uninstall(context, libraryItem.gameId)
                DownloadService.invalidateCache()
                if (result.isSuccess) cleanupNexusModsForApp(context, libraryItem, root)
                result.onFailure { Timber.tag(TAG).e(it, "Uninstall failed for ${libraryItem.appId}") }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { showDeletingDialog = false }
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) = startDownload(context, libraryItem)

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? = StoreGames.installPath(libraryItem.gameId)

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        val appId = libraryItem.gameId
        var attached: DownloadInfo? = null
        val listener: (Float) -> Unit = { onProgressChanged(it) }
        fun attach() {
            attached?.removeProgressListener(listener)
            attached = StoreGames.downloadInfo(appId)?.also {
                it.addProgressListener(listener)
                onProgressChanged(it.getProgress())
            }
        }
        attach()
        val downloadListener: (AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            if (event.appId == appId) {
                if (event.isDownloading) {
                    attach()
                } else {
                    attached?.removeProgressListener(listener)
                    attached = null
                    onHasPartialDownloadChanged?.invoke(StoreGames.hasPartialDownload(appId))
                }
                onStateChanged()
            }
        }
        val installListener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            if (event.appId == appId) onStateChanged()
        }
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(downloadListener)
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        return {
            attached?.removeProgressListener(listener)
            PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(downloadListener)
            PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }
    }

    override fun getExportFileExtension(): String = ".${source.name.lowercase()}"

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
    ) {
        val context = LocalContext.current
        val appId = libraryItem.appId

        var installDialogState by remember(appId) { mutableStateOf(getInstallDialogState(appId) ?: MessageDialogState(false)) }
        LaunchedEffect(appId) {
            snapshotFlow { getInstallDialogState(appId) }.collect { installDialogState = it ?: MessageDialogState(false) }
        }
        if (installDialogState.visible) {
            MessageDialog(
                visible = true,
                onDismissRequest = { hideInstallDialog(appId) },
                onConfirmClick = if (installDialogState.type == DialogType.CANCEL_APP_DOWNLOAD) {
                    {
                        hideInstallDialog(appId)
                        uninstall(context, libraryItem)
                    }
                } else {
                    null
                },
                onDismissClick = { hideInstallDialog(appId) },
                confirmBtnText = installDialogState.confirmBtnText,
                dismissBtnText = installDialogState.dismissBtnText,
                title = installDialogState.title,
                message = installDialogState.message,
                defaultFocus = installDialogState.type.defaultFocus,
            )
        }

        if (installDialogAppIds.contains(appId)) {
            InstallDialog(context, libraryItem, onDismissRequest = { installDialogAppIds.remove(appId) })
        }

        if (showDeletingDialog) {
            LoadingDialog(visible = true, progress = -1f, message = stringResource(R.string.deleting))
        }

        if (uninstallDialogAppIds.contains(appId)) {
            ThemedAlertDialog(
                onDismissRequest = { uninstallDialogAppIds.remove(appId) },
                title = { Text(stringResource(R.string.steam_uninstall_game_title)) },
                text = { Text(stringResource(R.string.steam_uninstall_confirmation_message, libraryItem.name)) },
                confirmButton = {
                    RingTextButton(onClick = {
                        uninstallDialogAppIds.remove(appId)
                        uninstall(context, libraryItem)
                    }) { Text(stringResource(R.string.uninstall)) }
                },
                dismissButton = {
                    RingTextButton(onClick = { uninstallDialogAppIds.remove(appId) }) { Text(stringResource(R.string.cancel)) }
                },
                containerColor = PluviaTheme.dialogContainerColor,
                titleContentColor = PluviaTheme.dialogTitleColor,
                textContentColor = PluviaTheme.dialogTextColor,
            )
        }
    }

    @Composable
    private fun InstallDialog(context: Context, libraryItem: LibraryItem, onDismissRequest: () -> Unit) {
        val scrollState = rememberScrollState()
        val game = game(libraryItem)
        val adapter = game?.let { StoreGames.adapter(it) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var attempt by remember { mutableStateOf(0) }
        var downloadBytes by remember { mutableLongStateOf(0L) }
        var installBytes by remember { mutableLongStateOf(0L) }
        var availableBytes by remember { mutableLongStateOf(0L) }
        var unsupported by remember { mutableStateOf(game?.unsupportedReason?.isNotEmpty() == true) }

        LaunchedEffect(attempt) {
            loading = true
            failed = false
            if (game != null && adapter != null && !unsupported) {
                try {
                    val sizes = adapter.downloadSizes(context, game)
                    downloadBytes = sizes?.first ?: 0L
                    installBytes = sizes?.second ?: 0L
                } catch (e: UnsupportedOperationException) {
                    unsupported = true
                    StoreGames.markUnsupported(game.appId, e.message ?: "unsupported")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Sizes unavailable for ${libraryItem.appId}")
                    failed = true
                }
                availableBytes = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = StoreGames.installDir(context, game).apply { parentFile?.mkdirs() }
                        StorageUtils.getAvailableSpace(dir.parentFile!!.path)
                    }.getOrDefault(0L)
                }
            }
            loading = false
        }

        val displayInfo = getGameDisplayInfo(context, libraryItem)
        GameContentDialog(
            displayInfo = displayInfo,
            scrollState = scrollState,
            loading = loading,
            rows = listOf(
                GameContentRow(
                    key = libraryItem.appId,
                    title = displayInfo.name,
                    downloadSize = downloadBytes,
                    installSize = installBytes,
                    checked = true,
                    enabled = false,
                ),
            ),
            onCheckedChange = { _, _ -> },
            formatSize = { StorageUtils.formatBinarySize(it) },
            footerText = stringResource(
                R.string.steam_install_space,
                StorageUtils.formatBinarySize(downloadBytes),
                StorageUtils.formatBinarySize(installBytes),
                StorageUtils.formatBinarySize(availableBytes),
            ),
            installEnabled = !unsupported && !failed && !loading && availableBytes >= installBytes,
            onInstall = {
                onDismissRequest()
                startDownload(context, libraryItem)
            },
            onDismissRequest = onDismissRequest,
            errorMessage = when {
                unsupported -> stringResource(R.string.store_install_unsupported, adapter?.displayName.orEmpty())
                failed -> stringResource(R.string.store_install_sizes_failed)
                else -> null
            },
            onRetry = if (failed) ({ attempt += 1 }) else null,
        )
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData =
        ContainerUtils.toContainerData(ContainerUtils.getOrCreateContainer(context, libraryItem.appId))

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true
}
