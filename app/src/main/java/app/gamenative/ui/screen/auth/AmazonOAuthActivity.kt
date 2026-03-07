package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.gamenative.service.amazon.AmazonAuthManager
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amazon OAuth Activity — hosts a WebView for the PKCE sign-in flow.
 *
 * After the user signs in, Amazon redirects to:
 *   https://www.amazon.com/?openid.assoc_handle=amzn_sonic_games_launcher&openid.oa2.authorization_code=...
 *
 * The code is captured in [AmazonAuthWebView] and returned via [Activity.RESULT_OK].
 */
class AmazonOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authUrl = AmazonAuthManager.startAuthFlow()

        setContent {
            PluviaTheme {
                AmazonAuthWebView(
                    authUrl = authUrl,
                    onCodeCaptured = { code ->
                        Timber.i("[AmazonOAuth] ✓ Captured authorization code")
                        finishWithCode(code)
                    },
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    private fun finishWithCode(code: String) {
        val resultIntent = Intent().apply { putExtra(EXTRA_AUTH_CODE, code) }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

/**
 * Custom WebView composable that intercepts Amazon OAuth redirects.
 *
 * The final OAuth redirect goes to:
 *   https://www.amazon.com/?openid.assoc_handle=amzn_sonic_games_launcher&openid.oa2.authorization_code=...
 *
 * On modern Android WebView, same-origin navigations (amazon.com → amazon.com/)
 * bypass shouldOverrideUrlLoading and go straight to onPageStarted. We therefore
 * check for the auth code in BOTH hooks. An AtomicBoolean guards against double-firing.
 */
@Composable
private fun AmazonAuthWebView(
    authUrl: String,
    onCodeCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val codeCaptured = remember { AtomicBoolean(false) }

    val webViewClient = remember {
        object : WebViewClient() {

            private fun tryCapture(url: String, source: String): Boolean {
                if (!isAmazonRedirect(url)) return false
                val code = extractAuthCode(url)
                if (code != null && codeCaptured.compareAndSet(false, true)) {
                    Timber.i("[AmazonOAuth] ✓ Code captured in $source: ${url.take(120)}...")
                    onCodeCaptured(code)
                    return true
                } else if (code == null) {
                    Timber.d("[AmazonOAuth] Amazon redirect but no auth code yet ($source): ${url.take(120)}...")
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Timber.d("[AmazonOAuth] shouldOverrideUrlLoading: ${url.take(120)}...")
                return tryCapture(url, "shouldOverrideUrlLoading")
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                Timber.d("[AmazonOAuth] shouldOverrideUrlLoading(legacy): ${url.take(120)}...")
                return tryCapture(url, "shouldOverrideUrlLoading(legacy)")
            }

            // Same-origin redirects on modern WebView skip shouldOverrideUrlLoading
            // entirely and only call onPageStarted — so we MUST also check here.
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url == null) return
                Timber.d("[AmazonOAuth] onPageStarted: ${url.take(120)}...")
                if (tryCapture(url, "onPageStarted")) {
                    // Stop the amazon.com homepage from fully loading after code is captured
                    view?.stopLoading()
                }
            }
        }
    }

    AuthWebViewDialog(
        isVisible = true,
        url = authUrl,
        onDismissRequest = onDismiss,
        customWebViewClient = webViewClient
    )
}

/**
 * Returns true if this URL is the Amazon OAuth success redirect.
 * The redirect lands at: https://www.amazon.com/?openid.assoc_handle=amzn_sonic_games_launcher&...
 */
private fun isAmazonRedirect(url: String): Boolean {
    return (url.startsWith("https://www.amazon.com/") || url.startsWith("https://amazon.com/")) &&
        url.contains("openid.assoc_handle=amzn_sonic_games_launcher")
}

private fun extractAuthCode(url: String): String? {
    return try {
        val uri = Uri.parse(url)
        uri.getQueryParameter("openid.oa2.authorization_code")
    } catch (e: Exception) {
        Timber.w(e, "[AmazonOAuth] Failed to parse URL")
        null
    }
}
