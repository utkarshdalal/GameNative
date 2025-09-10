package app.gamenative.service.GOG

import android.content.Context
import app.gamenative.db.dao.GOGGameDao
import javax.inject.Inject
import kotlinx.coroutines.*
import timber.log.Timber

class GOGLibraryManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track if background sync is already running
    private var backgroundSyncInProgress = false

    /**
     * Start background library sync that progressively syncs games in batches
     * Returns a Result indicating whether the sync was started successfully
     */
    suspend fun startBackgroundSync(context: Context, clearExisting: Boolean = false): Result<Unit> {
        if (backgroundSyncInProgress) {
            Timber.i("Background GOG sync already in progress, skipping")
            return Result.failure(Exception("Background sync already in progress"))
        }

        // Validate credentials before starting background sync
        return try {
            if (!GOGService.hasStoredCredentials(context)) {
                Timber.w("No GOG credentials found, cannot start background sync")
                return Result.failure(Exception("No GOG credentials found. Please log in first."))
            }

            val validationResult = GOGService.validateCredentials(context)
            if (validationResult.isFailure || !validationResult.getOrThrow()) {
                Timber.w("GOG credentials validation failed, cannot start background sync")
                return Result.failure(Exception("GOG credentials validation failed. Please log in again."))
            }

            scope.launch {
                backgroundSyncInProgress = true
                syncLibraryInBackground(context, clearExisting)
                backgroundSyncInProgress = false
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start background sync")
            Result.failure(e)
        }
    }

    /**
     * Clear all GOG games from the database
     */
    suspend fun clearLibrary(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.i("Clearing GOG library from database")
            gogGameDao.deleteAll()
            Timber.i("GOG library cleared successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear GOG library")
            Result.failure(e)
        }
    }

    /**
     * Background sync implementation with true progressive syncing
     * Games appear in the library as soon as they're fetched from GOG API
     */
    private suspend fun syncLibraryInBackground(context: Context, clearExisting: Boolean = false) {
        try {
            Timber.i("Starting progressive background GOG library sync...")

            val authConfigPath = "${context.filesDir}/gog_auth.json"

            // Clear existing games if requested
            if (clearExisting) {
                Timber.i("Clearing existing GOG games before sync")
                clearLibrary()
            }

            // Try progressive sync first (if available), fallback to batch sync
            syncLibraryProgressively(context, authConfigPath)
        } catch (e: Exception) {
            Timber.e(e, "Exception during background GOG sync")
        }
    }

    /**
     * Progressive sync method
     * Insert games one by one as they are fetched
     */
    private suspend fun syncLibraryProgressively(context: Context, authConfigPath: String): Result<Unit> {
        return try {
            Timber.i("Starting progressive GOG library sync...")

            // Validate credentials before making GOGDL calls
            val validationResult = GOGService.validateCredentials(context)
            if (validationResult.isFailure || !validationResult.getOrThrow()) {
                Timber.w("GOG credentials validation failed, aborting progressive sync")
                return Result.failure(Exception("GOG credentials validation failed"))
            }

            // Use the new progressive method that inserts games one by one
            val libraryResult = GOGService.getUserLibraryProgressively(
                context,
                onGameFetched = { game ->
                    // Insert each game immediately as it's fetched
                    // All database operations are already in the same coroutine context
                    try {
                        val existingGame = gogGameDao.getById(game.id)
                        val gameToInsert = if (existingGame != null) {
                            game.copy(isInstalled = existingGame.isInstalled, installPath = existingGame.installPath)
                        } else {
                            game
                        }
                        gogGameDao.insert(gameToInsert)

                        Timber.d("Inserted game: ${game.title}")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to insert game: ${game.title}")
                    }
                },
                onTotalCount = { totalCount ->
                    Timber.d("Total games to sync: $totalCount")
                },
            )

            if (libraryResult.isSuccess) {
                val totalGames = libraryResult.getOrThrow()
                Timber.i("Progressive GOG library sync completed successfully: $totalGames games")
                Result.success(Unit)
            } else {
                val error = libraryResult.exceptionOrNull()
                Timber.e("Failed to get library from GOG API: ${error?.message}")
                Result.failure(error ?: Exception("Failed to get library"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during progressive sync")
            Result.failure(e)
        }
    }

    /**
     * Get the count of games in the local database
     */
    suspend fun getLocalGameCount(): Int = withContext(Dispatchers.IO) {
        try {
            gogGameDao.getAllAsList().size
        } catch (e: Exception) {
            Timber.e(e, "Failed to get local GOG game count")
            0
        }
    }
}
