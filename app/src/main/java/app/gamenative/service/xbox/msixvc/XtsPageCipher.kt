// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * XTS-AES-128 (IEEE 1619-2007) over 4096-byte pages. The 32-byte CIK is tweakKey(16) || dataKey(16);
 * the per-page tweak is dataUnit (u32 LE) || regionId (u32 LE) || vduid bytes 0..8 (as stored on disk).
 *
 * A run of pages costs one ECB encrypt for all tweaks and one ECB call over all page bytes;
 * the 256 per-block tweak values of a page are derived by GF(2^128) multiply-by-x.
 * Not thread-safe: use one instance per worker.
 */
class XtsPageCipher(cik: ByteArray) {
    init {
        require(cik.size == 32) { "content key must be 32 bytes" }
    }

    private val tweakKey = SecretKeySpec(cik.copyOfRange(0, 16), "AES")
    private val dataKey = SecretKeySpec(cik.copyOfRange(16, 32), "AES")
    private val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, tweakKey) }
    private val decCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, dataKey) }
    private var encCipher: Cipher? = null

    private var tweakBuf = ByteArray(0)
    private var mask = ByteArray(0)

    fun decryptPages(buf: ByteArray, off: Int, pageCount: Int, regionId: Int, vduid: ByteArray, dataUnits: IntArray, unitsOff: Int = 0) =
        transform(buf, off, pageCount, regionId, vduid, dataUnits, unitsOff, decCipher)

    fun encryptPages(buf: ByteArray, off: Int, pageCount: Int, regionId: Int, vduid: ByteArray, dataUnits: IntArray, unitsOff: Int = 0) {
        val c = encCipher ?: Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, dataKey) }.also { encCipher = it }
        transform(buf, off, pageCount, regionId, vduid, dataUnits, unitsOff, c)
    }

    private fun transform(
        buf: ByteArray,
        off: Int,
        pageCount: Int,
        regionId: Int,
        vduid: ByteArray,
        dataUnits: IntArray,
        unitsOff: Int,
        blockCipher: Cipher,
    ) {
        if (pageCount == 0) return
        val bytes = pageCount * PAGE
        if (tweakBuf.size < pageCount * 16) tweakBuf = ByteArray(pageCount * 16)
        if (mask.size < bytes) mask = ByteArray(bytes)

        for (i in 0 until pageCount) {
            val t = i * 16
            val du = dataUnits[unitsOff + i]
            tweakBuf[t] = du.toByte()
            tweakBuf[t + 1] = (du ushr 8).toByte()
            tweakBuf[t + 2] = (du ushr 16).toByte()
            tweakBuf[t + 3] = (du ushr 24).toByte()
            tweakBuf[t + 4] = regionId.toByte()
            tweakBuf[t + 5] = (regionId ushr 8).toByte()
            tweakBuf[t + 6] = (regionId ushr 16).toByte()
            tweakBuf[t + 7] = (regionId ushr 24).toByte()
            System.arraycopy(vduid, 0, tweakBuf, t + 8, 8)
        }
        tweakCipher.doFinal(tweakBuf, 0, pageCount * 16, tweakBuf, 0)

        val tw = ByteBuffer.wrap(tweakBuf).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteBuffer.wrap(mask).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pageCount) {
            var lo = tw.getLong(i * 16)
            var hi = tw.getLong(i * 16 + 8)
            var p = i * PAGE
            for (b in 0 until BLOCKS_PER_PAGE) {
                m.putLong(p, lo)
                m.putLong(p + 8, hi)
                p += 16
                val carry = hi ushr 63
                hi = (hi shl 1) or (lo ushr 63)
                lo = (lo shl 1) xor (0x87L and -carry)
            }
        }

        val data = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        xorInto(data, off, m, bytes)
        blockCipher.doFinal(buf, off, bytes, buf, off)
        xorInto(data, off, m, bytes)
    }

    private fun xorInto(data: ByteBuffer, off: Int, m: ByteBuffer, bytes: Int) {
        var i = 0
        while (i < bytes) {
            data.putLong(off + i, data.getLong(off + i) xor m.getLong(i))
            i += 8
        }
    }

    companion object {
        const val PAGE = 4096
        private const val BLOCKS_PER_PAGE = PAGE / 16

        /** Multiply-by-x in GF(2^128) mod x^128 + x^7 + x^2 + x + 1, value as little-endian (lo, hi). */
        fun gfMulX(lo: Long, hi: Long): Pair<Long, Long> {
            val carry = hi ushr 63
            return Pair((lo shl 1) xor (0x87L and -carry), (hi shl 1) or (lo ushr 63))
        }
    }
}
