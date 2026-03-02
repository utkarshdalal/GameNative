package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.component.settings.SettingsMultiListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.core.envvars.EnvVarSelectionType

@Composable
fun EnvironmentTabContent(state: ContainerConfigState) {
    val config = state.config.value
    val envVars = EnvVars(config.envVars)
    SettingsGroup() {
        if (config.envVars.isNotEmpty()) {
            SettingsEnvVars(
                colors = settingsTileColors(),
                envVars = envVars,
                onEnvVarsChange = {
                    state.config.value = config.copy(envVars = it.toString())
                },
                knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS,
                envVarAction = {
                    IconButton(
                        onClick = {
                            envVars.remove(it)
                            state.config.value = config.copy(envVars = envVars.toString())
                        },
                        content = {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
                        },
                    )
                },
            )
        } else {
            SettingsCenteredLabel(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.no_environment_variables)) },
            )
        }
        SettingsMenuLink(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddCircleOutline,
                        contentDescription = "Add environment variable",
                    )
                }
            },
            onClick = { state.showEnvVarCreateDialog.value = true },
        )
    }

    if (state.showEnvVarCreateDialog.value) {
        var envVarName by rememberSaveable { mutableStateOf("") }
        var envVarValue by rememberSaveable { mutableStateOf("") }
        val config = state.config.value
        AlertDialog(
            onDismissRequest = { state.showEnvVarCreateDialog.value = false },
            title = { Text(text = stringResource(R.string.new_environment_variable)) },
            text = {
                var knownVarsMenuOpen by rememberSaveable { mutableStateOf(false) }
                Column {
                    Row {
                        OutlinedTextField(
                            value = envVarName,
                            onValueChange = { envVarName = it },
                            label = { Text(text = stringResource(R.string.name)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { knownVarsMenuOpen = true },
                                    content = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = "List known variable names",
                                        )
                                    },
                                )
                            },
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = knownVarsMenuOpen,
                            onDismissRequest = { knownVarsMenuOpen = false },
                        ) {
                            val knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS.values.filter {
                                !config.envVars.contains("${it.identifier}=")
                            }
                            if (knownEnvVars.isNotEmpty()) {
                                for (knownVariable in knownEnvVars) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(knownVariable.identifier) },
                                        onClick = {
                                            envVarName = knownVariable.identifier
                                            knownVarsMenuOpen = false
                                        },
                                    )
                                }
                            } else {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.no_more_known_variables)) },
                                    onClick = {},
                                )
                            }
                        }
                    }
                    val selectedEnvVarInfo = EnvVarInfo.KNOWN_ENV_VARS[envVarName]
                    if (selectedEnvVarInfo?.selectionType == EnvVarSelectionType.MULTI_SELECT) {
                        var multiSelectedIndices by remember { mutableStateOf(listOf<Int>()) }
                        SettingsMultiListDropdown(
                            enabled = true,
                            values = multiSelectedIndices,
                            items = selectedEnvVarInfo.possibleValues,
                            fallbackDisplay = "",
                            onItemSelected = { index ->
                                val newIndices = if (multiSelectedIndices.contains(index)) {
                                    multiSelectedIndices.filter { it != index }
                                } else {
                                    multiSelectedIndices + index
                                }
                                multiSelectedIndices = newIndices
                                envVarValue = newIndices.joinToString(",") { selectedEnvVarInfo.possibleValues[it] }
                            },
                            title = { Text(text = stringResource(R.string.value)) },
                            colors = settingsTileColors(),
                        )
                    } else {
                        OutlinedTextField(
                            value = envVarValue,
                            onValueChange = { envVarValue = it },
                            label = { Text(text = stringResource(R.string.value)) },
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { state.showEnvVarCreateDialog.value = false },
                    content = { Text(text = stringResource(R.string.cancel)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = envVarName.isNotEmpty(),
                    onClick = {
                        val envVars = EnvVars(config.envVars)
                        envVars.put(envVarName, envVarValue)
                        state.config.value = config.copy(envVars = envVars.toString())
                        state.showEnvVarCreateDialog.value = false
                    },
                    content = { Text(text = stringResource(R.string.ok)) },
                )
            },
        )
    }
}
