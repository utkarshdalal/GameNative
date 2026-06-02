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

// read-only asar v1 reader. whole header JSON stays in memory (real apps are <1 MB).
class AsarArchive private constructor(
    private val file: RandomAccessFile,
    private val fileLength: Long,
    private val contentStart: Long,
    private val header: JsonObject,
) : ElectronArchive {

    override fun read(relPath: String): ByteArray? {
        if (!isSafePath(relPath)) return null
        val node = lookup(relPath) ?: return null
        if (node["files"] != null) return null
        val size = node["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        // electron stores offset as a decimal STRING.
        val offsetStr = node["offset"]?.jsonPrimitive?.contentOrNull ?: return null
        val offset = offsetStr.toLongOrNull() ?: return null
        val absolute = contentStart + offset
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

    // defense-in-depth: asar lookups can't escape the archive today, but keep ".." and NUL out anyway.
    private fun isSafePath(relPath: String): Boolean {
        if (relPath.isEmpty()) return true
        if (relPath.contains('\u0000')) return false
        val segs = relPath.trim('/').split('/')
        return segs.all { it != ".." }
    }

    companion object {
        // real headers are <1 MB; anything near this is an attack or a broken archive.
        internal const val MAX_HEADER_SIZE: Int = 64 * 1024 * 1024

        fun open(file: File): AsarArchive {
            if (!file.isFile) throw IOException("asar file missing: ${file.absolutePath}")
            val raf = RandomAccessFile(file, "r")
            val length = raf.length()
            try {
                // two nested Chromium Pickles -> FOUR uint32 prefix fields:
                // [0..3] outer pickle size = 4
                // [4..7] headerPickleSize = 4 + innerPayloadSize
                // [8..11] innerPayloadSize
                // [12..15] json string length
                // [16..] json bytes, 4-byte padded; file bodies start at 8 + headerPickleSize
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
                // innerPayloadSize = 4-byte length field + json + pad.
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
