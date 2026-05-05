package app.gamenative.ui.component.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                SettingsTextFieldWithSuggestions(
                    colors = colors,
                    enabled = enabled,
                    title = { Text(identifier) },
                    value = value,
                    suggestions = envVarInfo?.possibleValues ?: emptyList(),
                    onValueChange = {
                        envVars.put(identifier, it)
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
                    SettingsTextField(
                        colors = colors,
                        enabled = enabled,
                        title = { Text(identifier) },
                        value = value,
                        onValueChange = {
                            envVars.put(identifier, it)
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
