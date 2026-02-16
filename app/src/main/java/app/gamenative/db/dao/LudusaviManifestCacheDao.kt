package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.LudusaviManifestCache

@Dao
interface LudusaviManifestCacheDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: LudusaviManifestCache)
    
    @Query("SELECT * FROM ludusavi_manifest_cache WHERE id = 1")
    suspend fun get(): LudusaviManifestCache?
    
    @Query("DELETE FROM ludusavi_manifest_cache")
    suspend fun clear()
}
