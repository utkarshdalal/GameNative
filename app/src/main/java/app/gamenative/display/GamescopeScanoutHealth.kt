package app.gamenative.display

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.gamenative.PrefManager
import java.io.File
import timber.log.Timber

object GamescopeScanoutHealth {
    private fun marker(context: Context): File = File(context.filesDir, "gamescope/.session-active")

    fun markSessionStart(context: Context) {
        runCatching {
            val file = marker(context)
            file.parentFile?.mkdirs()
            file.writeText(System.currentTimeMillis().toString())
        }.onFailure { Timber.w(it, "Could not write the gamescope session marker") }
    }

    fun markSessionEnd(context: Context) {
        runCatching { marker(context).delete() }
    }

    fun checkLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = marker(context)
        if (!file.exists()) return
        try {
            val startedAt = file.readText().trim().toLongOrNull() ?: 0L
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
            val exit = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > startedAt }
                .maxByOrNull { it.timestamp }
                ?: return
            when (exit.reason) {
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                -> {
                    PrefManager.gamescopeRgbaScanout = true
                    Timber.w("Gamescope session ended by exit reason ${exit.reason}; using the RGBA scan-out layout")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read the last exit reason")
        } finally {
            file.delete()
        }
    }

    fun forceRgba(context: Context): Boolean = PrefManager.gamescopeRgbaScanout
}
