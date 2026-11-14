package app.gamenative.html5.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileWorkerShimTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decode_defaultIsFalse_whenFieldAbsent() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3"}""")
        assertFalse(p.workerShim)
    }

    @Test fun decode_explicitTrue() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3","workerShim":true}""")
        assertTrue(p.workerShim)
    }

    @Test fun decode_explicitFalse() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:c3","workerShim":false}""")
        assertFalse(p.workerShim)
    }

    @Test fun decode_otherEngineIgnoresField() {
        val p = json.decodeFromString<EngineProfile>("""{"engine":"pack:rmmv","workerShim":true}""")
        assertEquals("pack:rmmv", p.engine)
        // workerShim parses for any engine but resolveShimUrls gates on engine=="pack:c3".
        assertTrue(p.workerShim)
    }

    @Test fun patchOverrides_workerShim_decodes() {
        val p = json.decodeFromString<PatchOverrides>("""{"workerShim":true}""")
        assertTrue(p.workerShim == true)
    }

    @Test fun patchOverrides_workerShim_defaultNull_whenAbsent() {
        // null = inherit from pack default, same as overlay/saves/input.
        val p = json.decodeFromString<PatchOverrides>("""{}""")
        assertEquals(null, p.workerShim)
    }

    @Test fun applyOverrides_workerShim_overridesPackDefault() {
        val pack = EngineProfile(engine = "pack:c3", workerShim = false)
        val overrides = PatchOverrides(workerShim = true)
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertTrue(merged.workerShim)
    }

    @Test fun applyOverrides_workerShim_inheritsWhenNull() {
        val pack = EngineProfile(engine = "pack:c3", workerShim = false)
        val overrides = PatchOverrides(workerShim = null)
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertFalse(merged.workerShim)
    }
}
