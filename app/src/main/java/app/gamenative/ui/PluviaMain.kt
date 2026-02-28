package app.gamenative.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.gamenative.BuildConfig
import app.gamenative.Constants
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.component.ConnectionStatusBanner
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.GameFeedbackDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.GameFeedbackDialogState
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.HomeScreen
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.login.UserLoginScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PlatformAuthUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.GameFeedbackUtils
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.UpdateChecker
import app.gamenative.utils.UpdateInfo
import app.gamenative.utils.UpdateInstaller
import app.gamenative.utils.LaunchDependencies
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import java.io.File
import java.util.Date
import java.util.EnumSet
import kotlin.reflect.KFunction2
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private fun NavHostController.navigateFromLoginIfNeeded(
    targetRoute: String,
    logTag: String = "PluviaMain",
) {
    val currentRoute = currentDestination?.route
    if (currentRoute == PluviaScreen.LoginUser.route) {
        Timber.tag(logTag).i("Navigating from LoginUser to $targetRoute")
        navigate(targetRoute) {
            popUpTo(PluviaScreen.LoginUser.route) {
                inclusive = true
            }
        }
    }
}

private sealed class GameResolutionResult {
    data class Success(
        val finalAppId: String,
        val gameId: Int,
        val isSteamInstalled: Boolean,
        val isCustomGame: Boolean,
    ) : GameResolutionResult()
    data class NotFound(
        val gameId: Int,
        val originalAppId: String,
    ) : GameResolutionResult()
}

private fun resolveGameAppId(appId: String): GameResolutionResult {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val isInstalled = when (gameSource) {
        GameSource.STEAM -> {
            SteamService.isAppInstalled(gameId)
        }

        GameSource.GOG -> {
            GOGService.isGameInstalled(gameId.toString())
        }

        GameSource.EPIC -> {
            EpicService.isGameInstalled(gameId)
        }

        GameSource.AMAZON -> {
            AmazonService.isGameInstalledByAppId(gameId)
        }

        GameSource.CUSTOM_GAME -> {
            CustomGameScanner.isGameInstalled(gameId)
        }
    }

    if (!isInstalled) {
        return GameResolutionResult.NotFound(
            gameId = gameId,
            originalAppId = appId,
        )
    }

    val isSteamInstalled = gameSource == GameSource.STEAM && isInstalled
    val isCustomGame = gameSource == GameSource.CUSTOM_GAME

    return GameResolutionResult.Success(
        finalAppId = appId,
        gameId = gameId,
        isSteamInstalled = isSteamInstalled,
        isCustomGame = isCustomGame,
    )
}

private fun resolveNotInstalledGameName(context: Context, appId: String, gameId: Int): String {
    return when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
        GameSource.STEAM -> SteamService.getAppInfoOf(gameId)?.name
        GameSource.GOG -> GOGService.getGOGGameOf(gameId.toString())?.title
        GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.title
        GameSource.AMAZON -> null // Amazon game title lookup requires suspend, use fallback
        GameSource.CUSTOM_GAME -> {
            val customAppId = "${GameSource.CUSTOM_GAME.name}_$gameId"
            CustomGameScanner.getFolderPathFromAppId(customAppId)
                ?.let { java.io.File(it).name }
        }
    } ?: context.getString(R.string.unknown_app)
}

@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }

    var gameFeedbackState by rememberSaveable(stateSaver = GameFeedbackDialogState.Saver) {
        mutableStateOf(GameFeedbackDialogState(false))
    }

    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }

    var isConnecting by rememberSaveable { mutableStateOf(false) }

    var gameBackAction by remember { mutableStateOf<() -> Unit?>({}) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    var openContainerConfigForAppId by rememberSaveable { mutableStateOf<String?>(null) }

    // Track if connection banner was dismissed by user
    var connectionBannerDismissed by rememberSaveable { mutableStateOf(false) }

    // Track previous connection state to detect actual changes (not just recomposition)
    val previousConnectionState = remember { mutableStateOf(state.connectionState) }

    // Reset dismissed state only when connection state actually changes
    LaunchedEffect(state.connectionState) {
        if (previousConnectionState.value != state.connectionState) {
            connectionBannerDismissed = false
            previousConnectionState.value = state.connectionState
        }
    }

    // Check for updates on app start
    LaunchedEffect(Unit) {
        val checkedUpdateInfo = UpdateChecker.checkForUpdate(context)
        if (checkedUpdateInfo != null) {
            val appVersionCode = BuildConfig.VERSION_CODE
            val serverVersionCode = checkedUpdateInfo.versionCode
            Timber.i("Update check: app versionCode=$appVersionCode, server versionCode=$serverVersionCode")
            if (appVersionCode < serverVersionCode) {
                updateInfo = checkedUpdateInfo
                viewModel.setUpdateInfo(checkedUpdateInfo)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.LaunchApp -> {
                    navController.navigate(PluviaScreen.XServer.route)
                }

                is MainViewModel.MainUiEvent.ExternalGameLaunch -> {
                    Timber.i("[PluviaMain]: Received ExternalGameLaunch UI event for app ${event.appId}")

                    when (val resolution = resolveGameAppId(event.appId)) {
                        is GameResolutionResult.Success -> {
                            Timber.i("[PluviaMain]: Using appId: ${resolution.finalAppId} (original: ${event.appId}, isSteamInstalled: ${resolution.isSteamInstalled}, isCustomGame: ${resolution.isCustomGame})")

                            viewModel.setLaunchedAppId(resolution.finalAppId)
                            viewModel.setBootToContainer(false)
                            preLaunchApp(
                                context = context,
                                appId = resolution.finalAppId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = setMessageDialogState,
                                onSuccess = viewModel::launchApp,
                            )
                        }

                        is GameResolutionResult.NotFound -> {
                            val appName = resolveNotInstalledGameName(context, resolution.originalAppId, resolution.gameId)
                            Timber.w("[PluviaMain]: Game not installed: $appName (${event.appId})")
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.game_not_installed_title),
                                message = context.getString(R.string.game_not_installed_message, appName),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }

                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (SteamService.keepAlive){
                        gameBackAction?.invoke() ?: run { navController.popBackStack() }
                    } else if (hasBack) {
                        // TODO: check if back leads to log out and present confidence modal
                        navController.popBackStack()
                    } else {
                        // TODO: quit app?
                    }
                }

                MainViewModel.MainUiEvent.OnLoggedOut -> {
                    if (SteamService.keepAlive) {
                        return@collect
                    }

                    // Clear persisted route so next login starts fresh from Home
                    viewModel.clearPersistedRoute()
                    // Pop stack and go back to login
                    navController.popBackStack(
                        route = PluviaScreen.LoginUser.route,
                        inclusive = false,
                        saveState = false,
                    )
                }

                is MainViewModel.MainUiEvent.OnLogonEnded -> {
                    when (event.result) {
                        LoginResult.Success -> {
                            if (MainActivity.hasPendingLaunchRequest()) {
                                MainActivity.consumePendingLaunchRequest()?.let { launchRequest ->
                                    Timber.tag("IntentLaunch")
                                        .i("Processing pending launch request for app ${launchRequest.appId} (user is now logged in)")
                                    when (val resolution = resolveGameAppId(launchRequest.appId)) {
                                        is GameResolutionResult.NotFound -> {
                                            val appName = resolveNotInstalledGameName(context, resolution.originalAppId, resolution.gameId)
                                            Timber.tag("IntentLaunch").w("Game not installed: $appName (${launchRequest.appId})")
                                            msgDialogState = MessageDialogState(
                                                visible = true,
                                                type = DialogType.SYNC_FAIL,
                                                title = context.getString(R.string.game_not_installed_title),
                                                message = context.getString(R.string.game_not_installed_message, appName),
                                                dismissBtnText = context.getString(R.string.ok),
                                            )
                                            return@let
                                        }

                                        is GameResolutionResult.Success -> {
                                            if (launchRequest.containerConfig != null) {
                                                IntentLaunchManager.applyTemporaryConfigOverride(
                                                    context,
                                                    launchRequest.appId,
                                                    launchRequest.containerConfig,
                                                )
                                                Timber.tag("IntentLaunch")
                                                    .i("Applied container config override for app ${launchRequest.appId}")
                                            }

                                            // Navigate to Home if not already there (for pending launch requests)
                                            if (navController.currentDestination?.route != PluviaScreen.Home.route) {
                                                navController.navigate(PluviaScreen.Home.route) {
                                                    popUpTo(navController.graph.startDestinationId) {
                                                        saveState = false
                                                    }
                                                }
                                            }

                                            viewModel.setLaunchedAppId(launchRequest.appId)
                                            viewModel.setBootToContainer(false)
                                            preLaunchApp(
                                                context = context,
                                                appId = launchRequest.appId,
                                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                                setMessageDialogState = setMessageDialogState,
                                                onSuccess = viewModel::launchApp,
                                            )
                                        }
                                    }
                                }
                            } else if (PluviaApp.xEnvironment == null) {
                                val targetRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                                navController.navigateFromLoginIfNeeded(targetRoute, "LogonEnded")
                            }
                        }

                        else -> Timber.i("Received non-result: ${event.result}")
                    }
                }

                MainViewModel.MainUiEvent.ShowDiscordSupportDialog -> {
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.DISCORD,
                        title = context.getString(R.string.main_discord_support_title),
                        message = context.getString(R.string.main_discord_support_message),
                        confirmBtnText = context.getString(R.string.main_open_discord),
                        dismissBtnText = context.getString(R.string.close),
                    )
                }

                is MainViewModel.MainUiEvent.ShowGameFeedbackDialog -> {
                    gameFeedbackState = GameFeedbackDialogState(
                        visible = true,
                        appId = event.appId,
                    )
                }

                is MainViewModel.MainUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(navController) {
        Timber.i("navController changed")

        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)

            Timber.i("Creating on destination changed listener")

            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                Timber.i("onDestinationChanged to ${destination.route}")
                // in order not to trigger the screen changed launch effect
                viewModel.setCurrentScreen(destination.route)
            }
            PluviaApp.events.emit(AndroidEvent.StartOrientator)
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }

        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            // Log.d("PluviaMain", "Screen changed to $currentScreen, resetting some values")
            // TODO: remove this if statement once XServerScreen orientation change bug is fixed
            if (state.currentScreen != PluviaScreen.XServer) {
                // Hide or show status bar based on if in game or not
                val shouldShowStatusBar = !PrefManager.hideStatusBarWhenNotInGame
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(shouldShowStatusBar))

                // reset system ui visibility based on user preference
                // TODO: add option for user to set
                // reset available orientations
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Only attempt reconnection if not already connected/connecting and not in offline mode
            val shouldAttemptReconnect = !state.isSteamConnected &&
                !isConnecting &&
                !SteamService.keepAlive

            if (shouldAttemptReconnect) {
                Timber.d("[PluviaMain]: Steam not connected - attempting reconnection")
                isConnecting = true
                viewModel.startConnecting() // Update ViewModel state for UI
                context.startForegroundService(Intent(context, SteamService::class.java))
            }

            // Start GOGService if user has GOG
            if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context) &&
                !app.gamenative.service.gog.GOGService.isRunning
            ) {
                Timber.tag("GOG").d("[PluviaMain]: Starting GOGService for logged-in user")
                app.gamenative.service.gog.GOGService.start(context)
            } else {
                Timber.tag("GOG").d("GOG SERVICE Not going to start: ${app.gamenative.service.gog.GOGService.isRunning}")
            }

            // Start EpicService if user has Epic credentials
            if (app.gamenative.service.epic.EpicService.hasStoredCredentials(context) &&
                !app.gamenative.service.epic.EpicService.isRunning
            ) {
                Timber.d("[PluviaMain]: Starting EpicService for logged-in user")
                app.gamenative.service.epic.EpicService.start(context)
            }

            // Start AmazonService if user has Amazon credentials
            if (AmazonService.hasStoredCredentials(context) &&
                !AmazonService.isRunning
            ) {
                Timber.d("[PluviaMain]: Starting AmazonService for logged-in user")
                AmazonService.start(context)
            }

            // Handle navigation when already logged in (e.g., app resumed with active session)
            // Only navigate if currently on LoginUser screen to avoid disrupting user's current view
            if (PlatformAuthUtils.isSignedInToAnyPlatform(context) && !SteamService.keepAlive) {
                val baseRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                val targetRoute = if (SteamService.isLoggedIn) {
                    baseRoute
                } else {
                    // Non-Steam platforms: ensure offline param for Home
                    if (baseRoute.startsWith(PluviaScreen.Home.route)) {
                        PluviaScreen.Home.route + "?offline=true"
                    } else {
                        baseRoute
                    }
                }
                navController.navigateFromLoginIfNeeded(targetRoute, "ResumeSession")
            }
        }
    }

    // Listen for connection state changes - reset local isConnecting flag
    LaunchedEffect(state.isSteamConnected) {
        if (state.isSteamConnected) {
            isConnecting = false
        }
    }

    // Listen for save container config prompt
    var pendingSaveAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val onPromptSaveConfig: (AndroidEvent.PromptSaveContainerConfig) -> Unit = { event ->
        pendingSaveAppId = event.appId
        msgDialogState = MessageDialogState(
            visible = true,
            type = DialogType.SAVE_CONTAINER_CONFIG,
            title = context.getString(R.string.save_container_settings_title),
            message = context.getString(R.string.save_container_settings_message),
            confirmBtnText = context.getString(R.string.save),
            dismissBtnText = context.getString(R.string.discard),
        )
    }

    // Listen for game feedback request
    val onShowGameFeedback: (AndroidEvent.ShowGameFeedback) -> Unit = { event ->
        gameFeedbackState = GameFeedbackDialogState(
            visible = true,
            appId = event.appId,
        )
    }

    LaunchedEffect(Unit) {
        PluviaApp.events.on<AndroidEvent.PromptSaveContainerConfig, Unit>(onPromptSaveConfig)
        PluviaApp.events.on<AndroidEvent.ShowGameFeedback, Unit>(onShowGameFeedback)
    }

    DisposableEffect(Unit) {
        onDispose {
            PluviaApp.events.off<AndroidEvent.PromptSaveContainerConfig, Unit>(onPromptSaveConfig)
            PluviaApp.events.off<AndroidEvent.ShowGameFeedback, Unit>(onShowGameFeedback)
        }
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    var onActionClick: (() -> Unit)? = null
    when (msgDialogState.type) {
        DialogType.DISCORD -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                uriHandler.openUri("https://discord.gg/2hKv4VfZfE")
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.SUPPORT -> {
            onConfirmClick = {
                uriHandler.openUri(Constants.Misc.KO_FI_LINK)
                PrefManager.tipped = true
                msgDialogState = MessageDialogState(visible = false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(visible = false)
            }
            onDismissClick = {
                msgDialogState = MessageDialogState(visible = false)
            }
            onActionClick = {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.main_share_text))
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.main_share)))
            }
        }

        DialogType.SYNC_CONFLICT -> {
            onConfirmClick = {
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    preferredSave = SaveLocation.Remote,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = viewModel::launchApp,
                )
                msgDialogState = MessageDialogState(false)
            }
            onDismissClick = {
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    preferredSave = SaveLocation.Local,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = viewModel::launchApp,
                )
                msgDialogState = MessageDialogState(false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(false)
            }
        }

        DialogType.SYNC_FAIL -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.EXECUTABLE_NOT_FOUND -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onActionClick = {
                setMessageDialogState(MessageDialogState(false))
                openContainerConfigForAppId = state.launchedAppId
            }
        }

        DialogType.SYNC_IN_PROGRESS -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    skipCloudSync = true,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = viewModel::launchApp,
                    isOffline = viewModel.isOffline.value,
                )
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.PENDING_UPLOAD_IN_PROGRESS -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.PENDING_UPLOAD -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    ignorePendingOperations = true,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = viewModel::launchApp,
                )
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_SESSION_ACTIVE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    ignorePendingOperations = true,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = viewModel::launchApp,
                )
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.ACCOUNT_SESSION_ACTIVE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                viewModel.viewModelScope.launch {
                    // Kick only the game on the other device and wait briefly for confirmation
                    SteamService.kickPlayingSession(onlyGame = true)
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = viewModel::launchApp,
                        isOffline = viewModel.isOffline.value,
                    )
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_SESSION_SUSPENDED -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.PENDING_OPERATION_NONE -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.MULTIPLE_PENDING_OPERATIONS -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.CRASH -> {
            onDismissClick = null
            onDismissRequest = {
                viewModel.setHasCrashedLastStart(false)
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = {
                viewModel.setHasCrashedLastStart(false)
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.SAVE_CONTAINER_CONFIG -> {
            onConfirmClick = {
                // Save the container config permanently
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.getEffectiveContainerConfig(context, appId)?.let { config ->
                        ContainerUtils.applyToContainer(context, appId, config)
                        Timber.i("[PluviaMain]: Saved container configuration for app $appId")
                    }
                    // Clear the temporary override after saving
                    IntentLaunchManager.clearTemporaryOverride(appId)
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissClick = {
                // Discard the temporary config and restore original
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.restoreOriginalConfiguration(context, appId)
                    IntentLaunchManager.clearTemporaryOverride(appId)
                    Timber.i("[PluviaMain]: Discarded temporary config and restored original for app $appId")
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                // Treat closing dialog as discard
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.restoreOriginalConfiguration(context, appId)
                    IntentLaunchManager.clearTemporaryOverride(appId)
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_UPDATE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val updateInfo = viewModel.updateInfo.value
                if (updateInfo != null) {
                    scope.launch {
                        viewModel.setLoadingDialogVisible(true)
                        viewModel.setLoadingDialogMessage("Downloading update...")
                        viewModel.setLoadingDialogProgress(0f)

                        val success = UpdateInstaller.downloadAndInstall(
                            context = context,
                            downloadUrl = updateInfo.downloadUrl,
                            versionName = updateInfo.versionName,
                            onProgress = { progress ->
                                viewModel.setLoadingDialogProgress(progress)
                            },
                        )

                        viewModel.setLoadingDialogVisible(false)
                        if (!success) {
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.main_update_failed_title),
                                message = context.getString(R.string.main_update_failed_message),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    PluviaTheme(
        isDark = when (state.appTheme) {
            AppTheme.AUTO -> isSystemInDarkTheme()
            AppTheme.DAY -> false
            AppTheme.NIGHT -> true
            AppTheme.AMOLED -> true
        },
        isAmoled = (state.appTheme == AppTheme.AMOLED),
        style = state.paletteStyle,
    ) {
        LoadingDialog(
            visible = state.loadingDialogVisible,
            progress = state.loadingDialogProgress,
            message = state.loadingDialogMessage,
        )

        MessageDialog(
            visible = msgDialogState.visible,
            onDismissRequest = onDismissRequest,
            onConfirmClick = onConfirmClick,
            confirmBtnText = msgDialogState.confirmBtnText,
            onDismissClick = onDismissClick,
            dismissBtnText = msgDialogState.dismissBtnText,
            onActionClick = onActionClick,
            actionBtnText = msgDialogState.actionBtnText,
            icon = msgDialogState.type.icon,
            title = msgDialogState.title,
            message = msgDialogState.message,
        )

        val scope = rememberCoroutineScope()
        var containerConfigForDialog by remember(openContainerConfigForAppId) { mutableStateOf<ContainerData?>(null) }
        LaunchedEffect(openContainerConfigForAppId) {
            val appId = openContainerConfigForAppId
            if (appId == null) {
                containerConfigForDialog = null
                return@LaunchedEffect
            }
            containerConfigForDialog = withContext(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                ContainerUtils.toContainerData(container)
            }
        }
        openContainerConfigForAppId?.let { appId ->
            containerConfigForDialog?.let { config ->
                ContainerConfigDialog(
                    visible = true,
                    title = context.getString(R.string.container_config_title),
                    initialConfig = config,
                    onDismissRequest = { openContainerConfigForAppId = null },
                    onSave = { newConfig ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ContainerUtils.applyToContainer(context, appId, newConfig)
                            }
                            openContainerConfigForAppId = null
                        }
                    },
                )
            }
        }

        GameFeedbackDialog(
            state = gameFeedbackState,
            onStateChange = { gameFeedbackState = it },
            onSubmit = { feedbackState ->
                Timber.d(
                    "GameFeedback: onSubmit called with rating=${feedbackState.rating}, tags=${feedbackState.selectedTags}, text=${
                        feedbackState.feedbackText.take(
                            20,
                        )
                    }",
                )
                try {
                    // Get the container for the app
                    val appId = feedbackState.appId
                    Timber.d("GameFeedback: Got appId=$appId")

                    // Submit feedback to Supabase
                    Timber.d("GameFeedback: Starting coroutine for submission")
                    viewModel.viewModelScope.launch {
                        Timber.d("GameFeedback: Inside coroutine scope")
                        try {
                            Timber.d("GameFeedback: Calling submitGameFeedback with rating=${feedbackState.rating}")
                            val result = GameFeedbackUtils.submitGameFeedback(
                                context = context,
                                supabase = PluviaApp.supabase,
                                appId = appId,
                                rating = feedbackState.rating,
                                tags = feedbackState.selectedTags.toList(),
                                notes = feedbackState.feedbackText.takeIf { it.isNotBlank() },
                            )

                            Timber.d("GameFeedback: Submission returned $result")
                            if (result) {
                                Timber.d("GameFeedback: Showing success toast")
                                viewModel.showToast("Thank you for your feedback!")
                            } else {
                                Timber.d("GameFeedback: Showing failure toast")
                                viewModel.showToast("Failed to submit feedback")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "GameFeedback: Error submitting game feedback")
                            viewModel.showToast("Error submitting feedback")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "GameFeedback: Error preparing game feedback")
                    viewModel.showToast("Failed to submit feedback")
                } finally {
                    // Close the dialog regardless of success
                    Timber.d("GameFeedback: Closing dialog")
                    gameFeedbackState = GameFeedbackDialogState(visible = false)
                }
            },
            onDismiss = {
                gameFeedbackState = GameFeedbackDialogState(visible = false)
            },
            onDiscordSupport = {
                uriHandler.openUri("https://discord.gg/2hKv4VfZfE")
            },
        )

        Box(modifier = Modifier.zIndex(10f)) {
            BootingSplash(
                visible = state.showBootingSplash,
                text = state.bootingSplashText,
            )
        }

        // Connection status banner (overlay) - dismissible so users can access navigation
        if (state.currentScreen != PluviaScreen.LoginUser && !connectionBannerDismissed && !SteamService.isConnected &&
            PrefManager.refreshToken.isNotEmpty() && PrefManager.username.isNotEmpty()) {
            Box(modifier = Modifier.zIndex(5f)) {
                ConnectionStatusBanner(
                    connectionState = state.connectionState,
                    connectionMessage = state.connectionMessage,
                    timeoutSeconds = state.connectionTimeoutSeconds,
                    onContinueOffline = {
                        viewModel.continueOffline()
                    },
                    onRetry = {
                        viewModel.retryConnection()
                        context.startForegroundService(Intent(context, SteamService::class.java))
                    },
                    onDismiss = {
                        connectionBannerDismissed = true
                    },
                )
            }
        }

        val startDestination = remember {
            when {
                SteamService.isLoggedIn -> PluviaScreen.Home.route + "?offline=false"
                GOGService.hasStoredCredentials(context) ||
                    EpicService.hasStoredCredentials(context) ||
                    AmazonService.hasStoredCredentials(context) ->
                    PluviaScreen.Home.route + "?offline=true"
                else -> PluviaScreen.LoginUser.route
            }
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            /** Login **/
            composable(route = PluviaScreen.LoginUser.route) {
                UserLoginScreen(
                    connectionState = state.connectionState,
                    onRetryConnection = viewModel::retryConnection,
                    onContinueOffline = {
                        navController.navigate(PluviaScreen.Home.route + "?offline=true")
                    },
                    onPlatformSignedIn = {
                        navController.navigate(PluviaScreen.Home.route + "?offline=true") {
                            popUpTo(PluviaScreen.LoginUser.route) { inclusive = true }
                        }
                    },
                )
            }
            /** Library, Downloads, Friends **/
            composable(
                route = PluviaScreen.Home.route + "?offline={offline}",
                deepLinks = listOf(navDeepLink { uriPattern = "pluvia://home" }),
                arguments = listOf(
                    navArgument("offline") {
                        type = NavType.BoolType
                        defaultValue = false // default when the query param isn’t present
                    },
                ),
            ) { backStackEntry ->
                val isOffline = backStackEntry.arguments?.getBoolean("offline") ?: false

                // Show update/crash/support dialogs when Home is first displayed
                // Skip when offline with Steam credentials (avoid flash when Steam reconnects)
                LaunchedEffect(Unit) {
                    val hasSteamCredentials = PrefManager.refreshToken.isNotEmpty() && PrefManager.username.isNotEmpty()
                    val shouldShowDialogs = !isOffline || !hasSteamCredentials

                    if (shouldShowDialogs && !state.annoyingDialogShown && PluviaApp.xEnvironment == null && !SteamService.keepAlive) {
                        val currentUpdateInfo = updateInfo
                        if (currentUpdateInfo != null) {
                            viewModel.setAnnoyingDialogShown(true)
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.APP_UPDATE,
                                title = context.getString(R.string.main_update_available_title),
                                message = context.getString(
                                    R.string.main_update_available_message,
                                    currentUpdateInfo.versionName,
                                    currentUpdateInfo.releaseNotes?.let { "\n\n$it" } ?: "",
                                ),
                                confirmBtnText = context.getString(R.string.main_update_button),
                                dismissBtnText = context.getString(R.string.main_later_button),
                            )
                        } else if (state.hasCrashedLastStart) {
                            viewModel.setAnnoyingDialogShown(true)
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.CRASH,
                                title = context.getString(R.string.main_recent_crash_title),
                                message = context.getString(R.string.main_recent_crash_message),
                                confirmBtnText = context.getString(R.string.ok),
                            )
                        } else if (!(PrefManager.tipped || BuildConfig.GOLD)) {
                            viewModel.setAnnoyingDialogShown(true)
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SUPPORT,
                                title = context.getString(R.string.main_thank_you_title),
                                message = context.getString(R.string.main_thank_you_message),
                                confirmBtnText = context.getString(R.string.main_join_kofi),
                                dismissBtnText = context.getString(R.string.close),
                                actionBtnText = context.getString(R.string.main_share),
                            )
                        }
                    }
                }

                HomeScreen(
                    onClickPlay = { appId, asContainer ->
                        viewModel.setLaunchedAppId(appId)
                        viewModel.setBootToContainer(asContainer)
                        viewModel.setTestGraphics(false)
                        viewModel.setOffline(isOffline)
                        preLaunchApp(
                            context = context,
                            appId = appId,
                            setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                            setLoadingProgress = viewModel::setLoadingDialogProgress,
                            setLoadingMessage = viewModel::setLoadingDialogMessage,
                            setMessageDialogState = { msgDialogState = it },
                            onSuccess = viewModel::launchApp,
                            isOffline = isOffline,
                            bootToContainer = asContainer,
                        )
                    },
                    onTestGraphics = { appId ->
                        viewModel.setLaunchedAppId(appId)
                        viewModel.setBootToContainer(true)
                        viewModel.setTestGraphics(true)
                        viewModel.setOffline(isOffline)
                        preLaunchApp(
                            context = context,
                            appId = appId,
                            setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                            setLoadingProgress = viewModel::setLoadingDialogProgress,
                            setLoadingMessage = viewModel::setLoadingDialogMessage,
                            setMessageDialogState = { msgDialogState = it },
                            onSuccess = viewModel::launchApp,
                            isOffline = isOffline,
                            bootToContainer = true,
                        )
                    },
                    onClickExit = {
                        PluviaApp.events.emit(AndroidEvent.EndProcess)
                    },
                    onChat = {
                        navController.navigate(PluviaScreen.Chat.route(it))
                    },
                    onNavigateRoute = {
                        navController.navigate(it)
                    },
                    onLogout = {
                        SteamService.logOut()
                    },
                    onGoOnline = {
                        navController.navigate(
                            if (!SteamService.isLoggedIn) PluviaScreen.LoginUser.route
                            else PluviaScreen.Home.route
                        )
                    },
                    isOffline = isOffline,
                )
            }

            /** Full Screen Chat **/
            // Chat feature temporarily disabled - screen component removed
            /* composable(
                route = "chat/{id}",
                arguments = listOf(
                    navArgument(PluviaScreen.Chat.ARG_ID) {
                        type = NavType.LongType
                    },
                ),
            ) {
                val id = it.arguments?.getLong(PluviaScreen.Chat.ARG_ID) ?: throw RuntimeException("Unable to get ID to chat")
                ChatScreen(
                    friendId = id,
                    onBack = {
                        CoroutineScope(Dispatchers.Main).launch {
                            navController.popBackStack()
                        }
                    },
                )
            } */

            /** Game Screen **/
            composable(route = PluviaScreen.XServer.route) {
                XServerScreen(
                    appId = state.launchedAppId,
                    bootToContainer = state.bootToContainer,
                    testGraphics = state.testGraphics,
                    registerBackAction = { cb ->
                        Timber.d("registerBackAction called: $cb")
                        gameBackAction = cb
                    },
                    navigateBack = {
                        CoroutineScope(Dispatchers.Main).launch {
                            val currentRoute = navController.currentBackStackEntry
                                ?.destination
                                ?.route

                            if (currentRoute == PluviaScreen.XServer.route) {
                                if (MainActivity.wasLaunchedViaExternalIntent) {
                                    Timber.d("[IntentLaunch]: Finishing activity to return to external launcher")
                                    MainActivity.wasLaunchedViaExternalIntent = false
                                    (context as? android.app.Activity)?.finish()
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        }
                    },
                    onWindowMapped = { context, window ->
                        viewModel.onWindowMapped(context, window, state.launchedAppId)
                    },
                    onExit = {
                        viewModel.exitSteamApp(context, state.launchedAppId)
                    },
                    onGameLaunchError = { error ->
                        viewModel.onGameLaunchError(error)
                    },
                )
            }

            /** Settings **/
            composable(route = PluviaScreen.Settings.route) {
                SettingsScreen(
                    appTheme = state.appTheme,
                    paletteStyle = state.paletteStyle,
                    onAppTheme = viewModel::setTheme,
                    onPaletteStyle = viewModel::setPalette,
                    onBack = { navController.navigateUp() },
                )
            }
        }
    }
}

fun preLaunchApp(
    context: Context,
    appId: String,
    ignorePendingOperations: Boolean = false,
    preferredSave: SaveLocation = SaveLocation.None,
    useTemporaryOverride: Boolean = false,
    skipCloudSync: Boolean = false,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: KFunction2<Context, String, Unit>,
    retryCount: Int = 0,
    isOffline: Boolean = false,
    bootToContainer: Boolean = false,
) {
    setLoadingDialogVisible(true)
    // TODO: add a way to cancel
    // TODO: add fail conditions

    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

    CoroutineScope(Dispatchers.IO).launch {
        // create container if it does not already exist
        // TODO: combine somehow with container creation in HomeLibraryAppScreen
        val containerManager = ContainerManager(context)
        val container = if (useTemporaryOverride) {
            ContainerUtils.getOrCreateContainerWithOverride(context, appId)
        } else {
            ContainerUtils.getOrCreateContainer(context, appId)
        }

        // Clear session metadata on every launch to ensure fresh values
        container.clearSessionMetadata()

        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)

        // When "Open container" is used we boot to desktop/file manager only — skip executable check
        if (!bootToContainer) {
            // Verify we have a launch executable for all platforms before proceeding (fail fast, avoid black screen)
            val effectiveExe = when (gameSource) {
                GameSource.STEAM -> SteamService.getLaunchExecutable(appId, container)
                GameSource.GOG -> GOGService.getLaunchExecutable(appId, container)
                GameSource.EPIC -> EpicService.getLaunchExecutable(appId)
                GameSource.CUSTOM_GAME -> CustomGameScanner.getLaunchExecutable(container)
                GameSource.AMAZON -> AmazonService.getLaunchExecutable(appId)
            }
            if (effectiveExe.isBlank()) {
                Timber.tag("preLaunchApp").w("Cannot launch $appId: no executable found (game source: $gameSource)")
                setLoadingDialogVisible(false)
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.EXECUTABLE_NOT_FOUND,
                        title = context.getString(R.string.game_executable_not_found_title),
                        message = context.getString(R.string.game_executable_not_found),
                        dismissBtnText = context.getString(R.string.ok),
                        actionBtnText = AppOptionMenuType.EditContainer.text,
                    ),
                )
                return@launch
            }
        }

        // download any manifest components (wine/proton, dxvk, etc.) missing from config
        if (gameSource == GameSource.STEAM) {
            try {
                val configJson = Json.parseToJsonElement(container.containerJson).jsonObject
                val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
                    context, configJson, "exact_gpu_match",
                )
                for (request in missingRequests) {
                    setLoadingMessage(context.getString(R.string.main_downloading_entry, request.entry.name))
                    try {
                        ManifestInstaller.installManifestEntry(
                            context, request.entry, request.isDriver, request.contentType,
                        ) { progress -> setLoadingProgress(progress.coerceIn(0f, 1f)) }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to install ${request.entry.name}, continuing")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to install manifest components")
                setLoadingDialogVisible(false)
                return@launch
            }
        }

        // Check if this is a Custom Game and validate executable selection before installing components
        // Skip the check if booting to container (Open Container menu option)
        val isCustomGame = gameSource == GameSource.CUSTOM_GAME

        // set up Ubuntu file system
        SplitCompat.install(context)
        if (!SteamService.isImageFsInstallable(context, container.containerVariant)) {
            setLoadingMessage("Downloading first-time files")
            SteamService.downloadImageFs(
                onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                this,
                variant = container.containerVariant,
                context = context,
            ).await()
        }
        if (container.containerVariant.equals(Container.GLIBC) &&
            !SteamService.isFileInstallable(context, "imagefs_patches_gamenative.tzst")
        ) {
            setLoadingMessage("Downloading Wine")
            SteamService.downloadImageFsPatches(
                onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                this,
                context = context,
            ).await()
        } else {
            if (container.wineVersion.contains("proton-9.0-arm64ec") &&
                !SteamService.isFileInstallable(context, "proton-9.0-arm64ec.txz")
            ) {
                setLoadingMessage("Downloading arm64ec Proton")
                SteamService.downloadFile(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                    "proton-9.0-arm64ec.txz",
                ).await()
            } else if (container.wineVersion.contains("proton-9.0-x86_64") &&
                !SteamService.isFileInstallable(context, "proton-9.0-x86_64.txz")
            ) {
                setLoadingMessage("Downloading x86_64 Proton")
                SteamService.downloadFile(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                    "proton-9.0-x86_64.txz",
                ).await()
            }
            if (container.wineVersion.contains("proton-9.0-x86_64") || container.wineVersion.contains("proton-9.0-arm64ec")) {
                val protonVersion = container.wineVersion
                val imageFs = ImageFs.find(context)
                val outFile = File(imageFs.rootDir, "/opt/$protonVersion")
                val binDir = File(outFile, "bin")
                if (!binDir.exists() || !binDir.isDirectory) {
                    Timber.i("Extracting $protonVersion to /opt/")
                    setLoadingMessage("Extracting $protonVersion")
                    setLoadingProgress(-1f)
                    val downloaded = File(imageFs.getFilesDir(), "$protonVersion.txz")
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.XZ,
                        downloaded,
                        outFile,
                    )
                }
            }
        }

        if (!isOffline) {
            try {
                LaunchDependencies().ensureLaunchDependencies(
                    context = context,
                    container = container,
                    gameSource = gameSource,
                    gameId = gameId,
                    setLoadingMessage = setLoadingMessage,
                    setLoadingProgress = setLoadingProgress,
                )
            } catch (e: Exception) {
                Timber.tag("preLaunchApp").e(e, "ensureLaunchDependencies failed")
                setLoadingDialogVisible(false)
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.launch_dependency_failed_title),
                        message = e.message ?: context.getString(R.string.launch_dependency_failed_message),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
                return@launch
            }
        } else {
            Timber.tag("preLaunchApp").e("Offline mode, skipping launch dependencies")
        }

        if (!container.isUseLegacyDRM && !container.isLaunchRealSteam &&
            !SteamService.isFileInstallable(context, "experimental-drm-20260116.tzst")
        ) {
            setLoadingMessage("Downloading extras")
            SteamService.downloadFile(
                onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                this,
                context = context,
                "experimental-drm-20260116.tzst",
            ).await()
        }
        if (container.isLaunchRealSteam && !SteamService.isFileInstallable(context, "steam.tzst")) {
            setLoadingMessage(context.getString(R.string.main_downloading_steam))
            SteamService.downloadSteam(
                onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                this,
                context = context,
            ).await()
        }
        if (container.isLaunchRealSteam && !SteamService.isFileInstallable(context, "steam-token.tzst")) {
            setLoadingMessage("Downloading steam-token")
            SteamService.downloadFile(
                onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                this,
                context = context,
                "steam-token.tzst",
            ).await()
        }

        val loadingMessage = if (container.containerVariant.equals(Container.GLIBC)) {
            context.getString(R.string.main_installing_glibc)
        } else {
            context.getString(R.string.main_installing_bionic)
        }
        setLoadingMessage(loadingMessage)
        val imageFsInstallSuccess =
            ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                // Log.d("XServerScreen", "$progress")
                setLoadingProgress(progress / 100f)
            }.get()
        setLoadingMessage(context.getString(R.string.main_loading))
        setLoadingProgress(-1f)

        // must activate container before downloading save files
        containerManager.activateContainer(container)

        // If another game is running on this account elsewhere, prompt user first (cross-app session)
        val isSteamGame = gameSource == GameSource.STEAM
        if(isSteamGame) {
            try {
                val currentPlaying = SteamService.getSelfCurrentlyPlayingAppId()
                if (!isOffline && currentPlaying != null && currentPlaying != gameId) {
                    val otherGameName = SteamService.getAppInfoOf(currentPlaying)?.name ?: "another game"
                    setLoadingDialogVisible(false)
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.ACCOUNT_SESSION_ACTIVE,
                            title = context.getString(R.string.main_app_running_title),
                            message = context.getString(R.string.main_app_running_message, otherGameName),
                            confirmBtnText = context.getString(R.string.main_play_anyway),
                            dismissBtnText = context.getString(R.string.cancel),
                        ),
                    )
                    return@launch
                }
            } catch (_: Exception) { /* ignore persona read errors */ }
        }

        // For Custom Games, bypass Steam Cloud operations entirely and proceed to launch
        if (isCustomGame) {
            Timber.tag("preLaunchApp").i("Custom Game detected for $appId — skipping Steam Cloud sync and launching container")
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For GOG Games, sync cloud saves before launch (executable already verified above via GOGService.getLaunchExecutable)
        val isGOGGame = gameSource == GameSource.GOG
        if (isGOGGame) {
            Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for $appId — syncing cloud saves before launch")

            // Sync cloud saves (download latest saves before playing)
            Timber.tag("GOG").d("[Cloud Saves] Starting pre-game download sync for $appId")
            val syncSuccess = app.gamenative.service.gog.GOGService.syncCloudSaves(
                context = context,
                appId = appId,
            )

            if (!syncSuccess) {
                Timber.tag("GOG").w("[Cloud Saves] Download sync failed for $appId, proceeding with launch anyway")
                // Don't block launch on sync failure - log warning and continue
            } else {
                Timber.tag("GOG").i("[Cloud Saves] Download sync completed successfully for $appId")
            }

            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Amazon Games, skip cloud sync entirely (Amazon doesn't support cloud saves)
        val isAmazonGame = gameSource == GameSource.AMAZON
        if (isAmazonGame) {
            Timber.tag("preLaunchApp").i("Amazon Game detected for $appId — skipping cloud sync and launching container")
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Epic Games, sync cloud saves before launch (executable already verified above via EpicService.getLaunchExecutable)
        val isEpicGame = gameSource == GameSource.EPIC
        if (isEpicGame) {
            // Handle Cloud Saves
            Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for $appId — syncing cloud saves before launch")
            // Sync cloud saves (download latest saves before playing)
            Timber.tag("Epic").d("[Cloud Saves] Starting pre-game download sync for $appId")
            val syncSuccess = app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
                context = context,
                appId = gameId,
            )

            if (!syncSuccess) {
                Timber.tag("Epic").w("[Cloud Saves] Download sync failed for $appId, proceeding with launch anyway")
                // Don't block launch on sync failure - log warning and continue
            } else {
                Timber.tag("Epic").i("[Cloud Saves] Download sync completed successfully for $appId")
            }

            // Delete Ownership Token if exists
            Timber.tag("Epic").i("[Ownership Tokens] Cleaning up launch tokens for Epic games...")
            EpicService.cleanupLaunchTokens(context)

            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        if (skipCloudSync) {
            Timber.tag("preLaunchApp").w("Skipping Steam Cloud sync for $appId by user request")
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Steam games, sync save files and check no pending remote operations are running
        val prefixToPath: (String) -> String = { prefix ->
            PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
        }
        setLoadingMessage("Syncing cloud saves")
        setLoadingProgress(-1f)
        val postSyncInfo = SteamService.beginLaunchApp(
            appId = gameId,
            prefixToPath = prefixToPath,
            ignorePendingOperations = ignorePendingOperations,
            preferredSave = preferredSave,
            parentScope = this,
            isOffline = isOffline,
            onProgress = { message, progress ->
                setLoadingMessage(message)
                setLoadingProgress(if (progress < 0) -1f else progress)
            },
        ).await()

        setLoadingDialogVisible(false)

        when (postSyncInfo.syncResult) {
            SyncResult.Conflict -> {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_CONFLICT,
                        title = context.getString(R.string.main_save_conflict_title),
                        message = context.getString(
                            R.string.main_save_conflict_message,
                            Date(postSyncInfo.localTimestamp).toString(),
                            Date(postSyncInfo.remoteTimestamp).toString(),
                        ),
                        dismissBtnText = context.getString(R.string.main_keep_local),
                        confirmBtnText = context.getString(R.string.main_keep_remote),
                    ),
                )
            }

            SyncResult.InProgress -> {
                if (useTemporaryOverride && retryCount < 5) {
                    // For intent launches, retry after a short delay (max 5 retries = ~10 seconds)
                    Timber.i("Sync in progress for intent launch, retrying in 2 seconds... (attempt ${retryCount + 1}/5)")
                    delay(2000)
                    preLaunchApp(
                        context = context,
                        appId = appId,
                        ignorePendingOperations = ignorePendingOperations,
                        preferredSave = preferredSave,
                        useTemporaryOverride = useTemporaryOverride,
                        setLoadingDialogVisible = setLoadingDialogVisible,
                        setLoadingProgress = setLoadingProgress,
                        setLoadingMessage = setLoadingMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = onSuccess,
                        retryCount = retryCount + 1,
                        bootToContainer = bootToContainer,
                    )
                } else {
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.SYNC_IN_PROGRESS,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_sync_in_progress_launch_anyway_message),
                            confirmBtnText = context.getString(R.string.main_launch_anyway),
                            dismissBtnText = context.getString(R.string.main_wait),
                        ),
                    )
                }
            }

            SyncResult.UnknownFail,
            SyncResult.DownloadFail,
            SyncResult.UpdateFail,
            -> {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.sync_error_title),
                        message = context.getString(R.string.main_sync_failed, postSyncInfo.syncResult.toString()),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
            }

            SyncResult.PendingOperations -> {
                Timber.i(
                    "Pending remote operations:${
                        postSyncInfo.pendingRemoteOperations.joinToString("\n") { pro ->
                            "\n\tmachineName: ${pro.machineName}" +
                                "\n\ttimestamp: ${Date(pro.timeLastUpdated * 1000L)}" +
                                "\n\toperation: ${pro.operation}"
                        }
                    }",
                )
                if (postSyncInfo.pendingRemoteOperations.size == 1) {
                    val pro = postSyncInfo.pendingRemoteOperations.first()
                    val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                    val dateStr = Date(pro.timeLastUpdated * 1000L).toString()
                    when (pro.operation) {
                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress -> {
                            // maybe this should instead wait for the upload to finish and then
                            // launch the app
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                                    title = context.getString(R.string.main_upload_in_progress_title),
                                    message = context.getString(
                                        R.string.main_upload_in_progress_message,
                                        gameName,
                                        pro.machineName,
                                        dateStr,
                                    ),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadPending -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD,
                                    title = context.getString(R.string.main_pending_upload_title),
                                    message = context.getString(
                                        R.string.main_pending_upload_message,
                                        gameName,
                                        pro.machineName,
                                        dateStr,
                                    ),
                                    confirmBtnText = context.getString(R.string.main_play_anyway),
                                    dismissBtnText = context.getString(R.string.cancel),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_ACTIVE,
                                    title = context.getString(R.string.main_app_running_title),
                                    message = context.getString(
                                        R.string.main_app_running_other_device,
                                        pro.machineName,
                                        gameName,
                                        dateStr,
                                    ),
                                    confirmBtnText = context.getString(R.string.main_play_anyway),
                                    dismissBtnText = context.getString(R.string.cancel),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionSuspended -> {
                            // I don't know what this means, yet
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_SUSPENDED,
                                    title = context.getString(R.string.sync_error_title),
                                    message = context.getString(R.string.main_app_session_suspended),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationNone -> {
                            // why are we here
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_OPERATION_NONE,
                                    title = context.getString(R.string.sync_error_title),
                                    message = context.getString(R.string.main_pending_operation_none),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }
                    }
                } else {
                    // this should probably be handled differently
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.MULTIPLE_PENDING_OPERATIONS,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_multiple_pending_operations),
                            dismissBtnText = context.getString(R.string.ok),
                        ),
                    )
                }
            }

            SyncResult.UpToDate,
            SyncResult.Success,
            -> onSuccess(context, appId)
        }
    }
}
