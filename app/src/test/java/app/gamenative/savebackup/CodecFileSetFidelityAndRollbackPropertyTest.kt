package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property tests for export/import file-set fidelity and rollback — game-save-backup Task 10.4
 * (Properties 7, 8, 9).
 *
 * ## Layer under test and why
 *
 * These properties describe the file-set fidelity (Properties 7, 8) and rollback (Property 9)
 * semantics of the save-backup transfer. Those semantics live in the **codec layer**
 * ([ArchiveCodec] / [RawTreeCodec]) and the shared rollback-safe commit ([StagedCommit]), NOT in
 * [DefaultSaveBackupEngine]. The engine only adapts SAF (`Context`/`ContainerUtils`/`DocumentFile`
 * + `ContentResolver` streams) onto those codecs; that adapter needs an Android device/emulator and
 * is not exercisable in plain JVM unit tests. The meaningful, deterministic way to validate
 * Properties 7/8/9 off-device is therefore against the codecs and [StagedCommit] directly, matching
 * the established Task 7.4 test style ([ArchiveCodecRoundTripPropertyTest],
 * [RawTreeCodecRoundTripPropertyTest], [CodecSafetyPropertyTest]).
 *
 * Mapping of engine-level branches that this file covers structurally:
 *  - **Property 7 empty → NoSavesFound / no external changes:** the engine returns
 *    [BackupResult.NoSavesFound] *before* creating any destination when `regularFilesUnder(savePath)`
 *    is empty. We validate the underlying invariant here: for an EMPTY save root the codecs write no
 *    save FILES (archive → manifest only, raw tree → an empty timestamped subdir), and the
 *    "regular files under an empty root" set the engine keys its decision on is empty. The
 *    NoSavesFound *mapping* is the engine's; the "no save data written" fact is the codec's and is
 *    asserted here.
 *  - **Property 8 empty source → no container changes:** archive with a manifest but zero files
 *    imports 0 files and leaves the container untouched; an empty raw-tree source throws
 *    [RawTreeCodec.ImportException.SourceEmpty] (which the engine maps to NoSavesFound) and leaves
 *    the container untouched.
 *  - **Property 9 mid-import failure → exact pre-import restore:** driven against [StagedCommit]
 *    directly (the component that *guarantees* the rollback), plus one codec-level rollback check.
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). The external archive side is in-memory
 * ([ByteArrayOutputStream]/[ByteArrayInputStream]); the external raw-tree side is a temp-dir-backed
 * [RawTreeCodec.TreeWriter]/[RawTreeCodec.TreeReader]. The container side uses real temp
 * directories on the same filesystem as the codec staging dirs. Every `@Property` runs a minimum of
 * 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class CodecFileSetFidelityAndRollbackPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    // Single-segment rootId, matching the codec on-disk scheme (entries are split at the first '/'
    // after `files/`, so the rootId itself must not contain a separator).
    private val rootId = "winsavedgames"

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // ------------------------------------------------------------------------
    // Property 7: Export copies exactly the resolved save set
    // ------------------------------------------------------------------------

    // Feature: game-save-backup, Property 7: Export copies exactly the resolved save set.
    // Archive export writes to the external location exactly the set of files under the resolved
    // save location — same relative paths, byte-identical content — and nothing else.
    // Validates: Requirements 4.3, 4.4
    @Property(trials = 150)
    fun archiveExportWritesExactlyTheResolvedSaveSet(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
    ) {
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)
        val container = newTempDir("p7-arc")
        materialize(container, saveSet)

        val zip = ByteArrayOutputStream()
        val written = ArchiveCodec.export(
            manifest = manifestFor(container),
            roots = listOf(ArchiveCodec.ExportRoot(rootId, container)),
            openDest = { zip },
        )
        assertEquals("export must write every save file", saveSet.size, written)

        // The archive's file entries (excluding the manifest) must be EXACTLY the resolved save set,
        // laid out as files/<rootId>/<relpath>, byte-for-byte.
        val expected = saveSet.entries.associate { (rel, bytes) ->
            "files/$rootId/$rel" to bytes.toList()
        }
        assertEquals(
            "archive must contain exactly the resolved save set (paths + bytes)",
            expected,
            readArchiveFileEntries(zip.toByteArray()),
        )
    }

    // Feature: game-save-backup, Property 7: Export copies exactly the resolved save set.
    // Raw-tree export reproduces, under its fresh timestamped subdir, exactly the set of files under
    // the resolved save location — same relative paths, byte-identical content — and nothing else.
    // Validates: Requirements 4.3, 4.4
    @Property(trials = 150)
    fun rawTreeExportWritesExactlyTheResolvedSaveSet(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        timestampSeed: Long,
    ) {
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)
        val container = newTempDir("p7-raw")
        materialize(container, saveSet)

        val external = newTempDir("p7-raw-ext")
        val ts = 1_700_000_000_000L + Math.floorMod(timestampSeed, 10_000_000).toLong()
        val written = RawTreeCodec.export(
            roots = listOf(RawTreeCodec.ExportRoot(container)),
            gameName = "P7Game",
            dest = TempDirTreeWriter(external),
            timestampMillis = ts,
        )
        assertEquals("export must write every save file", saveSet.size, written)

        val produced = singleSubdir(external)
        assertEquals(
            "raw-tree export must contain exactly the resolved save set (paths + bytes)",
            toContentMap(saveSet),
            readSaveSet(produced),
        )
    }

    // Feature: game-save-backup, Property 7: Export copies exactly the resolved save set.
    // Empty resolved save set → no save DATA is written by either codec, and the "regular files
    // under the resolved location" set the engine keys NoSavesFound on is empty (no external
    // changes). The NoSavesFound *mapping* is DefaultSaveBackupEngine's; this asserts the codec-
    // layer "no save files written" invariant + the empty-root file set that drives that mapping.
    // Validates: Requirements 4.5
    @Property(trials = 100)
    fun emptyResolvedSaveSetProducesNoSaveDataAndNoSavesFoundSignal(
        contentSeed: Long,
    ) {
        // An empty (existing but file-less) resolved save location.
        val container = newTempDir("p7-empty")

        // Engine decision input: regular files under an empty root is empty → NoSavesFound branch.
        assertTrue(
            "regular files under an empty save root must be empty (drives NoSavesFound)",
            regularFilesUnder(container).isEmpty(),
        )

        // Archive export of an empty root writes ONLY the manifest, no `files/` save entries.
        val zip = ByteArrayOutputStream()
        val archiveWritten = ArchiveCodec.export(
            manifest = manifestFor(container),
            roots = listOf(ArchiveCodec.ExportRoot(rootId, container)),
            openDest = { zip },
        )
        assertEquals("no save files written for an empty root", 0, archiveWritten)
        assertTrue(
            "archive must contain no `files/` save entries for an empty root",
            readArchiveFileEntries(zip.toByteArray()).isEmpty(),
        )

        // Raw-tree export of an empty root writes ZERO files (only the empty timestamped subdir).
        val external = newTempDir("p7-empty-ext")
        val ts = 1_700_000_000_000L + Math.floorMod(contentSeed, 10_000_000).toLong()
        val rawWritten = RawTreeCodec.export(
            roots = listOf(RawTreeCodec.ExportRoot(container)),
            gameName = "P7Empty",
            dest = TempDirTreeWriter(external),
            timestampMillis = ts,
        )
        assertEquals("no save files written for an empty root", 0, rawWritten)
        assertTrue(
            "raw-tree export must write no save files for an empty root",
            readSaveSet(singleSubdir(external)).isEmpty(),
        )
    }

    // ------------------------------------------------------------------------
    // Property 8: Import copies exactly the source set and overwrites by relative path
    // ------------------------------------------------------------------------

    // Feature: game-save-backup, Property 8: Import copies exactly the source set and overwrites by
    // relative path. Archive import produces, for every source file, a destination file with the
    // same relative path + bytes; a pre-existing colliding path is overwritten without prompting.
    // Validates: Requirements 5.4, 5.5, 5.10, 11.3
    @Property(trials = 150)
    fun archiveImportCopiesSourceSetAndOverwritesByRelativePath(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        collisionSeed: Long,
    ) {
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)
        val container = newTempDir("p8-arc-B")
        val staging = newTempDir("p8-arc-staging")

        // Pre-populate a pre-existing file at a relative path that COLLIDES with one source entry,
        // with different bytes, to prove overwrite-by-relative-path without prompting.
        val collidingRel = saveSet.keys.elementAt(Math.floorMod(collisionSeed, saveSet.size))
        val staleBytes = byteArrayOf(0xB, 0xA, 0xD)
        writeFile(container, collidingRel, staleBytes)

        val zip = buildArchive(
            roots = listOf(SaveRoot(rootId, container.toString())),
            entries = saveSet.entries.map { "files/$rootId/${it.key}" to it.value },
        )

        val imported = ArchiveCodec.import(
            openSource = { ByteArrayInputStream(zip) },
            resolveRoot = { id -> if (id == rootId) container else null },
            stagingDir = staging,
        )
        assertEquals("import must write every source file", saveSet.size, imported)

        // Every source file present at same relative path + bytes; colliding path overwritten.
        assertEquals(
            "imported container must equal the source set exactly (colliding path overwritten)",
            toContentMap(saveSet),
            readSaveSet(container),
        )
    }

    // Feature: game-save-backup, Property 8: Import copies exactly the source set and overwrites by
    // relative path. Raw-tree import produces, for every source file, a destination file with the
    // same relative path + bytes; a pre-existing colliding path is overwritten without prompting.
    // Validates: Requirements 5.4, 5.5, 5.10, 11.3
    @Property(trials = 150)
    fun rawTreeImportCopiesSourceSetAndOverwritesByRelativePath(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        collisionSeed: Long,
    ) {
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 1)
        val container = newTempDir("p8-raw-B")
        val staging = newTempDir("p8-raw-staging")

        val collidingRel = saveSet.keys.elementAt(Math.floorMod(collisionSeed, saveSet.size))
        writeFile(container, collidingRel, byteArrayOf(0xB, 0xA, 0xD))

        val reader = FakeTreeReader(
            saveSet.entries.map { entry(it.key, it.value, isSymlink = false) },
        )

        val imported = RawTreeCodec.import(reader, container, staging)
        assertEquals("import must write every source file", saveSet.size, imported)

        assertEquals(
            "imported container must equal the source set exactly (colliding path overwritten)",
            toContentMap(saveSet),
            readSaveSet(container),
        )
    }

    // Feature: game-save-backup, Property 8: Import copies exactly the source set and overwrites by
    // relative path. An empty source produces no container changes: an archive with a manifest but
    // zero files imports 0 files and leaves the container untouched; an empty raw-tree source throws
    // SourceEmpty and leaves the container untouched.
    // Validates: Requirements 5.6, 9.3
    @Property(trials = 100)
    fun emptySourceProducesNoContainerChanges(
        preExistingSeed: Long,
    ) {
        // Archive branch: manifest-only zip (zero `files/` entries).
        val archiveContainer = newTempDir("p8-empty-arc-B")
        val archiveStaging = newTempDir("p8-empty-arc-staging")
        val archivePre = prePopulate(archiveContainer, preExistingSeed)
        val emptyZip = buildArchive(
            roots = listOf(SaveRoot(rootId, archiveContainer.toString())),
            entries = emptyList(),
        )
        val importedArchive = ArchiveCodec.import(
            openSource = { ByteArrayInputStream(emptyZip) },
            resolveRoot = { id -> if (id == rootId) archiveContainer else null },
            stagingDir = archiveStaging,
        )
        assertEquals("empty archive imports zero files", 0, importedArchive)
        assertEquals(
            "container unchanged when the archive source has no files",
            archivePre,
            readSaveSet(archiveContainer),
        )

        // Raw-tree branch: empty source → SourceEmpty, container unchanged.
        val rawContainer = newTempDir("p8-empty-raw-B")
        val rawStaging = newTempDir("p8-empty-raw-staging")
        val rawPre = prePopulate(rawContainer, preExistingSeed + 1)
        assertThrows(RawTreeCodec.ImportException.SourceEmpty::class.java) {
            RawTreeCodec.import(FakeTreeReader(emptyList()), rawContainer, rawStaging)
        }
        assertEquals(
            "container unchanged when the raw-tree source is empty",
            rawPre,
            readSaveSet(rawContainer),
        )
    }

    // ------------------------------------------------------------------------
    // Property 9: Failed import restores pre-import state
    // ------------------------------------------------------------------------

    // Feature: game-save-backup, Property 9: Failed import restores pre-import state.
    // Driven against StagedCommit directly — the component that GUARANTEES the rollback (both codecs
    // delegate their commit phase to StagedCommit.commit). A mid-commit move failure must report
    // failure AND restore the destination tree to EXACTLY its pre-import state: pre-existing
    // overwritten files restored to their ORIGINAL bytes, net-new files absent (no partial files),
    // untouched pre-existing files unchanged.
    //
    // Forcing the failure: the commit applies operations in list order. We build several valid
    // operations and engineer the LAST one to fail by making its destination a NON-EMPTY DIRECTORY,
    // so Files.move onto it throws (DirectoryNotEmptyException) after earlier files were already
    // applied — exercising the snapshot-restore of an already-overwritten file. Because the failing
    // op is not first, at least one earlier overwrite is rolled back.
    // Validates: Requirements 5.9, 13.3
    @Property(trials = 150)
    fun stagedCommitFailureRestoresExactPreImportState(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        priorSeed: Long,
    ) {
        val container = newTempDir("p9-dst")
        val staging = newTempDir("p9-staging")

        // Generate a source save set (>=2 files so at least one earlier op precedes the failing op).
        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 2)
        val rels = saveSet.keys.toList()

        // Snapshot of the exact pre-import destination state we must restore to.
        val preImport = LinkedHashMap<String, ByteArray>()

        // Make SOME source paths collide with pre-existing files (different bytes) so rollback must
        // restore prior content; leave the rest net-new so rollback must delete them.
        rels.forEachIndexed { index, rel ->
            if (Math.floorMod(priorSeed + index, 2L) == 0L) {
                val prior = ByteArray(Math.floorMod(priorSeed + index, 24L).toInt() + 1) {
                    (it * 7 + index + 1).toByte()
                }
                writeFile(container, rel, prior)
                preImport[rel] = prior
            }
        }
        val preImportSnapshot = toContentMap(preImport)

        // Stage each source file into the staging tree (as the codecs do before committing).
        val operations = rels.map { rel ->
            val stagedFile = staging.resolve("stage").resolve(rel)
            stagedFile.parent?.let { Files.createDirectories(it) }
            Files.write(stagedFile, saveSet.getValue(rel))
            StagedCommit.Operation(staged = stagedFile, destination = container.resolve(rel))
        }.toMutableList()

        // Engineer the LAST op to FAIL: replace its destination with a non-empty directory so the
        // commit's Files.move onto it throws after all earlier ops have been applied.
        val failingRel = "fail_${Math.floorMod(pathSeed, 100000)}.dat"
        val failingDest = container.resolve(failingRel)
        Files.createDirectories(failingDest)
        Files.write(failingDest.resolve("blocker.dat"), byteArrayOf(1, 2, 3))
        // The blocker directory + its child are part of the pre-import state and must survive
        // (asserted below alongside preImportSnapshot).
        val failingStaged = staging.resolve("stage-fail.dat")
        Files.write(failingStaged, byteArrayOf(9, 9, 9))
        operations += StagedCommit.Operation(staged = failingStaged, destination = failingDest)

        // Commit must throw (failure reported) ...
        assertThrows(Throwable::class.java) {
            StagedCommit.commit(operations, snapshotParent = staging)
        }

        // ... and the destination must be EXACTLY the pre-import file state: overwritten files
        // restored to ORIGINAL bytes, net-new files absent (no partial files).
        val postState = readSaveSet(container)
        // The blocker directory's child is a legitimate pre-existing regular file; account for it.
        val expected = LinkedHashMap<String, List<Byte>>()
        expected.putAll(preImportSnapshot)
        expected["$failingRel/blocker.dat"] = listOf<Byte>(1, 2, 3)

        assertEquals(
            "failed commit must restore the exact pre-import state (bytes + no partial files)",
            expected,
            postState,
        )
        // The engineered blocker directory must still be a directory (untouched pre-existing entry).
        assertTrue(
            "pre-existing blocker directory must be untouched",
            Files.isDirectory(failingDest, LinkOption.NOFOLLOW_LINKS),
        )
    }

    // Feature: game-save-backup, Property 9: Failed import restores pre-import state.
    // One codec-level rollback check (RawTreeCodec delegates its commit to StagedCommit): a
    // mid-commit failure during a raw-tree import restores the exact pre-import state. The failure
    // is forced the same way — a source entry whose destination is a non-empty directory makes the
    // commit's Files.move throw after earlier files were applied.
    // Validates: Requirements 5.9, 13.3
    @Property(trials = 100)
    fun rawTreeImportCommitFailureRestoresExactPreImportState(
        fileCountSeed: Int,
        pathSeed: Long,
        contentSeed: Long,
        priorSeed: Long,
    ) {
        val container = newTempDir("p9-raw-dst")
        val staging = newTempDir("p9-raw-staging")

        val saveSet = generateSaveSet(fileCountSeed, pathSeed, contentSeed, minFiles = 2)
        val rels = saveSet.keys.toList()

        // Pre-populate some colliding files (overwrite → restore) and remember the exact prior state.
        val preImport = LinkedHashMap<String, ByteArray>()
        rels.forEachIndexed { index, rel ->
            if (Math.floorMod(priorSeed + index, 2L) == 0L) {
                val prior = ByteArray(Math.floorMod(priorSeed + index, 20L).toInt() + 1) {
                    (it * 5 + index + 3).toByte()
                }
                writeFile(container, rel, prior)
                preImport[rel] = prior
            }
        }

        // Engineer a source entry whose destination is a pre-existing NON-EMPTY directory, forcing
        // the commit's Files.move to throw. Use a fixed relative name at the root of the container.
        val failingRel = "raw_fail_${Math.floorMod(pathSeed, 100000)}.dat"
        val failingDest = container.resolve(failingRel)
        Files.createDirectories(failingDest)
        Files.write(failingDest.resolve("blocker.dat"), byteArrayOf(4, 5, 6))

        // Source = all valid entries plus the failing entry.
        val sourceEntries = saveSet.entries.map { entry(it.key, it.value, isSymlink = false) } +
            entry(failingRel, byteArrayOf(9, 9, 9), isSymlink = false)

        assertThrows(Throwable::class.java) {
            RawTreeCodec.import(FakeTreeReader(sourceEntries), container, staging)
        }

        val expected = LinkedHashMap<String, List<Byte>>()
        expected.putAll(toContentMap(preImport))
        expected["$failingRel/blocker.dat"] = listOf<Byte>(4, 5, 6)

        assertEquals(
            "failed raw-tree import must restore the exact pre-import state",
            expected,
            readSaveSet(container),
        )
        assertTrue(
            "pre-existing blocker directory must be untouched",
            Files.isDirectory(failingDest, LinkOption.NOFOLLOW_LINKS),
        )
    }

    // ------------------------------------------------------------------------
    // fakes / doubles
    // ------------------------------------------------------------------------

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

    /** A [RawTreeCodec.TreeWriter] mapping directory/file creation onto a real temp dir. */
    private class TempDirTreeWriter(private val root: Path) : RawTreeCodec.TreeWriter {
        override fun createDir(relativePath: String): RawTreeCodec.TreeWriter {
            val normalized = relativePath.replace('\\', '/').trim('/')
            val target = if (normalized.isEmpty()) root else root.resolve(normalized)
            Files.createDirectories(target)
            return TempDirTreeWriter(target)
        }

        override fun createFile(name: String): OutputStream {
            Files.createDirectories(root)
            return Files.newOutputStream(root.resolve(name))
        }
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private fun manifestFor(root: Path): SaveArchiveManifest = SaveArchiveManifest(
        version = 5,
        gameId = 440,
        gameName = "Fidelity Game",
        exportedAt = 1_700_000_000_000L,
        roots = listOf(SaveRoot(rootId = rootId, path = root.toString())),
    )

    /** Build a zip by hand with a manifest declaring [roots] plus the given `files/` entries. */
    private fun buildArchive(roots: List<SaveRoot>, entries: List<Pair<String, ByteArray>>): ByteArray {
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = 440,
            gameName = "Fidelity Game",
            exportedAt = 1_700_000_000_000L,
            roots = roots,
        )
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
        saveSet.forEach { (rel, bytes) -> writeFile(root, rel, bytes) }
    }

    private fun writeFile(root: Path, rel: String, bytes: ByteArray) {
        val file = root.resolve(rel)
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, bytes)
    }

    /** Pre-populate the destination with a single known file; returns its content map. */
    private fun prePopulate(root: Path, seed: Long): Map<String, List<Byte>> {
        val name = "preexisting_${Math.floorMod(seed, 1000)}.dat"
        val content = ByteArray(Math.floorMod(seed, 32).toInt() + 1) { (it + 1).toByte() }
        writeFile(root, name, content)
        return mapOf(name to content.toList())
    }

    /** The set of regular (non-directory, non-symlink) files under [root] — mirrors the engine. */
    private fun regularFilesUnder(root: Path): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .collect(java.util.stream.Collectors.toList())
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

    /** Return the single subdirectory under [dir] (the produced `<name>_saves_<ts>/`). */
    private fun singleSubdir(dir: Path): Path {
        val subdirs = Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        assertTrue("export must produce exactly one subdir, found $subdirs", subdirs.size == 1)
        return subdirs.single()
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
