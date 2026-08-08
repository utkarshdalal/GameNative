package app.gamenative.html5

// JSON-dir addressing for html5-containers/<slug>/. NOT used by save-sync origin (collapsed to
// "app.local"). on-disk slug shape is load-bearing -- changing it orphans existing containers.
object Html5SlugUtil {

    private const val MAX_JSON_DIR_NAME = 24
    private const val HASH_HEX_CHARS = 4

    fun slug(folderName: String, stableGameId: Int): String {
        val base = folderName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_JSON_DIR_NAME)
            .ifEmpty { "game" }
        val hash = fnv1a32(stableGameId.toString().toByteArray())
            .toUInt()
            .toString(16)
            .padStart(HASH_HEX_CHARS, '0')
            .take(HASH_HEX_CHARS)
        return "$base-$hash"
    }

    private fun fnv1a32(bytes: ByteArray): Int {
        var hash = 0x811c9dc5.toInt()
        for (b in bytes) {
            hash = hash xor (b.toInt() and 0xff)
            hash *= 0x01000193
        }
        return hash
    }
}
