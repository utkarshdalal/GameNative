package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.GOGGame
import kotlinx.coroutines.flow.Flow

/**
 * DAO for GOG games in the Room database
 */
@Dao
interface GOGGameDao {

    // SQLite (and Room's expanded IN lists) bind each entry separately; Android's default bind
    // limit is 999, so chunk large hidden sets to stay well under it.
    private companion object {
        const val MAX_HIDDEN_BIND_PARAMS = 500
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: GOGGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<GOGGame>)

    @Update
    suspend fun update(game: GOGGame)

    @Delete
    suspend fun delete(game: GOGGame)

    @Query("DELETE FROM gog_games WHERE id = :gameId")
    suspend fun deleteById(gameId: String)

    @Query("SELECT * FROM gog_games WHERE id = :gameId")
    suspend fun getById(gameId: String): GOGGame?

    @Query("SELECT * FROM gog_games WHERE exclude = 0 ORDER BY title ASC")
    fun getAll(): Flow<List<GOGGame>>

    @Query("SELECT * FROM gog_games WHERE exclude = 0 ORDER BY title ASC")
    suspend fun getAllAsList(): List<GOGGame>

    @Query("SELECT * FROM gog_games WHERE is_installed = :isInstalled AND exclude = 0 ORDER BY title ASC")
    fun getByInstallStatus(isInstalled: Boolean): Flow<List<GOGGame>>

    /** Returns all installed GOG games, excluding excluded entries, sorted by title. */
    @Query("SELECT * FROM gog_games WHERE is_installed = 1 AND exclude = 0 ORDER BY title ASC")
    suspend fun getInstalledGames(): List<GOGGame>

    @Query("SELECT * FROM gog_games WHERE is_installed = 0 AND exclude = 0")
    suspend fun getNonInstalledGames(): List<GOGGame>

    @Query("SELECT * FROM gog_games WHERE exclude = 0 AND title LIKE '%' || :searchQuery || '%' ORDER BY title ASC")
    fun searchByTitle(searchQuery: String): Flow<List<GOGGame>>

    @Query("DELETE FROM gog_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    @Query("SELECT COUNT(*) FROM gog_games WHERE exclude = 0")
    fun getCount(): Flow<Int>

    @Query("SELECT id FROM gog_games")
    suspend fun getAllGameIdsIncludingExcluded(): List<String>

    @Query("SELECT id FROM gog_games WHERE exclude = 0 AND vertical_cover_url = ''")
    suspend fun getGameIdsMissingVerticalCover(): List<String>

    @Query("UPDATE gog_games SET vertical_cover_url = :url WHERE id = :gameId")
    suspend fun updateVerticalCoverUrl(gameId: String, url: String)

    /** Marks the supplied product IDs as hidden on GOG.com. */
    @Query("UPDATE gog_games SET gog_com_hidden = 1 WHERE id IN (:ids)")
    suspend fun markGogComHidden(ids: Collection<String>)

    /** Clears the GOG.com hidden flag for the supplied product IDs. */
    @Query("UPDATE gog_games SET gog_com_hidden = 0 WHERE id IN (:ids)")
    suspend fun clearGogComHidden(ids: Collection<String>)

    /** Marks the supplied product IDs as hidden in Galaxy. */
    @Query("UPDATE gog_games SET galaxy_hidden = 1 WHERE id IN (:ids)")
    suspend fun markGalaxyHidden(ids: Collection<String>)

    /** Clears the Galaxy hidden flag for the supplied product IDs. */
    @Query("UPDATE gog_games SET galaxy_hidden = 0 WHERE id IN (:ids)")
    suspend fun clearGalaxyHidden(ids: Collection<String>)

    /** Applies only the gog.com observations in a validated source snapshot. */
    @Transaction
    suspend fun reconcileGogComHidden(trueIds: Collection<String>, falseIds: Collection<String>) {
        trueIds.chunked(MAX_HIDDEN_BIND_PARAMS).forEach { chunk ->
            if (chunk.isNotEmpty()) markGogComHidden(chunk)
        }
        falseIds.chunked(MAX_HIDDEN_BIND_PARAMS).forEach { chunk ->
            if (chunk.isNotEmpty()) clearGogComHidden(chunk)
        }
    }

    /** Applies only the Galaxy observations in a validated source snapshot. */
    @Transaction
    suspend fun reconcileGalaxyHidden(trueIds: Collection<String>, falseIds: Collection<String>) {
        trueIds.chunked(MAX_HIDDEN_BIND_PARAMS).forEach { chunk ->
            if (chunk.isNotEmpty()) markGalaxyHidden(chunk)
        }
        falseIds.chunked(MAX_HIDDEN_BIND_PARAMS).forEach { chunk ->
            if (chunk.isNotEmpty()) clearGalaxyHidden(chunk)
        }
    }

    /** Resets both authoritative hidden sources for retained rows during account cleanup. */
    @Query("UPDATE gog_games SET gog_com_hidden = 0, galaxy_hidden = 0")
    suspend fun clearHiddenSourceFlags()

    /**
     * Upserts GOG games while preserving local install state, play history, and hidden sources.
     * When updating an existing row, a nonblank incoming cover replaces the stored cover; blank
     * incoming cover data preserves the existing value.
     */
    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<GOGGame>) {
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
                    verticalCoverUrl = newGame.verticalCoverUrl.ifBlank { existingGame.verticalCoverUrl },
                    gogComHidden = existingGame.gogComHidden,
                    galaxyHidden = existingGame.galaxyHidden,
                )
                insert(gameToInsert)
            } else {
                // New game, insert as-is
                insert(newGame)
            }
        }
    }
}
