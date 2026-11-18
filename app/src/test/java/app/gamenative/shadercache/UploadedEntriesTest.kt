package app.gamenative.shadercache

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadedEntriesTest {
    @get:Rule val temp = TemporaryFolder()

    private val header = byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray() + byteArrayOf(0, 0, 0, 6)

    private fun name(tag: Int, hash: Long) = "%024x%016x".format(tag, hash)

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun entry(tag: Int, hash: Long, payload: ByteArray = ByteArray(0)) =
        name(tag, hash).toByteArray() + le(payload.size) + le(1) + le(0) + le(payload.size) + payload

    private fun foz(vararg entries: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write(header)
        entries.forEach { out.write(it) }
        return temp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    @Test fun keepsWhatWasUploadedOnceEach() {
        val uploaded = File(temp.newFolder(), "uploaded.foz")
        val module = entry(4, 1, ByteArray(300) { 7 })
        val pipeline = entry(6, 2, ByteArray(90) { 3 })
        val cut = entry(6, 9, ByteArray(40) { 1 })

        UploadedEntries.add(uploaded, foz(module, pipeline, cut.copyOf(cut.size - 5)), 1 shl 20)

        assertArrayEquals(header + module + pipeline, uploaded.readBytes())

        val link = entry(8, 3, ByteArray(20) { 5 })
        UploadedEntries.add(uploaded, foz(pipeline, link, link), 1 shl 20)

        assertArrayEquals(header + module + pipeline + link, uploaded.readBytes())
        assertEquals(listOf("uploaded.foz"), uploaded.parentFile!!.list()!!.toList())
    }

    @Test fun addsNothingPastTheLimitOrFromSomethingElse() {
        val uploaded = File(temp.newFolder(), "uploaded.foz")
        val module = entry(4, 1, ByteArray(300) { 7 })
        UploadedEntries.add(uploaded, foz(module), 1 shl 20)
        val before = uploaded.readBytes()

        UploadedEntries.add(uploaded, foz(entry(4, 2, ByteArray(300) { 8 })), before.size + 100L)
        UploadedEntries.add(uploaded, temp.newFile().apply { writeText("not a database") }, 1 shl 20)
        UploadedEntries.add(uploaded, foz(module), 1 shl 20)

        assertArrayEquals(before, uploaded.readBytes())
    }

    @Test fun dropsWhatAPoolHoldsAndGoesAwayWhenEmpty() {
        val uploaded = File(temp.newFolder(), "uploaded.foz")
        val module = entry(4, 1, ByteArray(300) { 7 })
        val pipeline = entry(6, 2, ByteArray(90) { 3 })
        val other = entry(6, 5, ByteArray(10) { 2 })
        UploadedEntries.add(uploaded, foz(module, pipeline, other), 1 shl 20)

        UploadedEntries.prune(uploaded, listOf(foz(entry(6, 8))))
        assertArrayEquals(header + module + pipeline + other, uploaded.readBytes())

        UploadedEntries.prune(uploaded, listOf(foz(module), foz(other, entry(6, 8))))
        assertArrayEquals(header + pipeline, uploaded.readBytes())

        UploadedEntries.prune(uploaded, listOf(foz(pipeline)))
        assertFalse(uploaded.exists())
        UploadedEntries.prune(uploaded, listOf(foz(pipeline)))
    }
}
