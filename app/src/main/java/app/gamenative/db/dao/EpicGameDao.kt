package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.EpicGame
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Epic games in the Room database
 */
@Dao
interface EpicGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: EpicGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<EpicGame>)

    @Update
    suspend fun update(game: EpicGame)

    @Delete
    suspend fun delete(game: EpicGame)

    @Query("UPDATE epic_games SET is_installed = 0, install_path='',install_size = 0 WHERE id = :appId")
    suspend fun uninstall(appId: String)

    @Query("DELETE FROM epic_games WHERE id = :appId")
    suspend fun deleteById(appId: String)

    @Query("SELECT * FROM epic_games WHERE id = :appId")
    suspend fun getById(appId: String): EpicGame?

    @Query("SELECT * FROM epic_games WHERE app_name = :appName")
    suspend fun getByAppName(appName: String): EpicGame?

    @Query("SELECT * FROM epic_games ORDER BY title ASC")
    fun getAll(): Flow<List<EpicGame>>

    @Query("SELECT * FROM epic_games ORDER BY title ASC")
    suspend fun getAllAsList(): List<EpicGame>

    @Query("SELECT * FROM epic_games WHERE is_installed = :isInstalled ORDER BY title ASC")
    fun getByInstallStatus(isInstalled: Boolean): Flow<List<EpicGame>>

    @Query("SELECT * FROM epic_games WHERE base_game_app_name = :appId")
    fun getDLCForTitle(appId: String): Flow<List<EpicGame>>

    @Query("SELECT * FROM epic_games WHERE base_game_app_name IS NOT NULL AND is_dlc = true")
    fun getAllDlcTitles(): Flow<List<EpicGame>>

    @Query("SELECT * FROM epic_games WHERE title LIKE '%' || :searchQuery || '%' ORDER BY title ASC")
    fun searchByTitle(searchQuery: String): Flow<List<EpicGame>>

    @Query("DELETE FROM epic_games")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM epic_games")
    fun getCount(): Flow<Int>

    @Transaction
    suspend fun replaceAll(games: List<EpicGame>) {
        deleteAll()
        insertAll(games)
    }

    /**
     * Upsert Epic games while preserving install status and paths
     * This is useful when refreshing the library from Epic/Legendary
     */
    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<EpicGame>) {
        games.forEach { newGame ->
            val existingGame = getById(newGame.id)
            if (existingGame != null) {
                // Preserve installation status, path, and size from existing game
                val gameToInsert = newGame.copy(
                    isInstalled = existingGame.isInstalled,
                    installPath = existingGame.installPath,
                    installSize = existingGame.installSize,
                    lastPlayed = existingGame.lastPlayed,
                    playTime = existingGame.playTime,
                )
                insert(gameToInsert)
            } else {
                // New game, insert as-is
                insert(newGame)
            }
        }
    }
}
