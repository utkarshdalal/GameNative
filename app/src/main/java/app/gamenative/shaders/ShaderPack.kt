package app.gamenative.shaders

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * On-demand shader pack: a single filtered extraction of the libretro/slang-shaders
 * tarball into {@code filesDir/retroarch_pack}. Nothing ships in the APK — the pack is
 * downloaded only when the user asks for shaders, and only the files any preset needs
 * (the catalog's dependency-closure union) are written to disk.
 *
 * The pack preserves the repo-root-relative layout, so librashader's own relative
 * resolution of `shaderN`, `#include`, `#reference` and texture paths (including
 * cross-folder references like `../../crt/shaders/...`) works unchanged.
 */
class ShaderPack(context: Context, private val catalogCommit: String = "") {

    private val appContext = context.applicationContext

    /** Cache root for downloaded shader files (repo-root-relative layout preserved). */
    val packDir: File get() = File(appContext.filesDir, "retroarch_pack")

    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelRequested = false

    companion object {
        /**
         * Per-preset on-demand download (user decision 2026-08-12): NO pack is downloaded —
         * each preset fetches only its own dependency closure from the pinned commit, file
         * by file (typically a few KB; shared files are reused from the cache). The commit
         * is pinned so paths can never drift from the catalog.
         */
        fun rawUrlFor(commit: String, path: String): String =
            okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("raw.githubusercontent.com")
                .addPathSegment("libretro")
                .addPathSegment("slang-shaders")
                .addPathSegment(commit)
                .addPathSegments(path)
                .build()
                .toString()
    }

    /** True when every file of the preset's closure is already in the cache. */
    fun isLocal(preset: ShaderPreset): Boolean = isPresetLocal(preset, packDir)

    /** Absolute path of the preset inside the cache, or null when not fully local. */
    fun presetFile(preset: ShaderPreset): File? {
        if (!isLocal(preset)) return null
        val file = File(packDir, preset.path)
        return file.takeIf { it.isFile }
    }

    /**
     * Downloads ONLY the missing files of [preset]'s closure (spec/user decision
     * 2026-08-12: nothing is downloaded by default — only what the user picks; already
     * cached files shared with other presets are reused). Reports byte progress via
     * [onProgress] (downloaded, total). A preset is "local" only when its whole closure
     * is present, so a partial failure leaves the preset in the cloud state for retry.
     *
     * Pre-checks: free space (worst case = the preset's closure) and metered-network
     * disclosure ([allowMetered] is the user's explicit consent). Typed failures:
     * [PackNoSpaceException], [PackMeteredException], [PackCancelledException].
     */
    suspend fun downloadPreset(
        preset: ShaderPreset,
        allowMetered: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val missing = missingFiles(preset, packDir)
            if (missing.isEmpty()) return@withContext Result.success(Unit)

            val totalBytes = preset.bytes.coerceAtLeast(1)
            val required = PackPrechecks.requiredFreeBytes(totalBytes)
            val available = StatFs(appContext.filesDir.absolutePath).availableBytes
            if (!PackPrechecks.hasEnoughSpace(totalBytes, available)) {
                throw PackNoSpaceException(required, available)
            }
            if (!allowMetered && PackPrechecks.needsMeteredConfirmation(isMeteredNetwork())) {
                throw PackMeteredException(totalBytes)
            }

            cancelRequested = false
            val commit = catalogCommit.ifBlank { "refs/heads/master" }
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            var downloaded = 0L
            val writtenNew = mutableListOf<File>()
            try {
                for (rel in missing) {
                    // Cancel requested between files (not only mid-call): stop cleanly.
                    if (cancelRequested) throw PackCancelledException()
                    val target = File(packDir, rel)
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    val request = Request.Builder().url(rawUrlFor(commit, rel)).build()
                    val call = client.newCall(request)
                    activeCall = call
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code} for $rel")
                            }
                            val body = response.body ?: throw IOException("empty body for $rel")
                            FileOutputStream(tmp).use { out ->
                                body.byteStream().use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        downloaded += n
                                        onProgress(downloaded, totalBytes)
                                    }
                                }
                            }
                            if (!tmp.renameTo(target)) {
                                throw IOException("could not move $rel into place")
                            }
                            writtenNew.add(target)
                        }
                    } catch (e: IOException) {
                        tmp.delete()
                        if (cancelRequested) throw PackCancelledException()
                        throw e
                    } finally {
                        activeCall = null
                    }
                }
            } catch (e: Throwable) {
                // Roll back only files written by THIS attempt; previously cached files
                // (shared with other presets) stay — the preset just stays "in the cloud".
                writtenNew.forEach { it.delete() }
                throw e
            }
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e !is PackCancelledException && e !is PackNoSpaceException && e !is PackMeteredException) {
                Timber.e(e, "ShaderPack: preset download failed")
            }
            Result.failure(e)
        }
    }

    /**
     * Aborts an in-flight preset download and drops the partial file (spec §4.2.4
     * adapted to per-preset fetches). The in-flight call fails with [PackCancelledException].
     */
    fun cancel() {
        cancelRequested = true
        activeCall?.cancel()
    }

    /** Removes the whole shader cache (files stay uninstalled; catalog remains browsable). */
    fun clear() {
        packDir.deleteRecursively()
    }

    private fun isMeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * Files of [preset]'s closure that are not yet in the cache. Pure JVM — testable.
 * When the catalog has no deps for a preset (shouldn't happen after re-sync), the
 * preset file itself is the whole closure.
 */
fun missingFiles(preset: ShaderPreset, packDir: File): List<String> {
    val closure = preset.deps.ifEmpty { listOf(preset.path) }
    return closure.filter { rel -> !File(packDir, rel).isFile }
}

/** True when every file of [preset]'s closure is cached (and the preset is usable). */
fun isPresetLocal(preset: ShaderPreset, packDir: File): Boolean {
    if (preset.broken) return false
    return missingFiles(preset, packDir).isEmpty()
}


/** Pure pre-check decisions (spec §4.2.2/§4.2.3, adapted to per-preset fetches) — JVM-testable. */
object PackPrechecks {

    /** Headroom beyond tmp + final file: downloads need roughly 2× the closure size on disk. */
    const val HEADROOM_BYTES = 16L * 1024 * 1024

    fun requiredFreeBytes(packBytes: Long): Long = packBytes * 2 + HEADROOM_BYTES

    fun hasEnoughSpace(packBytes: Long, availableBytes: Long): Boolean =
        availableBytes >= requiredFreeBytes(packBytes)

    /** Metered networks require an explicit size disclosure before any byte moves. */
    fun needsMeteredConfirmation(isActiveNetworkMetered: Boolean): Boolean = isActiveNetworkMetered
}

/** Not enough free space for the preset's closure + headroom (spec §4.2.2). */
class PackNoSpaceException(required: Long, available: Long) :
    IOException("not enough space: need ${required}B, have ${available}B")

/** Active network is metered and the user has not confirmed the download (spec §4.2.3). */
class PackMeteredException(val packBytes: Long) :
    IOException("metered network requires explicit confirmation")

/** User cancelled the in-flight download (spec §4.2.4) — a clean stop, not an error. */
class PackCancelledException : IOException("shader download cancelled")
