package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// pins that outbound from device produces cloud bytes that round-trip back to the desktop's origin shape
// (IDB filename-form + LS URL-form origins). blob envelopes aren't covered: maybeDecompressSnappyValue
// inlines sidecar bytes during the copy, so there is no separate envelope pass to round-trip.
class DesktopInteropRoundTripTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("interop-rt-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun idbOrigin_roundTripsKeysByteIdentical() {
        val desktopOrigin = "chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0"
        val webviewOrigin = "https_game-steam_2738490_0"

        val original = File(tmpRoot, "src-desktop")
        val toWebview = File(tmpRoot, "webview-staging")
        val backToDesktop = File(tmpRoot, "outbound-result")

        FixtureBuilder.idbWithDatabaseName(original, desktopOrigin, "GameDB")

        LevelDbRewriter.rewriteIdbOrigin(original, toWebview, desktopOrigin, webviewOrigin)
        LevelDbRewriter.rewriteIdbOrigin(toWebview, backToDesktop, webviewOrigin, desktopOrigin)

        val originalKeys = collectKeys(original, useIdb1 = true).map { it.toList() }.toSet()
        val roundTrippedKeys = collectKeys(backToDesktop, useIdb1 = true).map { it.toList() }.toSet()

        assertEquals(
            "round-tripped IDB keys must be identical to the original desktop-origin set",
            originalKeys,
            roundTrippedKeys,
        )
    }

    @Test
    fun lsOrigin_roundTripsKeysByteIdentical() {
        val desktopUrl = "chrome-extension://anopiimlkmdoenonenclohfilpeenfmj"
        val webviewUrl = "https://game-steam_2738490"

        val original = File(tmpRoot, "ls-src-desktop")
        val toWebview = File(tmpRoot, "ls-webview-staging")
        val backToDesktop = File(tmpRoot, "ls-outbound-result")

        FixtureBuilder.lsWithOrigins(
            original,
            desktopUrl to mapOf(
                "save-slot-0" to "slot0-bytes".toByteArray(),
                "save-slot-1" to "slot1-bytes".toByteArray(),
            ),
        )

        // last arg (activeContainerOrigin) is the origin of whichever leveldb is being read
        LevelDbRewriter.rewriteLsOrigin(original, toWebview, desktopUrl, webviewUrl, desktopUrl)
        LevelDbRewriter.rewriteLsOrigin(toWebview, backToDesktop, webviewUrl, desktopUrl, webviewUrl)

        val originalKeys = collectLsKeys(original).map { it.toList() }.toSet()
        val roundTrippedKeys = collectLsKeys(backToDesktop).map { it.toList() }.toSet()

        assertEquals(
            "round-tripped LS keys must be identical to the original desktop-url set",
            originalKeys,
            roundTrippedKeys,
        )
    }

    // chromium writes SSTables as `.ldb`; iq80 expects `.sst`. rename around the read and restore
    // after so the dir stays chromium-shaped for any later rewrite call.
    private fun collectKeys(dir: File, useIdb1: Boolean): List<ByteArray> {
        val options = Options().apply {
            createIfMissing(false)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) comparator(Idb1Comparator())
        }
        val renamed = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
        renamed.forEach { it.renameTo(File(it.parentFile, it.nameWithoutExtension + ".sst")) }
        return try {
            Iq80DBFactory.factory.open(dir, options).use { db ->
                val keys = mutableListOf<ByteArray>()
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) keys += iter.next().key
                }
                keys
            }
        } finally {
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty().forEach { sst ->
                sst.renameTo(File(sst.parentFile, sst.nameWithoutExtension + ".ldb"))
            }
        }
    }

    private fun collectLsKeys(dir: File): List<ByteArray> = collectKeys(dir, useIdb1 = false)
}
