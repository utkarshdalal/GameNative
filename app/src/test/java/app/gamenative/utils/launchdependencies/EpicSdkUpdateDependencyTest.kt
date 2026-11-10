package app.gamenative.utils.launchdependencies

import app.gamenative.data.GameSource
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
class EpicSdkUpdateDependencyTest {
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
        assertTrue(EpicSdkUpdateDependency.appliesTo(container, GameSource.EPIC, 1))
        assertFalse(EpicSdkUpdateDependency.appliesTo(container, GameSource.STEAM, 1))
        assertFalse(EpicSdkUpdateDependency.appliesTo(container, GameSource.GOG, 1))
    }

    @Test
    fun readProductVersion_parsesFixedFileInfo() {
        val dll = fakeDll("a.dll", 1, 12, 0, 0, padding = 100)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 12, 0, 0), EpicSdkUpdateDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_findsSignatureAcrossChunkBoundary() {
        val dll = fakeDll("b.dll", 1, 19, 2, 1, padding = (1 shl 20) - 2)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 19, 2, 1), EpicSdkUpdateDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_returnsNullWithoutSignature() {
        val file = tempFolder.newFile("c.dll")
        file.writeBytes(ByteArray(4096) { 0x41 })
        assertNull(EpicSdkUpdateDependency.readProductVersion(file))
    }

    private fun sdkDll(dir: String, major: Int, minor: Int, patch: Int = 0, backup: Pair<Int, Int>? = null): File {
        val folder = tempFolder.newFolder(dir)
        val dll = fakeDll("$dir-sdk.dll", major, minor, patch, 0, padding = 100)
        val target = File(folder, EpicSdkUpdateDependency.SDK_DLL_NAME)
        dll.renameTo(target)
        if (backup != null) {
            fakeDll("$dir-orig.dll", backup.first, backup.second, 0, 0, padding = 100)
                .renameTo(File(folder, target.name + EpicSdkUpdateDependency.BACKUP_SUFFIX))
        }
        return target
    }

    private fun backupOf(dll: File) = File(dll.parentFile, dll.name + EpicSdkUpdateDependency.BACKUP_SUFFIX)

    @Test
    fun untouchedGame_needsNothing() {
        val dll = sdkDll("a", 1, 10)
        assertFalse(EpicSdkUpdateDependency.needsRestore(dll, ""))
        assertFalse(EpicSdkUpdateDependency.needsSwap(dll, ""))
        assertFalse(EpicSdkUpdateDependency.needsSwap(dll, EpicSdkUpdateDependency.STATE_FAILED))
    }

    @Test
    fun oldSdk_isSwappedOnlyAfterACrash() {
        val dll = sdkDll("a", 1, 12)
        assertTrue(EpicSdkUpdateDependency.needsSwap(dll, EpicSdkUpdateDependency.STATE_PENDING))
        assertFalse(EpicSdkUpdateDependency.needsSwap(sdkDll("b", 1, 17), EpicSdkUpdateDependency.STATE_PENDING))
    }

    @Test
    fun earlierSwap_isRestored() {
        val dll = sdkDll("a", 1, 19, 2, backup = 1 to 10)
        assertTrue(EpicSdkUpdateDependency.needsRestore(dll, ""))
        EpicSdkUpdateDependency.restore(dll)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 10, 0, 0), EpicSdkUpdateDependency.readProductVersion(dll))
        assertFalse(backupOf(dll).exists())
        assertFalse(EpicSdkUpdateDependency.needsRestore(dll, ""))
    }

    @Test
    fun earlierSwap_isRestoredBeforeANewSwap() {
        val dll = sdkDll("a", 1, 19, 2, backup = 1 to 12)
        assertTrue(EpicSdkUpdateDependency.needsRestore(dll, EpicSdkUpdateDependency.STATE_PENDING))
        assertTrue(EpicSdkUpdateDependency.needsSwap(dll, EpicSdkUpdateDependency.STATE_PENDING))
    }

    @Test
    fun currentSwap_staysUntilItFails() {
        val dll = sdkDll("a", 1, 13, 1, backup = 1 to 12)
        assertFalse(EpicSdkUpdateDependency.needsRestore(dll, EpicSdkUpdateDependency.STATE_SWAPPED))
        assertFalse(EpicSdkUpdateDependency.needsSwap(dll, EpicSdkUpdateDependency.STATE_SWAPPED))
        assertTrue(EpicSdkUpdateDependency.needsRestore(dll, EpicSdkUpdateDependency.STATE_FAILED))
    }

    @Test
    fun restore_keepsTheOriginalAGameUpdatePutBack() {
        val dll = sdkDll("a", 1, 12, backup = 1 to 10)
        assertTrue(EpicSdkUpdateDependency.needsRestore(dll, EpicSdkUpdateDependency.STATE_SWAPPED))
        EpicSdkUpdateDependency.restore(dll)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 12, 0, 0), EpicSdkUpdateDependency.readProductVersion(dll))
        assertFalse(backupOf(dll).exists())
        assertTrue(EpicSdkUpdateDependency.needsSwap(dll, EpicSdkUpdateDependency.STATE_SWAPPED))
    }

    @Test
    fun stateAfterSdkCrash_swapsOnceThenGivesUp() {
        assertEquals(EpicSdkUpdateDependency.STATE_PENDING, EpicSdkUpdateDependency.stateAfterSdkCrash(""))
        assertEquals(EpicSdkUpdateDependency.STATE_PENDING, EpicSdkUpdateDependency.stateAfterSdkCrash(EpicSdkUpdateDependency.STATE_PENDING))
        assertEquals(EpicSdkUpdateDependency.STATE_FAILED, EpicSdkUpdateDependency.stateAfterSdkCrash(EpicSdkUpdateDependency.STATE_SWAPPED))
        assertEquals(EpicSdkUpdateDependency.STATE_FAILED, EpicSdkUpdateDependency.stateAfterSdkCrash(EpicSdkUpdateDependency.STATE_FAILED))
    }

    @Test
    fun isSdkCrashFrame_matchesOnlyTheTopBacktraceFrames() {
        assertTrue(EpicSdkUpdateDependency.isSdkCrashFrame("=>0 0x000000018012abcd in eossdk-win64-shipping (+0x12abcd) (0x000000000011f8a0)"))
        assertTrue(EpicSdkUpdateDependency.isSdkCrashFrame("  1 0x000000018012abcd EOS_Auth_Login+0x1d in EOSSDK-Win64-Shipping (0x000000000011f8a0)"))
        assertFalse(EpicSdkUpdateDependency.isSdkCrashFrame("  7 0x000000018012abcd in eossdk-win64-shipping (+0x12abcd) (0x000000000011f8a0)"))
        assertFalse(EpicSdkUpdateDependency.isSdkCrashFrame("=>0 0x000000007bc4f2a1 in ntdll (+0x4f2a1) (0x000000000011f8a0)"))
        assertFalse(EpicSdkUpdateDependency.isSdkCrashFrame("PE\t0000000180000000-0000000181a3c000\tDeferred        eossdk-win64-shipping"))
    }

    @Test
    fun endSession_marksTheGameOnlyForAnOnlineSdkCrash() {
        val frame = "=>0 0x000000018012abcd in eossdk-win64-shipping (+0x12abcd) (0x000000000011f8a0)"
        val container = mockk<Container>(relaxed = true)
        every { container.getExtra(EpicSdkUpdateDependency.EXTRA_STATE) } returns ""

        EpicSdkUpdateDependency.beginSession(online = false)
        EpicSdkUpdateDependency.onGuestOutput(frame)
        EpicSdkUpdateDependency.endSession(container)
        verify(exactly = 0) { container.putExtra(any(), any()) }

        EpicSdkUpdateDependency.beginSession(online = true)
        EpicSdkUpdateDependency.endSession(container)
        verify(exactly = 0) { container.putExtra(any(), any()) }

        EpicSdkUpdateDependency.beginSession(online = true)
        EpicSdkUpdateDependency.onGuestOutput(frame)
        EpicSdkUpdateDependency.endSession(container)
        verify(exactly = 1) { container.putExtra(EpicSdkUpdateDependency.EXTRA_STATE, EpicSdkUpdateDependency.STATE_PENDING) }
    }
}
