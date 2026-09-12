package app.gamenative.steamcontroller

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.winlator.xserver.XKeycode
import java.io.File

/**
 * Tests the per-game named-config registry in [ScConfigStore]: importing/authoring configs, selecting the active
 * one, duplicate/rename/delete, the [forKey] active-config resolution, and migration of legacy single-config files.
 */
@RunWith(RobolectricTestRunner::class)
class ScConfigStoreRegistryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "GAME_TEST"

    /** A minimal valid Steam config (two action sets) so [ScConfigStore.validate]/parse returns non-empty. */
    private val vdfTwoSets = SMOKE_CONFIG

    @Before
    fun clean() {
        File(ctx.filesDir, "sc_configs").deleteRecursively()
    }

    @Test
    fun `empty key has no configs and forKey is null`() {
        assertTrue(ScConfigStore.listConfigs(ctx, key).isEmpty())
        assertNull(ScConfigStore.activeConfigId(ctx, key))
        assertNull(ScConfigStore.forKey(ctx, key))
    }

    @Test
    fun `import vdf registers an active config that forKey resolves`() {
        val id = ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "Imported")
        assertNotNull(id)
        assertEquals(id, ScConfigStore.activeConfigId(ctx, key))
        assertEquals(1, ScConfigStore.listConfigs(ctx, key).size)
        assertEquals(ScConfigKind.VDF, ScConfigStore.listConfigs(ctx, key).first().kind)
        val cfg = ScConfigStore.forKey(ctx, key)
        assertNotNull(cfg)
        assertTrue(cfg!!.sets.isNotEmpty())
    }

    @Test
    fun `authored config resolves through forKey and switching action sets is preserved`() {
        // A two-set authored config: set 0 with A -> switch to set 1.
        val cfg = ScEditableConfig(
            sets = listOf(
                ScEditableSet(
                    id = "0", name = "Base",
                    profile = ScEditableProfile(buttons = mapOf("A" to EditBinding(OutputKind.SWITCH_ACTION_SET, targetSetId = "1"))),
                ),
                ScEditableSet(id = "1", name = "Alt"),
            ),
            defaultSetId = "0",
        )
        assertTrue(ScConfigStore.saveEditableConfig(ctx, key, cfg))
        val resolved = ScConfigStore.forKey(ctx, key)
        assertNotNull(resolved)
        assertEquals(setOf("0", "1"), resolved!!.sets.keys)
        // The switch binding survived the round-trip into the runtime config.
        val aBit = ScSource.A.bit
        val out = resolved.sets["0"]!!.buttons[aBit]?.output
        assertTrue(out is ScOutput.SwitchActionSet && (out as ScOutput.SwitchActionSet).targetSetId == "1")
    }

    @Test
    fun `editing a vdf-active config saves in place as an overlay (no fork)`() {
        val vdfId = ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "vdf")!!
        assertTrue(ScConfigStore.saveEditableConfig(ctx, key, ScEditableConfig(sets = listOf(ScEditableSet(id = "0", name = "Mine")))))
        // Lossless edit: no fork — still one config, still the same VDF, still active.
        assertEquals(1, ScConfigStore.listConfigs(ctx, key).size)
        assertEquals(vdfId, ScConfigStore.activeConfigId(ctx, key))
        assertEquals(ScConfigKind.VDF, ScConfigStore.listConfigs(ctx, key).first().kind)
        assertNotNull(ScConfigStore.forKey(ctx, key))
    }

    @Test
    fun `switching active between two configs changes activeConfigId`() {
        val a = ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "A")!!
        val b = ScConfigStore.duplicateConfig(ctx, key, a, "B")!!  // duplicate makes the copy active
        assertEquals(b, ScConfigStore.activeConfigId(ctx, key))
        assertTrue(ScConfigStore.setActiveConfig(ctx, key, a))
        assertEquals(a, ScConfigStore.activeConfigId(ctx, key))
    }

    @Test
    fun `editing a vdf preserves its action layers and mode-shift (lossless overlay)`() {
        ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "Smoke")
        val base = ScConfigStore.forKey(ctx, key)!!
        // Sanity: the parsed config carries action-layer sources + a mode-shift overlay (the surfaces the old
        // fork-to-authored path destroyed).
        assertTrue("base should have layer sources", base.setSources.isNotEmpty())
        assertTrue("base should have a mode-shift overlay", base.shiftOverlays.isNotEmpty())

        // Seed the editor from the vdf, rebind A in set "0" to F5 (a representable edit), and save.
        val seeded = ScConfigStore.loadEditableConfig(ctx, key)!!
        val set0 = seeded.sets.first { it.id == "0" }
        val edited = seeded.copy(
            sets = seeded.sets.map {
                if (it.id == "0") it.copy(profile = it.profile.copy(buttons = it.profile.buttons + ("A" to EditBinding(OutputKind.KEY, keys = listOf("KEY_F5"))))) else it
            },
        )
        assertTrue(ScConfigStore.saveEditableConfig(ctx, key, edited))

        val resolved = ScConfigStore.forKey(ctx, key)!!
        // The edit applied...
        val out = resolved.sets["0"]!!.buttons[ScSource.A.bit]?.output
        assertTrue("A should now be F5", out is ScOutput.Key && (out as ScOutput.Key).keys == listOf(XKeycode.KEY_F5))
        // ...and the advanced surfaces survived (they'd be empty if the edit had forked a default-based copy).
        assertTrue("layers preserved", resolved.setSources.isNotEmpty())
        assertTrue("mode-shift preserved", resolved.shiftOverlays.isNotEmpty())
        assertEquals(base.sets.keys, resolved.sets.keys)
    }

    @Test
    fun `duplicate copies the active config and makes the copy active`() {
        val id = ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "Orig")!!
        val dupId = ScConfigStore.duplicateConfig(ctx, key, id, "Copy")
        assertNotNull(dupId)
        assertEquals(2, ScConfigStore.listConfigs(ctx, key).size)
        assertEquals(dupId, ScConfigStore.activeConfigId(ctx, key))
        // Both resolve independently.
        assertNotNull(ScConfigStore.forKey(ctx, key))
        assertTrue(ScConfigStore.setActiveConfig(ctx, key, id))
        assertNotNull(ScConfigStore.forKey(ctx, key))
    }

    @Test
    fun `delete removes a config and falls back to a remaining one`() {
        val a = ScConfigStore.importVdfConfig(ctx, key, vdfTwoSets, "A")!!
        val b = ScConfigStore.duplicateConfig(ctx, key, a, "B")!!  // b is active
        assertTrue(ScConfigStore.deleteConfig(ctx, key, b))
        assertEquals(1, ScConfigStore.listConfigs(ctx, key).size)
        assertEquals(a, ScConfigStore.activeConfigId(ctx, key))
        assertTrue(ScConfigStore.deleteConfig(ctx, key, a))
        assertTrue(ScConfigStore.listConfigs(ctx, key).isEmpty())
        assertNull(ScConfigStore.forKey(ctx, key))
    }

    @Test
    fun `legacy vdf and sets files migrate into a registry preserving vdf-active`() {
        // Simulate the pre-registry on-disk state: a legacy <key>.vdf + <key>.sets.json.
        val dir = File(ctx.filesDir, "sc_configs").apply { mkdirs() }
        File(dir, "$key.vdf").writeText(vdfTwoSets)
        File(dir, "$key.sets.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                ScEditableConfig.serializer(),
                ScEditableConfig(sets = listOf(ScEditableSet(id = "0", name = "Authored"))),
            ),
        )
        val configs = ScConfigStore.listConfigs(ctx, key)
        assertEquals(2, configs.size)
        // vdf stays active (preserves prior resolution); the authored one is selectable.
        val active = ScConfigStore.listConfigs(ctx, key).first { it.id == ScConfigStore.activeConfigId(ctx, key) }
        assertEquals(ScConfigKind.VDF, active.kind)
        assertTrue(configs.any { it.kind == ScConfigKind.AUTHORED })
        // Legacy files were consumed.
        assertFalse(File(dir, "$key.vdf").exists())
        assertFalse(File(dir, "$key.sets.json").exists())
    }

    @Test
    fun `forKey falls back to the shared default key`() {
        ScConfigStore.importVdfConfig(ctx, ScConfigStore.DEFAULT_KEY, vdfTwoSets, "shared")
        // A game with no configs of its own resolves the shared default.
        assertTrue(ScConfigStore.listConfigs(ctx, key).isEmpty())
        assertNotNull(ScConfigStore.forKey(ctx, key))
    }
}
