// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.ui.screen.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.gamenative.R
import app.gamenative.service.xbox.XboxTokenStore
import app.gamenative.service.xbox.XboxUser
import app.gamenative.service.xbox.api.XboxLoginGate
import app.gamenative.service.xbox.live.BridgeMessage
import app.gamenative.service.xbox.live.LiveConfig
import app.gamenative.service.xbox.live.LiveSignIn
import app.gamenative.service.xbox.live.SignInResult
import app.gamenative.service.xbox.live.load
import app.gamenative.theme.ThemeManager
import app.gamenative.ui.component.RingIconButton
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Microsoft account sign-in through the login page Windows hosts for its token broker. The page is
 * loaded with the host's request headers and talks to its host through window.external.notify,
 * which a document-start script routes to a JavaScript interface. When it hands over the sign-in
 * result the token exchange runs; if the account needs an extra approval step, a second page
 * session opens at the URL the exchange returns, and its result finishes the sign-in.
 */
class XboxOAuthActivity : ComponentActivity() {
    private data class Session(val id: Int, val url: String, val headers: Map<String, String>, val finish: Boolean) {
        val origins: Set<String> = setOfNotNull(origin(url))
    }

    private var finished = false
    private var exchanging = false
    private var session by mutableStateOf<Session?>(null)
    private var pageTitle by mutableStateOf<String?>(null)
    private var webView: WebView? = null
    private lateinit var cfg: LiveConfig
    private lateinit var store: XboxTokenStore
    private val main = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !finished) {
            finished = true
            XboxLoginGate.deliver(null)
        }
    }

    private fun done(user: XboxUser?) {
        if (finished) return
        finished = true
        setResult(if (user != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        XboxLoginGate.deliver(user)
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingStore = XboxLoginGate.pendingStore()
        val config = runCatching { LiveConfig.load(filesDir) }
            .onFailure { Timber.e(it, "Xbox sign-in: helper configuration unavailable") }
            .getOrNull()
        if (pendingStore == null || config == null) {
            done(null)
            return
        }
        store = pendingStore
        cfg = config
        session = Session(1, LiveSignIn.loginUrl(cfg), LiveSignIn.loginHeaders(cfg), finish = false)

        setContent {
            PluviaTheme(spec = ThemeManager.activeSpec.collectAsState().value) {
                val current = session ?: return@PluviaTheme
                Dialog(
                    onDismissRequest = {
                        val view = webView
                        if (view != null && view.canGoBack()) view.goBack() else done(null)
                    },
                    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
                ) {
                    Scaffold(
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        text = pageTitle ?: stringResource(R.string.auth_webview_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    RingIconButton(
                                        onClick = { done(null) },
                                        content = { Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.close)) },
                                    )
                                },
                            )
                        },
                    ) { padding ->
                        key(current.id) {
                            AndroidView(
                                modifier = Modifier.padding(padding),
                                factory = { context -> createWebView(context, current) },
                                onRelease = { view ->
                                    if (webView === view) webView = null
                                    view.stopLoading()
                                    view.removeJavascriptInterface(LiveSignIn.BRIDGE_NAME)
                                    view.webViewClient = WebViewClient()
                                    view.webChromeClient = WebChromeClient()
                                    view.destroy()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: android.content.Context, current: Session): WebView {
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = LiveSignIn.userAgent(cfg)
            }
            addJavascriptInterface(Bridge(current.id), LiveSignIn.BRIDGE_NAME)
            if (documentStart) {
                WebViewCompat.addDocumentStartJavaScript(this, LiveSignIn.initScript(), setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Without document-start support the alias goes in as early as the platform allows.
                    if (!documentStart) view.evaluateJavascript(LiveSignIn.initScript(), null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && !finished && LiveSignIn.isPostPage(cfg, url)) {
                        view.evaluateJavascript(LiveSignIn.POST_SCRIPT, null)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Timber.e("Xbox sign-in page error: $errorCode $description for ${redactUrlForLogging(failingUrl)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) pageTitle = title
                }
            }
            webView = this
            loadUrl(current.url, current.headers)
        }
    }

    /** Messages from window.external.notify arrive on a binder thread; handle them on the main thread. */
    private inner class Bridge(private val sessionId: Int) {
        @JavascriptInterface
        fun postMessage(message: String) {
            main.post { onBridgeMessage(sessionId, message) }
        }
    }

    private fun onBridgeMessage(sessionId: Int, message: String) {
        val current = session ?: return
        val view = webView ?: return
        if (finished || sessionId != current.id) return
        when (val parsed = LiveSignIn.parseBridgeMessage(message)) {
            is BridgeMessage.GetContext -> view.evaluateJavascript(LiveSignIn.contextReplyScript(cfg, parsed.context), null)
            is BridgeMessage.Token -> {
                // The sign-in result is only taken from the login pages themselves.
                if (origin(view.url) !in current.origins + setOfNotNull(origin(cfg.signInPostUrl))) {
                    Timber.w("Xbox sign-in: ignored a sign-in result from another origin")
                    return
                }
                if (exchanging) return
                exchanging = true
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { LiveSignIn.completeSignIn(store, cfg, parsed.property, current.finish) }
                    }.getOrElse { SignInResult.Failed(it.message ?: it.javaClass.simpleName) }
                    exchanging = false
                    onSignInResult(current, result)
                }
            }
            BridgeMessage.Unsupported -> Unit
        }
    }

    private fun onSignInResult(from: Session, result: SignInResult) {
        if (finished) return
        when (result) {
            is SignInResult.SignedIn -> done(result.user)
            is SignInResult.NeedsApproval -> {
                Timber.i("Xbox sign-in: account approval needed")
                pageTitle = null
                session = Session(from.id + 1, result.url, emptyMap(), finish = true)
            }
            is SignInResult.Failed -> {
                Timber.w("Xbox sign-in failed: %s", result.reason)
                done(null)
            }
        }
    }

    private companion object {
        fun origin(url: String?): String? {
            val uri = url?.let(Uri::parse) ?: return null
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }
    }
}
