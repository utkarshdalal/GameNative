package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.gamenative.Constants
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.LibrariesDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupInfo() {
    SettingsGroup(title = { Text(text = stringResource(R.string.settings_info_title)) }) {
        val uriHandler = LocalUriHandler.current
        var askForTip by rememberSaveable { mutableStateOf(!PrefManager.tipped) }
        var showLibrariesDialog by rememberSaveable { mutableStateOf(false) }

        LibrariesDialog(
            visible = showLibrariesDialog,
            onDismissRequest = { showLibrariesDialog = false },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.settings_info_send_tip_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_send_tip_subtitle)) },
            icon = { Icon(imageVector = Icons.Filled.MonetizationOn, contentDescription = "Tip") },
            onClick = {
                uriHandler.openUri(Constants.Misc.KO_FI_LINK)
                askForTip = false
                PrefManager.tipped = !askForTip
            },
        )

        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = askForTip,
            title = { Text(stringResource(R.string.settings_info_ask_tip_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_ask_tip_subtitle)) },
            onCheckedChange = {
                askForTip = it
                PrefManager.tipped = !askForTip
            },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_source_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_source_subtitle)) },
            onClick = { uriHandler.openUri(Constants.Misc.GITHUB_LINK) },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_libraries_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_libraries_subtitle)) },
            onClick = { showLibrariesDialog = true },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_privacy_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_privacy_subtitle)) },
            onClick = {
                uriHandler.openUri(Constants.Misc.PRIVACY_LINK)
            },
        )
    }
}
