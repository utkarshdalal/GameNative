package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeVersionReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun readsMachineVersionAndStringsOfA64BitImage() {
        val image = SyntheticPe.build(
            PeVersionReader.MACHINE_AMD64,
            intArrayOf(14, 44, 35211, 0),
            mapOf("CompanyName" to "Microsoft Corporation", "OriginalFilename" to "msvcp140.dll", "InternalName" to "msvcp140"),
        )

        val info = PeVersionReader.read(image)!!

        assertEquals(PeVersionReader.MACHINE_AMD64, info.machine)
        assertEquals((14L shl 48) or (44L shl 32) or (35211L shl 16), info.fileVersion)
        assertEquals(14, info.majorVersion)
        assertEquals("msvcp140.dll", info.originalFilename)
        assertEquals("Microsoft Corporation", info.strings["CompanyName"])
        assertFalse(info.hybrid)
    }

    @Test
    fun readsA32BitImageFromFile() {
        val file = temporary.newFile("member").apply {
            writeBytes(SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(12, 0, 40660, 0), mapOf("OriginalFilename" to "msvcr120.dll")))
        }

        val info = PeVersionReader.read(file)!!

        assertEquals(PeVersionReader.MACHINE_I386, info.machine)
        assertEquals(12, info.majorVersion)
        assertEquals("msvcr120.dll", info.originalFilename)
    }

    @Test
    fun fileAndBytesGiveTheSameResult() {
        val image = SyntheticPe.build(PeVersionReader.MACHINE_AMD64, intArrayOf(10, 0, 40219, 325), mapOf("OriginalFilename" to "msvcr100.dll"))
        val file = temporary.newFile("image.dll").apply { writeBytes(image) }

        assertEquals(PeVersionReader.read(image), PeVersionReader.read(file))
    }

    @Test
    fun fallsBackToInternalNameWithoutOriginalFilename() {
        val image = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 0, 30729, 6161), mapOf("InternalName" to "msvcp90.dll"))

        assertEquals("msvcp90.dll", PeVersionReader.read(image)?.originalFilename)
    }

    @Test
    fun flagsArm64ecImagesAsHybrid() {
        val image = SyntheticPe.build(PeVersionReader.MACHINE_AMD64, intArrayOf(14, 42, 34438, 0), mapOf("OriginalFilename" to "vcruntime140_1.dll"), hybrid = true)

        val info = PeVersionReader.read(image)!!

        assertEquals(PeVersionReader.MACHINE_AMD64, info.machine)
        assertTrue(info.hybrid)
    }

    @Test
    fun imageWithoutVersionResourceHasNoVersion() {
        val info = PeVersionReader.read(SyntheticPe.build(PeVersionReader.MACHINE_I386))!!

        assertEquals(PeVersionReader.MACHINE_I386, info.machine)
        assertNull(info.fileVersion)
        assertNull(info.originalFilename)
    }

    @Test
    fun rejectsNonPeData() {
        assertNull(PeVersionReader.read("MSCF and other bytes that are not an image".toByteArray()))
        assertNull(PeVersionReader.read(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())))
        assertNull(PeVersionReader.read(ByteArray(0)))
    }

    @Test
    fun truncatedImageReturnsNullInsteadOfThrowing() {
        val image = SyntheticPe.build(PeVersionReader.MACHINE_AMD64, intArrayOf(14, 0, 0, 0), mapOf("OriginalFilename" to "a.dll"))

        assertNull(PeVersionReader.read(image.copyOf(0x50)))
        assertNull(PeVersionReader.read(image.copyOf(0x270))?.fileVersion)
    }
}
