package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.Box64PresetsDialog
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.FEXCorePresetsDialog
import app.gamenative.ui.component.dialog.OrientationDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.utils.ContainerUtils
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.delay

@Composable
fun SettingsGroupEmulation() {
    SettingsGroup(title = { Text(text = stringResource(R.string.settings_emulation_title)) }) {
        var showConfigDialog by rememberSaveable { mutableStateOf(false) }
        var showOrientationDialog by rememberSaveable { mutableStateOf(false) }
        var showBox64PresetsDialog by rememberSaveable { mutableStateOf(false) }

        OrientationDialog(
            openDialog = showOrientationDialog,
            onDismiss = { showOrientationDialog = false },
        )

        ContainerConfigDialog(
            visible = showConfigDialog,
            title = stringResource(R.string.settings_emulation_default_config_dialog_title),
            default = true,
            initialConfig = ContainerUtils.getDefaultContainerData(),
            onDismissRequest = { showConfigDialog = false },
            onSave = {
                showConfigDialog = false
                ContainerUtils.setDefaultContainerData(it)
            },
        )

        Box64PresetsDialog(
            visible = showBox64PresetsDialog,
            onDismissRequest = { showBox64PresetsDialog = false },
        )
        var showFexcorePresetsDialog by rememberSaveable { mutableStateOf(false) }
        if (showFexcorePresetsDialog) {
            FEXCorePresetsDialog(
                visible = showFexcorePresetsDialog,
                onDismissRequest = { showFexcorePresetsDialog = false },
            )
        }

        var showDriverManager by rememberSaveable { mutableStateOf(false) }
        if (showDriverManager) {
            // Lazy-load dialog composable to avoid cyclic imports
            app.gamenative.ui.screen.settings.DriverManagerDialog(open = showDriverManager, onDismiss = { showDriverManager = false })
        }

        var showContentsManager by rememberSaveable { mutableStateOf(false) }
        if (showContentsManager) {
            app.gamenative.ui.screen.settings.ContentsManagerDialog(open = showContentsManager, onDismiss = { showContentsManager = false })
        }

        var showWineProtonManager by rememberSaveable { mutableStateOf(false) }
        if (showWineProtonManager) {
            app.gamenative.ui.screen.settings.WineProtonManagerDialog(open = showWineProtonManager, onDismiss = { showWineProtonManager = false })
        }

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_orientations_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_orientations_subtitle)) },
            onClick = { showOrientationDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_default_config_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_default_config_subtitle)) },
            onClick = { showConfigDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_box64_presets_title)) },
            subtitle = { Text(stringResource(R.string.settings_emulation_box64_presets_subtitle)) },
            onClick = { showBox64PresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.fexcore_presets)) },
            subtitle = { Text(text = stringResource(R.string.fexcore_presets_description)) },
            onClick = { showFexcorePresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_driver_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_driver_manager_subtitle)) },
            onClick = { showDriverManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_contents_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_contents_manager_subtitle)) },
            onClick = { showContentsManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_subtitle)) },
            onClick = { showWineProtonManager = true },
        )
        
        // Vibration Intensity Setting
        val vibrationIntensity = PrefManager.vibrationIntensity
        var showVibrationSliderDialog by rememberSaveable { mutableStateOf(false) }
        
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.vibration_intensity_title)) },
            subtitle = { Text(text = "${vibrationIntensity.toInt()}%") },
            onClick = { showVibrationSliderDialog = true },
        )
        
        if (showVibrationSliderDialog) {
            VibrationIntensityDialog(
                initialValue = vibrationIntensity,
                onConfirm = { newValue ->
                    PrefManager.vibrationIntensity = newValue
                    showVibrationSliderDialog = false
                },
                onDismiss = { showVibrationSliderDialog = false }
            )
        }
    }
}

@Composable
fun VibrationIntensityDialog(
    initialValue: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var sliderValue by rememberSaveable { mutableStateOf(initialValue) }
    
    // Add effect to provide haptic feedback when slider value changes
    LaunchedEffect(sliderValue) {
        // Small delay to avoid vibration during initial load
        delay(100)
        // Only vibrate if the slider is being actively changed (not on initial load)
        // We'll use a simple approach: vibrate with the current intensity setting
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            // Calculate vibration amplitude based on the current slider position (0-255 range)
            val amplitude = (sliderValue / 200f * 255f).toInt()
            if (sliderValue > 0) {
                // Only vibrate if intensity is above 0%
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, amplitude.coerceIn(1, 255))) // Use minimum amplitude of 1
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100) // For older versions, just use duration
                }
            } else {
                // If intensity is at 0%, cancel any ongoing vibration
                vibrator.cancel()
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.vibration_intensity_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.vibration_intensity_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${sliderValue.toInt()}%",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.vibration_intensity_range),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..200f,
                    steps = 199, // For 1% increments
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sliderValue) }) {
                Text(text = stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}
