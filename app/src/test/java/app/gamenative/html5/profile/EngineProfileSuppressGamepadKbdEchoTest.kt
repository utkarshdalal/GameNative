package app.gamenative.html5.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// chromium's native KeyEvent->DOM auto-dispatch is suppressed for every html5 container by
// default; packs that genuinely want it must set the field to false explicitly.
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
        // pack code may construct EngineProfile() directly, so the default param must match
        // the JSON-decode default.
        val p = EngineProfile()
        assertTrue(p.suppressGamepadKbdEcho)
    }
}
