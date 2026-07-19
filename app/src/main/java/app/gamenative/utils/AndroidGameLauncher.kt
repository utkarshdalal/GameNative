package app.gamenative.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.gamenative.service.SteamService
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume

/**
 * Installs/launches the native Android build of a game (Steam Frame / Lepton depot),
 * bypassing the Wine/Winlator container entirely.
 */
object AndroidGameLauncher {

    private const val TAG = "AndroidGameLauncher"

    // Depot content for an Android build is expected to be shallow (apk + an obb/ folder at
    // most); bound the walk so a huge, deeply-nested Workshop/DLC tree can't make this slow.
    private const val MAX_SEARCH_DEPTH = 6

    private fun findApkFile(gameId: Int): File? {
        val dir = File(SteamService.getAppDirPath(gameId))
        if (!dir.exists()) return null
        return dir.walkTopDown().maxDepth(MAX_SEARCH_DEPTH)
            .firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
    }

    private fun findObbFiles(gameId: Int): List<File> {
        val dir = File(SteamService.getAppDirPath(gameId))
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().maxDepth(MAX_SEARCH_DEPTH)
            .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }.toList()
    }

    private fun getApkPackageName(context: Context, apk: File): String? {
        return try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not read package info from ${apk.absolutePath}")
            null
        }
    }

    /** Unwraps a possibly-wrapped Context (e.g. a ContextWrapper) to find the backing Activity. */
    private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
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

    private const val STAGING_DIR_NAME = "android_game_installs"

    /**
     * Copies the downloaded .apk into the app's own cache dir so FileProvider only ever needs to
     * grant access to a narrow, app-controlled location (already covered by the existing
     * cache-path entry) instead of the whole install-directory tree.
     */
    private fun stageApkForInstall(context: Context, gameId: Int, apk: File): File? {
        return try {
            val stagingDir = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }
            val staged = File(stagingDir, "$gameId.apk")
            apk.copyTo(staged, overwrite = true)
            staged
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not stage APK for install from ${apk.absolutePath}")
            null
        }
    }

    /**
     * Removes this game's staged install copy, if any. Each staged .apk is only overwritten by a
     * later install of the *same* game, so without this, every distinct Android game ever
     * installed leaves a permanent copy behind in the cache dir. Safe to call any time, including
     * when nothing was ever staged.
     */
    fun cleanupStagedApk(context: Context, gameId: Int) {
        val staged = File(File(context.cacheDir, STAGING_DIR_NAME), "$gameId.apk")
        if (staged.exists() && !staged.delete()) {
            Timber.tag(TAG).w("Could not delete staged APK ${staged.absolutePath}")
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
            val staged = stageApkForInstall(context, gameId, apk) ?: return Result.Failed
            return if (UpdateInstaller.installApk(context, staged)) Result.InstallStarted else Result.Failed
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.Failed
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Result.Launched
    }

    /**
     * Prompts the system uninstall dialog for the Android package matching this game (if one is
     * actually installed) and suspends until the dialog is dismissed. Uses
     * ACTION_UNINSTALL_PACKAGE + EXTRA_RETURN_RESULT so the result (confirmed vs. cancelled) comes
     * back immediately as an activity result, instead of waiting on an ACTION_PACKAGE_REMOVED
     * broadcast that a cancelled dialog never sends — that previously left the caller's "deleting"
     * UI stuck for a long fixed timeout on every cancel.
     *
     * Returns true when it's safe for the caller to delete the downloaded .apk / GameNative's own
     * install record: either nothing was installed to begin with, or the system confirmed removal.
     * Returns false if the user cancelled (or context isn't an Activity capable of launching for a
     * result), so the caller must NOT delete anything in that case — otherwise the app would be
     * left installed with no way left to resolve its package name.
     *
     * Must be called before the downloaded .apk is deleted from disk (needed to resolve the
     * package name in the first place).
     */
    suspend fun requestUninstall(context: Context, gameId: Int): Boolean {
        val apk = findApkFile(gameId)
        if (apk == null) {
            Timber.tag(TAG).w("requestUninstall: no APK found on disk for game $gameId, skipping system uninstall")
            return true
        }
        val packageName = getApkPackageName(context, apk)
        if (packageName == null) {
            Timber.tag(TAG).w("requestUninstall: could not resolve package name from ${apk.absolutePath}")
            return true
        }
        if (!isPackageInstalled(context, packageName)) {
            Timber.tag(TAG).i("requestUninstall: package $packageName is not currently installed, nothing to do")
            return true
        }
        val activity = context.findActivity()
        if (activity == null) {
            Timber.tag(TAG).e("requestUninstall: context is not a ComponentActivity, cannot prompt for a result")
            return false
        }

        Timber.tag(TAG).i("requestUninstall: prompting system uninstall for $packageName")
        return suspendCancellableCoroutine { cont ->
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Intent>
            launcher = activity.activityResultRegistry.register(
                "android_game_uninstall_$gameId",
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                launcher.unregister()
                if (cont.isActive) cont.resume(result.resultCode == Activity.RESULT_OK)
            }
            cont.invokeOnCancellation { runCatching { launcher.unregister() } }

            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            try {
                launcher.launch(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Could not launch system uninstall for $packageName")
                launcher.unregister()
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
