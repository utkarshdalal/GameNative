package app.gamenative.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.core.content.ContextCompat
import timber.log.Timber

object PermissionManager {
    private const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    private val legacyStoragePermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    fun hasStorageAccessForPath(context: Context, path: String): Boolean {
        val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
            !path.contains(context.dataDir.path)
        if (!isOutsideSandbox) {
            return true
        }
        return hasStorageAccess(context)
    }

    fun requestStorageAccess(
        context: Context,
        legacyPermissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>?,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess(context)
        } else {
            legacyPermissionLauncher?.launch(legacyStoragePermissions)
            true
        }
    }

    fun requestAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Timber.tag("PermissionManager").e(e, "Failed to open settings for all files access")
            return try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Timber.tag("PermissionManager").e(e2, "Failed to open app settings")
                false
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            NOTIFICATION_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(
        context: Context,
        launcher: ManagedActivityResultLauncher<String, Boolean>?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (hasNotificationPermission(context)) return true
        launcher?.launch(NOTIFICATION_PERMISSION)
        return true
    }
}
