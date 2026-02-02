package app.gamenative.events

import app.gamenative.ui.enums.Orientation
import java.util.EnumSet

interface AndroidEvent<T> : Event<T> {
    data object BackPressed : AndroidEvent<Unit>
    data class SetSystemUIVisibility(val visible: Boolean) : AndroidEvent<Unit>
    data class SetAllowedOrientation(val orientations: EnumSet<Orientation>) : AndroidEvent<Unit>
    data object StartOrientator : AndroidEvent<Unit>
    data object ActivityDestroyed : AndroidEvent<Unit>
    data object GuestProgramTerminated : AndroidEvent<Unit>
    data class KeyEvent(val event: android.view.KeyEvent) : AndroidEvent<Boolean>
    data class MotionEvent(val event: android.view.MotionEvent?) : AndroidEvent<Boolean>
    data object EndProcess : AndroidEvent<Unit>
    data class ExternalGameLaunch(val appId: String) : AndroidEvent<Unit>
    data class PromptSaveContainerConfig(val appId: String) : AndroidEvent<Unit>
    data class ShowGameFeedback(val appId: String) : AndroidEvent<Unit>
    data class ShowLaunchingOverlay(val appName: String) : AndroidEvent<Unit>
    data object HideLaunchingOverlay : AndroidEvent<Unit>
    data class SetBootingSplashText(val text: String) : AndroidEvent<Unit>
    data class DownloadPausedDueToConnectivity(val appId: Int) : AndroidEvent<Unit>
    data class DownloadStatusChanged(val appId: Int, val isDownloading: Boolean) : AndroidEvent<Unit>
    data class LibraryInstallStatusChanged(val appId: Int) : AndroidEvent<Unit>
    data class CustomGameImagesFetched(val appId: String) : AndroidEvent<Unit>
    data class GOGAuthCodeReceived(val authCode: String) : AndroidEvent<Unit>
    data class EpicAuthCodeReceived(val authCode: String) : AndroidEvent<Unit>
    // data class SetAppBarVisibility(val visible: Boolean) : AndroidEvent<Unit>
}
