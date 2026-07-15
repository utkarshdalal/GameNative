package app.gamenative.data

import androidx.room.ColumnInfo

// Slimmed-down projection of the columns the PICS collectors actually read when
// deciding whether an app needs (re)processing. Avoids pulling the full SteamApp
// row — including the large depots/config/UFS JSON blobs — into memory just to
// compare change numbers for tens of thousands of apps.
data class SteamAppPicsMeta(
    val id: Int,
    @ColumnInfo("package_id")
    val packageId: Int,
    @ColumnInfo("last_change_number")
    val lastChangeNumber: Int,
    @ColumnInfo("ufs_parse_version")
    val ufsParseVersion: Int,
)
