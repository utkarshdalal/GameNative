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

// synthetic round-trip coverage for the device ↔ desktop save-sync pipeline. exercises
// LevelDbRewriter origin swap in both directions so we can say with evidence: outbound
// from device produces cloud bytes that round-trip back to the desktop's origin shape.

// WHAT THIS COVERS
// - IDB origin rewrite: desktop filename ↔ webview filename, keys round-trip byte-identical
// - LS origin rewrite: desktop URL ↔ webview URL, keys round-trip byte-identical

// Blob envelope handling is NOT tested here — `LevelDbRewriter.maybeDecompressSnappyValue`
// inlines + decompresses sidecar bytes during rewriteIdbOrigin's src→dst copy. There is no
// separate envelope-rewrite pass to round-trip.
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

    // ---------- IDB leveldb: desktop origin → webview origin → desktop origin ----------

    @Test
    fun idbOrigin_roundTripsKeysByteIdentical() {
        val desktopOrigin = "chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0"
        val webviewOrigin = "https_game-steam_2738490_0"

        val original = File(tmpRoot, "src-desktop")
        val toWebview = File(tmpRoot, "webview-staging")
        val backToDesktop = File(tmpRoot, "outbound-result")

        FixtureBuilder.idbWithDatabaseName(original, desktopOrigin, "GameDB")

        // inbound: desktop → webview origin
        LevelDbRewriter.rewriteIdbOrigin(original, toWebview, desktopOrigin, webviewOrigin)
        // outbound: webview → desktop origin (this is the critical direction for desktop interop)
        LevelDbRewriter.rewriteIdbOrigin(toWebview, backToDesktop, webviewOrigin, desktopOrigin)

        val originalKeys = collectKeys(original, useIdb1 = true).map { it.toList() }.toSet()
        val roundTrippedKeys = collectKeys(backToDesktop, useIdb1 = true).map { it.toList() }.toSet()

        assertEquals(
            "round-tripped IDB keys must be identical to the original desktop-origin set",
            originalKeys,
            roundTrippedKeys,
        )
    }

    // ---------- LS leveldb: desktop URL → webview URL → desktop URL ----------

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

        // inbound: activeContainerOrigin == webview (we're writing into webview's leveldb)
        LevelDbRewriter.rewriteLsOrigin(original, toWebview, desktopUrl, webviewUrl, webviewUrl)
        // outbound: activeContainerOrigin == webview (we're reading webview's leveldb)
        LevelDbRewriter.rewriteLsOrigin(toWebview, backToDesktop, webviewUrl, desktopUrl, webviewUrl)

        val originalKeys = collectLsKeys(original).map { it.toList() }.toSet()
        val roundTrippedKeys = collectLsKeys(backToDesktop).map { it.toList() }.toSet()

        assertEquals(
            "round-tripped LS keys must be identical to the original desktop-url set",
            originalKeys,
            roundTrippedKeys,
        )
    }

    // ---------- helpers ----------

    // chromium writes SSTables as `.ldb`; iq80 expects `.sst`. same format, different name.
    // rename around the read, restore after, so the source dir stays chromium-shaped for any
    // rewrite call.
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
