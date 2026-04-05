package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "steam_unlocked_branch",
    primaryKeys = ["appId", "branchName"],
)
data class SteamUnlockedBranch(
    val appId: Int,
    @ColumnInfo("branchName")
    val branchName: String,
    @ColumnInfo("password")
    val password: String,
)
