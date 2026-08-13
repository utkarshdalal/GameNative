package app.gamenative.service

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

/**
 * Wishlists through a real (offscreen) WebView so the page's own JavaScript runs first. That earns
 * the cookies a bare HTTP client can't fake — Akamai's bot-manager token (bm_sv) and browserid —
 * which is what makes the UTM visit count as a *tracked* visit. The add then runs as an in-page
 * fetch, so it carries every one of those cookies and attributes to the visit.
 *
 * The WebView is attached to the window as a 1x1 transparent view so its renderer stays alive, and
 * heavy resources (images, video, css, fonts) are blocked so a memory-limited device can load the
 * page without crashing the renderer.
 */
object WishlistWebViewAdder {

    private const val TAG = "SteamWishlist"
    private const val STORE = "https://store.steampowered.com"
    private const val STORE_HOST = "store.steampowered.com"
    private const val STORE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 15_000L
    private val BLOCKED = Regex("\\.(jpg|jpeg|png|gif|webp|avif|svg|ico|mp4|m4s|webm|ttf|woff2?|css)(\\?|$)")
    private val STORE_COOKIES = listOf("steamLoginSecure", "birthtime", "lastagecheckage", "wantsmatureconctent")

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun add(context: Context, steamId: Long, token: String, appId: Int, campaignId: String): Boolean =
        withContext(Dispatchers.Main) {
            val activity = context.findActivity()
            val parent = activity?.findViewById<ViewGroup>(android.R.id.content)
            if (activity == null || parent == null) {
                Timber.tag(TAG).w("no activity window for webview add")
                return@withContext false
            }
            val done = CompletableDeferred<Boolean>()
            // Secret shared only with the main frame's injected script; a foreign frame that also
            // sees the AndroidWishlist bridge cannot fabricate a result without it.
            val nonce = UUID.randomUUID().toString().replace("-", "")

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val opts = "; path=/; domain=.steampowered.com; secure"
            cookieManager.setCookie(STORE, "steamLoginSecure=$steamId%7C%7C${URLEncoder.encode(token, "UTF-8")}$opts")
            cookieManager.setCookie(STORE, "birthtime=0$opts")
            cookieManager.setCookie(STORE, "lastagecheckage=1-January-1970$opts")
            cookieManager.setCookie(STORE, "wantsmatureconctent=1$opts")
            cookieManager.flush()

            val webView = WebView(activity)
            var injectRunnable: Runnable? = null
            var destroyed = false
            try {
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                    userAgentString = STORE_UA
                }
                webView.alpha = 0f
                parent.addView(webView, ViewGroup.LayoutParams(1, 1))

                fun finish(result: Boolean) {
                    if (!done.isCompleted) done.complete(result)
                }

                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onResult(callbackNonce: String, json: String) {
                            if (callbackNonce != nonce) {
                                Timber.tag(TAG).w("ignoring bridge call with bad nonce")
                                return
                            }
                            val ok = try {
                                JSONObject(json).optBoolean("success")
                            } catch (e: Exception) {
                                false
                            }
                            Timber.tag(TAG).i("webview addtowishlist result=$json -> $ok")
                            finish(ok)
                        }
                    },
                    "AndroidWishlist",
                )

                var posted = false
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString()?.lowercase() ?: return null
                        val block = u.contains("/store_trailers/") || u.contains("video.akamai") || BLOCKED.containsMatchIn(u)
                        return if (block) WebResourceResponse("text/plain", "utf-8", null) else null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (posted || view == null || url == null) return
                        val uri = runCatching { Uri.parse(url) }.getOrNull()
                        if (uri?.host != STORE_HOST || uri.path?.contains("/app/$appId") != true) return
                        posted = true
                        val target = view
                        // Give Akamai's inline bot-manager script a beat to set bm_sv before the add.
                        val r = Runnable {
                            if (!done.isCompleted && !destroyed) {
                                target.evaluateJavascript(addScript(appId, nonce), null)
                            }
                        }
                        injectRunnable = r
                        target.postDelayed(r, 800)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Timber.tag(TAG).w("webview renderer gone, aborting add")
                        finish(false)
                        return true
                    }
                }

                val utmUrl = "$STORE/app/$appId/?utm_source=gamenative&utm_medium=app" +
                    "&utm_campaign=${URLEncoder.encode(campaignId, "UTF-8")}"
                Timber.tag(TAG).i("webview loading $utmUrl")
                webView.loadUrl(utmUrl)

                val result = withTimeoutOrNull(TIMEOUT_MS) { done.await() } ?: false
                if (!done.isCompleted) Timber.tag(TAG).w("webview wishlist timed out")
                result
            } finally {
                destroyed = true
                injectRunnable?.let { webView.removeCallbacks(it) }
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
                // Don't leave this account's login cookie in the shared WebView store for the next
                // WebView (or the next signed-in account) to inherit.
                STORE_COOKIES.forEach {
                    cookieManager.setCookie(STORE, "$it=; path=/; domain=.steampowered.com; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                cookieManager.flush()
            }
        }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun addScript(appId: Int, nonce: String): String =
        """
        (function(){
          try {
            var sid = window.g_sessionID || (document.cookie.match(/sessionid=([^;]+)/) || [])[1] || '';
            fetch('/api/addtowishlist', {
              method: 'POST',
              credentials: 'include',
              headers: {'Content-Type': 'application/x-www-form-urlencoded'},
              body: 'appid=$appId&sessionid=' + encodeURIComponent(sid)
            })
            .then(function(r){ return r.text(); })
            .then(function(t){ AndroidWishlist.onResult('$nonce', t); })
            .catch(function(e){ AndroidWishlist.onResult('$nonce', '{"success":false,"error":"fetch"}'); });
          } catch (e) {
            AndroidWishlist.onResult('$nonce', '{"success":false,"error":"exc"}');
          }
        })();
        """.trimIndent()
}
