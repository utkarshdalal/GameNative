package app.gamenative.ui.model

import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameProcessInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.di.IAppTheme
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.ui.data.MainState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.UpdateInfo
import com.materialkolor.PaletteStyle
import com.winlator.xserver.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.dragonbra.javasteam.steam.handlers.steamapps.AppProcessInfo
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val appTheme: IAppTheme,
) : ViewModel() {

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object OnLoggedOut : MainUiEvent()
        data object LaunchApp : MainUiEvent()
        data class ExternalGameLaunch(val appId: String) : MainUiEvent()
        data class OnLogonEnded(val result: LoginResult) : MainUiEvent()
        data object ShowDiscordSupportDialog : MainUiEvent()
        data class ShowGameFeedbackDialog(val appId: String) : MainUiEvent()
        data class ShowToast(val message: String) : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> get() = _offline

    fun setOffline(value: Boolean) {
        _offline.value = value
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    fun setUpdateInfo(info: UpdateInfo?) {
        _updateInfo.value = info
    }

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.tag("MainViewModel").i("Received is connected")
        _state.update { it.copy(isSteamConnected = true) }
    }

    private val onSteamDisconnected: (SteamEvent.Disconnected) -> Unit = {
        Timber.tag("MainViewModel").i("Received disconnected from Steam")
        _state.update { it.copy(isSteamConnected = false) }
    }

    private val onLoggingIn: (SteamEvent.LogonStarted) -> Unit = {
        Timber.tag("MainViewModel").i("Received logon started")
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = {
        Timber.tag("MainViewModel").i("Received logon ended")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLogonEnded(it.loginResult))
        }
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.tag("MainViewModel").i("Received logged out")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLoggedOut)
        }
    }

    private val onExternalGameLaunch: (AndroidEvent.ExternalGameLaunch) -> Unit = {
        Timber.tag("MainViewModel").i("Received external game launch event for app ${it.appId}")
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Sending ExternalGameLaunch UI event for app ${it.appId}")
            _uiEvent.send(MainUiEvent.ExternalGameLaunch(it.appId))
        }
    }

    private val onSetBootingSplashText: (AndroidEvent.SetBootingSplashText) -> Unit = {
        setBootingSplashText(it.text)
    }

    private var bootingSplashTimeoutJob: Job? = null

    init {
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.on<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.on<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.on<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.on<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit>(onLoggedOut)

        viewModelScope.launch {
            appTheme.themeFlow.collect { value ->
                _state.update { it.copy(appTheme = value) }
            }
        }

        viewModelScope.launch {
            appTheme.paletteFlow.collect { value ->
                _state.update { it.copy(paletteStyle = value) }
            }
        }
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.off<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.off<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)
    }

    init {
        _state.update {
            it.copy(
                isSteamConnected = SteamService.isConnected,
                hasCrashedLastStart = PrefManager.recentlyCrashed,
                launchedAppId = "",
            )
        }
    }

    fun setTheme(value: AppTheme) {
        appTheme.currentTheme = value
    }

    fun setPalette(value: PaletteStyle) {
        appTheme.currentPalette = value
    }

    fun setAnnoyingDialogShown(value: Boolean) {
        _state.update { it.copy(annoyingDialogShown = value) }
    }

    fun setLoadingDialogVisible(value: Boolean) {
        _state.update { it.copy(loadingDialogVisible = value) }
    }

    fun setLoadingDialogProgress(value: Float) {
        _state.update { it.copy(loadingDialogProgress = value) }
    }

    fun setLoadingDialogMessage(value: String) {
        _state.update { it.copy(loadingDialogMessage = value) }
    }

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    fun setShowBootingSplash(value: Boolean) {
        _state.update { it.copy(showBootingSplash = value) }
    }

    fun setBootingSplashText(value: String) {
        _state.update { it.copy(bootingSplashText = value) }
    }

    fun setCurrentScreen(currentScreen: String?) {
        val screen = when (currentScreen) {
            PluviaScreen.LoginUser.route -> PluviaScreen.LoginUser
            PluviaScreen.Home.route -> PluviaScreen.Home
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            PluviaScreen.Settings.route -> PluviaScreen.Settings
            PluviaScreen.Chat.route -> PluviaScreen.Chat
            else -> PluviaScreen.LoginUser
        }

        setCurrentScreen(screen)
    }

    fun setCurrentScreen(value: PluviaScreen) {
        _state.update { it.copy(currentScreen = value) }
    }

    fun setHasCrashedLastStart(value: Boolean) {
        if (value.not()) {
            PrefManager.recentlyCrashed = false
        }
        _state.update { it.copy(hasCrashedLastStart = value) }
    }

    fun setScreen() {
        _state.update { it.copy(resettedScreen = it.currentScreen) }
    }

    fun setLaunchedAppId(value: String) {
        _state.update { it.copy(launchedAppId = value) }
    }

    fun setBootToContainer(value: Boolean) {
        _state.update { it.copy(bootToContainer = value) }
    }

    fun setTestGraphics(value: Boolean) {
        _state.update { it.copy(testGraphics = value) }
    }

    fun launchApp(context: Context, appId: String) {
        // Show booting splash before launching the app
        viewModelScope.launch {
            setShowBootingSplash(true)
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation))

            val apiJob = viewModelScope.async(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
                if (gameSource == GameSource.STEAM) {
                    if (container.isLaunchRealSteam()) {
                        SteamUtils.restoreSteamApi(context, appId)
                    } else {
                        val offline = _offline.value
                        if (container.isUseLegacyDRM) {
                            SteamUtils.replaceSteamApi(context, appId, offline)
                        } else {
                            SteamUtils.replaceSteamclientDll(context, appId, offline)
                        }
                    }
                }
            }

            // Small delay to ensure the splash screen is visible before proceeding
            delay(100)

            apiJob.await()

            _uiEvent.send(MainUiEvent.LaunchApp)
        }
    }

    fun exitSteamApp(context: Context, appId: String) {
        viewModelScope.launch {
            Timber.tag("Exit").i("Exiting, getting feedback for appId: $appId")
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            // Check if we have a temporary override before doing anything
            val hadTemporaryOverride = IntentLaunchManager.hasTemporaryOverride(appId)

            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            Timber.tag("Exit").i("Got game id: $gameId")
            SteamService.notifyRunningProcesses()

            // Check if this is a GOG or Epic game and sync cloud saves
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            if (gameSource == GameSource.GOG) {
                Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for $appId — syncing cloud saves after close")
                // Sync cloud saves (upload local changes to cloud)
                // Run in background, don't block UI
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        Timber.tag("GOG").d("[Cloud Saves] Starting post-game upload sync for $appId")
                        val syncSuccess = app.gamenative.service.gog.GOGService.syncCloudSaves(
                            context = context,
                            appId = appId,
                            preferredAction = "upload",
                        )
                        if (syncSuccess) {
                            Timber.tag("GOG").i("[Cloud Saves] Upload sync completed successfully for $appId")
                        } else {
                            Timber.tag("GOG").w("[Cloud Saves] Upload sync failed for $appId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("GOG").e(e, "[Cloud Saves] Exception during upload sync for $appId")
                    }
                }
            } else if (gameSource == GameSource.EPIC) {
                Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for $appId — syncing cloud saves after close")
                // Sync cloud saves (upload local changes to cloud)
                // Run in background, don't block UI
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        Timber.tag("Epic").d("[Cloud Saves] Starting post-game upload sync for $gameId")
                        val syncSuccess = app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
                            context = context,
                            appId = gameId,
                            preferredAction = "upload",
                        )
                        if (syncSuccess) {
                            Timber.tag("Epic").i("[Cloud Saves] Upload sync completed successfully for $gameId")
                        } else {
                            Timber.tag("Epic").w("[Cloud Saves] Upload sync failed for $gameId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "[Cloud Saves] Exception during upload sync for $gameId")
                    }
                }
            } else {
                // For Steam games, sync cloud saves
                SteamService.closeApp(gameId, isOffline.value) { prefix ->
                    PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                }.await()
            }

            // Prompt user to save temporary container configuration if one was applied
            if (hadTemporaryOverride) {
                PluviaApp.events.emit(AndroidEvent.PromptSaveContainerConfig(appId))
                // Dialog handler in PluviaMain manages the save/discard logic
            }

            // After app closes, check if we need to show the feedback dialog
            try {
                // Do not show the Feedback form for non-steam games until we can support.
                    val container = ContainerUtils.getContainer(context, appId)

                    val shown = container.getExtra("discord_support_prompt_shown", "false") == "true"
                    val configChanged = container.getExtra("config_changed", "false") == "true"
                    if (!shown) {
                        container.putExtra("discord_support_prompt_shown", "true")
                        container.saveData()
                        _uiEvent.send(MainUiEvent.ShowGameFeedbackDialog(appId))
                    }

                    // Only show feedback if container config was changed before this game run
                    if (configChanged) {
                        // Clear the flag
                        container.putExtra("config_changed", "false")
                        container.saveData()
                        // Show the feedback dialog
                        _uiEvent.send(MainUiEvent.ShowGameFeedbackDialog(appId))
                    }
            } catch (_: Exception) {
                // ignore container errors
            }
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: String) {
        viewModelScope.launch {
            // Hide the booting splash when a window is mapped
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)

            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

            SteamService.getAppInfoOf(gameId)?.let { appInfo ->
                // TODO: this should not be a search, the app should have been launched with a specific launch config that we then use to compare
                val launchConfig = SteamService.getWindowsLaunchInfos(gameId).firstOrNull {
                    val gameExe = Paths.get(it.executable.replace('\\', '/')).name.lowercase()
                    val windowExe = window.className.lowercase()
                    gameExe == windowExe
                }

                if (launchConfig != null) {
                    val steamProcessId = Process.myPid()
                    val processes = mutableListOf<AppProcessInfo>()
                    var currentWindow: Window = window
                    do {
                        var parentWindow: Window? = window.parent
                        val process = if (parentWindow != null && parentWindow.className.lowercase() != "explorer.exe") {
                            val processId = currentWindow.processId
                            val parentProcessId = parentWindow.processId
                            currentWindow = parentWindow

                            AppProcessInfo(processId, parentProcessId, false)
                        } else {
                            parentWindow = null

                            AppProcessInfo(currentWindow.processId, steamProcessId, true)
                        }
                        processes.add(process)
                    } while (parentWindow != null)

                    GameProcessInfo(appId = gameId, processes = processes).let {
                        // Only notify Steam if we're not using real Steam
                        // When launchRealSteam is true, let the real Steam client handle the "game is running" notification
                        val shouldLaunchRealSteam = try {
                            val container = ContainerUtils.getContainer(context, appId)
                            container.isLaunchRealSteam()
                        } catch (e: Exception) {
                            // Container might not exist, default to notifying Steam
                            false
                        }

                        if (!shouldLaunchRealSteam) {
                            SteamService.notifyRunningProcesses(it)
                        } else {
                            Timber.tag("MainViewModel").i("Skipping Steam process notification - real Steam will handle this")
                        }
                    }
                }
            }
        }
    }

    fun onGameLaunchError(error: String) {
        viewModelScope.launch {
            // Hide the splash screen if it's still showing
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)

            // You could also show an error dialog here if needed
            Timber.tag("MainViewModel").e("Game launch error: $error")
        }
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.ShowToast(message))
        }
    }
}
