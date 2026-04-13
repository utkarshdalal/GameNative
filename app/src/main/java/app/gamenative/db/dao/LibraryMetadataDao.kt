package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.LibraryMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryMetadataDao {

    @Query("SELECT * FROM library_metadata")
    fun getAll(): Flow<List<LibraryMetadata>>

    @Query("SELECT * FROM library_metadata WHERE app_id = :appId")
    suspend fun getByAppId(appId: String): LibraryMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: LibraryMetadata)

    @Query("UPDATE library_metadata SET is_favorite = :isFavorite WHERE app_id = :appId")
    suspend fun setFavorite(appId: String, isFavorite: Boolean)

    @Query("UPDATE library_metadata SET tags = :tags WHERE app_id = :appId")
    suspend fun setTags(appId: String, tags: List<String>)

    @Query("UPDATE library_metadata SET last_played_at = :lastPlayedAt WHERE app_id = :appId")
    suspend fun setLastPlayedAt(appId: String, lastPlayedAt: Long)

    @Query("DELETE FROM library_metadata WHERE app_id = :appId")
    suspend fun delete(appId: String)
}
