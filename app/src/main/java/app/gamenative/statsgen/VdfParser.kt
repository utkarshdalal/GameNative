package app.gamenative.statsgen

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VdfParser {
    companion object {
        private const val VDF_SUBSECTION = 0x00.toByte()
        private const val VDF_STRING = 0x01.toByte()
        private const val VDF_INT32 = 0x02.toByte()
        private const val VDF_FLOAT32 = 0x03.toByte()
        private const val VDF_INT64 = 0x07.toByte()
        private const val VDF_UINT64 = 0x0A.toByte()
        private const val VDF_END = 0x08.toByte()
    }

    fun binaryLoads(data: ByteArray): Map<String, Any> {
        val stream = DataInputStream(ByteArrayInputStream(data))
        return parseVdfData(stream)
    }

    private fun parseVdfData(stream: DataInputStream): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        while (stream.available() > 0) {
            val dataType = try {
                stream.readByte()
            } catch (e: Exception) {
                break
            }

            when (dataType) {
                VDF_END -> break
                VDF_SUBSECTION -> {
                    val key = readString(stream)
                    val value = parseVdfData(stream)
                    result[key] = value
                }
                VDF_STRING -> {
                    val key = readString(stream)
                    val value = readString(stream)
                    result[key] = value
                }
                VDF_INT32 -> {
                    val key = readString(stream)
                    val value = readInt32(stream)
                    result[key] = value
                }
                VDF_FLOAT32 -> {
                    val key = readString(stream)
                    val value = readFloat32(stream)
                    result[key] = value
                }
                VDF_INT64 -> {
                    val key = readString(stream)
                    val value = readInt64(stream)
                    result[key] = value
                }
                VDF_UINT64 -> {
                    val key = readString(stream)
                    val value = readUInt64(stream)
                    result[key] = value
                }
            }
        }

        return result
    }

    private fun readString(stream: DataInputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val byte = stream.readByte()
            if (byte == 0.toByte()) break
            bytes.add(byte)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun readInt32(stream: DataInputStream): Int {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readFloat32(stream: DataInputStream): Float {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun readInt64(stream: DataInputStream): Long {
        val bytes = ByteArray(8)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readUInt64(stream: DataInputStream): Long {
        val bytes = ByteArray(8)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }
}
