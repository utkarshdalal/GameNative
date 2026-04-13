package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-library-item metadata owned by the user.
 *
 * This is intentionally source-agnostic so favorites, tags, and last-played
 * timestamps can work across Steam, GOG, Epic, Amazon, and custom games.
 */
@Entity(tableName = "library_metadata")
data class LibraryMetadata(
    @PrimaryKey
    @ColumnInfo(name = "app_id")
    val appId: String,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long = 0L,
)
