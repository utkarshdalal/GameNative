package app.gamenative.html5.savesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginCodecTest {

    @Test
    fun filenameFromUrl_httpsWithAuthority() {
        // must match the IDB leveldb dirname exactly
        assertEquals("https_game-steam_379210_0", OriginCodec.filenameFromUrl("https://game-steam_379210"))
    }

    @Test
    fun filenameFromUrl_fileScheme() {
        // empty authority, no port: TWO underscores, one for "://" and one before the empty host's port
        assertEquals("file__0", OriginCodec.filenameFromUrl("file://"))
    }

    @Test
    fun filenameFromUrl_explicitPort() {
        // no default-port substitution when a port is given
        assertEquals("https_example_8080", OriginCodec.filenameFromUrl("https://example:8080"))
    }

    @Test
    fun filenameFromUrl_trailingSlashTolerated() {
        // chromium normalises "https://game-steam_379210/" to the no-slash form before encoding
        assertEquals("https_game-steam_379210_0", OriginCodec.filenameFromUrl("https://game-steam_379210/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun filenameFromUrl_rejectsMissingScheme() {
        // pack-JSON pcOrigin values always include a scheme; a bare hostname is a bug
        OriginCodec.filenameFromUrl("game-steam_379210")
    }

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
        // our synthetic webview host has an underscore; anchoring on the LAST one keeps it intact
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

    @Test
    fun asciiKeyOriginFromUrl_roundTripsBytes() {
        val url = "https://game-steam_379210"
        val result = OriginCodec.asciiKeyOriginFromUrl(url)
        // LS keys embed the URL byte-for-byte in US_ASCII
        assertEquals(url.length, result.size)
        assertEquals(url, String(result, Charsets.US_ASCII))
    }

    @Test
    fun isAppGeneratedOrigin_matchesOnlyGameNativeOrigins() {
        listOf(
            "http://steam-379210.localhost:59099",
            "https://game-steam_379210",
            "https://termina-608a.app.local",
        ).forEach { assertTrue(it, OriginCodec.isAppGeneratedOrigin(it)) }
        // PC-form and third-party origins must never be classified as ours -- outbound would
        // purge them from the Wine copy
        listOf(
            "file://",
            "chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda",
            "https://login.gog.com",
            "https://www.recaptcha.net",
            "http://localhost:8080",
        ).forEach { assertFalse(it, OriginCodec.isAppGeneratedOrigin(it)) }
    }

    @Test
    fun isLocalhostOrigin_requiresExactPort() {
        assertTrue(OriginCodec.isLocalhostOrigin("http://steam-1.localhost:59099", 59099))
        assertFalse(OriginCodec.isLocalhostOrigin("http://steam-1.localhost:590990", 59099))
        assertFalse(OriginCodec.isLocalhostOrigin("http://steam-1.localhost:5909", 59099))
        assertFalse(OriginCodec.isLocalhostOrigin("https://steam-1.localhost:59099", 59099))
    }

    @Test
    fun nwjsAppOrigin_matchesRealPcProfiles() {
        // ids read off real desktop NW.js profiles (IndexedDB dir name / Web Applications/_nwjs_<id>)
        assertEquals("chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda", OriginCodec.nwjsAppOrigin("CrossCode"))
        assertEquals("chrome-extension://anopiimlkmdoenonenclohfilpeenfmj", OriginCodec.nwjsAppOrigin("SolCesto"))
    }

    @Test
    fun utf16BePrefixBytes_fileFilenameYields14Bytes() {
        // DatabaseNameKey origin slice is UTF-16BE: 2 bytes per code unit
        val result = OriginCodec.utf16BePrefixBytes("file__0")
        assertEquals(14, result.size)
    }

    @Test
    fun utf16BePrefixBytes_firstByteIsZeroHighByte() {
        // the IDB comparator matches these bytes verbatim, so BE vs LE matters
        val result = OriginCodec.utf16BePrefixBytes("file__0")
        for (i in result.indices step 2) {
            assertEquals("byte[$i] must be 0x00 high byte for ASCII", 0x00.toByte(), result[i])
        }
    }

    @Test
    fun utf16BePrefixBytes_matchesExpectedBytesForWayward() {
        val result = OriginCodec.utf16BePrefixBytes("https_game-steam_379210_0")
        assertEquals(50, result.size)
        assertEquals(0x00.toByte(), result[0])
        assertEquals('h'.code.toByte(), result[1])
    }

    @Test
    fun filenameFromUrl_longOrigin_underVarintBoundary() {
        // < 128 code units: a 1-byte varint downstream. OriginCodec itself is varint-agnostic.
        val url = "https://" + "a".repeat(100)
        val result = OriginCodec.filenameFromUrl(url)
        assertEquals(108, result.length)
    }

    @Test
    fun filenameFromUrl_longOrigin_overVarintBoundary() {
        // > 128 code units: LevelDbRewriter owns the 2-byte varint re-encode, the codec just produces the name
        val url = "https://" + "a".repeat(130)
        val result = OriginCodec.filenameFromUrl(url)
        assertTrue(result.length >= 130)
    }
}
