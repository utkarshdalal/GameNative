package app.gamenative.ui.screen

import android.net.Uri

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings")
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
    data object ScreenshotGallery : PluviaScreen("screenshot_gallery/{appId}?index={index}") {
        // index >= 0 opens the viewer at that shot; -1 opens the list.
        fun route(appId: String, index: Int = -1) = "screenshot_gallery/${Uri.encode(appId)}?index=$index"
        const val ARG_APP_ID = "appId"
        const val ARG_INDEX = "index"
    }
}
