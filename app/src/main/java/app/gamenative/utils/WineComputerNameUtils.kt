package app.gamenative.utils

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Stable per-device Wine "computer name". [com.winlator.core.WineUtils.applyComputerName] writes it into the
 * registry of a container, so every container of this device reports the same machine.
 *
 * The name is derived from ANDROID_ID through a hash. Programs in the container can read the computer name, so
 * the raw ID characters are not used. There is deliberately no fixed prefix: a name that says "GameNative"
 * would be a new fingerprint for every online service that reads it.
 */
object WineComputerNameUtils {
    private const val FALLBACK_ID = "UNKNOWN0000"

    // 15 characters is the NetBIOS limit.
    private const val HASH_CHARS = 15

    @JvmStatic
    fun defaultName(context: Context): String =
        fromAndroidId(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))

    /**
     * The context-free part of [defaultName]: the first 15 hex characters of the SHA-256 of [androidId]. A null or
     * empty [androidId] gives the same fixed name on every such device.
     */
    @JvmStatic
    fun fromAndroidId(androidId: String?): String {
        val id = androidId?.takeIf { it.isNotEmpty() } ?: FALLBACK_ID
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(HASH_CHARS)
    }
}
