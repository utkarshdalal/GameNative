package app.gamenative.events

import app.gamenative.data.GameSource
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet

interface AndroidEvent<T> : Event<T> {
    data object BackPressed : AndroidEvent<Unit>
    data class SetSystemUIVisibility(val visible: Boolean) : AndroidEvent<Unit>
    data class SetAllowedOrientation(val orientations: EnumSet<Orientation>) : AndroidEvent<Unit>
    data object StartOrientator : AndroidEvent<Unit>
    data object ActivityDestroyed : AndroidEvent<Unit>
    data object GuestProgramTerminated : AndroidEvent<Unit>

    // fires immediately after webView.destroy() returns. save-sync subscribes here
    // (leveldb lock releases post-destroy). built once so doesn't retouch
    // WebViewScreen.
    data object WebViewDestroyed : AndroidEvent<Unit>
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
    data class PostInstallSyncStatusChanged(val appId: Int, val isSyncing: Boolean) : AndroidEvent<Unit>
    data class LibraryInstallStatusChanged(val appId: Int, val source: GameSource) : AndroidEvent<Unit>
    // fires when a sideloaded folder is newly added to the manual-folders set (the "install"
    // moment for custom games, since they don't have a download phase). consumed by
    // Html5InstallWatcher to auto-fingerprint + flip variant=html5 when an engine matches,
    // matching Steam/GOG behavior on download completion.
    data class CustomGameDiscovered(val appId: Int) : AndroidEvent<Unit>
    data class CustomGameImagesFetched(val appId: String) : AndroidEvent<Unit>
    data object RecommendationToggleChanged : AndroidEvent<Unit>
    data class GOGAuthCodeReceived(val authCode: String) : AndroidEvent<Unit>
    data class EpicAuthCodeReceived(val authCode: String) : AndroidEvent<Unit>
    data object ServiceReady : AndroidEvent<Unit>
    // data class SetAppBarVisibility(val visible: Boolean) : AndroidEvent<Unit>
}
