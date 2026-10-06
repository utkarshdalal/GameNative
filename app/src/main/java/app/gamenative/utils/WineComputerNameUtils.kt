package app.gamenative.utils

import android.content.Context
import android.provider.Settings

/**
 * Single source of truth for the Wine "computer name" default (no custom global or
 * per-container value set). Kept separate from WineUtils.java so both the Kotlin settings
 * UI (to display/pre-fill it) and the Java registry-writing code can use the same value.
 */
object WineComputerNameUtils {
    @JvmStatic
    fun defaultName(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotEmpty() } ?: "UNKNOWN0000"
        return "GN_" + androidId.take(12)
    }
}
