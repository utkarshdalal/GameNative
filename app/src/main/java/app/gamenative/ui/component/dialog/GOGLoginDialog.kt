package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme

/**
 * GOG Login Dialog – in-app WebView only (automatic code capture).
 * No browser or manual paste flow.
 */
@Composable
fun GOGLoginDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onLaunchInAppLogin: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(imageVector = Icons.Default.Login, contentDescription = null) },
        title = { Text(stringResource(R.string.gog_login_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.gog_login_auto_auth_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onLaunchInAppLogin,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (isLandscape) PaddingValues(8.dp) else ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        imageVector = Icons.Default.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gog_login_in_app_button))
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.gog_login_cancel))
            }
        }
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_GOGLoginDialog() {
    PluviaTheme {
        GOGLoginDialog(
            visible = true,
            onDismissRequest = {},
            onLaunchInAppLogin = {},
            isLoading = false,
            errorMessage = null
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_GOGLoginDialogWithError() {
    PluviaTheme {
        GOGLoginDialog(
            visible = true,
            onDismissRequest = {},
            onLaunchInAppLogin = {},
            isLoading = false,
            errorMessage = "Invalid authorization code. Please try again."
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_GOGLoginDialogLoading() {
    PluviaTheme {
        GOGLoginDialog(
            visible = true,
            onDismissRequest = {},
            onLaunchInAppLogin = {},
            isLoading = true,
            errorMessage = null
        )
    }
}
