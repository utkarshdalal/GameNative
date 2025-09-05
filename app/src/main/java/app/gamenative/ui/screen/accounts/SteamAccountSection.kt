package app.gamenative.ui.screen.accounts

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.PluviaScreen
import kotlinx.coroutines.launch

@Composable
fun SteamAccountSection(
    onNavigateRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSteamLoggedIn = remember { mutableStateOf(SteamService.isLoggedIn)}

    AccountSection(
        title = "Steam",
        description = "Access your Steam library and games",
        icon = "https://store.steampowered.com/favicon.ico",
        isLoggedIn = isSteamLoggedIn.value,
        username = if (isSteamLoggedIn.value) "Steam User" else null,
        onLogin = { onNavigateRoute(PluviaScreen.LoginUser.route) },
        onLogout = {
            SteamService.logOut()
            isSteamLoggedIn.value = false // Trigger a redraw
        },
        modifier = modifier,
    )
}
