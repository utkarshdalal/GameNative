package app.gamenative.ui.screen.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import app.gamenative.service.ubisoft.UbisoftHelperConfig
import app.gamenative.service.ubisoft.UbisoftLoginGate
import app.gamenative.service.ubisoft.UbisoftWebLogin
import app.gamenative.theme.ThemeManager
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import java.util.Locale
import org.json.JSONTokener
import timber.log.Timber

/**
 * Ubisoft account sign-in. Ubisoft's own page does everything, including two-factor and bot checks;
 * nothing is typed or posted for the user. Once the page lands on the configured landing URL its
 * storage holds the signed-in session, which is read with evaluateJavascript and handed to the gate.
 */
class UbisoftOAuthActivity : ComponentActivity() {
    private var finished = false
    private var landingUrl = ""
    private var webView: WebView? = null
    private var attempts = 0
    private val poller = Handler(Looper.getMainLooper())

    /* The page can write its storage after the landing page has loaded, so read it a few times. */
    private val readStorage = object : Runnable {
        override fun run() {
            val view = webView ?: return
            if (finished) return
            attempts++
            view.evaluateJavascript(STORAGE_SCRIPT) { raw ->
                if (finished) return@evaluateJavascript
                val storage = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                val login = storage?.let(UbisoftWebLogin::fromStorage)
                if (login != null) {
                    Timber.i("Ubisoft sign-in: session found in page storage (attempt %d)", attempts)
                    done(login)
                } else if (attempts < MAX_ATTEMPTS) {
                    poller.postDelayed(this, 1000)
                } else {
                    Timber.w("Ubisoft sign-in: landing page reached but no session in its storage")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !finished) {
            finished = true
            UbisoftLoginGate.deliver(null)
        }
    }

    private fun done(login: UbisoftWebLogin?) {
        if (finished) return
        finished = true
        poller.removeCallbacksAndMessages(null)
        setResult(if (login != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        UbisoftLoginGate.deliver(login)
        finish()
    }

    private fun isLanding(url: String): Boolean {
        if (landingUrl.isEmpty()) return false
        return url.substringBefore('?').substringBefore('#').trimEnd('/') ==
            landingUrl.substringBefore('?').substringBefore('#').trimEnd('/')
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loginUrl = runCatching {
            landingUrl = UbisoftHelperConfig.value(this, UbisoftHelperConfig.WEB_NEXT_URL)
            UbisoftHelperConfig.loginUrl(this, Locale.getDefault().toLanguageTag())
        }.onFailure { Timber.e(it, "Ubisoft sign-in: helper configuration unavailable") }.getOrNull()
        if (loginUrl == null) {
            done(null)
            return
        }
        setContent {
            PluviaTheme(spec = ThemeManager.activeSpec.collectAsState().value) {
                AuthWebViewDialog(
                    isVisible = true,
                    url = loginUrl,
                    onDismissRequest = { done(null) },
                    onPageFinished = { url, view ->
                        webView = view
                        if (isLanding(url) && attempts == 0) poller.post(readStorage)
                    },
                )
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 15

        /** Every storage value that is a JSON object, as one JSON array. */
        private const val STORAGE_SCRIPT = "(function(){var out=[];try{for(var i=0;i<localStorage.length;i++){" +
            "try{var v=JSON.parse(localStorage.getItem(localStorage.key(i)));if(v&&typeof v==='object'&&!Array.isArray(v))out.push(v);}catch(e){}}" +
            "}catch(e){}return JSON.stringify(out);})()"
    }
}
