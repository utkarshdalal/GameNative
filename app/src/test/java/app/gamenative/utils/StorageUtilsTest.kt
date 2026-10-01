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
    fun getFolderSize_skipsHardlinkedFiles() {
        val folder = tempFolder.newFolder("container")
        val shared = tempFolder.newFolder("shared")
        File(folder, "private.bin").writeBytes(ByteArray(1024))
        val sharedFile = File(shared, "shared.bin").apply { writeBytes(ByteArray(4096)) }
        Files.createLink(File(folder, "linked.bin").toPath(), sharedFile.toPath())

        val expected = folder.length() + 1024L
        assertEquals(expected, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun getFolderSize_sumsFilesWithoutHardlinks() {
        val folder = tempFolder.newFolder("container")
        val sub = File(folder, "sub").apply { mkdirs() }
        File(folder, "a.bin").writeBytes(ByteArray(1024))
        File(sub, "b.bin").writeBytes(ByteArray(2048))

        val expected = folder.length() + sub.length() + 3072L
        assertEquals(expected, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
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
