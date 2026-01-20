package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("app_info")
data class AppInfo(
    @PrimaryKey val id: Int,
    @ColumnInfo("is_downloaded")
    val isDownloaded: Boolean = false,
    @ColumnInfo("downloaded_depots")
    val downloadedDepots: List<Int> = emptyList<Int>(),
    @ColumnInfo("dlc_depots")
    val dlcDepots: List<Int> = emptyList<Int>(),
)
