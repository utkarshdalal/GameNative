package app.gamenative.ui.screen.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.gamenative.service.rockstar.RockstarAuthManager
import app.gamenative.service.rockstar.RockstarConstants
import app.gamenative.service.rockstar.RockstarLoginGate
import app.gamenative.service.rockstar.RockstarSignInShim
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber

/**
 * Rockstar account sign-in, following the flow that produced a working ScAuthToken on 2026-09-08:
 *
 *   1. load a plain page on the signin origin (robots.txt) and inject the launcher bridge there;
 *      injecting over the running app breaks its webpack config
 *   2. the shim fetches /signin/user-form?cid=launcher and writes it into the document
 *   3. the user signs in; the page calls CallAuthResult{authCode}, which expires in ~60 seconds
 *   4. move to the rgl origin and exchange the code at /api/connect/gateway for the token
 *
 * Not /sdk?cid=launcher: that is what the launcher itself opens, but it runs invisible reCAPTCHA
 * Enterprise and signs its requests inside its own fetchJson, so it cannot be driven from outside.
 */
class RockstarOAuthActivity : ComponentActivity() {
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !finished) { finished = true; RockstarLoginGate.deliver(null) }
    }

    private var finished = false
    private var bridgeAttached = false
    private var injected = false
    private var exchanging = false
    private var authCode: String? = null
    private var fingerprint: String = ""
    private var webView: WebView? = null
    private val poller = Handler(Looper.getMainLooper())

    /*
     * The shim sets gn_code with document.cookie, which needs no bridge at all. Watching for it
     * is therefore an independent route to the auth code: if the JS interface ever fails again,
     * this still finds it. The code expires in about a minute, so poll briefly and often.
     */
    private val watchCookie = object : Runnable {
        override fun run() {
            if (finished) return
            val raw = runCatching {
                CookieManager.getInstance().getCookie("https://${RockstarConstants.LAUNCHER_HOST}")
            }.getOrNull()
            val code = raw?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith(RockstarConstants.HANDOFF_COOKIE + "=") }
                ?.substringAfter('=')
            if (!code.isNullOrEmpty() && !exchanging) {
                Timber.i("Rockstar sign-in: auth code found in the %s cookie (%d chars)",
                    RockstarConstants.HANDOFF_COOKIE, code.length)
                Bridge().onAuthCode(code, RockstarSignInShim.fingerprint().toString())
                return
            }
            poller.postDelayed(this, 1000)
        }
    }

    private fun done(token: String?) {
        if (finished) return
        finished = true
        poller.removeCallbacksAndMessages(null)
        setResult(if (token != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        RockstarLoginGate.deliver(token)
        finish()
    }

    /** Called from the injected shim. Every method has to assume it is on a WebView thread. */
    inner class Bridge {
        @JavascriptInterface
        fun onQuery(method: String) = Timber.i("Rockstar sign-in: page asked for %s", method)

        @JavascriptInterface
        fun onAuthCode(code: String, fp: String) {
            runOnUiThread {
                if (exchanging || finished) return@runOnUiThread
                exchanging = true
                authCode = code
                fingerprint = fp
                Timber.i("Rockstar sign-in: auth code received (%d chars), exchanging", code.length)
                /*
                 * Exchanged here rather than by navigating: the launcher origin's root answers a
                 * plain browser with 403, so sending the WebView there just strands the user on
                 * an error page. The request carries that origin's cookies from the shared store.
                 */
                lifecycleScope.launch {
                    RockstarAuthManager.exchange(code, fp)
                        .onSuccess { done(it) }
                        .onFailure {
                            Timber.w("Rockstar sign-in: exchange failed (%s); window stays open", it.message)
                            exchanging = false
                        }
                }
            }
        }

        @JavascriptInterface
        fun onAuthFailed(detail: String) {
            Timber.w("Rockstar sign-in: page reported no auth code: %s", detail.take(200))
        }

        @JavascriptInterface
        fun onExchange(status: Int, fieldNames: String, token: String) {
            Timber.i("Rockstar sign-in: gateway status=%d fields=[%s] token=%d chars",
                status, fieldNames, token.length)
            if (token.isNotEmpty() && RockstarAuthManager.looksLikeScAuthToken(token)) {
                runOnUiThread { done(token) }
            } else {
                Timber.w("Rockstar sign-in: no usable token in the gateway response; leaving the window open")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activeTitle = intent.getStringExtra(RockstarConstants.ACTIVE_TITLE_EXTRA)
        if (activeTitle == null || !activeTitle.matches(Regex("[a-z0-9_]{1,127}"))) {
            finish()
            return
        }

        RockstarAuthManager.clearHandoffCookie()

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    /* a page on the signin origin that is not the app itself */
                    url = "https://${RockstarConstants.SIGNIN_HOST}/robots.txt",
                    onDismissRequest = { done(null) },
                    onPageFinished = { url, view ->
                        webView = view
                        RockstarAuthManager.observe("loaded", url)
                        val page = android.net.Uri.parse(url)
                        val isSignInOrigin = page.scheme == "https" && page.host == RockstarConstants.SIGNIN_HOST
                        when {
                            /*
                             * addJavascriptInterface only takes effect on the NEXT page load, so
                             * attach it and reload before injecting anything. Without the reload
                             * the bridge is undefined in the page, and since every call into it
                             * is wrapped in try/catch the failures are silent -- the sign-in looks
                             * like it is working while nothing is ever captured.
                             */
                            !bridgeAttached && isSignInOrigin -> {
                                bridgeAttached = true
                                view.addJavascriptInterface(Bridge(), BRIDGE)
                                Timber.i("Rockstar sign-in: bridge attached, reloading so it takes effect")
                                view.reload()
                            }
                            !injected && isSignInOrigin -> {
                                injected = true
                                view.evaluateJavascript(
                                    RockstarSignInShim.script(activeTitle, BRIDGE, android.os.Build.MODEL ?: "GAMENATIVE"),
                                ) { Timber.i("Rockstar sign-in: shim installed -> %s", it) }
                                poller.postDelayed(watchCookie, 1000)
                            }
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val BRIDGE = "GNBridge"
    }
}
