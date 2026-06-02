package app.gamenative.html5.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Refactor pin: suppressGamepadKbdEcho default-true (chromium's native KeyEvent→DOM
// auto-dispatch is suppressed for every html5 container by default). Pack JSONs that
// genuinely want the auto-dispatch must explicitly set the field to false.
class EngineProfileSuppressGamepadKbdEchoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decode_defaultIsTrue_whenFieldAbsent() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3"}""")
        assertTrue(p.suppressGamepadKbdEcho)
    }

    @Test fun decode_explicitFalse() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3","suppressGamepadKbdEcho":false}""")
        assertFalse(p.suppressGamepadKbdEcho)
    }

    @Test fun decode_explicitTrue_matchesDefault() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3","suppressGamepadKbdEcho":true}""")
        assertTrue(p.suppressGamepadKbdEcho)
    }

    @Test fun direct_construction_default_isTrue() {
        // pack code may construct EngineProfile() directly (test fixtures, defaults).
        // default param must match JSON-decode default.
        val p = EngineProfile()
        assertTrue(p.suppressGamepadKbdEcho)
    }
}
