package app.gamenative.utils

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Stable per-device Wine "computer name". [com.winlator.core.WineUtils.applyComputerName] writes it into the
 * registry of a new container, so every container of this device reports the same machine.
 *
 * The name is derived from ANDROID_ID through a hash. Programs in the container can read the computer name, so
 * the raw ID characters are not used.
 */
object WineComputerNameUtils {
    private const val PREFIX = "GN_"
    private const val FALLBACK_ID = "UNKNOWN0000"

    // PREFIX + 12 characters stays within the 15 character NetBIOS limit.
    private const val HASH_CHARS = 12

    @JvmStatic
    fun defaultName(context: Context): String =
        fromAndroidId(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))

    /**
     * The context-free part of [defaultName]: "GN_" plus the first 12 hex characters of the SHA-256 of [androidId]
     * (15 characters). A null or empty [androidId] gives the same fixed name on every such device.
     */
    @JvmStatic
    fun fromAndroidId(androidId: String?): String {
        val id = androidId?.takeIf { it.isNotEmpty() } ?: FALLBACK_ID
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return PREFIX + hex.take(HASH_CHARS)
    }
}
