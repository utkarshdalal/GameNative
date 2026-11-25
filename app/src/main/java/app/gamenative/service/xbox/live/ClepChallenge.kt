// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CLEP hardware challenge blobs sent as device components 8196 (v2) and 8197 (v4).
 * Both are 2048-byte packed structures run through a keyless Feistel obfuscation in a CBC-like mode.
 */
object ClepChallenge {
    const val SIZE = 2048

    /** Returns the obfuscated (v2, v4) blobs for a 256-byte SMBIOS buffer and a 64-byte disk serial buffer. */
    fun licenseChallenge(smbios: ByteArray, diskSerial: ByteArray): Pair<ByteArray, ByteArray> {
        val v2 = plainV2(smbios, diskSerial)
        val v4 = plainV4(smbios, diskSerial)
        obfuscate(v2)
        obfuscate(v4)
        return v2 to v4
    }

    /** ClepV2: version, smbios[256], disk_serial[64], always_0 u32, always_1 bool, then TPM and reserved fields left zero. */
    fun plainV2(smbios: ByteArray, diskSerial: ByteArray): ByteArray {
        require(smbios.size == 256 && diskSerial.size == 64)
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 2)
        b.position(4)
        b.put(smbios).put(diskSerial)
        b.putInt(324, 0)
        b.put(328, 1)
        return b.array()
    }

    /** ClepV4: version, smbios[256], disk_serial[64], TPM fields, then debugger_not_present u32 @1231. */
    fun plainV4(smbios: ByteArray, diskSerial: ByteArray): ByteArray {
        require(smbios.size == 256 && diskSerial.size == 64)
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 4)
        b.position(4)
        b.put(smbios).put(diskSerial)
        b.putInt(1231, 1)
        return b.array()
    }

    private const val MAGIC_LO = 0x41393243
    private const val MAGIC_HI = 0x24181621
    private const val MAGIC_01 = 0x2418
    private const val MAGIC_02 = 0x1621
    private const val MAGIC_03 = 0x4139
    private const val MAGIC_04 = 0x3243

    private fun ror(x: Int, n: Int) = Integer.rotateRight(x, n)
    private fun rol(x: Int, n: Int) = Integer.rotateLeft(x, n)

    private fun round1(x: Int) = MAGIC_04 * ror(x xor MAGIC_HI, 22) - ror(x, 8)
    private fun round2(x: Int) = MAGIC_04 * (ror(x, 15) xor MAGIC_01)
    private fun round3(x: Int) = (x ushr 9) + MAGIC_02 * rol(x xor MAGIC_03, 3)
    private fun round4(x: Int) = ror(x, 28) xor (MAGIC_03 * ror(x xor MAGIC_HI, 9))
    private fun round5(x: Int) = ror(x, 12) + MAGIC_04 * ror(x - MAGIC_HI, 14)
    private fun round6(x: Int) = ror(x, 11) xor (MAGIC_01 * rol(x xor MAGIC_02, 2))
    private fun round7(x: Int) = x - MAGIC_LO - MAGIC_02
    private fun round8(x: Int) = MAGIC_03 * rol(x xor MAGIC_01, 2) - ror(x, 18)
    private fun round0(x: Int) = MAGIC_04 * ror(x - MAGIC_HI, 18) - ror(x, 9)
    private fun whiten(x: Int) = MAGIC_03 * ror(x + MAGIC_HI, 10) - ror(x, 29)

    private val rounds: Array<(Int) -> Int> = arrayOf(::round1, ::round2, ::round3, ::round4, ::round5, ::round6, ::round7, ::round8)

    private fun runRounds(a0: Int, b0: Int): Pair<Int, Int> {
        var a = a0
        var b = b0
        for (r in rounds) {
            val next = a xor r(b)
            a = b
            b = next
        }
        return a to b
    }

    private fun runRoundsInverse(a0: Int, b0: Int): Pair<Int, Int> {
        var a = a0
        var b = b0
        for (r in rounds.reversed()) {
            val prev = b xor r(a)
            b = a
            a = prev
        }
        return a to b
    }

    private val INITIAL_STATE: Int = runRounds(0, (MAGIC_03 * ror(MAGIC_HI, 10)).inv()).second

    private class Cipher(iv: Int) {
        var lo = INITIAL_STATE xor iv
        var hi = 0
        var plain = iv.toLong() and 0xffffffffL

        fun encrypt(block: Long): Long {
            val blockLo = block.toInt()
            val blockHi = (block ushr 32).toInt()
            val ppLo = plain.toInt()
            val ppHi = (plain ushr 32).toInt()
            val r0 = lo xor blockLo
            val r1 = hi xor blockHi xor round0(r0)
            val (r8, r9) = runRoundsInverse(r0, r1)
            val newLo = ppLo xor r8 xor whiten(r9)
            val newHi = r9 xor ppHi
            lo = newLo
            hi = newHi
            plain = block
            return join(newLo, newHi)
        }

        fun decrypt(block: Long): Long {
            val newLo = block.toInt()
            val newHi = (block ushr 32).toInt()
            val ppLo = plain.toInt()
            val ppHi = (plain ushr 32).toInt()
            val r9 = newHi xor ppHi
            val r8 = newLo xor ppLo xor whiten(r9)
            val (r0, r1) = runRounds(r8, r9)
            val blockLo = lo xor r0
            val blockHi = hi xor r1 xor round0(r0)
            val p = join(blockLo, blockHi)
            lo = newLo
            hi = newHi
            plain = p
            return p
        }

        private fun join(lo: Int, hi: Int) = (lo.toLong() and 0xffffffffL) or (hi.toLong() shl 32)
    }

    fun obfuscate(buffer: ByteArray) {
        require(buffer.size == SIZE)
        val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val cipher = Cipher(b.getInt(4))
        b.putInt(4, cipher.lo)
        for (offset in 8 until SIZE step 8) b.putLong(offset, cipher.encrypt(b.getLong(offset)))
    }

    fun deobfuscate(buffer: ByteArray) {
        require(buffer.size == SIZE)
        val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val iv = INITIAL_STATE xor b.getInt(4)
        val cipher = Cipher(iv)
        b.putInt(4, iv)
        for (offset in 8 until SIZE step 8) b.putLong(offset, cipher.decrypt(b.getLong(offset)))
    }
}
