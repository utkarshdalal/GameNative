package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// walks the V8 ScriptValueSerializer (SSV) tag stream inside each IDB record value.
// goal: identify which tag/sub-encoding WebView 109 (V8 ~10.7) silently fails on
// when Wayward's saves (authored by Chromium 140) are read back.

// usage:
// 1. un-@Ignore below
// 2. ./gradlew :app:testDebugUnitTest --tests 'app.gamenative.html5.savesync.SsvTagWalker'
// 3. inspect /tmp/wayward-ssv-walk.txt

// tag reference:
// https://source.chromium.org/chromium/chromium/src/+/main:v8/src/objects/value-serializer.cc
// https://source.chromium.org/chromium/chromium/src/+/main:third_party/blink/renderer/bindings/core/v8/serialization/serialization_tag.h
@Ignore("debug tool — un-ignore on demand")
class SsvTagWalker {

    private val ldbPath = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    private val outPath = "/tmp/wayward-ssv-walk.txt"

    // V8 tag names keyed by byte value. sourced from v8/src/objects/value-serializer.cc.
    // covers the full historical set; anything NOT in this map is an unknown tag.
    private val tagNames: Map<Int, String> = mapOf(
        // primitives
        0x30 to "kTheHole",          // '0'
        0x5F to "kUndefined",        // '_'
        0x4E to "kNull",             // 'N'
        0x54 to "kTrue",             // 'T'
        0x46 to "kFalse",            // 'F'
        0x49 to "kInt32",            // 'I'
        0x55 to "kUint32",           // 'U'
        0x4E to "kNull",             // 'N' (dup with kNull)
        0x44 to "kDate",             // 'D'
        0x4E to "kNull",             // 'N'
        0x22 to "kOneByteString",    // '"'
        0x63 to "kTwoByteString",    // 'c'
        0x53 to "kUtf8String",       // 'S'
        // numbers/bigint
        0x4E to "kNull",             // 'N'
        0x5A to "kBigInt",           // 'Z'
        // compound
        0x6F to "kBeginJSObject",    // 'o'
        0x7B to "kEndJSObject",      // '{'
        0x61 to "kBeginDenseJSArray",// 'a'
        0x41 to "kBeginSparseJSArray",// 'A'
        0x24 to "kEndDenseJSArray",  // '$'
        0x40 to "kEndSparseJSArray", // '@'
        0x79 to "kTrueObject",       // 'y'
        0x78 to "kFalseObject",      // 'x'
        0x6E to "kNumberObject",     // 'n'
        0x7A to "kBigIntObject",     // 'z'
        0x73 to "kStringObject",     // 's'
        // typed arrays / buffers
        0x42 to "kArrayBuffer",                  // 'B'
        0x7E to "kArrayBufferTransfer",          // '~'
        0x56 to "kArrayBufferView",              // 'V'
        0x75 to "kSharedArrayBuffer",            // 'u'
        0x59 to "kResizableArrayBuffer",         // 'Y'
        // misc
        0x5E to "kObjectReference",  // '^'
        0x3F to "kWasmModuleTransfer",// '?'
        0x72 to "kRegExp",           // 'r'
        0x3B to "kMap",              // ';'
        0x3A to "kSet",              // ':'
        0x2C to "kBeginJSSet",       // ','
        0x2E to "kEndJSSet",         // '.'
        0x2F to "kBeginJSMap",       // '/'
        0x3D to "kEndJSMap",         // '='
        0x21 to "kError",            // '!'
        0x64 to "kWasmMemoryTransfer",// 'd'
        0x57 to "kWasmModuleTransfer",// 'W'
        // headers
        0xFF to "kVersion",          // first byte of SSV header
        0xFE to "kPadding",          // 'þ' - padding/reserved
        0xFD to "kTrailerOffset",    // trailer marker in outer envelope (v21+)
    )

    @Test
    fun walk() {
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        val out = StringBuilder()
        Iq80DBFactory.factory.open(File(ldbPath), opts).use { db ->
            db.iterator().use { it ->
                it.seekToFirst()
                var n = 0
                while (it.hasNext()) {
                    val entry = it.next()
                    val k = entry.key
                    val v = entry.value
                    // object-store-level records only (keyPrefix variant; skip metadata)
                    // IDB object-store records start with the store-id prefix; Wayward's
                    // main store lives at c8 offset. but we walk everything and flag
                    // successfully walked vs aborted.
                    if (v.size < 4) { n++; continue }
                    walkOne(n, k, v, out)
                    n++
                }
            }
        }
        File(outPath).writeText(out.toString())
        println("Wrote ${out.length} chars to $outPath")
    }

    private fun walkOne(index: Int, key: ByteArray, value: ByteArray, out: StringBuilder) {
        out.appendLine("=== record #$index keyLen=${key.size} valueLen=${value.size} keyAscii=${asciiKey(key)}")

        val r = Reader(value)
        // IDB wraps with a leading varint: "IDBValueVersion"
        val idbVersion = runCatching { r.readVarint() }.getOrElse {
            out.appendLine("  !! failed to read IDB leading varint: ${it.message}"); return
        }
        out.appendLine("  idbVersion=$idbVersion pos=${r.pos}")

        // outer envelope: optional ff <ver> fe <12 null bytes> (Chromium 140 style)
        if (r.peek() == 0xFF) {
            r.read() // consume 0xFF
            val outerVer = r.read()
            out.appendLine("  outer: ff (kVersion) version=$outerVer (0x${"%02x".format(outerVer)})")
            if (r.peek() == 0xFE) {
                r.read()
                // skip padding bytes (typically 12 zero bytes, could be trailer-offset info)
                var padLen = 0
                while (r.remaining() > 0 && r.peek() == 0) { r.read(); padLen++ }
                out.appendLine("  outer: fe (kPadding) paddingBytes=$padLen pos=${r.pos}")
            }
        }

        // inner SSV: ff 0f (version 15) ideally
        if (r.peek() == 0xFF) {
            r.read()
            val innerVer = r.read()
            out.appendLine("  inner: ff (kVersion) version=$innerVer (0x${"%02x".format(innerVer)})")
        } else {
            out.appendLine("  !! no inner kVersion tag at pos=${r.pos}, top=${"%02x".format(r.peek())}")
            return
        }

        // walk inner tag stream
        val depth = IntArray(1) { 0 }
        try {
            while (r.remaining() > 0) {
                if (!walkValue(r, out, depth, indent = 1)) break
                // top-level should be single value; if we return cleanly, we're done
                if (depth[0] == 0) break
            }
        } catch (e: Throwable) {
            out.appendLine("  !! exception at pos=${r.pos}: ${e.message}")
        }

        if (r.remaining() > 0) {
            out.appendLine("  ~~ ${r.remaining()} trailing bytes unparsed at pos=${r.pos}: ${hex(r.rest(), 32)}")
        }
        out.appendLine()
    }

    // returns true if a value was read; false on EOF
    private fun walkValue(r: Reader, out: StringBuilder, depth: IntArray, indent: Int): Boolean {
        if (r.remaining() == 0) return false
        val start = r.pos
        val tag = r.read()
        val name = tagNames[tag] ?: "UNKNOWN"
        val pad = "  ".repeat(indent + 1)

        when (tag) {
            0x49 -> { // kInt32
                val v = r.readZigZagVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name value=$v")
            }
            0x55 -> { // kUint32
                val v = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name value=$v")
            }
            0x44 -> { // kDate (double LE)
                val d = r.readDoubleLE()
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name date=$d")
            }
            0x22 -> { // kOneByteString
                val len = r.readVarint()
                val s = r.readBytes(len)
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name len=$len str=\"${asciiPrint(s)}\"")
            }
            0x63 -> { // kTwoByteString
                val len = r.readVarint()
                val s = r.readBytes(len)
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name bytelen=$len str=\"${utf16LePrint(s)}\"")
            }
            0x53 -> { // kUtf8String
                val len = r.readVarint()
                val s = r.readBytes(len)
                out.appendLine("${pad}[$start] tag=${printable(tag)} $name len=$len str=\"${String(s, Charsets.UTF_8).take(32)}\"")
            }
            0x4E -> {
                out.appendLine("${pad}[$start] tag=${printable(tag)} kNull")
            }
            0x5F -> {
                out.appendLine("${pad}[$start] tag=${printable(tag)} kUndefined")
            }
            0x54 -> out.appendLine("${pad}[$start] tag=${printable(tag)} kTrue")
            0x46 -> out.appendLine("${pad}[$start] tag=${printable(tag)} kFalse")
            0x30 -> out.appendLine("${pad}[$start] tag=${printable(tag)} kTheHole")
            0x5A -> { // kBigInt: bitfield varint + digits
                val bitfield = r.readVarint()
                val len = bitfield ushr 1
                val sign = bitfield and 1
                val bytes = r.readBytes(len)
                out.appendLine("${pad}[$start] tag=${printable(tag)} kBigInt sign=$sign digits=$len hex=${hex(bytes, 16)}")
            }
            0x6F -> { // kBeginJSObject
                out.appendLine("${pad}[$start] tag=${printable(tag)} kBeginJSObject {")
                depth[0]++
                while (r.remaining() > 0 && r.peek() != 0x7B) {
                    // property key + value
                    walkValue(r, out, depth, indent + 1)
                    if (r.remaining() == 0 || r.peek() == 0x7B) break
                    walkValue(r, out, depth, indent + 1)
                }
                if (r.remaining() > 0 && r.peek() == 0x7B) {
                    r.read()
                    val propCount = r.readVarint()
                    out.appendLine("${pad}} kEndJSObject propCount=$propCount")
                    depth[0]--
                } else {
                    out.appendLine("${pad}!! object not closed, remaining=${r.remaining()}")
                }
            }
            0x61 -> { // kBeginDenseJSArray
                val length = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} kBeginDenseJSArray length=$length [")
                depth[0]++
                var i = 0
                while (r.remaining() > 0 && r.peek() != 0x24 && i < length) {
                    walkValue(r, out, depth, indent + 1)
                    i++
                }
                // properties between dense values and $ end (rare)
                while (r.remaining() > 0 && r.peek() != 0x24) {
                    walkValue(r, out, depth, indent + 1)
                    if (r.remaining() == 0 || r.peek() == 0x24) break
                    walkValue(r, out, depth, indent + 1)
                }
                if (r.remaining() > 0 && r.peek() == 0x24) {
                    r.read()
                    val propCount = r.readVarint()
                    val totalLen = r.readVarint()
                    out.appendLine("${pad}] kEndDenseJSArray propCount=$propCount totalLen=$totalLen")
                    depth[0]--
                }
            }
            0x41 -> { // kBeginSparseJSArray
                val length = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} kBeginSparseJSArray length=$length [")
                depth[0]++
                while (r.remaining() > 0 && r.peek() != 0x40) {
                    walkValue(r, out, depth, indent + 1)
                    if (r.remaining() == 0 || r.peek() == 0x40) break
                    walkValue(r, out, depth, indent + 1)
                }
                if (r.remaining() > 0 && r.peek() == 0x40) {
                    r.read()
                    val propCount = r.readVarint()
                    val totalLen = r.readVarint()
                    out.appendLine("${pad}] kEndSparseJSArray propCount=$propCount totalLen=$totalLen")
                    depth[0]--
                }
            }
            0x42 -> { // kArrayBuffer
                val byteLen = r.readVarint()
                val bytes = r.readBytes(byteLen)
                out.appendLine("${pad}[$start] tag=${printable(tag)} kArrayBuffer byteLen=$byteLen head=${hex(bytes, 16)}")
            }
            0x75 -> { // kSharedArrayBuffer
                val id = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} kSharedArrayBuffer id=$id")
            }
            0x59 -> { // kResizableArrayBuffer
                val byteLen = r.readVarint()
                val maxByteLen = r.readVarint()
                val bytes = r.readBytes(byteLen)
                out.appendLine("${pad}[$start] tag=${printable(tag)} kResizableArrayBuffer byteLen=$byteLen maxByteLen=$maxByteLen head=${hex(bytes, 16)}")
            }
            0x56 -> { // kArrayBufferView — the suspect
                val subtag = r.read()
                val subName = when (subtag) {
                    0x62 -> "kInt8Array"
                    0x42 -> "kUint8Array"
                    0x43 -> "kUint8ClampedArray"
                    0x77 -> "kInt16Array"
                    0x57 -> "kUint16Array"
                    0x64 -> "kInt32Array"
                    0x44 -> "kUint32Array"
                    0x66 -> "kFloat32Array"
                    0x46 -> "kFloat64Array"
                    0x71 -> "kBigInt64Array"
                    0x51 -> "kBigUint64Array"
                    0x3F -> "kDataView"
                    else -> "UNKNOWN_SUBTAG"
                }
                val byteOffset = r.readVarint()
                val byteLength = r.readVarint()
                // flags varint — present in V8 11.3+ (Chromium ~113+), absent in V8 10.7 (Chromium 109)
                // we always read it — if the NEXT byte is a valid tag (0x7b, 0x22, etc.) then flags wasn't written.
                // peek ahead to distinguish: if remaining first byte looks like a tag, flags was omitted.
                val markerPos = r.pos
                val next = if (r.remaining() > 0) r.peek() else -1
                val flagsPresent = next == 0 // flags varint is always 0 in this data per prior inspection
                val flags = if (flagsPresent) r.readVarint() else -1
                out.appendLine("${pad}[$start] tag=${printable(tag)} kArrayBufferView sub=${printable(subtag)}($subName) offset=$byteOffset length=$byteLength flagsPresent=$flagsPresent flags=$flags (flags-byte peek at pos=$markerPos = 0x${"%02x".format(next)})")
            }
            0x5E -> { // kObjectReference
                val id = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} kObjectReference id=$id")
            }
            0x72 -> { // kRegExp
                val patternLen = r.readVarint()
                val patternBytes = r.readBytes(patternLen)
                val flags = r.readVarint()
                out.appendLine("${pad}[$start] tag=${printable(tag)} kRegExp patternLen=$patternLen pattern=\"${asciiPrint(patternBytes)}\" flags=$flags")
            }
            0x3B -> { // kMap (legacy)
                out.appendLine("${pad}[$start] tag=${printable(tag)} kMap (legacy)")
            }
            0x3F -> { // kDoubleHeader? legacy
                out.appendLine("${pad}[$start] tag=${printable(tag)} kDouble?legacy")
                r.readDoubleLE()
            }
            else -> {
                out.appendLine("${pad}[$start] tag=${printable(tag)} !!! UNKNOWN/UNHANDLED — aborting record")
                return false
            }
        }
        return true
    }

    private fun printable(b: Int): String {
        val c = b and 0xFF
        val ch = if (c in 0x20..0x7E) c.toChar().toString() else "?"
        return "0x${"%02x".format(c)}/'$ch'"
    }

    private fun hex(b: ByteArray, max: Int): String {
        val n = minOf(b.size, max)
        return (0 until n).joinToString(" ") { "%02x".format(b[it]) } + if (b.size > max) " ..." else ""
    }

    private fun asciiPrint(b: ByteArray): String =
        b.joinToString("") {
            val c = it.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "."
        }

    // UTF-16 LE decode for kTwoByteString — bytes are little-endian code units.
    private fun utf16LePrint(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < b.size) {
            val lo = b[i].toInt() and 0xFF
            val hi = b[i + 1].toInt() and 0xFF
            val cu = (hi shl 8) or lo
            if (cu in 0x20..0x7E) sb.append(cu.toChar()) else sb.append('.')
            i += 2
        }
        return sb.take(48).toString()
    }

    private fun asciiKey(k: ByteArray): String = asciiPrint(k).take(64)

    private class Reader(private val buf: ByteArray) {
        var pos: Int = 0
        fun remaining(): Int = buf.size - pos
        fun read(): Int = buf[pos++].toInt() and 0xFF
        fun peek(): Int = if (pos < buf.size) buf[pos].toInt() and 0xFF else -1
        fun readBytes(n: Int): ByteArray {
            if (n < 0 || n > remaining()) throw IllegalStateException("readBytes($n) exceeds remaining=${remaining()}")
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun rest(): ByteArray = buf.copyOfRange(pos, buf.size)
        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw IllegalStateException("varint EOF at pos=$pos")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                if (shift > 28) throw IllegalStateException("varint too big")
            }
        }
        fun readZigZagVarint(): Int {
            val raw = readVarint()
            return (raw ushr 1) xor -(raw and 1)
        }
        fun readDoubleLE(): Double {
            if (remaining() < 8) throw IllegalStateException("double EOF")
            var bits = 0L
            for (i in 0 until 8) bits = bits or ((buf[pos + i].toLong() and 0xFF) shl (i * 8))
            pos += 8
            return Double.fromBits(bits)
        }
    }
}
