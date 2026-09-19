package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Html5LsOriginCleanupTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("ls-origin-cleanup-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun isLeaked_keepsCurrentPortAndThirdParty_dropsPcFormAndDeadOrigins() {
        val port = 59099
        assertFalse(Html5LsOriginCleanup.isLeaked("http://steam-1.localhost:59099", port))
        assertFalse(Html5LsOriginCleanup.isLeaked("https://login.gog.com", port))
        assertTrue(Html5LsOriginCleanup.isLeaked("http://steam-1.localhost:50000", port))
        assertTrue(Html5LsOriginCleanup.isLeaked("https://game-steam_379210", port))
        assertTrue(Html5LsOriginCleanup.isLeaked("https://termina-608a.app.local", port))
        assertTrue(Html5LsOriginCleanup.isLeaked("file://", port))
        assertTrue(Html5LsOriginCleanup.isLeaked("chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda", port))
    }

    @Test
    fun cleanup_purgesLeakedOrigins_thenMarkerSkipsLaterBoots() {
        val ls = File(tmpRoot, "leveldb")
        val marker = File(tmpRoot, "html5/marker")
        val kv = mapOf("k" to byteArrayOf(1))
        val live = "http://steam-1.localhost:59099"
        val thirdParty = "https://login.gog.com"
        FixtureBuilder.lsWithOrigins(
            ls,
            live to kv,
            thirdParty to kv,
            "http://steam-1.localhost:50000" to kv,
            "https://game-steam_379210" to kv,
            "file://" to kv,
            "chrome-extension://abc" to kv,
        )

        // 4 leaked origins x (META + one underscore key)
        assertEquals(8, Html5LsOriginCleanup.cleanup(ls, marker, currentPort = 59099))
        assertEquals(setOf(live, thirdParty), origins(ls))
        assertTrue(marker.isFile)

        FixtureBuilder.lsWithOrigins(ls, "file://" to kv)
        assertEquals(0, Html5LsOriginCleanup.cleanup(ls, marker, currentPort = 59099))
        assertTrue("marker short-circuits the pass", "file://" in origins(ls))
    }

    @Test
    fun wipeLegacyProfiles_removesProfileDirs_keepsDefault() {
        val appWebview = File(tmpRoot, "app_webview")
        listOf("Default/Local Storage", "Profile 1/Local Storage", "Profile-STEAM_379210/IndexedDB")
            .forEach { File(appWebview, it).mkdirs() }
        File(appWebview, "Profile 1/Local Storage/000003.log").writeText("x")

        assertEquals(2, Html5LsOriginCleanup.wipeLegacyProfiles(appWebview))
        assertEquals(setOf("Default"), appWebview.list()!!.toSet())
        assertEquals(0, Html5LsOriginCleanup.wipeLegacyProfiles(appWebview))
    }

    private fun origins(dir: File): Set<String> {
        val options = Options().apply {
            createIfMissing(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
        }
        dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
            .forEach { it.renameTo(File(it.parentFile, it.nameWithoutExtension + ".sst")) }
        return try {
            Iq80DBFactory.factory.open(dir, options).use { db ->
                val out = mutableSetOf<String>()
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) LevelDbRewriter.lsKeyOrigin(iter.next().key)?.let { out += it }
                }
                out
            }
        } finally {
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty()
                .forEach { it.renameTo(File(it.parentFile, it.nameWithoutExtension + ".ldb")) }
        }
    }
}
