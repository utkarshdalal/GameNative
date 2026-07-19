package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import app.gamenative.service.SteamService
import timber.log.Timber
import java.io.File

/**
 * Installs/launches the native Android build of a game (Steam Frame / Lepton depot),
 * bypassing the Wine/Winlator container entirely.
 */
object AndroidGameLauncher {

    private const val TAG = "AndroidGameLauncher"

    private fun findApkFile(gameId: Int): File? {
        val dir = File(SteamService.getAppDirPath(gameId))
        if (!dir.exists()) return null
        return dir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
    }

    private fun findObbFiles(gameId: Int): List<File> {
        val dir = File(SteamService.getAppDirPath(gameId))
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }.toList()
    }

    private fun getApkPackageName(context: Context, apk: File): String? {
        return try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not read package info from ${apk.absolutePath}")
            null
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Best-effort OBB placement under /Android/obb/<package>/. On Android 11+ this requires
     * broad ("All files access") storage permission GameNative doesn't currently request, so
     * this silently no-ops there — games that need their OBB will fail to start until that's added.
     */
    private fun copyObbFiles(gameId: Int, packageName: String) {
        val obbFiles = findObbFiles(gameId)
        if (obbFiles.isEmpty()) return
        try {
            val obbRoot = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
            obbRoot.mkdirs()
            obbFiles.forEach { src -> src.copyTo(File(obbRoot, src.name), overwrite = true) }
            Timber.tag(TAG).i("Copied ${obbFiles.size} OBB file(s) to ${obbRoot.absolutePath}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not place OBB files for $packageName (needs broader storage access on Android 11+)")
        }
    }

    /** Package name of this game's downloaded .apk, or null if none is on disk / unreadable. */
    fun resolvePackageName(context: Context, gameId: Int): String? {
        val apk = findApkFile(gameId) ?: return null
        return getApkPackageName(context, apk)
    }

    /** Whether the Android app for this game is actually installed on the system (not just downloaded). */
    fun isGameInstalled(context: Context, gameId: Int): Boolean {
        val packageName = resolvePackageName(context, gameId) ?: return false
        return isPackageInstalled(context, packageName)
    }

    sealed class Result {
        data object Launched : Result()
        data object InstallStarted : Result()
        data object Failed : Result()
    }

    /**
     * If the downloaded APK is already installed, launches it directly. Otherwise kicks off
     * the system install flow (user confirmation required) — the caller must ask the user to
     * press Play again once that finishes; there is no reliable synchronous "wait for install".
     */
    fun installAndLaunch(context: Context, gameId: Int): Result {
        val apk = findApkFile(gameId)
        if (apk == null) {
            Timber.tag(TAG).e("No APK found on disk for game $gameId")
            return Result.Failed
        }
        val packageName = getApkPackageName(context, apk)
        if (packageName == null) {
            Timber.tag(TAG).e("Could not resolve package name from ${apk.absolutePath}")
            return Result.Failed
        }
        if (!isPackageInstalled(context, packageName)) {
            copyObbFiles(gameId, packageName)
            UpdateInstaller.installApk(context, apk)
            return Result.InstallStarted
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.Failed
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Result.Launched
    }

    /**
     * Prompts the system uninstall dialog for the Android package matching this game, if one
     * is actually installed. Must be called before the downloaded .apk is deleted from disk
     * (needed to resolve the package name). This is a separate step from GameNative forgetting
     * the game — the installed app is a distinct entity on the system that Android requires
     * explicit user confirmation to remove.
     */
    fun requestUninstall(context: Context, gameId: Int) {
        val apk = findApkFile(gameId)
        if (apk == null) {
            Timber.tag(TAG).w("requestUninstall: no APK found on disk for game $gameId, skipping system uninstall")
            return
        }
        val packageName = getApkPackageName(context, apk)
        if (packageName == null) {
            Timber.tag(TAG).w("requestUninstall: could not resolve package name from ${apk.absolutePath}")
            return
        }
        if (!isPackageInstalled(context, packageName)) {
            Timber.tag(TAG).w("requestUninstall: package $packageName is not currently installed, skipping")
            return
        }
        Timber.tag(TAG).i("requestUninstall: prompting system uninstall for $packageName")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
