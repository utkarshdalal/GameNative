package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupPerformance() {
    SettingsGroup {
        var powerControlDefaultEnabled by rememberSaveable { mutableStateOf(PrefManager.powerControlDefaultEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = powerControlDefaultEnabled,
            title = { Text(stringResource(R.string.settings_performance_power_control_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_power_control_subtitle)) },
            onCheckedChange = {
                powerControlDefaultEnabled = it
                PrefManager.powerControlDefaultEnabled = it
            },
        )

        var texturePackEnabled by rememberSaveable { mutableStateOf(PrefManager.texturePackEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = texturePackEnabled,
            title = { Text(stringResource(R.string.settings_texture_pack_title)) },
            subtitle = { Text(stringResource(R.string.settings_texture_pack_subtitle)) },
            onCheckedChange = {
                texturePackEnabled = it
                PrefManager.texturePackEnabled = it
            },
        )

        var texturePackAllowMobileData by rememberSaveable { mutableStateOf(PrefManager.texturePackAllowMobileData) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = texturePackAllowMobileData,
            title = { Text(stringResource(R.string.settings_texture_pack_mobile_data_title)) },
            subtitle = { Text(stringResource(R.string.settings_texture_pack_mobile_data_subtitle)) },
            onCheckedChange = {
                texturePackAllowMobileData = it
                PrefManager.texturePackAllowMobileData = it
            },
        )

        var texturePackServer by rememberSaveable { mutableStateOf(PrefManager.texturePackServer) }
        var editingServer by rememberSaveable { mutableStateOf(false) }
        var serverDraft by rememberSaveable { mutableStateOf(texturePackServer) }
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.settings_texture_pack_server_title)) },
            subtitle = { Text(texturePackServer) },
            onClick = {
                serverDraft = texturePackServer
                editingServer = true
            },
        )

        var texturePackToken by rememberSaveable { mutableStateOf(PrefManager.texturePackToken) }
        var editingToken by rememberSaveable { mutableStateOf(false) }
        var tokenDraft by rememberSaveable { mutableStateOf(texturePackToken) }
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.settings_texture_pack_token_title)) },
            subtitle = { Text(if (texturePackToken.isBlank()) stringResource(R.string.settings_texture_pack_token_unset) else "••••••••") },
            onClick = {
                tokenDraft = texturePackToken
                editingToken = true
            },
        )

        if (editingToken) {
            AlertDialog(
                onDismissRequest = { editingToken = false },
                title = { Text(stringResource(R.string.settings_texture_pack_token_title)) },
                text = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = tokenDraft.trim()
                        PrefManager.texturePackToken = value
                        texturePackToken = value
                        editingToken = false
                    }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingToken = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        if (editingServer) {
            AlertDialog(
                onDismissRequest = { editingServer = false },
                title = { Text(stringResource(R.string.settings_texture_pack_server_title)) },
                text = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverDraft,
                        onValueChange = { serverDraft = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = serverDraft.trim().ifBlank { PrefManager.DEFAULT_TEXTURE_PACK_SERVER }
                        PrefManager.texturePackServer = value
                        texturePackServer = value
                        editingServer = false
                    }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        PrefManager.texturePackServer = PrefManager.DEFAULT_TEXTURE_PACK_SERVER
                        texturePackServer = PrefManager.DEFAULT_TEXTURE_PACK_SERVER
                        editingServer = false
                    }) {
                        Text(stringResource(R.string.settings_texture_pack_server_reset))
                    }
                },
            )
        }
    }
}
