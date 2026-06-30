package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.ShooterModeConfig
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsSwitch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShooterModeSettingsDialog(
    shooterConfig: ShooterModeConfig,
    defaultJoystickOpacity: Float,
    onDismiss: () -> Unit,
    onSave: (ShooterModeConfig) -> Unit,
) {
    var config by remember(shooterConfig) { mutableStateOf(shooterConfig) }
    val locale = Locale.getDefault()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = PluviaBackground,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.shooter_mode_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { config = ShooterModeConfig() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_shooter_settings),
                            )
                        }
                        IconButton(onClick = { onSave(config) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(bottom = 16.dp),
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                        val showMouseLookOptions = config.lookType == ShooterModeConfig.LOOK_MOUSE
                        val showRightStickOptions = config.lookType == ShooterModeConfig.LOOK_GAMEPAD_RIGHT_STICK
                        val showAnalogMovementOptions =
                            config.movementType == ShooterModeConfig.MOVEMENT_GAMEPAD_LEFT_STICK

                        SettingsDialogSectionHeader(stringResource(R.string.shooter_section_input))

                        ShooterDropdownBlock(
                            title = stringResource(R.string.movement_type),
                            subtitle = stringResource(R.string.movement_type_subtitle),
                            value = config.movementType,
                            values = ShooterModeConfig.MOVEMENT_TYPES,
                            labels = movementTypeLabels(),
                            onValueChange = { config = config.copy(movementType = it) },
                        )

                        ShooterDropdownBlock(
                            title = stringResource(R.string.look_type),
                            subtitle = stringResource(R.string.look_type_subtitle),
                            value = config.lookType,
                            values = ShooterModeConfig.LOOK_TYPES,
                            labels = lookTypeLabels(),
                            onValueChange = { config = config.copy(lookType = it) },
                        )

                        SettingsDialogSectionHeader(stringResource(R.string.shooter_section_runtime))

                        SliderSettingBlock(
                            title = stringResource(R.string.shooter_movement_zone_width),
                            subtitle = stringResource(R.string.shooter_movement_zone_width_subtitle),
                            value = config.movementZoneSplit,
                            valueRange = 0.10f..0.90f,
                            valueText = percentText(config.movementZoneSplit, locale),
                            onValueChange = { config = config.copy(movementZoneSplit = it) },
                        )

                        GestureBlock {
                            SettingsSwitch(
                                colors = settingsTileColorsAlt(),
                                title = { Text(stringResource(R.string.show_shooter_runtime_toggle)) },
                                subtitle = { Text(stringResource(R.string.show_shooter_runtime_toggle_subtitle)) },
                                state = config.showRuntimeToggle,
                                onCheckedChange = { config = config.copy(showRuntimeToggle = it) },
                            )
                        }

                        GestureRow(
                            title = stringResource(R.string.button_drag_look),
                            subtitle = stringResource(R.string.button_drag_look_subtitle),
                            enabled = config.buttonLookThroughEnabled,
                            onEnabledChange = { config = config.copy(buttonLookThroughEnabled = it) },
                        ) {
                            DelayTextField(
                                label = stringResource(R.string.button_drag_threshold),
                                value = config.buttonLookThroughDragThreshold,
                                valueRange = 0..64,
                                onValueChange = { config = config.copy(buttonLookThroughDragThreshold = it) },
                            )
                        }

                        if (showMouseLookOptions) {
                            SettingsDialogSectionHeader(stringResource(R.string.shooter_section_mouse_look))

                            SliderSettingBlock(
                                title = stringResource(R.string.look_sensitivity_x),
                                subtitle = stringResource(R.string.look_sensitivity_x_subtitle),
                                value = config.lookSensitivityX,
                                valueRange = 0.1f..10.0f,
                                valueText = multiplierText(config.lookSensitivityX, locale),
                                onValueChange = { config = config.copy(lookSensitivityX = it) },
                            )

                            SliderSettingBlock(
                                title = stringResource(R.string.look_sensitivity_y),
                                subtitle = stringResource(R.string.look_sensitivity_y_subtitle),
                                value = config.lookSensitivityY,
                                valueRange = 0.1f..10.0f,
                                valueText = multiplierText(config.lookSensitivityY, locale),
                                onValueChange = { config = config.copy(lookSensitivityY = it) },
                            )

                            GestureBlock {
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(stringResource(R.string.invert_look_y)) },
                                    subtitle = { Text(stringResource(R.string.invert_look_y_subtitle)) },
                                    state = config.invertLookY,
                                    onCheckedChange = { config = config.copy(invertLookY = it) },
                                )
                            }

                            SliderSettingBlock(
                                title = stringResource(R.string.look_smoothing),
                                subtitle = stringResource(R.string.look_smoothing_subtitle),
                                value = config.lookSmoothing,
                                valueRange = 0.0f..0.95f,
                                valueText = percentText(config.lookSmoothing, locale),
                                onValueChange = { config = config.copy(lookSmoothing = it) },
                            )

                            SliderSettingBlock(
                                title = stringResource(R.string.look_deadzone),
                                subtitle = stringResource(R.string.look_deadzone_subtitle),
                                value = config.lookDeadzone,
                                valueRange = 0.0f..8.0f,
                                valueText = pixelText(config.lookDeadzone, locale),
                                onValueChange = { config = config.copy(lookDeadzone = it) },
                            )

                            GestureRow(
                                title = stringResource(R.string.mouse_look_acceleration),
                                subtitle = stringResource(R.string.mouse_look_acceleration_subtitle),
                                enabled = config.mouseAccelerationEnabled,
                                onEnabledChange = { config = config.copy(mouseAccelerationEnabled = it) },
                            ) {
                                SliderSettingBlock(
                                    title = stringResource(R.string.mouse_acceleration_strength),
                                    subtitle = stringResource(R.string.mouse_acceleration_strength_subtitle),
                                    value = config.mouseAccelerationStrength,
                                    valueRange = 0.1f..5.0f,
                                    valueText = multiplierText(config.mouseAccelerationStrength, locale),
                                    onValueChange = { config = config.copy(mouseAccelerationStrength = it) },
                                    compact = true,
                                )
                                SliderSettingBlock(
                                    title = stringResource(R.string.mouse_acceleration_max),
                                    subtitle = stringResource(R.string.mouse_acceleration_max_subtitle),
                                    value = config.mouseAccelerationMaxMultiplier,
                                    valueRange = 1.0f..8.0f,
                                    valueText = multiplierText(config.mouseAccelerationMaxMultiplier, locale),
                                    onValueChange = { config = config.copy(mouseAccelerationMaxMultiplier = it) },
                                    compact = true,
                                )
                            }
                        }

                        SettingsDialogSectionHeader(stringResource(R.string.shooter_section_joysticks))

                        SliderSettingBlock(
                            title = stringResource(R.string.joystick_opacity),
                            subtitle = stringResource(R.string.joystick_opacity_subtitle),
                            value = config.resolvedJoystickOpacity(defaultJoystickOpacity),
                            valueRange = 0.01f..1.0f,
                            valueText = percentText(config.resolvedJoystickOpacity(defaultJoystickOpacity), locale),
                            onValueChange = { config = config.copy(joystickOpacity = it) },
                        )

                        SettingsDialogSectionHeader(stringResource(R.string.shooter_section_movement_joystick))

                        SliderSettingBlock(
                            title = stringResource(R.string.movement_joystick_size),
                            subtitle = stringResource(R.string.movement_joystick_size_subtitle),
                            value = config.movementJoystickSize,
                            valueRange = 0.5f..3.0f,
                            valueText = multiplierText(config.movementJoystickSize, locale),
                            onValueChange = { config = config.copy(movementJoystickSize = it) },
                        )

                        ShooterDropdownBlock(
                            title = stringResource(R.string.movement_joystick_behavior),
                            subtitle = stringResource(R.string.movement_joystick_behavior_subtitle),
                            value = config.movementJoystickBehavior,
                            values = ShooterModeConfig.JOYSTICK_BEHAVIORS,
                            labels = joystickBehaviorLabels(),
                            onValueChange = { config = config.copy(movementJoystickBehavior = it) },
                        )

                        if (showAnalogMovementOptions) {
                            SliderSettingBlock(
                                title = stringResource(R.string.movement_joystick_deadzone),
                                subtitle = stringResource(R.string.movement_joystick_deadzone_subtitle),
                                value = config.movementJoystickDeadzone,
                                valueRange = 0.0f..0.75f,
                                valueText = percentText(config.movementJoystickDeadzone, locale),
                                onValueChange = { config = config.copy(movementJoystickDeadzone = it) },
                            )

                            SliderSettingBlock(
                                title = stringResource(R.string.movement_stick_sensitivity),
                                subtitle = stringResource(R.string.movement_stick_sensitivity_subtitle),
                                value = config.movementStickSensitivity,
                                valueRange = 0.1f..5.0f,
                                valueText = multiplierText(config.movementStickSensitivity, locale),
                                onValueChange = { config = config.copy(movementStickSensitivity = it) },
                            )
                        }

                        if (showRightStickOptions) {
                            SettingsDialogSectionHeader(stringResource(R.string.shooter_section_right_joystick))

                            SliderSettingBlock(
                                title = stringResource(R.string.look_joystick_size),
                                subtitle = stringResource(R.string.look_joystick_size_subtitle),
                                value = config.lookJoystickSize,
                                valueRange = 0.5f..3.0f,
                                valueText = multiplierText(config.lookJoystickSize, locale),
                                onValueChange = { config = config.copy(lookJoystickSize = it) },
                            )

                            ShooterDropdownBlock(
                                title = stringResource(R.string.look_joystick_behavior),
                                subtitle = stringResource(R.string.look_joystick_behavior_subtitle),
                                value = config.lookJoystickBehavior,
                                values = ShooterModeConfig.JOYSTICK_BEHAVIORS,
                                labels = joystickBehaviorLabels(),
                                onValueChange = { config = config.copy(lookJoystickBehavior = it) },
                            )

                            SliderSettingBlock(
                                title = stringResource(R.string.look_joystick_deadzone),
                                subtitle = stringResource(R.string.look_joystick_deadzone_subtitle),
                                value = config.lookJoystickDeadzone,
                                valueRange = 0.0f..0.75f,
                                valueText = percentText(config.lookJoystickDeadzone, locale),
                                onValueChange = { config = config.copy(lookJoystickDeadzone = it) },
                            )

                            SliderSettingBlock(
                                title = stringResource(R.string.look_stick_sensitivity),
                                subtitle = stringResource(R.string.look_stick_sensitivity_subtitle),
                                value = config.lookStickSensitivity,
                                valueRange = 0.1f..5.0f,
                                valueText = multiplierText(config.lookStickSensitivity, locale),
                                onValueChange = { config = config.copy(lookStickSensitivity = it) },
                            )
                        }

                        SettingsDialogSectionHeader(stringResource(R.string.shooter_section_movement))

                        GestureRow(
                            title = stringResource(R.string.outer_ring_sprint),
                            subtitle = stringResource(R.string.outer_ring_sprint_subtitle),
                            enabled = config.outerRingSprintEnabled,
                            onEnabledChange = { config = config.copy(outerRingSprintEnabled = it) },
                        ) {
                            SliderSettingBlock(
                                title = stringResource(R.string.outer_ring_sprint_threshold),
                                subtitle = stringResource(R.string.outer_ring_sprint_threshold_subtitle),
                                value = config.outerRingSprintThreshold,
                                valueRange = 0.5f..1.0f,
                                valueText = percentText(config.outerRingSprintThreshold, locale),
                                onValueChange = { config = config.copy(outerRingSprintThreshold = it) },
                                compact = true,
                            )
                            SettingsSwitch(
                                colors = settingsTileColorsAlt(),
                                title = { Text(stringResource(R.string.outer_ring_sprint_press_mode)) },
                                subtitle = { Text(stringResource(R.string.outer_ring_sprint_press_mode_subtitle)) },
                                state = config.outerRingSprintPressMode,
                                onCheckedChange = { config = config.copy(outerRingSprintPressMode = it) },
                            )
                            BindingActionPicker(
                                rowLabel = stringResource(R.string.sprint_binding),
                                currentBinding = config.sprintBinding,
                                includeKeyboard = true,
                                includeGamepad = true,
                                allowedBindings = ShooterModeConfig.SPRINT_BINDINGS.toSet(),
                                onBindingSelected = { config = config.copy(sprintBinding = it) },
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ShooterDropdownBlock(
    title: String,
    subtitle: String,
    value: String,
    values: List<String>,
    labels: List<String>,
    onValueChange: (String) -> Unit,
    compact: Boolean = false,
) {
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)
    val content: @Composable () -> Unit = {
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(title) },
            subtitle = { Text(subtitle) },
            value = selectedIndex,
            items = labels,
            onItemSelected = { index -> onValueChange(values[index]) },
        )
    }

    if (compact) {
        content()
    } else {
        GestureBlock { content() }
    }
}

@Composable
private fun SliderSettingBlock(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    compact: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    if (compact) {
        content()
    } else {
        GestureBlock { content() }
    }
}

@Composable
private fun movementTypeLabels(): List<String> = listOf(
    stringResource(R.string.movement_wasd),
    stringResource(R.string.movement_arrow_keys),
    stringResource(R.string.movement_gamepad_left_stick),
)

@Composable
private fun lookTypeLabels(): List<String> = listOf(
    stringResource(R.string.look_type_mouse),
    stringResource(R.string.look_type_gamepad_right_stick),
)

@Composable
private fun joystickBehaviorLabels(): List<String> = listOf(
    stringResource(R.string.joystick_behavior_anchored),
    stringResource(R.string.joystick_behavior_floating),
)

private fun multiplierText(value: Float, locale: Locale): String {
    return String.format(locale, "%.1fx", value)
}

private fun pixelText(value: Float, locale: Locale): String {
    return String.format(locale, "%.1f px", value)
}

private fun percentText(value: Float, locale: Locale): String {
    return String.format(locale, "%d%%", (value * 100).roundToInt())
}

private fun ShooterModeConfig.resolvedJoystickOpacity(defaultJoystickOpacity: Float): Float {
    return if (joystickOpacity > 0) {
        joystickOpacity
    } else {
        defaultJoystickOpacity.coerceIn(0.01f, 1.0f)
    }
}
