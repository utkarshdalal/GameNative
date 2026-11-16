package app.gamenative.service.ubisoft

import java.security.MessageDigest

/**
 * The on-disk shape of a Ubisoft Connect save slot: `<N>.save` with a header whose bytes 8..40 hold
 * the hash of the cloud version the file is based on (32 hex characters), or zeros when it was never
 * synced. Header lengths differ between library generations; only the first 40 bytes are relied on.
 */
object UbisoftSaveFile {
    const val EXTENSION = ".save"
    const val UPLOAD_MARKER = ".upload"
    const val DELETE_MARKER = ".delete"
    private const val HASH_START = 8
    private const val HASH_END = 40
    private val SLOT_NAME = Regex("([1-9][0-9]{0,8})\\.save")
    private val HEX32 = Regex("[0-9A-Fa-f]{32}")

    fun slotOf(name: String): Int? = SLOT_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    fun fileName(slot: Int): String = "$slot$EXTENSION"

    /** MD5 of the content with the stamped hash zeroed, so a stamp alone never reads as a change. */
    fun contentKey(bytes: ByteArray): String {
        val md5 = MessageDigest.getInstance("MD5")
        if (bytes.size < HASH_END) {
            md5.update(bytes)
        } else {
            md5.update(bytes, 0, HASH_START)
            md5.update(ByteArray(HASH_END - HASH_START))
            md5.update(bytes, HASH_END, bytes.size - HASH_END)
        }
        return hex(md5.digest())
    }

    fun stampedHash(bytes: ByteArray): String? {
        if (bytes.size < HASH_END) return null
        val text = String(bytes, HASH_START, HASH_END - HASH_START, Charsets.ISO_8859_1)
        return text.takeIf { HEX32.matches(it) }?.lowercase()
    }

    fun stamp(bytes: ByteArray, hash: String): ByteArray {
        require(bytes.size >= HASH_END) { "save file too short to stamp" }
        require(HEX32.matches(hash)) { "stamp hash must be 32 hex characters" }
        val out = bytes.copyOf()
        hash.lowercase().toByteArray(Charsets.ISO_8859_1).copyInto(out, HASH_START)
        return out
    }

    fun canStamp(bytes: ByteArray): Boolean = bytes.size >= HASH_END

    fun newHash(bytes: ByteArray): String = contentKey(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
