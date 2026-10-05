package app.gamenative.ui.data

import app.gamenative.data.BootAdItem
import app.gamenative.data.LaunchInfo
import app.gamenative.enums.AppTheme
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.LaunchMode
import com.materialkolor.PaletteStyle

data class LaunchOptionPrompt(val gameName: String, val mode: LaunchMode, val options: List<LaunchInfo>)

data class NonVrArgsPrompt(val gameName: String, val args: List<String>)

data class MainState(
    val appTheme: AppTheme = AppTheme.NIGHT,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val resettedScreen: PluviaScreen? = null,
    val currentScreen: PluviaScreen? = PluviaScreen.LoginUser,
    val hasLaunched: Boolean = false,
    val loadingDialogVisible: Boolean = false,
    val loadingDialogProgress: Float = 0F,
    val loadingDialogMessage: String = "Loading...",
    val annoyingDialogShown: Boolean = false,
    val hasCrashedLastStart: Boolean = false,
    val isSteamConnected: Boolean = false,
    val launchedAppId: String = "",
    val bootToContainer: Boolean = false,
    val testGraphics: Boolean = false,
    val diagnostics: Boolean = false,
    val debugRun: Boolean = false,
    val showBootingSplash: Boolean = false,
    val bootingSplashText: String = "Booting...",
    val bootingSplashHeroImageUrl: String = "",
    val bootAd: BootAdItem? = null,
    val launchOptionPrompt: LaunchOptionPrompt? = null,
    val nonVrArgsPrompt: NonVrArgsPrompt? = null,

    // Connection state for background reconnection
    // Default to DISCONNECTED - service will start and set to CONNECTING
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionMessage: String? = null,
    val connectionTimeoutSeconds: Int = 0,
)
