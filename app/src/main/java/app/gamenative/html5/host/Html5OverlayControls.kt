package app.gamenative.html5.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.ui.component.dialog.PhysicalControllerConfigSection
import com.winlator.inputcontrols.ControlsProfile

// Wine-parity overlay-controls dialog. Mirrors the structure
// XServerScreen offers via input_controls -- small AlertDialog with opacity slider + visible
// switch. local state for live preview; commits to container JSON only on Done.
@Composable
internal fun OverlayControlsDialog(
    initialOpacity: Float,
    initialVisible: Boolean,
    onLiveOpacity: (Float) -> Unit,
    onLiveVisible: (Boolean) -> Unit,
    onDone: (opacity: Float, visible: Boolean) -> Unit,
) {
    var opacity by remember { mutableStateOf(initialOpacity) }
    var visible by remember { mutableStateOf(initialVisible) }
    AlertDialog(
        onDismissRequest = { onDone(opacity, visible) },
        title = { Text(stringResource(R.string.input_controls)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.html5_controller_tab_overlay_visible))
                    Switch(
                        checked = visible,
                        onCheckedChange = { v ->
                            visible = v
                            onLiveVisible(v)
                        },
                    )
                }
                val pct = (opacity * 100f).toInt()
                Text(stringResource(R.string.html5_controller_tab_overlay_opacity_value, pct))
                Slider(
                    value = opacity,
                    onValueChange = { v ->
                        opacity = v
                        onLiveOpacity(v)
                    },
                    valueRange = 0f..1f,
                    steps = 19, // 5% granularity, mirrors prior slider config
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(opacity, visible) }) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

// HTML5-local edit-overlay toolbar. mirrors Wine's EditModeToolbar
// (XServerScreen.kt) -- but kept LOCAL so the Wine private composable stays untouched
// (Wine bytecode bytewise-identical). minimum useful set: Add / Edit / Delete / Done.
// Duplicate-from-profile + Save-vs-Cancel are deferred until full toolbar parity;
// Done acts as Save (writes profile JSON) for now.
@Composable
internal fun Html5EditOverlayToolbar(
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAdd) {
                Text(text = stringResource(R.string.add), color = Color.White)
            }
            TextButton(onClick = onEdit) {
                Text(text = stringResource(R.string.edit), color = Color.White)
            }
            TextButton(onClick = onDelete) {
                Text(text = stringResource(R.string.delete), color = Color.White)
            }
            TextButton(onClick = onDone) {
                Text(text = stringResource(R.string.done), color = Color.White)
            }
        }
    }
}

@Composable
internal fun PhysicalControllerDialog(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f)),
        ) {
            PhysicalControllerConfigSection(
                profile = profile,
                onDismiss = onDismiss,
                onSave = onSave,
            )
        }
    }
}
