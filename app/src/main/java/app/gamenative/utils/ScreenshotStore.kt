package app.gamenative.utils

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One screenshot on disk plus its capture time (file last-modified). */
data class ScreenshotItem(val file: File, val dateTakenMillis: Long)

/**
 * Pure file-model for per-game screenshots, no Android deps so it's unit-testable.
 * Layout: <root>/<appId>/<gameName>_<date>.png
 */
object ScreenshotStore {
    const val EXTENSION = "png"
    private const val INTERNAL_DIR_NAME = "screenshots"

    /** Date stamp baked into filenames; filesystem-safe (no ':') and human-readable. */
    private const val DATE_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    /** Characters not allowed in the game-name portion of a filename are collapsed to '_'. */
    private val ILLEGAL_FILENAME_CHARS = Regex("""[^A-Za-z0-9 ._-]""")

    /** Internal app dir by default; the user-picked external folder when configured and non-blank. */
    fun resolveRoot(internalFilesDir: File, useExternal: Boolean, externalPath: String): File =
        if (useExternal && externalPath.isNotBlank()) {
            File(externalPath)
        } else {
            File(internalFilesDir, INTERNAL_DIR_NAME)
        }

    /** Per-game directory. [appId] is reduced to one safe path segment so it can't escape [root]. */
    fun gameDir(root: File, appId: String): File = File(root, sanitizePathSegment(appId))

    /** Collapse anything that isn't a safe filename character to '_', guarding against path traversal. */
    private fun sanitizePathSegment(segment: String): String =
        segment.trim().replace(ILLEGAL_FILENAME_CHARS, "_").trim('_', ' ', '.').ifBlank { "unknown" }

    /** Strip filesystem-unsafe characters from a game name, falling back to "Screenshot" if nothing usable remains. */
    fun sanitizeGameName(gameName: String): String =
        gameName.trim().replace(ILLEGAL_FILENAME_CHARS, "_").trim('_', ' ', '.').ifBlank { "Screenshot" }

    /**
     * Filename for a capture: "<gameName>_<date>.png".
     * [sequence] > 0 appends "-<sequence>" to avoid same-second collisions.
     */
    fun fileNameFor(gameName: String, epochMillis: Long, sequence: Int = 0): String {
        val date = SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date(epochMillis))
        val base = "${sanitizeGameName(gameName)}_$date"
        return if (sequence > 0) "$base-$sequence.$EXTENSION" else "$base.$EXTENSION"
    }

    /**
     * A name for [displayName] free per the [exists] predicate; appends " (n)" before the extension
     * until free. Pure (caller supplies [exists]) so it's unit-testable.
     */
    fun uniqueDownloadName(displayName: String, exists: (String) -> Boolean): String {
        if (!exists(displayName)) return displayName
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var seq = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($seq)" else "$base ($seq).$ext"
            if (!exists(candidate)) return candidate
            seq++
        }
    }

    /** All screenshots for a game, newest first. Empty if the directory is absent. */
    fun list(root: File, appId: String): List<ScreenshotItem> {
        val dir = gameDir(root, appId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".$EXTENSION", ignoreCase = true) }
            .map { ScreenshotItem(it, it.lastModified()) }
            // Newest first; tie-break on filename for a stable order on same-millisecond captures.
            .sortedWith(
                compareByDescending<ScreenshotItem> { it.dateTakenMillis }
                    .thenByDescending { it.file.name },
            )
    }
}
