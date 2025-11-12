package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.core.net.toUri
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.enums.PathType
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirPath
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.SteamUtils
import com.posthog.PostHog
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.fexcore.FEXCoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Steam-specific implementation of BaseAppScreen
 */
class SteamAppScreen : BaseAppScreen() {
    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        val gameId = libraryItem.gameId
        val appInfo = remember(libraryItem.appId) {
            SteamService.getAppInfoOf(gameId)
        } ?: return GameDisplayInfo(
            name = libraryItem.name,
            developer = "",
            releaseDate = 0L,
            heroImageUrl = null,
            iconUrl = null,
            gameId = gameId,
            appId = libraryItem.appId,
        )

        val isInstalled = remember(libraryItem.appId) {
            SteamService.isAppInstalled(gameId)
        }

        // Get hero image URL
        val heroImageUrl = remember(appInfo.id) {
            appInfo.getHeroUrl()
        }

        // Get icon URL
        val iconUrl = remember(appInfo.id) {
            appInfo.iconUrl
        }

        // Get install location
        val installLocation = remember(isInstalled, gameId) {
            if (isInstalled) {
                getAppDirPath(gameId)
            } else null
        }

        // Get size on disk (async, will update via state)
        var sizeOnDisk by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(isInstalled, gameId) {
            if (isInstalled) {
                DownloadService.getSizeOnDiskDisplay(gameId) {
                    sizeOnDisk = it
                }
            }
        }

        // Get size from store
        val sizeFromStore = remember(gameId) {
            if (!isInstalled) {
                DownloadService.getSizeFromStoreDisplay(gameId)
            } else null
        }

        // Get last played text
        val lastPlayedText = remember(isInstalled, gameId) {
            if (isInstalled) {
                val path = getAppDirPath(gameId)
                val file = java.io.File(path)
                if (file.exists()) {
                    SteamUtils.fromSteamTime((file.lastModified() / 1000).toInt())
                } else {
                    "Never"
                }
            } else {
                "Never"
            }
        }

        // Get playtime text
        var playtimeText by remember { mutableStateOf("0 hrs") }
        LaunchedEffect(gameId) {
            val steamID = SteamService.userSteamId?.accountID?.toLong()
            if (steamID != null) {
                val games = SteamService.getOwnedGames(steamID)
                val game = games.firstOrNull { it.appId == gameId }
                playtimeText = if (game != null) {
                    SteamUtils.formatPlayTime(game.playtimeForever) + " hrs"
                } else "0 hrs"
            }
        }

        return GameDisplayInfo(
            name = appInfo.name,
            developer = appInfo.developer,
            releaseDate = appInfo.releaseDate,
            heroImageUrl = heroImageUrl,
            iconUrl = iconUrl,
            gameId = gameId,
            appId = libraryItem.appId,
            installLocation = installLocation,
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = sizeFromStore,
            lastPlayedText = lastPlayedText,
            playtimeText = playtimeText,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        return SteamService.isAppInstalled(libraryItem.gameId)
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val appInfo = SteamService.getAppInfoOf(libraryItem.gameId) ?: return false
        return appInfo.branches.isNotEmpty() && appInfo.depots.isNotEmpty()
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        val downloadInfo = SteamService.getAppDownloadInfo(libraryItem.gameId)
        return downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val downloadInfo = SteamService.getAppDownloadInfo(libraryItem.gameId)
        return downloadInfo?.getProgress() ?: 0f
    }

    override suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        return SteamService.isUpdatePending(libraryItem.gameId)
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        val gameId = libraryItem.gameId
        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = SteamService.isAppInstalled(gameId)

        if (isDownloading) {
            // This will be handled by dialogs in AdditionalDialogs
            // For now, just cancel
            downloadInfo?.cancel()
        } else if (SteamService.hasPartialDownload(gameId)) {
            // Resume incomplete download
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(gameId)
            }
        } else if (!isInstalled) {
            // Request permissions and show install dialog
            // This will be handled by AdditionalDialogs
        } else {
            // Already installed: launch app
            val appInfo = SteamService.getAppInfoOf(gameId)
            PostHog.capture(
                event = "game_launched",
                properties = mapOf("game_name" to (appInfo?.name ?: ""))
            )
            onClickPlay(false)
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId
        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f

        if (isDownloading) {
            downloadInfo?.cancel()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(gameId)
            }
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        // This will be handled by dialogs in AdditionalDialogs
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        CoroutineScope(Dispatchers.IO).launch {
            SteamService.downloadApp(libraryItem.gameId)
        }
    }

    override fun getOptionsMenu(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit
    ): List<AppMenuOption> {
        val gameId = libraryItem.gameId
        val appId = libraryItem.appId
        val appInfo = SteamService.getAppInfoOf(gameId) ?: return emptyList()
        val isInstalled = SteamService.isAppInstalled(gameId)

        val menuOptions = mutableListOf<AppMenuOption>()

        // Edit Container option (always available)
        menuOptions.add(
            AppMenuOption(
                optionType = AppOptionMenuType.EditContainer,
                onClick = onEditContainer
            )
        )

        if (isInstalled) {
            menuOptions.addAll(
                listOf(
                    AppMenuOption(
                        AppOptionMenuType.RunContainer,
                        onClick = {
                            PostHog.capture(
                                event = "container_opened",
                                properties = mapOf("game_name" to appInfo.name)
                            )
                            onClickPlay(true)
                        },
                    ),
                    AppMenuOption(
                        AppOptionMenuType.ResetToDefaults,
                        onClick = {
                            // This will be handled by dialogs
                        },
                    ),
                    AppMenuOption(
                        AppOptionMenuType.ResetDrm,
                        onClick = {
                            val container = ContainerUtils.getOrCreateContainer(context, appId)
                            MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_REPLACED)
                            MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_RESTORED)
                            container.isNeedsUnpacking = true
                            container.saveData()
                        },
                    ),
                    AppMenuOption(
                        optionType = AppOptionMenuType.CreateShortcut,
                        onClick = {
                            // This will be handled by dialogs
                        }
                    ),
                    AppMenuOption(
                        optionType = AppOptionMenuType.ExportFrontend,
                        onClick = {
                            // This will be handled by export launcher
                        }
                    ),
                    AppMenuOption(
                        AppOptionMenuType.VerifyFiles,
                        onClick = {
                            // This will be handled by dialogs
                        },
                    ),
                    AppMenuOption(
                        AppOptionMenuType.Update,
                        onClick = {
                            // This will be handled by dialogs
                        },
                    ),
                    AppMenuOption(
                        AppOptionMenuType.Uninstall,
                        onClick = {
                            // This will be handled by dialogs
                        },
                    ),
                    AppMenuOption(
                        AppOptionMenuType.ForceCloudSync,
                        onClick = {
                            PostHog.capture(
                                event = "cloud_sync_forced",
                                properties = mapOf("game_name" to appInfo.name)
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
                                    prefixToPath = prefixToPath
                                ).await()

                                withContext(Dispatchers.Main) {
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
                )
            )
        }

        menuOptions.add(
            AppMenuOption(
                optionType = AppOptionMenuType.SubmitFeedback,
                onClick = {
                    PluviaApp.events.emit(AndroidEvent.ShowGameFeedback(appId))
                },
            )
        )

        menuOptions.add(
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
        )

        return menuOptions
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        var containerData = ContainerUtils.toContainerData(container)
        // Seed FEXCore UI fields from actual per-container config file so values show up when editing
        try {
            val fex = FEXCoreManager.readFEXCoreSettings(context, container)
            containerData = containerData.copy(
                fexcoreTSOMode = fex[0],
                fexcoreX87Mode = fex[1],
                fexcoreMultiBlock = fex[2],
            )
        } catch (_: Throwable) { }
        return containerData
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit
    ) {
        // Steam-specific dialogs are complex and require state management
        // For now, we'll handle them in a simplified way
        // The full implementation would require moving all the dialog state management here
    }
}

