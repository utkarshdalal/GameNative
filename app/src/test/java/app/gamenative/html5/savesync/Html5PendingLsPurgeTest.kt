package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class Html5PendingLsPurgeTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("ls-pending-purge-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    private fun originFor(appId: String) = "http://${appId.lowercase().replace('_', '-')}.localhost:59099"

    // a reinstalled game's live keys may already be the new install's, so boot leaves them to its first launch.
    @Test
    fun purgeAtBoot_purgesUninstalledGames_leavesReinstalledQueuedAndUntouched() {
        val ls = File(tmpRoot, "leveldb")
        val queue = File(tmpRoot, "html5/ls-pending-purge")
        val kv = mapOf("k" to byteArrayOf(1))
        FixtureBuilder.lsWithOrigins(
            ls,
            originFor("STEAM_1") to kv,
            originFor("STEAM_2") to kv,
            originFor("STEAM_3") to kv,
        )

        Html5PendingLsPurge.enqueue(queue, "STEAM_1")
        Html5PendingLsPurge.enqueue(queue, "STEAM_1")
        Html5PendingLsPurge.enqueue(queue, "STEAM_2")
        assertEquals(listOf("STEAM_1", "STEAM_2"), queue.readLines())

        val installed = { appId: String -> appId == "STEAM_2" }
        // STEAM_1: META + one underscore key
        assertEquals(2, Html5PendingLsPurge.purgeAtBoot(queue, ls, installed, ::originFor))
        assertEquals(setOf(originFor("STEAM_2"), originFor("STEAM_3")), origins(ls))
        assertEquals(listOf("STEAM_2"), queue.readLines())

        assertEquals(0, Html5PendingLsPurge.purgeAtBoot(queue, ls, installed, ::originFor))
        assertEquals(listOf("STEAM_2"), queue.readLines())
    }

    @Test
    fun remove_dropsAppId_andDeletesEmptyQueue() {
        val queue = File(tmpRoot, "html5/ls-pending-purge")
        Html5PendingLsPurge.enqueue(queue, "STEAM_1")
        Html5PendingLsPurge.enqueue(queue, "GOG_2")

        Html5PendingLsPurge.remove(queue, "STEAM_1")
        assertEquals(listOf("GOG_2"), queue.readLines())

        Html5PendingLsPurge.remove(queue, "GOG_2")
        assertFalse(queue.exists())
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
