package app.gamenative.html5.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// locks ProfileRegistry.applyOverrides merge semantics. each field has a documented rule:
//   patches / shims: concat (pack first, override appended)
//   gamepadKeySynthesisMap / overlay / saves / input: non-null replace, null = inherit
//   workerShim / desktopUaSpoof / fsBridgeOnly: nullable boolean — non-null wins, null inherits
// adding the 10th override field is a one-line schema change; this test guards against
// silent drift between PatchOverrides.kt fields and ProfileRegistry.applyOverrides.
class ProfileRegistryApplyOverridesTest {

    private fun packDefault() = EngineProfile(
        engine = "pack:rmmv",
        entryPoint = "index.html",
        patches = listOf(Patch.AssetDecrypt(kind = "rpgmv-xor")),
        input = InputSpec(mode = "native-controller"),
        saves = SaveSpec(),
        shims = listOf("nw-noop"),
        overlay = "rmmv-default-overlay",
        gamepadKeySynthesisMap = mapOf("GAMEPAD_BUTTON_START" to "KEY_X"),
        workerShim = false,
        desktopUaSpoof = true,
        fsBridgeOnly = true,
    )

    @Test
    fun applyOverrides_emptyOverrides_returnsPackEquivalent() {
        val pack = packDefault()
        val merged = ProfileRegistry.applyOverrides(pack, PatchOverrides())
        // engine / entryPoint never go through the merge — they're pack-defining.
        assertEquals(pack.engine, merged.engine)
        assertEquals(pack.entryPoint, merged.entryPoint)
        // null overrides inherit the pack values.
        assertEquals(pack.gamepadKeySynthesisMap, merged.gamepadKeySynthesisMap)
        assertEquals(pack.overlay, merged.overlay)
        assertSame(pack.saves, merged.saves)
        assertSame(pack.input, merged.input)
        assertEquals(pack.workerShim, merged.workerShim)
        assertEquals(pack.desktopUaSpoof, merged.desktopUaSpoof)
        assertEquals(pack.fsBridgeOnly, merged.fsBridgeOnly)
        // empty list overrides concat to the pack's lists unchanged.
        assertEquals(pack.patches, merged.patches)
        assertEquals(pack.shims, merged.shims)
    }

    @Test
    fun applyOverrides_patchesAndShims_concatAppend() {
        val pack = packDefault()
        val extraPatch = Patch.AssetDecrypt(kind = "omori-aes-ctr")
        val overrides = PatchOverrides(
            patches = listOf(extraPatch),
            shims = listOf("worker-install"),
        )
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        // pack-first ordering matters: shim/patch chains rely on pack defaults running
        // BEFORE per-title overrides.
        assertEquals(pack.patches + extraPatch, merged.patches)
        assertEquals(listOf("nw-noop", "worker-install"), merged.shims)
    }

    @Test
    fun applyOverrides_nullableBooleans_nonNullReplaces_nullInherits() {
        val pack = packDefault().copy(workerShim = false, desktopUaSpoof = true, fsBridgeOnly = true)
        val overrides = PatchOverrides(
            workerShim = true,
            desktopUaSpoof = null,
            fsBridgeOnly = false,
        )
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertEquals(true, merged.workerShim)
        assertEquals(true, merged.desktopUaSpoof)
        assertEquals(false, merged.fsBridgeOnly)
    }

    @Test
    fun applyOverrides_referenceFields_nonNullReplaces() {
        val pack = packDefault()
        val newOverlay = "rmmv-custom-overlay"
        val newGamepadMap = mapOf("GAMEPAD_BUTTON_SELECT" to "KEY_SHIFT_L")
        val newSaves = SaveSpec()
        val newInput = InputSpec(mode = "pointer-with-tap-detection")
        val overrides = PatchOverrides(
            overlay = newOverlay,
            gamepadKeySynthesisMap = newGamepadMap,
            saves = newSaves,
            input = newInput,
        )
        val merged = ProfileRegistry.applyOverrides(pack, overrides)
        assertEquals(newOverlay, merged.overlay)
        assertEquals(newGamepadMap, merged.gamepadKeySynthesisMap)
        assertSame(newSaves, merged.saves)
        assertSame(newInput, merged.input)
        // pack-defining fields are untouched even when overrides land.
        assertEquals(pack.engine, merged.engine)
        assertEquals(pack.entryPoint, merged.entryPoint)
    }

    @Test
    fun applyOverrides_doesNotMutatePackInstance() {
        val pack = packDefault()
        val originalPatches = pack.patches.toList()
        val originalShims = pack.shims.toList()
        ProfileRegistry.applyOverrides(
            pack,
            PatchOverrides(
                patches = listOf(Patch.AssetDecrypt(kind = "omori-aes-ctr")),
                shims = listOf("worker-install"),
                workerShim = true,
            ),
        )
        // EngineProfile.copy(...) returns a fresh instance; merging must NOT touch the pack.
        // otherwise the same pack default would drift between successive resolveProfile calls.
        assertEquals(originalPatches, pack.patches)
        assertEquals(originalShims, pack.shims)
        assertTrue(!pack.workerShim)
    }
}
