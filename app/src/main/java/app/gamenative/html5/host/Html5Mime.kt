package app.gamenative.html5.host

import android.net.Uri
import android.webkit.WebResourceResponse

// Content-Length hint for the loopback server. set ONLY on pristine passthrough bodies whose
// byte length is known cheaply (zip entry.size, File.length(), asar bytes.size). lets
// Html5LocalHttpServer stream large media bodies sequentially instead of buffering a whole
// cutscene in memory (see Html5LocalHttpServer.writeResponse). MUST be exact: a serve-time
// transform that changes the body size invalidates it -- PatchApplication.applyServeTime rebuilds
// every transform via the 3-arg WebResourceResponse ctor, which drops responseHeaders, so the
// hint auto-clears for decrypt / body-replace. callers set it after lookup, before applyServeTime.
internal const val HEADER_CONTENT_LENGTH = "Content-Length"

// attach the size hint, preserving any headers already set. negative length = no-op (unknown).
// runCatching guards the pure-JVM unit-test path: android.jar's WebResourceResponse stub throws
// RuntimeException("Stub!") from the responseHeaders getter/setter. on a real device these never
// throw, so this only no-ops under the stub (callers' runCatching would otherwise null the body).
internal fun WebResourceResponse.withContentLength(length: Long): WebResourceResponse {
    if (length < 0L) return this
    runCatching {
        val headers = responseHeaders?.toMutableMap() ?: LinkedHashMap()
        headers[HEADER_CONTENT_LENGTH] = length.toString()
        responseHeaders = headers
    }
    return this
}

// internal (not private) so WebViewScreenLifecycleTest in the same package can assert
// mime mappings without reflection. single source of truth -- do not redeclare elsewhere.
// chromium's media pipeline reads Cues from the END of WebM/MP4 containers via Range
// requests; a WebResourceResponse-wrapped InputStream from shouldInterceptRequest isn't
// seekable so the FFmpeg demuxer's seek fails. interceptors return null for these URLs
// → WebView falls back to the loopback HTTP server which serves with Accept-Ranges +
// Content-Length (Html5LocalHttpServer.writeResponse honors Range header).
internal fun isMediaUrl(uri: Uri): Boolean {
    val path = (uri.path ?: return false).lowercase()
    return path.endsWith(".webm") || path.endsWith(".mp4") || path.endsWith(".ogv") ||
        path.endsWith(".mov") || path.endsWith(".m4v") ||
        path.endsWith(".mp3") || path.endsWith(".ogg") || path.endsWith(".wav") ||
        path.endsWith(".m4a") || path.endsWith(".flac") || path.endsWith(".aac")
}

internal fun mimeFor(name: String): String = when {
    name.endsWith(".html", ignoreCase = true) -> "text/html"
    name.endsWith(".htm", ignoreCase = true) -> "text/html"
    name.endsWith(".js", ignoreCase = true) -> "application/javascript"
    name.endsWith(".css", ignoreCase = true) -> "text/css"
    name.endsWith(".json", ignoreCase = true) -> "application/json"
    name.endsWith(".png", ignoreCase = true) -> "image/png"
    name.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
    name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
    name.endsWith(".gif", ignoreCase = true) -> "image/gif"
    name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
    name.endsWith(".webp", ignoreCase = true) -> "image/webp"
    name.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
    name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
    name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
    name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
    name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
    name.endsWith(".webm", ignoreCase = true) -> "video/webm"
    name.endsWith(".ogv", ignoreCase = true) -> "video/ogg"
    name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
    name.endsWith(".woff", ignoreCase = true) -> "font/woff"
    name.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
    name.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
    // WebAssembly.instantiateStreaming REQUIRES application/wasm -- octet-stream path throws.
    name.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
    else -> "application/octet-stream"
}
