package app.gamenative.ui.component.dialog

import android.content.Context
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
import androidx.compose.runtime.remember
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
import com.winlator.core.StringUtils
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCorePreset
import com.winlator.fexcore.FEXCorePresetManager
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import org.json.JSONArray
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FEXCorePresetsDialog(
    visible: Boolean = true,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val defaultValues = remember {
        loadFexcoreEnvDefaults(context).also {
            if (it.isEmpty()) Timber.w("FEXCore default env vars not found")
        }
    }

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
                        title = { Text(text = stringResource(R.string.fexcore_presets)) },
                        actions = {
                            IconButton(
                                onClick = onDismissRequest,
                                content = { Icon(Icons.Default.Done, "Close FEXCore Presets") },
                            )
                        },
                    )
                },
            ) { paddingValues ->
                val getPresets: () -> ArrayList<FEXCorePreset> = { FEXCorePresetManager.getPresets(context) }
                fun resolvePreset(id: String): FEXCorePreset =
                    getPresets().firstOrNull { it.id == id } ?: getPresets().first()

                var showPresets by rememberSaveable { mutableStateOf(false) }
                var presetId by rememberSaveable { mutableStateOf(getPresets().first().id) }
                var presetName by rememberSaveable { mutableStateOf(resolvePreset(presetId).name) }
                var envVars by rememberSaveable {
                    mutableStateOf(
                        ensureFexcoreEnvDefaults(
                            FEXCorePresetManager.getEnvVars(context, presetId),
                            defaultValues,
                        ).toString(),
                    )
                }
                val isCustom = { resolvePreset(presetId).isCustom }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        value = presetName,
                        enabled = isCustom(),
                        onValueChange = {
                            presetName = it.replace("|", "")
                            FEXCorePresetManager.editPreset(context, presetId, presetName, EnvVars(envVars))
                        },
                        label = { Text(stringResource(R.string.preset_name)) },
                        trailingIcon = {
                            IconButton(
                                colors = IconButtonDefaults.iconButtonColors()
                                    .copy(contentColor = MaterialTheme.colorScheme.onSurface),
                                onClick = { showPresets = true },
                                content = { Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = "Preset list") },
                            )
                            DropdownMenu(
                                expanded = showPresets,
                                onDismissRequest = { showPresets = false },
                            ) {
                                for (preset in getPresets()) {
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            presetId = preset.id
                                            presetName = resolvePreset(presetId).name
                                            envVars = ensureFexcoreEnvDefaults(
                                                FEXCorePresetManager.getEnvVars(context, presetId),
                                                defaultValues,
                                            ).toString()
                                            showPresets = false
                                        },
                                    )
                                }
                            }
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.environment_variables))
                        Row {
                            IconButton(
                                onClick = {
                                    FEXCorePresetManager.duplicatePreset(context, presetId)?.let { newId ->
                                        presetId = newId
                                        presetName = resolvePreset(presetId).name
                                        envVars = ensureFexcoreEnvDefaults(
                                            FEXCorePresetManager.getEnvVars(context, presetId),
                                            defaultValues,
                                        ).toString()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Duplicate preset",
                                )
                            }
                            IconButton(
                                onClick = {
                                    val defaults = EnvVars().apply {
                                        defaultValues.forEach { (key, value) -> put(key, value) }
                                    }
                                    presetId = FEXCorePresetManager.editPreset(context, null, "Unnamed", defaults)
                                    presetName = resolvePreset(presetId).name
                                    envVars = defaults.toString()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AddCircle,
                                    contentDescription = "Create preset",
                                )
                            }
                            IconButton(
                                enabled = isCustom(),
                                onClick = {
                                    val idToDelete = presetId
                                    presetId = getPresets().first().id
                                    presetName = resolvePreset(presetId).name
                                    envVars = ensureFexcoreEnvDefaults(
                                        FEXCorePresetManager.getEnvVars(context, presetId),
                                        defaultValues,
                                    ).toString()
                                    FEXCorePresetManager.removePreset(context, idToDelete)
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete preset",
                                )
                            }
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
                        val envVarState = remember(envVars) {
                            ensureFexcoreEnvDefaults(EnvVars(envVars), defaultValues)
                        }
                        SettingsEnvVars(
                            colors = settingsTileColors(),
                            enabled = isCustom(),
                            envVars = envVarState,
                            onEnvVarsChange = {
                                ensureFexcoreEnvDefaults(it, defaultValues)
                                envVars = it.toString()
                                FEXCorePresetManager.editPreset(context, presetId, presetName, it)
                            },
                            knownEnvVars = EnvVarInfo.KNOWN_FEXCORE_VARS,
                            envVarAction = { varName ->
                                IconButton(
                                    onClick = {
                                        val resName = "fexcore_env_var_help__" +
                                            varName.removePrefix("FEX_").lowercase(Locale.getDefault())
                                        StringUtils.getString(context, resName)
                                            ?.let { infoMsg = it }
                                            ?: Timber.w("Could not find string resource of $resName")
                                    },
                                    content = { Icon(Icons.Outlined.Info, contentDescription = "Variable info") },
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun loadFexcoreEnvDefaults(context: Context): LinkedHashMap<String, String> {
    return try {
        val jsonText = context.assets.open("fexcore_env_vars.json").bufferedReader().use { it.readText() }
        val array = JSONArray(jsonText)
        LinkedHashMap<String, String>().apply {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val defaultValue = obj.optString("defaultValue").ifEmpty {
                    obj.optJSONArray("values")?.optString(0).orEmpty()
                }
                put(name, defaultValue)
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to load fexcore_env_vars.json")
        LinkedHashMap()
    }
}

private fun ensureFexcoreEnvDefaults(envVars: EnvVars, defaults: Map<String, String>): EnvVars {
    defaults.forEach { (key, value) ->
        if (!envVars.has(key) && value.isNotEmpty()) {
            envVars.put(key, value)
        }
    }
    return envVars
}

