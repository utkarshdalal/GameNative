package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.LaunchInfo
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.data.LaunchOptionPrompt
import app.gamenative.ui.data.NonVrArgsPrompt
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.utils.SteamLaunchOptions

/** Steam-like prompt shown at Play when the launch mode has several Steam launch options. */
@Composable
fun LaunchOptionDialog(
    prompt: LaunchOptionPrompt,
    onChoose: (option: LaunchInfo, rememberChoice: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(prompt) { mutableIntStateOf(0) }
    var rememberChoice by remember(prompt) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.launch_option_title, prompt.gameName)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                prompt.options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == index, onClick = { selected = index })
                        LaunchOptionText(option, prompt.gameName)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text(text = stringResource(R.string.launch_option_dont_ask))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(prompt.options[selected], rememberChoice) }) {
                Text(text = stringResource(R.string.run_app))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
    )
}

/** Warns at Play that the container's arguments turn VR off; tells where to change them. */
@Composable
fun NonVrArgsDialog(
    prompt: NonVrArgsPrompt,
    onAnswer: (remove: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val location = stringResource(R.string.container_config_tab_general) + " › " + stringResource(R.string.exec_arguments)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.non_vr_args_title)) },
        text = {
            Text(text = stringResource(R.string.non_vr_args_message, prompt.gameName, prompt.args.joinToString(" "), location))
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(text = stringResource(R.string.non_vr_args_remove)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
                TextButton(onClick = { onAnswer(false) }) { Text(text = stringResource(R.string.non_vr_args_keep)) }
            }
        },
    )
}

/** Container setting for the remembered choice: "ask every time" or one of the options. */
@Composable
fun LaunchOptionSetting(
    title: String,
    options: List<LaunchInfo>,
    gameName: String,
    selectedKey: String,
    onSelected: (key: String) -> Unit,
) {
    val labels = listOf(stringResource(R.string.launch_option_ask)) + options.map { launchOptionLabel(it, gameName) }
    val selectedIndex = options.indexOfFirst { SteamLaunchOptions.key(it) == selectedKey } + 1
    SettingsListDropdown(
        colors = settingsTileColors(),
        title = { Text(text = title) },
        value = selectedIndex,
        items = labels,
        onItemSelected = { index ->
            onSelected(if (index == 0) "" else SteamLaunchOptions.key(options[index - 1]))
        },
    )
}

@Composable
private fun LaunchOptionText(option: LaunchInfo, gameName: String) {
    Column {
        Text(text = launchOptionLabel(option, gameName))
        if (option.arguments.isNotBlank()) {
            Text(
                text = option.arguments,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun launchOptionLabel(option: LaunchInfo, gameName: String): String {
    val name = option.description.ifBlank { gameName }
    val api = when {
        option.type.equals("openxr", ignoreCase = true) -> "OpenXR"
        option.type.equals("vr", ignoreCase = true) -> "SteamVR"
        else -> null
    }
    return if (api != null) "$name · $api" else name
}
