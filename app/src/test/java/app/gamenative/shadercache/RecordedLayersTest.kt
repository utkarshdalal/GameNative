package app.gamenative.shadercache

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordedLayersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun vk(major: Int, minor: Int, patch: Int) = (major.toLong() shl 22) or (minor.toLong() shl 12) or patch.toLong()

    private fun appInfo(engine: String, version: Long) =
        """{"version":6,"applicationInfo":{"applicationName":"game.exe","engineName":"$engine","engineVersion":$version},"physicalDeviceFeatures":{}}"""

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun entry(tag: Int, hash: Long, payload: ByteArray, deflate: Boolean): ByteArray {
        val stored = if (deflate) {
            val deflater = Deflater().apply { setInput(payload); finish() }
            val buffer = ByteArray(payload.size + 64)
            buffer.copyOf(deflater.deflate(buffer)).also { deflater.end() }
        } else {
            payload
        }
        val name = "%024x%016x".format(tag, hash).toByteArray()
        return name + le(stored.size) + le(if (deflate) 2 else 1) + le(0) + le(payload.size) + stored
    }

    private fun foz(vararg entries: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray() + byteArrayOf(0, 0, 0, 6))
        entries.forEach { out.write(it) }
        return temp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    @Test fun readsTheReportedEngineVersions() {
        val file = foz(
            entry(1, 7, ByteArray(300) { 1 }, deflate = false),
            entry(0, 1, appInfo("DXVK", vk(2, 4, 1)).toByteArray(), deflate = true),
            entry(0, 2, appInfo("vkd3d", vk(3, 0, 0)).toByteArray(), deflate = false),
        )

        assertEquals("dxvk-2.4.1" to "vkd3d-3.0.0", RecordedLayers.read(listOf(file)))
    }

    @Test fun ignoresOtherEnginesAndATruncatedEntry() {
        val cut = entry(0, 2, appInfo("vkd3d", vk(2, 14, 1)).toByteArray(), deflate = false)
        val file = foz(entry(0, 1, appInfo("mesa zink", vk(1, 0, 0)).toByteArray(), deflate = false), cut.copyOf(cut.size - 5))

        assertEquals(null to null, RecordedLayers.read(listOf(file)))
        assertEquals(null to null, RecordedLayers.read(listOf(temp.newFile().apply { writeText("not a database") })))
    }

    @Test fun decodesVulkanVersions() {
        assertEquals("1.10.3", RecordedLayers.vulkanVersion(vk(1, 10, 3)))
    }
}
