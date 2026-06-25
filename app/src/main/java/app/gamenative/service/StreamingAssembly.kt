package app.gamenative.service

// pure logic for streaming download+assembly, decoupled from Epic/GOG types.
// TODO: overlap assembly with download in a separate coroutine for better throughput
object StreamingAssembly {

    // deduplicated chunk-ID queue ordered by file appearance
    fun buildOrderedChunkQueue(fileChunkIds: List<List<String>>): List<String> {
        val seen = mutableSetOf<String>()
        val queue = mutableListOf<String>()
        for (chunks in fileChunkIds) {
            for (id in chunks) {
                if (seen.add(id)) queue.add(id)
            }
        }
        return queue
    }

    // last file index per chunk — chunk is safe to delete once that file is assembled
    fun buildChunkLastFileMap(fileChunkIds: List<List<String>>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        fileChunkIds.forEachIndexed { i, chunks ->
            for (id in chunks) { map[id] = i }
        }
        return map
    }

    // used by tests
    fun countReadyFiles(
        fileChunkIds: List<List<String>>,
        downloadedChunkIds: Set<String>,
        nextFileIndex: Int,
    ): Int {
        require(nextFileIndex >= 0) { "nextFileIndex must be non-negative" }
        var ready = 0
        var i = nextFileIndex
        while (i < fileChunkIds.size) {
            if (!fileChunkIds[i].all { it in downloadedChunkIds }) break
            ready++
            i++
        }
        return ready
    }

    // used by tests
    fun chunksToDelete(
        fileChunkIds: List<List<String>>,
        chunkLastFile: Map<String, Int>,
        fileIndex: Int,
    ): List<String> {
        require(fileIndex in fileChunkIds.indices) { "fileIndex out of bounds" }
        return fileChunkIds[fileIndex].filter { chunkLastFile[it] == fileIndex }
    }
}
