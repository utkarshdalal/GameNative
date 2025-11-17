package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AirplaneTicket
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SteamIconImage
import app.gamenative.utils.getAvatarURL
import `in`.dragonbra.javasteam.enums.EPersonaState

@Composable
fun ProfileDialog(
    openDialog: Boolean,
    name: String,
    avatarHash: String,
    state: EPersonaState,
    onStatusChange: (EPersonaState) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    onGoOnline: () -> Unit,
    isOffline: Boolean = false,
) {
    if (!openDialog) {
        return
    }

    var selectedItem by remember(state) { mutableStateOf(state) }
    var showSupporters by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val uriHandler = LocalUriHandler.current
                /* Icon, Name, and Status */
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                    leadingContent = {
                        SteamIconImage(
                            size = 48.dp,
                            image = { avatarHash.getAvatarURL() },
                        )
                    },
                    headlineContent = {
                        Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(text = state.name)
                    },
                )
                /* Online Status */
                Spacer(modifier = Modifier.height(16.dp))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val status =
                        listOf(EPersonaState.Online, EPersonaState.Away, EPersonaState.Invisible)
                    status.forEachIndexed { index, state ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = status.size,
                            ),
                            onClick = {
                                selectedItem = state
                                onStatusChange(state)
                            },
                            selected = state == selectedItem,
                            label = {
                                Text(state.name)
                            },
                        )
                    }
                }

                /* Action Buttons */
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { onNavigateRoute(PluviaScreen.Settings.route) }) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(text = "Settings")
                }

                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { uriHandler.openUri("https://discord.gg/2hKv4VfZfE") }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Help, contentDescription = null)
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(text = stringResource(R.string.help_and_support))
                }

                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { showSupporters = true }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.StarHalf, contentDescription = null)
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(text = stringResource(R.string.hall_of_fame))
                }

                if(isOffline) {
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onGoOnline) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(text = stringResource(R.string.go_online))
                    }
                } else {
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        SteamService.stop()
                        onNavigateRoute(PluviaScreen.Home.route + "?offline=true")
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.AirplaneTicket, contentDescription = null)
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(text = stringResource(R.string.go_offline))
                    }
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onLogout) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(text = stringResource(R.string.log_out))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
    )

    SupportersDialog(visible = showSupporters, onDismiss = { showSupporters = false })
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ProfileDialog() {
    PluviaTheme {
        ProfileDialog(
            openDialog = true,
            name = stringResource(R.string.app_name).repeat(4),
            avatarHash = "",
            state = EPersonaState.Online,
            onStatusChange = {},
            onNavigateRoute = {},
            onLogout = {},
            onDismiss = {},
            onGoOnline = {},
        )
    }
}
