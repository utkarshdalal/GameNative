package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/** Android-facing screenshot operations: capture, save, export, open. Delegates file-model to [ScreenshotStore]. */
object ScreenshotManager {
    private const val MIME_PNG = "image/png"
    private const val SHARE_CACHE_DIR = "screenshot-share"

    fun rootDir(context: Context): File =
        ScreenshotStore.resolveRoot(
            internalFilesDir = context.filesDir,
            useExternal = PrefManager.screenshotUseExternal,
            externalPath = PrefManager.screenshotExternalPath,
        )

    fun gameDir(context: Context, appId: String): File =
        ScreenshotStore.gameDir(rootDir(context), appId).apply { mkdirs() }

    fun list(context: Context, appId: String): List<ScreenshotItem> =
        ScreenshotStore.list(rootDir(context), appId)

    /**
     * Capture the game frame from [surfaceView] via PixelCopy (game-only; overlays are sibling views),
     * save as PNG, and report the saved file (or failure) on [onResult], invoked on [scope].
     * [nowMillis] is the capture timestamp (pass System.currentTimeMillis()).
     */
    fun capture(
        surfaceView: SurfaceView,
        appId: String,
        gameName: String,
        context: Context,
        nowMillis: Long,
        scope: CoroutineScope,
        onResult: (Result<File>) -> Unit,
    ) {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            onResult(Result.failure(IllegalStateException("Game surface not ready")))
            return
        }
        // Allocation can throw OutOfMemoryError for a large surface; route it through onResult
        // (rather than crashing the caller) like every other capture failure.
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            onResult(Result.failure(t))
            return
        }
        val thread = HandlerThread("screenshot-pixelcopy").apply { start() }
        val handler = Handler(thread.looper)
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult != PixelCopy.SUCCESS) {
                        bitmap.recycle()
                        thread.quitSafely()
                        scope.launch {
                            onResult(Result.failure(IllegalStateException("PixelCopy failed: $copyResult")))
                        }
                        return@request
                    }
                    // Save + recycle on this PixelCopy callback thread (a background HandlerThread, so
                    // file IO is fine here). Done before delivery so the bitmap is always freed even if
                    // `scope` was cancelled (composition torn down) before the result is delivered.
                    val saved = runCatching {
                        val dir = gameDir(context, appId)
                        // Atomically claim a filename so two captures in the same second can't clobber.
                        var seq = 0
                        var out = File(dir, ScreenshotStore.fileNameFor(gameName, nowMillis))
                        while (!out.createNewFile()) {
                            seq++
                            out = File(dir, ScreenshotStore.fileNameFor(gameName, nowMillis, seq))
                        }
                        FileOutputStream(out).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        }
                        out
                    }
                    bitmap.recycle()
                    thread.quitSafely()
                    scope.launch { onResult(saved) }
                },
                handler,
            )
        } catch (t: Throwable) {
            thread.quitSafely()
            bitmap.recycle()
            onResult(Result.failure(t))
        }
    }

    fun delete(item: ScreenshotItem): Boolean = item.file.delete()

    /** Delete every screenshot for [appId]. Returns the number of files removed. */
    fun deleteAll(context: Context, appId: String): Int =
        list(context, appId).count { it.file.delete() }

    /** Open the screenshot in an external viewer/gallery via ACTION_VIEW. May throw for files outside app dirs. */
    fun openWithGallery(context: Context, item: ScreenshotItem) {
        val uri = uriFor(context, item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_PNG)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Share the screenshot via ACTION_SEND. May throw for files outside app dirs (caller should retry via a cache copy). */
    fun shareScreenshot(context: Context, item: ScreenshotItem) {
        val uri = uriFor(context, item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Copy the screenshot into the public Downloads collection. */
    fun exportToDownloads(context: Context, item: ScreenshotItem): Result<Unit> = runCatching {
        val displayName = item.file.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_PNG)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads entry")
            try {
                resolver.openOutputStream(uri).use { out ->
                    requireNotNull(out) { "Null output stream" }
                    item.file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                // Don't leave an invisible IS_PENDING=1 row behind to accumulate across retries.
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            // No MediaStore de-duplication here, so don't clobber an existing same-named file.
            val name = ScreenshotStore.uniqueDownloadName(displayName) { File(downloads, it).exists() }
            val dest = File(downloads, name)
            item.file.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        }
        Timber.i("Exported screenshot to Downloads: $displayName")
    }

    /** Copy [item] into the app cache (covered by the FileProvider cache-path) for sharing files outside app dirs. */
    fun cacheCopyFor(context: Context, item: ScreenshotItem): ScreenshotItem {
        // Dedicated subdir we own, so prior copies can be pruned without touching other cache files.
        val shareDir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        // Prune previous share copies (prune-on-use): by the next share, the receiving app is done
        // reading the earlier URI, so external-folder screenshot copies don't accumulate in the cache.
        shareDir.listFiles()?.forEach { it.delete() }
        val dest = File(shareDir, item.file.name)
        item.file.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        return ScreenshotItem(dest, item.dateTakenMillis)
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
}
