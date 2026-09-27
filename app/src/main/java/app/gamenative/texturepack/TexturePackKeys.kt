package app.gamenative.texturepack

import java.util.Locale

object TexturePackKeys {

    const val SOURCE_SUFFIX = ".src"

    private val BLOCK_SIZES = mapOf("a4" to 4, "a6" to 6, "a8" to 8, "h4" to 4)

    private val LDR_TAGS_BY_BLOCK = mapOf("4x4" to "a4", "6x6" to "a6", "8x8" to "a8")

    private const val HDR_PREFIX = "bc6h"

    val OUT_TAGS: Set<String> = BLOCK_SIZES.keys

    fun isHdr(srcTag: String): Boolean = srcTag.startsWith(HDR_PREFIX)

    fun defaultOutTagFor(srcTag: String): String = when {
        srcTag == "bc1" -> "a6"
        isHdr(srcTag) -> "h4"
        else -> "a4"
    }

    fun policyBlockFor(srcTag: String, policy: PackPolicy?): String? {
        if (policy == null || !policy.enabled) return null
        return policy.blocks[srcTag] ?: if (isHdr(srcTag)) policy.blocks[HDR_PREFIX] else null
    }

    fun outTagForBlock(srcTag: String, block: String): String? =
        if (isHdr(srcTag)) "h4".takeIf { block == "4x4" } else LDR_TAGS_BY_BLOCK[block]

    fun outTagFor(srcTag: String, policy: PackPolicy? = null): String =
        policyBlockFor(srcTag, policy)?.let { outTagForBlock(srcTag, it) } ?: defaultOutTagFor(srcTag)

    fun key(srcTag: String, width: Int, height: Int, hash: Long, policy: PackPolicy? = null): String =
        String.format(Locale.ROOT, "%s_%dx%d_%016x.%s", srcTag, width, height, hash, outTagFor(srcTag, policy))

    fun keyForStem(stem: String, policy: PackPolicy? = null): String =
        "$stem.${outTagFor(stem.substringBefore('_'), policy)}"

    fun outTagOf(key: String): String? =
        key.substringAfterLast('.', "").takeIf { it in BLOCK_SIZES }

    fun isKey(name: String): Boolean = outTagOf(name) != null

    fun stem(key: String): String =
        outTagOf(key)?.let { key.dropLast(it.length + 1) } ?: key

    fun blockSizeOf(key: String): Int? = outTagOf(key)?.let { BLOCK_SIZES[it] }

    fun dimensionsOf(key: String): Pair<Int, Int>? {
        val dims = key.substringAfter('_', "").substringBefore('_', "")
        val x = dims.indexOf('x')
        if (x <= 0) return null
        val w = dims.substring(0, x).toIntOrNull() ?: return null
        val h = dims.substring(x + 1).toIntOrNull() ?: return null
        return w to h
    }

    fun canonicalKey(name: String, policy: PackPolicy? = null): String? =
        if (isKey(name)) keyForStem(stem(name), policy) else null

    fun isCanonical(key: String, policy: PackPolicy? = null): Boolean =
        isKey(key) && canonicalKey(key, policy) == key
}
