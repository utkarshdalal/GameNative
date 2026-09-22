package app.gamenative.texturepack

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

class TexturePackSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PrefManager.texturePackEnabled) return Result.success()
        val appId = inputData.getString(KEY_APP_ID)
        if (appId.isNullOrBlank()) return sweep()
        if (!TexturePackGate.needsTexturePack(applicationContext)) return Result.success()

        val container = runCatching { ContainerUtils.getContainer(applicationContext, appId) }.getOrNull()
            ?: return Result.success()
        val cacheDir = TexturePackPaths.cacheDir(container)
        if (!cacheDir.isDirectory) return Result.success()

        val client = TexturePackClient(applicationContext)
        val upload = inputData.getBoolean(KEY_UPLOAD, true)
        return try {
            if (upload) uploadSync(client, container, appId, cacheDir)
            downloadSync(client, container, appId, cacheDir)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TexturePackNetworkUnavailable) {
            Timber.i("texture pack sync for $appId deferred: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            Timber.w(e, "texture pack sync for $appId failed")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun sweep(): Result {
        runCatching {
            ContainerManager(applicationContext).containers.forEach { container ->
                if (container.getExtra(TexturePackGate.CONTAINER_EXTRA_PLATFORM, "").isNotBlank()) {
                    enqueueFullSync(applicationContext, container.id)
                }
            }
        }.onFailure { Timber.w(it, "texture pack sweep failed") }
        return Result.success()
    }

    private suspend fun uploadSync(client: TexturePackClient, container: Container, appId: String, cacheDir: File) {
        val overCap = withContext(Dispatchers.IO) {
            TexturePackSync.overCapSources(TexturePackSync.sourceFiles(cacheDir))
        }
        if (overCap.isNotEmpty()) {
            val bytes = overCap.sumOf { it.length() }
            Timber.i("texture pack: dropping ${overCap.size} pending sources ($bytes bytes) for $appId over the cap")
            withContext(Dispatchers.IO) { overCap.forEach { it.delete() } }
        }

        val sources = withContext(Dispatchers.IO) { TexturePackSync.sourceFiles(cacheDir) }
        if (sources.isNotEmpty()) {
            val byKey = sources.associateBy { TexturePackSync.keyOf(it) }
            for (batch in byKey.keys.chunked(TexturePackSync.LOOKUP_BATCH_SIZE)) {
                val response = TexturePackSync.withRetry { client.lookup(batch) }
                if (response.invalid.isNotEmpty()) {
                    Timber.w("texture pack: server rejected ${response.invalid.size} keys for $appId")
                }
                val uploaded = TexturePackSync.uploadSources(client, response.want.mapNotNull { byKey[it] })
                val settled = TexturePackSync.settledKeys(response, uploaded)
                withContext(Dispatchers.IO) {
                    settled.forEach { key -> byKey[key]?.delete() }
                }
            }
        }

        val fingerprint = register(client, container, appId, TexturePackSync.packKeys(cacheDir))
        if (fingerprint.isNotBlank()) {
            container.putExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, fingerprint)
            withContext(Dispatchers.IO) { container.saveData() }
        }
    }

    private suspend fun downloadSync(client: TexturePackClient, container: Container, appId: String, cacheDir: File) {
        var fingerprint = container.getExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, "")
        if (fingerprint.isBlank()) {
            fingerprint = register(client, container, appId, emptyList())
            if (fingerprint.isBlank()) return
            container.putExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, fingerprint)
            withContext(Dispatchers.IO) { container.saveData() }
        }
        val pack = TexturePackSync.withRetry { client.pack(fingerprint) }
        val keys = withContext(Dispatchers.IO) {
            TexturePackSync.downloadKeys(cacheDir, pack.entries, pack.pendingKeys)
        }
        if (keys.isEmpty()) return
        val gate = Semaphore(TexturePackSync.DOWNLOAD_PARALLELISM)
        coroutineScope {
            keys.map { key ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            val payload = TexturePackSync.withRetry { client.entry(key) } ?: return@withPermit
                            if (TextureCacheStore.writeEntryAtomic(cacheDir, key, payload)) {
                                TexturePackSync.sourceFile(cacheDir, key).delete()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "texture pack entry $key failed")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun register(client: TexturePackClient, container: Container, appId: String, keys: List<String>): String {
        val platform = container.getExtra(TexturePackGate.CONTAINER_EXTRA_PLATFORM, "")
        val storeId = container.getExtra(TexturePackGate.CONTAINER_EXTRA_STORE_ID, "")
        val installDir = container.getExtra(TexturePackGate.CONTAINER_EXTRA_INSTALL_DIR, "")
        if (platform.isBlank() || installDir.isBlank()) return ""
        val files = withContext(Dispatchers.IO) { TexturePackPreparer.scanFiles(File(installDir)) }
        if (files.isEmpty()) return ""
        return TexturePackSync.withRetry {
            client.packRegister(PackRegisterRequest(platform, storeId, files, keys))
        }.fingerprint
    }

    companion object {
        private const val KEY_APP_ID = "appId"
        private const val KEY_UPLOAD = "upload"
        private const val MAX_ATTEMPTS = 3
        private const val SWEEP_INTERVAL_HOURS = 12L

        private fun constraints(): Constraints {
            val networkType = if (PrefManager.texturePackAllowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            return Constraints.Builder().setRequiredNetworkType(networkType).build()
        }

        private fun enqueue(context: Context, appId: String, upload: Boolean) {
            val request = OneTimeWorkRequestBuilder<TexturePackSyncWorker>()
                .setConstraints(constraints())
                .setInputData(
                    Data.Builder()
                        .putString(KEY_APP_ID, appId)
                        .putBoolean(KEY_UPLOAD, upload)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("texture-pack-sync-$appId", ExistingWorkPolicy.REPLACE, request)
            }.onFailure { Timber.w(it, "could not enqueue texture pack sync for $appId") }
        }

        fun enqueueFullSync(context: Context, appId: String) {
            if (!PrefManager.texturePackEnabled) return
            enqueue(context, appId, upload = true)
        }

        fun enqueueDownloadSync(context: Context, appId: String) {
            if (!PrefManager.texturePackEnabled) return
            enqueue(context, appId, upload = false)
        }

        fun schedule(context: Context) {
            if (!PrefManager.texturePackEnabled) return
            val sweep = OneTimeWorkRequestBuilder<TexturePackSyncWorker>()
                .setConstraints(constraints())
                .build()
            val periodic = PeriodicWorkRequestBuilder<TexturePackSyncWorker>(SWEEP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()
            runCatching {
                val manager = WorkManager.getInstance(context.applicationContext)
                manager.enqueueUniqueWork("texture-pack-sweep-now", ExistingWorkPolicy.REPLACE, sweep)
                manager.enqueueUniquePeriodicWork(
                    "texture-pack-sweep",
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodic,
                )
            }.onFailure { Timber.w(it, "could not schedule texture pack sync") }
        }
    }
}
