package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.StoreGame
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreGameDao {
    @Query("SELECT * FROM store_games ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StoreGame>>

    @Query("SELECT * FROM store_games")
    suspend fun getAll(): List<StoreGame>

    @Query("SELECT * FROM store_games WHERE app_id = :appId")
    suspend fun getByAppId(appId: Int): StoreGame?

    @Query("SELECT * FROM store_games WHERE source = :source")
    suspend fun getBySource(source: String): List<StoreGame>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<StoreGame>)

    @Update
    suspend fun update(game: StoreGame)

    @Delete
    suspend fun delete(games: List<StoreGame>)

    /**
     * Replaces [source]'s library with [owned], keeping each known game's id, install state and
     * play history. Games no longer owned are dropped unless they are installed.
     */
    @Transaction
    suspend fun replaceLibrary(source: String, owned: List<StoreGame>) {
        val existing = getBySource(source).associateBy { it.productId }
        insertAll(
            owned.map { game ->
                existing[game.productId]?.let { old ->
                    game.copy(
                        appId = old.appId,
                        isInstalled = old.isInstalled,
                        installPath = old.installPath,
                        installSize = old.installSize,
                        installedVersion = old.installedVersion,
                        latestVersion = game.latestVersion.ifEmpty { old.latestVersion },
                        unsupportedReason = game.unsupportedReason.ifEmpty { old.unsupportedReason },
                        lastPlayed = old.lastPlayed,
                    )
                } ?: game
            },
        )
        val ownedIds = owned.mapTo(HashSet()) { it.productId }
        delete(existing.values.filter { it.productId !in ownedIds && !it.isInstalled })
    }

    @Query("DELETE FROM store_games WHERE source = :source AND is_installed = 0")
    suspend fun deleteNotInstalled(source: String)
}
