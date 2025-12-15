package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.theme.ThemeManager
import app.gamenative.theme.ThemeManager.Source
import app.gamenative.ui.theme.PluviaTheme

/**
 * A simple picker dialog that lists built-in and user themes. Dev builds expose a Reload button.
 */
@Composable
fun ThemePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // Ensure ThemeManager is initialized when dialog opens (idempotent)
    LaunchedEffect(Unit) {
        try { ThemeManager.init(context) } catch (_: Throwable) { }
    }

    val themes by ThemeManager.availableThemes.collectAsState()
    val selectedId by ThemeManager.selectedThemeId.collectAsState(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                themes.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = entry.id == selectedId, onClick = {
                            ThemeManager.selectTheme(entry.id)
                        })
                        Column(Modifier.weight(1f)) {
                            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                            val src = when (entry.source) {
                                Source.BuiltIn -> stringResource(R.string.settings_theme_source_builtin)
                                Source.User -> stringResource(R.string.settings_theme_source_user)
                            }
                            Text(text = src, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ThemePickerDialog() {
    PluviaTheme {
        ThemePickerDialog(onDismiss = { })
    }
}
