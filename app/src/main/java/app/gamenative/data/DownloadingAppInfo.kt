package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("downloading_app_info")
data class DownloadingAppInfo (
    @PrimaryKey
    val appId: Int,

    @ColumnInfo("dlcAppIds")
    val dlcAppIds: List<Int> = emptyList<Int>()
){}
