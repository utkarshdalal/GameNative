package app.gamenative.html5.profile

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchOverridesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun empty_overrides_leave_pack_unchanged() {
        val pack = packRmmv()
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides())
        assertEquals(pack, merged)
    }

    @Test fun patches_concat_pack_first() {
        val pack = packRmmv()
        val overrides = PatchOverrides(
            patches = listOf(Patch.UrlPathRedirect(from = "/a", to = "/b")),
        )
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertEquals(pack.patches.size + 1, merged.patches.size)
        assertEquals(pack.patches[0], merged.patches.first())
        assertTrue(merged.patches.last() is Patch.UrlPathRedirect)
    }

    @Test fun shims_concat_pack_first() {
        val pack = packRmmv()
        val overrides = PatchOverrides(shims = listOf("title-fix"))
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertEquals(pack.shims + listOf("title-fix"), merged.shims)
    }

    @Test fun gamepadKeySynthesisMap_replaces_when_nonNull() {
        val pack = packRmmv().copy(gamepadKeySynthesisMap = mapOf("GAMEPAD_BUTTON_A" to "KEY_Z"))
        val overrides = PatchOverrides(gamepadKeySynthesisMap = mapOf("GAMEPAD_BUTTON_A" to "KEY_SPACE"))
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertEquals(mapOf("GAMEPAD_BUTTON_A" to "KEY_SPACE"), merged.gamepadKeySynthesisMap)
    }

    @Test fun gamepadKeySynthesisMap_inherits_when_null() {
        val pack = packRmmv().copy(gamepadKeySynthesisMap = mapOf("GAMEPAD_BUTTON_A" to "KEY_Z"))
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides())
        assertEquals(mapOf("GAMEPAD_BUTTON_A" to "KEY_Z"), merged.gamepadKeySynthesisMap)
    }

    @Test fun overlay_replaces_when_nonNull() {
        val pack = packRmmv().copy(overlay = "rmmv-default-overlay")
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides(overlay = "title-overlay"))
        assertEquals("title-overlay", merged.overlay)
    }

    @Test fun overlay_inherits_when_null() {
        val pack = packRmmv().copy(overlay = "rmmv-default-overlay")
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides())
        assertEquals("rmmv-default-overlay", merged.overlay)
    }

    @Test fun saves_replaces_when_nonNull() {
        val pack = packRmmv().copy(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "leveldb-origin-rewrite")))
        val titleSaves = SaveSpec(sync = SaveSyncSpec(mechanism = "fsbridge"))
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides(saves = titleSaves))
        assertEquals(titleSaves, merged.saves)
    }

    @Test fun input_replaces_when_nonNull() {
        val pack = packRmmv().copy(input = InputSpec(mode = "pointer-with-tap-detection"))
        val titleInput = InputSpec(mode = "native-controller")
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides(input = titleInput))
        assertEquals(titleInput, merged.input)
    }

    @Test fun engine_entryPoint_NOT_overridable() {
        // PatchOverrides has no engine/entryPoint constructor params. applyOverrides
        // copies them from pack regardless.
        val pack = packRmmv().copy(engine = "pack:rmmv", entryPoint = "custom.html")
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides(shims = listOf("anything")))
        assertEquals("pack:rmmv", merged.engine)
        assertEquals("custom.html", merged.entryPoint)
    }

    @Test fun patchRegistry_parses_steam_and_custom_keys() {
        val body = """
            {"byAppId":{
               "STEAM_12345":{"shims":["fix-a"]},
               "CUSTOM_GAME_42":{"patches":[{"type":"url-redirect","from":"/x","to":"/y"}]}
             }}
        """.trimIndent()
        val registry = json.decodeFromString<PatchRegistry>(body)
        assertEquals(2, registry.byAppId.size)
        assertEquals(listOf("fix-a"), registry.byAppId["STEAM_12345"]!!.shims)
        val custom = registry.byAppId["CUSTOM_GAME_42"]!!
        assertEquals(1, custom.patches.size)
        assertTrue(custom.patches[0] is Patch.UrlPathRedirect)
    }

    private fun packRmmv() = EngineProfile(
        engine = "pack:rmmv",
        entryPoint = "index.html",
        patches = listOf(Patch.AudioExtensionRemap(".rpgmvo", ".ogg")),
        shims = listOf("steamworks-noop", "pack-rmmv"),
    )
}
