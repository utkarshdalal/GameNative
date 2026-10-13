package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_play_history")
data class LibraryPlayHistory(
    @PrimaryKey
    @ColumnInfo("app_id")
    val appId: String,

    @ColumnInfo(name = "last_played", defaultValue = "0")
    val lastPlayed: Long = 0L,
)
