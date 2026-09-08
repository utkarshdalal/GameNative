package app.gamenative.data

import androidx.room.ColumnInfo

// Slimmed down type for retrieving licences for processing.
data class SteamLicenseForPics(
    val packageId: Int,
    @ColumnInfo("access_token")
    val accessToken: Long,
)
