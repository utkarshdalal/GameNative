package app.gamenative.html5.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pin: desktopUaSpoof default-on at the field level (every html5 pack inherits a coherent
// Windows-desktop navigator fiction). per-title opt-out via patches.json byAppId.
// pure-jvm: kotlinx.serialization decode + applyOverrides merge only.
class EngineProfileDesktopUaSpoofTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decode_defaultIsTrue_whenFieldAbsent() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:nwjs"}""")
        assertTrue(p.desktopUaSpoof)
    }

    @Test fun decode_explicitTrue_matchesDefault() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:nwjs","desktopUaSpoof":true}""")
        assertTrue(p.desktopUaSpoof)
    }

    @Test fun decode_explicitFalse() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:nwjs","desktopUaSpoof":false}""")
        assertFalse(p.desktopUaSpoof)
    }

    @Test fun direct_construction_default_isTrue() {
        // pack code may construct EngineProfile() directly. default param must match JSON-decode.
        val p = EngineProfile()
        assertTrue(p.desktopUaSpoof)
    }

    @Test fun patchOverrides_desktopUaSpoof_decodes() {
        val p = json.decodeFromString<PatchOverrides>("""{"desktopUaSpoof":true}""")
        assertEquals(true, p.desktopUaSpoof)
    }

    @Test fun patchOverrides_desktopUaSpoof_defaultNull_whenAbsent() {
        // null sentinel = inherit from pack default. matches workerShim/overlay/saves shape.
        val p = json.decodeFromString<PatchOverrides>("""{}""")
        assertNull(p.desktopUaSpoof)
    }

    @Test fun applyOverrides_desktopUaSpoof_overridesPackDefault() {
        val pack = EngineProfile(engine = "pack:nwjs", desktopUaSpoof = false)
        val overrides = PatchOverrides(desktopUaSpoof = true)
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertTrue(merged.desktopUaSpoof)
    }

    @Test fun applyOverrides_desktopUaSpoof_inheritsWhenNull() {
        val pack = EngineProfile(engine = "pack:nwjs", desktopUaSpoof = true)
        val overrides = PatchOverrides(desktopUaSpoof = null)
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertTrue(merged.desktopUaSpoof)
    }

    @Test fun applyOverrides_desktopUaSpoof_explicitFalse_overridesPackTrue() {
        val pack = EngineProfile(engine = "pack:nwjs", desktopUaSpoof = true)
        val overrides = PatchOverrides(desktopUaSpoof = false)
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertFalse(merged.desktopUaSpoof)
    }
}
