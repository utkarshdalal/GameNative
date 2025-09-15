package app.gamenative.ui.screen.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Games
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.PluviaScreen
import kotlinx.coroutines.launch

@Composable
fun SteamAccountSection(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    // State for Steam
    val isSteamLoggedIn = SteamService.isLoggedIn
    
    AccountSection(
        title = "Steam",
        description = "Access your Steam library and games",
        icon = Icons.Default.Games,
        isLoggedIn = isSteamLoggedIn,
        username = if (isSteamLoggedIn) "Steam User" else null,
        onLogin = {
            navController.navigate(PluviaScreen.LoginUser.route)
        },
        onLogout = {
            scope.launch {
                SteamService.logOut()
            }
        },
        modifier = modifier
    )
}
