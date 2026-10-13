package app.gamenative.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.inputcontrols.ControlLayoutChoice

@Composable
fun ControlLayoutChoiceSection(
    selected: ControlLayoutChoice,
    onSelected: (ControlLayoutChoice) -> Unit,
    modifier: Modifier = Modifier,
    titleRes: Int = R.string.control_layout_install_title,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.control_layout_install_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(12.dp))

        ControlLayoutChoice.entries.forEach { choice ->
            LayoutOptionCard(
                selected = selected == choice,
                title = stringResource(titleResFor(choice)),
                subtitle = stringResource(subtitleResFor(choice)),
                onClick = { onSelected(choice) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LayoutOptionCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

private fun titleResFor(choice: ControlLayoutChoice): Int = when (choice) {
    ControlLayoutChoice.AUTO -> R.string.control_layout_auto_title
    ControlLayoutChoice.KEYBOARD_MOUSE -> R.string.control_layout_kbm_title
    ControlLayoutChoice.FPS -> R.string.control_layout_fps_title
    ControlLayoutChoice.RTS -> R.string.control_layout_rts_title
    ControlLayoutChoice.GAMEPAD -> R.string.control_layout_gamepad_title
}

private fun subtitleResFor(choice: ControlLayoutChoice): Int = when (choice) {
    ControlLayoutChoice.AUTO -> R.string.control_layout_auto_subtitle
    ControlLayoutChoice.KEYBOARD_MOUSE -> R.string.control_layout_kbm_subtitle
    ControlLayoutChoice.FPS -> R.string.control_layout_fps_subtitle
    ControlLayoutChoice.RTS -> R.string.control_layout_rts_subtitle
    ControlLayoutChoice.GAMEPAD -> R.string.control_layout_gamepad_subtitle
}
