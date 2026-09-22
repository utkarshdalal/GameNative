package app.gamenative.texturepack

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gamenative.PrefManager
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class TexturePackDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appId = inputData.getString(KEY_APP_ID) ?: return Result.failure()
        val fingerprint = inputData.getString(KEY_FINGERPRINT) ?: return Result.failure()
        if (!PrefManager.texturePackEnabled) return Result.success()
        val client = TexturePackClient(applicationContext)
        val cacheDir = TexturePackPaths.cacheDirForApp(applicationContext, appId) ?: return Result.failure()
        return try {
            val entries = client.pack(fingerprint).entries
            var pending = 0
            for (entry in entries) {
                if (TextureCacheStore.hasEntry(cacheDir, entry.key)) continue
                val payload = client.entry(entry.key)
                if (payload == null || !TextureCacheStore.writeEntryAtomic(cacheDir, entry.key, payload)) {
                    pending++
                }
            }
            if (pending > 0) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "texture pack background download for $appId failed")
            Result.retry()
        }
    }

    companion object {
        private const val KEY_APP_ID = "appId"
        private const val KEY_FINGERPRINT = "fingerprint"

        fun enqueue(context: Context, appId: String, fingerprint: String) {
            val networkType = if (PrefManager.texturePackAllowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<TexturePackDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setInputData(
                    Data.Builder()
                        .putString(KEY_APP_ID, appId)
                        .putString(KEY_FINGERPRINT, fingerprint)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("texture-pack-$fingerprint", ExistingWorkPolicy.REPLACE, request)
            }.onFailure { Timber.w(it, "could not enqueue texture pack download") }
        }
    }
}
