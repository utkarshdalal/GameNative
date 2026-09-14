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
import org.junit.runner.RunWith

/**
 * Property test for codec import safety on invalid manifests — game-save-backup Task 7.5
 * (Property 13).
 *
 * Feature: game-save-backup, Property 13: Invalid archive manifest fails cleanly.
 *
 * For any archive whose `manifest.json` is missing or cannot be parsed, [ArchiveCodec.import]
 * throws [ArchiveCodec.ImportException.InvalidManifest] and writes NOTHING to the container
 * destination (Req 8.7). The codec reads and validates the manifest before touching any `files/`
 * entry, so a bad manifest must leave the destination byte-for-byte in its pre-import state.
 *
 * Each trial pre-populates the destination with a known file, drives the invalid-case selection
 * (missing vs unparseable) and the garbage bytes from junit-quickcheck-generated inputs, attempts
 * the import, and asserts (a) `InvalidManifest` is thrown and (b) the destination still contains
 * exactly the pre-existing file with no other files created. This mirrors the
 * `buildArchive`-by-hand + `assertUnchanged` patterns from [CodecSafetyPropertyTest].
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class InvalidManifestPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    // A single-segment rootId, matching the codec's on-disk scheme (entries are split at the first
    // '/' after `files/`, so the rootId itself must not contain a separator — confirmed in 7.4).
    private val rootId = "winsavedgames"

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 13: Invalid archive manifest fails cleanly — for any
    // archive whose manifest is missing OR unparseable, import throws InvalidManifest and leaves
    // the destination (a pre-existing file) byte-for-byte unchanged with no other files created.
    @Property(trials = 150)
    fun invalidManifestFailsCleanly(
        caseSeed: Int,
        garbageSeed: Long,
        entrySeed: Long,
        preExistingSeed: Long,
    ) {
        val container = newTempDir("invalid-manifest-B")
        val staging = newTempDir("invalid-manifest-staging")
        val preExisting = prePopulate(container, preExistingSeed)

        // A files/<rootId>/<name> entry that WOULD be importable if the manifest were valid — its
        // presence proves nothing is committed when the manifest is rejected.
        val fileEntry = "files/$rootId/save_${Math.floorMod(entrySeed, 1000)}.dat"
        val fileBytes = byteArrayOf(1, 2, 3, 4)

        // Choose between the two invalid cases from generated input so this is a genuine property
        // over many manifests.
        val zip = if (Math.floorMod(caseSeed, 2) == 0) {
            // Case 1: MISSING manifest — a zip containing only files/ entries and no manifest.json.
            buildZip(
                manifestBytes = null,
                fileEntries = listOf(fileEntry to fileBytes),
            )
        } else {
            // Case 2: UNPARSEABLE manifest — manifest.json holds arbitrary garbage bytes. Includes
            // random non-JSON, truncated JSON, and JSON missing the required gameId (with no
            // steamAppId alias) so decoding throws MissingFieldException.
            buildZip(
                manifestBytes = garbageManifest(garbageSeed),
                fileEntries = listOf(fileEntry to fileBytes),
            )
        }

        assertThrows(ArchiveCodec.ImportException.InvalidManifest::class.java) {
            ArchiveCodec.import(
                openSource = { ByteArrayInputStream(zip) },
                resolveRoot = { id -> if (id == rootId) container else null },
                stagingDir = staging,
            )
        }

        // Nothing was written: the container still holds exactly the pre-existing file.
        assertUnchanged(container, preExisting)
    }

    // An oversized manifest entry is rejected as InvalidManifest (bounded read) rather than being
    // loaded whole into memory, guarding against OOM from a malicious archive.
    @org.junit.Test
    fun oversizedManifestIsRejected() {
        // > 1 MiB of JSON-ish bytes under the manifest entry name.
        val huge = ByteArray((1 shl 20) + 1024) { '{'.code.toByte() }
        val zip = buildZip(huge, emptyList())

        assertThrows(ArchiveCodec.ImportException.InvalidManifest::class.java) {
            ArchiveCodec.readManifest { ByteArrayInputStream(zip) }
        }
    }

    // ---- garbage generation -----------------------------------------------

    /** Produce arbitrary non-parseable manifest bytes, varied by [seed] across several shapes. */
    private fun garbageManifest(seed: Long): ByteArray = when (Math.floorMod(seed, 5)) {
        // Random raw bytes — almost certainly not valid JSON.
        0 -> ByteArray(Math.floorMod(seed, 48).toInt() + 1) { i -> ((seed + i * 31) and 0xFF).toByte() }
        // Truncated JSON object.
        1 -> "{\"version\":5,\"gameId\":".toByteArray()
        // Plain non-JSON text.
        2 -> "this is not json $seed".toByteArray()
        // Valid JSON but missing the required gameId and with no steamAppId alias present, so
        // decoding throws MissingFieldException.
        3 -> "{\"version\":5,\"gameName\":\"X\",\"exportedAt\":1,\"roots\":[]}".toByteArray()
        // Empty content.
        else -> ByteArray(0)
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Build a zip by hand. When [manifestBytes] is null no `manifest.json` entry is written (the
     * missing-manifest case); otherwise the given bytes are written verbatim as `manifest.json`.
     */
    private fun buildZip(manifestBytes: ByteArray?, fileEntries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (manifestBytes != null) {
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
            }
            fileEntries.forEach { (name, bytes) ->
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
