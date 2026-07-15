package app.gamenative.data

import androidx.room.ColumnInfo

// Slimmed-down projection of the columns the PICS collectors actually read
data class SteamAppPicsMeta(
    val id: Int,
    @ColumnInfo("package_id")
    val packageId: Int,
    @ColumnInfo("last_change_number")
    val lastChangeNumber: Int,
    @ColumnInfo("ufs_parse_version")
    val ufsParseVersion: Int,
)
