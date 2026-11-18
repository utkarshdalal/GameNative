package app.gamenative.utils

import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Simulates a depot download where manifests reference the same directory tree
 * with different casing (e.g. base game creates "Game/_work", DLC references
 * "Game/_Work"). Verifies CaseInsensitiveFileSystem resolves both to a single
 * on-disk directory.
 *
 * LIMITATION: on macOS (case-insensitive FS) these tests pass trivially because
 * the OS itself prevents duplicate-cased dirs. The tests are meaningful on
 * Linux/Android (case-sensitive FS) where duplicates would actually be created
 * without CaseInsensitiveFileSystem. CI typically runs on Linux.
 */
class CaseInsensitiveFileSystemTest {

    private lateinit var tmpDir: File
    private lateinit var fs: CaseInsensitiveFileSystem

    @Before
    fun setUp() {
        tmpDir = createTempDir("depot_test")
        fs = CaseInsensitiveFileSystem()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `mixed-case writes land in single directory tree`() {
        val root = tmpDir.toOkioPath()

        // base game creates directories with one casing
        val baseDir = root / "steamapps" / "common" / "MyGame" / "_work" / "data"
        fs.createDirectories(baseDir)
        fs.write(baseDir / "base.pak") { writeUtf8("base") }

        // DLC references same tree with different casing
        val dlcDir = root / "steamapps" / "common" / "MyGame" / "_Work" / "Data"
        fs.createDirectories(dlcDir)
        fs.write(dlcDir / "dlc.pak") { writeUtf8("dlc") }

        // verify only one directory tree exists on disk
        val gameDir = File(tmpDir, "steamapps/common/MyGame")
        val subdirs = gameDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        // on case-sensitive FS with the fix: 1 dir. on case-insensitive FS: trivially 1 dir.
        // without the fix on case-sensitive FS: would be 2 (_work and _Work)
        assertEquals(
            "expected single directory, got: ${subdirs.map { it.name }}",
            1,
            subdirs.size,
        )

        // both files should be reachable under the single tree
        val workDir = subdirs[0]
        val dataDir = workDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals(
            "expected single data dir, got: ${dataDir.map { it.name }}",
            1,
            dataDir.size,
        )

        val files = dataDir[0].listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals(listOf("base.pak", "dlc.pak"), files)
    }

    @Test
    fun `concurrent mixed-case writes from multiple threads`() {
        val root = tmpDir.toOkioPath()
        val base = root / "steamapps" / "common" / "TestGame"
        fs.createDirectories(base)

        // simulate concurrent depot workers writing with different casing
        val threads = listOf(
            Thread {
                val dir = base / "Saves" / "Profile"
                fs.createDirectories(dir)
                fs.write(dir / "slot1.sav") { writeUtf8("save1") }
            },
            Thread {
                val dir = base / "saves" / "profile"
                fs.createDirectories(dir)
                fs.write(dir / "slot2.sav") { writeUtf8("save2") }
            },
            Thread {
                val dir = base / "SAVES" / "PROFILE"
                fs.createDirectories(dir)
                fs.write(dir / "slot3.sav") { writeUtf8("save3") }
            },
        )

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val gameDir = File(tmpDir, "steamapps/common/TestGame")
        val saveDirs = gameDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals(
            "expected single saves dir, got: ${saveDirs.map { it.name }}",
            1,
            saveDirs.size,
        )

        val profileDirs = saveDirs[0].listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals(
            "expected single profile dir, got: ${profileDirs.map { it.name }}",
            1,
            profileDirs.size,
        )

        val files = profileDirs[0].listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals(listOf("slot1.sav", "slot2.sav", "slot3.sav"), files)
    }

    @Test
    fun `chunk staging paths are redirected and cleaned up on both sides`() {
        val redirectDir = createTempDir("chunk_redirect")
        try {
            val redirectFs = CaseInsensitiveFileSystem(
                chunkStagingRedirect = redirectDir.toOkioPath(),
            )
            val installDir = tmpDir.toOkioPath() / "steamapps" / "common" / "MyGame"
            val chunkDir = installDir / ".DepotDownloader" / "staging" / "chunks" / "file1"
            val chunkPath = chunkDir / "0_abc.chunk"

            redirectFs.createDirectories(installDir / ".DepotDownloader" / "staging")
            redirectFs.createDirectories(chunkDir)
            redirectFs.write(chunkPath) { writeUtf8("chunkdata") }

            // written under the redirect root, not beside the install dir
            assertTrue(File(redirectDir, "file1/0_abc.chunk").exists())
            assertFalse(File(tmpDir, "steamapps/common/MyGame/.DepotDownloader/staging/chunks/file1/0_abc.chunk").exists())

            // read back through the original path
            assertEquals("chunkdata", redirectFs.read(chunkPath) { readUtf8() })
            assertTrue(redirectFs.exists(chunkPath))

            redirectFs.delete(chunkPath)
            assertFalse(File(redirectDir, "file1/0_abc.chunk").exists())

            // deleteRecursively clears the per-file dir on the redirect side
            redirectFs.write(chunkPath) { writeUtf8("leftover") }
            redirectFs.deleteRecursively(chunkDir)
            assertFalse(File(redirectDir, "file1").exists())

            // non-chunk paths are untouched by the redirect
            redirectFs.write(installDir / "game.pak") { writeUtf8("pak") }
            assertTrue(File(tmpDir, "steamapps/common/MyGame/game.pak").exists())
        } finally {
            redirectDir.deleteRecursively()
        }
    }
}
