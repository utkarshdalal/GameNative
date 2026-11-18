package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GOGGame
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.gog.GogDlcKind
import app.gamenative.service.gog.GogDlcOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GOGGameManagerDialog(
    visible: Boolean,
    isInstalled: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onGetLanguage: () -> String,
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
    var baseSizes by remember { mutableStateOf(0L to 0L) }
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(visible, reloadKey) {
        if (!visible) return@LaunchedEffect
        loading = true
        loadError = null
        launch { scrollState.animateScrollTo(0) }

        val (game, space, result) = withContext(Dispatchers.IO) {
            val path = GOGService.getInstallPath(gameId) ?: GOGConstants.defaultGOGGamesPath
            val freeBytes = try {
                StorageUtils.getAvailableSpaceForUncreatedPath(path)
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
        options = result.getOrNull()?.dlcs.orEmpty()
        baseSizes = result.getOrNull()?.let { it.baseDownloadSize to it.baseInstallSize } ?: (0L to 0L)
        loadError = result.exceptionOrNull()?.let { it.message.orEmpty() }
        selectedIds.clear()
        options.forEach { selectedIds[it.productId] = it.installed || it.isSelectable() }
        loading = false
    }

    val selectableIds = options.filter { it.isSelectable() && !it.installed }.map { it.productId }
    val allSelectableSelected = selectableIds.isNotEmpty() && selectableIds.all { selectedIds[it] == true }
    val newSelections = options.filter { it.isSelectable() && !it.installed && selectedIds[it.productId] == true }
    val unresolvedTitles = options.filter { it.kind == GogDlcKind.UNKNOWN }.map { it.title }

    val baseDownloadBytes = if (isInstalled) 0L else baseSizes.first.takeIf { it > 0L } ?: baseGame?.downloadSize ?: 0L
    val baseInstallBytes = if (isInstalled) 0L else baseSizes.second.takeIf { it > 0L } ?: baseGame?.installSize ?: 0L
    val totalDownloadBytes = baseDownloadBytes + newSelections.sumOf { it.downloadSize }
    val totalInstallBytes = maxOf(baseInstallBytes, baseDownloadBytes) +
        newSelections.sumOf { maxOf(it.installSize, it.downloadSize) }

    val installButtonEnabled = availableBytes >= totalInstallBytes &&
        (!isInstalled || newSelections.isNotEmpty())

    if (!visible) return

    val retryMessage = loadError ?: unresolvedTitles.takeIf { it.isNotEmpty() }?.joinToString()

    val baseRow = GameContentRow(
        key = gameId,
        title = displayInfo.name,
        downloadSize = baseDownloadBytes,
        installSize = baseInstallBytes,
        installed = isInstalled,
        checked = true,
        enabled = false,
    )
    val dlcRows = options.map { option ->
        GameContentRow(
            key = option.productId,
            title = option.title,
            downloadSize = option.downloadSize,
            installSize = option.installSize,
            note = when (option.kind) {
                GogDlcKind.NONE -> stringResource(R.string.gog_dlc_not_installable)
                GogDlcKind.UNKNOWN -> stringResource(R.string.gog_dlc_unresolved)
                GogDlcKind.INSTALLER -> if (option.installed) null else stringResource(R.string.gog_dlc_installs_on_launch)
                else -> null
            },
            installed = option.installed,
            checked = selectedIds[option.productId] ?: false,
            enabled = option.isSelectable() && !option.installed,
        )
    }

    GameContentDialog(
        displayInfo = displayInfo,
        scrollState = scrollState,
        loading = loading,
        rows = listOf(baseRow) + dlcRows,
        onCheckedChange = { productId, checked -> selectedIds[productId] = checked },
        formatSize = { StorageUtils.formatBinarySize(it) },
        footerText = stringResource(
            R.string.steam_install_space,
            StorageUtils.formatBinarySize(totalDownloadBytes),
            StorageUtils.formatBinarySize(totalInstallBytes),
            StorageUtils.formatBinarySize(availableBytes)
        ),
        installEnabled = installButtonEnabled,
        onInstall = {
            val dlcIds = options
                .filter { it.installed || (it.isSelectable() && selectedIds[it.productId] == true) }
                .map { it.productId }
                .toSet()
            onInstall(dlcIds)
        },
        onDismissRequest = onDismissRequest,
        errorMessage = retryMessage?.let { stringResource(R.string.gog_dlc_load_failed, it) },
        onRetry = { reloadKey++ },
        showSelectAll = selectableIds.isNotEmpty(),
        allSelected = allSelectableSelected,
        onToggleSelectAll = {
            val newState = !allSelectableSelected
            selectableIds.forEach { selectedIds[it] = newState }
        },
    )
}

private fun GogDlcOption.isSelectable(): Boolean = installable && kind != GogDlcKind.UNKNOWN
