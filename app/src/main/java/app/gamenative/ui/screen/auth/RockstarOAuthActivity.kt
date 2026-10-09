package app.gamenative.ui.screen.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.gamenative.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.gamenative.service.rockstar.RockstarAuthManager
import app.gamenative.service.rockstar.RockstarConstants
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.rockstar.RockstarLoginGate
import app.gamenative.service.rockstar.RockstarSignInShim
import app.gamenative.service.rockstar.RockstarSteamTicket
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import org.json.JSONObject
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
 * The sign-in is always plain. After the login, when the launch is for a Steam game, the
 * Rockstar account's linked accounts are checked with the loginGuid; if Steam is not among them
 * the user is asked to link it, with a ticket minted for the game's app id, which is what a never
 * linked account needs before the stub's entitlement mirror can find the game.
 *
 * Not /sdk?cid=launcher: that is what the launcher itself opens, but it runs invisible reCAPTCHA
 * Enterprise and signs its requests inside its own fetchJson, so it cannot be driven from outside.
 */
class RockstarOAuthActivity : ComponentActivity() {
    override fun onDestroy() {
        super.onDestroy()
        ticket?.close()
        if (isFinishing && !finished) { finished = true; RockstarLoginGate.deliver(null) }
    }

    private var finished = false
    private var bridgeAttached = false
    private var injected = false
    private var exchanging = false
    private var authCode: String? = null
    private var fingerprint: String = ""
    private var webView: WebView? = null
    private var steamAppId = 0
    private var activateTitle = false
    private var token: String? = null
    private var loginGuid: String? = null
    private var linkChecked = false
    private var ticket: RockstarSteamTicket? = null
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

    private val noLoginGuid = Runnable {
        Timber.w("Rockstar sign-in: no loginGuid within 10 s of the token; skipping the Steam link check")
        SnackbarManager.show(getString(R.string.rockstar_link_unchecked))
        done(token)
    }

    private fun tokenReady(value: String) {
        if (finished || token != null) return
        if (steamAppId <= 0) {
            done(value)
            return
        }
        token = value
        if (loginGuid != null) checkLink() else poller.postDelayed(noLoginGuid, 10000)
    }

    private fun checkLink() {
        val guid = loginGuid ?: return
        if (finished || token == null || linkChecked) return
        linkChecked = true
        poller.removeCallbacks(noLoginGuid)
        val view = webView ?: return done(token)
        Timber.i("Rockstar sign-in: checking the linked accounts")
        view.evaluateJavascript("window.gnLinkedAccounts(${JSONObject.quote(guid)})", null)
    }

    private fun onLinkedAccounts(status: Int, body: String) {
        if (finished) return
        val json = if (status == 200) runCatching { JSONObject(body) }.getOrNull() else null
        if (json == null) {
            Timber.w("Rockstar sign-in: linked accounts check failed (%d); skipping the Steam link", status)
            SnackbarManager.show(getString(R.string.rockstar_link_unchecked))
            return done(token)
        }
        val nickname = json.optString("nickname")
        val accounts = json.optJSONArray("linkedAccounts")
        val steam = (0 until (accounts?.length() ?: 0))
            .mapNotNull { accounts?.optJSONObject(it) }
            .firstOrNull { it.optString("onlineService").equals("steam", ignoreCase = true) }
        if (steam != null) {
            val linked = steam.optString("username").trim()
            val current = RockstarSteamTicket.persona()?.trim()
            val same = current?.let { linked.equals(it, ignoreCase = true) }
            Timber.i("Rockstar sign-in: account is linked to Steam; matches the current Steam account: %s", same ?: "unknown")
            if (same == false) SnackbarManager.show(getString(R.string.rockstar_link_refused))
            if (activateTitle && same != false) return validateTitle()
            if (!forceLinkStep && !java.io.File(filesDir, "rockstar_force_link").isFile) return done(token)
        }
        val guid = loginGuid ?: return done(token)
        lifecycleScope.launch {
            val minted = RockstarSteamTicket.mint(steamAppId)
            if (minted == null || finished) {
                minted?.close()
                done(token)
                return@launch
            }
            ticket = minted
            android.app.AlertDialog.Builder(this@RockstarOAuthActivity)
                .setTitle(R.string.rockstar_link_title)
                .setMessage(getString(R.string.rockstar_link_text, minted.persona, nickname))
                .setCancelable(false)
                .setPositiveButton(R.string.rockstar_link_confirm) { _, _ ->
                    Timber.i("Rockstar sign-in: linking Steam account %d for app %d", minted.steamId, steamAppId)
                    val steamJson = minted.externalPlatformInfo().toString()
                    val view = webView ?: return@setPositiveButton done(token)
                    view.evaluateJavascript("window.gnLinkSteam(${JSONObject.quote(guid)}, ${JSONObject.quote(steamJson)})", null)
                }
                .setNegativeButton(R.string.rockstar_link_skip) { _, _ ->
                    Timber.i("Rockstar sign-in: Steam link declined")
                    done(token)
                }
                .show()
        }
    }

    /*
     * The page's own step for a launch from Steam on a signed-in account: the Steam block and the
     * launched title's name go to validateExternalAndLoggedInUser, which is where Rockstar mirrors
     * the title's ownership onto the account. The answer is only logged; sign-in is already done.
     */
    /*
     * The user-form route never runs the page's own autoLogin, so when a title activation brings
     * a signed-in user here the saved loginGuid is presented through the same call the launcher
     * makes; on success the stored token is reused and the link check runs. Anything else leaves
     * the form for the user, as before.
     */
    private fun resumeSavedSession(view: WebView) {
        if (!activateTitle) return
        val creds = RockstarAuthManager.load(this) ?: return
        if (creds.loginGuid.isEmpty()) return
        Timber.i("Rockstar sign-in: resuming the saved session for the title activation")
        view.evaluateJavascript(
            "window.gnAutoLogin(${JSONObject.quote(creds.loginGuid)}, ${JSONObject.quote(creds.rememberedMachineToken)})", null,
        )
    }

    private fun onAutoLogin(status: Int, body: String) {
        if (finished || token != null) return
        val creds = RockstarAuthManager.load(this) ?: return
        if (status != 200) {
            Timber.w("Rockstar sign-in: saved session not resumed (%d); the form stays for the user", status)
            return
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        loginGuid = json?.optString("loginGuid")?.takeIf { it.isNotEmpty() } ?: creds.loginGuid
        val code = json?.optString("authCode").orEmpty()
        if (code.isEmpty()) {
            Timber.w("Rockstar sign-in: auto login carried no auth code; the form stays for the user")
            return
        }
        Bridge().onAuthCode(code, RockstarSignInShim.fingerprint(android.os.Build.MODEL ?: "GAMENATIVE").toString())
    }

    private fun validateTitle() {
        val guid = loginGuid ?: return done(token)
        lifecycleScope.launch {
            val minted = RockstarSteamTicket.mint(steamAppId)
            if (minted == null || finished) {
                minted?.close()
                done(token)
                return@launch
            }
            ticket = minted
            Timber.i("Rockstar sign-in: validating the Steam account for the title (app %d)", steamAppId)
            val steamJson = minted.externalPlatformInfo().toString()
            val view = webView ?: return@launch done(token)
            view.evaluateJavascript("window.gnValidateSteam(${JSONObject.quote(guid)}, ${JSONObject.quote(steamJson)})", null)
        }
    }

    private fun onLinkResult(status: Int, body: String) {
        try {
            val error = runCatching { JSONObject(body).optJSONObject("data")?.optString("errorCode") }.getOrNull().orEmpty()
            when {
                body.contains(RockstarConstants.ALREADY_LINKED_ERROR) ->
                    SnackbarManager.show(getString(R.string.rockstar_link_refused))
                status == 200 && error.isEmpty() -> SnackbarManager.show(getString(R.string.rockstar_link_done))
                else -> {
                    val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "HTTP $status"
                    SnackbarManager.show(getString(R.string.rockstar_link_failed, message))
                }
            }
        } finally {
            ticket?.close()
            ticket = null
            done(token)
        }
    }

    private fun done(token: String?) {
        if (finished) return
        finished = true
        poller.removeCallbacksAndMessages(null)
        ticket?.close()
        ticket = null
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
                        .onSuccess { tokenReady(it) }
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
        fun onRequest(url: String, status: Int, carriesSteam: Boolean, body: String) {
            Timber.i("Rockstar sign-in: page %s %s -> %d%s %s", if (carriesSteam) "LINK" else "call",
                url.substringBefore('?').take(120), status, if (carriesSteam) " (steam payload)" else "", body.take(300))
        }

        @JavascriptInterface
        fun onLoginGuid(guid: String) {
            Timber.i("Rockstar sign-in: loginGuid received (%d chars)", guid.length)
            runOnUiThread {
                loginGuid = guid
                checkLink()
            }
        }

        @JavascriptInterface
        fun onLinkedAccounts(status: Int, body: String) {
            Timber.i("Rockstar sign-in: linked accounts -> %d (%d bytes)", status, body.length)
            runOnUiThread { this@RockstarOAuthActivity.onLinkedAccounts(status, body) }
        }

        @JavascriptInterface
        fun onAutoLogin(status: Int, body: String) {
            Timber.i("Rockstar sign-in: auto login -> %d %s", status, body.take(300))
            runOnUiThread { this@RockstarOAuthActivity.onAutoLogin(status, body) }
        }

        @JavascriptInterface
        fun onValidateResult(status: Int, body: String) {
            Timber.i("Rockstar sign-in: title validation -> %d %s", status, body.take(600))
            RockstarLoginGate.titleValidated = status == 200
            runOnUiThread { done(token) }
        }

        @JavascriptInterface
        fun onLinkResult(status: Int, body: String) {
            Timber.i("Rockstar sign-in: Steam link -> %d %s", status, body.take(300))
            runOnUiThread { this@RockstarOAuthActivity.onLinkResult(status, body) }
        }

        @JavascriptInterface
        fun onExchange(status: Int, fieldNames: String, token: String) {
            Timber.i("Rockstar sign-in: gateway status=%d fields=[%s] token=%d chars",
                status, fieldNames, token.length)
            if (token.isNotEmpty() && RockstarAuthManager.looksLikeScAuthToken(token)) {
                runOnUiThread { tokenReady(token) }
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
        activateTitle = intent.getBooleanExtra(RockstarConstants.ACTIVATE_TITLE_EXTRA, false)

        steamAppId = intent.getIntExtra(RockstarConstants.STEAM_APP_ID_EXTRA, 0)

        RockstarAuthManager.clearHandoffCookie()

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    /* a page on the signin origin that is not the app itself */
                    url = "https://${RockstarConstants.SIGNIN_HOST}/robots.txt",
                    onDismissRequest = { done(token) },
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
                                    RockstarSignInShim.script(
                                        filesDir, activeTitle, BRIDGE, android.os.Build.MODEL ?: "GAMENATIVE",
                                    ),
                                ) { Timber.i("Rockstar sign-in: shim installed -> %s", it) }
                                resumeSavedSession(view)
                                poller.postDelayed(watchCookie, 1000)
                            }
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val BRIDGE = "GNBridge"

        @Volatile var forceLinkStep = false
    }
}
