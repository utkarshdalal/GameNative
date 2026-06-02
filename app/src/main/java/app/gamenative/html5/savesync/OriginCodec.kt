package app.gamenative.html5.savesync

// chromium stores an origin in 3 on-disk forms -- IDB filename (`scheme_host_port`), LS ASCII key
// prefix (full URL), IDB DatabaseNameKey UTF-16BE. any drift between them is a SILENT save-sync
// failure, so every derivation lives here.
//
// not java.net.URI: synthetic hosts like `game-steam_<appid>` contain underscores, which URI rejects.
object OriginCodec {

    // hosts may contain underscores, so anchor on the FIRST (scheme end) and LAST (port start) one.
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

    // LS keys ("_<url>\0<key>" and "META:<url>") are ASCII; our origin URLs never contain non-ASCII.
    fun asciiKeyOriginFromUrl(url: String): ByteArray =
        url.toByteArray(Charsets.US_ASCII)

    // origins only GameNative's WebView ever runs games under: the loopback
    // `http://<label>.localhost:<port>` plus the retired `https://game-*` and `*.app.local`
    // schemes. none of these exist on a PC, so a Wine-side copy holding them is a leak.
    fun isAppGeneratedOrigin(url: String): Boolean =
        LOCALHOST_ORIGIN.matches(url) || LEGACY_GAME_ORIGIN.matches(url) || APP_LOCAL_ORIGIN.matches(url)

    // chromium extension id NW.js gives an app: sha256(manifest name), first 32 hex digits mapped
    // 0-f -> a-p. install-path independent, so every PC install of a title shares this origin.
    fun nwjsAppOrigin(manifestName: String): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(manifestName.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return "chrome-extension://" + hex.map { 'a' + it.digitToInt(16) }.joinToString("")
    }

    fun isLocalhostOrigin(url: String, port: Int): Boolean =
        LOCALHOST_ORIGIN.matchEntire(url)?.groupValues?.get(1) == port.toString()

    private val LOCALHOST_ORIGIN = Regex("^http://[a-z0-9-]+\\.localhost:(\\d+)$")
    private val LEGACY_GAME_ORIGIN = Regex("^https://game-[^/:]+$")
    private val APP_LOCAL_ORIGIN = Regex("^https?://[^/:]+\\.app\\.local(:\\d+)?$")

    // per chromium leveldb_coding_scheme.md. callers prefix a varint length in UTF-16 code units, NOT bytes.
    fun utf16BePrefixBytes(filename: String): ByteArray =
        filename.toByteArray(Charsets.UTF_16BE)
}
