package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.epic.EpicConstants
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import timber.log.Timber

/**
 * Epic OAuth Activity that hosts a WebView and automatically captures
 * the authorization code. Epic returns the code in the redirect page body as JSON
 * ({"authorizationCode":"...", ...}), not in the URL – so we read the body via JS.
 * Uses a per-session state parameter for CSRF protection.
 */
class EpicOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
        private const val SAVED_OAUTH_STATE = "oauth_state"
        private const val SAVED_AUTH_URL = "auth_url"
    }

    private var oauthState: String? = null
    private var initialAuthUrl: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        oauthState?.let { outState.putString(SAVED_OAUTH_STATE, it) }
        initialAuthUrl?.let { outState.putString(SAVED_AUTH_URL, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (authUrl, state) = if (savedInstanceState != null) {
            val savedState = savedInstanceState.getString(SAVED_OAUTH_STATE)
            val savedUrl = savedInstanceState.getString(SAVED_AUTH_URL)
            if (savedState != null && savedUrl != null) {
                savedUrl to savedState
            } else {
                EpicConstants.LoginUrlWithState()
            }
        } else {
            EpicConstants.LoginUrlWithState()
        }
        oauthState = state
        initialAuthUrl = authUrl

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
                            if (extractState(currentUrl) != oauthState) {
                                Timber.w("OAuth callback state mismatch; ignoring (possible CSRF)")
                                return@AuthWebViewDialog
                            }
                            val code = extractAuthCode(currentUrl)
                            if (code != null) finishWithCode(code)
                            // else: URL has no code param; we'll get it from page body in onPageFinished
                        }
                    },
                    onPageFinished = { url, webView ->
                        if (!isValidRedirectUrl(url)) return@AuthWebViewDialog
                        if (extractState(url) != oauthState) {
                            Timber.w("OAuth callback state mismatch; ignoring (possible CSRF)")
                            return@AuthWebViewDialog
                        }
                        webView.evaluateJavascript(
                            "(function(){ try { var j = JSON.parse(document.body && document.body.innerText || '{}'); return j.authorizationCode || null; } catch(e){ return null; } })();"
                        ) { result ->
                            val code = unquoteJsonString(result)
                            if (!code.isNullOrBlank()) {
                                Timber.d("Automatically extracted Epic auth code from page body")
                                finishWithCode(code)
                            }
                        }
                    },
                )
            }
        }
    }

    private fun finishWithCode(code: String) {
        val resultIntent = Intent().apply { putExtra(EXTRA_AUTH_CODE, code) }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun isValidRedirectUrl(url: String): Boolean {
        return try {
            val parsed = Uri.parse(url)
            val expected = Uri.parse(EpicConstants.EPIC_REDIRECT_URI)
            parsed.scheme.equals(expected.scheme, ignoreCase = true) &&
                parsed.host.equals(expected.host, ignoreCase = true) &&
                parsed.path == expected.path
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse redirect URL: %s", redactUrlForLogging(url))
            false
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

    private fun extractAuthCode(url: String): String? {
        return try {
            Uri.parse(url).getQueryParameter("code")
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract auth code from URL: %s", redactUrlForLogging(url))
            null
        }
    }

    /** evaluateJavascript returns a JSON-encoded string (e.g. "\"ef444d3a...\""). Strip quotes and unescape. */
    private fun unquoteJsonString(jsResult: String?): String? {
        if (jsResult.isNullOrBlank()) return null
        val raw = jsResult.trim()
        if (raw == "null") return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        return raw.drop(1).dropLast(1).replace("\\\"", "\"")
    }
}
