package app.gamenative.ui.component.topbar

import android.content.res.Configuration
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PluviaApp
import app.gamenative.data.SteamFriend
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.ProfileDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SteamIconImage
import app.gamenative.utils.getAvatarURL
import `in`.dragonbra.javasteam.enums.EPersonaState
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun AccountButton(
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    isOffline: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var persona by remember { mutableStateOf<SteamFriend?>(null) }

    LaunchedEffect(Unit) {
        persona = SteamService.instance?.localPersona?.value
    }

    DisposableEffect(true) {
        val onPersonaStateReceived: (SteamEvent.PersonaStateReceived) -> Unit = { event ->
            Timber.d("onPersonaStateReceived: ${event.persona.state}")
            persona = event.persona
        }

        PluviaApp.events.on<SteamEvent.PersonaStateReceived, Unit>(onPersonaStateReceived)

        onDispose {
            PluviaApp.events.off<SteamEvent.PersonaStateReceived, Unit>(onPersonaStateReceived)
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    ProfileDialog(
        openDialog = showDialog,
        name = persona?.name.orEmpty(),
        avatarHash = persona?.avatarHash.orEmpty(),
        state = persona?.state ?: EPersonaState.Offline,
        onStatusChange = {
            scope.launch {
                SteamService.setPersonaState(it)
            }
        },
        onNavigateRoute = {
            onNavigateRoute(it)
            showDialog = false
        },
        onLogout = {
            onLogout()
            showDialog = false
        },
        onGoOnline = {
            onGoOnline()
            showDialog = false
        },
        onDismiss = {
            showDialog = false
        },
        isOffline = isOffline,
    )

    IconButton(
        onClick = { showDialog = true },
        content = {
            SteamIconImage(
                image = { persona?.avatarHash?.getAvatarURL() },
                contentDescription = "Logged in account user profile",
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_AccountButton() {
    PluviaTheme {
        CenterAlignedTopAppBar(
            title = { Text("Top App Bar") },
            actions = {
                AccountButton(
                    onNavigateRoute = {},
                    onLogout = {},
                    onGoOnline = {},
                )
            },
        )
    }
}
