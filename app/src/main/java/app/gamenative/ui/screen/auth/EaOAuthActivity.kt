package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.ea.EaAuthManager
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import timber.log.Timber

/**
 * EA account sign-in through EA's own login page. The EA app's OAuth client redirects to a
 * Qt resource URL (qrc:///html/login_successful.html?code=...), which only an embedded browser
 * can observe, so the WebView intercepts that navigation and hands the code back.
 */
class EaOAuthActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
    }

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authUrl = EaAuthManager.buildLoginUrl(this)

        val client = object : WebViewClient() {
            private fun intercept(url: String?): Boolean {
                if (url == null || !EaAuthManager.isRedirect(url)) return false
                if (finished) return true
                val code = EaAuthManager.extractCode(url)
                if (code == null) {
                    Timber.w("EA redirect without code: %s", redactUrlForLogging(url))
                    setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, "no code in redirect"))
                } else {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_AUTH_CODE, code))
                }
                finished = true
                finish()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                intercept(request?.url?.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = intercept(url)
        }

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    url = authUrl,
                    onDismissRequest = {
                        if (!finished) {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    },
                    customWebViewClient = client,
                )
            }
        }
    }
}
