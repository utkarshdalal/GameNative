package app.gamenative.ui.component.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVarSelectionType
import com.winlator.core.envvars.EnvVars
import kotlin.text.split

@Composable
fun SettingsEnvVars(
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    envVars: EnvVars,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    onEnvVarsChange: (EnvVars) -> Unit,
    knownEnvVars: Map<String, EnvVarInfo>,
    envVarAction: (@Composable (String) -> Unit)? = null,
) {
    for (identifier in envVars) {
        val value = envVars.get(identifier)
        val envVarInfo = knownEnvVars[identifier]
        when (envVarInfo?.selectionType ?: EnvVarSelectionType.NONE) {
            EnvVarSelectionType.TOGGLE -> {
                SettingsSwitchWithAction(
                    colors = colors,
                    enabled = enabled,
                    title = { Text(identifier) },
                    state = envVarInfo?.possibleValues?.indexOf(value) != 0,
                    onCheckedChange = {
                        val newValue = envVarInfo!!.possibleValues[if (it) 1 else 0]
                        envVars.put(identifier, newValue)
                        onEnvVarsChange(envVars)
                    },
                    action = envVarAction?.let {
                        { envVarAction(identifier) }
                    },
                )
            }
            EnvVarSelectionType.MULTI_SELECT -> {
                val values = value.split(",")
                    .map { envVarInfo!!.possibleValues.indexOf(it) }
                    .filter { it >= 0 && it < envVarInfo!!.possibleValues.size }
                SettingsMultiListDropdown(
                    colors = colors,
                    enabled = enabled,
                    title = { Text(identifier) },
                    values = values,
                    items = envVarInfo!!.possibleValues,
                    fallbackDisplay = value,
                    onItemSelected = { index ->
                        val newValues = if (values.contains(index)) {
                            values.filter { it != index }
                        } else {
                            values + index
                        }
                        envVars.put(
                            identifier,
                            newValues.joinToString(",") { envVarInfo.possibleValues[it] },
                        )
                        onEnvVarsChange(envVars)
                    },
                    action = envVarAction?.let {
                        { envVarAction(identifier) }
                    },
                )
            }
            EnvVarSelectionType.SUGGESTIONS -> {
                // Local state preserves exactly what the user typed (including spaces),
                // while envVars stores a space-stripped version so the space-delimited
                // serialization format isn't corrupted. Commit happens on EVERY keystroke,
                // so no typed data can be lost when the user taps Save.
                var localValue by remember(identifier) { mutableStateOf(value) }
                // Sync local state if the stored value changes externally (e.g. suggestion click,
                // or first composition). Only reset when stored value diverges from the cleaned
                // local value — this prevents wiping user's in-progress typing.
                LaunchedEffect(value) {
                    if (value != localValue.replace(" ", "")) {
                        localValue = value
                    }
                }
                SettingsTextFieldWithSuggestions(
                    colors = colors,
                    enabled = enabled,
                    title = { Text(identifier) },
                    value = localValue,
                    suggestions = envVarInfo?.possibleValues ?: emptyList(),
                    onValueChange = { newText ->
                        localValue = newText
                        envVars.put(identifier, newText.replace(" ", ""))
                        onEnvVarsChange(envVars)
                    },
                    action = envVarAction?.let {
                        { envVarAction(identifier) }
                    },
                )
            }
            EnvVarSelectionType.NONE -> {
                if (envVarInfo?.possibleValues?.isNotEmpty() == true) {
                    SettingsListDropdown(
                        colors = colors,
                        enabled = enabled,
                        title = { Text(identifier) },
                        value = envVarInfo.possibleValues.indexOf(value),
                        items = envVarInfo.possibleValues,
                        fallbackDisplay = value,
                        onItemSelected = {
                            envVars.put(identifier, envVarInfo.possibleValues[it])
                            onEnvVarsChange(envVars)
                        },
                        action = envVarAction?.let {
                            { envVarAction(identifier) }
                        },
                    )
                } else {
                    var localValue by remember(identifier) { mutableStateOf(value) }
                    LaunchedEffect(value) {
                        if (value != localValue.replace(" ", "")) {
                            localValue = value
                        }
                    }
                    SettingsTextField(
                        colors = colors,
                        enabled = enabled,
                        title = { Text(identifier) },
                        value = localValue,
                        onValueChange = { newText ->
                            localValue = newText
                            envVars.put(identifier, newText.replace(" ", ""))
                            onEnvVarsChange(envVars)
                        },
                        action = envVarAction?.let {
                            { envVarAction(identifier) }
                        },
                    )
                }
            }
        }
    }
}
