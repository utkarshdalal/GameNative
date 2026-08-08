package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// resolveOpfsBucketDir reads chromium's File System/Origins index (SandboxOriginDatabase:
// key "ORIGIN:<scheme_host_port>" → bucket dir name) via shadow-copy. it drives the
// OPFS-wipe-on-uninstall path in ContainerUtils; a wrong/missing read there leaks stale OPFS
// across a reinstall (the title-screen no-Continue bug this fix closes).
class LevelDbRewriterOpfsBucketTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("opfs-bucket-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    private fun writeOrigins(dir: File, entries: Map<String, String>) {
        dir.mkdirs()
        val opts = Options().apply {
            createIfMissing(true)
            compressionType(CompressionType.SNAPPY)
        }
        Iq80DBFactory.factory.open(dir, opts).use { db ->
            entries.forEach { (k, v) ->
                db.put(k.toByteArray(Charsets.US_ASCII), v.toByteArray(Charsets.US_ASCII))
            }
        }
        // iq80 leaves a 0-byte LOCK on close; chromium re-creates its own on next open.
        File(dir, "LOCK").delete()
    }

    @Test
    fun resolvesBucketForKnownOrigin() {
        val origin = "http://gog-1516178466.localhost:59099"
        val originsDir = File(tmpRoot, "Origins")
        writeOrigins(
            originsDir,
            mapOf(
                "ORIGIN:" + OriginCodec.filenameFromUrl(origin) to "002",
                "ORIGIN:http_steam-379210.localhost_59099" to "001",
            ),
        )

        assertEquals("002", LevelDbRewriter.resolveOpfsBucketDir(originsDir, origin))
    }

    @Test
    fun nullForUnknownOrigin() {
        val originsDir = File(tmpRoot, "Origins")
        writeOrigins(originsDir, mapOf("ORIGIN:http_steam-379210.localhost_59099" to "001"))

        assertNull(
            LevelDbRewriter.resolveOpfsBucketDir(originsDir, "http://gog-1516178466.localhost:59099"),
        )
    }

    @Test
    fun nullForMissingDir() {
        assertNull(
            LevelDbRewriter.resolveOpfsBucketDir(
                File(tmpRoot, "nope"),
                "http://gog-1516178466.localhost:59099",
            ),
        )
    }

    @Test
    fun nullForEmptyShell() {
        val originsDir = File(tmpRoot, "Origins").apply { mkdirs() }
        File(originsDir, "LOCK").createNewFile()
        File(originsDir, "LOG").createNewFile()

        assertNull(
            LevelDbRewriter.resolveOpfsBucketDir(originsDir, "http://gog-1516178466.localhost:59099"),
        )
    }
}
