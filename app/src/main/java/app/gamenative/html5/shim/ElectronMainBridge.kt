package app.gamenative.html5.shim

import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import app.gamenative.html5.asar.AppSignInHosts
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// session.cookies for an electron main script running in the page (electron-main.js). the jar is the one of the
// page's own WebView profile (Html5PageProfiles), or the default one behind SignInGuardJar where WebView has no
// profiles; HttpOnly cookies are out of page js's reach, hence the host side. only urls on hosts the app's main
// serves itself are answered.
class ElectronMainBridge(
    private val allowsHost: (String) -> Boolean,
    private val jar: Jar,
) {
    interface Jar {
        fun get(url: String): String?
        fun set(url: String, cookie: String)
        fun flush()
    }

    class ProfileJar(private val cookies: CookieManager) : Jar {
        override fun get(url: String): String? = cookies.getCookie(url)
        override fun set(url: String, cookie: String) {
            cookies.setCookie(url, cookie)
        }
        override fun flush() = cookies.flush()
    }

    // the default profile also holds the app's store sign-ins: their hosts are never read or written through it.
    class SignInGuardJar(private val inner: Jar, private val refuses: (String) -> Boolean = AppSignInHosts::covers) : Jar {
        private fun refused(url: String): Boolean {
            val host = runCatching { URI(url).host }.getOrNull()
            return host == null || refuses(host.lowercase())
        }
        override fun get(url: String): String? = if (refused(url)) null else inner.get(url)
        override fun set(url: String, cookie: String) {
            if (!refused(url)) inner.set(url, cookie)
        }
        override fun flush() = inner.flush()
    }

    // what the jar is asked for: https, an allowed host, the path; nothing else from the page's string.
    private class Target(val host: String, val url: String)

    private fun allowed(url: String): Target? {
        val target = parseTarget(url, allowsHost)
        if (target == null) Timber.tag(TAG).w("cookie access refused for %s", url)
        return target
    }

    // electron Cookie objects; the jar only exposes name and value.
    @JavascriptInterface
    fun getCookies(url: String): String {
        val t = allowed(url) ?: return "[]"
        val out = JSONArray()
        parseCookieHeader(jar.get(t.url)).forEach { (name, value) ->
            out.put(JSONObject().put("name", name).put("value", value).put("domain", t.host).put("path", "/"))
        }
        return out.toString()
    }

    @JavascriptInterface
    fun setCookie(url: String, detailsJson: String) {
        val t = allowed(url) ?: return
        val d = runCatching { JSONObject(detailsJson) }.getOrNull() ?: return
        val name = d.optString("name").takeIf { it.isNotEmpty() } ?: return
        jar.set(t.url, buildSetCookie(name, d.optString("value"), d))
    }

    // the cookie's attributes aren't readable here, so expire both the host-only and the domain form.
    @JavascriptInterface
    fun removeCookie(url: String, name: String) {
        val t = allowed(url) ?: return
        if (name.isEmpty()) return
        expire(jar, t.url, t.host, name)
    }

    @JavascriptInterface
    fun flushCookies() {
        runCatching { jar.flush() }
    }

    companion object {
        private const val TAG = "ElectronMainBridge"

        private fun expire(jar: Jar, url: String, host: String, name: String) {
            jar.set(url, "$name=; Max-Age=0; Path=/")
            jar.set(url, "$name=; Max-Age=0; Path=/; Domain=$host")
        }

        // every cookie the jar sends to https://<host>/, expired; the count of names.
        internal fun expireAll(jar: Jar, host: String): Int {
            val url = "https://$host/"
            val names = parseCookieHeader(jar.get(url)).map { it.first }.distinct()
            names.forEach { expire(jar, url, host, it) }
            return names.size
        }

        // "https://<host><path>" for an https url on an allowed host, else null.
        internal fun targetUrl(url: String, allowsHost: (String) -> Boolean): String? = parseTarget(url, allowsHost)?.url

        private fun parseTarget(url: String, allowsHost: (String) -> Boolean): Target? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (!allowsHost(host)) return null
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: "/"
            return Target(host, "https://$host$port$path")
        }

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
