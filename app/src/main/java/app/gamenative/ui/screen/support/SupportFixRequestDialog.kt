package app.gamenative.ui.screen.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = stringResource(R.string.support_fix_request_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
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
                val confirmFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.cancel),
                        enabled = true,
                        onClick = onDismiss,
                    )
                    FocusableButton(
                        text = stringResource(R.string.support_fix_request_run),
                        onClick = onRun,
                        enabled = !busy && error == null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .focusRequester(confirmFocus),
                    )
                }
            }
        }
    }
}
