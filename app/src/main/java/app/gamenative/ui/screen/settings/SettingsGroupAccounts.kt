package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.runtime.collectAsState
import app.gamenative.service.store.StoreAdapters
import app.gamenative.service.store.StoreGames
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.rockstar.RockstarAuthManager
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import app.gamenative.ui.component.settings.SettingsMenuLink
import app.gamenative.ui.component.settings.SettingsSwitch
import kotlinx.coroutines.launch

@Composable
fun SettingsGroupAccounts() {
    val context = LocalContext.current
    SettingsGroup {
        val scope = rememberCoroutineScope()
        val storeGames by StoreGames.all.collectAsState()
        val helperFailures by StoreGames.helperFailures.collectAsState()
        var accountsChanged by remember { mutableStateOf(0) }
        for (store in StoreAdapters.all) {
            val account = remember(storeGames, accountsChanged) { store.accountName(context) }
            val signedIn = account != null
            val available = remember(storeGames, accountsChanged, helperFailures) {
                StoreGames.availability(context, store.source) != StoreGames.Availability.UNAVAILABLE
            }
            if (signedIn || available) {
                SettingsMenuLink(
                    colors = settingsTileColors(),
                    title = { Text(stringResource(R.string.settings_accounts_store_title, store.displayName)) },
                    subtitle = {
                        Text(account ?: stringResource(R.string.settings_accounts_signed_out))
                    },
                    icon = {
                        Icon(if (signedIn) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    },
                    onClick = {
                        if (signedIn) {
                            scope.launch {
                                StoreGames.signOut(context, store.source)
                                accountsChanged += 1
                            }
                        } else {
                            StoreGames.startSignIn(context, store.source)
                        }
                    },
                )
            }
        }

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
                rockstarName = null
                scope.launch { RockstarAuthManager.logout(context.applicationContext) }
            },
        )

        var ubisoftUploads by rememberSaveable { mutableStateOf(PrefManager.ubisoftCloudSaveUploads) }
        SettingsSwitch(
            colors = settingsTileColors(),
            state = ubisoftUploads,
            title = { Text(stringResource(R.string.settings_accounts_ubisoft_uploads_title)) },
            subtitle = { Text(stringResource(R.string.settings_accounts_ubisoft_uploads_subtitle)) },
            onCheckedChange = {
                ubisoftUploads = it
                PrefManager.ubisoftCloudSaveUploads = it
            },
        )
    }
}
