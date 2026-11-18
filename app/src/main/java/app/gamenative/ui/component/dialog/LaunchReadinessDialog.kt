package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedAlertDialog

@Composable
fun LaunchReadinessDialog(
    isLoading: Boolean,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val messageScroll = rememberScrollState()
    ThemedAlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        modifier = Modifier.dialogScrollKeys(messageScroll),
        title = { Text(text = title) },
        text = {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), color = PluviaTheme.progressColor)
            } else {
                Text(text = message, modifier = Modifier.verticalScroll(messageScroll))
            }
        },
        confirmButton = {
            RingTextButton(onClick = onConfirm, enabled = !isLoading) { Text(text = confirmText) }
        },
        dismissButton = {
            RingTextButton(onClick = onDismiss, enabled = !isLoading) { Text(text = dismissText) }
        },
        containerColor = PluviaTheme.dialogContainerColor,
        titleContentColor = PluviaTheme.dialogTitleColor,
        textContentColor = PluviaTheme.dialogTextColor,
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LaunchReadinessDialog() {
    PluviaTheme {
        LaunchReadinessDialog(
            isLoading = false,
            title = "Title",
            message = "Message body goes here.",
            confirmText = "Confirm",
            dismissText = "Not now",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
