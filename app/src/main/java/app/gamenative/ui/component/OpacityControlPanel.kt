package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.R
import kotlin.math.roundToInt

/**
 * Shows a simple opacity control dialog when [showDialog] is true.
 * The slider value is persisted via [PrefManager.controlsOpacity].
 */
@Composable
fun OpacityControlPanel(
    showDialog: Boolean,
    onDismiss: () -> Unit
) {
    if (!showDialog) return

    val sliderValue = remember { mutableStateOf(PrefManager.controlsOpacity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.controls_opacity)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Slider(
                    value = sliderValue.value,
                    onValueChange = { newValue ->
                        sliderValue.value = newValue
                        PluviaApp.inputControlsView?.let { icView ->
                            icView.setOverlayOpacity(newValue)
                            icView.invalidate()
                        }
                    },
                    onValueChangeFinished = {
                        PrefManager.controlsOpacity = sliderValue.value
                    },
                    valueRange = 0f..1f,
                    steps = 20,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    )
                )
                Text(
                    text = "${(sliderValue.value * 100).roundToInt()}%",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
