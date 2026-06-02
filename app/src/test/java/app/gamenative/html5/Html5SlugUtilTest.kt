package app.gamenative.html5

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Html5SlugUtilTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("slug-test-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun slug_carriesTheStoreAndId() {
        assertEquals("sol-cesto-steam-2738490", Html5SlugUtil.slug("Sol Cesto", "STEAM_2738490"))
        assertEquals("crosscode-gog-1252295864", Html5SlugUtil.slug("CrossCode", "GOG_1252295864"))
    }

    // same title on two stores must not collide -- a hash over the BARE numeric id differed only
    // by 16 bits, and matched outright when the ids matched too.
    @Test
    fun slug_sameTitleOnTwoStores_doesNotCollide() {
        val steam = Html5SlugUtil.slug("CrossCode", "STEAM_368340")
        val gog = Html5SlugUtil.slug("CrossCode", "GOG_1252295864")
        assertTrue("distinct stores must produce distinct dirs", steam != gog)
        assertEquals(steam, Html5SlugUtil.slug("CrossCode", "STEAM_368340"))
    }

    @Test
    fun slug_sameNumericIdAcrossStores_doesNotCollide() {
        assertTrue(Html5SlugUtil.slug("Game", "STEAM_777") != Html5SlugUtil.slug("Game", "GOG_777"))
    }

    @Test
    fun slug_truncatesTheReadablePrefixButKeepsTheIdWhole() {
        val s = Html5SlugUtil.slug("Welcome to Elderfield - Full Game", "STEAM_123")
        assertTrue("prefix truncated at 24: $s", s.startsWith("welcome-to-elderfield-fu-"))
        assertTrue("id must survive whole: $s", s.endsWith("-steam-123"))
    }

    @Test
    fun slug_emptyFolderNameStillProducesAUsableName() {
        assertEquals("game-steam-5", Html5SlugUtil.slug("", "STEAM_5"))
        assertEquals("game-steam-5", Html5SlugUtil.slug("!!!", "STEAM_5"))
    }

    @Test
    fun canonicalize_renamesALegacySlugDir() {
        val install = File(root, "install/Sol Cesto").also { it.mkdirs() }
        val legacy = File(root, "sol-cesto-7721").also { it.mkdirs() }
        File(legacy, "config.json").writeText("{}")

        val result = Html5SlugUtil.canonicalize(legacy, install.absolutePath, "STEAM_2738490")

        assertEquals("sol-cesto-steam-2738490", result.name)
        assertTrue("config must travel with the rename", File(result, "config.json").isFile)
        assertTrue("legacy dir must be gone", !legacy.exists())
    }

    @Test
    fun canonicalize_isANoOpOnAnAlreadyCanonicalDir() {
        val install = File(root, "install/Sol Cesto").also { it.mkdirs() }
        val dir = File(root, "sol-cesto-steam-2738490").also { it.mkdirs() }

        assertEquals(dir, Html5SlugUtil.canonicalize(dir, install.absolutePath, "STEAM_2738490"))
    }

    // lookups match container.id, so refusing to rename costs a stale name and nothing else --
    // much better than clobbering whatever already owns the canonical name.
    @Test
    fun canonicalize_neverClobbersAnExistingDir() {
        val install = File(root, "install/Sol Cesto").also { it.mkdirs() }
        val legacy = File(root, "sol-cesto-7721").also { it.mkdirs() }
        File(legacy, "config.json").writeText("{\"mine\":true}")
        val occupied = File(root, "sol-cesto-steam-2738490").also { it.mkdirs() }
        File(occupied, "config.json").writeText("{\"theirs\":true}")

        val result = Html5SlugUtil.canonicalize(legacy, install.absolutePath, "STEAM_2738490")

        assertEquals(legacy, result)
        assertEquals("{\"theirs\":true}", File(occupied, "config.json").readText())
    }

    @Test
    fun canonicalize_withoutAnInstallPath_leavesTheDirAlone() {
        val dir = File(root, "legacy-abcd").also { it.mkdirs() }
        assertEquals(dir, Html5SlugUtil.canonicalize(dir, "", "STEAM_1"))
    }
}
