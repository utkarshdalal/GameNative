package app.gamenative.service.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonManifestTest {

    @Test
    fun parse_uncompressedManifest_parsesFilesAndSizes() {
        val headerBytes = protobufMessage {
            // ManifestHeader.compression (field 1)
            field(1, wireType = 2, value = protobufMessage {
                // CompressionSettings.algorithm = 0 (none)
                field(1, wireType = 0, value = byteArrayOf(0x00))
            })
        }

        val fileMessage = protobufMessage {
            field(1, wireType = 2, value = "bin\\game.exe".encodeToByteArray())
            field(3, wireType = 0, value = varintBytes(123L))
            field(5, wireType = 2, value = protobufMessage {
                field(1, wireType = 0, value = byteArrayOf(0x00)) // sha256
                field(2, wireType = 2, value = byteArrayOf(0x01, 0x02, 0x03))
            })
        }

        val packageMessage = protobufMessage {
            field(1, wireType = 2, value = "base".encodeToByteArray())
            field(2, wireType = 2, value = fileMessage)
        }

        val manifestBody = protobufMessage {
            // Manifest.packages (field 1)
            field(1, wireType = 2, value = packageMessage)
        }

        val bytes = buildManifestBytes(headerBytes, manifestBody)
        val parsed = AmazonManifest.parse(bytes)

        assertEquals(1, parsed.packages.size)
        assertEquals("base", parsed.packages[0].name)
        assertEquals(1, parsed.allFiles.size)
        assertEquals(123L, parsed.totalInstallSize)

        val file = parsed.allFiles.first()
        assertEquals("bin\\game.exe", file.path)
        assertEquals("bin/game.exe", file.unixPath)
        assertEquals(123L, file.size)
        assertEquals(0, file.hashAlgorithm)
        assertTrue(file.hashBytes.contentEquals(byteArrayOf(0x01, 0x02, 0x03)))
    }

    private fun buildManifestBytes(header: ByteArray, body: ByteArray): ByteArray {
        val headerSize = header.size
        val prefix = byteArrayOf(
            ((headerSize ushr 24) and 0xFF).toByte(),
            ((headerSize ushr 16) and 0xFF).toByte(),
            ((headerSize ushr 8) and 0xFF).toByte(),
            (headerSize and 0xFF).toByte(),
        )
        return prefix + header + body
    }

    private fun protobufMessage(builder: ProtoBuilder.() -> Unit): ByteArray {
        val proto = ProtoBuilder()
        proto.builder()
        return proto.toByteArray()
    }

    private fun varintBytes(value: Long): ByteArray {
        var remaining = value
        val out = ArrayList<Byte>()
        while (true) {
            if ((remaining and 0x7FL.inv()) == 0L) {
                out.add(remaining.toByte())
                break
            }
            out.add(((remaining and 0x7F) or 0x80).toByte())
            remaining = remaining ushr 7
        }
        return out.toByteArray()
    }

    private inner class ProtoBuilder {
        private val bytes = ArrayList<Byte>()

        fun field(fieldNumber: Int, wireType: Int, value: ByteArray) {
            bytes.addAll(varintBytes(((fieldNumber shl 3) or wireType).toLong()).toList())
            when (wireType) {
                0 -> bytes.addAll(value.toList())
                2 -> {
                    bytes.addAll(varintBytes(value.size.toLong()).toList())
                    bytes.addAll(value.toList())
                }
                else -> error("Unsupported wire type in test builder: $wireType")
            }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
