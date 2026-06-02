package app.gamenative.html5.savesync

// single source of truth for origin shape transforms. chromium stores origin in 3 on-disk
// forms -- IDB filename (`scheme_host_port`), LS ASCII key prefix (full URL), IDB
// DatabaseNameKey UTF-16BE bytes. any drift between these and WebViewOrigin / pack JSON
// pcOrigin is a silent save-sync failure (quick-task #1). keeping all three
// derivations here makes drift structurally impossible.

// WHY NOT java.net.URI: our synthetic hosts (https://game-steam_<appid>) contain
// underscores -- URI rejects as RFC-1035 invalid. simple string split on "://" + ":" works.
object OriginCodec {

    // inverse of filenameFromUrl -- derive URL form from chromium's on-disk `scheme_host_port`
    // filename. used when the resolver discovers an on-disk origin (e.g. Electron's real
    // chrome-extension_<hash>_0) whose URL form the pack JSON didn't declare. hosts may
    // contain underscores (our synthetic `game-steam_<id>`), so we anchor on the FIRST
    // underscore (scheme end) and LAST underscore (port start) rather than splitting blindly.
    
    // "file__0" -> "file://" (empty host + default port)
    // "chrome-extension_anopii..._0" -> "chrome-extension://anopii..."
    // "https_game-steam_2738490_0" -> "https://game-steam_2738490"
    // "https_example_8080" -> "https://example:8080" (non-default port)
    fun urlFromFilename(filename: String): String {
        val firstUs = filename.indexOf('_')
        val lastUs = filename.lastIndexOf('_')
        require(firstUs > 0 && lastUs > firstUs) {
            "bad chromium origin filename (missing scheme/port separators): $filename"
        }
        val scheme = filename.substring(0, firstUs)
        val host = filename.substring(firstUs + 1, lastUs)
        val port = filename.substring(lastUs + 1)
        return if (port == "0") "$scheme://$host" else "$scheme://$host:$port"
    }

    // "https://game-steam_379210" -> "https_game-steam_379210_0"
    // "file://" -> "file__0" (empty authority + default port 0 -> two underscores)
    // "https://example:8080" -> "https_example_8080"
    fun filenameFromUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        require(schemeEnd > 0) { "bad origin URL (missing scheme): $url" }
        val scheme = url.substring(0, schemeEnd)
        // strip at most one trailing slash AFTER the authority (path separator, not protocol slashes)
        val rawAuthority = url.substring(schemeEnd + 3)
        val authority = rawAuthority.trimEnd('/')
        val colonIdx = authority.indexOf(':')
        val (host, portStr) = if (colonIdx >= 0) {
            authority.substring(0, colonIdx) to authority.substring(colonIdx + 1)
        } else {
            authority to "0" // chromium uses port "0" for default/missing
        }
        return "${scheme}_${host}_$portStr"
    }

    // LS keys ("_<url>\0<key>" and "META:<url>") are ASCII. US_ASCII match is safe -- our
    // URLs never include non-ASCII.
    fun asciiKeyOriginFromUrl(url: String): ByteArray =
        url.toByteArray(Charsets.US_ASCII)

    // IDB DatabaseNameKey origin slice is UTF-16BE (per Chromium leveldb_coding_scheme.md).
    // downstream rewriter prepends a varint length (count of UTF-16 code units, NOT bytes).
    fun utf16BePrefixBytes(filename: String): ByteArray =
        filename.toByteArray(Charsets.UTF_16BE)
}
