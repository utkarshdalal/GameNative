package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.service.ea.EaAuthManager
import app.gamenative.service.rockstar.RockstarAuthManager
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

@Composable
fun SettingsGroupAccounts() {
    val context = LocalContext.current
    SettingsGroup {
        var eaName by remember { mutableStateOf(EaAuthManager.displayName(context)) }
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.settings_accounts_ea_title)) },
            subtitle = {
                Text(eaName ?: stringResource(R.string.settings_accounts_signed_out))
            },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            enabled = eaName != null,
            onClick = {
                EaAuthManager.logout(context)
                eaName = null
            },
        )

        var rockstarName by remember { mutableStateOf(RockstarAuthManager.nickname(context)) }
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.settings_accounts_rockstar_title)) },
            subtitle = {
                Text(rockstarName ?: stringResource(R.string.settings_accounts_signed_out))
            },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            enabled = rockstarName != null,
            onClick = {
                RockstarAuthManager.logout(context)
                rockstarName = null
            },
        )
    }
}
