package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamUnlockedBranch

@Dao
interface SteamUnlockedBranchDao {
    @Query("SELECT * FROM steam_unlocked_branch WHERE appId = :appId")
    suspend fun getSteamUnlockedBranches(appId: Int): List<SteamUnlockedBranch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(branch: SteamUnlockedBranch)

    @Query("DELETE FROM steam_unlocked_branch WHERE appId = :appId AND branchName = :branchName")
    suspend fun delete(appId: Int, branchName: String)

    @Query("DELETE FROM steam_unlocked_branch")
    suspend fun deleteAll()
}
