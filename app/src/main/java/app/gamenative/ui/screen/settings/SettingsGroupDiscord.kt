package app.gamenative.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.discord.DiscordRichPresence
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupDiscord() {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    if (!isPreview) {
        PrefManager.init(context)
    }

    var richPresenceEnabled by rememberSaveable {
        mutableStateOf(if (isPreview) false else PrefManager.discordRichPresenceEnabled)
    }

    SettingsGroup(modifier = Modifier.background(Color.Transparent)) {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_discord_rich_presence_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_discord_rich_presence_subtitle)) },
            state = richPresenceEnabled,
            onCheckedChange = {
                richPresenceEnabled = it
                PrefManager.discordRichPresenceEnabled = it
                DiscordRichPresence.onEnabledChanged(it)
            },
        )
    }
}
