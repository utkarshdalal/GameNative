package app.gamenative.html5.savesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

// RMMV filesystem <-> localStorage equivalence. an on-device LS dump showed the value payload (inside
// chromium's `01 <len> 01` framing) IS the LZString-base64 string in the `.rpgsave` file, so the sync
// transform is a pure file <-> KV mapping with zero re-encoding. this pins the PC-side half: fixture files
// are LZString-base64 and filenames map deterministically to LS keys.
class RmmvFilesystemProbeTest {

    // RMMV's LZString.compressToBase64 emits [A-Za-z0-9+/=]; URI-safe variants use [A-Za-z0-9+-_=]. accept both.
    private val lzStringBase64 = Regex("""^[A-Za-z0-9+/=_-]+$""")

    // populated LZString-compressed JSON typically begins with a capital letter (N, O, M)
    private val leadingCapital = Regex("""^[A-Z].*""")

    @Test
    fun config_rpgsave_is_valid_lzstring_base64() {
        val fixture = SaveFixtureHarness.loadTermina()
        assumeNotNull("termina fixture absent", fixture)
        val config = fixture!!.rpgSaveFiles.firstOrNull { it.name == "config.rpgsave" }
        assumeNotNull("config.rpgsave absent", config)
        val text = config!!.readText(Charsets.UTF_8).trim()
        assertTrue("config.rpgsave non-empty", text.isNotEmpty())
        assertTrue("config.rpgsave size > 0 (was ${config.length()})", config.length() > 0)
        assertTrue("config.rpgsave not LZString-base64 shape: head=${text.take(40)}", lzStringBase64.matches(text))
        assertTrue("config.rpgsave doesn't start with capital: head=${text.take(4)}", leadingCapital.matches(text))
    }

    @Test
    fun file1_rpgsave_is_valid_lzstring_base64_and_populated() {
        val fixture = SaveFixtureHarness.loadTermina()
        assumeNotNull("termina fixture absent", fixture)
        val file1 = fixture!!.rpgSaveFiles.firstOrNull { it.name == "file1.rpgsave" }
        assumeNotNull("file1.rpgsave absent", file1)
        val text = file1!!.readText(Charsets.UTF_8).trim()
        assertTrue("file1.rpgsave not LZString-base64 shape", lzStringBase64.matches(text))
        assertTrue("file1.rpgsave doesn't start with capital: head=${text.take(4)}", leadingCapital.matches(text))
        // a populated RMMV save is usually >= ~100 KB
        assertTrue("file1.rpgsave suspiciously small (${file1.length()} bytes) — real save expected > 100KB", file1.length() > 100_000)
    }

    @Test
    fun global_rpgsave_is_valid_lzstring_base64() {
        val fixture = SaveFixtureHarness.loadTermina()
        assumeNotNull("termina fixture absent", fixture)
        val global = fixture!!.rpgSaveFiles.firstOrNull { it.name == "global.rpgsave" }
        assumeNotNull("global.rpgsave absent", global)
        val text = global!!.readText(Charsets.UTF_8).trim()
        assertTrue("global.rpgsave size > 0", global.length() > 0)
        assertTrue("global.rpgsave not LZString-base64 shape", lzStringBase64.matches(text))
        assertTrue("global.rpgsave doesn't start with capital: head=${text.take(4)}", leadingCapital.matches(text))
    }

    @Test
    fun filename_to_localstorage_key_mapping() {
        // from RMMV's StorageManager.webStorageKey, confirmed against on-device keys
        assertEquals("RPG Config", RmmvSaveMapper.storageKeyForFilename("config.rpgsave"))
        assertEquals("RPG Global", RmmvSaveMapper.storageKeyForFilename("global.rpgsave"))
        assertEquals("RPG File1", RmmvSaveMapper.storageKeyForFilename("file1.rpgsave"))
        assertEquals("RPG File2", RmmvSaveMapper.storageKeyForFilename("file2.rpgsave"))
        assertEquals("RPG File99", RmmvSaveMapper.storageKeyForFilename("file99.rpgsave"))
        // "save slot" variant: fileNsave.rpgsave <-> "RPG SaveN"
        assertEquals("RPG Save1", RmmvSaveMapper.storageKeyForFilename("file1save.rpgsave"))
    }

    @Test
    fun unknown_filename_returns_null_key() {
        assertEquals(null, RmmvSaveMapper.storageKeyForFilename("random.rpgsave"))
        assertEquals(null, RmmvSaveMapper.storageKeyForFilename("config.txt"))
        assertEquals(null, RmmvSaveMapper.storageKeyForFilename(""))
    }

    @Test
    fun all_fixture_saves_map_to_a_known_key() {
        val fixture = SaveFixtureHarness.loadTermina()
        assumeNotNull("termina fixture absent", fixture)
        for (file in fixture!!.rpgSaveFiles) {
            val key = RmmvSaveMapper.storageKeyForFilename(file.name)
            assertNotNull("fixture file ${file.name} has no localStorage-key mapping", key)
        }
    }
}
