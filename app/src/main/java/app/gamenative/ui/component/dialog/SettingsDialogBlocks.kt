package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.PluviaBorder
import app.gamenative.ui.theme.PluviaSurface
import app.gamenative.ui.theme.PluviaSurfaceElevated
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.inputcontrols.Binding

@Composable
fun SettingsDialogSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun GestureRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GestureBlock {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(title) },
            subtitle = {
                Text(
                    text = subtitle,
                    color = if (!enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        Color.Unspecified
                    },
                )
            },
            state = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (enabled && expandedContent != null) {
            GestureSubSettings { expandedContent() }
        }
    }
}

@Composable
fun GestureBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = PluviaBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            content()
        }
    }
}

@Composable
fun GestureSubSettings(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = (-6).dp)
            .padding(start = 12.dp, end = 10.dp, bottom = 4.dp),
    ) {
        content()
    }
}

@Composable
fun DelayTextField(
    label: String,
    value: Int,
    valueRange: IntRange = 0..10000,
    onValueChange: (Int) -> Unit,
) {
    val clampedValue = value.coerceIn(valueRange)
    LaunchedEffect(clampedValue, value) {
        if (value != clampedValue) onValueChange(clampedValue)
    }

    NoExtractOutlinedTextField(
        value = clampedValue.toString(),
        onValueChange = { newText ->
            val filtered = newText.filter { it.isDigit() }
            val nextValue = when {
                filtered.isEmpty() -> valueRange.first
                else -> filtered.toLongOrNull()
                    ?.coerceIn(valueRange.first.toLong(), valueRange.last.toLong())
                    ?.toInt()
                    ?: valueRange.last
            }
            onValueChange(nextValue)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 0.dp),
    )
}

data class SettingsActionCategory(
    val header: String,
    val actions: List<Pair<String, String>>,
)

@Composable
fun CategorizedActionPicker(
    currentValue: String,
    currentLabel: String,
    rowLabel: String,
    dialogTitle: String,
    categories: List<SettingsActionCategory>,
    onValueSelected: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(10.dp),
        color = PluviaSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(rowLabel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(currentLabel, color = MaterialTheme.colorScheme.primary)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = PluviaBackground,
            title = { Text(dialogTitle) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    categories.forEach { category ->
                        item {
                            Text(
                                text = category.header,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(category.actions) { (actionKey, actionLabel) ->
                            val isSelected = actionKey == currentValue
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueSelected(actionKey)
                                        showDialog = false
                                    },
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    PluviaSurface
                                },
                            ) {
                                Text(
                                    text = actionLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun BindingActionPicker(
    rowLabel: String,
    currentBinding: String,
    includeKeyboard: Boolean,
    includeGamepad: Boolean,
    allowedBindings: Set<String>? = null,
    onBindingSelected: (String) -> Unit,
) {
    val binding = Binding.fromString(currentBinding)
    CategorizedActionPicker(
        currentValue = currentBinding,
        currentLabel = if (binding != Binding.NONE) binding.toString() else currentBinding,
        rowLabel = rowLabel,
        dialogTitle = rowLabel,
        categories = buildBindingActionCategories(
            includeKeyboard = includeKeyboard,
            includeGamepad = includeGamepad,
            allowedBindings = allowedBindings,
        ),
        onValueSelected = onBindingSelected,
    )
}

@Composable
private fun buildBindingActionCategories(
    includeKeyboard: Boolean,
    includeGamepad: Boolean,
    allowedBindings: Set<String>?,
): List<SettingsActionCategory> {
    val categories = mutableListOf<SettingsActionCategory>()

    if (includeGamepad) {
        categories += SettingsActionCategory(
            header = stringResource(R.string.gesture_header_gamepad),
            actions = bindingActionsOf(
                allowedBindings,
                Binding.GAMEPAD_BUTTON_A,
                Binding.GAMEPAD_BUTTON_B,
                Binding.GAMEPAD_BUTTON_X,
                Binding.GAMEPAD_BUTTON_Y,
                Binding.GAMEPAD_BUTTON_L1,
                Binding.GAMEPAD_BUTTON_R1,
                Binding.GAMEPAD_BUTTON_L2,
                Binding.GAMEPAD_BUTTON_R2,
                Binding.GAMEPAD_BUTTON_L3,
                Binding.GAMEPAD_BUTTON_R3,
                Binding.GAMEPAD_BUTTON_SELECT,
                Binding.GAMEPAD_BUTTON_START,
                Binding.GAMEPAD_DPAD_UP,
                Binding.GAMEPAD_DPAD_RIGHT,
                Binding.GAMEPAD_DPAD_DOWN,
                Binding.GAMEPAD_DPAD_LEFT,
            ),
        )
    }

    if (includeKeyboard) {
        categories += listOf(
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_common_game),
                actions = bindingActionsOf(
                    allowedBindings,
                    Binding.KEY_ESC,
                    Binding.KEY_SPACE,
                    Binding.KEY_E,
                    Binding.KEY_Q,
                    Binding.KEY_F,
                    Binding.KEY_TAB,
                    Binding.KEY_ENTER,
                    Binding.KEY_I,
                    Binding.KEY_M,
                    Binding.KEY_R,
                ),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_navigation_editing),
                actions = bindingActionsOf(
                    allowedBindings,
                    Binding.KEY_UP,
                    Binding.KEY_RIGHT,
                    Binding.KEY_DOWN,
                    Binding.KEY_LEFT,
                    Binding.KEY_BKSP,
                    Binding.KEY_DEL,
                    Binding.KEY_INSERT,
                    Binding.KEY_HOME,
                    Binding.KEY_END,
                    Binding.KEY_PG_UP,
                    Binding.KEY_PG_DOWN,
                    Binding.KEY_PRTSCN,
                ),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_symbols),
                actions = bindingActionsOf(
                    allowedBindings,
                    Binding.KEY_BRACKET_LEFT,
                    Binding.KEY_BRACKET_RIGHT,
                    Binding.KEY_BACKSLASH,
                    Binding.KEY_SLASH,
                    Binding.KEY_SEMICOLON,
                    Binding.KEY_COMMA,
                    Binding.KEY_PERIOD,
                    Binding.KEY_APOSTROPHE,
                    Binding.KEY_GRAVE,
                    Binding.KEY_TILDE,
                    Binding.KEY_MINUS,
                    Binding.KEY_EQUAL,
                ),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_letters),
                actions = ('A'..'Z')
                    .map { "KEY_$it" to it.toString() }
                    .filterAllowed(allowedBindings),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_numbers),
                actions = (0..9)
                    .map { "KEY_$it" to it.toString() }
                    .filterAllowed(allowedBindings),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_numpad),
                actions = bindingActionsOf(
                    allowedBindings,
                    Binding.KEY_KP_DIVIDE,
                    Binding.KEY_KP_MULTIPLY,
                    Binding.KEY_KP_SUBTRACT,
                    Binding.KEY_KP_ADD,
                    Binding.KEY_KP_DEL,
                    Binding.KEY_KP_0,
                    Binding.KEY_KP_1,
                    Binding.KEY_KP_2,
                    Binding.KEY_KP_3,
                    Binding.KEY_KP_4,
                    Binding.KEY_KP_5,
                    Binding.KEY_KP_6,
                    Binding.KEY_KP_7,
                    Binding.KEY_KP_8,
                    Binding.KEY_KP_9,
                ),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_function_keys),
                actions = (1..12)
                    .map { "KEY_F$it" to "F$it" }
                    .filterAllowed(allowedBindings),
            ),
            SettingsActionCategory(
                header = stringResource(R.string.gesture_header_modifiers_locks),
                actions = bindingActionsOf(
                    allowedBindings,
                    Binding.KEY_SHIFT_L,
                    Binding.KEY_SHIFT_R,
                    Binding.KEY_CTRL_L,
                    Binding.KEY_CTRL_R,
                    Binding.KEY_ALT_L,
                    Binding.KEY_ALT_R,
                    Binding.KEY_CAPS_LOCK,
                    Binding.KEY_NUM_LOCK,
                ),
            ),
        )
    }

    return categories.filter { it.actions.isNotEmpty() }
}

private fun bindingActionsOf(
    allowedBindings: Set<String>?,
    vararg bindings: Binding,
): List<Pair<String, String>> {
    return bindings
        .map { it.name to it.toString() }
        .filterAllowed(allowedBindings)
}

private fun List<Pair<String, String>>.filterAllowed(allowedBindings: Set<String>?): List<Pair<String, String>> {
    return if (allowedBindings == null) this else filter { (binding, _) -> binding in allowedBindings }
}
