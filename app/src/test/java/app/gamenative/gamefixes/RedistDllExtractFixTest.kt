package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedistDllExtractFixTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private val container: Container = mockk(relaxed = true)

    private val installerRelativePath = "_CommonRedist/vcredist/2015/vc_redist.x64.exe"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun locatesTheCabinetHoldingTheWantedEntry() {
        val uxCabinet = cabinet("0", "u0", "u1")
        val payloadCabinet = cabinet("a9", "a10", "a11")
        val installer = fileOf(
            "vc_redist.x64.exe",
            ByteArray(100) { 7 } + uxCabinet + ByteArray(40) + payloadCabinet + ByteArray(16),
        )

        val location = CabArchive.locate(installer, "a10")

        assertEquals((100 + uxCabinet.size + 40).toLong(), location?.offset)
        assertEquals(payloadCabinet.size.toLong(), location?.length)
        assertEquals(listOf("a9", "a10", "a11"), location?.entryNames)
    }

    @Test
    fun ignoresCabinetsWithoutTheWantedEntry() {
        val installer = fileOf("vc_redist.x64.exe", ByteArray(64) + cabinet("0", "u0", "u1"))

        assertNull(CabArchive.locate(installer, "a10"))
    }

    @Test
    fun ignoresSignatureBytesThatAreNotACabinetHeader() {
        val payloadCabinet = cabinet("a10")
        val decoy = "MSCF".toByteArray(Charsets.US_ASCII) + ByteArray(24) { 0x5A }
        val installer = fileOf("vc_redist.x64.exe", decoy + payloadCabinet)

        val location = CabArchive.locate(installer, "a10")

        assertEquals(decoy.size.toLong(), location?.offset)
        assertEquals(payloadCabinet.size.toLong(), location?.length)
    }

    @Test
    fun sliceCopiesTheCabinetWithoutTheTrailingBytes() {
        val payloadCabinet = cabinet("ucrtbase.dll")
        val source = fileOf("a10", payloadCabinet + ByteArray(16) { 0x2A })
        val destination = File(temporary.newFolder(), "a10.cab")

        CabArchive.slice(source, CabArchive.locate(source, "ucrtbase.dll")!!, destination)

        assertArrayEquals(payloadCabinet, destination.readBytes())
    }

    @Test
    fun unpacksTheNestedDllAndLeavesNothingBehind() {
        val gameDir = temporary.newFolder()
        writeInstaller(gameDir, ByteArray(24) + cabinet("a9", "a10", "a11") + ByteArray(8))
        val nestedCabinet = cabinet("api_ms_win_crt_runtime_l1_1_0.dll", "ucrtbase.dll")
        val dll = ByteArray(2048) { (it % 251).toByte() }
        val unpacked = ArrayList<String>()
        var workDir: File? = null
        val extractor = CabEntryExtractor { cabinet, entryName, destination ->
            unpacked += entryName
            workDir = cabinet.parentFile
            when (entryName) {
                "a10" -> {
                    destination.writeBytes(nestedCabinet + ByteArray(16) { 0x2A })
                    true
                }
                "ucrtbase.dll" -> {
                    assertEquals(nestedCabinet.size.toLong(), cabinet.length())
                    destination.writeBytes(dll)
                    true
                }
                else -> false
            }
        }

        val applied = fix(extractor).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertTrue(applied)
        assertEquals(listOf("a10", "ucrtbase.dll"), unpacked)
        assertArrayEquals(dll, File(gameDir, "x64/ucrtbase.dll").readBytes())
        assertEquals(listOf("ucrtbase.dll"), File(gameDir, "x64").list()?.toList())
        assertFalse(workDir!!.exists())
    }

    @Test
    fun keepsADllThatIsAlreadyInPlace() {
        val gameDir = temporary.newFolder()
        writeInstaller(gameDir, ByteArray(24) + cabinet("a10"))
        File(gameDir, "x64").mkdirs()
        File(gameDir, "x64/ucrtbase.dll").writeText("placed by hand")
        val extractor = CabEntryExtractor { _, _, _ -> throw AssertionError("must not unpack anything") }

        val applied = fix(extractor).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertTrue(applied)
        assertEquals("placed by hand", File(gameDir, "x64/ucrtbase.dll").readText())
    }

    @Test
    fun survivesAMissingInstaller() {
        val gameDir = temporary.newFolder()

        val applied = fix().apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertFalse(File(gameDir, "x64/ucrtbase.dll").exists())
    }

    @Test
    fun survivesAnInstallerWithoutTheCabinet() {
        val gameDir = temporary.newFolder()
        writeInstaller(gameDir, ByteArray(4096) { 0x4D })
        val extractor = CabEntryExtractor { _, _, _ -> throw AssertionError("must not unpack anything") }

        val applied = fix(extractor).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertFalse(File(gameDir, "x64/ucrtbase.dll").exists())
    }

    @Test
    fun survivesAnExtractorThatFails() {
        val gameDir = temporary.newFolder()
        writeInstaller(gameDir, ByteArray(24) + cabinet("a10"))
        val extractor = CabEntryExtractor { _, _, _ -> error("decoder crashed") }

        val applied = fix(extractor).apply(context, "595520", gameDir.absolutePath, "A:\\", container)

        assertFalse(applied)
        assertEquals(emptyList<String>(), File(gameDir, "x64").list()?.toList() ?: emptyList<String>())
    }

    private fun fix(extractor: CabEntryExtractor = CabEntryExtractor { _, _, _ -> false }) = RedistDllExtractFix(
        installerRelativePath = installerRelativePath,
        cabinetEntryName = "a10",
        fileName = "ucrtbase.dll",
        destinationRelativePath = "x64/ucrtbase.dll",
        extractor = extractor,
    )

    private fun writeInstaller(gameDir: File, bytes: ByteArray) {
        val installer = File(gameDir, installerRelativePath)
        installer.parentFile!!.mkdirs()
        installer.writeBytes(bytes)
    }

    private fun fileOf(name: String, bytes: ByteArray): File =
        File(temporary.newFolder(), name).apply { writeBytes(bytes) }

    private fun cabinet(vararg entryNames: String): ByteArray {
        val table = ByteArrayOutputStream()
        entryNames.forEach { name ->
            table.write(ByteArray(16))
            table.write(name.toByteArray(Charsets.US_ASCII))
            table.write(0)
        }
        val tableBytes = table.toByteArray()
        val payload = ByteArray(32) { 0x11 }
        val header = ByteArray(36)
        "MSCF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putU32(header, 8, 36 + tableBytes.size + payload.size)
        putU32(header, 16, 36)
        header[24] = 3
        header[25] = 1
        putU16(header, 26, 1)
        putU16(header, 28, entryNames.size)
        return header + tableBytes + payload
    }

    private fun putU32(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = value.toByte()
        bytes[index + 1] = (value ushr 8).toByte()
        bytes[index + 2] = (value ushr 16).toByte()
        bytes[index + 3] = (value ushr 24).toByte()
    }

    private fun putU16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = value.toByte()
        bytes[index + 1] = (value ushr 8).toByte()
    }
}
