package app.gamenative.texturepack

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class TexturePackRecord(val key: String, val payload: ByteArray)

object TexturePackFraming {

    const val MAX_KEY_LENGTH = 0xFFFF
    const val MAX_PAYLOAD_LENGTH = 256L * 1024L * 1024L

    fun writeRecord(out: OutputStream, key: String, payload: ByteArray) {
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        require(keyBytes.size <= MAX_KEY_LENGTH) { "key too long: ${keyBytes.size}" }
        val header = ByteArray(2 + keyBytes.size + 4)
        header[0] = keyBytes.size.toByte()
        header[1] = (keyBytes.size ushr 8).toByte()
        System.arraycopy(keyBytes, 0, header, 2, keyBytes.size)
        val length = payload.size
        val at = 2 + keyBytes.size
        header[at] = length.toByte()
        header[at + 1] = (length ushr 8).toByte()
        header[at + 2] = (length ushr 16).toByte()
        header[at + 3] = (length ushr 24).toByte()
        out.write(header)
        out.write(payload)
    }

    fun encode(records: List<TexturePackRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        records.forEach { writeRecord(out, it.key, it.payload) }
        return out.toByteArray()
    }

    fun readRecord(input: InputStream): TexturePackRecord? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) throw EOFException("truncated record key length")
        val keyBytes = ByteArray(first or (second shl 8))
        readFully(input, keyBytes)
        val lengthBytes = ByteArray(4)
        readFully(input, lengthBytes)
        val length = (lengthBytes[0].toLong() and 0xFF) or
            ((lengthBytes[1].toLong() and 0xFF) shl 8) or
            ((lengthBytes[2].toLong() and 0xFF) shl 16) or
            ((lengthBytes[3].toLong() and 0xFF) shl 24)
        if (length > MAX_PAYLOAD_LENGTH) throw IOException("record payload too large: $length")
        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        return TexturePackRecord(String(keyBytes, Charsets.US_ASCII), payload)
    }

    fun decode(input: InputStream): List<TexturePackRecord> {
        val records = mutableListOf<TexturePackRecord>()
        while (true) records += readRecord(input) ?: break
        return records
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("truncated record")
            offset += read
        }
    }
}
