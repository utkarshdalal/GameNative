package app.gamenative.data

import androidx.room.ColumnInfo

// Smallest possible stub for steam license comparisons
data class SteamLicenseStub(
    val packageId: Int,
    @ColumnInfo("last_change_number") val lastChangeNumber: Int,
    @ColumnInfo("access_token") val accessToken: Long,
)
