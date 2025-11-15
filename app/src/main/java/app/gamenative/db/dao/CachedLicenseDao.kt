package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.CachedLicense

@Dao
interface CachedLicenseDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(license: CachedLicense)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(licenses: List<CachedLicense>)
    
    @Query("SELECT * FROM cached_license ORDER BY id")
    suspend fun getAll(): List<CachedLicense>
    
    @Query("DELETE FROM cached_license")
    suspend fun deleteAll()
}

