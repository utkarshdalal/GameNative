package app.gamenative.html5.asar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// synthetic asar writer. mirrors @electron/asar bytes: pickle-wrapped header JSON
// followed by 4-byte-aligned concatenated file bodies. used by AsarArchiveTest,
// AsarDirectoryRefTest , AsarAssetInterceptorTest .

// deterministic output order: entries iterate in Map insertion order; Kotlin
// LinkedHashMap is the default. tests rely on insertion order for offset math.
object AsarTestFixtures {

    fun writeFixture(target: File, entries: Map<String, ByteArray>): File {
        val root = buildJsonTree(entries)
        val headerJson = Json.encodeToString(JsonObject.serializer(), root)
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        val headerSize = headerBytes.size
        val pad = ((4 - (headerSize % 4)) % 4)

        // mirrors electron's Chromium Pickle layout — two nested pickles:
        // [0..3] outer pickle size field = 4
        // [4..7] headerPickleSize = 4 + innerPayloadSize
        // [8..11] innerPayloadSize = 4 (strLen field) + headerSize + pad
        // [12..15] json string length
        // [16..] json bytes + pad + bodies
        val innerPayloadSize = 4 + headerSize + pad
        val headerPickleSize = 4 + innerPayloadSize

        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            writeLeUint32(raf, 4)
            writeLeUint32(raf, headerPickleSize)
            writeLeUint32(raf, innerPayloadSize)
            writeLeUint32(raf, headerSize)
            raf.write(headerBytes)
            if (pad > 0) raf.write(ByteArray(pad))
            // bodies — iterate entries in the SAME order used when building the tree.
            for ((_, bytes) in entries) raf.write(bytes)
        }
        return target
    }

    private fun buildJsonTree(entries: Map<String, ByteArray>): JsonObject {
        // walk each slash-delimited path; accumulate offsets in entries order.
        // all files land in a single linear body region; offset is prefix-sum
        // over insertion order.
        data class Leaf(val size: Int, val offset: Long)
        val leaves = LinkedHashMap<String, Leaf>()
        var cursor = 0L
        for ((name, bytes) in entries) {
            leaves[name] = Leaf(bytes.size, cursor)
            cursor += bytes.size
        }
        val rootFiles = linkedMapOf<String, JsonElement>()
        for ((path, leaf) in leaves) {
            insertLeaf(rootFiles, path.split('/'), leaf.size, leaf.offset)
        }
        return buildJsonObject {
            putJsonObject("files") {
                rootFiles.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    private fun insertLeaf(
        dirMap: MutableMap<String, JsonElement>,
        segments: List<String>,
        size: Int,
        offset: Long,
    ) {
        if (segments.size == 1) {
            dirMap[segments[0]] = buildJsonObject {
                put("size", JsonPrimitive(size))
                // electron stores offset as decimal STRING in JSON.
                put("offset", JsonPrimitive(offset.toString()))
            }
            return
        }
        val head = segments[0]
        val existing = dirMap[head] as? JsonObject
        val childMap = linkedMapOf<String, JsonElement>()
        if (existing != null) {
            val childFiles = existing["files"] as? JsonObject
            childFiles?.forEach { (k, v) -> childMap[k] = v }
        }
        insertLeaf(childMap, segments.drop(1), size, offset)
        dirMap[head] = buildJsonObject {
            putJsonObject("files") {
                childMap.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    private fun writeLeUint32(raf: RandomAccessFile, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        raf.write(buf.array())
    }
}
