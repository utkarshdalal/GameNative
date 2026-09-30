package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.GyroSettings
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsSwitch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyroSettingsDialog(
    gyroSettings: GyroSettings,
    tiltAvailable: Boolean,
    hasGyroControlAssigned: (Int) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (GyroSettings) -> Unit,
    onAssignControl: (GyroSettings) -> Unit,
) {
    var config by remember(gyroSettings) { mutableStateOf(gyroSettings) }
    val target = config.lastTarget
    val stickOutput = target != GyroSettings.MODE_MOUSE
    val tiltActive = stickOutput && config.tiltSteeringEnabled && tiltAvailable
    val maximumTiltDeadzone = minOf(
        GyroSettings.MAX_TILT_DEADZONE_DEGREES,
        config.tiltFullScaleDegrees - GyroSettings.MIN_TILT_ACTIVE_RANGE_DEGREES,
    )
    val needsGyroControl = config.activationMode != GyroSettings.ACTIVATION_ALWAYS &&
        !hasGyroControlAssigned(config.activationMode)

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
                            text = stringResource(R.string.gyro_settings_title),
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
                        IconButton(
                            onClick = {
                                config = GyroSettings(
                                    mode = config.mode,
                                    lastTarget = config.lastTarget,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_gyro_settings),
                            )
                        }
                        IconButton(onClick = { onSave(config.normalized()) }) {
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
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                SettingsDialogSectionHeader(stringResource(R.string.gyro_section_output))
                SettingsDropdownBlock(
                    title = stringResource(R.string.gyro_output),
                    subtitle = stringResource(R.string.gyro_output_subtitle),
                    value = target,
                    values = listOf(
                        GyroSettings.MODE_LEFT_STICK,
                        GyroSettings.MODE_RIGHT_STICK,
                        GyroSettings.MODE_MOUSE,
                    ),
                    labels = listOf(
                        stringResource(R.string.left_stick),
                        stringResource(R.string.right_stick),
                        stringResource(R.string.mouse),
                    ),
                    onValueChange = { nextTarget ->
                        config = config.copy(
                            lastTarget = nextTarget,
                            mode = if (config.mode == GyroSettings.MODE_DISABLED) {
                                GyroSettings.MODE_DISABLED
                            } else {
                                nextTarget
                            },
                        )
                    },
                )

                if (stickOutput) {
                    GestureBlock {
                        if (tiltAvailable) {
                            SettingsSwitch(
                                colors = settingsTileColorsAlt(),
                                title = { Text(stringResource(R.string.gyro_tilt_steering)) },
                                subtitle = { Text(stringResource(R.string.gyro_tilt_steering_subtitle)) },
                                state = config.tiltSteeringEnabled,
                                onCheckedChange = { config = config.copy(tiltSteeringEnabled = it) },
                            )
                        } else {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.gyro_tilt_steering),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.gyro_tilt_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                SettingsDialogSectionHeader(stringResource(R.string.gyro_section_activation))
                SettingsDropdownBlock(
                    title = stringResource(R.string.gyro_activation),
                    subtitle = stringResource(R.string.gyro_activation_help),
                    value = config.activationMode,
                    values = listOf(
                        GyroSettings.ACTIVATION_ALWAYS,
                        GyroSettings.ACTIVATION_HOLD,
                        GyroSettings.ACTIVATION_TOGGLE,
                        GyroSettings.ACTIVATION_RATCHET,
                    ),
                    labels = listOf(
                        stringResource(R.string.gyro_activation_always),
                        stringResource(R.string.gyro_activation_hold),
                        stringResource(R.string.gyro_activation_toggle),
                        stringResource(R.string.gyro_activation_ratchet),
                    ),
                    onValueChange = { config = config.copy(activationMode = it) },
                )

                if (needsGyroControl) {
                    GestureBlock {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = stringResource(R.string.gyro_control_not_assigned),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = { onAssignControl(config.normalized()) },
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text(stringResource(R.string.gyro_assign_in_controls_editor))
                            }
                        }
                    }
                }

                SettingsDialogSectionHeader(stringResource(R.string.gyro_section_response))
                if (tiltActive) {
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_tilt_full_scale),
                        subtitle = stringResource(R.string.gyro_tilt_full_scale_subtitle),
                        value = config.tiltFullScaleDegrees,
                        valueRange = GyroSettings.MIN_TILT_FULL_SCALE_DEGREES..
                            GyroSettings.MAX_TILT_FULL_SCALE_DEGREES,
                        steps = 49,
                        valueText = stringResource(
                            R.string.gyro_degrees_value,
                            config.tiltFullScaleDegrees.roundToInt(),
                        ),
                        onValueChange = { fullScale ->
                            config = config.copy(
                                tiltFullScaleDegrees = fullScale,
                                tiltDeadzoneDegrees = minOf(
                                    config.tiltDeadzoneDegrees,
                                    fullScale - GyroSettings.MIN_TILT_ACTIVE_RANGE_DEGREES,
                                ),
                            )
                        },
                    )
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_tilt_deadzone),
                        subtitle = stringResource(R.string.gyro_tilt_deadzone_subtitle),
                        value = config.tiltDeadzoneDegrees,
                        valueRange = 0f..maximumTiltDeadzone,
                        steps = maximumTiltDeadzone.roundToInt() - 1,
                        valueText = stringResource(
                            R.string.gyro_degrees_value,
                            config.tiltDeadzoneDegrees.roundToInt(),
                        ),
                        onValueChange = { config = config.copy(tiltDeadzoneDegrees = it) },
                    )
                } else {
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_sensitivity),
                        subtitle = stringResource(R.string.gyro_sensitivity_subtitle),
                        value = config.sensitivity,
                        valueRange = GyroSettings.MIN_SENSITIVITY..GyroSettings.MAX_SENSITIVITY,
                        steps = 77,
                        valueText = stringResource(
                            R.string.gyro_percent_value,
                            (config.sensitivity * 100f).roundToInt(),
                        ),
                        onValueChange = { config = config.copy(sensitivity = it) },
                    )
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_vertical_scale),
                        subtitle = stringResource(R.string.gyro_vertical_scale_subtitle),
                        value = config.verticalScale,
                        valueRange = GyroSettings.MIN_VERTICAL_SCALE..GyroSettings.MAX_VERTICAL_SCALE,
                        steps = 37,
                        valueText = stringResource(
                            R.string.gyro_percent_value,
                            (config.verticalScale * 100f).roundToInt(),
                        ),
                        onValueChange = { config = config.copy(verticalScale = it) },
                    )
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_steadying),
                        subtitle = stringResource(R.string.gyro_steadying_subtitle),
                        value = config.steadyingDegreesPerSecond,
                        valueRange = 0f..GyroSettings.MAX_STEADYING_DPS,
                        steps = 19,
                        valueText = stringResource(
                            R.string.gyro_degrees_per_second_value,
                            config.steadyingDegreesPerSecond,
                        ),
                        onValueChange = { config = config.copy(steadyingDegreesPerSecond = it) },
                    )
                }

                SettingsSliderBlock(
                    title = stringResource(R.string.gyro_smoothing),
                    subtitle = stringResource(R.string.gyro_smoothing_subtitle),
                    value = config.smoothingMilliseconds,
                    valueRange = 0f..GyroSettings.MAX_SMOOTHING_MS,
                    steps = 19,
                    valueText = stringResource(
                        R.string.gyro_milliseconds_value,
                        config.smoothingMilliseconds.roundToInt(),
                    ),
                    onValueChange = { config = config.copy(smoothingMilliseconds = it) },
                )

                if (stickOutput) {
                    SettingsSliderBlock(
                        title = stringResource(R.string.gyro_stick_anti_deadzone),
                        subtitle = stringResource(R.string.gyro_stick_anti_deadzone_subtitle),
                        value = config.stickAntiDeadzone,
                        valueRange = 0f..GyroSettings.MAX_STICK_ANTI_DEADZONE,
                        steps = 9,
                        valueText = stringResource(
                            R.string.gyro_percent_value,
                            (config.stickAntiDeadzone * 100f).roundToInt(),
                        ),
                        onValueChange = { config = config.copy(stickAntiDeadzone = it) },
                    )
                }

                GestureBlock {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_invert_horizontal)) },
                        state = config.invertX,
                        onCheckedChange = { config = config.copy(invertX = it) },
                    )
                    if (!tiltActive) {
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.gyro_invert_vertical)) },
                            state = config.invertY,
                            onCheckedChange = { config = config.copy(invertY = it) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
