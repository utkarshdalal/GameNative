package app.gamenative.savebackup

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Example tests for [SymlinkGuard] and its use in the import codecs (CWE-59).
 *
 * The prior leaf-only `Files.isSymbolicLink(destination)` check missed the case where an
 * **ancestor** directory between the save root and the destination is a symlink — `Files.move`
 * would follow it and write outside the save root. [SymlinkGuard.hasSymlinkAncestor] detects a
 * symlink anywhere in the chain, and [RawTreeCodec]/[ArchiveCodec] reject such entries before
 * staging any bytes.
 *
 * Symlink creation is guarded with [assumeTrue] so filesystems that disallow symlinks skip the
 * symlink-specific assertions rather than failing.
 */
class SymlinkAncestorGuardTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    @Test
    fun cleanChainHasNoSymlinkAncestor() {
        val root = newTempDir("symguard-clean")
        val dest = root.resolve("a/b/c.txt")
        Files.createDirectories(dest.parent)
        assertFalse(SymlinkGuard.hasSymlinkAncestor(root, dest))
    }

    @Test
    fun symlinkedAncestorIsDetected() {
        val root = newTempDir("symguard-link")
        val outside = newTempDir("symguard-outside")
        // root/link -> outside ; a destination under root/link/... escapes via the symlink.
        val link = root.resolve("link")
        val created = try {
            Files.createSymbolicLink(link, outside)
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("filesystem does not support symlinks", created)

        val dest = root.resolve("link/evil.txt")
        assertTrue(SymlinkGuard.hasSymlinkAncestor(root, dest))
    }

    @Test
    fun rawTreeImportRejectsSymlinkedAncestor() {
        val root = newTempDir("symguard-raw-root")
        val outside = newTempDir("symguard-raw-outside")
        val link = root.resolve("saves")
        val created = try {
            Files.createSymbolicLink(link, outside)
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("filesystem does not support symlinks", created)

        val staging = newTempDir("symguard-raw-staging")
        val source = object : RawTreeCodec.TreeReader {
            override fun listFiles(): List<RawTreeCodec.TreeReader.Entry> = listOf(
                RawTreeCodec.TreeReader.Entry(
                    relativePath = "saves/slot1.sav",
                    length = 3,
                    isSymlink = false,
                    openInput = { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                ),
            )
        }

        assertThrows(RawTreeCodec.ImportException.Symlink::class.java) {
            RawTreeCodec.import(source = source, destinationRoot = root, stagingDir = staging)
        }
        // Nothing was written through the symlink into the outside directory.
        assertFalse(Files.exists(outside.resolve("slot1.sav"), LinkOption.NOFOLLOW_LINKS))
    }

    private fun newTempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
