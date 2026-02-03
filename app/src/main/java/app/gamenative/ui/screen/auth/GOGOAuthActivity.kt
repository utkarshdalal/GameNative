package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.ui.component.dialog.GOGWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber

/**
 * GOG OAuth Activity that hosts GOGWebViewDialog and automatically captures
 * the authorization code when GOG redirects to the success URL (aligns with gog-support).
 */
class GOGOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
        const val GOG_CLIENT_ID = "46899977096215655"
        val GOG_AUTH_URL = "https://auth.gog.com/auth?" +
            "client_id=$GOG_CLIENT_ID" +
            "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
            "&response_type=code" +
            "&layout=galaxy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PluviaTheme {
                GOGWebViewDialog(
                    isVisible = true,
                    url = GOG_AUTH_URL,
                    onDismissRequest = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onUrlChange = { currentUrl: String ->
                        if (currentUrl.contains("embed.gog.com/on_login_success")) {
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

    private fun extractAuthCode(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter("code")
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract auth code from URL: $url")
            null
        }
    }
}
