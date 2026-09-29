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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.component.settings.SettingsMultiListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ModDllOverrideLauncher
import app.gamenative.utils.ModDllOverrides
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVarSelectionType
import com.winlator.core.envvars.EnvVars
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EnvironmentTabContent(state: ContainerConfigState, appId: String? = null) {
    val context = LocalContext.current
    val config = state.config.value
    val supportsAutomaticOverrides =
        appId != null && ModDllOverrideLauncher.supportsLaunch(config.launchRealSteam, config.launchBionicSteam)
    var inspectionRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { inspectionRevision++ }
    val inspection by produceState<ModDllOverrides.Inspection?>(
        null, appId, config.executablePath, config.drives, supportsAutomaticOverrides, inspectionRevision,
    ) {
        value = null
        if (!supportsAutomaticOverrides) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val prefix = appId?.let { File(ContainerUtils.getContainer(context, it).rootDir, ".wine") }
                val executablePath = ModDllOverrideLauncher.resolvePreviewExecutable(config.executablePath, appId) {
                    SteamService.getInstalledExe(it)
                }
                ModDllOverrides.inspect(executablePath, ModDllOverrideLauncher.drives(config.drives), prefix)
            }.getOrNull()
        }
    }
    SettingsGroup {
        EnvironmentVariableRows(
            savedEnvVars = config.envVars,
            inspection = inspection.takeIf { supportsAutomaticOverrides },
            appId = appId,
            onEnvVarsChange = { state.config.value = config.copy(envVars = it) },
        )
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
                        NoExtractOutlinedTextField(
                            value = envVarName,
                            onValueChange = { envVarName = it },
                            label = { Text(text = stringResource(R.string.name)) },
                            singleLine = true,
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
                        var suggestionsExpanded by remember { mutableStateOf(false) }
                        val hasSuggestions = selectedEnvVarInfo?.selectionType == EnvVarSelectionType.SUGGESTIONS
                        NoExtractOutlinedTextField(
                            value = envVarValue,
                            onValueChange = { envVarValue = it },
                            label = { Text(text = stringResource(R.string.value)) },
                            singleLine = true,
                            trailingIcon = if (hasSuggestions) {
                                {
                                    IconButton(onClick = { suggestionsExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = "Presets",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = suggestionsExpanded,
                                        onDismissRequest = { suggestionsExpanded = false },
                                    ) {
                                        selectedEnvVarInfo!!.possibleValues.forEach { suggestion ->
                                            // suggestion box headers
                                            if (suggestion.startsWith("---")) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = suggestion.removePrefix("---"),
                                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                        )
                                                    },
                                                    onClick = {},
                                                    enabled = false,
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text(suggestion) },
                                                    onClick = {
                                                        envVarValue = suggestion
                                                        suggestionsExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                null
                            },
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

@Composable
internal fun EnvironmentVariableRows(
    savedEnvVars: String,
    inspection: ModDllOverrides.Inspection?,
    appId: String? = null,
    onEnvVarsChange: (String) -> Unit,
) {
    val saved = EnvVars(savedEnvVars)
    val displayed = EnvVars(savedEnvVars)
    // Do not refill the text field between keystrokes, including when the user clears it.
    var overridesEdited by rememberSaveable(appId) { mutableStateOf(false) }
    if (!overridesEdited) {
        inspection?.merge(saved.get("WINEDLLOVERRIDES"))?.takeIf { it.added.isNotEmpty() }?.let {
            displayed.put("WINEDLLOVERRIDES", it.value)
        }
    }
    val displayedOverrides = displayed.get("WINEDLLOVERRIDES")
    if (!displayed.isEmpty) {
        SettingsEnvVars(
            colors = settingsTileColors(),
            envVars = displayed,
            onEnvVarsChange = { edited ->
                if (edited.get("WINEDLLOVERRIDES") != displayedOverrides) overridesEdited = true
                onEnvVarsChange(savedEnvironmentAfterEdit(savedEnvVars, displayedOverrides, edited))
            },
            knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS,
            envVarAction = { identifier ->
                // Only saved variables can be deleted; generated overrides follow the installed mod.
                if (saved.has(identifier)) {
                    IconButton(
                        onClick = {
                            if (identifier == "WINEDLLOVERRIDES") overridesEdited = false
                            val updated = EnvVars(savedEnvVars).apply { remove(identifier) }
                            onEnvVarsChange(updated.toString())
                        },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
                    }
                }
            },
        )
    } else {
        SettingsCenteredLabel(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.no_environment_variables)) },
        )
    }
}

/** Editing another variable must not turn the displayed automatic overrides into saved settings. */
private fun savedEnvironmentAfterEdit(savedEnvVars: String, displayedOverrides: String, edited: EnvVars): String {
    val updated = EnvVars().apply { putAll(edited) }
    if (updated.get("WINEDLLOVERRIDES") == displayedOverrides) {
        val saved = EnvVars(savedEnvVars)
        if (saved.has("WINEDLLOVERRIDES")) {
            updated.put("WINEDLLOVERRIDES", saved.get("WINEDLLOVERRIDES"))
        } else {
            updated.remove("WINEDLLOVERRIDES")
        }
    }
    return updated.toString()
}
