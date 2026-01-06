package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.epic.EpicConstants
import app.gamenative.ui.theme.PluviaTheme
import android.content.Intent
import android.net.Uri
import android.widget.Toast

private fun extractCodeFromInput(input: String): String {
    val trimmed = input.trim()
    // Check if it's a URL with code parameter
    if (trimmed.startsWith("http")) {
        val codeMatch = Regex("[?&]code=([^&]+)").find(trimmed)
        return codeMatch?.groupValues?.get(1) ?: ""
    }
    // Otherwise assume it's already the code
    return trimmed
}

/**
 * Epic Login Dialog
 *
 * Epic uses OAuth2 authentication with automatic callback handling:
 * 1. Open Epic login URL in browser
 * 2. Login with Epic credentials
 * 3. Epic redirects back to app with authorization code automatically
 * ! Note: This UI will be temporary as we will migrate to a redirect flow.
 */
@Composable
fun EpicLoginDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onAuthCodeClick: (authCode: String) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
) {
    val context = LocalContext.current
    var authCode by rememberSaveable { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    if (!visible) return
        AlertDialog(
            onDismissRequest = onDismissRequest,
            icon = { Icon(imageVector = Icons.Default.Login, contentDescription = null) },
            title = { Text(stringResource(R.string.epic_login_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isLandscape) {
                                Modifier
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(scrollState)
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.epic_login_auto_auth_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Open browser button
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(EpicConstants.EPIC_AUTH_LOGIN_URL))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.epic_login_browser_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = if (isLandscape) PaddingValues(8.dp) else ButtonDefaults.ContentPadding
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.epic_login_open_button))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = if (isLandscape) 4.dp else 8.dp))

                    // Manual code entry fallback
                    Text(
                        text = stringResource(R.string.epic_login_auth_example),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Authorization code input
                    OutlinedTextField(
                        value = authCode,
                        onValueChange = { authCode = it.trim() },
                        label = { Text(stringResource(R.string.epic_login_auth_code_label)) },
                        placeholder = { Text(stringResource(R.string.epic_login_auth_code_placeholder)) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Error message
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Loading indicator
                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (authCode.isNotBlank()) {
                            val extractedCode = extractCodeFromInput(authCode)
                            if (extractedCode.isNotEmpty()) {
                                onAuthCodeClick(extractedCode)
                            }
                        }
                    },
                    enabled = !isLoading && authCode.isNotBlank()
                ) {
                    Text(stringResource(R.string.epic_login_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissRequest,
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.epic_login_cancel))
                }
            }
        )
    }

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_EpicLoginDialog() {
    PluviaTheme {
        EpicLoginDialog(
            visible = true,
            onDismissRequest = {},
            onAuthCodeClick = {},
            isLoading = false,
            errorMessage = null
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_EpicLoginDialogWithError() {
    PluviaTheme {
        EpicLoginDialog(
            visible = true,
            onDismissRequest = {},
            onAuthCodeClick = {},
            isLoading = false,
            errorMessage = "Invalid authorization code. Please try again."
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_EpicLoginDialogLoading() {
    PluviaTheme {
        EpicLoginDialog(
            visible = true,
            onDismissRequest = {},
            onAuthCodeClick = {},
            isLoading = true,
            errorMessage = null
        )
    }
}
