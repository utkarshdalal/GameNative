package app.gamenative.html5.host

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-JVM tests for WebViewOrigin's URL/host derivation. drift-locks the public contract
// other code paths depend on:
//   - SaveDirectoryResolver derives leveldb origin filenames from levelDbPrefix
//   - WebViewScreen / Html5LocalHttpServer use originUrl + hostFor for AssetLoader setDomain
//   - chromium leveldb files include the host literally → any change in safeIdFor
//     normalization orphans existing saves on upgrade.
class WebViewOriginTest {

    @After fun cleanup() = unmockkAll()

    // ---------------- safeIdFor — RFC 1035 normalization ----------------

    // _ → -, lowercase. covers existing container.id formats: STEAM_<id>, GOG_<id>,
    // EPIC_<id>, AMAZON_<id>, CUSTOM_GAME_<ts>.
    @Test fun safeIdFor_steam_lowercasesAndDashes() {
        assertEquals("steam-2738490", WebViewOrigin.safeIdFor("STEAM_2738490"))
    }

    @Test fun safeIdFor_gog_lowercasesAndDashes() {
        assertEquals("gog-1516178466", WebViewOrigin.safeIdFor("GOG_1516178466"))
    }

    @Test fun safeIdFor_customGame_handlesMultipleUnderscores() {
        assertEquals(
            "custom-game-1712345678",
            WebViewOrigin.safeIdFor("CUSTOM_GAME_1712345678"),
        )
    }

    @Test fun safeIdFor_alreadyLowercaseAlphanumeric_unchanged() {
        assertEquals("abc123", WebViewOrigin.safeIdFor("abc123"))
    }

    // ---------------- hostFor — `<safeId>.localhost` ----------------

    @Test fun hostFor_appendsLocalhostSuffix() {
        assertEquals("steam-2738490.localhost", WebViewOrigin.hostFor("STEAM_2738490"))
    }

    @Test fun hostFor_isInputForAssetLoaderSetDomain() {
        // setDomain accepts hostname only (no port). hostFor must NOT include the port.
        val host = WebViewOrigin.hostFor("STEAM_2738490")
        assertTrue("hostFor must not include port: $host", !host.contains(":"))
    }

    // ---------------- originUrl — http scheme + port ----------------

    @Test fun originUrl_includesSchemeHostAndPort() {
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723
        assertEquals(
            "http://steam-2738490.localhost:5723",
            WebViewOrigin.originUrl("STEAM_2738490"),
        )
    }

    @Test fun originUrl_useshttpScheme_notHttps() {
        // chromium honors `*.localhost` as a secure context even on http (RFC 6761), so we
        // intentionally use http to match the loopback server's actual scheme.
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723
        val url = WebViewOrigin.originUrl("STEAM_2738490")
        assertTrue("originUrl must be http://: $url", url.startsWith("http://"))
        assertTrue("originUrl must not be https://: $url", !url.startsWith("https://"))
    }

    // ---------------- levelDbPrefix — chromium filename encoding ----------------

    // chromium encodes leveldb origin paths as `<scheme>_<host>_<port>`. drift-lock against
    // OriginCodec changes — SaveDirectoryResolver uses this to find IDB dirs on disk.
    @Test fun levelDbPrefix_matchesChromiumFilenameFormat() {
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723
        assertEquals(
            "http_steam-2738490.localhost_5723",
            WebViewOrigin.levelDbPrefix("STEAM_2738490"),
        )
    }

    @Test fun levelDbPrefix_perContainer_distinguishesSiblings() {
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723
        // two containers must produce distinct prefixes — chromium partitions IDB by this.
        val a = WebViewOrigin.levelDbPrefix("STEAM_2738490")
        val b = WebViewOrigin.levelDbPrefix("STEAM_379210")
        assertTrue("siblings must produce distinct leveldb prefixes (a=$a b=$b)", a != b)
    }

    // ---------------- ensurePortAllocated — deterministic port ----------------

    // pre-init() fallback: same applicationId → same port across calls. drift-lock against
    // future hash changes that could orphan saves on upgrade.
    @Test fun ensurePortAllocated_deterministic_acrossCalls() {
        // do NOT mockkObject here — we want the real implementation.
        val first = WebViewOrigin.ensurePortAllocated()
        val second = WebViewOrigin.ensurePortAllocated()
        assertEquals("ensurePortAllocated must be deterministic", first, second)
    }

    @Test fun ensurePortAllocated_returnsDynamicRangePort() {
        // RFC 6335 dynamic/private range: 49152..65535. our deterministic primary must fall
        // inside this band; sentinel-recovered ports also clamp here per wrapToRange.
        val port = WebViewOrigin.ensurePortAllocated()
        assertTrue("port $port must be >= 49152", port >= 49152)
        assertTrue("port $port must be < 65536", port < 65536)
    }
}
