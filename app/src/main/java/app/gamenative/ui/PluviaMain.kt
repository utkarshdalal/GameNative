package app.gamenative.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.HomeScreen
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.chat.ChatScreen
import app.gamenative.ui.screen.login.UserLoginScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ContainerUtils
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFsInstaller
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import java.util.Date
import java.util.EnumSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.reflect.KFunction2

@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }

    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }

    var isConnecting by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.LaunchApp -> {
                    navController.navigate(PluviaScreen.XServer.route)
                }

                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (hasBack) {
                        // TODO: check if back leads to log out and present confidence modal
                        navController.popBackStack()
                    } else {
                        // TODO: quit app?
                    }
                }

                MainViewModel.MainUiEvent.OnLoggedOut -> {
                    // Pop stack and go back to login.
                    navController.popBackStack(
                        route = PluviaScreen.LoginUser.route,
                        inclusive = false,
                        saveState = false,
                    )
                }

                is MainViewModel.MainUiEvent.OnLogonEnded -> {
                    when (event.result) {
                        LoginResult.Success -> {
                            Timber.i("Navigating to library")
                            navController.navigate(PluviaScreen.Home.route)

                            // If a crash happen, lets not ask for a tip yet.
                            // Instead, ask the user to contribute their issues to be addressed.
                            if (!state.annoyingDialogShown && state.hasCrashedLastStart) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.CRASH,
                                    title = "Recent Crash",
                                    message = "Sorry about that!\n" +
                                        "It would be nice to know about the recent issue you've had.\n" +
                                        "You can view and export the most recent crash log in the app's settings " +
                                        "and attach it as a Github issue in the project's repository.\n" +
                                        "Link to the Github repo is also in settings!",
                                    confirmBtnText = "OK",
                                )
                            } else if (!(PrefManager.tipped || BuildConfig.GOLD) && !state.annoyingDialogShown) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.SUPPORT,
                                    message = "Thank you for using GameNative, please consider supporting " +
                                        "open-source PC gaming on Android by donating whatever amount is comfortable to you",
                                    confirmBtnText = "Tip",
                                    dismissBtnText = "Close",
                                )
                            }
                        }

                        else -> Timber.i("Received non-result: ${event.result}")
                    }
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
            navController.removeOnDestinationChangedListener(PluviaApp.onDestinationChangedListener!!)
        }

        navController.addOnDestinationChangedListener(PluviaApp.onDestinationChangedListener!!)
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            // Log.d("PluviaMain", "Screen changed to $currentScreen, resetting some values")
            // TODO: remove this if statement once XServerScreen orientation change bug is fixed
            if (state.currentScreen != PluviaScreen.XServer) {
                // reset system ui visibility
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(true))
                // TODO: add option for user to set
                // reset available orientations
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    LaunchedEffect(lifecycleOwner) {
        if (!state.isSteamConnected && !isConnecting) {
            isConnecting = true
            val intent = Intent(context, SteamService::class.java)
            context.startForegroundService(intent)
        }
        // Go to the Home screen if we're already logged in.
        if (SteamService.isLoggedIn && state.currentScreen == PluviaScreen.LoginUser) {
            navController.navigate(PluviaScreen.Home.route)
        }
    }

    // Listen for connection state changes
    LaunchedEffect(state.isSteamConnected) {
        if (state.isSteamConnected) {
            isConnecting = false
        }
    }

    // Timeout if stuck in connecting state for 10 seconds so that its not in loading state forever
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            Timber.d("Started connecting, will timeout in 10s")
            kotlinx.coroutines.delay(10000)
            Timber.d("Timeout reached, isSteamConnected=${state.isSteamConnected}")
            if (!state.isSteamConnected) {
                isConnecting = false
            }
        }
    }

    // Show loading or error UI as appropriate
    when {
        isConnecting -> {
            LoadingScreen()
            return
        }
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    when (msgDialogState.type) {
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
        }

        DialogType.SYNC_CONFLICT -> {
            onConfirmClick = {
                preLaunchApp(
                    context = context,
                    appId = state.launchedAppId,
                    preferredSave = SaveLocation.Remote,
                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                    setLoadingProgress = viewModel::setLoadingDialogProgress,
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
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
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
        )

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

        Box(modifier = Modifier.zIndex(10f)) {
            BootingSplash(
                visible = state.showBootingSplash,
                onBootCompleted = {
                    viewModel.setShowBootingSplash(false)
                },
            )
        }

        NavHost(
            navController = navController,
            startDestination = PluviaScreen.LoginUser.route,
        ) {
            /** Login **/
            /** Login **/
            composable(route = PluviaScreen.LoginUser.route) {
                UserLoginScreen()
            }
            /** Library, Downloads, Friends **/
            /** Library, Downloads, Friends **/
            composable(
                route = PluviaScreen.Home.route,
                deepLinks = listOf(navDeepLink { uriPattern = "pluvia://home" }),
            ) {
                HomeScreen(
                    onClickPlay = { launchAppId, asContainer ->
                        viewModel.setLaunchedAppId(launchAppId)
                        viewModel.setBootToContainer(asContainer)
                        preLaunchApp(
                            context = context,
                            appId = launchAppId,
                            setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                            setLoadingProgress = viewModel::setLoadingDialogProgress,
                            setMessageDialogState = { msgDialogState = it },
                            onSuccess = viewModel::launchApp,
                        )
                    },
                    onClickExit = {
                        PluviaApp.events.emit(AndroidEvent.EndProcess)
                    },
                    onChat = {
                        navController.navigate(PluviaScreen.Chat.route(it))
                    },
                    onSettings = {
                        navController.navigate(PluviaScreen.Settings.route)
                    },
                    onLogout = {
                        SteamService.logOut()
                    },
                )
            }

            /** Full Screen Chat **/

            /** Full Screen Chat **/
            composable(
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
            }

            /** Game Screen **/

            /** Game Screen **/
            composable(route = PluviaScreen.XServer.route) {
                XServerScreen(
                    appId = state.launchedAppId,
                    bootToContainer = state.bootToContainer,
                    navigateBack = {
                        CoroutineScope(Dispatchers.Main).launch {
                            navController.popBackStack()
                        }
                    },
                    onWindowMapped = { window ->
                        viewModel.onWindowMapped(window, state.launchedAppId)
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
    appId: Int,
    ignorePendingOperations: Boolean = false,
    preferredSave: SaveLocation = SaveLocation.None,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: KFunction2<Context, Int, Unit>,
) {
    setLoadingDialogVisible(true)
    // TODO: add a way to cancel
    // TODO: add fail conditions
    CoroutineScope(Dispatchers.IO).launch {
        // set up Ubuntu file system
        SplitCompat.install(context)
        val imageFsInstallSuccess =
            ImageFsInstaller.installIfNeededFuture(context, context.assets) { progress ->
                // Log.d("XServerScreen", "$progress")
                setLoadingProgress(progress / 100f)
            }.get()
        setLoadingProgress(-1f)

        // create container if it does not already exist
        // TODO: combine somehow with container creation in HomeLibraryAppScreen
        val containerManager = ContainerManager(context)
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        // must activate container before downloading save files
        containerManager.activateContainer(container)

        // sync save files and check no pending remote operations are running
        val prefixToPath: (String) -> String = { prefix ->
            PathType.from(prefix).toAbsPath(context, appId)
        }
        val postSyncInfo = SteamService.beginLaunchApp(
            appId = appId,
            prefixToPath = prefixToPath,
            ignorePendingOperations = ignorePendingOperations,
            preferredSave = preferredSave,
            parentScope = this,
        ).await()

        setLoadingDialogVisible(false)

        when (postSyncInfo.syncResult) {
            SyncResult.Conflict -> {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_CONFLICT,
                        title = "Save Conflict",
                        message = "There is a new remote save and a new local save, which would you " +
                            "like to keep?\n\nLocal save:\n\t${Date(postSyncInfo.localTimestamp)}" +
                            "\nRemote save:\n\t${Date(postSyncInfo.remoteTimestamp)}",
                        dismissBtnText = "Keep local",
                        confirmBtnText = "Keep remote",
                    ),
                )
            }

            SyncResult.InProgress,
            SyncResult.UnknownFail,
            SyncResult.DownloadFail,
            SyncResult.UpdateFail,
            -> {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = "Sync Error",
                        message = "Failed to sync save files: ${postSyncInfo.syncResult}. Please restart app.",
                        dismissBtnText = "Ok",
                    ),
                )
            }

            SyncResult.PendingOperations -> {
                Timber.i(
                    "Pending remote operations:${
                        postSyncInfo.pendingRemoteOperations.map { pro ->
                            "\n\tmachineName: ${pro.machineName}" +
                                "\n\ttimestamp: ${Date(pro.timeLastUpdated * 1000L)}" +
                                "\n\toperation: ${pro.operation}"
                        }.joinToString("\n")
                    }",
                )
                if (postSyncInfo.pendingRemoteOperations.size == 1) {
                    val pro = postSyncInfo.pendingRemoteOperations.first()
                    when (pro.operation) {
                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress -> {
                            // maybe this should instead wait for the upload to finish and then
                            // launch the app
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                                    title = "Upload in Progress",
                                    message = "You played ${SteamService.getAppInfoOf(appId)?.name} " +
                                        "on the device ${pro.machineName} " +
                                        "(${Date(pro.timeLastUpdated * 1000L)}) and the save of " +
                                        "that session is still uploading.\nTry again later.",
                                    dismissBtnText = "Ok",
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadPending -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD,
                                    title = "Pending Upload",
                                    message = "You played " +
                                        "${SteamService.getAppInfoOf(appId)?.name} " +
                                        "on the device ${pro.machineName} " +
                                        "(${Date(pro.timeLastUpdated * 1000L)}), " +
                                        "and that save is not yet in the cloud. " +
                                        "(upload not started)\nYou can still play " +
                                        "this game, but that may create a conflict " +
                                        "when your previous game progress " +
                                        "successfully uploads.",
                                    confirmBtnText = "Play anyway",
                                    dismissBtnText = "Cancel",
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_ACTIVE,
                                    title = "App Running",
                                    message = "You are logged in on another device (${pro.machineName}) " +
                                        "already playing ${SteamService.getAppInfoOf(appId)?.name} " +
                                        "(${Date(pro.timeLastUpdated * 1000L)}), and that save " +
                                        "is not yet in the cloud. \nYou can still play this game, " +
                                        "but that will disconnect the other session from Steam " +
                                        "and may create a save conflict when that session " +
                                        "progress is synced",
                                    confirmBtnText = "Play anyway",
                                    dismissBtnText = "Cancel",
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionSuspended -> {
                            // I don't know what this means, yet
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_SUSPENDED,
                                    title = "Sync Error",
                                    message = "App session suspended. Please restart app.",
                                    dismissBtnText = "Ok",
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationNone -> {
                            // why are we here
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_OPERATION_NONE,
                                    title = "Sync Error",
                                    message = "Received pending remote operations whose operation was 'none'. Please restart app.",
                                    dismissBtnText = "Ok",
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
                            title = "Sync Error",
                            message = "Multiple pending remote operations, try again later. Please restart app.",
                            dismissBtnText = "Ok",
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
