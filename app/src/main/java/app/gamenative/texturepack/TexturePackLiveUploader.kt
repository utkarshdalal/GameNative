package app.gamenative.texturepack

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.PowerManager
import android.os.SystemClock
import app.gamenative.PrefManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class TexturePackLiveUploader private constructor(
    private val context: Context,
    private val cacheDir: File,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { TexturePackClient(context) }

    @Volatile private var lastSidecarAt = SystemClock.elapsedRealtime()
    private var nextSlotAt = 0L

    private fun noteEvent(path: String?) {
        if (path != null && path.endsWith(TexturePackSync.SOURCE_SUFFIX)) {
            lastSidecarAt = SystemClock.elapsedRealtime()
        }
    }

    private val observer: FileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(cacheDir, EVENT_MASK) {
            override fun onEvent(event: Int, path: String?) = noteEvent(path)
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(cacheDir.absolutePath, EVENT_MASK) {
            override fun onEvent(event: Int, path: String?) = noteEvent(path)
        }
    }

    private fun begin() {
        runCatching { observer.startWatching() }
            .onFailure { Timber.w(it, "texture pack live uploader could not watch ${cacheDir.path}") }
        scope.launch { loop() }
    }

    private fun end() {
        runCatching { observer.stopWatching() }
        scope.cancel()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (!PrefManager.texturePackEnabled) return
                if (!TexturePackClient.isTransferAllowed(context)) {
                    delay(BACKOFF_MS)
                    continue
                }
                if (thermallyThrottled()) {
                    delay(THERMAL_RECHECK_MS)
                    continue
                }
                val quietFor = SystemClock.elapsedRealtime() - lastSidecarAt
                if (quietFor < QUIET_PERIOD_MS) {
                    delay(QUIET_PERIOD_MS - quietFor)
                    continue
                }
                val sources = TexturePackSync.sourceFiles(cacheDir)
                if (sources.isEmpty()) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                drain(sources)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "texture pack live upload failed")
                delay(BACKOFF_MS)
            }
        }
    }

    private suspend fun drain(sources: List<File>) {
        for (batch in sources.chunked(LOOKUP_BATCH_SIZE)) {
            if (SystemClock.elapsedRealtime() - lastSidecarAt < QUIET_PERIOD_MS) return
            if (thermallyThrottled()) return
            val byKey = batch.associateBy { TexturePackSync.keyOf(it) }
            val response = TexturePackSync.withRetry { client.lookup(byKey.keys.toList()) }
            (response.have + response.pending).forEach { byKey[it]?.delete() }
            val uploaded = TexturePackSync.uploadSources(
                client = client,
                sources = response.want.mapNotNull { byKey[it] },
                parallelism = 1,
                beforeUpload = { bytes -> throttle(bytes) },
            )
            uploaded.forEach { byKey[it]?.delete() }
        }
    }

    private suspend fun throttle(bytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val start = maxOf(now, nextSlotAt)
        nextSlotAt = start + bytes * 1000L / RATE_BYTES_PER_SECOND
        val wait = start - now
        if (wait > 0) delay(wait)
    }

    private fun thermallyThrottled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val EVENT_MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        private const val QUIET_PERIOD_MS = 5_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val THERMAL_RECHECK_MS = 10_000L
        private const val BACKOFF_MS = 30_000L
        private const val RATE_BYTES_PER_SECOND = 2L * 1024L * 1024L
        private const val LOOKUP_BATCH_SIZE = 200

        @Volatile private var active: TexturePackLiveUploader? = null

        @Synchronized
        fun start(context: Context, cacheDir: File) {
            stop()
            if (!PrefManager.texturePackEnabled) return
            if (!TexturePackGate.needsTexturePack(context)) return
            if (!cacheDir.isDirectory) return
            active = try {
                TexturePackLiveUploader(context.applicationContext, cacheDir).also { it.begin() }
            } catch (e: Exception) {
                Timber.w(e, "texture pack live uploader could not start")
                null
            }
        }

        @Synchronized
        fun stop() {
            active?.let { runCatching { it.end() } }
            active = null
        }
    }
}
