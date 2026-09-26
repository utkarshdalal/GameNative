package app.gamenative.texturepack

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import net.jpountz.xxhash.XXHashFactory

object TextureCacheStore {

    const val XXH64_SEED = 1L

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private val hash64 by lazy { XXHashFactory.fastestJavaInstance().hash64() }

    fun contentHash(mip: ByteArray): Long = hash64.hash(mip, 0, mip.size, XXH64_SEED)

    fun contentKey(src: String, width: Int, height: Int, mip: ByteArray, policy: PackPolicy? = null): String =
        contentKey(src, width, height, contentHash(mip), policy)

    fun contentKey(src: String, width: Int, height: Int, hash: Long, policy: PackPolicy? = null): String =
        TexturePackKeys.key(src, width, height, hash, policy)

    fun astcSizeBytes(width: Int, height: Int, blockSize: Int = 4): Long {
        val blocksX = (width + blockSize - 1) / blockSize
        val blocksY = (height + blockSize - 1) / blockSize
        return blocksX.toLong() * blocksY.toLong() * 16L
    }

    fun expectedAstcSizeForKey(key: String): Long? {
        val blockSize = TexturePackKeys.blockSizeOf(key) ?: return null
        val (w, h) = TexturePackKeys.dimensionsOf(key) ?: return null
        return astcSizeBytes(w, h, blockSize)
    }

    fun isZstd(payload: ByteArray): Boolean =
        payload.size >= ZSTD_MAGIC.size && ZSTD_MAGIC.indices.all { payload[it] == ZSTD_MAGIC[it] }

    private fun hasZstdMagic(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(ZSTD_MAGIC.size)
            input.read(head) == head.size && isZstd(head)
        }
    } catch (e: Exception) {
        false
    }

    fun isServerEntry(cacheDir: File, key: String): Boolean {
        val file = File(cacheDir, key)
        return file.isFile && hasZstdMagic(file)
    }

    fun isDeviceEntry(cacheDir: File, key: String): Boolean {
        val file = File(cacheDir, key)
        return file.isFile && !hasZstdMagic(file)
    }

    fun hasEntry(cacheDir: File, key: String): Boolean {
        val file = File(cacheDir, key)
        if (!file.isFile) return false
        val expected = expectedAstcSizeForKey(key) ?: return file.length() > 0L
        return file.length() == expected || hasZstdMagic(file)
    }

    fun writeEntryAtomic(cacheDir: File, key: String, payload: ByteArray): Boolean {
        val expected = expectedAstcSizeForKey(key)
        if (expected != null && payload.size.toLong() != expected && !isZstd(payload)) return false
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return false
        val temp = File(cacheDir, ".$key.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(payload)
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

    fun pruneSuperseded(cacheDir: File, key: String): Int {
        val tag = TexturePackKeys.outTagOf(key) ?: return 0
        val stem = TexturePackKeys.stem(key)
        val doomed = TexturePackKeys.OUT_TAGS
            .filter { it != tag }
            .map { File(cacheDir, "$stem.$it") } + File(cacheDir, stem + TexturePackKeys.SOURCE_SUFFIX)
        var oldest = Long.MAX_VALUE
        var removed = 0
        for (file in doomed) {
            if (!file.isFile) continue
            val modified = file.lastModified()
            if (file.delete()) {
                removed++
                if (modified in 1 until oldest) oldest = modified
            }
        }
        val entry = File(cacheDir, key)
        if (oldest != Long.MAX_VALUE && entry.isFile && oldest < entry.lastModified()) {
            entry.setLastModified(oldest)
        }
        return removed
    }
}
