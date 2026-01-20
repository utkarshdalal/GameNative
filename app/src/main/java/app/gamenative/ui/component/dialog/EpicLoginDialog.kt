package app.gamenative.ui.component.dialog

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.service.epic.EpicConstants
import app.gamenative.ui.theme.PluviaTheme

private fun extractCodeFromInput(input: String): String {
    val trimmed = input.trim()
    // Check if it's a URL with code parameter
    if (trimmed.startsWith("http")) {
        val codeMatch = Regex("[?&]code=([^&]+)").find(trimmed)
        return codeMatch?.groupValues?.get(1) ?: ""
    }
    // Check if it's a URL with authorizationCode parameter (Epic specific)
    if (trimmed.contains("authorizationCode=")) {
        val codeMatch = Regex("[?&]authorizationCode=([^&]+)").find(trimmed)
        return codeMatch?.groupValues?.get(1) ?: ""
    }
    // Otherwise assume it's already the code
    return trimmed
}

/**
 * Epic Games Login Dialog
 *
 * Epic uses OAuth2 authentication with manual code entry:
 * 1. Open Epic login URL in browser
 * 2. Login with Epic credentials
 * 3. Copy the authorization code from the redirect URL
 * 4. Paste it into this dialog
 * 5. GameNative exchanges code for access tokens via legendary
 *
 * ! Note: This UI will be temporary as we may migrate to a redirect flow in the future.
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

    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(imageVector = Icons.Default.Login, contentDescription = null) },
        title = { Text("Sign in to Epic Games") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Instructions
                Text(
                    text = "Sign in with your Epic Games account:",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "1. Tap 'Open Epic Login' and sign in\n2. After login, you'll see 'redirectUrl' in the browser\n3. Copy the 'authorizationCode' value from the URL\n4. Paste it below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "Example URL:\nhttps://www.epicgames.com/id/api/redirect?clientId=...&authorizationCode=YOUR_CODE_HERE",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
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
                                "Could not open browser",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Epic Login")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Manual code entry
                Text(
                    text = "Paste your authorization code below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = authCode,
                    onValueChange = { authCode = it },
                    label = { Text("Authorization Code") },
                    placeholder = { Text("Paste code here…") },
                    enabled = !isLoading,
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Loading indicator
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Authenticating...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val extractedCode = extractCodeFromInput(authCode)
                    if (extractedCode.isNotEmpty()) {
                        onAuthCodeClick(extractedCode)
                    }
                },
                enabled = !isLoading && authCode.isNotBlank(),
            ) {
                Text("Sign In")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isLoading,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Preview
@Composable
private fun Preview_EpicLoginDialog() {
    PluviaTheme {
        EpicLoginDialog(
            visible = true,
            onDismissRequest = {},
            onAuthCodeClick = {},
            isLoading = false,
            errorMessage = null,
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
            errorMessage = "Invalid authorization code. Please try again.",
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
            errorMessage = null,
        )
    }
}
