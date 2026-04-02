package app.gamenative.service

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChunkRefCounter.
 *
 * These tests run on the JVM (no device/emulator required).
 * Run them with: ./gradlew test
 */
class ChunkRefCounterTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = createTempDirectory("chunk_test_cache").toFile()
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // ---------- helpers ----------

    private fun createChunk(id: String, ext: String = ""): File =
        File(cacheDir, "$id$ext").also { it.writeText("data") }

    // ---------- tests ----------

    @Test
    fun `chunk used by only one file is deleted after that file is released`() {
        val chunk = createChunk("abc")
        val counter = ChunkRefCounter(cacheDir)

        counter.trackFile(listOf("abc"))
        assertTrue("chunk should exist before release", chunk.exists())

        counter.releaseFile(listOf("abc"))
        assertFalse("chunk should be deleted after its only consumer is released", chunk.exists())
    }

    @Test
    fun `chunk shared by two files is kept until both files are released`() {
        val chunk = createChunk("shared")
        val counter = ChunkRefCounter(cacheDir)

        counter.trackFile(listOf("shared")) // file A
        counter.trackFile(listOf("shared")) // file B

        counter.releaseFile(listOf("shared")) // file A done
        assertTrue("chunk should still exist — file B still needs it", chunk.exists())

        counter.releaseFile(listOf("shared")) // file B done
        assertFalse("chunk should be deleted once both files are released", chunk.exists())
    }

    @Test
    fun `chunk shared by three files is deleted only after all three are released`() {
        val chunk = createChunk("triple")
        val counter = ChunkRefCounter(cacheDir)

        repeat(3) { counter.trackFile(listOf("triple")) }

        counter.releaseFile(listOf("triple"))
        assertTrue(chunk.exists())
        counter.releaseFile(listOf("triple"))
        assertTrue(chunk.exists())
        counter.releaseFile(listOf("triple"))
        assertFalse("chunk should be gone after the third release", chunk.exists())
    }

    @Test
    fun `chunks only used by released file are deleted, others are kept`() {
        val chunkA = createChunk("only_file1")
        val chunkB = createChunk("shared_by_both")
        val chunkC = createChunk("only_file2")
        val counter = ChunkRefCounter(cacheDir)

        counter.trackFile(listOf("only_file1", "shared_by_both"))   // file 1
        counter.trackFile(listOf("shared_by_both", "only_file2"))   // file 2

        // Release file 1
        counter.releaseFile(listOf("only_file1", "shared_by_both"))
        assertFalse("exclusive chunk for file 1 should be gone", chunkA.exists())
        assertTrue("shared chunk still needed by file 2", chunkB.exists())
        assertTrue("exclusive chunk for file 2 is untouched", chunkC.exists())

        // Release file 2
        counter.releaseFile(listOf("shared_by_both", "only_file2"))
        assertFalse("shared chunk should be gone after file 2 released", chunkB.exists())
        assertFalse("exclusive chunk for file 2 should be gone", chunkC.exists())
    }

    @Test
    fun `chunkExtension is appended to filename when deleting`() {
        val chunk = createChunk("def", ext = ".chunk")
        val counter = ChunkRefCounter(cacheDir, chunkExtension = ".chunk")

        counter.trackFile(listOf("def"))
        counter.releaseFile(listOf("def"))
        assertFalse("chunk file with .chunk extension should be deleted", chunk.exists())
    }

    @Test
    fun `releasing an unknown chunk id does not throw`() {
        val counter = ChunkRefCounter(cacheDir)
        // Should complete without any exception
        counter.releaseFile(listOf("nonexistent_id"))
    }

    @Test
    fun `releasing more times than tracked does not throw or go negative`() {
        createChunk("over")
        val counter = ChunkRefCounter(cacheDir)

        counter.trackFile(listOf("over")) // tracked once
        counter.releaseFile(listOf("over")) // first release — count hits 0, file deleted
        counter.releaseFile(listOf("over")) // second release — should not throw

        val refCount = counter.getRefCount("over")
        assertTrue(
            "refcount must not go below zero after excess releases; was $refCount",
            refCount == null || refCount >= 0,
        )
    }

    @Test
    fun `file with no chunks does not affect other chunks`() {
        val chunk = createChunk("untouched")
        val counter = ChunkRefCounter(cacheDir)

        counter.trackFile(listOf("untouched"))
        counter.trackFile(emptyList()) // empty file (e.g. 0-byte placeholder)
        counter.releaseFile(emptyList())
        assertTrue("unrelated chunk should still exist", chunk.exists())

        counter.releaseFile(listOf("untouched"))
        assertFalse("chunk should be gone after its consumer is released", chunk.exists())
    }
}
