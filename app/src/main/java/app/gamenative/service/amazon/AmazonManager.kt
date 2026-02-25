package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.db.dao.AmazonGameDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Amazon library manager and DB bridge. */
@Singleton
class AmazonManager @Inject constructor(
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) {

    /** Refresh the Amazon library from API and persist it in DB. */
    suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        Timber.i("[Amazon] Starting library refresh…")

        val credentialsResult = AmazonAuthManager.getStoredCredentials(context)
        if (credentialsResult.isFailure) {
            Timber.w("[Amazon] No stored credentials — ${credentialsResult.exceptionOrNull()?.message}")
            return@withContext
        }
        val credentials = credentialsResult.getOrNull()!!

        val games = AmazonApiClient.getEntitlements(
            bearerToken = credentials.accessToken,
            deviceSerial = credentials.deviceSerial,
        )

        if (games.isEmpty()) {
            Timber.w("[Amazon] No entitlements returned from API")
            return@withContext
        }

        amazonGameDao.upsertPreservingInstallStatus(games)
        Timber.i("[Amazon] Library refresh complete — ${games.size} game(s) in DB")
    }

    /** Look up a game by product ID. */
    suspend fun getGameById(productId: String): AmazonGame? = withContext(Dispatchers.IO) {
        amazonGameDao.getByProductId(productId)
    }

    /** Return all Amazon games from DB. */
    suspend fun getAllGames(): List<AmazonGame> = withContext(Dispatchers.IO) {
        amazonGameDao.getAllAsList()
    }

    /** Mark a game as installed and persist install metadata. */
    suspend fun markInstalled(productId: String, installPath: String, installSize: Long, versionId: String = "") =
        withContext(Dispatchers.IO) {
            amazonGameDao.markAsInstalled(productId, installPath, installSize, versionId)
            Timber.i("[Amazon] Marked installed: $productId at $installPath (${installSize}B, version=$versionId)")
        }

    /** Mark a game as not installed. */
    suspend fun markUninstalled(productId: String) = withContext(Dispatchers.IO) {
        amazonGameDao.markAsUninstalled(productId)
        Timber.i("[Amazon] Marked uninstalled: $productId")
    }

    /** Update cached download size for a game. */
    suspend fun updateDownloadSize(productId: String, size: Long) = withContext(Dispatchers.IO) {
        amazonGameDao.updateDownloadSize(productId, size)
        Timber.i("[Amazon] Updated download size for $productId: $size bytes")
    }

    /** Get the stored bearer token. */
    suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        AmazonAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
    }

    /** Delete all non-installed Amazon games on logout. */
    suspend fun deleteAllNonInstalledGames() = withContext(Dispatchers.IO) {
        amazonGameDao.deleteAllNonInstalledGames()
        Timber.i("[Amazon] Deleted all non-installed games from DB")
    }
}
