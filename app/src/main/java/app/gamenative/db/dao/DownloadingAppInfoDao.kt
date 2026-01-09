package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.DownloadingAppInfo

@Dao
interface DownloadingAppInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(appInfo: DownloadingAppInfo)

    @Query("SELECT * FROM downloading_app_info WHERE appId = :appId")
    suspend fun getDownloadingApp(appId: Int): DownloadingAppInfo?

    @Query("DELETE from downloading_app_info WHERE appId = :appId")
    suspend fun deleteApp(appId: Int)

    @Query("DELETE from downloading_app_info")
    suspend fun deleteAll()
}
