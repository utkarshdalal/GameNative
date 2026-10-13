package app.gamenative.utils

/**
 * Seals the refresh token handed to the Real Steam host. The key is this app's own
 * package name; the host reproduces it from the prefix path the OS gave the app.
 */
object SteamHostAuth {
    private fun fnv1a64(s: String): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L // 0x100000001b3
        }
        return h
    }

    fun seal(packageName: String, token: String): String {
        var x = fnv1a64(packageName) or 1L
        val sb = StringBuilder()
        for (b in token.toByteArray(Charsets.UTF_8)) {
            x = x xor (x ushr 12)
            x = x xor (x shl 25)
            x = x xor (x ushr 27)
            val k = ((x * 2685821657736338717L) ushr 56).toInt() // 0x2545F4914F6CDD1D
            sb.append("%02x".format((b.toInt() and 0xff) xor k))
        }
        return sb.toString()
    }
}
