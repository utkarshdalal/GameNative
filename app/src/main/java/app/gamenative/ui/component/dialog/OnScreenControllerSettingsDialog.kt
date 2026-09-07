package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaBackground
import com.winlator.inputcontrols.ControlsProfile
import java.util.Locale

internal val DEFAULT_MOUSE_SPEED = ControlsProfile.DEFAULT_CURSOR_SPEED
internal const val MIN_MOUSE_SPEED = 0.1f
internal const val MAX_MOUSE_SPEED = 3.0f

internal fun mouseSpeedOrDefault(value: Float): Float {
    return value.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_MOUSE_SPEED
}

internal fun mouseSpeedForSlider(value: Float): Float {
    return mouseSpeedOrDefault(value).coerceIn(MIN_MOUSE_SPEED, MAX_MOUSE_SPEED)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnScreenControllerSettingsDialog(
    initialCursorSpeed: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    var cursorSpeed by remember(initialCursorSpeed) {
        mutableFloatStateOf(mouseSpeedForSlider(initialCursorSpeed))
    }
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
                            text = stringResource(R.string.on_screen_controller_settings),
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
                        IconButton(onClick = { cursorSpeed = DEFAULT_MOUSE_SPEED }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_mouse_speed),
                            )
                        }
                        IconButton(onClick = { onSave(cursorSpeed) }) {
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
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsDialogSectionHeader(stringResource(R.string.mouse))

                SettingsSliderBlock(
                    title = stringResource(R.string.mouse_speed),
                    subtitle = stringResource(R.string.mouse_speed_subtitle),
                    value = cursorSpeed,
                    valueRange = MIN_MOUSE_SPEED..MAX_MOUSE_SPEED,
                    valueText = String.format(locale, "%.1fx", cursorSpeed),
                    onValueChange = { cursorSpeed = it },
                )
            }
        }
    }
}
