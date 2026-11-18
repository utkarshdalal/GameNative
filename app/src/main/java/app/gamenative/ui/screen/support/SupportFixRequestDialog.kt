package app.gamenative.ui.screen.support

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.api.SupportApi
import app.gamenative.ui.theme.PluviaTheme

@Composable
internal fun SupportFixRequestDialog(
    fixes: SupportApi.Fixes?,
    busy: Boolean,
    error: String?,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    SupportConfirmDialog(
        title = stringResource(R.string.support_fix_request_title),
        confirmText = stringResource(R.string.support_fix_request_run),
        onConfirm = onRun,
        onDismiss = onDismiss,
        confirmEnabled = !busy && error == null,
    ) {
        Text(
            text = stringResource(R.string.support_fix_request_message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (fixes != null && fixes.limit > 0) {
            Text(
                text = if (fixes.resetsAt != null) {
                    stringResource(R.string.support_fix_request_left_resets, fixes.left, fixes.limit, fixResetDate(fixes.resetsAt))
                } else {
                    stringResource(R.string.support_fix_request_left, fixes.left, fixes.limit)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = PluviaTheme.colors.accentWarning,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}
