package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("downloading_app_info")
data class DownloadingAppInfo (
    @PrimaryKey
    val appId: Int,

    @ColumnInfo("dlcAppIds")
    val dlcAppIds: List<Int> = emptyList<Int>(),

    @ColumnInfo("branch", defaultValue = "public")
    val branch: String = "public",

    /**
     * The [app.gamenative.service.download.SteamDownloadMode] this run was started with, so an
     * interrupted UPDATE or VERIFY resumes as itself: resuming either one as INSTALL would skip the
     * depots it exists to re-check, and a resumed VERIFY would resolve current branch manifests
     * instead of the installed ones. Stored by name; unknown values fall back to UPDATE.
     */
    @ColumnInfo("mode", defaultValue = "UPDATE")
    val mode: String = "UPDATE",
)
