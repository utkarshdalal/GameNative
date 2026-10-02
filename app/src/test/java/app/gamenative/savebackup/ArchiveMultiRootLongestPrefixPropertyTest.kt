package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property test for multi-root archive resolution — game-save-backup Task 18.3.
 *
 * Feature: game-save-backup, Property 17: Archive rootId resolves by longest prefix to distinct
 * destinations.
 *
 * For any archive whose manifest records one or more roots with arbitrary (including multi-segment)
 * `rootId`s, every `files/<rootId>/<relpath>` entry resolves to the destination of the recorded
 * root whose `rootId` is the **longest prefix** of the entry's path, each root mapping to its own
 * destination; an entry matching no recorded `rootId` is rejected as unknown-root; and a round-trip
 * (export → import → re-export) of a multi-root archive preserves per-root relative paths and bytes
 * (Req 8.3/8.3a/8.4).
 *
 * The longest-prefix case is exercised explicitly with **nested** rootIds (e.g.
 * `winappdatalocal/root` and `winappdatalocal/root/sub`) so a first-segment split would resolve the
 * wrong (or no) root — the PR #1914 import regression this closes.
 *
 * Runs under junit-quickcheck (Robolectric-free), driving [ArchiveCodec] directly over in-memory
 * zip streams and temp dirs. Every `@Property` runs ≥100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class ArchiveMultiRootLongestPrefixPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 17: multi-root archive resolves each rootId by longest
    // prefix to its own destination, and round-trips byte-for-byte.
    @Property(trials = 200)
    fun multiRootResolvesByLongestPrefixAndRoundTrips(
        rootCountSelector: Int,
        pathSeed: Long,
        contentSeed: Long,
    ) {
        // Build 2..3 roots, including a NESTED pair so longest-prefix matters.
        val rootIds = buildRootIds(rootCountSelector)

        // A distinct save file per root (relative path + bytes) under that root's own destination.
        var seed = pathSeed xor contentSeed
        val perRoot = rootIds.associateWith { rootId ->
            seed = next(seed)
            val rel = "sub_${Math.floorMod(seed, 1000)}/file_${Math.floorMod(seed shr 8, 1000)}.sav"
            val bytes = ByteArray(Math.floorMod(seed shr 3, 32).toInt()) { (seed ushr it).toByte() }
            rel to bytes
        }

        // Materialize each root's source tree under its own container-A dir.
        val sourceDirs = rootIds.associateWith { newTempDir("mr-src") }
        rootIds.forEach { rootId ->
            val (rel, bytes) = perRoot.getValue(rootId)
            val f = sourceDirs.getValue(rootId).resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }

        // Export a multi-root archive: one manifest root + one files/<rootId>/ subtree per root.
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = 440,
            gameName = "Multi Root Game",
            exportedAt = 1_700_000_000_000L,
            roots = rootIds.map { SaveRootManifest(rootId = it, path = sourceDirs.getValue(it).toString()) },
        )
        val zip = ByteArrayOutputStream()
        ArchiveCodec.export(
            manifest = manifest,
            roots = rootIds.map { ArchiveCodec.ExportRoot(it, sourceDirs.getValue(it), allRegularFiles(sourceDirs.getValue(it))) },
            openDest = { zip },
        )

        // Import into DISTINCT destinations per rootId, matched by longest prefix (as the engine does).
        val destDirs = rootIds.associateWith { newTempDir("mr-dst") }
        val staging = newTempDir("mr-staging")
        ArchiveCodec.import(
            openSource = { ByteArrayInputStream(zip.toByteArray()) },
            resolveRoot = { id -> longestPrefixMatch(id, destDirs) },
            stagingDir = staging,
        )

        // Each root's file landed in ITS OWN destination with identical relative path + bytes —
        // never collapsed onto one folder (Req 8.3), and longest-prefix picked the right nested root.
        rootIds.forEach { rootId ->
            val (rel, bytes) = perRoot.getValue(rootId)
            val landed = destDirs.getValue(rootId).resolve(rel)
            assertTrue("file for root '$rootId' must land in its own destination at $rel", Files.exists(landed))
            assertEquals(
                "bytes for root '$rootId' must round-trip identically",
                bytes.toList(),
                Files.readAllBytes(landed).toList(),
            )
        }

        // Re-export the imported destinations and confirm the archive entry set matches the original.
        val zipB = ByteArrayOutputStream()
        ArchiveCodec.export(
            manifest = manifest,
            roots = rootIds.map { ArchiveCodec.ExportRoot(it, destDirs.getValue(it), allRegularFiles(destDirs.getValue(it))) },
            openDest = { zipB },
        )
        assertEquals(
            "re-exported multi-root archive must contain the same entries + bytes",
            readArchiveEntries(zip.toByteArray()),
            readArchiveEntries(zipB.toByteArray()),
        )
    }

    // Feature: game-save-backup, Property 17 (unknown root): an entry whose rootId matches no
    // recorded destination is rejected as UnknownRoot and nothing is committed.
    @Property(trials = 100)
    fun entryWithNoMatchingRootIsRejected(pathSeed: Long) {
        val knownRootId = "winsavedgames/root"
        val src = newTempDir("mr-unknown-src")
        val rel = "a/b.sav"
        Files.createDirectories(src.resolve(rel).parent)
        Files.write(src.resolve(rel), byteArrayOf(1, 2, 3))

        // Manifest declares the known root, but we hand-add an entry under an UNRELATED rootId.
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = 1,
            gameName = "Unknown Root Game",
            exportedAt = 1L,
            roots = listOf(SaveRootManifest(rootId = knownRootId, path = src.toString())),
        )
        val zip = buildArchiveWithRawEntries(
            manifest,
            listOf("files/winmydocuments/other/x.sav" to byteArrayOf(9)),
        )

        val dest = newTempDir("mr-unknown-dst")
        val staging = newTempDir("mr-unknown-staging")
        assertThrows(ArchiveCodec.ImportException.UnknownRoot::class.java) {
            ArchiveCodec.import(
                openSource = { ByteArrayInputStream(zip) },
                resolveRoot = { id -> if (id == knownRootId) dest else null },
                stagingDir = staging,
            )
        }
        // Nothing committed to the destination.
        assertTrue("no file may be committed on an unknown-root rejection", isEmptyTree(dest))
    }

    // ---- helpers -----------------------------------------------------------

    /** Build 2..3 rootIds including a nested pair so longest-prefix is actually exercised. */
    private fun buildRootIds(selector: Int): List<String> {
        val three = Math.floorMod(selector, 2) == 0
        return if (three) {
            listOf("winappdatalocal/root", "winappdatalocal/root/sub", "winsavedgames/root")
        } else {
            listOf("winappdatalocal/root", "winappdatalocal/root/sub")
        }
    }

    /** Mirror of the engine's longest-prefix matcher. */
    private fun longestPrefixMatch(rootId: String, destinations: Map<String, Path>): Path? {
        destinations[rootId]?.let { return it }
        return destinations.entries
            .filter { (key, _) -> rootId == key || rootId.startsWith("$key/") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun allRegularFiles(root: Path): List<Path> =
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.collect(java.util.stream.Collectors.toList())
        }

    private fun readArchiveEntries(zipBytes: ByteArray): Map<String, List<Byte>> {
        val result = LinkedHashMap<String, List<Byte>>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.startsWith("files/")) {
                    result[entry.name] = zip.readBytes().toList()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun buildArchiveWithRawEntries(
        manifest: SaveArchiveManifest,
        entries: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(
                kotlinx.serialization.json.Json.encodeToString(
                    SaveArchiveManifest.serializer(),
                    manifest,
                ).toByteArray(),
            )
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun isEmptyTree(root: Path): Boolean {
        if (!Files.isDirectory(root)) return true
        Files.walk(root).use { stream ->
            return stream.noneMatch { Files.isRegularFile(it) }
        }
    }

    private fun newTempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    private fun next(seed: Long): Long = seed * 6364136223846793005L + 1442695040888963407L
}
