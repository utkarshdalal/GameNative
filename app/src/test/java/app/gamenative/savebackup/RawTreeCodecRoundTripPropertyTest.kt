package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property test for [RawTreeCodec] round-trip fidelity — game-save-backup Task 7.4 (Property 11).
 *
 * Feature: game-save-backup, Property 11: Raw tree round-trip preserves the save set.
 *
 * For any set of save files exported as a Raw tree layout, importing that tree into a container
 * save location and re-exporting produces an equivalent set of save files — the same relative
 * paths with byte-for-byte identical contents (Req 9.1, 9.2, 9.4).
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). Because [RawTreeCodec] abstracts the external side behind
 * [RawTreeCodec.TreeWriter] / [RawTreeCodec.TreeReader], the test supplies temp-dir-backed doubles
 * (mapping createDir/createFile to real directories/files under a temp "external" dir, and
 * listFiles/openInput to reading them back with forward-slash relative paths and correct lengths).
 * This keeps the test off SAF while exercising the codec. The container side uses real temp dirs.
 *
 * The codec's export creates a fresh `<gameName>_saves_<timestamp>/` subdirectory; the import
 * reader is pointed at that produced subdirectory (discovered by listing the external dir). Every
 * `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class RawTreeCodecRoundTripPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 11: Raw tree round-trip preserves the save set —
    // export(container A) -> import(container B) -> export(container B) yields byte-identical save
    // sets keyed by relative path.
    @Property(trials = 150)
    fun rawTreeExportImportReExportPreservesSaveSet(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        timestampSeed: Long,
    ) {
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)

        // Materialize under a temp container root A.
        val containerA = newTempDir("rawtree-A")
        materialize(containerA, saveSet)

        // Export A into a temp-dir-backed external location.
        val externalA = newTempDir("rawtree-ext-A")
        // Distinct timestampMillis per trial avoids same-second subdir collisions.
        val tsA = 1_700_000_000_000L + Math.floorMod(timestampSeed, 10_000_000).toLong()
        val exportedCount = RawTreeCodec.export(
            roots = listOf(RawTreeCodec.ExportRoot(containerA)),
            gameName = "RoundTripGame",
            dest = TempDirTreeWriter(externalA),
            timestampMillis = tsA,
        )
        assertEquals("export must write every save file", saveSet.size, exportedCount)

        // The export produced exactly one `<name>_saves_<ts>/` subdir; point the reader at it.
        val producedSubdir = singleSubdir(externalA)

        // Import into a fresh container root B.
        val containerB = newTempDir("rawtree-B")
        val stagingB = newTempDir("rawtree-staging-B")
        val importedCount = RawTreeCodec.import(
            source = TempDirTreeReader(producedSubdir),
            destinationRoot = containerB,
            stagingDir = stagingB,
        )
        assertEquals("import must write every save file", saveSet.size, importedCount)

        // The imported tree under B must equal the original save set.
        assertEquals(
            "imported save set must equal the original",
            toContentMap(saveSet),
            readSaveSet(containerB),
        )

        // Re-export B and compare the produced tree to the original save set.
        val externalB = newTempDir("rawtree-ext-B")
        val tsB = tsA + 1L
        RawTreeCodec.export(
            roots = listOf(RawTreeCodec.ExportRoot(containerB)),
            gameName = "RoundTripGame",
            dest = TempDirTreeWriter(externalB),
            timestampMillis = tsB,
        )
        val reExportedSubdir = singleSubdir(externalB)
        assertEquals(
            "re-exported raw tree must equal the original save set",
            toContentMap(saveSet),
            readSaveSet(reExportedSubdir),
        )
    }

    // ---- temp-dir-backed TreeWriter / TreeReader doubles -------------------

    /** A [RawTreeCodec.TreeWriter] that maps directory/file creation onto a real temp dir. */
    private class TempDirTreeWriter(private val root: Path) : RawTreeCodec.TreeWriter {
        override fun createDir(relativePath: String): RawTreeCodec.TreeWriter {
            val normalized = relativePath.replace('\\', '/').trim('/')
            val target = if (normalized.isEmpty()) root else root.resolve(normalized)
            Files.createDirectories(target)
            return TempDirTreeWriter(target)
        }

        override fun createFile(name: String): OutputStream {
            Files.createDirectories(root)
            val file = root.resolve(name)
            return Files.newOutputStream(file)
        }
    }

    /** A [RawTreeCodec.TreeReader] that lists/reads a real temp dir with forward-slash rel paths. */
    private class TempDirTreeReader(private val root: Path) : RawTreeCodec.TreeReader {
        override fun listFiles(): List<RawTreeCodec.TreeReader.Entry> {
            if (!Files.isDirectory(root)) return emptyList()
            val entries = mutableListOf<RawTreeCodec.TreeReader.Entry>()
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    val rel = root.relativize(file).toString().replace('\\', '/')
                    entries += RawTreeCodec.TreeReader.Entry(
                        relativePath = rel,
                        length = Files.size(file),
                        isSymlink = false,
                        openInput = { Files.newInputStream(file) },
                    )
                }
            }
            return entries
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** Return the single subdirectory under [dir] (the produced `<name>_saves_<ts>/`). */
    private fun singleSubdir(dir: Path): Path {
        val subdirs = Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        assertTrue("export must produce exactly one subdir, found $subdirs", subdirs.size == 1)
        return subdirs.single()
    }

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
            result[safeRelativePath(pRng, i)] = safeContent(cRng)
            i++
        }
        return result
    }

    private fun safeRelativePath(seed: Long, index: Int): String {
        val depth = Math.floorMod(seed, 3).toInt() + 1
        val segments = (0 until depth).map { d -> "dir${Math.floorMod(nextSeed(seed + d), 4)}" }
        val leaf = "save_${index}_${Math.floorMod(seed, 1000)}.dat"
        return (segments.dropLast(1) + leaf).joinToString("/")
    }

    private fun safeContent(seed: Long): ByteArray {
        val len = Math.floorMod(seed, 64).toInt()
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

    private fun toContentMap(saveSet: Map<String, ByteArray>): Map<String, List<Byte>> =
        saveSet.mapValues { it.value.toList() }

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
