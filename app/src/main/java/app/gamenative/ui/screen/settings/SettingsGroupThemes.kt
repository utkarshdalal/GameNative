package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.theme.ThemeManager
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

@Composable
fun SettingsGroupThemes() {
    SettingsGroup {
        var showThemeManager by rememberSaveable { mutableStateOf(false) }
        if (showThemeManager) {
            ThemeManagerDialog(open = showThemeManager, onDismiss = { showThemeManager = false })
        }

        val themes by ThemeManager.themes.collectAsState()
        val enabledCount = themes.count { it.enabled }

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.themes_manage)) },
            subtitle = { Text(text = stringResource(R.string.themes_settings_subtitle, enabledCount)) },
            onClick = { showThemeManager = true },
        )
    }
}
