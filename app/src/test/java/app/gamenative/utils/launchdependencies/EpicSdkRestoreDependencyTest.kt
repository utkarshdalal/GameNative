package app.gamenative.utils.launchdependencies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import com.winlator.container.Container
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class EpicSdkRestoreDependencyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeDll(name: String, major: Int, minor: Int, patch: Int, build: Int, padding: Int): File {
        val file = tempFolder.newFile(name)
        val info = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        info.putInt(0xFEEF04BD.toInt())
        info.putInt(0x00010000)
        info.putInt((major shl 16) or minor)
        info.putInt((patch shl 16) or build)
        info.putInt((major shl 16) or minor)
        info.putInt((patch shl 16) or build)
        file.writeBytes(ByteArray(padding) { 0x41 } + info.array() + ByteArray(64))
        return file
    }

    @Test
    fun appliesTo_onlyForEpic() {
        val container = mockk<Container>(relaxed = true)
        assertTrue(EpicSdkRestoreDependency.appliesTo(container, GameSource.EPIC, 1))
        assertFalse(EpicSdkRestoreDependency.appliesTo(container, GameSource.STEAM, 1))
        assertFalse(EpicSdkRestoreDependency.appliesTo(container, GameSource.GOG, 1))
    }

    @Test
    fun readProductVersion_parsesFixedFileInfo() {
        val dll = fakeDll("a.dll", 1, 12, 0, 0, padding = 100)
        assertEquals(EpicSdkRestoreDependency.SdkVersion(1, 12, 0, 0), EpicSdkRestoreDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_findsSignatureAcrossChunkBoundary() {
        val dll = fakeDll("b.dll", 1, 19, 2, 1, padding = (1 shl 20) - 2)
        assertEquals(EpicSdkRestoreDependency.SdkVersion(1, 19, 2, 1), EpicSdkRestoreDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_returnsNullWithoutSignature() {
        val file = tempFolder.newFile("c.dll")
        file.writeBytes(ByteArray(4096) { 0x41 })
        assertNull(EpicSdkRestoreDependency.readProductVersion(file))
    }

    private fun sdkDll(dir: String, major: Int, minor: Int, patch: Int = 0, backup: Pair<Int, Int>? = null): File {
        val folder = tempFolder.newFolder(dir)
        val dll = fakeDll("$dir-sdk.dll", major, minor, patch, 0, padding = 100)
        val target = File(folder, EpicSdkRestoreDependency.SDK_DLL_NAME)
        dll.renameTo(target)
        if (backup != null) {
            fakeDll("$dir-orig.dll", backup.first, backup.second, 0, 0, padding = 100)
                .renameTo(backupOf(target))
        }
        return target
    }

    private fun backupOf(dll: File) = File(dll.parentFile, dll.name + EpicSdkRestoreDependency.BACKUP_SUFFIX)

    @Test
    fun restore_putsTheOriginalBackOverEitherSwappedBuild() {
        for ((dir, minor, patch) in listOf(Triple("a", 19, 2), Triple("b", 13, 1))) {
            val dll = sdkDll(dir, 1, minor, patch, backup = 1 to 10)
            EpicSdkRestoreDependency.restore(dll)
            assertEquals(EpicSdkRestoreDependency.SdkVersion(1, 10, 0, 0), EpicSdkRestoreDependency.readProductVersion(dll))
            assertFalse(backupOf(dll).exists())
            assertFalse(File(dll.parentFile, dll.name + ".gn-tmp").exists())
        }
    }

    @Test
    fun restore_keepsTheDllAGameUpdatePutBack() {
        val dll = sdkDll("a", 1, 16, backup = 1 to 10)
        EpicSdkRestoreDependency.restore(dll)
        assertEquals(EpicSdkRestoreDependency.SdkVersion(1, 16, 0, 0), EpicSdkRestoreDependency.readProductVersion(dll))
        assertFalse(backupOf(dll).exists())
    }

    @Test
    fun restore_leavesAGameWithoutBackupAlone() {
        val dll = sdkDll("a", 1, 10)
        val before = dll.readBytes()
        EpicSdkRestoreDependency.restore(dll)
        assertArrayEquals(before, dll.readBytes())
    }

    @Test
    fun install_deletesCachedReplacements() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = mockk<Container>(relaxed = true)
        val cached = EpicSdkRestoreDependency.cachedReplacementFiles
            .flatMap { listOf(File(context.filesDir, it), File(context.filesDir, "$it.gn-tmp")) }
        cached.forEach { it.writeBytes(ByteArray(16)) }
        assertFalse(EpicSdkRestoreDependency.isSatisfied(context, container, GameSource.EPIC, 1))

        EpicSdkRestoreDependency.install(context, container, LaunchDependencyCallbacks({}, {}), GameSource.EPIC, 1)

        cached.forEach { assertFalse(it.exists()) }
        assertTrue(EpicSdkRestoreDependency.isSatisfied(context, container, GameSource.EPIC, 1))
    }
}
