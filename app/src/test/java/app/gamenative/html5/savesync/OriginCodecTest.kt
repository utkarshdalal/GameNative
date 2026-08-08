package app.gamenative.html5.savesync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// covers all three OriginCodec transforms + varint-boundary documentation. pure-JVM —
// no android deps, no Robolectric. each test documents WHY the specific case matters.
class OriginCodecTest {

    // --- filenameFromUrl ---

    @Test
    fun filenameFromUrl_httpsWithAuthority() {
        // canonical pack:electron/c3 WebView origin → must match the IDB leveldb dirname exactly
        assertEquals("https_game-steam_379210_0", OriginCodec.filenameFromUrl("https://game-steam_379210"))
    }

    @Test
    fun filenameFromUrl_fileScheme() {
        // file:// has empty authority ("") and no explicit port → chromium encodes as "file__0"
        // TWO underscores: one for "://", one for empty authority separator. Destination form on Wine side.
        assertEquals("file__0", OriginCodec.filenameFromUrl("file://"))
    }

    @Test
    fun filenameFromUrl_explicitPort() {
        // explicit port preserved verbatim — no default substitution when colon present
        assertEquals("https_example_8080", OriginCodec.filenameFromUrl("https://example:8080"))
    }

    @Test
    fun filenameFromUrl_trailingSlashTolerated() {
        // chromium normalises "https://game-steam_379210/" to the no-slash form before encoding
        assertEquals("https_game-steam_379210_0", OriginCodec.filenameFromUrl("https://game-steam_379210/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun filenameFromUrl_rejectsMissingScheme() {
        // callers pass pack-JSON pcOrigin values which always include scheme. bare hostnames are bugs.
        OriginCodec.filenameFromUrl("game-steam_379210")
    }

    // --- urlFromFilename (inverse) ---

    @Test
    fun urlFromFilename_fileEmptyHostDefaultPort() {
        assertEquals("file://", OriginCodec.urlFromFilename("file__0"))
    }

    @Test
    fun urlFromFilename_chromeExtensionHash() {
        assertEquals(
            "chrome-extension://anopiimlkmdoenonenclohfilpeenfmj",
            OriginCodec.urlFromFilename("chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0"),
        )
    }

    @Test
    fun urlFromFilename_httpsHostWithUnderscore() {
        // our synthetic webview host has an underscore — last-underscore anchor keeps it intact.
        assertEquals(
            "https://game-steam_2738490",
            OriginCodec.urlFromFilename("https_game-steam_2738490_0"),
        )
    }

    @Test
    fun urlFromFilename_explicitPortRetained() {
        assertEquals("https://example:8080", OriginCodec.urlFromFilename("https_example_8080"))
    }

    @Test
    fun urlFromFilename_roundTripsFilenameFromUrl() {
        val urls = listOf(
            "file://",
            "https://game-steam_2738490",
            "chrome-extension://anopiimlkmdoenonenclohfilpeenfmj",
            "https://example:8080",
        )
        for (url in urls) {
            val round = OriginCodec.urlFromFilename(OriginCodec.filenameFromUrl(url))
            assertEquals("round-trip failed for $url", url, round)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun urlFromFilename_rejectsSingleUnderscore() {
        OriginCodec.urlFromFilename("file_")
    }

    // --- asciiKeyOriginFromUrl ---

    @Test
    fun asciiKeyOriginFromUrl_roundTripsBytes() {
        val url = "https://game-steam_379210"
        val result = OriginCodec.asciiKeyOriginFromUrl(url)
        // LS keys embed the URL byte-for-byte in US_ASCII — round-trip confirms encoding correctness
        assertEquals(url.length, result.size)
        assertEquals(url, String(result, Charsets.US_ASCII))
    }

    // --- utf16BePrefixBytes ---

    @Test
    fun utf16BePrefixBytes_fileFilenameYields14Bytes() {
        // "file__0" = 7 UTF-16 code units. DatabaseNameKey origin slice is UTF-16BE → 14 bytes.
        val result = OriginCodec.utf16BePrefixBytes("file__0")
        assertEquals(14, result.size)
    }

    @Test
    fun utf16BePrefixBytes_firstByteIsZeroHighByte() {
        // ASCII chars have a 0x00 high byte in UTF-16BE. even-indexed bytes MUST be 0x00 for
        // any ASCII-only filename. IDB comparator matches these bytes verbatim.
        val result = OriginCodec.utf16BePrefixBytes("file__0")
        for (i in result.indices step 2) {
            assertEquals("byte[$i] must be 0x00 high byte for ASCII", 0x00.toByte(), result[i])
        }
    }

    @Test
    fun utf16BePrefixBytes_matchesExpectedBytesForWayward() {
        // spot-check the Wayward origin filename: 25 chars → 50 bytes; first two bytes [0x00, 'h']
        val result = OriginCodec.utf16BePrefixBytes("https_game-steam_379210_0")
        assertEquals(50, result.size)
        assertEquals(0x00.toByte(), result[0])
        assertEquals('h'.code.toByte(), result[1])
    }

    // --- varint-boundary documentation ---

    @Test
    fun filenameFromUrl_longOrigin_underVarintBoundary() {
        // 100-char host → origin filename is 100 chars (< 128 code units). downstream IDB rewriter
        // encodes this as a 1-byte varint. OriginCodec itself is varint-agnostic — test documents
        // structural coverage, assertion = no exception + correct length.
        val url = "https://" + "a".repeat(100)
        val result = OriginCodec.filenameFromUrl(url)
        // scheme_host_port: "https_" + 100 "a"s + "_0" = 108 chars
        assertEquals(108, result.length)
    }

    @Test
    fun filenameFromUrl_longOrigin_overVarintBoundary() {
        // 130-char host → filename > 128 code units. downstream IDB rewriter needs a 2-byte
        // varint. OriginCodec makes no varint assumption — test proves codec produces the filename;
        // caller (LevelDbRewriter) is responsible for varint re-encoding 
        val url = "https://" + "a".repeat(130)
        val result = OriginCodec.filenameFromUrl(url)
        assertTrue(result.length >= 130)
    }
}
