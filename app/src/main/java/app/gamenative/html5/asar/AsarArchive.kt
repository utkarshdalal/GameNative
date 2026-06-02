// SPDX-License-Identifier: MIT
// asar format: parser inspired by electron/shell/common/asar/archive.cc (BSD-3-Clause)
// and @electron/asar (MIT). no verbatim code copied; format constants (pickle header
// byte layout, json tree schema, offset-as-string) are spec-derived.
package app.gamenative.html5.asar

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// handrolled asar v1 reader. stdlib + kotlinx-serialization-json only.
// read-only; no writer; single-thread usage expected (WebViewScreen remember{} block).
// full header JSON cached in memory -- real electron apps have <1 MB headers; many
// random-access reads over few paths favor cached-tree over re-parsing per lookup.
class AsarArchive private constructor(
    private val file: RandomAccessFile,
    private val fileLength: Long,
    private val contentStart: Long,
    private val header: JsonObject,
) : ElectronArchive {

    // read(relPath): resolves path through header tree, seeks to (contentStart + offset),
    // reads `size` bytes. returns null for missing / directory / traversal-rejected paths.
    override fun read(relPath: String): ByteArray? {
        if (!isSafePath(relPath)) return null
        val node = lookup(relPath) ?: return null
        // directory nodes carry a `files` object; file nodes carry `size` + `offset`.
        if (node["files"] != null) return null
        val size = node["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        // electron stores offset as decimal STRING; parse defensively.
        val offsetStr = node["offset"]?.jsonPrimitive?.contentOrNull ?: return null
        val offset = offsetStr.toLongOrNull() ?: return null
        val absolute = contentStart + offset
        // bounds check: (absolute + size) must fit inside file.
        if (absolute < 0 || size < 0 || absolute + size > fileLength) return null
        val out = ByteArray(size.toInt().coerceAtLeast(0))
        if (out.isEmpty()) return out
        synchronized(file) {
            file.seek(absolute)
            file.readFully(out)
        }
        return out
    }

    override fun exists(relPath: String): Boolean {
        if (relPath.isEmpty() || relPath == "/" || relPath == ".") return true
        if (!isSafePath(relPath)) return false
        return lookup(relPath) != null
    }

    override fun listFiles(relPath: String): List<String> {
        if (relPath.isNotEmpty() && !isSafePath(relPath)) return emptyList()
        val node = if (relPath.isEmpty() || relPath == ".") header else lookup(relPath) ?: return emptyList()
        val files = node["files"] as? JsonObject ?: return emptyList()
        return files.keys.toList()
    }

    override fun packageJson(): JsonObject? {
        val bytes = read("package.json") ?: return null
        return runCatching {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    override fun close() {
        runCatching { file.close() }
    }

    // ---------- internals ----------

    // walk "foo/bar/baz" through nested files: {foo: {files: {bar: {files: {baz: {...}}}}}}.
    // returns the terminal JSON node (file or directory) or null if absent.
    private fun lookup(relPath: String): JsonObject? {
        val segments = relPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return header
        var current: JsonObject = header
        for (seg in segments) {
            val files = current["files"] as? JsonObject ?: return null
            val next = files[seg] as? JsonObject ?: return null
            current = next
        }
        return current
    }

    // defense-in-depth: reject any "..", empty segment, or null byte. asar is structurally
    // confined (no filesystem escape), but the guard keeps future IO-adjacent refactors
    // from regressing.
    private fun isSafePath(relPath: String): Boolean {
        if (relPath.isEmpty()) return true
        if (relPath.contains('\u0000')) return false
        val segs = relPath.trim('/').split('/')
        return segs.all { it != ".." }
    }

    companion object {
        // 64 MB hard cap on header JSON size. real-world electron apps have <1 MB
        // headers; anything bigger is either an attack or a broken archive.
        internal const val MAX_HEADER_SIZE: Int = 64 * 1024 * 1024

        fun open(file: File): AsarArchive {
            if (!file.isFile) throw IOException("asar file missing: ${file.absolutePath}")
            val raf = RandomAccessFile(file, "r")
            val length = raf.length()
            try {
                // real electron asar has FOUR uint32 prefix fields (two nested Chromium
                // Pickles; outer wraps a single uint32 = headerPickleSize, inner wraps a
                // STRING -- which itself has a length prefix).
                // byte layout:
                // [0..3] outer pickle size field = 4
                // [4..7] headerPickleSize = size of inner (header) pickle INCL its
                // own size field = 4 + innerPayloadSize
                // [8..11] inner pickle size field = innerPayloadSize
                // [12..15] json string length
                // [16..16+jsonLen-1] json bytes
                // [+pad] 4-byte alignment (pickle strings are padded)
                // [bodies] contentStart = 8 + headerPickleSize
                
                // 20-byte minimum: 16 header + at least 4 bytes of json-or-padding.
                if (length < 20) throw IOException("asar too short ($length bytes)")

                val outerPrefix = readLeUint32(raf, 0)
                if (outerPrefix != 4) throw IOException("asar outer pickle prefix != 4 (got $outerPrefix)")

                val headerPickleSize = readLeUint32(raf, 4)
                val innerPayloadSize = readLeUint32(raf, 8)
                if (innerPayloadSize != headerPickleSize - 4) {
                    throw IOException("asar inner pickle size mismatch (expected ${headerPickleSize - 4}, got $innerPayloadSize)")
                }

                val headerJsonLen = readLeUint32(raf, 12)
                if (headerJsonLen < 0 || headerJsonLen > MAX_HEADER_SIZE) {
                    throw IOException("asar header size out of range: $headerJsonLen")
                }
                // json must fit within the inner pickle's payload (innerPayloadSize =
                // 4-byte length field + json + pad).
                if (headerJsonLen > innerPayloadSize - 4) {
                    throw IOException("asar json length $headerJsonLen exceeds inner pickle payload $innerPayloadSize")
                }
                if (headerJsonLen.toLong() + 16L > length) {
                    throw IOException("asar header claims $headerJsonLen bytes but file is $length")
                }

                val jsonBytes = ByteArray(headerJsonLen)
                raf.seek(16)
                raf.readFully(jsonBytes)

                val header = runCatching {
                    Json.parseToJsonElement(jsonBytes.toString(Charsets.UTF_8)).jsonObject
                }.getOrElse { throw IOException("asar header JSON invalid", it) }

                val contentStart = 8L + headerPickleSize.toLong()
                if (contentStart > length) {
                    throw IOException("asar contentStart past EOF ($contentStart > $length)")
                }

                return AsarArchive(raf, length, contentStart, header)
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
        }

        private fun readLeUint32(raf: RandomAccessFile, offset: Long): Int {
            raf.seek(offset)
            val b = ByteArray(4)
            raf.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}
