package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-game shader store (spec 2026-08-12): shaders are OFF by default for every game;
 * a selection is associated with the game where it was first enabled; nothing leaks
 * across games; uninstall clears only the target game.
 */
class PerGameShaderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(fileName: String = "per_game.json") =
        PerGameShaderStore(File(tmp.root, fileName))

    private fun config(
        enabled: Boolean = true,
        presetPath: String = "/cache/pack/crt/easymode.slangp",
        presetName: String = "Easymode",
        relativePath: String = "crt/easymode.slangp",
    ) = PerGameShaderConfig(enabled, presetPath, presetName, relativePath)

    // ── default off ──

    @Test
    fun `missing store file means every game is off`() {
        val s = store()
        assertNull(s.loadForGame("STEAM_1293830"))
        assertFalse(s.hasEntry("STEAM_1293830"))
    }

    @Test
    fun `unknown game has no entry even when the store exists`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        assertNull(s.loadForGame("GOG_19283103"))
        assertFalse(s.hasEntry("GOG_19283103"))
        assertEquals("Easymode", s.loadForGame("STEAM_1293830")?.presetName)
    }

    // ── roundtrip / isolation ──

    @Test
    fun `save then load roundtrips every field`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        val loaded = s.loadForGame("STEAM_1293830")!!
        assertTrue(loaded.enabled)
        assertEquals("/cache/pack/crt/easymode.slangp", loaded.presetPath)
        assertEquals("Easymode", loaded.presetName)
        assertEquals("crt/easymode.slangp", loaded.relativePath)
    }

    @Test
    fun `game A and game B are isolated`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        s.saveForGame("EPIC_123", config(enabled = false, presetPath = "", presetName = "", relativePath = ""))
        assertNull(s.loadForGame("EPIC_123")) // fully default entry is removed, not stored
        s.saveForGame("EPIC_123", config(presetName = "Technicolor", relativePath = "film/technicolor.slangp"))
        assertEquals("Easymode", s.loadForGame("STEAM_1293830")?.presetName)
        assertEquals("Technicolor", s.loadForGame("EPIC_123")?.presetName)
    }

    // ── saving a fully default config removes the entry ──

    @Test
    fun `saving fully default config removes the entry`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        assertTrue(s.hasEntry("STEAM_1293830"))
        s.saveForGame("STEAM_1293830", config(enabled = false, presetPath = "", presetName = "", relativePath = ""))
        assertNull(s.loadForGame("STEAM_1293830"))
        assertFalse(s.hasEntry("STEAM_1293830"))
        // File disappears with the last entry.
        assertFalse(File(tmp.root, "per_game.json").exists())
    }

    // ── clearForGame only removes the target game ──

    @Test
    fun `clearForGame removes only the target game`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        s.saveForGame("GOG_19283103", config(presetName = "Blargg", relativePath = "ntsc/blargg.slangp"))
        s.clearForGame("STEAM_1293830")
        assertNull(s.loadForGame("STEAM_1293830"))
        assertEquals("Blargg", s.loadForGame("GOG_19283103")?.presetName)
        // Clearing an absent game is a no-op that keeps the rest intact.
        s.clearForGame("AMAZON_999")
        assertEquals("Blargg", s.loadForGame("GOG_19283103")?.presetName)
    }

    // ── malformed JSON degrades to empty ──

    @Test
    fun `malformed json degrades to empty store and recovers on next save`() {
        val s = store()
        File(tmp.root, "per_game.json").writeText("{not valid json!!!")
        assertNull(s.loadForGame("STEAM_1293830"))
        assertFalse(s.hasEntry("STEAM_1293830"))
        s.saveForGame("STEAM_1293830", config())
        assertEquals("Easymode", s.loadForGame("STEAM_1293830")?.presetName)
    }

    // ── atomic write leaves no tmp file behind ──

    @Test
    fun `save leaves no tmp file behind`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config())
        assertFalse(File(tmp.root, "per_game.json.tmp").exists())
        assertTrue(File(tmp.root, "per_game.json").isFile)
    }

    // ── decideShaderMigration: the 4 cases ──

    @Test
    fun `migration decision case1 already done skips everything`() {
        assertEquals(
            ShaderMigrationDecision.AlreadyDone,
            decideShaderMigration(migrationDone = true, hasContainerExtras = true, storeHasEntry = false),
        )
    }

    @Test
    fun `migration decision case2 no extras means nothing to migrate`() {
        assertEquals(
            ShaderMigrationDecision.NothingToMigrate,
            decideShaderMigration(migrationDone = false, hasContainerExtras = false, storeHasEntry = false),
        )
    }

    @Test
    fun `migration decision case3 store entry wins over extras`() {
        assertEquals(
            ShaderMigrationDecision.StoreAlreadyHasEntry,
            decideShaderMigration(migrationDone = false, hasContainerExtras = true, storeHasEntry = true),
        )
    }

    @Test
    fun `migration decision case4 extras with no store entry migrate`() {
        assertEquals(
            ShaderMigrationDecision.Migrate,
            decideShaderMigration(migrationDone = false, hasContainerExtras = true, storeHasEntry = false),
        )
    }

    // ── enabledGameIds (spec 2026-08-12, M4: library badge set) ──

    @Test
    fun `enabledGameIds is empty when the store is missing`() {
        val s = store()
        assertTrue(s.enabledGameIds().isEmpty())
    }

    @Test
    fun `enabledGameIds contains only games with enabled entries`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config(enabled = true))
        s.saveForGame("STEAM_550", config(enabled = false, presetPath = "", presetName = "", relativePath = ""))
        s.saveForGame("GOG_19283103", config(enabled = true, presetName = "Technicolor", relativePath = "film/technicolor.slangp"))
        assertEquals(setOf("STEAM_1293830", "GOG_19283103"), s.enabledGameIds())
    }

    @Test
    fun `enabledGameIds drops fully default entries`() {
        val s = store()
        s.saveForGame("STEAM_1293830", config(enabled = true))
        s.saveForGame("STEAM_1293830", config(enabled = false, presetPath = "", presetName = "", relativePath = ""))
        assertTrue(s.enabledGameIds().isEmpty())
    }

    @Test
    fun `enabledGameIds degrades to empty on malformed json`() {
        val file = File(tmp.root, "per_game.json")
        file.writeText("{ not valid json !!")
        val s = PerGameShaderStore(file)
        assertTrue(s.enabledGameIds().isEmpty())
        // ... and recovers on the next save
        s.saveForGame("STEAM_1293830", config())
        assertEquals(setOf("STEAM_1293830"), s.enabledGameIds())
    }
}
