package app.gamenative.html5.host

import android.net.Uri
import android.webkit.WebResourceResponse

// lets Html5LocalHttpServer stream large bodies instead of buffering them. set ONLY on pristine passthrough
// bodies and MUST be exact. PatchApplication.applyServeTime rebuilds transformed responses via the 3-arg
// WebResourceResponse ctor, which drops headers, so the hint auto-clears when the body changes.
internal const val HEADER_CONTENT_LENGTH = "Content-Length"

// runCatching: android.jar's stub throws "Stub!" from responseHeaders in JVM unit tests; never on device.
internal fun WebResourceResponse.withContentLength(length: Long): WebResourceResponse {
    if (length < 0L) return this
    runCatching {
        val headers = responseHeaders?.toMutableMap() ?: LinkedHashMap()
        headers[HEADER_CONTENT_LENGTH] = length.toString()
        responseHeaders = headers
    }
    return this
}

// chromium's media pipeline seeks (Range requests) to read cues from the end of WebM/MP4, and an
// intercepted InputStream isn't seekable. interceptors return null for these so the loopback server,
// which honors Range, serves them.
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
