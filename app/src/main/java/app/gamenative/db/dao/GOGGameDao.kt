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

    @Query(
        "SELECT id FROM gog_games WHERE exclude = 0 AND " +
            "(vertical_cover_url = '' OR developer = '' OR release_date IN ('', 'null'))",
    )
    suspend fun getGameIdsMissingGamesDbInfo(): List<String>

    /**
     * Sets each of the given values only where the stored one is empty and the given one is not.
     * A row that had no developer has only the store's listing date, so its date is replaced too.
     */
    @Query(
        "UPDATE gog_games SET " +
            "vertical_cover_url = CASE WHEN vertical_cover_url = '' AND :url != '' THEN :url ELSE vertical_cover_url END, " +
            "developer = CASE WHEN developer = '' AND :developer != '' THEN :developer ELSE developer END, " +
            "publisher = CASE WHEN publisher = '' AND :publisher != '' THEN :publisher ELSE publisher END, " +
            "release_date = CASE WHEN :releaseDate != '' AND (release_date IN ('', 'null') OR developer = '') THEN :releaseDate ELSE release_date END " +
            "WHERE id = :gameId",
    )
    suspend fun fillGamesDbInfo(gameId: String, url: String, developer: String, publisher: String, releaseDate: String)

    @Transaction
    suspend fun updateInstalledDlcIds(gameId: String, dlcIds: Collection<String>) {
        val game = getById(gameId) ?: return
        val merged = (game.installedDlcIds + dlcIds).distinct()
        if (merged != game.installedDlcIds) update(game.copy(installedDlcIds = merged))
    }

    @Transaction
    suspend fun setInstalledDlcIds(gameId: String, dlcIds: Collection<String>) {
        val game = getById(gameId) ?: return
        val ids = dlcIds.distinct()
        if (ids != game.installedDlcIds) update(game.copy(installedDlcIds = ids))
    }

    @Query("UPDATE gog_games SET is_installed = :isInstalled, install_path = :installPath, install_size = :installSize WHERE id = :gameId")
    suspend fun updateInstallState(gameId: String, isInstalled: Boolean, installPath: String, installSize: Long)

    /** Install size from the store build, kept only while the game is not installed (an install records its folder size). */
    @Query("UPDATE gog_games SET install_size = :installSize WHERE id = :gameId AND is_installed = 0")
    suspend fun updateStoreInstallSize(gameId: String, installSize: Long)

    /** Clears the hidden flag on every GOG row (used before applying a fresh hidden set). */
    @Query("UPDATE gog_games SET hidden = 0")
    suspend fun clearHiddenFlags()

    /** Marks the given GOG product IDs as hidden. */
    @Query("UPDATE gog_games SET hidden = 1 WHERE id IN (:hiddenIds)")
    suspend fun markHidden(hiddenIds: Collection<String>)

    /**
     * Replaces the stored hidden state with [hiddenIds]: every GOG row is cleared first, then the
     * listed product IDs are marked hidden. Large sets are applied in chunks to stay under SQLite's
     * bind-variable limit.
     */
    @Transaction
    suspend fun applyHiddenFlags(hiddenIds: Collection<String>) {
        clearHiddenFlags()
        hiddenIds.chunked(MAX_HIDDEN_BIND_PARAMS).forEach { chunk ->
            markHidden(chunk)
        }
    }

    /**
     * Upserts GOG games while preserving local install state, play history, and hidden state.
     * When updating an existing row, a nonblank incoming cover, developer, publisher or release
     * date replaces the stored one; blank incoming data preserves the existing value.
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
                    developer = newGame.developer.ifBlank { existingGame.developer },
                    publisher = newGame.publisher.ifBlank { existingGame.publisher },
                    releaseDate = newGame.releaseDate.takeUnless { it.isBlank() || it == "null" } ?: existingGame.releaseDate,
                    hidden = existingGame.hidden,
                    installedDlcIds = existingGame.installedDlcIds,
                )
                insert(gameToInsert)
            } else {
                // New game, insert as-is
                insert(newGame)
            }
        }
    }
}
