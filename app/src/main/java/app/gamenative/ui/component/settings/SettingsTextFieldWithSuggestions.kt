package app.gamenative.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.base.internal.SettingsTileScaffold

/**
 * A text field that also offers a dropdown of preset suggestions.
 * The user can pick a preset to populate the field, or type freely.
 * Title sits on top, text field + suggestions button sit underneath.
 */
@Composable
fun SettingsTextFieldWithSuggestions(
    value: String,
    suggestions: List<String>,
    title: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    onValueChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var suggestionsExpanded by remember { mutableStateOf(false) }

    SettingsTileScaffold(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = { focusRequester.requestFocus() })
            .then(modifier),
        enabled = enabled,
        title = title,
        subtitle = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .weight(1f),
                    enabled = enabled,
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                )
                IconButton(
                    enabled = enabled,
                    onClick = { suggestionsExpanded = true },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ViewList,
                        contentDescription = "Presets",
                    )
                    DropdownMenu(
                        expanded = suggestionsExpanded,
                        onDismissRequest = { suggestionsExpanded = false },
                    ) {
                        suggestions.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion) },
                                onClick = {
                                    onValueChange(suggestion)
                                    suggestionsExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        action?.invoke()
    }
}
