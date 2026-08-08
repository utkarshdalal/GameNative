package app.gamenative.html5.host

import java.net.URLDecoder
import java.net.URLEncoder

// stub URL builder + parser. encodes the original worker source URL into
// /_worker_stub?orig=<encoded> so AssetInterceptor synthesizes the correct entry body
// (importScripts pair OR module body with top-level await) for the resolved worker mode.
// pure-jvm -- unit-testable.
object WorkerStubUrl {
    const val PATH = "/_worker_stub"
    const val QUERY_KEY = "orig"

    fun build(originalUrl: String): String {
        val enc = URLEncoder.encode(originalUrl, "UTF-8")
        return "$PATH?$QUERY_KEY=$enc"
    }

    // accepts a query string (no leading ?) e.g. "orig=...&foo=bar". returns the decoded
    // orig URL, or null if missing/malformed.
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
