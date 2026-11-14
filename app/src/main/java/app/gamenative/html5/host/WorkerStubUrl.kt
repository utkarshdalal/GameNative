package app.gamenative.html5.host

import java.net.URLDecoder
import java.net.URLEncoder

// carries the original worker URL so AssetInterceptor can synthesize the entry body for the worker mode.
object WorkerStubUrl {
    const val PATH = "/_worker_stub"
    const val QUERY_KEY = "orig"

    fun build(originalUrl: String): String {
        val enc = URLEncoder.encode(originalUrl, "UTF-8")
        return "$PATH?$QUERY_KEY=$enc"
    }

    // query without the leading '?'.
    fun parseOrig(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val key = "$QUERY_KEY="
        val idx = query.indexOf(key)
        if (idx < 0) return null
        val rest = query.substring(idx + key.length)
        val end = rest.indexOf('&').let { if (it < 0) rest.length else it }
        return runCatching { URLDecoder.decode(rest.substring(0, end), "UTF-8") }.getOrNull()
    }
}
