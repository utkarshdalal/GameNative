package app.gamenative.utils

import android.content.Context
import android.provider.Settings

/**
 * Stable per-device Wine "computer name". [com.winlator.core.WineUtils.applyComputerName] writes it into the
 * registry of a new container, so every container of this device reports the same machine.
 */
object WineComputerNameUtils {
    private const val PREFIX = "GN_"
    private const val FALLBACK_ID = "UNKNOWN0000"

    // PREFIX + 12 characters stays within the 15 character NetBIOS limit.
    private const val ID_CHARS = 12

    @JvmStatic
    fun defaultName(context: Context): String =
        fromAndroidId(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))

    /** The context-free part of [defaultName]: "GN_" plus up to 12 characters of [androidId] (15 characters at most). */
    @JvmStatic
    fun fromAndroidId(androidId: String?): String =
        PREFIX + (androidId?.takeIf { it.isNotEmpty() } ?: FALLBACK_ID).take(ID_CHARS)
}
