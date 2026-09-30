package app.gamenative.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModArchiveExtractorAndroidTest {

    @Test
    fun extractRar5_readsStoredArchiveOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempDir = File(context.cacheDir, "rar5_extract_test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val archive = File(tempDir, "mod.rar").apply { writeBytes(uudecode(RAR5_STORED_UU)) }

            val result = ModArchiveExtractor.extract(archive, File(tempDir, "out"))

            assertTrue(File(result.destination, "helloworld.txt").isFile)
            assertEquals("hello libarchive test suite!\n", File(result.destination, "helloworld.txt").readText())
            assertTrue(result.entries.any { it.path == "helloworld.txt" && !it.directory })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun uudecode(encoded: String): ByteArray {
        val lines = encoded.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val body = lines.dropWhile { !it.startsWith("begin ") }
            .drop(1)
            .takeWhile { it != "end" }
        val output = ByteArrayOutputStream()
        body.forEach { line ->
            val expectedBytes = decodeUuChar(line.first())
            if (expectedBytes == 0) return@forEach
            var written = 0
            var index = 1
            while (index + 3 < line.length && written < expectedBytes) {
                val a = decodeUuChar(line[index])
                val b = decodeUuChar(line[index + 1])
                val c = decodeUuChar(line[index + 2])
                val d = decodeUuChar(line[index + 3])
                val decoded = byteArrayOf(
                    ((a shl 2) or (b shr 4)).toByte(),
                    (((b and 0x0F) shl 4) or (c shr 2)).toByte(),
                    (((c and 0x03) shl 6) or d).toByte(),
                )
                decoded.take(expectedBytes - written).forEach { output.write(it.toInt()) }
                written += 3
                index += 4
            }
        }
        return output.toByteArray()
    }

    private fun decodeUuChar(char: Char): Int =
        (char.code - 32) and 0x3F

    private companion object {
        private val RAR5_STORED_UU = """
begin 644 test_read_format_rar5_stored.rar
M4F%R(1H'`0`SDK7E"@$%!@`%`0&`@``X,`9C+`(#"YT`!)T`I(,"M$.@E8``
M`0YH96QL;W=O<FQD+G1X=`H#${'$'}WX.JUM6Z0X::&5L;&\@;&EB87)C:&EV92!T
397-T('-U:71E(0H==U91`P4$````
`
end
"""
    }
}
