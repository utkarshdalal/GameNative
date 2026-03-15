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
)
