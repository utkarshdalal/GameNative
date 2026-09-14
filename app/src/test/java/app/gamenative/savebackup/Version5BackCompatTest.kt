package app.gamenative.savebackup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version-5 archive back-compat example — game-save-backup Task 7.5 (Req 8.2).
 *
 * The retired Steam manifest wrote the game identifier under the field name `steamAppId`; the
 * generalized [SaveArchiveManifest] names it `gameId` and accepts the old name through a
 * `@JsonNames("steamAppId")` alias. These tests build a version-5 archive whose `manifest.json`
 * uses the OLD field name (written by hand, not via the serializer) and assert:
 *  1. [ArchiveCodec.readManifest] decodes it, mapping `steamAppId` → `gameId` (== 440).
 *  2. [ArchiveCodec.import] succeeds and imports the archive's file entry into the container with
 *     byte-for-byte identical content.
 *
 * Plain JUnit 4 `@Test` (no property generation, no Robolectric).
 */
class Version5BackCompatTest {

    private val tempDirs = mutableListOf<Path>()
    private val rootId = "winsavedgames"

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    /** A hand-written version-5 archive using the OLD `steamAppId` manifest field. */
    private fun buildLegacyArchive(saveName: String, saveBytes: ByteArray): ByteArray {
        // Written by hand so the manifest genuinely uses `steamAppId` (not `gameId`).
        val manifestJson = """
            {"version":5,"steamAppId":440,"gameName":"TF2","exportedAt":1700000000000,
            "roots":[{"rootId":"$rootId","path":"/legacy/container/path"}]}
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("files/$rootId/$saveName"))
            zip.write(saveBytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun readManifestDecodesLegacySteamAppIdAlias() {
        val zip = buildLegacyArchive("save.dat", byteArrayOf(1, 2, 3))

        val manifest = ArchiveCodec.readManifest { ByteArrayInputStream(zip) }

        // The @JsonNames("steamAppId") alias maps the old field onto gameId.
        assertEquals("legacy steamAppId must decode into gameId", 440, manifest.gameId)
        assertEquals(5, manifest.version)
        assertEquals("TF2", manifest.gameName)
        assertEquals(1700000000000L, manifest.exportedAt)
        assertEquals(1, manifest.roots.size)
        assertEquals(rootId, manifest.roots.first().rootId)
    }

    @Test
    fun importSucceedsForLegacySteamAppIdArchive() {
        val saveName = "profile/slot1.sav"
        val saveBytes = byteArrayOf(10, 20, 30, 40, 50)
        val zip = buildLegacyArchive(saveName, saveBytes)

        val container = newTempDir("v5-backcompat-B")
        val staging = newTempDir("v5-backcompat-staging")

        val imported = ArchiveCodec.import(
            openSource = { ByteArrayInputStream(zip) },
            resolveRoot = { id -> if (id == rootId) container else null },
            stagingDir = staging,
        )

        assertEquals("exactly one file entry imported", 1, imported)

        // The file entry landed in the container at its relative path with identical bytes.
        val destination = container.resolve(saveName)
        assertTrue("imported save file must exist", Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals("imported bytes must match source", saveBytes, Files.readAllBytes(destination))
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
