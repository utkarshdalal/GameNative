package app.gamenative.utils

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUtilsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun getFolderSize_countsHardlinkedFilesOnce() {
        val folder = tempFolder.newFolder("game")
        File(folder, "private.bin").writeBytes(ByteArray(1024))
        val original = File(folder, "mods/original.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(4096)) }
        Files.createLink(File(folder, "linked.bin").toPath(), original.toPath())
        Files.createLink(File(folder, "mods/linked-again.bin").toPath(), original.toPath())

        assertEquals(5120L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun getFolderSize_skipsFilesHardlinkedOutsideTheTree() {
        val folder = tempFolder.newFolder("container")
        val shared = tempFolder.newFolder("shared")
        File(folder, "private.bin").writeBytes(ByteArray(1024))
        val sharedFile = File(shared, "shared.bin").apply { writeBytes(ByteArray(4096)) }
        Files.createLink(File(folder, "linked.bin").toPath(), sharedFile.toPath())
        // Two of three links in-tree is still not all of them.
        Files.createLink(File(folder, "linked-again.bin").toPath(), sharedFile.toPath())

        assertEquals(1024L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun getFolderSize_sumsRegularFiles() {
        val folder = tempFolder.newFolder("game")
        val sub = File(folder, "sub").apply { mkdirs() }
        File(folder, "a.bin").writeBytes(ByteArray(1024))
        File(sub, "b.bin").writeBytes(ByteArray(2048))

        assertEquals(3072L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun directorySizeOnDisk_doesNotFollowSymlinks() {
        val folder = tempFolder.newFolder("game")
        val outside = tempFolder.newFolder("workshop")
        File(folder, "game.exe").writeBytes(ByteArray(1024))
        File(outside, "mod.pak").writeBytes(ByteArray(8192))
        // GOG's _CommonRedist/ISI/rootdir points back at the install dir itself.
        File(folder, "_CommonRedist/ISI").mkdirs()
        Files.createSymbolicLink(File(folder, "_CommonRedist/ISI/rootdir").toPath(), folder.toPath())
        Files.createSymbolicLink(File(folder, "workshop").toPath(), outside.toPath())
        Files.createSymbolicLink(File(folder, "mod-link.pak").toPath(), File(outside, "mod.pak").toPath())

        assertEquals(1024L, StorageUtils.directorySizeOnDisk(folder.toPath()))
        assertEquals(1024L, FileUtils.calculateDirectorySize(folder))
    }

    @Test
    fun directorySizeOnDisk_resolvesSymlinkedRoot() {
        val folder = tempFolder.newFolder("game")
        File(folder, "game.exe").writeBytes(ByteArray(1024))
        val link = File(tempFolder.root, "game-link")
        Files.createSymbolicLink(link.toPath(), folder.toPath())

        assertEquals(1024L, StorageUtils.directorySizeOnDisk(link.toPath()))
    }

    private val defaultStorageUuid = UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69")

    @Test
    fun `built-in primary storage is not a separate install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `default UUID overrides weaker physical and legacy signals`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `adopted primary storage is an install target`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `legacy adopted primary falls back to its volume UUID`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `modern primary does not use a raw UUID when its typed UUID is unavailable`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `physical primary storage remains an install target when UUID lookup fails`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `unidentified emulated primary storage is not an install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }
}
