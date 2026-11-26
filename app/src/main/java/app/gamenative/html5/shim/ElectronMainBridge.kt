package app.gamenative.html5.shim

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// session.cookies for an electron main script running in the page (electron-main.js). the jar is the WebView's,
// which the page shares; HttpOnly cookies are out of page js's reach, hence the host side. only urls on hosts the
// app's main serves itself are answered.
class ElectronMainBridge(
    private val allowsHost: (String) -> Boolean,
    private val jar: Jar = WebViewJar,
) {
    interface Jar {
        fun get(url: String): String?
        fun set(url: String, cookie: String)
        fun flush()
    }

    object WebViewJar : Jar {
        override fun get(url: String): String? = CookieManager.getInstance().getCookie(url)
        override fun set(url: String, cookie: String) {
            CookieManager.getInstance().setCookie(url, cookie)
        }
        override fun flush() = CookieManager.getInstance().flush()
    }

    private fun allowed(url: String): Uri? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true) || !allowsHost(host)) {
            Timber.tag(TAG).w("cookie access refused for %s", url)
            return null
        }
        return uri
    }

    // electron Cookie objects; the jar only exposes name and value.
    @JavascriptInterface
    fun getCookies(url: String): String {
        val uri = allowed(url) ?: return "[]"
        val out = JSONArray()
        parseCookieHeader(jar.get(url)).forEach { (name, value) ->
            out.put(JSONObject().put("name", name).put("value", value).put("domain", uri.host).put("path", "/"))
        }
        return out.toString()
    }

    @JavascriptInterface
    fun setCookie(url: String, detailsJson: String) {
        allowed(url) ?: return
        val d = runCatching { JSONObject(detailsJson) }.getOrNull() ?: return
        val name = d.optString("name").takeIf { it.isNotEmpty() } ?: return
        jar.set(url, buildSetCookie(name, d.optString("value"), d))
    }

    // the cookie's attributes aren't readable here, so expire both the host-only and the domain form.
    @JavascriptInterface
    fun removeCookie(url: String, name: String) {
        val uri = allowed(url) ?: return
        if (name.isEmpty()) return
        jar.set(url, "$name=; Max-Age=0; Path=/")
        jar.set(url, "$name=; Max-Age=0; Path=/; Domain=${uri.host}")
    }

    @JavascriptInterface
    fun flushCookies() {
        runCatching { jar.flush() }
    }

    companion object {
        private const val TAG = "ElectronMainBridge"

        internal fun parseCookieHeader(header: String?): List<Pair<String, String>> =
            header.orEmpty().split(';').mapNotNull { part ->
                val p = part.trim()
                if (p.isEmpty()) return@mapNotNull null
                val eq = p.indexOf('=')
                if (eq <= 0) null else p.substring(0, eq).trim() to p.substring(eq + 1).trim()
            }

        internal fun buildSetCookie(name: String, value: String, d: JSONObject): String = buildString {
            append(name).append('=').append(value)
            append("; Path=").append(d.optString("path").ifEmpty { "/" })
            d.optString("domain").takeIf { it.isNotEmpty() }?.let { append("; Domain=").append(it) }
            if (d.optBoolean("secure")) append("; Secure")
            if (d.optBoolean("httpOnly")) append("; HttpOnly")
            if (d.has("expirationDate")) append("; Max-Age=").append(
                maxOf(0L, d.optDouble("expirationDate").toLong() - System.currentTimeMillis() / 1000),
            )
        }
    }
}
