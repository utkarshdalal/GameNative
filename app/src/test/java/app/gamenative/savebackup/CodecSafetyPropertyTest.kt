package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Property test for codec import safety — game-save-backup Task 7.4 (Property 12).
 *
 * Feature: game-save-backup, Property 12: Import rejects path-escape and symlink entries
 * atomically.
 *
 * For any archive or raw-tree source containing an entry that resolves outside its target save
 * root or resolves to a symbolic link, the import rejects that entry, reports failure identifying
 * the rejected entry, and leaves the container unchanged with no partial files written
 * (Req 8.5, 8.6, 9.5, 9.6).
 *
 * Because both codecs stage + validate ALL entries before committing, a rejection must leave the
 * destination byte-for-byte in its pre-import state — even a perfectly valid sibling entry present
 * in the same import must NOT be committed. Each trial pre-populates the destination with a known
 * file, attempts the malicious import, and asserts the destination still contains exactly that
 * pre-existing file.
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). The external side is in-memory (archive) or temp-dir-backed
 * (raw tree). Symlink creation is guarded with [assumeTrue]/try-catch so filesystems that disallow
 * symlinks skip only the symlink assertions. Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class CodecSafetyPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    // A single-segment rootId, matching the codec's on-disk scheme (see ArchiveCodec: entries are
    // split at the first '/' after `files/`, so the rootId itself must not contain a separator).
    private val rootId = "winsavedgames"

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 12: Import rejects path-escape and symlink entries
    // atomically — Archive path-escape entry is rejected with PathEscape identifying the entry and
    // the destination (a pre-existing file) is left unchanged; a valid sibling is NOT committed.
    @Property(trials = 120)
    fun archivePathEscapeIsRejectedAtomically(
        escapeDepthSeed: Int,
        siblingSeed: Long,
        preExistingSeed: Long,
    ) {
        val containerB = newTempDir("safety-arc-esc-B")
        val stagingB = newTempDir("safety-arc-esc-staging")
        val preExisting = prePopulate(containerB, preExistingSeed)

        // Escape path with an arbitrary number of ../ segments (>=2 guarantees escaping the root).
        val depth = Math.floorMod(escapeDepthSeed, 3) + 2
        val escapeRel = (0 until depth).joinToString("/") { ".." } + "/escape.dat"
        val escapeEntry = "files/$rootId/$escapeRel"
        val siblingEntry = "files/$rootId/valid_${Math.floorMod(siblingSeed, 1000)}.dat"

        val zip = buildArchive(
            roots = listOf(SaveRoot(rootId, containerB.toString())),
            entries = listOf(
                siblingEntry to byteArrayOf(1, 2, 3), // a valid sibling that must NOT commit
                escapeEntry to byteArrayOf(9),
            ),
        )

        val ex = assertThrows(ArchiveCodec.ImportException.PathEscape::class.java) {
            ArchiveCodec.import(
                openSource = { ByteArrayInputStream(zip) },
                resolveRoot = { id -> if (id == rootId) containerB else null },
                stagingDir = stagingB,
            )
        }
        assertEquals("rejection must identify the offending entry", escapeEntry, ex.entryName)
        assertUnchanged(containerB, preExisting)
    }

    // Feature: game-save-backup, Property 12: Import rejects path-escape and symlink entries
    // atomically — Archive symlink destination is rejected with Symlink and the destination is
    // left unchanged.
    @Property(trials = 120)
    fun archiveSymlinkIsRejectedAtomically(
        siblingSeed: Long,
        preExistingSeed: Long,
        linkNameSeed: Long,
    ) {
        val containerB = newTempDir("safety-arc-sym-B")
        val stagingB = newTempDir("safety-arc-sym-staging")
        val preExisting = prePopulate(containerB, preExistingSeed)

        // Pre-create a symlink at the destination path so the codec's symlink guard triggers.
        val linkName = "link_${Math.floorMod(linkNameSeed, 1000)}.dat"
        val linkTargetDir = newTempDir("safety-arc-sym-target")
        val linkTarget = linkTargetDir.resolve("real.dat")
        Files.write(linkTarget, byteArrayOf(7))
        val symlinkCreated = tryCreateSymlink(containerB.resolve(linkName), linkTarget)
        assumeTrue("filesystem must support symlinks for this trial", symlinkCreated)

        val symlinkEntry = "files/$rootId/$linkName"
        val siblingEntry = "files/$rootId/valid_${Math.floorMod(siblingSeed, 1000)}.dat"

        val zip = buildArchive(
            roots = listOf(SaveRoot(rootId, containerB.toString())),
            entries = listOf(
                siblingEntry to byteArrayOf(4, 5),
                symlinkEntry to byteArrayOf(6),
            ),
        )

        val ex = assertThrows(ArchiveCodec.ImportException.Symlink::class.java) {
            ArchiveCodec.import(
                openSource = { ByteArrayInputStream(zip) },
                resolveRoot = { id -> if (id == rootId) containerB else null },
                stagingDir = stagingB,
            )
        }
        assertEquals("rejection must identify the offending entry", symlinkEntry, ex.entryName)
        // The valid sibling must not have been committed; the pre-existing file + symlink remain.
        assertTrue(
            "valid sibling must not be committed on rejection",
            !Files.exists(containerB.resolve(siblingEntry.removePrefix("files/$rootId/"))),
        )
        assertEquals("pre-existing content must be unchanged", preExisting, readRegularFiles(containerB))
    }

    // Feature: game-save-backup, Property 12: Import rejects path-escape and symlink entries
    // atomically — RawTree path-escape entry is rejected with PathEscape and the destination is
    // left unchanged; a valid sibling is NOT committed.
    @Property(trials = 120)
    fun rawTreePathEscapeIsRejectedAtomically(
        escapeDepthSeed: Int,
        siblingSeed: Long,
        preExistingSeed: Long,
    ) {
        val containerB = newTempDir("safety-raw-esc-B")
        val stagingB = newTempDir("safety-raw-esc-staging")
        val preExisting = prePopulate(containerB, preExistingSeed)

        val depth = Math.floorMod(escapeDepthSeed, 3) + 2
        val escapeRel = (0 until depth).joinToString("/") { ".." } + "/escape.dat"
        val siblingRel = "valid_${Math.floorMod(siblingSeed, 1000)}.dat"

        val reader = FakeTreeReader(
            listOf(
                entry(siblingRel, byteArrayOf(1, 2, 3), isSymlink = false),
                entry(escapeRel, byteArrayOf(9), isSymlink = false),
            ),
        )

        val ex = assertThrows(RawTreeCodec.ImportException.PathEscape::class.java) {
            RawTreeCodec.import(reader, containerB, stagingB)
        }
        assertEquals("rejection must identify the offending entry", escapeRel, ex.entryName)
        assertUnchanged(containerB, preExisting)
    }

    // Feature: game-save-backup, Property 12: Import rejects path-escape and symlink entries
    // atomically — RawTree source entry flagged as a symlink is rejected with Symlink and the
    // destination is left unchanged; a valid sibling is NOT committed.
    @Property(trials = 120)
    fun rawTreeSymlinkIsRejectedAtomically(
        siblingSeed: Long,
        preExistingSeed: Long,
        linkNameSeed: Long,
    ) {
        val containerB = newTempDir("safety-raw-sym-B")
        val stagingB = newTempDir("safety-raw-sym-staging")
        val preExisting = prePopulate(containerB, preExistingSeed)

        val linkRel = "link_${Math.floorMod(linkNameSeed, 1000)}.dat"
        val siblingRel = "valid_${Math.floorMod(siblingSeed, 1000)}.dat"

        val reader = FakeTreeReader(
            listOf(
                entry(siblingRel, byteArrayOf(4, 5), isSymlink = false),
                entry(linkRel, byteArrayOf(6), isSymlink = true),
            ),
        )

        val ex = assertThrows(RawTreeCodec.ImportException.Symlink::class.java) {
            RawTreeCodec.import(reader, containerB, stagingB)
        }
        assertEquals("rejection must identify the offending entry", linkRel, ex.entryName)
        assertUnchanged(containerB, preExisting)
    }

    // ---- fakes -------------------------------------------------------------

    private class FakeTreeReader(private val entries: List<RawTreeCodec.TreeReader.Entry>) :
        RawTreeCodec.TreeReader {
        override fun listFiles(): List<RawTreeCodec.TreeReader.Entry> = entries
    }

    private fun entry(rel: String, bytes: ByteArray, isSymlink: Boolean): RawTreeCodec.TreeReader.Entry =
        RawTreeCodec.TreeReader.Entry(
            relativePath = rel,
            length = bytes.size.toLong(),
            isSymlink = isSymlink,
            openInput = { ByteArrayInputStream(bytes) },
        )

    // ---- helpers -----------------------------------------------------------

    /** Build a zip by hand with a manifest declaring [roots] plus the given `files/` entries. */
    private fun buildArchive(roots: List<SaveRoot>, entries: List<Pair<String, ByteArray>>): ByteArray {
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = 440,
            gameName = "Safety Game",
            exportedAt = 1_700_000_000_000L,
            roots = roots,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                kotlinx.serialization.json.Json.encodeToString(
                    SaveArchiveManifest.serializer(),
                    manifest,
                ).toByteArray(),
            )
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Pre-populate the destination with a single known file; returns its content map. */
    private fun prePopulate(root: Path, seed: Long): Map<String, List<Byte>> {
        val name = "preexisting_${Math.floorMod(seed, 1000)}.dat"
        val content = ByteArray(Math.floorMod(seed, 32).toInt() + 1) { (it + 1).toByte() }
        val file = root.resolve(name)
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, content)
        return mapOf(name to content.toList())
    }

    /** Assert the destination contains exactly the expected pre-existing (regular) files. */
    private fun assertUnchanged(root: Path, expected: Map<String, List<Byte>>) {
        assertEquals(
            "container must be left unchanged with no partial writes",
            expected,
            readRegularFiles(root),
        )
    }

    /** Read only regular files (ignoring symlinks) as relative-path -> byte-list. */
    private fun readRegularFiles(root: Path): Map<String, List<Byte>> {
        if (!Files.isDirectory(root)) return emptyMap()
        val result = LinkedHashMap<String, List<Byte>>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                val rel = root.relativize(file).toString().replace('\\', '/')
                result[rel] = Files.readAllBytes(file).toList()
            }
        }
        return result
    }

    private fun tryCreateSymlink(link: Path, target: Path): Boolean = try {
        link.parent?.let { Files.createDirectories(it) }
        Files.createSymbolicLink(link, target)
        true
    } catch (e: Exception) {
        false
    }

    private fun newTempDir(prefix: String): Path {
        val dir: Path = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path: Path -> Files.deleteIfExists(path) }
        }
    }
}
