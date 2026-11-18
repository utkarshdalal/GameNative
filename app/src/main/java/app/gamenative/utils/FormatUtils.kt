package app.gamenative.utils

import java.util.Locale

/**
 * Formats byte counts for display. Uses SI units (1000 base) to match
 * android.text.format.Formatter.formatFileSize (downloads screen) and the
 * decimal sizes reported by the stores — every byte display should go
 * through this one helper so all screens agree.
 */
object FormatUtils {

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        val kb = 1000.0
        val mb = kb * 1000
        val gb = mb * 1000
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
