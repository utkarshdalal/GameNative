package app.gamenative.theme

import app.gamenative.utils.Net
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * HTTPS GETs for [RemoteFonts]: redirects are followed only to HTTPS, bodies are capped, timeouts are short and the
 * request carries a generic user agent and nothing else about the user or device.
 */
object RemoteFontFetcher : RemoteFonts.Fetcher {

    /** A browser-like agent that Google Fonts answers with TrueType files (a current browser's agent gets WOFF2). */
    private const val USER_AGENT = "Mozilla/5.0"
    private const val MAX_REDIRECTS = 3

    private val client by lazy {
        Net.http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun get(url: String, maxBytes: Long): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!RemoteFonts.isHttps(current)) throw IOException("Not an https url: $current")
            val target = current.toHttpUrlOrNull() ?: throw IOException("Bad url: $current")
            val request = Request.Builder().url(target).header("User-Agent", USER_AGENT).build()
            val next = client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: throw IOException("Redirect without a location from $current")
                } else {
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $current")
                    val body = response.body ?: throw IOException("Empty response for $current")
                    if (body.contentLength() > maxBytes) throw IOException("$current is larger than $maxBytes bytes")
                    return body.byteStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("$current is larger than $maxBytes bytes")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }
            }
            current = next
        }
        throw IOException("Too many redirects for $url")
    }
}
