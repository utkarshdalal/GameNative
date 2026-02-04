package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.gog.GOGConstants
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import timber.log.Timber

/**
 * GOG OAuth Activity that hosts AuthWebViewDialog and automatically captures
 * the authorization code when GOG redirects to the success URL (aligns with gog-support).
 * Uses a per-session state parameter for CSRF protection.
 */
class GOGOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
    }

    private var oauthState: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (authUrl, state) = GOGConstants.LoginUrlWithState()
        oauthState = state

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    url = authUrl,
                    onDismissRequest = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onUrlChange = { currentUrl: String ->
                        if (isValidRedirectUrl(currentUrl)) {
                            val returnedState = extractState(currentUrl)
                            if (returnedState != oauthState) {
                                Timber.w("OAuth callback state mismatch; ignoring (possible CSRF)")
                                return@AuthWebViewDialog
                            }
                            val extractedCode = extractAuthCode(currentUrl)
                            if (extractedCode != null) {
                                Timber.d("Automatically extracted auth code from URL")
                                val resultIntent = Intent().apply {
                                    putExtra(EXTRA_AUTH_CODE, extractedCode)
                                }
                                setResult(Activity.RESULT_OK, resultIntent)
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    private fun extractState(url: String): String? {
        return try {
            Uri.parse(url).getQueryParameter("state")
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract state from URL: %s", redactUrlForLogging(url))
            null
        }
    }

    private fun isValidRedirectUrl(url: String): Boolean {
        return try {
            val parsed = Uri.parse(url)
            val expected = Uri.parse(GOGConstants.GOG_REDIRECT_URI)
            parsed.scheme.equals(expected.scheme, ignoreCase = true) &&
                parsed.host.equals(expected.host, ignoreCase = true) &&
                parsed.path == expected.path
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse redirect URL: %s", redactUrlForLogging(url))
            false
        }
    }

    private fun extractAuthCode(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter("code")
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract auth code from URL: %s", redactUrlForLogging(url))
            null
        }
    }
}
