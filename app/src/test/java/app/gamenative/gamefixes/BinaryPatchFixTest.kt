package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BinaryPatchFixTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private val container: Container = mockk(relaxed = true)

    private val targetRelativePath = "x64/game.exe"
    private val original = byteArrayOf(0x74, 0x31)
    private val replacement = byteArrayOf(0x90.toByte(), 0x90.toByte())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun writesTheReplacementOverTheOriginalBytes() {
        val gameDir = temporary.newFolder()
        val exe = writeTarget(gameDir, body(original))

        val applied = fix(expectedFileLength = exe.length()).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertTrue(applied)
        assertArrayEquals(body(replacement), exe.readBytes())
    }

    @Test
    fun leavesAnAlreadyPatchedFileAlone() {
        val gameDir = temporary.newFolder()
        val exe = writeTarget(gameDir, body(original))
        val fix = fix(expectedFileLength = exe.length())
        assertTrue(fix.apply(context, "595520", gameDir.absolutePath, "A:\\", container))
        val stamp = 1_600_000_000_000L
        assertTrue(exe.setLastModified(stamp))

        val applied = fix.apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertTrue(applied)
        assertEquals(stamp, exe.lastModified())
        assertArrayEquals(body(replacement), exe.readBytes())
    }

    @Test
    fun refusesAndWritesNothingWhenTheBytesAreUnknown() {
        val gameDir = temporary.newFolder()
        val unknown = body(byteArrayOf(0x75, 0x31))
        val exe = writeTarget(gameDir, unknown)

        val applied = fix(expectedFileLength = exe.length()).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertArrayEquals(unknown, exe.readBytes())
    }

    @Test
    fun refusesAndWritesNothingWhenOnlyOnePatchOfTwoIsKnown() {
        val gameDir = temporary.newFolder()
        val before = body(original)
        val exe = writeTarget(gameDir, before)
        val fix = BinaryPatchFix(
            targetRelativePath = targetRelativePath,
            expectedFileLength = exe.length(),
            patches = listOf(
                BinaryPatch(offset = PATCH_OFFSET, original = original, replacement = replacement),
                BinaryPatch(offset = 0L, original = byteArrayOf(0x4D, 0x5A), replacement = byteArrayOf(0x4D, 0x5B)),
            ),
        )

        val applied = fix.apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertArrayEquals(before, exe.readBytes())
    }

    @Test
    fun refusesWhenTheFileLengthIsNotTheExpectedOne() {
        val gameDir = temporary.newFolder()
        val before = body(original)
        val exe = writeTarget(gameDir, before)

        val applied = fix(expectedFileLength = exe.length() + 1).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertArrayEquals(before, exe.readBytes())
    }

    @Test
    fun refusesWhenTheTargetIsMissing() {
        val gameDir = temporary.newFolder()

        val applied = fix().apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertFalse(File(gameDir, targetRelativePath).exists())
    }

    @Test
    fun refusesWhenTheFileIsShorterThanThePatchOffset() {
        val gameDir = temporary.newFolder()
        val short = ByteArray(8) { 0x11 }
        val exe = writeTarget(gameDir, short)

        val applied = fix().apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertArrayEquals(short, exe.readBytes())
    }

    @Test
    fun rejectsAPatchWhoseReplacementIsADifferentLength() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryPatch(offset = PATCH_OFFSET, original = original, replacement = byteArrayOf(0x90.toByte()))
        }
    }

    @Test
    fun rejectsAPatchWithoutBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryPatch(offset = PATCH_OFFSET, original = ByteArray(0), replacement = ByteArray(0))
        }
    }

    @Test
    fun steamFix595520_nopsTheDisplayModeJumpInTheShippedExe() {
        val gameDir = temporary.newFolder()
        val exe = ffxiiExe(gameDir, FFXII_CONTEXT)

        STEAM_Fix_595520.apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertArrayEquals(FFXII_PATCHED_CONTEXT, contextOf(exe))
        assertEquals(FFXII_EXE_BYTES, exe.length())
    }

    @Test
    fun steamFix595520_leavesAnExeWithADifferentInstructionContextAlone() {
        val gameDir = temporary.newFolder()
        val exe = ffxiiExe(gameDir, byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x35, 0x74, 0x31))
        val before = digestOf(exe)

        STEAM_Fix_595520.apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertArrayEquals(before, digestOf(exe))
        assertEquals(FFXII_EXE_BYTES, exe.length())
    }

    @Test
    fun refusesAndWritesNothingWhenOnlyTheTailOfTheContextIsDifferent() {
        val gameDir = temporary.newFolder()
        val before = ByteArray(64) { (it % 251).toByte() }
        byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x34, 0x75, 0x31)
            .copyInto(before, PATCH_OFFSET.toInt())
        val exe = writeTarget(gameDir, before)
        val fix = BinaryPatchFix(
            targetRelativePath = targetRelativePath,
            expectedFileLength = exe.length(),
            patches = listOf(BinaryPatch(offset = PATCH_OFFSET, original = FFXII_CONTEXT, replacement = FFXII_PATCHED_CONTEXT)),
        )

        val applied = fix.apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertArrayEquals(before, exe.readBytes())
    }

    private fun fix(expectedFileLength: Long? = null) = BinaryPatchFix(
        targetRelativePath = targetRelativePath,
        expectedFileLength = expectedFileLength,
        patches = listOf(BinaryPatch(offset = PATCH_OFFSET, original = original, replacement = replacement)),
    )

    private fun body(atPatchOffset: ByteArray): ByteArray {
        val bytes = ByteArray(64) { (it % 251).toByte() }
        atPatchOffset.copyInto(bytes, PATCH_OFFSET.toInt())
        return bytes
    }

    private fun ffxiiExe(gameDir: File, contextBytes: ByteArray): File {
        val exe = File(gameDir, "x64/FFXII_TZA.exe")
        exe.parentFile!!.mkdirs()
        RandomAccessFile(exe, "rw").use { file ->
            file.setLength(FFXII_EXE_BYTES)
            file.seek(FFXII_CONTEXT_OFFSET)
            file.write(contextBytes)
        }
        return exe
    }

    private fun contextOf(exe: File): ByteArray {
        val bytes = ByteArray(FFXII_CONTEXT.size)
        RandomAccessFile(exe, "r").use { file ->
            file.seek(FFXII_CONTEXT_OFFSET)
            file.readFully(bytes)
        }
        return bytes
    }

    private fun digestOf(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun writeTarget(gameDir: File, bytes: ByteArray): File {
        val target = File(gameDir, targetRelativePath)
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    private companion object {
        const val PATCH_OFFSET = 40L
        const val FFXII_CONTEXT_OFFSET = 0xbcef4L
        const val FFXII_EXE_BYTES = 32_864_752L
        val FFXII_CONTEXT = byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x34, 0x74, 0x31)
        val FFXII_PATCHED_CONTEXT = byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x34, 0x90.toByte(), 0x90.toByte())
    }
}
