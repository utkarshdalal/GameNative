package app.gamenative.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SteamTokenHelper {
    companion object {
        private val obf = intArrayOf(
            0x1739a3b0.toInt(), 0xb8907fe1.toInt(), 0x8290d3b7.toInt(), 0x72839cd0.toInt(),
            0x242df096.toInt(), 0x3829750b.toInt(), 0x38de7a77.toInt(), 0x72f0924c.toInt(),
            0x44783927.toInt(), 0x01925372.toInt(), 0x20902714.toInt(), 0x27585920.toInt(),
            0x27890632.toInt(), 0x82910476.toInt(), 0x72906721.toInt(), 0x28798904.toInt(),
            0x78592700.toInt()
        )

        fun obfuscate(ptext: ByteArray, key: Long): String {
            val ctext = mutableListOf<Byte>()
            ctext.addAll(byteArrayOf(0x02, 0x00, 0x00, 0x00).toList())

            var k1 = (key shr 0x1f).toInt()
            var k2 = key.toInt()
            var csum = 0

            var remainingPtext = ptext

            while (remainingPtext.size >= 4) {
                k1 = (k1 + 0x25fe6761) and 0xffffffff.toInt()
                k2 = (k2 + 1) and 0xffffffff.toInt()

                val d = ByteBuffer.wrap(remainingPtext.sliceArray(0..3))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int

                val t = obf[k2 % 0x11] xor k1 xor d
                csum = (csum + d) and 0xffffffff.toInt()

                val tBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(t)
                    .array()

                ctext.addAll(tBytes.toList())

                remainingPtext = remainingPtext.sliceArray(4 until remainingPtext.size)
            }

            // Add remaining bytes
            ctext.addAll(remainingPtext.toList())

            k1 = (k1 + 0x25fe6761) and 0xffffffff.toInt()
            k2 = (k2 + 1) and 0xffffffff.toInt()
            val t = obf[k2 % 0x11] xor k1 xor csum

            val tBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(t)
                .array()

            ctext.addAll(tBytes.toList())

            return byteArrayToHexString(ctext.toByteArray())
        }

        fun deobfuscate(data: String, key: Long): String {
            val dataBytes = hexStringToByteArray(data)

            // Check header
            if (dataBytes.size < 4 ||
                dataBytes[0] != 0x02.toByte() ||
                dataBytes[1] != 0x00.toByte() ||
                dataBytes[2] != 0x00.toByte() ||
                dataBytes[3] != 0x00.toByte()) {
                throw Exception("wrong type of data")
            }

            val csumData = dataBytes.sliceArray(dataBytes.size - 4 until dataBytes.size)
            var ctext = dataBytes.sliceArray(4 until dataBytes.size - 4)
            val ptext = mutableListOf<Byte>()

            var k1 = (key shr 0x1f).toInt()
            var k2 = key.toInt()
            var csum = 0

            while (ctext.size >= 4) {
                k1 = (k1 + 0x25fe6761) and 0xffffffff.toInt()
                k2 = (k2 + 1) and 0xffffffff.toInt()

                val d = ByteBuffer.wrap(ctext.sliceArray(0..3))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int

                val t = obf[k2 % 0x11] xor k1 xor d
                csum = (csum + t) and 0xffffffff.toInt()

                val tBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(t)
                    .array()

                ptext.addAll(tBytes.toList())

                ctext = ctext.sliceArray(4 until ctext.size)
            }

            // Add remaining bytes
            ptext.addAll(ctext.toList())

            k1 = (k1 + 0x25fe6761) and 0xffffffff.toInt()
            k2 = (k2 + 1) and 0xffffffff.toInt()
            val t = obf[k2 % 0x11] xor k1 xor csum

            val tBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(t)
                .array()

            if (!tBytes.contentEquals(csumData)) {
                throw Exception("bad checksum! ${t.toString(16)}")
            }

            return ptext.toByteArray().decodeToString()
        }

        private fun hexStringToByteArray(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "")
            val len = cleanHex.length
            val data = ByteArray(len / 2)

            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                        Character.digit(cleanHex[i + 1], 16)).toByte()
            }
            return data
        }

        private fun byteArrayToHexString(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

