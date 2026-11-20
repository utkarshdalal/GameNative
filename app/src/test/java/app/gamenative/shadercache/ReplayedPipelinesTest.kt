package app.gamenative.shadercache

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayedPipelinesTest {
    @get:Rule val temp = TemporaryFolder()

    private val driver = "turnip:25.1.0"
    private val uuid = "0123456789abcdef0123456789abcdef"

    private fun name(tag: Int, hash: Long) = "%024x%016x".format(tag, hash)

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun entry(tag: Int, hash: Long, payload: ByteArray = ByteArray(0)) =
        name(tag, hash).toByteArray() + le(payload.size) + le(1) + le(0) + le(payload.size) + payload

    private fun foz(file: File, vararg entries: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray() + byteArrayOf(0, 0, 0, 6))
        entries.forEach { out.write(it) }
        return file.apply { writeBytes(out.toByteArray()) }
    }

    private fun runFile(dir: File, index: Int, uuid: String = this.uuid) =
        File(dir, "${ReplayedPipelines.cachePath(dir, driver).name}.$uuid.$index.foz")

    private fun baseFile(dir: File) = File(dir, "${ReplayedPipelines.cachePath(dir, driver).name}.$uuid.foz")

    @Test fun readsOnlyPipelineEntries() {
        val cut = entry(6, 9, ByteArray(40) { 1 })
        val pool = foz(
            temp.newFile(),
            entry(4, 1, ByteArray(300) { 1 }),
            entry(6, 2, ByteArray(120) { 1 }),
            entry(7, 3),
            entry(9, 4, ByteArray(5)),
            entry(8, 5),
            cut.copyOf(cut.size - 5),
        )

        assertEquals(setOf(name(6, 2), name(7, 3), name(9, 4)), ReplayedPipelines.pipelineNames(pool))
        assertEquals(emptySet<String>(), ReplayedPipelines.pipelineNames(temp.newFile().apply { writeText("not a database") }))
        assertEquals(emptySet<String>(), ReplayedPipelines.pipelineNames(File(temp.root, "missing.foz")))
    }

    @Test fun foldsWhatEachReplayProcessWroteIntoTheDatabaseTheNextOneReads() {
        val dir = temp.newFolder()
        foz(runFile(dir, 1), entry(6, 1), entry(6, 2))
        foz(runFile(dir, 2), entry(7, 3))

        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 1), name(6, 2), name(7, 3)), ReplayedPipelines.pipelineNames(baseFile(dir)))
        assertFalse(runFile(dir, 1).exists())
        assertFalse(runFile(dir, 2).exists())

        foz(runFile(dir, 1), entry(6, 4))
        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 1), name(6, 2), name(7, 3), name(6, 4)), ReplayedPipelines.known(dir, driver))
        assertEquals(listOf(baseFile(dir).name), dir.list()!!.toList())
    }

    @Test fun marksWaitForTheDriverUuidAndThenJoinItsDatabase() {
        val dir = temp.newFolder()

        ReplayedPipelines.mark(dir, driver, setOf(name(6, 1)))

        assertEquals(setOf(name(6, 1)), ReplayedPipelines.known(dir, driver))
        assertFalse(baseFile(dir).exists())

        foz(runFile(dir, 1), entry(6, 2))
        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 1), name(6, 2)), ReplayedPipelines.pipelineNames(baseFile(dir)))
        assertEquals(listOf(baseFile(dir).name), dir.list()!!.toList())

        ReplayedPipelines.mark(dir, driver, setOf(name(6, 2), name(7, 3)))

        assertEquals(setOf(name(6, 1), name(6, 2), name(7, 3)), ReplayedPipelines.pipelineNames(baseFile(dir)))
        assertEquals(listOf(baseFile(dir).name), dir.list()!!.toList())
    }

    @Test fun keepsDriversApart() {
        val dir = temp.newFolder()
        foz(runFile(dir, 1), entry(6, 1))
        ReplayedPipelines.fold(dir, driver)
        ReplayedPipelines.mark(dir, "turnip:26.0.0", setOf(name(6, 2)))

        assertEquals(setOf(name(6, 1)), ReplayedPipelines.known(dir, driver))
        assertEquals(setOf(name(6, 2)), ReplayedPipelines.known(dir, "turnip:26.0.0"))
        assertTrue(ReplayedPipelines.known(dir, "vortek:1").isEmpty())
    }

    @Test fun writesEntriesFossilizeReadsAsEmpty() {
        val dir = temp.newFolder()
        ReplayedPipelines.mark(dir, driver, setOf(name(6, 1)))

        val written = dir.listFiles()!!.single().readBytes()

        assertEquals(16 + 56, written.size)
        assertEquals(name(6, 1), String(written, 16, 40))
        assertEquals(listOf<Byte>(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), written.drop(56).toList())
    }
}
