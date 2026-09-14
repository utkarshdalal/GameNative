package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/**
 * Property test for [ArchiveCodec] round-trip fidelity — game-save-backup Task 7.4 (Property 10).
 *
 * Feature: game-save-backup, Property 10: Archive round-trip preserves the save set.
 *
 * For any set of save files exported as an Archive layout, importing that archive into a container
 * and re-exporting produces an equivalent set of save files — the same set of relative paths under
 * each save root with byte-for-byte identical contents (Req 8.1, 8.2, 8.3, 8.4).
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). The external side is modelled entirely in memory: export
 * writes the zip to a [ByteArrayOutputStream]; import reads it back from a [ByteArrayInputStream]
 * over those bytes. The container side uses real temp directories on the same filesystem as the
 * codec's staging directory. Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class ArchiveCodecRoundTripPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    // A single-segment rootId, matching the codec's on-disk scheme: entries are laid out under
    // files/<rootId>/<relpath> and the importer splits at the first '/' after files/, so the
    // rootId must not itself contain a separator (the engine produces ids like `winsavedgames`,
    // `winmydocuments_subdir`, `steamuserdata`).
    private val rootId = "winsavedgames"

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 10: Archive round-trip preserves the save set —
    // export(container A) -> import(container B) -> export(container B) yields byte-identical
    // save sets keyed by relative path under the root.
    @Property(trials = 150)
    fun archiveExportImportReExportPreservesSaveSet(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
    ) {
        // Generate an arbitrary, non-empty set of save files (relative path -> bytes).
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)

        // Materialize them under a temp container root A.
        val containerA = newTempDir("archive-A")
        materialize(containerA, saveSet)

        // Export A to an in-memory zip.
        val zipA = ByteArrayOutputStream()
        val manifest = manifestFor(containerA)
        val exportedCount = ArchiveCodec.export(
            manifest = manifest,
            roots = listOf(ArchiveCodec.ExportRoot(rootId, containerA)),
            openDest = { zipA },
        )
        assertEquals("export must write every save file", saveSet.size, exportedCount)

        // Import into a fresh temp container root B.
        val containerB = newTempDir("archive-B")
        val stagingB = newTempDir("archive-staging-B")
        val importedCount = ArchiveCodec.import(
            openSource = { ByteArrayInputStream(zipA.toByteArray()) },
            resolveRoot = { id -> if (id == rootId) containerB else null },
            stagingDir = stagingB,
        )
        assertEquals("import must write every save file", saveSet.size, importedCount)

        // The imported tree under B must equal the original save set (compare by content lists so
        // ByteArray reference equality does not defeat the map comparison).
        assertEquals(
            "imported save set must equal the original",
            toContentMap(saveSet),
            readSaveSet(containerB),
        )

        // Re-export B and compare the archives' file entries + bytes to A's.
        val zipB = ByteArrayOutputStream()
        ArchiveCodec.export(
            manifest = manifestFor(containerB),
            roots = listOf(ArchiveCodec.ExportRoot(rootId, containerB)),
            openDest = { zipB },
        )

        val entriesA = readArchiveFileEntries(zipA.toByteArray())
        val entriesB = readArchiveFileEntries(zipB.toByteArray())
        assertEquals(
            "re-exported archive must contain the same file entries + bytes",
            entriesA,
            entriesB,
        )
        // And those entries must correspond to the original save set (prefixed by files/<rootId>/).
        val expectedEntries = saveSet.entries.associate { (rel, bytes) ->
            "files/$rootId/$rel" to bytes.toList()
        }
        assertEquals("archive entries must match the original save set", expectedEntries, entriesA)
    }

    // ---- helpers -----------------------------------------------------------

    private fun manifestFor(root: Path): SaveArchiveManifest = SaveArchiveManifest(
        version = 5,
        gameId = 440,
        gameName = "Round Trip Game",
        exportedAt = 1_700_000_000_000L,
        roots = listOf(SaveRoot(rootId = rootId, path = root.toString())),
    )

    /**
     * Generate a deterministic set of save files: relative forward-slash paths (nested dirs, safe
     * single-segment names) mapped to arbitrary byte contents (including empty files). Paths are
     * built from segment lists joined by '/', so nothing can escape the root.
     */
    private fun generateSaveSet(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        minFiles: Int,
    ): Map<String, ByteArray> {
        val fileCount = Math.floorMod(fileCountSeed, 6) + minFiles
        val result = LinkedHashMap<String, ByteArray>()
        var pRng = pathSeed
        var cRng = contentSeed
        var i = 0
        while (result.size < fileCount) {
            pRng = nextSeed(pRng)
            cRng = nextSeed(cRng)
            val relPath = safeRelativePath(pRng, i)
            val content = safeContent(cRng)
            result[relPath] = content
            i++
        }
        return result
    }

    /** Build a safe nested relative path with 1..3 segments; unique per index. */
    private fun safeRelativePath(seed: Long, index: Int): String {
        val depth = Math.floorMod(seed, 3).toInt() + 1
        val segments = (0 until depth).map { d ->
            val s = nextSeed(seed + d)
            "dir${Math.floorMod(s, 4)}"
        }
        // Ensure uniqueness with the index on the file leaf.
        val leaf = "save_${index}_${Math.floorMod(seed, 1000)}.dat"
        return (segments.dropLast(1) + leaf).joinToString("/")
    }

    /** Arbitrary content, possibly empty. */
    private fun safeContent(seed: Long): ByteArray {
        val len = Math.floorMod(seed, 64).toInt() // 0..63, includes empty files
        val bytes = ByteArray(len)
        var s = seed
        for (j in 0 until len) {
            s = nextSeed(s)
            bytes[j] = (s and 0xFF).toByte()
        }
        return bytes
    }

    private fun nextSeed(seed: Long): Long = seed * 6364136223846793005L + 1442695040888963407L

    private fun materialize(root: Path, saveSet: Map<String, ByteArray>) {
        saveSet.forEach { (rel, bytes) ->
            val file = root.resolve(rel)
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, bytes)
        }
    }

    /** Read the container tree back as a relative-path -> byte-list map (content-comparable). */
    private fun readSaveSet(root: Path): Map<String, List<Byte>> {
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

    /** Normalize a save set to content-list values for map equality. */
    private fun toContentMap(saveSet: Map<String, ByteArray>): Map<String, List<Byte>> =
        saveSet.mapValues { it.value.toList() }

    /** Extract `files/...` entries from a zip as name -> byte-list (excluding the manifest). */
    private fun readArchiveFileEntries(zipBytes: ByteArray): Map<String, List<Byte>> {
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
