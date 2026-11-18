package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.component.dialog.InstallSizeInfo
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.launch

@Composable
fun EpicGameManagerDialog(
    visible: Boolean,
    isInstalled: Boolean = false,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onInstall: (List<Int>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val allDownloadableGames = remember { mutableStateListOf<EpicGame>() }
    val selectedGameIds = remember { mutableStateMapOf<Int, Boolean>() }
    var loading by remember { mutableStateOf(true) }

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId

    LaunchedEffect(visible, isInstalled) {
        launch { scrollState.animateScrollTo(0) }

        loading = true
        allDownloadableGames.clear()
        selectedGameIds.clear()

        // Get base game
        val baseGame = EpicService.getEpicGameOf(gameId)
        if (baseGame != null) {
            val updatedGame = if (isInstalled) {
                baseGame.copy(downloadSize = 0L, installSize = 0L)
            } else {
                val sizes = EpicService.fetchManifestSizes(context, baseGame.id ?: 0)
                baseGame.copy(
                    downloadSize = sizes.downloadSize,
                    installSize = sizes.installSize
                )
            }
            allDownloadableGames.add(updatedGame)
            // Base game is always selected and can't be deselected
            selectedGameIds[updatedGame.id ?: 0] = true
        }

        // Get DLCs and fetch their manifest sizes
        val dlcs = EpicService.getDLCForGame(gameId)
        dlcs.forEach { dlc ->
            // Fetch manifest to get accurate sizes for each DLC
            val sizes = EpicService.fetchManifestSizes(context, dlc.id ?: 0)
            val updatedDlc = dlc.copy(
                downloadSize = sizes.downloadSize,
                installSize = sizes.installSize
            )
            allDownloadableGames.add(updatedDlc)
            selectedGameIds[updatedDlc.id ?: 0] = if (isInstalled) {
                updatedDlc.isInstalled
            } else {
                updatedDlc.isInstalled || !updatedDlc.hasNothingToDownload()
            }
        }
        loading = false
    }

    val spaceCheckPath = remember(gameId, isInstalled) {
        if (isInstalled) {
            EpicService.getEpicGameOf(gameId)?.let { game ->
                game.installPath.takeIf { it.isNotEmpty() } ?: EpicConstants.getGameInstallPath(context, game.appName)
            } ?: EpicConstants.defaultEpicGamesPath(context)
        } else {
            EpicConstants.defaultEpicGamesPath(context)
        }
    }

    fun isLocked(game: EpicGame): Boolean =
        (game.id ?: 0) == gameId || game.hasNothingToDownload() || (isInstalled && game.isInstalled)

    fun selectedIdsToInstall(): List<Int> = allDownloadableGames
        .filter { selectedGameIds[it.id ?: 0] == true && !it.hasNothingToDownload() }
        .filter { !isInstalled || !it.isInstalled }
        .map { it.id ?: 0 }

    fun getInstallSizeInfo(): InstallSizeInfo {
        val availableBytes = try {
            StorageUtils.getAvailableSpace(spaceCheckPath)
        } catch (e: Exception) {
            0L
        }

        val selectedIds = selectedIdsToInstall().toSet()
        val selectedGames = allDownloadableGames.filter {
            (it.id ?: 0) in selectedIds
        }

        val totalDownloadBytes = selectedGames.sumOf { it.downloadSize }
        val totalInstallBytes = selectedGames.sumOf { it.installSize }

        return InstallSizeInfo(
            downloadSize = StorageUtils.formatBinarySize(totalDownloadBytes),
            installSize = StorageUtils.formatBinarySize(totalInstallBytes),
            availableSpace = StorageUtils.formatBinarySize(availableBytes),
            installBytes = totalInstallBytes,
            availableBytes = availableBytes
        )
    }

    val installSizeInfo by remember(selectedGameIds.toMap()) {
        derivedStateOf { getInstallSizeInfo() }
    }

    fun installSizeDisplay(): String {
        return context.getString(
            R.string.steam_install_space,
            installSizeInfo.downloadSize,
            installSizeInfo.installSize,
            installSizeInfo.availableSpace
        )
    }

    fun installButtonEnabled(): Boolean {
        // Check if there's enough space
        if (installSizeInfo.availableBytes < installSizeInfo.installBytes) {
            return false
        }

        return selectedIdsToInstall().isNotEmpty()
    }

    if (!visible) return

    val selectableIds = allDownloadableGames.filter { !isLocked(it) }.map { it.id ?: 0 }
    val allSelectableSelected = selectableIds.isNotEmpty() && selectableIds.all { selectedGameIds[it] == true }

    GameContentDialog(
        displayInfo = displayInfo,
        scrollState = scrollState,
        loading = loading,
        rows = allDownloadableGames.map { game ->
            val gameIdValue = game.id ?: 0
            GameContentRow(
                key = gameIdValue,
                title = game.title,
                downloadSize = game.downloadSize,
                installSize = game.installSize,
                note = if (game.hasNothingToDownload()) stringResource(R.string.epic_dlc_no_download) else null,
                installed = isInstalled && game.isInstalled,
                checked = selectedGameIds[gameIdValue] ?: false,
                enabled = !isLocked(game),
            )
        },
        onCheckedChange = { gameIdValue, checked -> selectedGameIds[gameIdValue] = checked },
        formatSize = { StorageUtils.formatBinarySize(it) },
        footerText = installSizeDisplay(),
        installEnabled = installButtonEnabled(),
        onInstall = {
            onInstall(selectedIdsToInstall())
        },
        onDismissRequest = onDismissRequest,
        showSelectAll = selectableIds.isNotEmpty(),
        allSelected = allSelectableSelected,
        onToggleSelectAll = {
            val newState = !allSelectableSelected
            selectableIds.forEach { selectedGameIds[it] = newState }
        },
    )
}

private fun EpicGame.hasNothingToDownload(): Boolean =
    isDLC && !isInstalled && downloadSize == 0L && installSize == 0L
