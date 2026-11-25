// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
// The SPLicense layout follows CikExtractor (MIT, Copyright (c) 2022 LukeFZ).
package app.gamenative.service.xbox.live

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class SpLicenseException(message: String, cause: Throwable? = null) : LiveException(message, cause)

/** Parsed SPLicense TLV block (device or content license). */
class SpLicense {
    var licenseId: UUID = UUID(0, 0)
    var deviceId: ByteArray = ByteArray(0)
    var keyholderKeyLicenseId: UUID = UUID(0, 0)
    var packageName: String = ""
    var signatureOrigin: Int = 0
    var signatureBlock: ByteArray = ByteArray(0)
    var clepSignState: ByteArray? = null
    var encryptedDeviceKey: ByteArray? = null
    val contentKeys = LinkedHashMap<UUID, ByteArray>()
    var keyholderPublicKey: ByteArray = ByteArray(0)
    var keyholderPolicies: ByteArray = ByteArray(0)
    var licensePolicies: ByteArray = ByteArray(0)
    val entryIds = mutableListOf<ByteArray>()
    var hardwareId: ByteArray = ByteArray(0)
    var pollingTime: Long = 0
    var licenseExpirationTime: Long = 0

    companion object {
        private const val LICENSE_ID = 0xcb
        private const val DEVICE_LICENSE_DEVICE_ID = 0x2
        private const val LICENSE_DEVICE_ID = 0xd2
        private const val KEYHOLDER_KEY_LICENSE_ID = 0xde
        private const val ENCRYPTED_DEVICE_KEY = 0x1
        private const val PACKAGE_FULL_NAME = 0xce
        private const val PACKED_CONTENT_KEYS = 0xca
        private const val CLEP_SIGN_STATE = 0x12d
        private const val SIGNATURE_BLOCK = 0xcc
        private const val POLLING_TIME = 0xd3
        private const val LICENSE_EXPIRATION_TIME = 0x20
        private const val DEVICE_LICENSE_EXPIRATION_TIME = 0x1f
        private const val HARDWARE_ID = 0xd0
        private const val LICENSE_INFORMATION = 0xc9
        private const val LICENSE_ENTRY_IDS = 0xcd
        private const val KEYHOLDER_PUBLIC_SIGNING_KEY = 0xdc
        private const val KEYHOLDER_POLICIES = 0xdd
        private const val LICENSE_POLICIES = 0xd4

        fun parseBase64(value: String): SpLicense {
            val data = try {
                Base64.getMimeDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw SpLicenseException("could not decode base64 string", e)
            }
            return decode(data)
        }

        fun decode(data: ByteArray): SpLicense {
            val input = ByteArrayInputStream(data)
            readExact(input, 4)
            readU32(input)
            val license = SpLicense()
            while (license.mergeTlv(input)) Unit
            return license
        }

        private fun readExact(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw SpLicenseException("IO error: failed to fill whole buffer", EOFException())
                off += r
            }
            return buf
        }

        private fun readU32(input: InputStream): Long =
            ByteBuffer.wrap(readExact(input, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

        private fun readU16(input: InputStream): Int =
            ByteBuffer.wrap(readExact(input, 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

        private fun readUuid(input: InputStream): UUID = uuidFromBytesLe(readExact(input, 16))
    }

    /** Reads at most [limit] bytes from the underlying stream, like `Read::take`. */
    private class Limited(private val input: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val r = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r > 0) remaining -= r
            return r
        }
    }

    private fun mergeTlv(input: InputStream): Boolean {
        val first = ByteArray(4)
        val n = input.read(first, 0, 4)
        if (n <= 0) return false
        if (n < 4) System.arraycopy(readExact(input, 4 - n), 0, first, n, 4 - n)
        val blockId = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN).int
        val size = readU32(input)
        val reader = Limited(input, size)
        val sizeInt = size.toInt()
        when (blockId) {
            LICENSE_ID -> licenseId = readUuid(reader)
            DEVICE_LICENSE_DEVICE_ID, LICENSE_DEVICE_ID -> deviceId = readExact(reader, sizeInt)
            KEYHOLDER_KEY_LICENSE_ID -> keyholderKeyLicenseId = readUuid(reader)
            ENCRYPTED_DEVICE_KEY -> encryptedDeviceKey = readExact(reader, BLOB_SIZE)
            PACKAGE_FULL_NAME -> {
                val raw = readExact(reader, sizeInt)
                if (raw.size % 2 != 0) throw SpLicenseException("invalid UTF-16 package name: odd length")
                packageName = try {
                    Charsets.UTF_16LE.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString()
                } catch (e: CharacterCodingException) {
                    throw SpLicenseException("invalid UTF-16 package name", e)
                }
            }
            PACKED_CONTENT_KEYS -> {
                var offset = 0L
                while (offset < size) {
                    val idLen = readU16(reader)
                    readU16(reader)
                    if (idLen < 16) throw SpLicenseException("PackedContentKey id_len $idLen is less than 16")
                    val keyId = readUuid(reader)
                    readExact(reader, idLen - 16)
                    contentKeys[keyId] = readExact(reader, 40)
                    offset += 4 + idLen + 40
                }
            }
            CLEP_SIGN_STATE -> clepSignState = readExact(reader, BLOB_SIZE)
            SIGNATURE_BLOCK -> {
                if (sizeInt < 4) throw SpLicenseException("Signature block is $size bytes")
                readExact(reader, 2)
                signatureOrigin = readU16(reader)
                signatureBlock = readExact(reader, sizeInt - 4)
            }
            POLLING_TIME -> pollingTime = readU32(reader)
            LICENSE_EXPIRATION_TIME, DEVICE_LICENSE_EXPIRATION_TIME -> licenseExpirationTime = readU32(reader)
            HARDWARE_ID -> hardwareId = readExact(reader, sizeInt)
            LICENSE_INFORMATION -> readExact(reader, 10)
            LICENSE_ENTRY_IDS -> repeat(readU16(reader)) { entryIds.add(readExact(reader, 32)) }
            KEYHOLDER_PUBLIC_SIGNING_KEY -> keyholderPublicKey = readExact(reader, sizeInt)
            KEYHOLDER_POLICIES -> keyholderPolicies = readExact(reader, sizeInt)
            LICENSE_POLICIES -> licensePolicies = readExact(reader, sizeInt)
            else -> readExact(reader, sizeInt)
        }
        if (reader.remaining != 0L) {
            throw SpLicenseException("expected to read $size bytes but only ${size - reader.remaining} were read")
        }
        return true
    }
}

internal const val BLOB_SIZE = 4096

/**
 * The 4096-byte secret blobs (ClepSignState, ClepHmacState, EncryptedDeviceKey) carry an AES key schedule;
 * the AES-128 key is read back from fixed schedule words and decrypts the payload with CBC and a zero IV.
 */
object LiveBlobs {
    private fun u32(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun schedule(data: ByteArray, offset: Int): IntArray = IntArray(58) { u32(data, offset + it * 4) }

    fun decryptionKey(schedule: IntArray): ByteArray {
        val words = intArrayOf(
            schedule[46] xor schedule[56] xor 0xE20DF371.toInt() xor 0xCCB22FE6.toInt(),
            schedule[36] xor schedule[47] xor 0xDF080E39.toInt(),
            schedule[40] xor schedule[51] xor 0x6D09B2F5 xor 0x2AE17AB9,
            schedule[30] xor schedule[41] xor 0x37288CEC,
        )
        val out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        words.forEach { out.putInt(it) }
        return out.array()
    }

    fun decryptCbcZeroIv(schedule: IntArray, data: ByteArray): ByteArray {
        require(data.size % 16 == 0)
        return Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptionKey(schedule), "AES"), javax.crypto.spec.IvParameterSpec(ByteArray(16)))
            doFinal(data)
        }
    }

    private fun checkBlob(blob: ByteArray, versionOffset: Int, name: String) {
        if (blob.size != BLOB_SIZE) throw SpLicenseException("$name is ${blob.size} bytes, expected $BLOB_SIZE")
        val version = u32(blob, versionOffset)
        if (version != 4) throw SpLicenseException("$name version $version is not supported")
    }

    /** ClepSignState: version, 544-byte key data, key schedule. Returns the BCrypt RSA private key blob. */
    fun clepSignRsaBlob(blob: ByteArray): ByteArray {
        checkBlob(blob, 0, "ClepSignState")
        return decryptCbcZeroIv(schedule(blob, 548), blob.copyOfRange(4, 548))
    }

    /** ClepHmacState: version, 48-byte key data, key schedule. Returns the 32-byte HMAC secret after the BCrypt key header. */
    fun clepHmacSecret(blob: ByteArray): ByteArray {
        checkBlob(blob, 0, "ClepHmacState")
        return decryptCbcZeroIv(schedule(blob, 52), blob.copyOfRange(4, 52)).copyOfRange(12, 44)
    }

    /** EncryptedDeviceKey: u16 size, version, key schedule, 280 bytes, 16-byte encrypted device key. */
    fun deviceKey(blob: ByteArray): ByteArray {
        checkBlob(blob, 2, "EncryptedDeviceKey")
        val schedule = schedule(blob, 6)
        val key = decryptCbcZeroIv(schedule, blob.copyOfRange(518, 534))
        if (!key.contentEquals(decryptionKey(schedule))) throw SpLicenseException("Device key does not match its key schedule")
        return key
    }

    /** RFC 3394 AES key unwrap of a 40-byte packed content key into the 32-byte content key. */
    fun unwrapContentKey(deviceKey: ByteArray, packed: ByteArray): ByteArray {
        if (packed.size != 40) throw SpLicenseException("Packed content key is ${packed.size} bytes")
        val n = packed.size / 8 - 1
        val a = packed.copyOfRange(0, 8)
        val r = Array(n) { packed.copyOfRange(8 + it * 8, 16 + it * 8) }
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(deviceKey, "AES")) }
        val block = ByteArray(16)
        for (j in 5 downTo 0) {
            for (i in n downTo 1) {
                val t = (n * j + i).toLong()
                for (k in 0 until 8) a[7 - k] = (a[7 - k].toInt() xor ((t ushr (8 * k)).toInt() and 0xff)).toByte()
                System.arraycopy(a, 0, block, 0, 8)
                System.arraycopy(r[i - 1], 0, block, 8, 8)
                val b = aes.doFinal(block)
                System.arraycopy(b, 0, a, 0, 8)
                r[i - 1] = b.copyOfRange(8, 16)
            }
        }
        if (!a.all { it == 0xA6.toByte() }) throw SpLicenseException("the ciphertext couldn't be authenticated")
        return ByteArray(n * 8).also { out -> r.forEachIndexed { i, b -> System.arraycopy(b, 0, out, i * 8, 8) } }
    }

    /** BCRYPT_RSAPRIVATE_BLOB / BCRYPT_RSAFULLPRIVATE_BLOB to a JCA key. */
    fun rsaPrivateKey(blob: ByteArray): RSAPrivateCrtKey {
        val magic = u32(blob, 0)
        val cbPubExp = u32(blob, 8)
        val cbMod = u32(blob, 12)
        val cbP1 = u32(blob, 16)
        val cbP2 = u32(blob, 20)
        var off = 24
        fun take(n: Int): BigInteger {
            if (n < 0 || off + n > blob.size) throw SpLicenseException("RSA blob is truncated")
            return BigInteger(1, blob.copyOfRange(off, off + n)).also { off += n }
        }
        val e = take(cbPubExp)
        val n = take(cbMod)
        val p = take(cbP1)
        val q = take(cbP2)
        val one = BigInteger.ONE
        val d = when (magic) {
            RSA_FULL_PRIVATE_MAGIC -> {
                take(cbP1)
                take(cbP2)
                take(cbP1)
                take(cbMod)
            }
            RSA_PRIVATE_MAGIC -> e.modInverse((p - one) * (q - one))
            else -> throw SpLicenseException("not an RSA private blob")
        }
        if (p * q != n) throw SpLicenseException("RSA blob primes do not match the modulus")
        val spec = RSAPrivateCrtKeySpec(n, e, d, p, q, d.mod(p - one), d.mod(q - one), q.modInverse(p))
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateCrtKey
    }

    private const val RSA_PRIVATE_MAGIC = 0x32415352 // "RSA2"
    private const val RSA_FULL_PRIVATE_MAGIC = 0x33415352 // "RSA3"
}

/** GUID bytes in Windows mixed-endian order. */
fun uuidFromBytesLe(b: ByteArray): UUID {
    require(b.size == 16)
    val swapped = byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
    val buf = ByteBuffer.wrap(swapped)
    return UUID(buf.long, buf.long)
}

fun uuidToBytesLe(id: UUID): ByteArray {
    val be = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
    return byteArrayOf(be[3], be[2], be[1], be[0], be[5], be[4], be[7], be[6]) + be.copyOfRange(8, 16)
}
