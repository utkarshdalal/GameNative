package app.gamenative.texturepack

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import net.jpountz.xxhash.XXHashFactory

object TextureCacheStore {

    const val XXH64_SEED = 1L

    private val hash64 by lazy { XXHashFactory.fastestJavaInstance().hash64() }

    fun contentHash(mip: ByteArray): Long = hash64.hash(mip, 0, mip.size, XXH64_SEED)

    fun contentKey(src: String, width: Int, height: Int, mip: ByteArray): String =
        contentKey(src, width, height, contentHash(mip))

    fun contentKey(src: String, width: Int, height: Int, hash: Long): String =
        String.format(Locale.ROOT, "%s_%dx%d_%016x.a4", src, width, height, hash)

    fun astcSizeBytes(width: Int, height: Int): Long {
        val blocksX = (width + 3) / 4
        val blocksY = (height + 3) / 4
        return blocksX.toLong() * blocksY.toLong() * 16L
    }

    fun expectedAstcSizeForKey(key: String): Long? {
        val dims = key.substringAfter('_', "").substringBefore('_', "")
        val x = dims.indexOf('x')
        if (x <= 0) return null
        val w = dims.substring(0, x).toIntOrNull() ?: return null
        val h = dims.substring(x + 1).toIntOrNull() ?: return null
        return astcSizeBytes(w, h)
    }

    fun hasEntry(cacheDir: File, key: String): Boolean {
        val file = File(cacheDir, key)
        if (!file.isFile) return false
        val expected = expectedAstcSizeForKey(key) ?: return file.length() > 0L
        return file.length() == expected
    }

    fun writeEntryAtomic(cacheDir: File, key: String, payload: ByteArray): Boolean {
        val expected = expectedAstcSizeForKey(key)
        if (expected != null && payload.size.toLong() != expected) return false
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return false
        val temp = File(cacheDir, ".$key.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            val target = File(cacheDir, key)
            if (temp.renameTo(target)) return true
            target.delete()
            return temp.renameTo(target)
        } catch (e: Exception) {
            return false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
