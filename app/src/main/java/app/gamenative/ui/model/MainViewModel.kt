package app.gamenative.ui.model

import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameProcessInfo
import app.gamenative.di.IAppTheme
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.data.MainState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.SteamUtils
import com.materialkolor.PaletteStyle
import com.winlator.xserver.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.dragonbra.javasteam.steam.handlers.steamapps.AppProcessInfo
import kotlinx.coroutines.Dispatchers
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.name
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.Job
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.async

@HiltViewModel
class MainViewModel @Inject constructor(
    private val appTheme: IAppTheme,
) : ViewModel() {

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object OnLoggedOut : MainUiEvent()
        data object LaunchApp : MainUiEvent()
        data class OnLogonEnded(val result: LoginResult) : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.i("Received is connected")
        _state.update { it.copy(isSteamConnected = true) }
    }

    private val onSteamDisconnected: (SteamEvent.Disconnected) -> Unit = {
        Timber.i("Received disconnected from Steam")
        _state.update { it.copy(isSteamConnected = false) }
    }

    private val onLoggingIn: (SteamEvent.LogonStarted) -> Unit = {
        Timber.i("Received logon started")
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = {
        Timber.i("Received logon ended")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLogonEnded(it.loginResult))
        }
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.i("Received logged out")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLoggedOut)
        }
    }

    private var bootingSplashTimeoutJob: Job? = null

    init {
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
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
        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)
    }

    init {
        _state.update {
            it.copy(
                isSteamConnected = SteamService.isConnected,
                hasCrashedLastStart = PrefManager.recentlyCrashed,
                launchedAppId = SteamService.INVALID_APP_ID,
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

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    fun setShowBootingSplash(value: Boolean) {
        _state.update { it.copy(showBootingSplash = value) }
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

    fun setLaunchedAppId(value: Int) {
        _state.update { it.copy(launchedAppId = value) }
    }

    fun setBootToContainer(value: Boolean) {
        _state.update { it.copy(bootToContainer = value) }
    }

    fun launchApp(context: Context, appId: Int) {
        // Check if we should use real Steam or replace Steam API
        val container = ContainerUtils.getContainer(context, appId)

        // Show booting splash before launching the app
        viewModelScope.launch {
            setShowBootingSplash(true)
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation))

            val apiJob = viewModelScope.async(Dispatchers.IO) {
                if (container.isLaunchRealSteam()) {
                    SteamUtils.restoreSteamApi(context, appId)
                } else {
                    SteamUtils.replaceSteamApi(context, appId)
                }
            }

            // Small delay to ensure the splash screen is visible before proceeding
            delay(100)

            apiJob.await()

            _uiEvent.send(MainUiEvent.LaunchApp)
        }
    }

    fun exitSteamApp(context: Context, appId: Int) {
        viewModelScope.launch {
            SteamService.notifyRunningProcesses()
            SteamService.closeApp(appId) { prefix ->
                PathType.from(prefix).toAbsPath(context, appId)
            }.await()
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: Int) {
        viewModelScope.launch {
            // Hide the booting splash when a window is mapped
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)

            SteamService.getAppInfoOf(appId)?.let { appInfo ->
                // TODO: this should not be a search, the app should have been launched with a specific launch config that we then use to compare
                val launchConfig = SteamService.getWindowsLaunchInfos(appId).firstOrNull {
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

                    GameProcessInfo(appId = appId, processes = processes).let {
                        // Only notify Steam if we're not using real Steam
                        // When launchRealSteam is true, let the real Steam client handle the "game is running" notification
                        val container = ContainerUtils.getContainer(context, appId)
                        if (!container.isLaunchRealSteam()) {
                            SteamService.notifyRunningProcesses(it)
                        } else {
                            Timber.i("Skipping Steam process notification - real Steam will handle this")
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
            Timber.e("Game launch error: $error")
        }
    }
}
