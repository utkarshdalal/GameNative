package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.utils.CloudSaveBackupManager.BackupRoot
import app.gamenative.utils.CloudSaveBackupManager.Kind
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudSaveBackupManagerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var saves: File
    private var keep = 3

    private val provider = CloudSaveBackupManager.PROVIDER_STEAM
    private val gameId = "4242"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "save_backups").deleteRecursively()
        root = File(context.cacheDir, "save-backup-test-${System.nanoTime()}")
        saves = File(root, "saves").apply { mkdirs() }
        keep = 3
        mockkObject(PrefManager)
        every { PrefManager.saveBackupsToKeep } answers { keep }
    }

    @After
    fun tearDown() {
        unmockkObject(PrefManager)
        root.deleteRecursively()
        File(context.filesDir, "save_backups").deleteRecursively()
    }

    private fun write(file: File, text: String, mtime: Long = 1_700_000_000_000L): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        file.setLastModified(mtime)
        return file
    }

    private fun backup(roots: List<BackupRoot> = listOf(BackupRoot("saves", saves)), kind: Kind = Kind.PRE_SYNC): Boolean =
        runBlocking { CloudSaveBackupManager.backup(context, provider, gameId, roots, kind) }

    private fun list() = CloudSaveBackupManager.listBackups(context, provider, gameId)

    private fun entries(backup: CloudSaveBackupManager.SaveBackup): Set<String> =
        ZipFile(backup.file).use { zip -> zip.entries().toList().map { it.name }.toSet() }

    private fun read(backup: CloudSaveBackupManager.SaveBackup, name: String): String =
        ZipFile(backup.file).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString() }

    @Test
    fun backupWritesZipAndSidecar() {
        write(File(saves, "slot1.sav"), "one")
        write(File(saves, "sub/slot2.sav"), "two!")

        assertTrue(backup())

        val backups = list()
        assertEquals(1, backups.size)
        val b = backups.single()
        assertEquals(Kind.PRE_SYNC, b.kind)
        assertEquals(2, b.fileCount)
        assertEquals(7L, b.sizeBytes)
        assertTrue(b.file.name.matches(Regex("\\d{8}-\\d{6}-\\d{3}\\.zip")))
        assertEquals(File(File(context.filesDir, "save_backups"), "${provider}_$gameId"), b.file.parentFile)
        assertEquals(setOf("files/saves/slot1.sav", "files/saves/sub/slot2.sav"), entries(b))
        assertEquals("two!", read(b, "files/saves/sub/slot2.sav"))

        val sidecar = JSONObject(File(b.file.parentFile, b.file.name.removeSuffix(".zip") + ".json").readText())
        assertEquals(provider, sidecar.getString("provider"))
        assertEquals(gameId, sidecar.getString("gameId"))
        assertEquals("PRE_SYNC", sidecar.getString("kind"))
        val roots = sidecar.getJSONArray("roots")
        assertEquals(1, roots.length())
        assertEquals("saves", roots.getJSONObject(0).getString("id"))
        assertEquals(saves.canonicalPath, roots.getJSONObject(0).getString("absolutePath"))
        assertTrue(roots.getJSONObject(0).getBoolean("wholeDir"))
        assertTrue(b.file.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun identicalStateIsDeduplicated() {
        write(File(saves, "slot1.sav"), "one")

        assertTrue(backup())
        assertFalse(backup())
        assertEquals(1, list().size)

        write(File(saves, "slot1.sav"), "one", mtime = 1_700_000_100_000L)
        assertTrue(backup())
        assertEquals(2, list().size)
    }

    @Test
    fun prunesToConfiguredCount() {
        repeat(5) { i ->
            write(File(saves, "slot.sav"), "state $i", mtime = 1_700_000_000_000L + i * 1000)
            assertTrue(backup())
        }

        val backups = list()
        assertEquals(3, backups.size)
        assertEquals("state 4", read(backups[0], "files/saves/slot.sav"))
        assertEquals("state 2", read(backups[2], "files/saves/slot.sav"))
        assertTrue(backups.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
    }

    @Test
    fun preRestoreIsKeptSeparatelyAndNotCounted() {
        repeat(3) { i ->
            write(File(saves, "slot.sav"), "sync $i", mtime = 1_700_000_000_000L + i * 1000)
            assertTrue(backup())
        }
        repeat(2) { i ->
            write(File(saves, "slot.sav"), "restore $i", mtime = 1_700_001_000_000L + i * 1000)
            assertTrue(backup(kind = Kind.PRE_RESTORE))
        }
        write(File(saves, "slot.sav"), "sync 3", mtime = 1_700_002_000_000L)
        assertTrue(backup())

        val backups = list()
        assertEquals(3, backups.count { it.kind == Kind.PRE_SYNC })
        val preRestore = backups.filter { it.kind == Kind.PRE_RESTORE }
        assertEquals(1, preRestore.size)
        assertEquals("restore 1", read(preRestore.single(), "files/saves/slot.sav"))
    }

    @Test
    fun restorePutsFilesBackRemovesExtrasAndBumpsMtime() {
        write(File(saves, "slot1.sav"), "original one")
        write(File(saves, "sub/slot2.sav"), "original two")
        assertTrue(backup())
        val target = list().single()

        write(File(saves, "slot1.sav"), "cloud one", mtime = 1_700_000_500_000L)
        File(saves, "sub/slot2.sav").delete()
        File(saves, "sub").delete()
        write(File(saves, "extra.sav"), "from cloud")

        val before = System.currentTimeMillis() - 2000
        assertTrue(runBlocking { CloudSaveBackupManager.restore(context, target) })

        assertEquals("original one", File(saves, "slot1.sav").readText())
        assertEquals("original two", File(saves, "sub/slot2.sav").readText())
        assertFalse(File(saves, "extra.sav").exists())
        assertTrue(File(saves, "slot1.sav").lastModified() >= before)
        assertTrue(File(saves, "sub/slot2.sav").lastModified() >= before)
        assertTrue(saves.walkTopDown().none { it.name.endsWith(".part") })

        val preRestore = list().filter { it.kind == Kind.PRE_RESTORE }
        assertEquals(1, preRestore.size)
        assertEquals(setOf("files/saves/slot1.sav", "files/saves/extra.sav"), entries(preRestore.single()))
        assertEquals("cloud one", read(preRestore.single(), "files/saves/slot1.sav"))
        assertTrue(list().contains(target))
    }

    @Test
    fun restoreLeavesExtrasForExplicitRoots() {
        val a = write(File(saves, "a.sav"), "a")
        assertTrue(backup(listOf(BackupRoot("explicit", saves, listOf(a)))))
        val target = list().single()

        write(a, "changed", mtime = 1_700_000_900_000L)
        write(File(saves, "other.dat"), "not ours")

        assertTrue(runBlocking { CloudSaveBackupManager.restore(context, target) })
        assertEquals("a", a.readText())
        assertTrue(File(saves, "other.dat").exists())
    }

    @Test
    fun restoreRefusesMissingSidecar() {
        write(File(saves, "a.sav"), "a")
        assertTrue(backup())
        val target = list().single()
        File(target.file.parentFile, target.file.name.removeSuffix(".zip") + ".json").delete()

        assertFalse(runBlocking { CloudSaveBackupManager.restore(context, target) })
        assertTrue(list().isEmpty())
    }

    @Test
    fun explicitFilesRootOnlyArchivesThoseFiles() {
        val a = write(File(saves, "a.sav"), "a")
        write(File(saves, "b.sav"), "b")
        val nested = write(File(saves, "deep/c.sav"), "c")
        val outside = write(File(root, "outside.sav"), "x")

        assertTrue(backup(listOf(BackupRoot("explicit", saves, listOf(a, nested, outside)))))

        assertEquals(setOf("files/explicit/a.sav", "files/explicit/deep/c.sav"), entries(list().single()))
    }

    @Test
    fun symlinksAreRefused() {
        write(File(saves, "real.sav"), "real")
        val outsideFile = write(File(root, "outside/secret.sav"), "secret")
        Files.createSymbolicLink(File(saves, "link.sav").toPath(), outsideFile.toPath())
        Files.createSymbolicLink(File(saves, "linkdir").toPath(), outsideFile.parentFile!!.toPath())

        assertTrue(backup())
        assertEquals(setOf("files/saves/real.sav"), entries(list().single()))

        File(context.filesDir, "save_backups").deleteRecursively()
        assertTrue(
            backup(
                listOf(BackupRoot("explicit", saves, listOf(File(saves, "real.sav"), File(saves, "link.sav"), File(saves, "linkdir/secret.sav")))),
            ),
        )
        assertEquals(setOf("files/explicit/real.sav"), entries(list().single()))
    }

    @Test
    fun offWhenKeepIsZero() {
        keep = 0
        write(File(saves, "a.sav"), "a")

        assertEquals(0, CloudSaveBackupManager.backupsToKeep())
        assertFalse(backup())
        assertTrue(list().isEmpty())
    }

    @Test
    fun sessionBacksUpAtMostOnce() {
        write(File(saves, "a.sav"), "a")
        var calls = 0
        val session = CloudSaveBackupManager.session(context, provider, gameId) {
            calls++
            listOf(BackupRoot("saves", saves))
        }

        assertTrue(runBlocking { session.ensure() })
        write(File(saves, "a.sav"), "changed", mtime = 1_700_000_300_000L)
        assertTrue(runBlocking { session.ensure() })

        assertEquals(1, calls)
        assertEquals(1, list().size)
    }

    @Test
    fun emptyRootsAreSkipped() {
        assertFalse(backup())
        assertFalse(backup(listOf(BackupRoot("missing", File(root, "nope")))))
        assertTrue(list().isEmpty())
    }
}
