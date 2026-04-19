package app.gamenative.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAssemblyTest {

    // -- buildOrderedChunkQueue --

    @Test
    fun `chunks ordered by file appearance, deduplicated`() {
        val files = listOf(
            listOf("a", "b"),
            listOf("c", "d"),
            listOf("e"),
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), StreamingAssembly.buildOrderedChunkQueue(files))
    }

    @Test
    fun `shared chunk appears at first file position`() {
        // chunk "shared" used by file 0 and file 2
        val files = listOf(
            listOf("a", "shared"),
            listOf("b"),
            listOf("shared", "c"),
        )
        val queue = StreamingAssembly.buildOrderedChunkQueue(files)
        assertEquals(listOf("a", "shared", "b", "c"), queue)
        // "shared" at index 1 (from file 0), not duplicated at file 2's position
    }

    @Test
    fun `empty files produce empty queue`() {
        val files = listOf(emptyList<String>(), emptyList())
        assertEquals(emptyList<String>(), StreamingAssembly.buildOrderedChunkQueue(files))
    }

    @Test
    fun `single file single chunk`() {
        val files = listOf(listOf("only"))
        assertEquals(listOf("only"), StreamingAssembly.buildOrderedChunkQueue(files))
    }

    // -- buildChunkLastFileMap --

    @Test
    fun `last file map tracks final consumer`() {
        val files = listOf(
            listOf("a", "shared"),
            listOf("b"),
            listOf("shared", "c"),
        )
        val map = StreamingAssembly.buildChunkLastFileMap(files)
        assertEquals(0, map["a"])      // only in file 0
        assertEquals(2, map["shared"]) // last in file 2
        assertEquals(1, map["b"])      // only in file 1
        assertEquals(2, map["c"])      // only in file 2
    }

    @Test
    fun `chunk in every file maps to last`() {
        val files = listOf(
            listOf("everywhere"),
            listOf("everywhere"),
            listOf("everywhere"),
        )
        assertEquals(2, StreamingAssembly.buildChunkLastFileMap(files)["everywhere"])
    }

    // -- countReadyFiles --

    @Test
    fun `no files ready when first file incomplete`() {
        val files = listOf(listOf("a", "b"), listOf("c"))
        val downloaded = setOf("a") // missing "b"
        assertEquals(0, StreamingAssembly.countReadyFiles(files, downloaded, 0))
    }

    @Test
    fun `first file ready, second not`() {
        val files = listOf(listOf("a", "b"), listOf("c"))
        val downloaded = setOf("a", "b")
        assertEquals(1, StreamingAssembly.countReadyFiles(files, downloaded, 0))
    }

    @Test
    fun `both files ready`() {
        val files = listOf(listOf("a"), listOf("b"))
        val downloaded = setOf("a", "b")
        assertEquals(2, StreamingAssembly.countReadyFiles(files, downloaded, 0))
    }

    @Test
    fun `starts counting from nextFileIndex`() {
        val files = listOf(listOf("a"), listOf("b"), listOf("c"))
        val downloaded = setOf("a", "b", "c")
        // already assembled file 0, start from 1
        assertEquals(2, StreamingAssembly.countReadyFiles(files, downloaded, 1))
    }

    // -- chunksToDelete --

    @Test
    fun `deletes exclusive chunks, keeps shared`() {
        val files = listOf(
            listOf("exclusive", "shared"),
            listOf("shared", "other"),
        )
        val lastFile = StreamingAssembly.buildChunkLastFileMap(files)

        // after assembling file 0: "exclusive" last consumer is 0, "shared" last is 1
        val toDelete = StreamingAssembly.chunksToDelete(files, lastFile, 0)
        assertEquals(listOf("exclusive"), toDelete)
    }

    @Test
    fun `shared chunk deleted when last consumer assembled`() {
        val files = listOf(
            listOf("exclusive", "shared"),
            listOf("shared", "other"),
        )
        val lastFile = StreamingAssembly.buildChunkLastFileMap(files)

        val toDelete = StreamingAssembly.chunksToDelete(files, lastFile, 1)
        assertTrue("shared" in toDelete)
        assertTrue("other" in toDelete)
    }

    // -- simulated batch loop --

    @Test
    fun `full batch loop simulation`() {
        // 3 files, 6 chunks, batch size 2
        // file 0: chunks [a, b]
        // file 1: chunks [c, d]
        // file 2: chunks [d, e]  — "d" shared with file 1
        val files = listOf(
            listOf("a", "b"),
            listOf("c", "d"),
            listOf("d", "e"),
        )

        val queue = StreamingAssembly.buildOrderedChunkQueue(files)
        assertEquals(listOf("a", "b", "c", "d", "e"), queue)

        val lastFile = StreamingAssembly.buildChunkLastFileMap(files)
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 1, "d" to 2, "e" to 2), lastFile)

        val batchSize = 2
        val downloaded = mutableSetOf<String>()
        var nextFile = 0
        val assembledOrder = mutableListOf<Int>()
        val deletedChunks = mutableSetOf<String>()

        for (batch in queue.chunked(batchSize)) {
            downloaded.addAll(batch)

            val ready = StreamingAssembly.countReadyFiles(files, downloaded, nextFile)
            repeat(ready) {
                val toDelete = StreamingAssembly.chunksToDelete(files, lastFile, nextFile)
                deletedChunks.addAll(toDelete)
                assembledOrder.add(nextFile)
                nextFile++
            }
        }

        // all files assembled in order
        assertEquals(listOf(0, 1, 2), assembledOrder)
        assertEquals(3, nextFile)

        // batch 1 [a,b]: file 0 ready → assembled, a+b deleted (last consumer = 0)
        // batch 2 [c,d]: file 1 ready → assembled, c deleted (d last consumer = 2)
        // batch 3 [e]:   file 2 ready → assembled, d+e deleted
        assertEquals(setOf("a", "b", "c", "d", "e"), deletedChunks)
    }

    @Test
    fun `batch loop with late-completing file`() {
        // file 0 needs chunks spread across many batches
        // file 1 completes early but can't assemble until file 0 does (front-to-back)
        // Actually no — countReadyFiles stops at first incomplete file, so file 1
        // can't assemble until file 0 is done. This tests that invariant.
        val files = listOf(
            listOf("slow1", "slow2", "slow3"),
            listOf("fast"),
        )
        val queue = StreamingAssembly.buildOrderedChunkQueue(files)
        assertEquals(listOf("slow1", "slow2", "slow3", "fast"), queue)

        val lastFile = StreamingAssembly.buildChunkLastFileMap(files)
        val downloaded = mutableSetOf<String>()
        var nextFile = 0

        // batch 1: [slow1, slow2]
        downloaded.addAll(listOf("slow1", "slow2"))
        assertEquals(0, StreamingAssembly.countReadyFiles(files, downloaded, nextFile))

        // batch 2: [slow3, fast]
        downloaded.addAll(listOf("slow3", "fast"))
        // now both are ready
        assertEquals(2, StreamingAssembly.countReadyFiles(files, downloaded, nextFile))
    }
}
