package app.gamenative.utils

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
