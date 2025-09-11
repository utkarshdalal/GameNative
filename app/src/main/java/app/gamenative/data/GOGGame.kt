package app.gamenative.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gog_games")
data class GOGGame(
    @PrimaryKey
    val id: String,
    val title: String,
    val slug: String,
    val downloadSize: Long = 0,
    val installSize: Long = 0,
    val isInstalled: Boolean = false,
    val installPath: String = "",
    val imageUrl: String = "",
    val iconUrl: String = "",
    val description: String = "",
    val releaseDate: String = "",
    val developer: String = "",
    val publisher: String = "",
    val genres: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val lastPlayed: Long = 0,
    val playTime: Long = 0,
)

data class GOGCredentials(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
)

data class GOGDownloadInfo(
    val gameId: String,
    val totalSize: Long,
    val downloadedSize: Long = 0,
    val progress: Float = 0f,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
)
