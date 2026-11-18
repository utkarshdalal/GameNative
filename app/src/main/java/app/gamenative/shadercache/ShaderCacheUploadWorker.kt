package app.gamenative.shadercache

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.gamenative.service.cloud.CloseSyncTracker
import com.winlator.container.ContainerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Uploads a container's recorded shader sessions as scheduled work, so the upload still runs when the app goes to
 * the background as the game exits (a game started from a frontend returns there) and Android stops the process from
 * running. Tried again later when the network or the server did not take everything.
 */
class ShaderCacheUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val containerId = inputData.getString(KEY_CONTAINER) ?: return@withContext Result.success()
        // The upload reads the container's files; an uninstall of the game waits for it.
        inputData.getString(KEY_CLOSE_SYNC)?.let { CloseSyncTracker.track(it, coroutineContext.job) }
        try {
            val container = ContainerManager(applicationContext).getContainerById(containerId)
                ?: return@withContext Result.success()
            when {
                FossilizeManager.uploadPending(applicationContext, container) -> Result.success()
                runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Shader cache upload failed")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Fossilize"
        private const val KEY_CONTAINER = "container"
        private const val KEY_CLOSE_SYNC = "closeSync"
        private const val MAX_ATTEMPTS = 4

        /** Schedules the upload for [containerId]; one that is already running for it is followed by another. */
        fun enqueue(context: Context, containerId: String, closeSyncKey: String?) {
            val request = OneTimeWorkRequestBuilder<ShaderCacheUploadWorker>()
                .setInputData(workDataOf(KEY_CONTAINER to containerId, KEY_CLOSE_SYNC to closeSyncKey))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply {
                    // Started at once where that needs no notification.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("shader-cache-upload-$containerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
