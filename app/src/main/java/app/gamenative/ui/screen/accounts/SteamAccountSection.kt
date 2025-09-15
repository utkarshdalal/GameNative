package app.gamenative.ui.screen.accounts

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.PluviaScreen
import kotlinx.coroutines.launch

@Composable
fun SteamAccountSection(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val isSteamLoggedIn = SteamService.isLoggedIn

    AccountSection(
        title = "Steam",
        description = "Access your Steam library and games",
        icon = "https://store.steampowered.com/favicon.ico",
        isLoggedIn = isSteamLoggedIn,
        username = if (isSteamLoggedIn) "Steam User" else null,
        onLogin = {
            navController.navigate(PluviaScreen.LoginUser.route)
        },
        onLogout = {
            scope.launch {
                SteamService.logOut()
                // Re-navigate to current screen to refresh logged in state
                navController.navigate(PluviaScreen.AccountManagement.route) {
                    popUpTo(PluviaScreen.Home.route) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        },
        modifier = modifier,
    )
}
