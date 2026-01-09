package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gamenative.data.AppInfo
import app.gamenative.data.DepotInfo

@Dao
interface AppInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(appInfo: AppInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(appInfos: List<AppInfo>)

    @Update
    suspend fun update(appInfo: AppInfo)

    @Query("SELECT * FROM app_info WHERE id = :appId")
    suspend fun getInstalledApp(appId: Int): AppInfo?

    @Query("SELECT * FROM app_info WHERE id = :appId")
    suspend fun get(appId: Int): AppInfo?

    @Query("DELETE from app_info WHERE id = :appId")
    suspend fun deleteApp(appId: Int)

    @Query("DELETE from app_info")
    suspend fun deleteAll()
}
