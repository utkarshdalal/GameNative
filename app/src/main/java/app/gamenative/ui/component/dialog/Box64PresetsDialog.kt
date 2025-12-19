package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.theme.settingsTileColors
import com.winlator.box86_64.Box86_64Preset
import com.winlator.box86_64.Box86_64PresetManager
import com.winlator.core.StringUtils
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Box64PresetsDialog(
    visible: Boolean = true,
    onDismissRequest: () -> Unit,
) {
    if (visible) {
        val context = LocalContext.current
        val prefix = "box64"
        val scrollState = rememberScrollState()

        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
            content = {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(text = stringResource(R.string.box64_presets)) },
                            actions = {
                                IconButton(
                                    onClick = onDismissRequest,
                                    content = {
                                        Icon(Icons.Default.Done, "Close Box64 Presets")
                                    },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    val getPresets: () -> ArrayList<Box86_64Preset> = { Box86_64PresetManager.getPresets(prefix, context) }
                    val getPreset: (String) -> Box86_64Preset = { id -> getPresets().first { it.id == id } }
                    var showPresets by rememberSaveable { mutableStateOf(false) }
                    var presetId by rememberSaveable { mutableStateOf(getPresets().first().id) }
                    var presetName by rememberSaveable { mutableStateOf(getPreset(presetId).name) }
                    var envVars by rememberSaveable {
                        mutableStateOf(
                            Box86_64PresetManager.getEnvVars(prefix, context, getPreset(presetId).id).toString(),
                        )
                    }
                    val isCustom: () -> Boolean = { getPreset(presetId).isCustom }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            value = presetName,
                            enabled = isCustom(),
                            onValueChange = {
                                presetName = it.replace("|", "")
                                Box86_64PresetManager.editPreset(prefix, context, presetId, presetName, EnvVars(envVars))
                            },
                            label = { Text(stringResource(R.string.preset_name)) },
                            trailingIcon = {
                                IconButton(
                                    colors = IconButtonDefaults.iconButtonColors()
                                        .copy(contentColor = MaterialTheme.colorScheme.onSurface),
                                    onClick = { showPresets = true },
                                    content = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = "Preset list",
                                        )
                                    },
                                )
                                DropdownMenu(
                                    expanded = showPresets,
                                    onDismissRequest = { showPresets = false },
                                    content = {
                                        for (preset in getPresets()) {
                                            DropdownMenuItem(
                                                text = { Text(preset.name) },
                                                onClick = {
                                                    presetId = preset.id
                                                    presetName = getPreset(presetId).name
                                                    envVars = Box86_64PresetManager.getEnvVars(
                                                        prefix,
                                                        context,
                                                        getPreset(presetId).id,
                                                    ).toString()
                                                    showPresets = false
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.environment_variables))
                            Row {
                                IconButton(
                                    onClick = {
                                        presetId = Box86_64PresetManager.duplicatePreset(prefix, context, presetId)
                                        presetName = getPreset(presetId).name
                                        envVars = Box86_64PresetManager.getEnvVars(prefix, context, getPreset(presetId).id).toString()
                                    },
                                    content = {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "Duplicate preset",
                                        )
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        val defaultEnvVars = EnvVarInfo.KNOWN_BOX64_VARS.values.joinToString(" ") {
                                            "${it.identifier}=${it.possibleValues.first()}"
                                        }
                                        presetId = Box86_64PresetManager
                                            .editPreset(prefix, context, null, "Unnamed", EnvVars(defaultEnvVars))
                                        presetName = getPreset(presetId).name
                                        envVars = Box86_64PresetManager.getEnvVars(prefix, context, getPreset(presetId).id).toString()
                                    },
                                    content = {
                                        Icon(
                                            imageVector = Icons.Outlined.AddCircle,
                                            contentDescription = "Create preset",
                                        )
                                    },
                                )
                                IconButton(
                                    enabled = isCustom(),
                                    onClick = {
                                        val idToBeDeleted = presetId
                                        presetId = getPresets().first().id
                                        presetName = getPreset(presetId).name
                                        envVars = Box86_64PresetManager.getEnvVars(prefix, context, getPreset(presetId).id).toString()
                                        Box86_64PresetManager.removePreset(prefix, context, idToBeDeleted)
                                    },
                                    content = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete preset",
                                        )
                                    },
                                )
                            }
                        }
                        Column(modifier = Modifier.verticalScroll(scrollState)) {
                            var infoMsg by rememberSaveable { mutableStateOf("") }
                            MessageDialog(
                                visible = infoMsg.isNotEmpty(),
                                onDismissRequest = { infoMsg = "" },
                                message = infoMsg,
                                useHtmlInMsg = true,
                            )
                            SettingsEnvVars(
                                colors = settingsTileColors(),
                                enabled = isCustom(),
                                envVars = EnvVars(envVars),
                                onEnvVarsChange = {
                                    envVars = it.toString()
                                    Box86_64PresetManager.editPreset(prefix, context, presetId, presetName, it)
                                },
                                knownEnvVars = EnvVarInfo.KNOWN_BOX64_VARS,
                                envVarAction = { varName ->
                                    IconButton(
                                        onClick = {
                                            val resName = varName.replace(prefix.uppercase(), "box86_64_env_var_help_").lowercase()
                                            StringUtils.getString(context, resName)
                                                ?.let { infoMsg = it }
                                                ?: Timber.w("Could not find string resource of $resName")
                                        },
                                        content = {
                                            Icon(Icons.Outlined.Info, contentDescription = "Variable info")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
