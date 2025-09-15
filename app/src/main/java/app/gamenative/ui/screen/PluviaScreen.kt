package app.gamenative.ui.screen

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings")
    data object AccountManagement : PluviaScreen("accounts")
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
}
