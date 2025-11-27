package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object UpdateInstaller {
    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val apkFileName = "gamenative-v$versionName.apk"
            val destFile = File(context.cacheDir, apkFileName)

            // Extract filename from URL for fetchFileWithFallback
            // The URL should be like: https://downloads.gamenative.app/gamenative-v0.5.3.apk
            val fileName = downloadUrl.substringAfterLast("https://downloads.gamenative.app/")

            Timber.i("Downloading update: $fileName from URL: $downloadUrl")
            Timber.i("Saving to: ${destFile.absolutePath}")

            // Use the existing fetchFileWithFallback method which handles fallback URLs
            SteamService.fetchFileWithFallback(
                fileName = fileName,
                dest = destFile,
                context = context,
                onProgress = onProgress
            )

            // Verify the file exists and has content
            if (!destFile.exists()) {
                Timber.e("Downloaded file does not exist: ${destFile.absolutePath}")
                return@withContext false
            }

            val fileSize = destFile.length()
            if (fileSize == 0L) {
                Timber.e("Downloaded file is empty: ${destFile.absolutePath}")
                return@withContext false
            }

            Timber.i("Download complete: ${destFile.absolutePath}, size: $fileSize bytes")

            // Install the APK
            withContext(Dispatchers.Main) {
                installApk(context, destFile)
            }

            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Error downloading/installing update")
            return@withContext false
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            // Verify file exists before attempting installation
            if (!apkFile.exists()) {
                Timber.e("APK file does not exist: ${apkFile.absolutePath}")
                return
            }

            Timber.i("Installing APK from: ${apkFile.absolutePath}, size: ${apkFile.length()} bytes")

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        apkFile
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Error getting FileProvider URI")
                    return
                }
            } else {
                // Use file:// URI for older versions
                Uri.fromFile(apkFile)
            }

            Timber.i("FileProvider URI: $uri")
            Timber.i("File absolute path: ${apkFile.absolutePath}")
            Timber.i("File exists: ${apkFile.exists()}, size: ${apkFile.length()}")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Grant URI permissions to the package installer
            val resInfoList = context.packageManager.queryIntentActivities(intent, 0)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(intent)
            Timber.i("Install intent launched successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error launching install intent")
        }
    }
}

