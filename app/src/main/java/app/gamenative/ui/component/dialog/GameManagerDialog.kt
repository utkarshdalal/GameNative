package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.R
import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.INVALID_APP_ID
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.StorageUtils
import kotlin.collections.orEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstallSizeInfo(
    val downloadSize: String,
    val installSize: String,
    val availableSpace: String,
    val installBytes: Long,
    val availableBytes: Long,
)

@Composable
fun GameManagerDialog(
    visible: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    branch: String? = null,
    onInstall: (List<Int>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val downloadableDepots = remember { mutableStateMapOf<Int, DepotInfo>() }
    val allDownloadableApps = remember { mutableStateListOf<Pair<Int, DepotInfo>>() }
    val selectedAppIds = remember { mutableStateMapOf<Int, Boolean>() }
    val enabledAppIds = remember { mutableStateMapOf<Int, Boolean>() }
    var loading by remember { mutableStateOf(true) }

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId

    val isBaseGameInstalled = remember(gameId) {
        SteamService.isAppInstalled(gameId)
    }
    val installedApp = remember(gameId, isBaseGameInstalled) {
        if (isBaseGameInstalled) SteamService.getInstalledApp(gameId) else null
    }
    val installedDlcIds = installedApp?.dlcDepots.orEmpty()

    val indirectDlcAppIds = remember(gameId) {
        SteamService.getDownloadableDlcAppsOf(gameId).orEmpty().map { it.id }
    }

    val mainAppDlcIdsWithoutProperDepotDlcIds = remember(gameId) {
        SteamService.getMainAppDlcIdsWithoutProperDepotDlcIds(gameId).toList()
    }

    LaunchedEffect(visible) {
        // Keep the scroll off the critical path: on D-pad devices (Android TV) the focus
        // system scrolls the dialog as it opens, cancelling this animation and killing the
        // depot load below, which left the dialog at 0 B with Install disabled.
        launch { scrollState.scrollTo(0) }

        loading = true
        downloadableDepots.clear()
        allDownloadableApps.clear()

        // Get Downloadable Depots
        val allPossibleDownloadableDepots = withContext(Dispatchers.IO) { SteamService.getDownloadableDepots(gameId) }
        downloadableDepots.putAll(allPossibleDownloadableDepots)

        // Get Optional DLC IDs
        val optionalDlcIds = allPossibleDownloadableDepots
            .filter { it.value.optionalDlcId == it.value.dlcAppId }
            .map { it.value.dlcAppId }

        // Add DLCs
        downloadableDepots
            .toSortedMap()
            .filter { (_, depot) ->
                return@filter depot.dlcAppId != INVALID_APP_ID // Skip Main App
            }.values
                .groupBy { it.dlcAppId }
                .mapValues { it.value.first() }
                .toMap()
            .forEach { (_, depotInfo) ->
                allDownloadableApps.add(Pair(depotInfo.dlcAppId, depotInfo))
                val installed = SteamService.isAppInstalled(depotInfo.dlcAppId)
                selectedAppIds[depotInfo.dlcAppId] =
                        installed || // For installed Base Game and Indirect DLC App
                        installedDlcIds.contains(depotInfo.dlcAppId) || // For installed DLC from Main Depot
                        ( !indirectDlcAppIds.contains(depotInfo.dlcAppId) && !optionalDlcIds.contains(depotInfo.dlcAppId) ) // Not in indirect DLC and not in optional DLC ids

                enabledAppIds[depotInfo.dlcAppId] = !installedDlcIds.contains(depotInfo.dlcAppId) && !installed
            }

        allDownloadableApps.sortBy { it.first }

        // Add Base Game
        val baseDepot = downloadableDepots.values.firstOrNull { it.dlcAppId == INVALID_APP_ID }
        if (baseDepot != null) {
            allDownloadableApps.add(0, Pair(gameId, baseDepot))
            selectedAppIds[gameId] = true
            enabledAppIds[gameId] = false
        }
        loading = false
    }

    fun getDepotAppName(depotInfo: DepotInfo): String {
        if (depotInfo.dlcAppId == INVALID_APP_ID) {
            return displayInfo.name
        }

        val app = SteamService.getAppInfoOf(depotInfo.dlcAppId)
        if (app != null) {
            return app.name
        }

        return "DLC ${depotInfo.dlcAppId}"
    }

    val selectedBranch = branch ?: installedApp?.branch ?: "public"

    fun manifestFor(depot: DepotInfo): ManifestInfo? =
        depot.manifests[selectedBranch]
            ?: depot.encryptedManifests[selectedBranch]
            ?: depot.manifests["public"]

    fun getSizeInfo(dlcAppId: Int): Pair<Long, Long> {
        if (dlcAppId == INVALID_APP_ID || dlcAppId == gameId) {
            val depotsForBaseGame = downloadableDepots.filter { (_, depot) ->
                depot.dlcAppId == INVALID_APP_ID
            }

            val installBytes = depotsForBaseGame.values.sumOf {
                manifestFor(it)?.size ?: 0
            }
            val downloadBytes = depotsForBaseGame.values.sumOf {
                SteamUtils.getDownloadBytes(manifestFor(it))
            }

            return Pair(downloadBytes, installBytes)
        }

        val depotsForDlc = downloadableDepots.filter { (_, depot) ->
            depot.dlcAppId == dlcAppId
        }

        val installBytes = depotsForDlc.values.sumOf {
            manifestFor(it)?.size ?: 0
        }
        val downloadBytes = depotsForDlc.values.sumOf {
            SteamUtils.getDownloadBytes(manifestFor(it))
        }

        return Pair(downloadBytes, installBytes)
    }

    fun getInstallSizeInfo(): InstallSizeInfo {
        val availableBytes = StorageUtils.getAvailableSpaceForUncreatedPath(SteamService.getAppDirPath(gameId))

        val baseGameInstallBytes = if (!isBaseGameInstalled) {
            downloadableDepots
                .filter { (_, depot) ->
                    depot.dlcAppId == INVALID_APP_ID
                }.values.sumOf { manifestFor(it)?.size ?: 0 }
        } else {
            0L
        }

        val baseGameDownloadBytes = if (!isBaseGameInstalled) {
            downloadableDepots
                .filter { (_, depot) ->
                    depot.dlcAppId == INVALID_APP_ID
                }.values.sumOf {
                    SteamUtils.getDownloadBytes(manifestFor(it))
                }
        } else {
            0L
        }

        val selectedInstallBytes = downloadableDepots
            .filter { (_, depot) ->
                selectedAppIds[depot.dlcAppId] == true && enabledAppIds[depot.dlcAppId] == true
            }
            .values.sumOf { manifestFor(it)?.size ?: 0 }

        val selectedDownloadBytes = downloadableDepots
            .filter { (_, depot) ->
                selectedAppIds[depot.dlcAppId] == true && enabledAppIds[depot.dlcAppId] == true
            }
            .values.sumOf {
                SteamUtils.getDownloadBytes(manifestFor(it))
            }

        return InstallSizeInfo(
            downloadSize = StorageUtils.formatBinarySize(baseGameDownloadBytes + selectedDownloadBytes),
            installSize = StorageUtils.formatBinarySize(baseGameInstallBytes + selectedInstallBytes),
            availableSpace = StorageUtils.formatBinarySize(availableBytes),
            installBytes = baseGameInstallBytes + selectedInstallBytes,
            availableBytes = availableBytes
        )
    }

    val selectableAppIds by remember(enabledAppIds.toMap()) {
        derivedStateOf {
            enabledAppIds.filter { it.value }.keys.toList()
        }
    }

    val allSelectableSelected by remember(selectedAppIds.toMap(), selectableAppIds) {
        derivedStateOf {
            selectableAppIds.isNotEmpty() && selectableAppIds.all { selectedAppIds[it] == true }
        }
    }

    val installSizeInfo by remember(downloadableDepots.keys.toSet(), selectedAppIds.toMap(), enabledAppIds.toMap()) {
        derivedStateOf { getInstallSizeInfo() }
    }

    fun installSizeDisplay() : String {
        return context.getString(
            R.string.steam_install_space,
            installSizeInfo.downloadSize,
            installSizeInfo.installSize,
            installSizeInfo.availableSpace
        )
    }

    fun installButtonEnabled() : Boolean {
        if (installSizeInfo.availableBytes < installSizeInfo.installBytes) {
            return false
        }

        if (isBaseGameInstalled) {
            val installed = installedDlcIds.toSet() - mainAppDlcIdsWithoutProperDepotDlcIds.toSet()
            val realSelectedAppIds = selectedAppIds.filter { it.value }.keys - installed
            return (realSelectedAppIds.size - 1) > 0 // -1 for main app
        }

        return selectedAppIds.filter { it.value }.isNotEmpty()
    }

    if (!visible) return

    GameContentDialog(
        displayInfo = displayInfo,
        scrollState = scrollState,
        loading = loading,
        rows = allDownloadableApps.map { (dlcAppId, depotInfo) ->
            val (downloadBytes, installBytes) = getSizeInfo(dlcAppId)
            GameContentRow(
                key = dlcAppId,
                title = getDepotAppName(depotInfo),
                downloadSize = downloadBytes,
                installSize = installBytes,
                checked = selectedAppIds[dlcAppId] ?: false,
                enabled = enabledAppIds[dlcAppId] ?: false,
            )
        },
        onCheckedChange = { dlcAppId, isChecked ->
            // Update the local (unsaved) state only
            selectedAppIds[dlcAppId] = isChecked
        },
        formatSize = { StorageUtils.formatBinarySize(it) },
        footerText = installSizeDisplay(),
        installEnabled = installButtonEnabled(),
        onInstall = {
            onInstall(selectedAppIds
                .filter { selectedId -> selectedId.key in enabledAppIds.filter { enabledId -> enabledId.value } }
                .filter { selectedId -> selectedId.value }.keys.toList())
        },
        onDismissRequest = onDismissRequest,
        headerNote = if (branch != null && branch != "public") {
            stringResource(R.string.steam_installing_branch, branch)
        } else {
            null
        },
        showSelectAll = selectableAppIds.isNotEmpty(),
        allSelected = allSelectableSelected,
        onToggleSelectAll = {
            val newState = !allSelectableSelected
            selectableAppIds.forEach { appId ->
                selectedAppIds[appId] = newState
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
fun Preview_GameManagerDialog() {
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
        GameManagerDialog(
            visible = true,
            onGetDisplayInfo = {
                return@GameManagerDialog displayInfo
            },
            onInstall = {},
            onDismissRequest = {}
        )
    }
}
