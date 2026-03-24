package app.gamenative.workshop

/**
 * Represents a subscribed Steam Workshop item with its metadata.
 */
data class WorkshopItem(
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val fileSizeBytes: Long,
    val manifestId: Long,
    val timeUpdated: Long,
    val fileUrl: String = "",
    val fileName: String = "",
    val previewUrl: String = "",
) {
    companion object {
        /** File extensions recognised as valid workshop content (used to skip magic-byte detection). */
        val KNOWN_EXTENSIONS = setOf(
            "gma", "vpk", "bsp", "zip", "rar", "7z",
            "bsa", "esp", "esm", "ckm", "pak", "bin",
            "txt", "cfg", "lua", "mdl", "vmt", "vtf",
            "wav", "mp3", "ogg", "png", "jpg", "jpeg",
        )
    }
}

/**
 * Wraps a subscription fetch result so callers can distinguish
 * "user has no subscriptions" from "the fetch failed (network error, timeout)".
 * When [succeeded] is false, callers should preserve existing on-disk mods
 * instead of cleaning up based on an unreliable empty list.
 */
data class WorkshopFetchResult(
    val items: List<WorkshopItem>,
    val succeeded: Boolean,
    /** True only when every page was fetched without error. */
    val isComplete: Boolean = false,
)
