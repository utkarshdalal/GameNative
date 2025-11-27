package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@Composable
fun OrientationDialog(
    openDialog: Boolean,
    onDismiss: () -> Unit,
) {
    if (!openDialog) {
        return
    }

    var currentSettings by remember {
        mutableStateOf(PrefManager.allowedOrientation.toList())
    }

    // Save on close.
    val onClose: () -> Unit = {
        PrefManager.allowedOrientation = EnumSet.copyOf(currentSettings)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = {
            // Block dismissal unless there is one valid setting checked.
            if (currentSettings.isNotEmpty()) {
                onClose()
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.ScreenRotation,
                contentDescription = null,
            )
        },
        title = {
            Text(text = stringResource(R.string.allowed_orientations))
        },
        text = {
            Column {
                Orientation.entries.dropLast(1).forEach { orientation ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = currentSettings.contains(orientation),
                            onCheckedChange = { enable ->
                                currentSettings = if (enable) {
                                    currentSettings + orientation
                                } else {
                                    currentSettings - orientation
                                }
                            },
                        )
                        Text(text = orientation.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = currentSettings.isNotEmpty()) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ProfileDialog() {
    val content = LocalContext.current
    PrefManager.init(content)
    PluviaTheme {
        OrientationDialog(
            openDialog = true,
            onDismiss = {},
        )
    }
}
